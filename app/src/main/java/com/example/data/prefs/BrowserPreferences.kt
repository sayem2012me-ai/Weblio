package com.example.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.browserDataStore by preferencesDataStore(name = "weblio_browser_prefs")

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

    fun buildSearchUrl(query: String): String {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return searchUrlTemplate.replace("%s", encoded)
    }

    fun logoUrl(size: Int = 128): String =
        "https://www.google.com/s2/favicons?domain=$logoDomain&sz=$size"
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
    val homepageUrl: String = "weblio://home",
    val startupBehavior: StartupBehavior = StartupBehavior.RESTORE_TABS,
    val languageTag: String = "en",
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val wallpaperStyle: WallpaperStyle = WallpaperStyle.COSMIC_OBS,
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
    val downloadLocationLabel: String = "App Downloads (Scoped Storage)",
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

class BrowserPreferences(private val context: Context) {

    private object Keys {
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val HOMEPAGE_URL = stringPreferencesKey("homepage_url")
        val STARTUP_BEHAVIOR = stringPreferencesKey("startup_behavior")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WALLPAPER_STYLE = stringPreferencesKey("wallpaper_style")
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

    val settingsFlow: Flow<BrowserSettings> = context.browserDataStore.data.map { p ->
        BrowserSettings(
            activeProfileId = p[Keys.ACTIVE_PROFILE_ID] ?: 1L,
            defaultSearchEngine = p[Keys.SEARCH_ENGINE]?.let {
                runCatching { SearchEngine.valueOf(it) }.getOrNull()
            } ?: SearchEngine.GOOGLE,
            homepageUrl = p[Keys.HOMEPAGE_URL] ?: "weblio://home",
            startupBehavior = p[Keys.STARTUP_BEHAVIOR]?.let {
                runCatching { StartupBehavior.valueOf(it) }.getOrNull()
            } ?: StartupBehavior.RESTORE_TABS,
            languageTag = p[Keys.LANGUAGE] ?: "en",
            themeMode = p[Keys.THEME_MODE]?.let {
                runCatching { AppThemeMode.valueOf(it) }.getOrNull()
            } ?: AppThemeMode.DARK,
            wallpaperStyle = p[Keys.WALLPAPER_STYLE]?.let {
                runCatching { WallpaperStyle.valueOf(it) }.getOrNull()
            } ?: WallpaperStyle.COSMIC_OBS,
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
            downloadLocationLabel = p[Keys.DOWNLOAD_LOCATION] ?: "App Downloads (Scoped Storage)",
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

    suspend fun setActiveProfile(profileId: Long) = context.browserDataStore.edit { it[Keys.ACTIVE_PROFILE_ID] = profileId }
    suspend fun setSearchEngine(engine: SearchEngine) = context.browserDataStore.edit { it[Keys.SEARCH_ENGINE] = engine.name }
    suspend fun setHomepage(url: String) = context.browserDataStore.edit { it[Keys.HOMEPAGE_URL] = url }
    suspend fun setStartupBehavior(behavior: StartupBehavior) = context.browserDataStore.edit { it[Keys.STARTUP_BEHAVIOR] = behavior.name }
    suspend fun setLanguage(lang: String) = context.browserDataStore.edit { it[Keys.LANGUAGE] = lang }
    suspend fun setThemeMode(mode: AppThemeMode) = context.browserDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setWallpaperStyle(style: WallpaperStyle) = context.browserDataStore.edit { it[Keys.WALLPAPER_STYLE] = style.name }
    suspend fun setForceDarkWeb(enabled: Boolean) = context.browserDataStore.edit { it[Keys.FORCE_DARK_WEB] = enabled }
    suspend fun setShowQuickLinks(show: Boolean) = context.browserDataStore.edit { it[Keys.SHOW_QUICK_LINKS] = show }
    suspend fun setCompactToolbar(compact: Boolean) = context.browserDataStore.edit { it[Keys.COMPACT_TOOLBAR] = compact }
    suspend fun setAdBlockerEnabled(enabled: Boolean) = context.browserDataStore.edit { it[Keys.ADBLOCK_ENABLED] = enabled }
    suspend fun setTrackerProtection(enabled: Boolean) = context.browserDataStore.edit { it[Keys.TRACKER_PROTECTION] = enabled }
    suspend fun setPopupBlocking(enabled: Boolean) = context.browserDataStore.edit { it[Keys.POPUP_BLOCKING] = enabled }
    suspend fun setThirdPartyBlocking(enabled: Boolean) = context.browserDataStore.edit { it[Keys.THIRD_PARTY_BLOCKING] = enabled }
    suspend fun setCookiePolicy(policy: CookiePolicy) = context.browserDataStore.edit { it[Keys.COOKIE_POLICY] = policy.name }
    suspend fun setDnsFilterMode(mode: DnsFilterMode) = context.browserDataStore.edit { it[Keys.DNS_FILTER_MODE] = mode.name }
    suspend fun setCustomDohUrl(url: String) = context.browserDataStore.edit { it[Keys.CUSTOM_DOH_URL] = url }
    suspend fun setVpnDnsEnabled(enabled: Boolean) = context.browserDataStore.edit { it[Keys.VPN_DNS_ENABLED] = enabled }
    suspend fun setClearIncognitoOnExit(enabled: Boolean) = context.browserDataStore.edit { it[Keys.CLEAR_INCOGNITO_EXIT] = enabled }
    suspend fun setSafeBrowsing(enabled: Boolean) = context.browserDataStore.edit { it[Keys.SAFE_BROWSING] = enabled }
    suspend fun setClipboardGuard(enabled: Boolean) = context.browserDataStore.edit { it[Keys.CLIPBOARD_GUARD] = enabled }
    suspend fun setBiometricAppLock(enabled: Boolean) = context.browserDataStore.edit { it[Keys.BIOMETRIC_APP_LOCK] = enabled }
    suspend fun setJavascriptEnabled(enabled: Boolean) = context.browserDataStore.edit { it[Keys.JS_ENABLED] = enabled }
    suspend fun setDesktopModeDefault(enabled: Boolean) = context.browserDataStore.edit { it[Keys.DESKTOP_MODE_DEFAULT] = enabled }
    suspend fun setHardwareAcceleration(enabled: Boolean) = context.browserDataStore.edit { it[Keys.HW_ACCEL] = enabled }
    suspend fun setDownloadLocationLabel(label: String) = context.browserDataStore.edit { it[Keys.DOWNLOAD_LOCATION] = label }
    suspend fun setDownloadNotifications(enabled: Boolean) = context.browserDataStore.edit { it[Keys.DOWNLOAD_NOTIFS] = enabled }
    suspend fun setAskBeforeDownload(ask: Boolean) = context.browserDataStore.edit { it[Keys.ASK_BEFORE_DOWNLOAD] = ask }
    suspend fun incrementBlockedCount(delta: Long = 1L) = context.browserDataStore.edit {
        it[Keys.TOTAL_BLOCKED] = (it[Keys.TOTAL_BLOCKED] ?: 0L) + delta
    }
    suspend fun setLastFilterUpdate(millis: Long) = context.browserDataStore.edit {
        it[Keys.LAST_FILTER_UPDATE] = millis
    }
    suspend fun setHttpsFirstEnabled(enabled: Boolean) = context.browserDataStore.edit { it[Keys.HTTPS_FIRST] = enabled }
    suspend fun setDownloadScanEnabled(enabled: Boolean) = context.browserDataStore.edit { it[Keys.DOWNLOAD_SCAN] = enabled }
    suspend fun setCloudHashReputationEnabled(enabled: Boolean) = context.browserDataStore.edit { it[Keys.CLOUD_HASH_REP] = enabled }
    suspend fun setSecurityNotificationsEnabled(enabled: Boolean) = context.browserDataStore.edit { it[Keys.SECURITY_NOTIFS] = enabled }
    suspend fun setLastSecurityUpdate(millis: Long) = context.browserDataStore.edit { it[Keys.LAST_SECURITY_UPDATE] = millis }
}
