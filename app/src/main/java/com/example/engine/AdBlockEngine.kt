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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class BlockedEvent(
    val url: String,
    val host: String,
    val matchedRule: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)

class AdBlockEngine(private val repository: WeblioRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class CompiledRule(
        val entity: AdBlockRuleEntity,
        val pathPart: String = "",
        val regex: Regex? = null,
        val literalNeedle: String? = null
    )

    private class RuleSnapshot(
        val signature: Int,
        val dnsBlockDomains: Set<String> = emptySet(),
        val domainBlockRules: Map<String, List<CompiledRule>> = emptyMap(),
        val generalBlockRules: List<CompiledRule> = emptyList(),
        val domainAllowRules: Map<String, List<CompiledRule>> = emptyMap(),
        val generalAllowRules: List<CompiledRule> = emptyList(),
        val cosmeticCss: String = ""
    )

    @Volatile
    private var snapshot = RuleSnapshot(signature = 0)

    private val pendingRuleHits = ConcurrentHashMap<Long, AtomicLong>()
    private val pendingUiBlockedCount = AtomicLong(0L)
    private val pendingRecentEvents = ConcurrentLinkedDeque<BlockedEvent>()
    private val uiFlushScheduled = AtomicBoolean(false)

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
            runCatching {
                repository.observeAdBlockRules().collect { rules ->
                    val active = rules.filter { it.enabled }
                    val sig = computeRuleStructureSignature(active)
                    if (sig != snapshot.signature || (active.isEmpty() && snapshot.signature != 0)) {
                        snapshot = buildSnapshot(active, sig)
                    }
                }
            }
        }
        // Flush rule hit counts periodically instead of writing Room on every blocked subresource
        scope.launch {
            while (isActive) {
                delay(8_000L)
                runCatching { flushRuleHits() }
            }
        }
    }

    private suspend fun flushRuleHits() {
        if (pendingRuleHits.isEmpty()) return
        val keys = pendingRuleHits.keys().toList()
        for (ruleId in keys) {
            val delta = pendingRuleHits[ruleId]?.getAndSet(0L) ?: 0L
            if (delta > 0L) {
                runCatching { repository.incrementRuleHitsBy(ruleId, delta) }
            }
        }
    }

    private fun computeRuleStructureSignature(active: List<AdBlockRuleEntity>): Int {
        var h = active.size
        for (r in active) {
            h = 31 * h + r.id.hashCode()
            h = 31 * h + r.pattern.hashCode()
            h = 31 * h + r.ruleType.hashCode()
        }
        return h
    }

    private fun buildSnapshot(active: List<AdBlockRuleEntity>, signature: Int): RuleSnapshot {
        val dnsSet = HashSet<String>()
        val blockByDomain = HashMap<String, MutableList<CompiledRule>>()
        val blockGeneral = ArrayList<CompiledRule>()
        val allowByDomain = HashMap<String, MutableList<CompiledRule>>()
        val allowGeneral = ArrayList<CompiledRule>()
        val cosmeticSelectors = ArrayList<String>()

        for (rule in active) {
            when (rule.ruleType) {
                FilterRuleType.DNS_BLOCK.name -> {
                    val d = rule.pattern.trim().lowercase().trimEnd('.')
                    if (d.isNotEmpty()) dnsSet.add(d)
                }
                FilterRuleType.NETWORK_ALLOW.name -> {
                    indexNetworkRule(rule, allowByDomain, allowGeneral)
                }
                FilterRuleType.NETWORK_BLOCK.name -> {
                    indexNetworkRule(rule, blockByDomain, blockGeneral)
                }
                FilterRuleType.COSMETIC_HIDE.name -> {
                    val p = rule.pattern.trim()
                    if (p.startsWith("##")) {
                        val sel = p.removePrefix("##").trim()
                        if (isSafeCosmeticSelector(sel)) cosmeticSelectors.add(sel)
                    }
                }
            }
        }

        val css = if (cosmeticSelectors.isEmpty()) "" else {
            cosmeticSelectors.joinToString(separator = ", ") +
                " { display: none !important; visibility: hidden !important; }"
        }

        return RuleSnapshot(
            signature = signature,
            dnsBlockDomains = dnsSet,
            domainBlockRules = blockByDomain,
            generalBlockRules = blockGeneral,
            domainAllowRules = allowByDomain,
            generalAllowRules = allowGeneral,
            cosmeticCss = css
        )
    }

    private fun indexNetworkRule(
        rule: AdBlockRuleEntity,
        byDomain: HashMap<String, MutableList<CompiledRule>>,
        general: ArrayList<CompiledRule>
    ) {
        val pattern = rule.pattern.trim()
        if (pattern.isEmpty() || pattern.startsWith("!") || pattern.startsWith("##")) return
        val clean = if (pattern.startsWith("@@")) pattern.removePrefix("@@") else pattern
        if (clean.startsWith("||")) {
            val body = clean.removePrefix("||")
            val sep = body.indexOfAny(charArrayOf('^', '/'))
            val domain = (if (sep >= 0) body.substring(0, sep) else body).lowercase()
            val path = if (sep >= 0) body.substring(sep).replace("^", "") else ""
            if (domain.isNotEmpty() && !domain.contains('*')) {
                byDomain.getOrPut(domain) { ArrayList(2) }
                    .add(CompiledRule(entity = rule, pathPart = path))
                return
            }
        }
        if (clean.contains("*")) {
            val normalized = clean.replace(Regex("\\*{2,}"), "*")
            if (normalized.count { it == '*' } <= 12) {
                val regexStr = normalized.split('*').joinToString(".*") { Regex.escape(it) }
                val compiled = runCatching { Regex(regexStr, RegexOption.IGNORE_CASE) }.getOrNull()
                if (compiled != null) {
                    general.add(CompiledRule(entity = rule, regex = compiled))
                }
            }
            return
        }
        general.add(CompiledRule(entity = rule, literalNeedle = clean))
    }

    /**
     * Real network request interception called from WebViewClient.shouldInterceptRequest.
     * Uses O(1) domain lookups and precompiled patterns to keep page loading lag-free.
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

        val snap = snapshot

        // Check whitelist rules first (@@||domain.com^ or domain)
        if (matchesDomainMap(url, reqHost, snap.domainAllowRules) != null ||
            matchesGeneralList(url, snap.generalAllowRules) != null
        ) {
            return null
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

        // Check DNS blocklist domains (O(1) label walk)
        val matchedDns = findMatchingDomainInSet(reqHost, snap.dnsBlockDomains)
        if (matchedDns != null) {
            val event = BlockedEvent(url, reqHost, "DNS:$matchedDns", "DNS Blocklist")
            recordBlocked(event, null)
            onBlocked?.invoke(event)
            return emptyBlockedResponse()
        }

        // Check EasyList / custom network filter rules
        val matchedRule = matchesDomainMap(url, reqHost, snap.domainBlockRules)
            ?: matchesGeneralList(url, snap.generalBlockRules)
        if (matchedRule != null) {
            val category = if (trackerProtectionEnabled &&
                (url.contains("analytics", true) || url.contains("pixel", true) || url.contains("track", true))
            ) "Tracker" else "Ad"
            val event = BlockedEvent(url, reqHost, matchedRule.pattern, category)
            recordBlocked(event, matchedRule.id)
            onBlocked?.invoke(event)
            return emptyBlockedResponse()
        }

        return null
    }

    private fun findMatchingDomainInSet(host: String, domainSet: Set<String>): String? {
        if (domainSet.isEmpty()) return null
        var candidate = host
        while (true) {
            if (candidate in domainSet) return candidate
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndex) break
            candidate = candidate.substring(dot + 1)
        }
        return null
    }

    private fun matchesDomainMap(
        url: String,
        host: String,
        domainMap: Map<String, List<CompiledRule>>
    ): AdBlockRuleEntity? {
        if (domainMap.isEmpty()) return null
        var candidate = host
        while (true) {
            val rules = domainMap[candidate]
            if (rules != null) {
                for (r in rules) {
                    if (r.pathPart.isEmpty() || url.contains(r.pathPart, ignoreCase = true)) {
                        return r.entity
                    }
                }
            }
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndex) break
            candidate = candidate.substring(dot + 1)
        }
        return null
    }

    private fun matchesGeneralList(url: String, list: List<CompiledRule>): AdBlockRuleEntity? {
        for (r in list) {
            val matched = when {
                r.regex != null -> r.regex.containsMatchIn(url)
                r.literalNeedle != null -> url.contains(r.literalNeedle, ignoreCase = true)
                else -> false
            }
            if (matched) return r.entity
        }
        return null
    }

    fun isDnsDomainBlocked(domain: String): Boolean {
        val clean = domain.trim().lowercase().trimEnd('.')
        if (!globalEnabled) return false
        return findMatchingDomainInSet(clean, snapshot.dnsBlockDomains) != null
    }

    fun buildCosmeticCss(siteAdBlockEnabled: Boolean): String {
        if (!globalEnabled || !siteAdBlockEnabled) return ""
        return snapshot.cosmeticCss
    }

    /**
     * Strips common tracking query params (utm_*, fbclid, gclid, mc_cid) when tracker protection is active.
     */
    fun cleanTrackingUrl(rawUrl: String): String {
        if (!trackerProtectionEnabled) return rawUrl
        val qIdx = rawUrl.indexOf('?')
        if (qIdx < 0) return rawUrl
        val queryPart = rawUrl.substring(qIdx + 1).lowercase()
        if (TRACKING_KEYS.none { queryPart.contains("$it=") }) return rawUrl
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        if (uri.scheme != "http" && uri.scheme != "https") return rawUrl
        val names = uri.queryParameterNames
        if (names.none { it.lowercase() in TRACKING_KEYS }) return rawUrl
        val builder = uri.buildUpon().clearQuery()
        for (name in names) {
            if (name.lowercase() !in TRACKING_KEYS) {
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
        pendingUiBlockedCount.incrementAndGet()
        pendingRecentEvents.addFirst(event)
        while (pendingRecentEvents.size > 60) {
            pendingRecentEvents.pollLast()
        }
        if (ruleId != null) {
            pendingRuleHits.computeIfAbsent(ruleId) { AtomicLong(0L) }.incrementAndGet()
        }
        if (uiFlushScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(350L)
                flushUiBlockedStats()
            }
        }
    }

    private fun flushUiBlockedStats() {
        uiFlushScheduled.set(false)
        val delta = pendingUiBlockedCount.getAndSet(0L)
        if (delta > 0L) {
            _sessionBlockedCount.update { it + delta }
        }
        if (pendingRecentEvents.isNotEmpty()) {
            val fresh = ArrayList<BlockedEvent>(60)
            while (fresh.size < 60) {
                val item = pendingRecentEvents.pollFirst() ?: break
                fresh.add(item)
            }
            if (fresh.isNotEmpty()) {
                _recentBlocked.update { prev -> (fresh + prev).take(60) }
            }
        }
    }

    private fun emptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "Blocked by Weblo Shield",
            BLOCKED_HEADERS,
            ByteArrayInputStream(EMPTY_BYTES)
        )
    }

    companion object {
        private val EMPTY_BYTES = ByteArray(0)
        private val BLOCKED_HEADERS = mapOf("X-Weblio-Blocked" to "true")
        private val TRACKING_KEYS = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "mc_cid", "mc_eid", "igshid", "yclid"
        )
        private fun isSafeCosmeticSelector(sel: String): Boolean {
            return sel.isNotBlank() &&
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
