package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.WeakHashMap

enum class SearchEngine(
    val displayName: String,
    val searchUrlTemplate: String,
    val homeUrl: String,
    val logoDomain: String
) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com", "google.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com", "duckduckgo.com"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s", "https://search.brave.com", "brave.com"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com", "bing.com"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org", "ecosia.org");

    fun buildSearchUrl(query: String, safeSearchEnabled: Boolean = true): String {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val base = searchUrlTemplate.replace("%s", encoded)
        return when (this) {
            GOOGLE -> if (safeSearchEnabled) "$base&safe=active" else base
            DUCKDUCKGO -> if (safeSearchEnabled) "$base&kp=1" else "$base&kp=-2"
            BRAVE -> if (safeSearchEnabled) "$base&safesearch=strict" else "$base&safesearch=off"
            BING -> if (safeSearchEnabled) "$base&adlt=strict" else "$base&adlt=off"
            ECOSIA -> if (safeSearchEnabled) "$base&safesearch=2" else "$base&safesearch=0"
        }
    }

    fun logoUrl(size: Int = 128): String =
        "https://www.google.com/s2/favicons?domain=$logoDomain&sz=$size"

    companion object {
        fun enforceSafeSearchOnUrl(rawUrl: String, safeSearchEnabled: Boolean): String {
            if (!safeSearchEnabled) return rawUrl
            val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return rawUrl
            val host = uri.host?.lowercase() ?: return rawUrl
            val path = uri.path.orEmpty().lowercase()
            val query = uri.rawQuery ?: return rawUrl
            if (!query.contains("q=")) return rawUrl

            val paramPair: Pair<String, String>? = when {
                (host.contains("google.") && path.startsWith("/search")) -> "safe" to "active"
                (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) -> "kp" to "1"
                host == "search.brave.com" && path.startsWith("/search") -> "safesearch" to "strict"
                (host == "bing.com" || host.endsWith(".bing.com")) && path.startsWith("/search") -> "adlt" to "strict"
                (host == "ecosia.org" || host.endsWith(".ecosia.org")) && path.startsWith("/search") -> "safesearch" to "2"
                else -> null
            }
            val (key, desiredVal) = paramPair ?: return rawUrl

            val pairs = query.split("&").filter { it.isNotEmpty() }.toMutableList()
            val existingIdx = pairs.indexOfFirst { it.startsWith("$key=", ignoreCase = true) }
            if (existingIdx >= 0) {
                val existingVal = pairs[existingIdx].substringAfter('=')
                if (existingVal.equals(desiredVal, ignoreCase = true)) return rawUrl
                pairs[existingIdx] = "$key=$desiredVal"
            } else {
                pairs.add("$key=$desiredVal")
            }
            val updatedQuery = pairs.joinToString("&")
            return runCatching {
                java.net.URI(
                    uri.scheme,
                    uri.rawAuthority,
                    uri.rawPath,
                    updatedQuery,
                    uri.rawFragment
                ).toString()
            }.getOrDefault(rawUrl)
        }
    }
}

fun websiteFaviconUrl(rawUrl: String, size: Int = 128): String {
    val trimmed = rawUrl.trim()
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    val host = runCatching { java.net.URI(withScheme).host }
        .getOrNull()
        ?.lowercase()
        ?.removePrefix("www.")
        ?.removePrefix("m.")
        ?.takeIf { it.isNotBlank() }
        ?: "example.com"
    val canonical = when (host) {
        "search.brave.com" -> "brave.com"
        else -> host
    }
    return "https://www.google.com/s2/favicons?domain=$canonical&sz=$size"
}

enum class StartupBehavior(val label: String) {
    HOME_SCREEN("Open Home Screen"),
    RESTORE_TABS("Restore Previous Session"),
    CUSTOM_HOMEPAGE("Open Custom Homepage")
}

enum class CookiePolicy(val label: String) {
    ALLOW_ALL("Allow All Cookies"),
    BLOCK_THIRD_PARTY("Block Third-Party Cookies"),
    BLOCK_ALL("Block All Cookies")
}

enum class DnsFilterMode(val label: String, val dohEndpoint: String) {
    OFF("System Default DNS", ""),
    ADGUARD_DOH("AdGuard DNS (DoH Ad & Tracker Blocking)", "https://dns.adguard-dns.com/dns-query"),
    QUAD9_DOH("Quad9 Security DNS (Malware Protection)", "https://dns.quad9.net/dns-query"),
    CLOUDFLARE_MALWARE("Cloudflare Family/Malware DoH", "https://security.cloudflare-dns.com/dns-query"),
    CUSTOM("Custom DoH / Blocklist", "")
}

enum class WallpaperStyle(val label: String) {
    COSMIC_OBS("Obsidian Glow"),
    ELECTRIC_AURORA("Electric Aurora"),
    DEEP_PURPLE_NEBULA("Purple Nebula"),
    MINIMAL_PURE("Pure Minimal")
}

data class BrowserSettings(
    val activeProfileId: Long = 1L,
    val defaultSearchEngine: SearchEngine = SearchEngine.GOOGLE,
    val safeSearchEnabled: Boolean = true,
    val homepageUrl: String = "weblio://home",
    val startupBehavior: StartupBehavior = StartupBehavior.RESTORE_TABS,
    val languageTag: String = "en",
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val wallpaperStyle: WallpaperStyle = WallpaperStyle.ELECTRIC_AURORA,
    val customHomeBackgroundPath: String = "",
    val forceDarkWebPages: Boolean = false,
    val showQuickLinksOnHome: Boolean = true,
    val compactBottomToolbar: Boolean = false,
    val adBlockerEnabled: Boolean = true,
    val trackerProtectionEnabled: Boolean = true,
    val popupBlockingEnabled: Boolean = true,
    val thirdPartyRequestBlocking: Boolean = false,
    val cookiePolicy: CookiePolicy = CookiePolicy.BLOCK_THIRD_PARTY,
    val dnsFilterMode: DnsFilterMode = DnsFilterMode.ADGUARD_DOH,
    val customDohUrl: String = "",
    val vpnDnsFilterEnabled: Boolean = false,
    val clearIncognitoOnExit: Boolean = true,
    val safeBrowsingEnabled: Boolean = true,
    val clipboardGuardEnabled: Boolean = true,
    val biometricAppLockEnabled: Boolean = false,
    val javascriptEnabled: Boolean = true,
    val desktopModeDefault: Boolean = false,
    val hardwareAccelerationEnabled: Boolean = true,
    val downloadLocationLabel: String = "Gallery & Files (Weblo)",
    val downloadNotificationsEnabled: Boolean = true,
    val askBeforeDownload: Boolean = true,
    val totalBlockedCount: Long = 0L,
    val lastFilterUpdateMillis: Long = 0L,
    val httpsFirstEnabled: Boolean = true,
    val downloadScanEnabled: Boolean = true,
    val cloudHashReputationEnabled: Boolean = false,
    val securityNotificationsEnabled: Boolean = true,
    val lastSecurityUpdateMillis: Long = 0L
)

private object BrowserDataStoreRegistry {
    private val stores = WeakHashMap<Context, DataStore<Preferences>>()

    @Synchronized
    fun forContext(context: Context): DataStore<Preferences> {
        val appCtx = context.applicationContext ?: context
        return stores.getOrPut(appCtx) {
            PreferenceDataStoreFactory.create(
                produceFile = { appCtx.preferencesDataStoreFile("weblio_browser_prefs") }
            )
        }
    }
}

class BrowserPreferences(private val context: Context) {

    private val dataStore: DataStore<Preferences>
        get() = BrowserDataStoreRegistry.forContext(context)

    private object Keys {
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val SAFE_SEARCH = booleanPreferencesKey("safe_search_enabled")
        val DEFAULT_BOOKMARKS_CLEANED = booleanPreferencesKey("default_bookmarks_cleaned_v1")
        val HOMEPAGE_URL = stringPreferencesKey("homepage_url")
        val STARTUP_BEHAVIOR = stringPreferencesKey("startup_behavior")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WALLPAPER_STYLE = stringPreferencesKey("wallpaper_style")
        val CUSTOM_HOME_BG_PATH = stringPreferencesKey("custom_home_bg_path")
        val FORCE_DARK_WEB = booleanPreferencesKey("force_dark_web")
        val SHOW_QUICK_LINKS = booleanPreferencesKey("show_quick_links")
        val COMPACT_TOOLBAR = booleanPreferencesKey("compact_toolbar")
        val ADBLOCK_ENABLED = booleanPreferencesKey("adblock_enabled")
        val TRACKER_PROTECTION = booleanPreferencesKey("tracker_protection")
        val POPUP_BLOCKING = booleanPreferencesKey("popup_blocking")
        val THIRD_PARTY_BLOCKING = booleanPreferencesKey("third_party_blocking")
        val COOKIE_POLICY = stringPreferencesKey("cookie_policy")
        val DNS_FILTER_MODE = stringPreferencesKey("dns_filter_mode")
        val CUSTOM_DOH_URL = stringPreferencesKey("custom_doh_url")
        val VPN_DNS_ENABLED = booleanPreferencesKey("vpn_dns_enabled")
        val CLEAR_INCOGNITO_EXIT = booleanPreferencesKey("clear_incognito_exit")
        val SAFE_BROWSING = booleanPreferencesKey("safe_browsing")
        val CLIPBOARD_GUARD = booleanPreferencesKey("clipboard_guard")
        val BIOMETRIC_APP_LOCK = booleanPreferencesKey("biometric_app_lock")
        val JS_ENABLED = booleanPreferencesKey("js_enabled")
        val DESKTOP_MODE_DEFAULT = booleanPreferencesKey("desktop_mode_default")
        val HW_ACCEL = booleanPreferencesKey("hw_accel")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val DOWNLOAD_NOTIFS = booleanPreferencesKey("download_notifs")
        val ASK_BEFORE_DOWNLOAD = booleanPreferencesKey("ask_before_download")
        val TOTAL_BLOCKED = longPreferencesKey("total_blocked")
        val LAST_FILTER_UPDATE = longPreferencesKey("last_filter_update")
        val HTTPS_FIRST = booleanPreferencesKey("https_first")
        val DOWNLOAD_SCAN = booleanPreferencesKey("download_scan")
        val CLOUD_HASH_REP = booleanPreferencesKey("cloud_hash_rep")
        val SECURITY_NOTIFS = booleanPreferencesKey("security_notifs")
        val LAST_SECURITY_UPDATE = longPreferencesKey("last_security_update")
    }

    val settingsFlow: Flow<BrowserSettings> = dataStore.data.map { p ->
        BrowserSettings(
            activeProfileId = p[Keys.ACTIVE_PROFILE_ID] ?: 1L,
            defaultSearchEngine = p[Keys.SEARCH_ENGINE]?.let {
                runCatching { SearchEngine.valueOf(it) }.getOrNull()
            } ?: SearchEngine.GOOGLE,
            safeSearchEnabled = p[Keys.SAFE_SEARCH] ?: true,
            homepageUrl = p[Keys.HOMEPAGE_URL] ?: "weblio://home",
            startupBehavior = p[Keys.STARTUP_BEHAVIOR]?.let {
                runCatching { StartupBehavior.valueOf(it) }.getOrNull()
            } ?: StartupBehavior.RESTORE_TABS,
            languageTag = p[Keys.LANGUAGE] ?: "en",
            themeMode = p[Keys.THEME_MODE]?.let {
                runCatching { AppThemeMode.valueOf(it) }.getOrNull()
            } ?: AppThemeMode.LIGHT,
            wallpaperStyle = p[Keys.WALLPAPER_STYLE]?.let {
                runCatching { WallpaperStyle.valueOf(it) }.getOrNull()
            } ?: WallpaperStyle.ELECTRIC_AURORA,
            customHomeBackgroundPath = p[Keys.CUSTOM_HOME_BG_PATH] ?: "",
            forceDarkWebPages = p[Keys.FORCE_DARK_WEB] ?: false,
            showQuickLinksOnHome = p[Keys.SHOW_QUICK_LINKS] ?: true,
            compactBottomToolbar = p[Keys.COMPACT_TOOLBAR] ?: false,
            adBlockerEnabled = p[Keys.ADBLOCK_ENABLED] ?: true,
            trackerProtectionEnabled = p[Keys.TRACKER_PROTECTION] ?: true,
            popupBlockingEnabled = p[Keys.POPUP_BLOCKING] ?: true,
            thirdPartyRequestBlocking = p[Keys.THIRD_PARTY_BLOCKING] ?: false,
            cookiePolicy = p[Keys.COOKIE_POLICY]?.let {
                runCatching { CookiePolicy.valueOf(it) }.getOrNull()
            } ?: CookiePolicy.BLOCK_THIRD_PARTY,
            dnsFilterMode = p[Keys.DNS_FILTER_MODE]?.let {
                runCatching { DnsFilterMode.valueOf(it) }.getOrNull()
            } ?: DnsFilterMode.ADGUARD_DOH,
            customDohUrl = p[Keys.CUSTOM_DOH_URL] ?: "",
            vpnDnsFilterEnabled = p[Keys.VPN_DNS_ENABLED] ?: false,
            clearIncognitoOnExit = p[Keys.CLEAR_INCOGNITO_EXIT] ?: true,
            safeBrowsingEnabled = p[Keys.SAFE_BROWSING] ?: true,
            clipboardGuardEnabled = p[Keys.CLIPBOARD_GUARD] ?: true,
            biometricAppLockEnabled = p[Keys.BIOMETRIC_APP_LOCK] ?: false,
            javascriptEnabled = p[Keys.JS_ENABLED] ?: true,
            desktopModeDefault = p[Keys.DESKTOP_MODE_DEFAULT] ?: false,
            hardwareAccelerationEnabled = p[Keys.HW_ACCEL] ?: true,
            downloadLocationLabel = p[Keys.DOWNLOAD_LOCATION] ?: "Gallery & Files (Weblo)",
            downloadNotificationsEnabled = p[Keys.DOWNLOAD_NOTIFS] ?: true,
            askBeforeDownload = p[Keys.ASK_BEFORE_DOWNLOAD] ?: true,
            totalBlockedCount = p[Keys.TOTAL_BLOCKED] ?: 0L,
            lastFilterUpdateMillis = p[Keys.LAST_FILTER_UPDATE] ?: 0L,
            httpsFirstEnabled = p[Keys.HTTPS_FIRST] ?: true,
            downloadScanEnabled = p[Keys.DOWNLOAD_SCAN] ?: true,
            cloudHashReputationEnabled = p[Keys.CLOUD_HASH_REP] ?: false,
            securityNotificationsEnabled = p[Keys.SECURITY_NOTIFS] ?: true,
            lastSecurityUpdateMillis = p[Keys.LAST_SECURITY_UPDATE] ?: 0L
        )
    }

    suspend fun setActiveProfile(profileId: Long) = dataStore.edit { it[Keys.ACTIVE_PROFILE_ID] = profileId }
    suspend fun setSearchEngine(engine: SearchEngine) = dataStore.edit { it[Keys.SEARCH_ENGINE] = engine.name }
    suspend fun setSafeSearchEnabled(enabled: Boolean) = dataStore.edit { it[Keys.SAFE_SEARCH] = enabled }
    suspend fun isDefaultBookmarksCleanedOnce(): Boolean =
        dataStore.data.map { it[Keys.DEFAULT_BOOKMARKS_CLEANED] ?: false }.first()
    suspend fun markDefaultBookmarksCleaned() = dataStore.edit { it[Keys.DEFAULT_BOOKMARKS_CLEANED] = true }
    suspend fun setHomepage(url: String) = dataStore.edit { it[Keys.HOMEPAGE_URL] = url }
    suspend fun setStartupBehavior(behavior: StartupBehavior) = dataStore.edit { it[Keys.STARTUP_BEHAVIOR] = behavior.name }
    suspend fun setLanguage(lang: String) = dataStore.edit { it[Keys.LANGUAGE] = lang }
    suspend fun setThemeMode(mode: AppThemeMode) = dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setWallpaperStyle(style: WallpaperStyle) = dataStore.edit { it[Keys.WALLPAPER_STYLE] = style.name }
    suspend fun setCustomHomeBackgroundPath(path: String) = dataStore.edit { it[Keys.CUSTOM_HOME_BG_PATH] = path }
    suspend fun setForceDarkWeb(enabled: Boolean) = dataStore.edit { it[Keys.FORCE_DARK_WEB] = enabled }
    suspend fun setShowQuickLinks(show: Boolean) = dataStore.edit { it[Keys.SHOW_QUICK_LINKS] = show }
    suspend fun setCompactToolbar(compact: Boolean) = dataStore.edit { it[Keys.COMPACT_TOOLBAR] = compact }
    suspend fun setAdBlockerEnabled(enabled: Boolean) = dataStore.edit { it[Keys.ADBLOCK_ENABLED] = enabled }
    suspend fun setTrackerProtection(enabled: Boolean) = dataStore.edit { it[Keys.TRACKER_PROTECTION] = enabled }
    suspend fun setPopupBlocking(enabled: Boolean) = dataStore.edit { it[Keys.POPUP_BLOCKING] = enabled }
    suspend fun setThirdPartyBlocking(enabled: Boolean) = dataStore.edit { it[Keys.THIRD_PARTY_BLOCKING] = enabled }
    suspend fun setCookiePolicy(policy: CookiePolicy) = dataStore.edit { it[Keys.COOKIE_POLICY] = policy.name }
    suspend fun setDnsFilterMode(mode: DnsFilterMode) = dataStore.edit { it[Keys.DNS_FILTER_MODE] = mode.name }
    suspend fun setCustomDohUrl(url: String) = dataStore.edit { it[Keys.CUSTOM_DOH_URL] = url }
    suspend fun setVpnDnsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.VPN_DNS_ENABLED] = enabled }
    suspend fun setClearIncognitoOnExit(enabled: Boolean) = dataStore.edit { it[Keys.CLEAR_INCOGNITO_EXIT] = enabled }
    suspend fun setSafeBrowsing(enabled: Boolean) = dataStore.edit { it[Keys.SAFE_BROWSING] = enabled }
    suspend fun setClipboardGuard(enabled: Boolean) = dataStore.edit { it[Keys.CLIPBOARD_GUARD] = enabled }
    suspend fun setBiometricAppLock(enabled: Boolean) = dataStore.edit { it[Keys.BIOMETRIC_APP_LOCK] = enabled }
    suspend fun setJavascriptEnabled(enabled: Boolean) = dataStore.edit { it[Keys.JS_ENABLED] = enabled }
    suspend fun setDesktopModeDefault(enabled: Boolean) = dataStore.edit { it[Keys.DESKTOP_MODE_DEFAULT] = enabled }
    suspend fun setHardwareAcceleration(enabled: Boolean) = dataStore.edit { it[Keys.HW_ACCEL] = enabled }
    suspend fun setDownloadLocationLabel(label: String) = dataStore.edit { it[Keys.DOWNLOAD_LOCATION] = label }
    suspend fun setDownloadNotifications(enabled: Boolean) = dataStore.edit { it[Keys.DOWNLOAD_NOTIFS] = enabled }
    suspend fun setAskBeforeDownload(ask: Boolean) = dataStore.edit { it[Keys.ASK_BEFORE_DOWNLOAD] = ask }
    suspend fun incrementBlockedCount(delta: Long = 1L) = dataStore.edit {
        it[Keys.TOTAL_BLOCKED] = (it[Keys.TOTAL_BLOCKED] ?: 0L) + delta
    }
    suspend fun setLastFilterUpdate(millis: Long) = dataStore.edit {
        it[Keys.LAST_FILTER_UPDATE] = millis
    }
    suspend fun setHttpsFirstEnabled(enabled: Boolean) = dataStore.edit { it[Keys.HTTPS_FIRST] = enabled }
    suspend fun setDownloadScanEnabled(enabled: Boolean) = dataStore.edit { it[Keys.DOWNLOAD_SCAN] = enabled }
    suspend fun setCloudHashReputationEnabled(enabled: Boolean) = dataStore.edit { it[Keys.CLOUD_HASH_REP] = enabled }
    suspend fun setSecurityNotificationsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.SECURITY_NOTIFS] = enabled }
    suspend fun setLastSecurityUpdate(millis: Long) = dataStore.edit { it[Keys.LAST_SECURITY_UPDATE] = millis }

    suspend fun applyBackupSettings(
        searchEngine: SearchEngine? = null,
        homepageUrl: String? = null,
        themeMode: AppThemeMode? = null,
        adBlockerEnabled: Boolean? = null,
        trackerProtectionEnabled: Boolean? = null,
        dnsFilterMode: DnsFilterMode? = null,
        customDohUrl: String? = null
    ) {
        dataStore.edit { prefs ->
            searchEngine?.let { prefs[Keys.SEARCH_ENGINE] = it.name }
            homepageUrl?.let { prefs[Keys.HOMEPAGE_URL] = it }
            themeMode?.let { prefs[Keys.THEME_MODE] = it.name }
            adBlockerEnabled?.let { prefs[Keys.ADBLOCK_ENABLED] = it }
            trackerProtectionEnabled?.let { prefs[Keys.TRACKER_PROTECTION] = it }
            dnsFilterMode?.let { prefs[Keys.DNS_FILTER_MODE] = it.name }
            customDohUrl?.let { prefs[Keys.CUSTOM_DOH_URL] = it }
        }
    }
}
