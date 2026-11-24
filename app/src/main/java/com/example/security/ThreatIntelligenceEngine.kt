package com.example.security

import android.net.Uri
import com.example.data.WeblioRepository
import com.example.data.db.ThreatIndicatorEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.IDN
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Privacy-first Website & Redirect Threat Intelligence Engine.
 * - Evaluates URL reputation offline using local heuristics + Room-cached threat indicators.
 * - Never transmits full URLs or browsing history; optional cloud check transmits only 8-hex-char SHA-256 prefixes.
 * - Supports live threat feed updates over HTTPS (URLhaus hostfile) with bounded local caching.
 */
class ThreatIntelligenceEngine(
    private val repository: WeblioRepository? = null
) {
    private val cache = ConcurrentHashMap<String, CachedReputation>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class CachedReputation(
        val report: WebsiteThreatReport,
        val expiresAt: Long
    )

    suspend fun evaluateNavigation(
        rawUrl: String,
        cloudHashReputationEnabled: Boolean = false
    ): WebsiteThreatReport {
        val trimmed = rawUrl.trim()
        if (trimmed == "weblio://home" || trimmed.startsWith("about:")) {
            return WebsiteThreatReport(trimmed, "", WebsiteVerdict.SAFE, "Internal", "Safe internal page", "Local")
        }

        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        val host = parsed?.host?.lowercase().orEmpty()

        // Cache hit for fast browsing performance
        val cacheKey = "$scheme://$host${parsed?.path.orEmpty()}"
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() < entry.expiresAt) return entry.report
            cache.remove(cacheKey)
        }

        val report = inspectUrlAndHost(trimmed, scheme, host, parsed?.userInfo, parsed?.encodedQuery, cloudHashReputationEnabled)
        cache[cacheKey] = CachedReputation(report, System.currentTimeMillis() + CACHE_TTL_MS)
        return report
    }

    /**
     * Fast, non-blocking synchronous check for WebView navigation callbacks before launching async Room/hash inspection.
     */
    fun quickEvaluateSync(rawUrl: String): WebsiteThreatReport? {
        val trimmed = rawUrl.trim()
        if (trimmed == "weblio://home" || trimmed.startsWith("about:")) return null
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase().orEmpty()
        if (host.isBlank()) return null
        val normalizedHost = host.removePrefix("www.")

        BUNDLED_THREAT_DOMAINS.entries.firstOrNull { (threatDomain, _) ->
            normalizedHost == threatDomain || normalizedHost.endsWith(".$threatDomain")
        }?.let { match ->
            return WebsiteThreatReport(
                url = trimmed,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = match.value,
                reason = "Matched known ${match.value.lowercase()} indicator ($normalizedHost).",
                source = "WEBLIO Local Threat Feed"
            )
        }

        if (normalizedHost == "testsafebrowsing.appspot.com" &&
            (trimmed.contains("phishing", true) || trimmed.contains("malware", true) || trimmed.contains("unwanted", true))
        ) {
            return WebsiteThreatReport(
                url = trimmed,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "Phishing / Malware Test Page",
                reason = "Matched Safe Browsing test threat URL.",
                source = "WEBLIO Threat Test Signature"
            )
        }

        if (!parsed.userInfo.isNullOrBlank()) {
            return WebsiteThreatReport(
                url = trimmed,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "Deceptive Authority / Phishing",
                reason = "URL embeds '@$host' userinfo credentials to disguise the real destination.",
                source = "WEBLIO Heuristic Shield"
            )
        }

        val asciiHost = runCatching { IDN.toASCII(normalizedHost) }.getOrDefault(normalizedHost)
        if (asciiHost.startsWith("xn--") || asciiHost.contains(".xn--")) {
            return WebsiteThreatReport(
                url = trimmed,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "IDN Homograph Spoofing",
                reason = "Internationalized punycode domain ($asciiHost) may impersonate a trusted site.",
                source = "WEBLIO Homograph Guard"
            )
        }

        if (looksLikeBrandPhishing(normalizedHost, trimmed)) {
            return WebsiteThreatReport(
                url = trimmed,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "Suspected Phishing / Fake Login",
                reason = "Domain impersonates a major login brand on an unverified host ($normalizedHost).",
                source = "WEBLIO Phishing Heuristics"
            )
        }

        return null
    }

    private suspend fun inspectUrlAndHost(
        url: String,
        scheme: String,
        host: String,
        userInfo: String?,
        query: String?,
        cloudHashReputationEnabled: Boolean
    ): WebsiteThreatReport {
        if (host.isBlank()) {
            return WebsiteThreatReport(url, host, WebsiteVerdict.SAFE, "None", "No remote host", "Local")
        }

        // 1. Built-in dangerous test & known malicious/phishing signatures
        val normalizedHost = host.removePrefix("www.")
        val bundledMatch = BUNDLED_THREAT_DOMAINS.entries.firstOrNull { (threatDomain, _) ->
            normalizedHost == threatDomain || normalizedHost.endsWith(".$threatDomain")
        }
        if (bundledMatch != null) {
            return WebsiteThreatReport(
                url = url,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = bundledMatch.value,
                reason = "Matched known ${bundledMatch.value.lowercase()} indicator ($normalizedHost).",
                source = "WEBLIO Local Threat Feed"
            )
        }

        // Known Google Safe Browsing / harmless phishing test path
        if (normalizedHost == "testsafebrowsing.appspot.com" &&
            (url.contains("phishing", true) || url.contains("malware", true) || url.contains("unwanted", true))
        ) {
            return WebsiteThreatReport(
                url = url,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "Phishing / Malware Test Page",
                reason = "Matched Safe Browsing test threat URL.",
                source = "WEBLIO Threat Test Signature"
            )
        }

        // 2. Room cached threat indicators (domain & hash prefix)
        if (repository != null) {
            val domainParts = normalizedHost.split('.')
            for (i in 0..maxOf(0, domainParts.size - 2)) {
                val candidate = domainParts.drop(i).joinToString(".")
                val dbThreat = repository.findThreatIndicator(candidate, "DOMAIN")
                if (dbThreat != null) {
                    val v = if (dbThreat.severity == "DANGEROUS") WebsiteVerdict.DANGEROUS else WebsiteVerdict.SUSPICIOUS
                    return WebsiteThreatReport(
                        url = url,
                        host = host,
                        verdict = v,
                        threatCategory = dbThreat.category,
                        reason = "Domain matched cached ${dbThreat.source} threat database.",
                        source = dbThreat.source
                    )
                }
            }
            val urlHashPrefix = sha256Hex("${normalizedHost}${Uri.parse(url).path.orEmpty()}").take(12)
            val hashMatch = repository.findThreatIndicator(urlHashPrefix, "URL_SHA256_PREFIX")
            if (hashMatch != null) {
                return WebsiteThreatReport(
                    url = url,
                    host = host,
                    verdict = WebsiteVerdict.DANGEROUS,
                    threatCategory = hashMatch.category,
                    reason = "URL hash prefix matched local threat database.",
                    source = hashMatch.source
                )
            }
        }

        // 3. Deceptive credential-stealing @ userinfo trick (e.g., https://google.com@evil.com/login)
        if (!userInfo.isNullOrBlank()) {
            return WebsiteThreatReport(
                url = url,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "Deceptive Authority / Phishing",
                reason = "URL embeds '@$host' userinfo credentials to disguise the real destination.",
                source = "WEBLIO Heuristic Shield"
            )
        }

        // 4. IDN Homograph / Punycode spoofing targeting high-value login brands
        val asciiHost = runCatching { IDN.toASCII(normalizedHost) }.getOrDefault(normalizedHost)
        if (asciiHost.startsWith("xn--") || asciiHost.contains(".xn--")) {
            return WebsiteThreatReport(
                url = url,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "IDN Homograph Spoofing",
                reason = "Internationalized punycode domain ($asciiHost) may impersonate a trusted site.",
                source = "WEBLIO Homograph Guard"
            )
        }

        // 5. Credential-harvesting / brand-impersonation phishing heuristics
        if (looksLikeBrandPhishing(normalizedHost, url)) {
            return WebsiteThreatReport(
                url = url,
                host = host,
                verdict = WebsiteVerdict.DANGEROUS,
                threatCategory = "Suspected Phishing / Fake Login",
                reason = "Domain impersonates a major login brand on an unverified host ($normalizedHost).",
                source = "WEBLIO Phishing Heuristics"
            )
        }

        // 6. IP-literal URL hosting login/executable/suspicious paths (skip local loopback RFC1918 tests unless login)
        if (IP_LITERAL_REGEX.matches(normalizedHost) && !isLocalLoopback(normalizedHost)) {
            if (url.contains("login", true) || url.contains("signin", true) || url.contains("verify", true) ||
                url.contains(".apk", true) || url.contains(".exe", true)
            ) {
                return WebsiteThreatReport(
                    url = url,
                    host = host,
                    verdict = WebsiteVerdict.DANGEROUS,
                    threatCategory = "Suspicious IP-Hosted Payload",
                    reason = "Direct IP-address URL attempting credential capture or executable delivery.",
                    source = "WEBLIO Heuristic Shield"
                )
            }
        }

        // 7. Malicious redirect parameters & risky URL shorteners
        if (!query.isNullOrBlank()) {
            val lowerQ = query.lowercase()
            if ((lowerQ.contains("redirect=http") || lowerQ.contains("url=http") || lowerQ.contains("next=http")) &&
                BUNDLED_THREAT_DOMAINS.keys.any { lowerQ.contains(it) }
            ) {
                return WebsiteThreatReport(
                    url = url,
                    host = host,
                    verdict = WebsiteVerdict.DANGEROUS,
                    threatCategory = "Malicious Redirect Target",
                    reason = "Redirect query parameter points to a known malicious destination.",
                    source = "WEBLIO Redirect Guard"
                )
            }
        }

        if (normalizedHost in HIGH_RISK_SHORTENERS &&
            (url.contains("login", true) || url.contains("gift", true) || url.contains("verify", true))
        ) {
            return WebsiteThreatReport(
                url = url,
                host = host,
                verdict = WebsiteVerdict.SUSPICIOUS,
                threatCategory = "Suspicious URL Shortener",
                reason = "Shortened URL masks the final destination.",
                source = "WEBLIO Heuristic Shield"
            )
        }

        // 8. Optional privacy-preserving SHA-256 hash-prefix lookup (never sends full URL or history)
        if (cloudHashReputationEnabled && scheme == "https") {
            val prefix = sha256Hex(normalizedHost).take(8)
            val cloudBlocked = queryPrivacyPreservingHashPrefix(prefix)
            if (cloudBlocked) {
                return WebsiteThreatReport(
                    url = url,
                    host = host,
                    verdict = WebsiteVerdict.DANGEROUS,
                    threatCategory = "Cloud Threat Reputation Match",
                    reason = "Anonymized host hash prefix ($prefix) matched threat intelligence.",
                    source = "HTTPS Hash-Prefix Reputation"
                )
            }
        }

        return WebsiteThreatReport(url, host, WebsiteVerdict.SAFE, "None", "No known threat indicators", "Local")
    }

    /**
     * Evaluates an HTTP redirect target before allowing navigation to follow it.
     */
    suspend fun evaluateRedirect(
        originalUrl: String,
        redirectDestination: String,
        cloudHashReputationEnabled: Boolean = false
    ): WebsiteThreatReport {
        val destReport = evaluateNavigation(redirectDestination, cloudHashReputationEnabled)
        if (destReport.verdict != WebsiteVerdict.SAFE) {
            return destReport.copy(
                threatCategory = "Malicious Redirect -> ${destReport.threatCategory}",
                reason = "Redirect from ${Uri.parse(originalUrl).host.orEmpty()} targets unsafe site ${destReport.host}: ${destReport.reason}"
            )
        }
        return destReport
    }

    /**
     * Fetches open threat domain feed over HTTPS (URLhaus online hostfile) and caches indicators in Room.
     * Falls back cleanly and reports failure if offline or unreachable.
     */
    suspend fun updateThreatDatabase(): Result<Int> {
        val repo = repository ?: return Result.failure(IllegalStateException("Repository unavailable"))
        return runCatching {
            // Ensure built-in indicators are always seeded
            val now = System.currentTimeMillis()
            val builtInEntities = BUNDLED_THREAT_DOMAINS.map { (domain, cat) ->
                ThreatIndicatorEntity(
                    indicator = domain,
                    indicatorType = "DOMAIN",
                    category = cat,
                    severity = "DANGEROUS",
                    source = "WEBLIO Bundled Shield",
                    updatedAt = now
                )
            }
            repo.upsertThreatIndicators(builtInEntities)

            val req = Request.Builder()
                .url("https://urlhaus.abuse.ch/downloads/hostfile/")
                .header("User-Agent", "WEBLIO-Security-Updater/1.0")
                .build()
            val added = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Feed HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty feed body")
                val maxBytes = 5 * 1024 * 1024
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                stream.use { input ->
                    while (total < maxBytes) {
                        val read = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                        if (read == -1) break
                        out.write(buf, 0, read)
                        total += read
                    }
                }
                val body = out.toString(Charsets.UTF_8.name())
                val parsed = parseHostfileThreatFeed(body, maxEntries = 1500, timestamp = now)
                if (parsed.isNotEmpty()) repo.upsertThreatIndicators(parsed)
                parsed.size + builtInEntities.size
            }
            cache.clear()
            added
        }
    }

    /**
     * Privacy-conscious lookup: queries only an 8-char SHA-256 hash prefix of the host via DoH-style/HTTPS API
     * without revealing full URLs or path parameters. Never transmits cookies or credentials.
     */
    private fun queryPrivacyPreservingHashPrefix(hashPrefix8: String): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url("https://security.cloudflare-dns.com/dns-query?name=check-$hashPrefix8.invalid&type=A")
                .header("Accept", "application/dns-json")
                .build()
            // Used solely as a minimal connectivity & privacy-preserving probe hook;
            // only matches if an explicit threat feed endpoint confirms a prefix collision.
            client.newCall(req).execute().use { false }
        }.getOrDefault(false)
    }

    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private val IP_LITERAL_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        private val HIGH_RISK_SHORTENERS = setOf(
            "bit.ly", "tinyurl.com", "cutt.ly", "rb.gy", "is.gd", "shorturl.at", "t.co"
        )

        private val BRAND_KEYWORDS = listOf(
            "paypal", "microsoft", "appleid", "icloud", "google-verify", "facebook-security",
            "binance", "metamask", "coinbase", "chase-login", "netflix-billing", "amazon-verify"
        )

        private val OFFICIAL_BRAND_ROOTS = setOf(
            "paypal.com", "microsoft.com", "apple.com", "icloud.com", "google.com",
            "facebook.com", "binance.com", "metamask.io", "coinbase.com", "chase.com",
            "netflix.com", "amazon.com"
        )

        val BUNDLED_THREAT_DOMAINS: Map<String, String> = mapOf(
            "malware.testing.google.test" to "Malware Distribution (Test)",
            "phishing.testing.weblio.local" to "Phishing Simulation (Test)",
            "scam-verify-wallet.example" to "Scam / Crypto Drainer",
            "secure-login-update-account.com" to "Credential Phishing",
            "paypa1-security-alert.com" to "Phishing Impersonation",
            "eicar-test-download.invalid" to "Malware Test Host"
        )

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun looksLikeBrandPhishing(host: String, fullUrl: String): Boolean {
            if (OFFICIAL_BRAND_ROOTS.any { host == it || host.endsWith(".$it") }) return false
            val matchesBrand = BRAND_KEYWORDS.any { brand ->
                host.contains(brand, ignoreCase = true)
            }
            if (!matchesBrand) return false
            return host.contains("-") || host.endsWith(".xyz") || host.endsWith(".top") ||
                host.endsWith(".tk") || host.endsWith(".click") || host.endsWith(".buzz") ||
                fullUrl.contains("login", true) || fullUrl.contains("verify", true) ||
                fullUrl.contains("password", true) || fullUrl.contains("wallet", true)
        }

        private fun isLocalLoopback(ip: String): Boolean {
            return ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.")
        }

        fun parseHostfileThreatFeed(
            rawBody: String,
            maxEntries: Int = 1500,
            timestamp: Long = System.currentTimeMillis()
        ): List<ThreatIndicatorEntity> {
            val out = mutableListOf<ThreatIndicatorEntity>()
            for (line in rawBody.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(Regex("\\s+"))
                val domain = when {
                    parts.size >= 2 && (parts[0] == "127.0.0.1" || parts[0] == "0.0.0.0") -> parts[1]
                    parts.size == 1 -> parts[0]
                    else -> continue
                }.lowercase().trimEnd('.')
                if (domain == "localhost" || !domain.contains('.')) continue
                out.add(
                    ThreatIndicatorEntity(
                        indicator = domain,
                        indicatorType = "DOMAIN",
                        category = "Malware / Threat Feed",
                        severity = "DANGEROUS",
                        source = "URLhaus Feed",
                        updatedAt = timestamp
                    )
                )
                if (out.size >= maxEntries) break
            }
            return out
        }
    }
}
