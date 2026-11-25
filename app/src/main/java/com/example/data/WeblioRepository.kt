package com.example.data

import com.example.data.db.AdBlockDao
import com.example.data.db.AdBlockRuleEntity
import com.example.data.db.BookmarkDao
import com.example.data.db.BookmarkEntity
import com.example.data.db.ClosedTabEntity
import com.example.data.db.DownloadDao
import com.example.data.db.DownloadEntity
import com.example.data.db.ExtensionDao
import com.example.data.db.ExtensionEntity
import com.example.data.db.FilterRuleType
import com.example.data.db.FilterSubscriptionEntity
import com.example.data.db.HistoryDao
import com.example.data.db.HistoryEntity
import com.example.data.db.PasswordDao
import com.example.data.db.PasswordEntity
import com.example.data.db.ProfileDao
import com.example.data.db.ProfileEntity
import com.example.data.db.QuickLinkDao
import com.example.data.db.QuickLinkEntity
import com.example.data.db.SecurityEventDao
import com.example.data.db.SecurityEventEntity
import com.example.data.db.SitePermissionDao
import com.example.data.db.SitePermissionEntity
import com.example.data.db.TabDao
import com.example.data.db.TabEntity
import com.example.data.db.ThreatIndicatorEntity
import com.example.data.db.WeblioDatabase
import com.example.data.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.Flow

class WeblioRepository(private val db: WeblioDatabase) {
    private val profileDao: ProfileDao = db.profileDao()
    private val tabDao: TabDao = db.tabDao()
    private val bookmarkDao: BookmarkDao = db.bookmarkDao()
    private val historyDao: HistoryDao = db.historyDao()
    private val downloadDao: DownloadDao = db.downloadDao()
    private val passwordDao: PasswordDao = db.passwordDao()
    private val quickLinkDao: QuickLinkDao = db.quickLinkDao()
    private val extensionDao: ExtensionDao = db.extensionDao()
    private val adBlockDao: AdBlockDao = db.adBlockDao()
    private val sitePermissionDao: SitePermissionDao = db.sitePermissionDao()
    private val securityEventDao: SecurityEventDao = db.securityEventDao()

    fun observeProfiles(): Flow<List<ProfileEntity>> = profileDao.observeProfiles()
    fun observeTabs(profileId: Long): Flow<List<TabEntity>> = tabDao.observeTabs(profileId)
    fun observeClosedTabs(profileId: Long): Flow<List<ClosedTabEntity>> = tabDao.observeClosedTabs(profileId)
    fun observeBookmarks(profileId: Long): Flow<List<BookmarkEntity>> = bookmarkDao.observeBookmarks(profileId)
    fun observeHistory(profileId: Long): Flow<List<HistoryEntity>> = historyDao.observeHistory(profileId)
    fun observeDownloads(profileId: Long): Flow<List<DownloadEntity>> = downloadDao.observeDownloads(profileId)
    fun observePasswords(profileId: Long): Flow<List<PasswordEntity>> = passwordDao.observePasswords(profileId)
    fun observeQuickLinks(profileId: Long): Flow<List<QuickLinkEntity>> = quickLinkDao.observeQuickLinks(profileId)
    fun observeExtensions(profileId: Long): Flow<List<ExtensionEntity>> = extensionDao.observeExtensions(profileId)
    fun observeAdBlockRules(): Flow<List<AdBlockRuleEntity>> = adBlockDao.observeRules()
    fun observeFilterSubscriptions(): Flow<List<FilterSubscriptionEntity>> = adBlockDao.observeSubscriptions()
    fun observeSitePermissions(profileId: Long): Flow<List<SitePermissionEntity>> = sitePermissionDao.observeSitePermissions(profileId)
    fun observeSecurityEvents(): Flow<List<SecurityEventEntity>> = securityEventDao.observeEvents()

    suspend fun ensureDefaultsSeeded(): Long {
        val existingProfiles = profileDao.getAllOnce()
        if (existingProfiles.isNotEmpty()) {
            seedDefaultAdBlockIfNeeded()
            backfillBuiltInExtensionHashes(existingProfiles.first().id)
            return existingProfiles.first().id
        }

        val personalId = profileDao.insert(
            ProfileEntity(name = "Personal", iconKey = "person", isGuest = false, webProfileName = "weblio_personal")
        )
        val workId = profileDao.insert(
            ProfileEntity(name = "Work", iconKey = "work", isGuest = false, webProfileName = "weblio_work")
        )
        val guestId = profileDao.insert(
            ProfileEntity(name = "Guest", iconKey = "guest", isGuest = true, webProfileName = "weblio_guest")
        )

        listOf(personalId, workId, guestId).forEach { pid ->
            seedDefaultQuickLinksForProfile(pid)
        }
        seedDefaultExtensions(personalId)
        seedDefaultAdBlockIfNeeded()
        return personalId
    }

    suspend fun seedDefaultQuickLinksForProfile(profileId: Long) {
        if (quickLinkDao.getOnce(profileId).isNotEmpty()) return
        val defaults = listOf(
            QuickLinkEntity(profileId = profileId, title = "Wikipedia", url = "https://www.wikipedia.org", iconKey = "wiki", position = 0),
            QuickLinkEntity(profileId = profileId, title = "GitHub", url = "https://github.com", iconKey = "code", position = 1),
            QuickLinkEntity(profileId = profileId, title = "YouTube", url = "https://m.youtube.com", iconKey = "video", position = 2),
            QuickLinkEntity(profileId = profileId, title = "Proton", url = "https://proton.me", iconKey = "shield", position = 3),
            QuickLinkEntity(profileId = profileId, title = "MDN Docs", url = "https://developer.mozilla.org", iconKey = "docs", position = 4),
            QuickLinkEntity(profileId = profileId, title = "Hacker News", url = "https://news.ycombinator.com", iconKey = "news", position = 5)
        )
        defaults.forEach { quickLinkDao.insert(it) }
    }

    suspend fun pruneLegacyDefaultBookmarks(profileId: Long) {
        bookmarkDao.deleteByUrlAndFolder(profileId, "https://www.wikipedia.org", "Knowledge")
        bookmarkDao.deleteByUrlAndFolder(profileId, "https://developer.mozilla.org", "Developer")
        bookmarkDao.deleteByUrlAndFolder(profileId, "https://privacytests.org", "Privacy")
    }

    private suspend fun seedDefaultExtensions(profileId: Long) {
        if (extensionDao.getOnce(profileId).isNotEmpty()) return
        builtInExtensionCatalog(profileId).take(2).forEach { extensionDao.insert(it) }
    }

    private suspend fun backfillBuiltInExtensionHashes(profileId: Long) {
        val catalogByExtId = builtInExtensionCatalog(profileId).associateBy { it.extId }
        extensionDao.getOnce(profileId).forEach { ext ->
            if (ext.contentHashSha256.isBlank() && ext.extId in catalogByExtId) {
                val computed = com.example.engine.ExtensionEngine.computeExtensionHash(ext.scriptContent, ext.cssContent)
                extensionDao.update(ext.copy(contentHashSha256 = computed))
            }
        }
    }

    private suspend fun seedDefaultAdBlockIfNeeded() {
        if (adBlockDao.getActiveRulesOnce().isNotEmpty()) return
        val coreRules = listOf(
            // Ad & Tracker Domains (Real EasyList/EasyPrivacy core entries)
            "||doubleclick.net^",
            "||googlesyndication.com^",
            "||googleadservices.com^",
            "||google-analytics.com^",
            "||googletagmanager.com^",
            "||adnxs.com^",
            "||adsrvr.org^",
            "||taboola.com^",
            "||outbrain.com^",
            "||criteo.com^",
            "||pubmatic.com^",
            "||rubiconproject.com^",
            "||openx.net^",
            "||scorecardresearch.com^",
            "||quantserve.com^",
            "||facebook.net/tr^",
            "||connect.facebook.net/signals^",
            "||hotjar.com^",
            "||mixpanel.com^",
            "||segment.io^",
            "||amplitude.com^",
            "||amazon-adsystem.com^",
            "||moatads.com^",
            "||ads-twitter.com^",
            "||tiktokv.com/api/ad^",
            "||yandex.ru/metrika^",
            "||demdex.net^",
            "||omtrdc.net^"
        ).map {
            AdBlockRuleEntity(pattern = it, ruleType = FilterRuleType.NETWORK_BLOCK.name, sourceList = "Weblo Core EasyList", enabled = true)
        }
        val cosmeticRules = listOf(
            "##.adsbygoogle",
            "##ins.adsbygoogle",
            "##div[id^='div-gpt-ad']",
            "##.ad-banner",
            "##.sponsored-content",
            "##.taboola-widget",
            "##.outbrain-widget"
        ).map {
            AdBlockRuleEntity(pattern = it, ruleType = FilterRuleType.COSMETIC_HIDE.name, sourceList = "Weblo Cosmetic Shield", enabled = true)
        }
        val dnsRules = listOf(
            "doubleclick.net",
            "pagead2.googlesyndication.com",
            "adservice.google.com",
            "analytics.google.com",
            "pixel.facebook.com",
            "ads.tiktok.com",
            "metrics.icloud.com",
            "app-measurement.com"
        ).map {
            AdBlockRuleEntity(pattern = it, ruleType = FilterRuleType.DNS_BLOCK.name, sourceList = "Weblo DNS Blocklist", enabled = true)
        }
        adBlockDao.insertRules(coreRules + cosmeticRules + dnsRules)

        if (adBlockDao.getSubscriptionsOnce().isEmpty()) {
            adBlockDao.insertSubscription(
                FilterSubscriptionEntity(
                    name = "EasyList (Core Web Ads)",
                    url = "https://easylist.to/easylist/easylist.txt",
                    enabled = true,
                    ruleCount = coreRules.size,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
            adBlockDao.insertSubscription(
                FilterSubscriptionEntity(
                    name = "Peter Lowe's Ad & Tracking List",
                    url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
                    enabled = true,
                    ruleCount = dnsRules.size,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
            adBlockDao.insertSubscription(
                FilterSubscriptionEntity(
                    name = "EasyPrivacy (Trackers & Analytics)",
                    url = "https://easylist.to/easylist/easyprivacy.txt",
                    enabled = true,
                    ruleCount = cosmeticRules.size,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun builtInExtensionCatalog(profileId: Long): List<ExtensionEntity> = listOf(
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.darkreader.engine",
            name = "Dark Reader Engine",
            version = "4.9.92-weblio",
            description = "Dynamic dark mode CSS inversion for bright websites while preserving media & images.",
            sourceType = "BUILTIN_ENGINE",
            enabled = false,
            permissionsJson = "[\"activeTab\",\"storage\",\"scripting\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_end",
            cssContent = """
                html { filter: invert(90%) hue-rotate(180deg) !important; background: #111 !important; }
                img, video, picture, canvas, iframe, [style*="background-image"] {
                    filter: invert(100%) hue-rotate(180deg) !important;
                }
            """.trimIndent(),
            scriptContent = "/* Dark Reader Weblo CSS Engine active */"
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.ublock.cosmetic",
            name = "uBlock Cosmetic & Scriptlets",
            version = "1.61.0-weblio",
            description = "Removes residual ad containers, anti-adblock overlays, and popunder listeners.",
            sourceType = "BUILTIN_ENGINE",
            enabled = true,
            permissionsJson = "[\"webRequest\",\"scripting\",\"storage\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_start",
            cssContent = ".adsbygoogle, [id^='google_ads_iframe'], .ad-container, .sponsored-post { display: none !important; }",
            scriptContent = """
                (function() {
                    try {
                        Object.defineProperty(window, 'canRunAds', { get: () => true, configurable: false });
                        window.adblockDetector = undefined;
                    } catch(e) {}
                })();
            """.trimIndent()
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.privacy.badger",
            name = "Privacy Badger Guard",
            version = "2025.1-weblio",
            description = "Strips tracking parameters (utm_*, fbclid, gclid) and blocks navigator.sendBeacon trackers.",
            sourceType = "BUILTIN_ENGINE",
            enabled = true,
            permissionsJson = "[\"tabs\",\"privacy\",\"webRequest\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_start",
            scriptContent = """
                (function() {
                    try {
                        const origBeacon = navigator.sendBeacon;
                        navigator.sendBeacon = function(url, data) {
                            if (typeof url === 'string' && /(analytics|telemetry|pixel|track|beacon)/i.test(url)) {
                                return true;
                            }
                            return origBeacon ? origBeacon.apply(this, arguments) : false;
                        };
                    } catch(e) {}
                })();
            """.trimIndent()
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.tampermonkey.host",
            name = "Tampermonkey UserScript Host",
            version = "5.3.0-weblio",
            description = "Provides GM_getValue, GM_setValue, GM_addStyle & @match UserScript execution runtime.",
            sourceType = "BUILTIN_ENGINE",
            enabled = true,
            permissionsJson = "[\"storage\",\"scripting\",\"tabs\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_start",
            scriptContent = "/* GM_* Userscript API Bridge ready */"
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.bitwarden.bridge",
            name = "Bitwarden / Vault Autofill Bridge",
            version = "2025.2-weblio",
            description = "Detects login forms and fills credentials securely from Weblo Keystore Vault.",
            sourceType = "BUILTIN_ENGINE",
            enabled = true,
            permissionsJson = "[\"activeTab\",\"storage\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_idle",
            scriptContent = "/* Autofill form observer active */"
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.cws.sponsorblock",
            name = "SponsorBlock & YouTube Clean",
            version = "5.11-weblio",
            description = "Auto-hides YouTube Shorts shelves, ad overlays, and distractions on mobile & desktop YouTube.",
            sourceType = "CHROME_WEBEXT",
            enabled = false,
            permissionsJson = "[\"activeTab\",\"storage\"]",
            matchPatternsJson = "[\"*://*.youtube.com/*\",\"*://youtu.be/*\"]",
            runAt = "document_end",
            cssContent = "ytm-reel-shelf-renderer, ytd-reel-shelf-renderer, .ytp-ad-overlay-container, #player-ads { display: none !important; }",
            scriptContent = """
                (function() {
                    try {
                        setInterval(function() {
                            var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');
                            if (skip) skip.click();
                        }, 800);
                    } catch(e) {}
                })();
            """.trimIndent()
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.cws.translator",
            name = "Google Translate Floating Bar",
            version = "2.0.16-weblio",
            description = "Adds a sleek floating Translate button on foreign-language pages using Google Translate Web.",
            sourceType = "CHROME_WEBEXT",
            enabled = false,
            permissionsJson = "[\"activeTab\",\"scripting\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_end",
            scriptContent = """
                (function() {
                    if (document.getElementById('weblio-tr-fab') || location.hostname.indexOf('translate.goog') !== -1) return;
                    var btn = document.createElement('button');
                    btn.id = 'weblio-tr-fab';
                    btn.textContent = 'Translate Page';
                    btn.style.cssText = 'position:fixed;right:14px;bottom:16px;z-index:2147483647;padding:8px 12px;border-radius:20px;border:none;background:#1D6FFF;color:#fff;font:600 12px sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.35);';
                    btn.onclick = function() {
                        location.href = 'https://translate.google.com/translate?sl=auto&tl=en&u=' + encodeURIComponent(location.href);
                    };
                    (document.body || document.documentElement).appendChild(btn);
                })();
            """.trimIndent()
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.cws.jsonformatter",
            name = "JSON Formatter Pro",
            version = "0.7.1-weblio",
            description = "Automatically formats raw API JSON responses into clean syntax-highlighted views.",
            sourceType = "CHROME_WEBEXT",
            enabled = false,
            permissionsJson = "[\"activeTab\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_end",
            scriptContent = """
                (function() {
                    try {
                        var pre = document.body && document.body.children.length === 1 && document.body.firstElementChild.tagName === 'PRE'
                            ? document.body.firstElementChild : null;
                        if (!pre) return;
                        var parsed = JSON.parse(pre.textContent);
                        pre.textContent = JSON.stringify(parsed, null, 2);
                        pre.style.cssText = 'background:#1e1e1e;color:#d4d4d4;padding:16px;font:13px/1.5 monospace;white-space:pre-wrap;word-break:break-word;';
                    } catch(e) {}
                })();
            """.trimIndent()
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.cws.readermode",
            name = "Reader View & Clean Typography",
            version = "3.4.0-weblio",
            description = "Improves article readability by widening text columns, setting crisp line height, and dimming sidebars.",
            sourceType = "CHROME_WEBEXT",
            enabled = false,
            permissionsJson = "[\"activeTab\",\"scripting\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_end",
            cssContent = "article, main { max-width: 72ch !important; margin: 0 auto !important; line-height: 1.65 !important; font-size: 17px !important; } aside, [class*='sidebar'], [class*='newsletter'] { opacity: 0.2 !important; }",
            scriptContent = "/* Reader View typography stylesheet active */"
        ),
        ExtensionEntity(
            profileId = profileId,
            extId = "weblio.cws.cookiehider",
            name = "I Still Don't Care About Cookies",
            version = "1.1.4-weblio",
            description = "Hides annoying GDPR cookie consent banners & overlays automatically across websites.",
            sourceType = "CHROME_WEBEXT",
            enabled = false,
            permissionsJson = "[\"scripting\",\"storage\"]",
            matchPatternsJson = "[\"<all_urls>\"]",
            runAt = "document_end",
            cssContent = "#onetrust-banner-sdk, .fc-consent-root, .qc-cmp2-container, [id*='cookie-banner'], [class*='cookie-consent'] { display: none !important; } body { overflow: auto !important; }",
            scriptContent = "/* Cookie banner suppressor active */"
        )
    ).map { ext ->
        ext.copy(
            contentHashSha256 = com.example.engine.ExtensionEngine.computeExtensionHash(ext.scriptContent, ext.cssContent)
        )
    }

    // Profile operations
    suspend fun getProfile(id: Long): ProfileEntity? = profileDao.getById(id)
    suspend fun createProfile(name: String, iconKey: String): Long {
        val id = profileDao.insert(
            ProfileEntity(
                name = name.trim(),
                iconKey = iconKey,
                isGuest = false,
                webProfileName = "weblio_profile_${System.currentTimeMillis()}"
            )
        )
        seedDefaultQuickLinksForProfile(id)
        return id
    }
    suspend fun updateProfile(profile: ProfileEntity) = profileDao.update(profile)
    suspend fun deleteProfile(profile: ProfileEntity) {
        if (profile.isGuest) return
        tabDao.deleteAllForProfile(profile.id)
        tabDao.clearClosedTabs(profile.id)
        bookmarkDao.deleteAllForProfile(profile.id)
        historyDao.clearAllForProfile(profile.id)
        downloadDao.clearForProfile(profile.id)
        passwordDao.clearForProfile(profile.id)
        extensionDao.deleteAllForProfile(profile.id)
        sitePermissionDao.deleteAllForProfile(profile.id)
        profileDao.delete(profile)
    }

    // Tab operations
    suspend fun createTab(
        profileId: Long,
        url: String = "weblio://home",
        title: String = "New Tab",
        isIncognito: Boolean = false,
        groupName: String? = null,
        desktopMode: Boolean = false
    ): Long {
        val current = tabDao.getTabsOnce(profileId)
        val nextPos = (current.maxOfOrNull { it.position } ?: -1) + 1
        return tabDao.insert(
            TabEntity(
                profileId = profileId,
                url = url,
                title = title,
                isIncognito = isIncognito,
                groupName = groupName,
                desktopMode = desktopMode,
                position = nextPos
            )
        )
    }
    suspend fun updateTab(tab: TabEntity) = tabDao.update(tab)
    suspend fun updateTabTitle(tabId: Long, title: String) = tabDao.updateTitle(tabId, title)
    suspend fun updateTabUrlAndTitle(tabId: Long, url: String, title: String, lastActiveAt: Long = System.currentTimeMillis()) =
        tabDao.updateUrlAndTitle(tabId, url, title, lastActiveAt)
    suspend fun closeTab(tab: TabEntity) {
        if (!tab.isIncognito && tab.url != "weblio://home") {
            tabDao.insertClosedTab(ClosedTabEntity(profileId = tab.profileId, url = tab.url, title = tab.title))
        }
        tabDao.deleteById(tab.id)
    }
    suspend fun closeAllTabs(profileId: Long, incognitoOnly: Boolean? = null) {
        when (incognitoOnly) {
            null -> tabDao.deleteAllForProfile(profileId)
            true -> tabDao.deleteAllByType(profileId, true)
            false -> tabDao.deleteAllByType(profileId, false)
        }
    }
    suspend fun reopenClosedTab(closed: ClosedTabEntity): Long {
        tabDao.deleteClosedTab(closed.id)
        return createTab(closed.profileId, url = closed.url, title = closed.title, isIncognito = false)
    }

    // Bookmarks & History
    suspend fun addBookmark(profileId: Long, title: String, url: String, folder: String = "General") {
        bookmarkDao.insert(BookmarkEntity(profileId = profileId, title = title.ifBlank { url }, url = url, folder = folder.ifBlank { "General" }))
    }
    suspend fun updateBookmark(bookmark: BookmarkEntity) = bookmarkDao.update(bookmark)
    suspend fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkDao.delete(bookmark)
    suspend fun removeBookmarkByUrl(profileId: Long, url: String) = bookmarkDao.deleteByUrl(profileId, url)

    suspend fun recordHistory(profileId: Long, title: String, url: String, isIncognito: Boolean, isGuest: Boolean) {
        if (isIncognito || isGuest || url.startsWith("weblio://") || url.isBlank()) return
        historyDao.insert(HistoryEntity(profileId = profileId, title = title.ifBlank { url }, url = url))
    }
    suspend fun deleteHistoryEntry(id: Long) = historyDao.deleteById(id)
    suspend fun clearHistorySince(profileId: Long, sinceMillis: Long) = historyDao.deleteSince(profileId, sinceMillis)
    suspend fun clearAllHistory(profileId: Long) = historyDao.clearAllForProfile(profileId)

    // Passwords (always encrypted via KeystoreCryptoManager!)
    suspend fun savePassword(profileId: Long, host: String, username: String, plainPassword: String) {
        val enc = KeystoreCryptoManager.encryptSecret(plainPassword)
        val existing = passwordDao.findByHost(profileId, host).firstOrNull { it.username == username }
        if (existing != null) {
            passwordDao.update(existing.copy(encryptedPassword = enc.cipherTextBase64, iv = enc.ivBase64, updatedAt = System.currentTimeMillis()))
        } else {
            passwordDao.insert(
                PasswordEntity(
                    profileId = profileId,
                    originHost = host,
                    username = username,
                    encryptedPassword = enc.cipherTextBase64,
                    iv = enc.ivBase64
                )
            )
        }
    }

    suspend fun getPasswordsForHost(profileId: Long, host: String): List<Pair<PasswordEntity, String>> {
        return passwordDao.findByHost(profileId, host).mapNotNull { entity ->
            runCatching {
                entity to KeystoreCryptoManager.decryptSecret(entity.encryptedPassword, entity.iv)
            }.getOrNull()
        }
    }

    fun decryptPassword(entity: PasswordEntity): String? {
        return runCatching { KeystoreCryptoManager.decryptSecret(entity.encryptedPassword, entity.iv) }.getOrNull()
    }

    suspend fun deletePassword(entity: PasswordEntity) = passwordDao.delete(entity)

    // Quick Links
    suspend fun addQuickLink(profileId: Long, title: String, url: String, iconKey: String = "globe") {
        val count = quickLinkDao.getOnce(profileId).size
        quickLinkDao.insert(QuickLinkEntity(profileId = profileId, title = title, url = url, iconKey = iconKey, position = count))
    }
    suspend fun updateQuickLink(link: QuickLinkEntity) = quickLinkDao.update(link)
    suspend fun deleteQuickLink(link: QuickLinkEntity) = quickLinkDao.delete(link)

    // Extensions
    suspend fun upsertExtension(ext: ExtensionEntity): Long = extensionDao.insert(ext)
    suspend fun updateExtension(ext: ExtensionEntity) = extensionDao.update(ext)
    suspend fun deleteExtension(ext: ExtensionEntity) = extensionDao.delete(ext)

    // AdBlock
    suspend fun getActiveAdBlockRules(): List<AdBlockRuleEntity> = adBlockDao.getActiveRulesOnce()
    suspend fun addAdBlockRule(pattern: String, type: FilterRuleType, sourceList: String = "Custom User Rules") {
        adBlockDao.insertRule(AdBlockRuleEntity(pattern = pattern.trim(), ruleType = type.name, sourceList = sourceList, enabled = true))
    }
    suspend fun toggleAdBlockRule(rule: AdBlockRuleEntity) = adBlockDao.updateRule(rule.copy(enabled = !rule.enabled))
    suspend fun deleteAdBlockRule(rule: AdBlockRuleEntity) = adBlockDao.deleteRule(rule)
    suspend fun replaceRulesForSource(source: String, rules: List<AdBlockRuleEntity>) {
        adBlockDao.deleteBySource(source)
        adBlockDao.insertRules(rules)
    }
    suspend fun addSubscription(name: String, url: String) = adBlockDao.insertSubscription(FilterSubscriptionEntity(name = name, url = url))
    suspend fun updateSubscription(sub: FilterSubscriptionEntity) = adBlockDao.updateSubscription(sub)
    suspend fun deleteSubscription(sub: FilterSubscriptionEntity) = adBlockDao.deleteSubscription(sub)
    suspend fun getSubscriptionsOnce(): List<FilterSubscriptionEntity> = adBlockDao.getSubscriptionsOnce()
    suspend fun incrementRuleHit(ruleId: Long) = adBlockDao.incrementHit(ruleId)
    suspend fun incrementRuleHitsBy(ruleId: Long, delta: Long) = adBlockDao.incrementHitBy(ruleId, delta)

    // Site Permissions
    suspend fun getSitePermission(profileId: Long, host: String): SitePermissionEntity? = sitePermissionDao.getForHost(profileId, host)
    suspend fun upsertSitePermission(perm: SitePermissionEntity): Long = sitePermissionDao.upsert(perm)
    suspend fun deleteSitePermission(perm: SitePermissionEntity) = sitePermissionDao.delete(perm)

    // Downloads
    suspend fun insertDownload(entity: DownloadEntity): Long = downloadDao.insert(entity)
    suspend fun updateDownload(entity: DownloadEntity) = downloadDao.update(entity)
    suspend fun getDownload(id: Long): DownloadEntity? = downloadDao.getById(id)
    suspend fun deleteDownload(id: Long) = downloadDao.deleteById(id)
    suspend fun clearDownloads(profileId: Long) = downloadDao.clearForProfile(profileId)

    // Security Events & Threat Intelligence
    suspend fun recordSecurityEvent(
        category: String,
        severity: String,
        title: String,
        summary: String,
        targetHostOrFile: String = "",
        actionTaken: String = ""
    ): Long = securityEventDao.insertEvent(
        SecurityEventEntity(
            category = category,
            severity = severity,
            title = title,
            summary = summary,
            targetHostOrFile = targetHostOrFile,
            actionTaken = actionTaken
        )
    )

    suspend fun clearSecurityEvents() = securityEventDao.clearEvents()
    suspend fun findThreatIndicator(indicator: String, type: String): ThreatIndicatorEntity? =
        securityEventDao.findThreat(indicator, type)
    suspend fun countThreatIndicators(): Int = securityEventDao.countThreats()
    suspend fun upsertThreatIndicators(items: List<ThreatIndicatorEntity>) = securityEventDao.upsertThreats(items)
    suspend fun pruneExpiredThreatIndicators(olderThanMillis: Long) = securityEventDao.pruneExpiredThreats(olderThanMillis)
}
