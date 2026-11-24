package com.example.engine

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.example.data.WeblioRepository
import com.example.data.db.AdBlockRuleEntity
import com.example.data.db.FilterRuleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class BlockedEvent(
    val url: String,
    val host: String,
    val matchedRule: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)

class AdBlockEngine(private val repository: WeblioRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val networkBlockRules = CopyOnWriteArrayList<AdBlockRuleEntity>()
    private val networkAllowRules = CopyOnWriteArrayList<AdBlockRuleEntity>()
    private val cosmeticRules = CopyOnWriteArrayList<AdBlockRuleEntity>()
    private val dnsBlockDomains = CopyOnWriteArrayList<String>()

    private val _recentBlocked = MutableStateFlow<List<BlockedEvent>>(emptyList())
    val recentBlocked: StateFlow<List<BlockedEvent>> = _recentBlocked.asStateFlow()

    private val _sessionBlockedCount = MutableStateFlow(0L)
    val sessionBlockedCount: StateFlow<Long> = _sessionBlockedCount.asStateFlow()

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    var globalEnabled: Boolean = true

    @Volatile
    var trackerProtectionEnabled: Boolean = true

    @Volatile
    var thirdPartyBlockingEnabled: Boolean = false

    init {
        scope.launch {
            repository.observeAdBlockRules().collect { rules ->
                val active = rules.filter { it.enabled }
                networkBlockRules.clear()
                networkAllowRules.clear()
                cosmeticRules.clear()
                dnsBlockDomains.clear()
                active.forEach { rule ->
                    when (rule.ruleType) {
                        FilterRuleType.NETWORK_BLOCK.name -> networkBlockRules.add(rule)
                        FilterRuleType.NETWORK_ALLOW.name -> networkAllowRules.add(rule)
                        FilterRuleType.COSMETIC_HIDE.name -> cosmeticRules.add(rule)
                        FilterRuleType.DNS_BLOCK.name -> dnsBlockDomains.add(rule.pattern.lowercase())
                    }
                }
            }
        }
    }

    /**
     * Real network request interception called from WebViewClient.shouldInterceptRequest.
     * Returns an empty HTTP 204/plain response if blocked, or null to proceed normally.
     */
    fun interceptRequest(
        request: WebResourceRequest,
        pageHost: String?,
        siteAdBlockEnabled: Boolean,
        onBlocked: ((BlockedEvent) -> Unit)? = null
    ): WebResourceResponse? {
        if (!globalEnabled || !siteAdBlockEnabled) return null
        val url = request.url?.toString() ?: return null
        val reqHost = request.url.host?.lowercase() ?: return null

        // Do not block the main document navigation to the user's explicit host unless whitelisted check fails
        if (request.isForMainFrame && pageHost == reqHost) return null

        // Check whitelist rules first (@@||domain.com^ or domain)
        for (allow in networkAllowRules) {
            if (matchesAdBlockPattern(url, reqHost, allow.pattern)) {
                return null
            }
        }

        // Check third-party strict blocking if enabled
        if (thirdPartyBlockingEnabled && !request.isForMainFrame && !pageHost.isNullOrBlank()) {
            if (!isSameRegistrableDomain(reqHost, pageHost.lowercase())) {
                if (looksLikeTrackerOrScript(url)) {
                    val event = BlockedEvent(url, reqHost, "3P-Strict-Block", "Third-Party")
                    recordBlocked(event, null)
                    onBlocked?.invoke(event)
                    return emptyBlockedResponse()
                }
            }
        }

        // Check DNS blocklist domains
        for (domain in dnsBlockDomains) {
            if (reqHost == domain || reqHost.endsWith(".$domain")) {
                val event = BlockedEvent(url, reqHost, "DNS:$domain", "DNS Blocklist")
                recordBlocked(event, null)
                onBlocked?.invoke(event)
                return emptyBlockedResponse()
            }
        }

        // Check EasyList / custom network filter rules
        for (rule in networkBlockRules) {
            if (matchesAdBlockPattern(url, reqHost, rule.pattern)) {
                val category = if (trackerProtectionEnabled &&
                    (url.contains("analytics", true) || url.contains("pixel", true) || url.contains("track", true))
                ) "Tracker" else "Ad"
                val event = BlockedEvent(url, reqHost, rule.pattern, category)
                recordBlocked(event, rule.id)
                onBlocked?.invoke(event)
                return emptyBlockedResponse()
            }
        }

        return null
    }

    fun isDnsDomainBlocked(domain: String): Boolean {
        val clean = domain.trim().lowercase().trimEnd('.')
        if (!globalEnabled) return false
        return dnsBlockDomains.any { blocked -> clean == blocked || clean.endsWith(".$blocked") }
    }

    fun buildCosmeticCss(siteAdBlockEnabled: Boolean): String {
        if (!globalEnabled || !siteAdBlockEnabled) return ""
        val selectors = cosmeticRules
            .mapNotNull { rule ->
                val p = rule.pattern.trim()
                if (p.startsWith("##")) p.removePrefix("##").trim() else null
            }
            .filter { sel ->
                sel.isNotBlank() &&
                    sel.length <= 240 &&
                    !sel.contains('{') &&
                    !sel.contains('}') &&
                    !sel.contains(';') &&
                    !sel.contains('<') &&
                    !sel.contains('>') &&
                    !sel.contains("@import", ignoreCase = true) &&
                    !sel.contains("url(", ignoreCase = true) &&
                    !sel.contains("expression(", ignoreCase = true)
            }
        if (selectors.isEmpty()) return ""
        return selectors.joinToString(separator = ", ") + " { display: none !important; visibility: hidden !important; }"
    }

    /**
     * Strips common tracking query params (utm_*, fbclid, gclid, mc_cid) when tracker protection is active.
     */
    fun cleanTrackingUrl(rawUrl: String): String {
        if (!trackerProtectionEnabled) return rawUrl
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        if (uri.scheme != "http" && uri.scheme != "https") return rawUrl
        val trackingKeys = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "mc_cid", "mc_eid", "igshid", "yclid"
        )
        val names = uri.queryParameterNames
        if (names.none { it.lowercase() in trackingKeys }) return rawUrl
        val builder = uri.buildUpon().clearQuery()
        for (name in names) {
            if (name.lowercase() !in trackingKeys) {
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        return builder.build().toString()
    }

    /**
     * Real DoH (DNS-over-HTTPS RFC 8484 wireformat) query verification for diagnostics & VPN DNS forwarding.
     */
    fun queryDoh(dohEndpoint: String, dnsWireQuery: ByteArray): ByteArray? {
        if (dohEndpoint.isBlank() || !dohEndpoint.startsWith("https://", ignoreCase = true)) return null
        return runCatching {
            val body = dnsWireQuery.toRequestBody("application/dns-message".toMediaType())
            val req = Request.Builder()
                .url(dohEndpoint)
                .post(body)
                .header("Accept", "application/dns-message")
                .build()
            dohClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun recordBlocked(event: BlockedEvent, ruleId: Long?) {
        _sessionBlockedCount.update { it + 1 }
        _recentBlocked.update { list -> (listOf(event) + list).take(60) }
        if (ruleId != null) {
            scope.launch { repository.incrementRuleHit(ruleId) }
        }
    }

    private fun emptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "Blocked by WEBLIO Shield",
            mapOf("X-Weblio-Blocked" to "true"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    companion object {
        fun matchesAdBlockPattern(url: String, host: String, rawPattern: String): Boolean {
            val pattern = rawPattern.trim()
            if (pattern.isEmpty() || pattern.startsWith("!") || pattern.startsWith("##")) return false
            val clean = if (pattern.startsWith("@@")) pattern.removePrefix("@@") else pattern

            if (clean.startsWith("||")) {
                val body = clean.removePrefix("||")
                val separatorIdx = body.indexOfAny(charArrayOf('^', '/'))
                val domainPart = (if (separatorIdx >= 0) body.substring(0, separatorIdx) else body).lowercase()
                val pathPart = if (separatorIdx >= 0) body.substring(separatorIdx).replace("^", "") else ""
                val hostMatches = host == domainPart || host.endsWith(".$domainPart")
                if (!hostMatches) return false
                return pathPart.isEmpty() || url.contains(pathPart, ignoreCase = true)
            }

            if (clean.contains("*")) {
                val normalized = clean.replace(Regex("\\*{2,}"), "*")
                if (normalized.count { it == '*' } > 12) return false
                val regexStr = normalized.split('*').joinToString(".*") { Regex.escape(it) }
                return runCatching { Regex(regexStr, RegexOption.IGNORE_CASE).containsMatchIn(url) }.getOrDefault(false)
            }

            return url.contains(clean, ignoreCase = true)
        }

        private fun isSameRegistrableDomain(hostA: String, hostB: String): Boolean {
            val partsA = hostA.split('.')
            val partsB = hostB.split('.')
            val rootA = if (partsA.size >= 2) partsA.takeLast(2).joinToString(".") else hostA
            val rootB = if (partsB.size >= 2) partsB.takeLast(2).joinToString(".") else hostB
            return rootA == rootB
        }

        private fun looksLikeTrackerOrScript(url: String): Boolean {
            val lower = url.lowercase()
            return lower.endsWith(".js") ||
                lower.contains("analytics") ||
                lower.contains("track") ||
                lower.contains("pixel") ||
                lower.contains("ads") ||
                lower.contains("beacon")
        }
    }
}
