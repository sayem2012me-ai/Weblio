package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.PersistableBundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.WeblioApplication
import com.example.data.db.AdBlockRuleEntity
import com.example.data.db.BookmarkEntity
import com.example.data.db.ClosedTabEntity
import com.example.data.db.DownloadEntity
import com.example.data.db.ExtensionEntity
import com.example.data.db.FilterRuleType
import com.example.data.db.FilterSubscriptionEntity
import com.example.data.db.HistoryEntity
import com.example.data.db.PasswordEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.QuickLinkEntity
import com.example.data.db.SecurityEventEntity
import com.example.data.db.SitePermissionEntity
import com.example.data.db.TabEntity
import com.example.data.prefs.BrowserSettings
import com.example.data.prefs.CookiePolicy
import com.example.data.prefs.DnsFilterMode
import com.example.data.prefs.SearchEngine
import com.example.data.prefs.StartupBehavior
import com.example.data.prefs.WallpaperStyle
import com.example.data.security.KeystoreCryptoManager
import com.example.engine.AdBlockEngine
import com.example.engine.BackupRestoreManager
import com.example.engine.ExtensionEngine
import com.example.engine.FilterUpdateWorker
import com.example.engine.PendingQuarantineReview
import com.example.engine.ProfileIsolationManager
import com.example.engine.SearchSuggestionEngine
import com.example.engine.WeblioDownloadManager
import com.example.data.db.DownloadStatus
import com.example.security.AppIntegrityReport
import com.example.security.SecurityNotifications
import com.example.security.SecurityState
import com.example.security.UpdateMetadata
import com.example.security.UpdateVerificationResult
import com.example.security.WebsiteThreatReport
import com.example.security.WebsiteVerdict
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class TabRuntimeState(
    val tabId: Long,
    val currentUrl: String = "weblio://home",
    val title: String = "New Tab",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isSecureHttps: Boolean = false,
    val findInPageOpen: Boolean = false,
    val findQuery: String = "",
    val findActiveMatch: Int = 0,
    val findTotalMatches: Int = 0,
    val isMediaPlaying: Boolean = false,
    val isFullScreenVideo: Boolean = false,
    val blockedOnPage: Int = 0,
    val errorBanner: String? = null,
    val isDesktopMode: Boolean = false,
    val webViewInstanceVersion: Int = 0,
    val rendererCrashCount: Int = 0
)

data class SslErrorPrompt(
    val tabId: Long,
    val url: String,
    val primaryError: Int,
    val issuedTo: String?,
    val issuedBy: String?,
    val onCancel: () -> Unit
)

data class PendingCredentialPrompt(
    val host: String,
    val username: String,
    val password: String
)

data class PendingDownloadPrompt(
    val url: String,
    val fileName: String,
    val mimeType: String,
    val userAgent: String?
)

data class PendingWebsiteWarning(
    val tabId: Long,
    val report: WebsiteThreatReport
)

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WeblioApplication
    val repo = app.repository
    val prefs = app.preferences
    val adBlock = app.adBlockEngine
    val extensionEngine = app.extensionEngine
    val threatEngine = app.threatEngine
    val quarantineManager = app.quarantineManager
    val downloadManager = app.downloadManager
    val integrityManager = app.integrityManager
    val updateVerifier = app.updateVerifier
    val backupManager = app.backupManager

    /** Live WebView instances keyed by Tab ID so tabs stay alive across switching & background playback. */
    private val webViewRegistry = mutableMapOf<Long, WebView>()
    private val lastDispatchedUrl = mutableMapOf<Long, String>()

    val settings: StateFlow<BrowserSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, BrowserSettings())

    val profiles: StateFlow<List<ProfileEntity>> = repo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeProfileId: StateFlow<Long> = settings.map { it.activeProfileId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1L)

    val activeProfile: StateFlow<ProfileEntity?> = combine(profiles, activeProfileId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tabs: StateFlow<List<TabEntity>> = activeProfileId
        .flatMapLatest { repo.observeTabs(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val closedTabs: StateFlow<List<ClosedTabEntity>> = activeProfileId
        .flatMapLatest { repo.observeClosedTabs(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = activeProfileId
        .flatMapLatest { repo.observeBookmarks(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<HistoryEntity>> = activeProfileId
        .flatMapLatest { repo.observeHistory(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = activeProfileId
        .flatMapLatest { repo.observeDownloads(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lightweight root security flags so progress ticks on active downloads don't recompose browser chrome. */
    val quarantineSecurityFlags: StateFlow<Pair<Boolean, Boolean>> = activeProfileId
        .flatMapLatest { repo.observeDownloads(it) }
        .map { list ->
            val hasDangerous = list.any { it.status == DownloadStatus.QUARANTINED.name && it.securityVerdict == "DANGEROUS" }
            val hasAny = list.any { it.status == DownloadStatus.QUARANTINED.name }
            hasDangerous to hasAny
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false to false)

    val passwords: StateFlow<List<PasswordEntity>> = activeProfileId
        .flatMapLatest { repo.observePasswords(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quickLinks: StateFlow<List<QuickLinkEntity>> = activeProfileId
        .flatMapLatest { repo.observeQuickLinks(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val extensions: StateFlow<List<ExtensionEntity>> = activeProfileId
        .flatMapLatest { repo.observeExtensions(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val adBlockRules: StateFlow<List<AdBlockRuleEntity>> = repo.observeAdBlockRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filterSubscriptions: StateFlow<List<FilterSubscriptionEntity>> = repo.observeFilterSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sitePermissions: StateFlow<List<SitePermissionEntity>> = activeProfileId
        .flatMapLatest { repo.observeSitePermissions(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _activeTabId = MutableStateFlow<Long?>(null)
    val activeTabId: StateFlow<Long?> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<TabEntity?> = combine(tabs, activeTabId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _tabRuntime = MutableStateFlow<Map<Long, TabRuntimeState>>(emptyMap())
    val tabRuntime: StateFlow<Map<Long, TabRuntimeState>> = _tabRuntime.asStateFlow()

    private val _tabPreviews = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val tabPreviews: StateFlow<Map<Long, Bitmap>> = _tabPreviews.asStateFlow()

    private val previewCacheDir: File by lazy {
        File(app.cacheDir, "tab_previews").apply { mkdirs() }
    }

    private val _isStartupReady = MutableStateFlow(false)
    val isStartupReady: StateFlow<Boolean> = _isStartupReady.asStateFlow()

    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    private val _pendingProfileUnlock = MutableStateFlow<ProfileEntity?>(null)
    val pendingProfileUnlock: StateFlow<ProfileEntity?> = _pendingProfileUnlock.asStateFlow()

    private val _sslPrompt = MutableStateFlow<SslErrorPrompt?>(null)
    val sslPrompt: StateFlow<SslErrorPrompt?> = _sslPrompt.asStateFlow()

    private val _credentialPrompt = MutableStateFlow<PendingCredentialPrompt?>(null)
    val credentialPrompt: StateFlow<PendingCredentialPrompt?> = _credentialPrompt.asStateFlow()

    private val _downloadPrompt = MutableStateFlow<PendingDownloadPrompt?>(null)
    val downloadPrompt: StateFlow<PendingDownloadPrompt?> = _downloadPrompt.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isPasswordVaultUnlocked = MutableStateFlow(false)
    val isPasswordVaultUnlocked: StateFlow<Boolean> = _isPasswordVaultUnlocked.asStateFlow()

    private val _activeExtensionPopupId = MutableStateFlow<Long?>(null)
    val activeExtensionPopup: StateFlow<ExtensionEntity?> = combine(extensions, _activeExtensionPopupId) { list, id ->
        id?.let { targetId -> list.firstOrNull { it.id == targetId } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val securityEvents: StateFlow<List<SecurityEventEntity>> = repo.observeSecurityEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingQuarantineReviews: StateFlow<List<PendingQuarantineReview>> = downloadManager.pendingReviews

    private val _pendingWebsiteWarning = MutableStateFlow<PendingWebsiteWarning?>(null)
    val pendingWebsiteWarning: StateFlow<PendingWebsiteWarning?> = _pendingWebsiteWarning.asStateFlow()

    private val _appIntegrityReport = MutableStateFlow<AppIntegrityReport?>(null)
    val appIntegrityReport: StateFlow<AppIntegrityReport?> = _appIntegrityReport.asStateFlow()

    private val _threatDbCount = MutableStateFlow(0)
    val threatDbCount: StateFlow<Int> = _threatDbCount.asStateFlow()

    private val _isUpdatingThreatDb = MutableStateFlow(false)
    val isUpdatingThreatDb: StateFlow<Boolean> = _isUpdatingThreatDb.asStateFlow()

    private val _isInstallingExtension = MutableStateFlow(false)
    val isInstallingExtension: StateFlow<Boolean> = _isInstallingExtension.asStateFlow()

    private val _pendingExtensionReview = MutableStateFlow<ExtensionEntity?>(null)
    val pendingExtensionReview: StateFlow<ExtensionEntity?> = _pendingExtensionReview.asStateFlow()

    private val suggestionEngine = SearchSuggestionEngine()
    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private val _isLoadingSuggestions = MutableStateFlow(false)
    val isLoadingSuggestions: StateFlow<Boolean> = _isLoadingSuggestions.asStateFlow()

    private var suggestionJob: Job? = null
    private var oneTimeAllowedNavUrl: String? = null
    private var sessionThreatHits: Int = 0
    private var clipboardClearJob: Job? = null
    private var pendingBlockedDelta = AtomicLong(0L)
    private var blockedFlushJob: Job? = null
    private val pendingPageBlockedCounts = ConcurrentHashMap<Long, AtomicLong>()
    private val pageBlockedFlushScheduled = AtomicBoolean(false)
    private val lastPreviewCaptureMeta = mutableMapOf<Long, Pair<String, Long>>()
    private val dispatchedWebViewIdentity = mutableMapOf<Long, Int>()
    private val tabLastLeftMs = mutableMapOf<Long, Long>()
    private val webViewLruOrder = ArrayDeque<Long>()
    private var lastBackgroundedAtMs: Long = 0L

    init {
        viewModelScope.launch {
            try {
                val startup = withContext(Dispatchers.IO) {
                    val defaultProfileId = repo.ensureDefaultsSeeded()
                    if (!prefs.isDefaultBookmarksCleanedOnce()) {
                        repo.observeProfiles().first().forEach { profile ->
                            repo.pruneLegacyDefaultBookmarks(profile.id)
                        }
                        prefs.markDefaultBookmarksCleaned()
                    }
                    val currentSettings = prefs.settingsFlow.first()
                    adBlock.globalEnabled = currentSettings.adBlockerEnabled
                    adBlock.trackerProtectionEnabled = currentSettings.trackerProtectionEnabled
                    adBlock.thirdPartyBlockingEnabled = currentSettings.thirdPartyRequestBlocking

                    val profileId = repo.getProfile(currentSettings.activeProfileId)?.id ?: defaultProfileId
                    if (profileId != currentSettings.activeProfileId) {
                        prefs.setActiveProfile(profileId)
                    }

                    var hadIncognitoTabs = false
                    if (currentSettings.clearIncognitoOnExit) {
                        val beforeTabs = repo.observeTabs(profileId).first()
                        hadIncognitoTabs = beforeTabs.any { it.isIncognito }
                        if (hadIncognitoTabs) {
                            repo.closeAllTabs(profileId, incognitoOnly = true)
                        }
                    }

                    val existingTabs = repo.observeTabs(profileId).first()

                    val initialActiveTabId = when (currentSettings.startupBehavior) {
                        StartupBehavior.HOME_SCREEN -> {
                            repo.createTab(profileId, url = "weblio://home", title = "Home")
                        }
                        StartupBehavior.CUSTOM_HOMEPAGE -> {
                            val hp = currentSettings.homepageUrl.ifBlank { "weblio://home" }
                            repo.createTab(profileId, url = hp, title = if (hp == "weblio://home") "Home" else hp)
                        }
                        StartupBehavior.RESTORE_TABS -> {
                            if (existingTabs.isEmpty()) {
                                repo.createTab(profileId, url = "weblio://home", title = "Home")
                            } else {
                                existingTabs.maxByOrNull { it.lastActiveAt }?.id ?: existingTabs.first().id
                            }
                        }
                    }
                    Triple(currentSettings, initialActiveTabId, Pair(existingTabs, hadIncognitoTabs))
                }

                val currentSettings = startup.first
                if (currentSettings.biometricAppLockEnabled) {
                    _isAppLocked.value = true
                }
                _activeTabId.value = startup.second
                val existingTabs = startup.third.first
                val hadIncognitoTabs = startup.third.second
                _isStartupReady.value = true

                if (currentSettings.startupBehavior == StartupBehavior.RESTORE_TABS && existingTabs.isNotEmpty()) {
                    loadCachedTabPreviews(existingTabs)
                }
                if (hadIncognitoTabs) {
                    viewModelScope.launch {
                        delay(1_500L)
                        ProfileIsolationManager.clearEphemeralData()
                    }
                }
            } finally {
                _isStartupReady.value = true
            }
        }

        viewModelScope.launch {
            settings.collect { s ->
                adBlock.globalEnabled = s.adBlockerEnabled
                adBlock.trackerProtectionEnabled = s.trackerProtectionEnabled
                adBlock.thirdPartyBlockingEnabled = s.thirdPartyRequestBlocking
                downloadManager.securityNotificationsEnabled = s.securityNotificationsEnabled
            }
        }

        viewModelScope.launch {
            app.trimMemoryEvents.collect { level ->
                onTrimMemory(level)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            delay(1_200L)
            runIntegrityCheck()
            _threatDbCount.value = repo.countThreatIndicators()
            val activeQuarantinePaths = repo.observeDownloads(activeProfileId.value).first()
                .filter { it.quarantined }
                .map { it.localPath }
                .toSet()
            downloadManager.cleanupAbandonedQuarantine(activeQuarantinePaths)
        }
    }

    fun getLiveWebViewCount(): Int = webViewRegistry.size

    fun registerWebView(tabId: Long, webView: WebView) {
        webViewRegistry[tabId] = webView
        touchWebViewLru(tabId)
        if (_activeTabId.value == tabId || activeTab.value?.id == tabId) {
            resumeWebViewForForeground(webView)
        } else {
            pauseWebViewForBackground(tabId, webView)
        }
    }

    fun getWebView(tabId: Long): WebView? = webViewRegistry[tabId]

    private fun touchWebViewLru(tabId: Long) {
        webViewLruOrder.remove(tabId)
        webViewLruOrder.addLast(tabId)
        evictBackgroundWebViews(keepTotal = MAX_LIVE_WEBVIEWS)
    }

    fun evictBackgroundWebViews(keepTotal: Int = MAX_LIVE_WEBVIEWS) {
        if (webViewRegistry.size <= keepTotal) return
        val currentActiveId = _activeTabId.value ?: activeTab.value?.id
        val iterator = webViewLruOrder.iterator()
        val toEvict = mutableListOf<Long>()
        var remainingCount = webViewRegistry.size
        while (iterator.hasNext() && remainingCount > keepTotal) {
            val candidateId = iterator.next()
            if (candidateId == currentActiveId) continue
            if (_tabRuntime.value[candidateId]?.isMediaPlaying == true) continue
            if (webViewRegistry.containsKey(candidateId)) {
                toEvict.add(candidateId)
                iterator.remove()
                remainingCount--
            } else {
                iterator.remove()
            }
        }
        toEvict.forEach { evictId ->
            destroyWebViewOnly(evictId)
        }
    }

    private fun destroyWebViewOnly(tabId: Long) {
        lastDispatchedUrl.remove(tabId)
        dispatchedWebViewIdentity.remove(tabId)
        webViewLruOrder.remove(tabId)
        webViewRegistry.remove(tabId)?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            onPause()
            (parent as? android.view.ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
    }

    private fun pauseWebViewForBackground(tabId: Long, webView: WebView) {
        if (_tabRuntime.value[tabId]?.isMediaPlaying == true) return
        runCatching {
            webView.onPause()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
            }
        }
    }

    private fun resumeWebViewForForeground(webView: WebView) {
        runCatching {
            webView.onResume()
            webView.resumeTimers()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        val keepCount = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 1
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> 2
            else -> MAX_LIVE_WEBVIEWS
        }
        if (keepCount < MAX_LIVE_WEBVIEWS) {
            evictBackgroundWebViews(keepTotal = keepCount)
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            _tabPreviews.value = emptyMap()
        }
    }

    fun ensureTabPreviewsLoaded() {
        val currentTabs = tabs.value
        if (currentTabs.isEmpty()) return
        val existingPreviews = _tabPreviews.value
        val needsLoad = currentTabs.any { it.url != "weblio://home" && !existingPreviews.containsKey(it.id) }
        if (needsLoad) {
            loadCachedTabPreviews(currentTabs)
        }
    }

    fun unregisterWebView(tabId: Long) {
        lastDispatchedUrl.remove(tabId)
        dispatchedWebViewIdentity.remove(tabId)
        lastPreviewCaptureMeta.remove(tabId)
        webViewLruOrder.remove(tabId)
        val wasPlayingMedia = _tabRuntime.value[tabId]?.isMediaPlaying == true
        webViewRegistry.remove(tabId)?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            onPause()
            clearHistory()
            (parent as? android.view.ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
        _tabRuntime.update { it - tabId }
        if (wasPlayingMedia && _tabRuntime.value.values.none { it.isMediaPlaying }) {
            runCatching {
                app.startService(
                    android.content.Intent(app, com.example.engine.WeblioMediaService::class.java).apply {
                        action = com.example.engine.WeblioMediaService.ACTION_STOP
                    }
                )
            }
        }
    }

    fun handleRendererProcessGone(tabId: Long, crashedView: WebView?, didCrash: Boolean) {
        if (crashedView == null || webViewRegistry[tabId] === crashedView) {
            webViewRegistry.remove(tabId)
        }
        webViewLruOrder.remove(tabId)
        lastDispatchedUrl.remove(tabId)
        dispatchedWebViewIdentity.remove(tabId)
        val previous = _tabRuntime.value[tabId] ?: TabRuntimeState(tabId = tabId)
        val nextCrashes = previous.rendererCrashCount + 1
        // Always auto-recover when OS reclaimed a background/idle renderer to prevent blank pages
        val canAutoRecover = !didCrash
        updateRuntimeState(tabId) {
            it.copy(
                isLoading = false,
                progress = 0,
                isMediaPlaying = false,
                isFullScreenVideo = false,
                webViewInstanceVersion = it.webViewInstanceVersion + 1,
                rendererCrashCount = nextCrashes,
                errorBanner = if (canAutoRecover) null else {
                    "Webpage renderer stopped unexpectedly. Tap Reload to restore this tab."
                }
            )
        }
    }

    fun retryTabLoad(tabId: Long) {
        recoverStaleTab(tabId, forceRecreateWebView = webViewRegistry[tabId] == null)
    }

    fun recoverStaleTab(tabId: Long, forceRecreateWebView: Boolean = false) {
        val tab = tabs.value.firstOrNull { it.id == tabId } ?: return
        val targetUrl = (_tabRuntime.value[tabId]?.currentUrl ?: tab.url)
            .takeIf { it.isNotBlank() && it != "weblio://home" } ?: return
        lastDispatchedUrl.remove(tabId)
        dispatchedWebViewIdentity.remove(tabId)
        if (forceRecreateWebView) {
            destroyWebViewOnly(tabId)
        }
        val wv = webViewRegistry[tabId]
        updateRuntimeState(tabId) {
            it.copy(
                errorBanner = null,
                isLoading = true,
                progress = 5,
                rendererCrashCount = 0,
                webViewInstanceVersion = if (wv == null) it.webViewInstanceVersion + 1 else it.webViewInstanceVersion
            )
        }
        if (wv != null) {
            resumeWebViewForForeground(wv)
            touchWebViewLru(tabId)
            lastDispatchedUrl[tabId] = targetUrl
            dispatchedWebViewIdentity[tabId] = System.identityHashCode(wv)
            wv.loadUrl(targetUrl)
        }
    }

    fun onAppBackgrounded() {
        val now = System.currentTimeMillis()
        lastBackgroundedAtMs = now
        activeTab.value?.id?.let { tabLastLeftMs[it] = now }
        webViewRegistry.forEach { (tabId, wv) ->
            pauseWebViewForBackground(tabId, wv)
        }
    }

    fun onAppResumed(
        staleTabThresholdMs: Long = STALE_TAB_RELOAD_MS
    ) {
        val activeId = _activeTabId.value ?: activeTab.value?.id
        activeId?.let { id ->
            webViewRegistry[id]?.let { wv ->
                resumeWebViewForForeground(wv)
                touchWebViewLru(id)
            }
        }
        val bgAt = lastBackgroundedAtMs
        if (bgAt <= 0L) return
        lastBackgroundedAtMs = 0L
        val now = System.currentTimeMillis()
        val elapsed = (now - bgAt).coerceAtLeast(0L)
        val active = activeTab.value ?: return
        val currentUrl = _tabRuntime.value[active.id]?.currentUrl ?: active.url
        if (currentUrl.isNotBlank() && currentUrl != "weblio://home") {
            val wv = webViewRegistry[active.id]
            val surfaceMissing = wv == null || wv.url.isNullOrBlank() || wv.contentHeight == 0
            if (elapsed >= staleTabThresholdMs || surfaceMissing) {
                recoverStaleTab(active.id, forceRecreateWebView = wv?.url.isNullOrBlank())
            }
        }
    }

    fun dismissTabErrorBanner(tabId: Long) {
        updateRuntimeState(tabId) { it.copy(errorBanner = null) }
    }

    fun markUrlDispatched(tabId: Long, url: String) {
        lastDispatchedUrl[tabId] = url
    }

    fun shouldDispatchInitialLoad(tabId: Long, url: String, webView: WebView): Boolean {
        if (url == "weblio://home" || url.isBlank()) return false
        val currentWvUrl = webView.url
        val wvHash = System.identityHashCode(webView)
        if (!currentWvUrl.isNullOrBlank() && (currentWvUrl == url || webView.originalUrl == url)) return false
        if (!currentWvUrl.isNullOrBlank() && lastDispatchedUrl[tabId] == url && dispatchedWebViewIdentity[tabId] == wvHash) {
            return false
        }
        lastDispatchedUrl[tabId] = url
        dispatchedWebViewIdentity[tabId] = wvHash
        return true
    }

    private fun loadCachedTabPreviews(existingTabs: List<TabEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = mutableMapOf<Long, Bitmap>()
            existingTabs.forEach { tab ->
                if (tab.url != "weblio://home") {
                    val f = File(previewCacheDir, "tab_${tab.id}.jpg")
                    if (f.exists()) {
                        runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { bmp ->
                            loaded[tab.id] = bmp
                        }
                    }
                }
            }
            if (loaded.isNotEmpty()) {
                _tabPreviews.update { loaded + it }
            }
        }
    }

    fun captureActiveTabPreview() {
        val current = activeTab.value ?: return
        val url = _tabRuntime.value[current.id]?.currentUrl ?: current.url
        if (url != "weblio://home") {
            captureTabPreview(current.id)
        }
    }

    fun captureTabPreview(tabId: Long, force: Boolean = false) {
        val wv = webViewRegistry[tabId] ?: return
        if (!wv.isAttachedToWindow || wv.width <= 0 || wv.height <= 0 || wv.contentHeight <= 0) return
        val rt = _tabRuntime.value[tabId]
        val currentUrl = rt?.currentUrl ?: wv.url
        if (currentUrl == null || currentUrl == "weblio://home") return

        val now = System.currentTimeMillis()
        val prev = lastPreviewCaptureMeta[tabId]
        if (!force && prev != null && prev.first == currentUrl && (now - prev.second) < 3_000L && _tabPreviews.value[tabId] != null) {
            return
        }
        lastPreviewCaptureMeta[tabId] = currentUrl to now

        runCatching {
            val targetWidth = 420
            val scale = targetWidth.toFloat() / wv.width.toFloat()
            val targetHeight = (wv.height * scale).roundToInt().coerceIn(220, 740)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            wv.draw(canvas)

            // Skip overwriting an existing preview if the page hasn't painted yet (uniform blank frame)
            if (isBitmapUniformBlank(bitmap) && (_tabPreviews.value[tabId] != null || rt?.isLoading == true)) {
                bitmap.recycle()
                return
            }

            _tabPreviews.update { it + (tabId to bitmap) }

            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val outFile = File(previewCacheDir, "tab_${tabId}.jpg")
                    FileOutputStream(outFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, fos)
                    }
                }
            }
        }
    }

    private fun isBitmapUniformBlank(bitmap: Bitmap): Boolean {
        val sampleCols = 6
        val sampleRows = 8
        val stepX = (bitmap.width / sampleCols).coerceAtLeast(1)
        val stepY = (bitmap.height / sampleRows).coerceAtLeast(1)
        val firstPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        for (y in stepY until bitmap.height step stepY) {
            for (x in stepX until bitmap.width step stepX) {
                if (bitmap.getPixel(x, y) != firstPixel) return false
            }
        }
        return true
    }

    private fun deleteTabPreview(tabId: Long) {
        lastPreviewCaptureMeta.remove(tabId)
        _tabPreviews.update { it - tabId }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(previewCacheDir, "tab_${tabId}.jpg").delete() }
        }
    }

    fun unlockApp() {
        _isAppLocked.value = false
    }

    fun setPasswordVaultUnlocked(unlocked: Boolean) {
        _isPasswordVaultUnlocked.value = unlocked
    }

    fun updateSearchQuery(rawQuery: String, engine: SearchEngine = settings.value.defaultSearchEngine) {
        suggestionJob?.cancel()
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2 || SearchSuggestionEngine.looksLikeExplicitUrl(trimmed)) {
            _searchSuggestions.value = emptyList()
            _isLoadingSuggestions.value = false
            return
        }
        suggestionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(160L)
            _isLoadingSuggestions.value = true
            val results = runCatching {
                suggestionEngine.fetchSuggestions(trimmed, engine)
            }.getOrDefault(emptyList())
            _searchSuggestions.value = results
            _isLoadingSuggestions.value = false
        }
    }

    fun clearSearchSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.value = emptyList()
        _isLoadingSuggestions.value = false
    }

    fun copyToClipboard(text: String, isSensitive: Boolean, label: String = "Weblo") {
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        val guardActive = settings.value.clipboardGuardEnabled
        val clip = ClipData.newPlainText(label, text)
        if (isSensitive || guardActive) {
            val extras = PersistableBundle().apply {
                val extraKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ClipDescription.EXTRA_IS_SENSITIVE
                } else {
                    "android.content.extra.IS_SENSITIVE"
                }
                putBoolean(extraKey, true)
            }
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)
        clipboardClearJob?.cancel()
        if (guardActive && (isSensitive || text.isNotBlank())) {
            clipboardClearJob = viewModelScope.launch {
                delay(45_000L)
                val currentText = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (currentText == text) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cm.clearPrimaryClip()
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    // Tab Navigation & Lifecycle
    fun openUrlInActiveTab(rawInput: String) {
        val current = activeTab.value
        val s = settings.value
        var url = resolveInputToUrl(rawInput, s.defaultSearchEngine, s.safeSearchEnabled)
        if (current == null) {
            openNewTab(url)
            return
        }
        if (url == "weblio://home") {
            goHomeInActiveTab()
            return
        }
        // HTTPS-first upgrade for external hosts unless already https
        if (s.httpsFirstEnabled && url.startsWith("http://", ignoreCase = true)) {
            val host = extractHost(url)
            if (host.isNotBlank() && host != "localhost" && !host.startsWith("127.") && !host.startsWith("192.168.")) {
                url = "https://" + url.removePrefix("http://")
            }
        }
        val cleanUrl = if (url.startsWith("http")) adBlock.cleanTrackingUrl(url) else url
        val initialTitle = extractHost(cleanUrl).ifBlank { cleanUrl }
        val isLocalPreview = initialTitle.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)
        val isExtensionStore = ExtensionEngine.isExtensionStoreUrl(cleanUrl)
        val checkSafeBrowsing = s.safeBrowsingEnabled && !isLocalPreview

        if (checkSafeBrowsing) {
            val syncThreat = threatEngine.quickEvaluateSync(cleanUrl)
            if (syncThreat != null && !consumeOneTimeAllowance(cleanUrl)) {
                triggerWebsiteWarning(current.id, syncThreat, current.isIncognito)
                return
            }
        }

        val forceDesktop = current.desktopMode || isExtensionStore
        updateRuntimeState(current.id) {
            it.copy(
                currentUrl = cleanUrl,
                title = initialTitle,
                isLoading = true,
                progress = 5,
                errorBanner = null,
                findInPageOpen = false,
                isDesktopMode = forceDesktop
            )
        }
        webViewRegistry[current.id]?.let { wv ->
            resumeWebViewForForeground(wv)
            touchWebViewLru(current.id)
            if (forceDesktop) {
                applyDesktopUserAgent(wv, true, forceInitialScale = true)
            }
            lastDispatchedUrl[current.id] = cleanUrl
            dispatchedWebViewIdentity[current.id] = System.identityHashCode(wv)
            wv.loadUrl(cleanUrl)
        }

        viewModelScope.launch {
            if (isExtensionStore && !current.desktopMode) {
                repo.updateTab(
                    current.copy(
                        url = cleanUrl,
                        title = initialTitle,
                        desktopMode = true,
                        lastActiveAt = System.currentTimeMillis()
                    )
                )
            } else {
                repo.updateTabUrlAndTitle(current.id, cleanUrl, initialTitle, System.currentTimeMillis())
            }
            if (checkSafeBrowsing) {
                val report = threatEngine.evaluateNavigation(cleanUrl, s.cloudHashReputationEnabled)
                if (report.verdict != WebsiteVerdict.SAFE && !consumeOneTimeAllowance(cleanUrl)) {
                    triggerWebsiteWarning(current.id, report, current.isIncognito)
                }
            }
        }
    }

    fun openExtensionStoreUrl(storeUrl: String) {
        val current = activeTab.value
        if (current == null) {
            openNewTab(url = storeUrl, desktopModeOverride = true)
        } else {
            openUrlInActiveTab(storeUrl)
        }
    }

    fun enableDesktopModeForTab(tabId: Long) {
        updateRuntimeState(tabId) { it.copy(isDesktopMode = true) }
        val tab = tabs.value.firstOrNull { it.id == tabId } ?: return
        if (!tab.desktopMode) {
            viewModelScope.launch {
                repo.updateTab(tab.copy(desktopMode = true))
            }
        }
        webViewRegistry[tabId]?.let { applyDesktopUserAgent(it, true) }
    }

    fun consumeOneTimeAllowance(url: String): Boolean {
        val allowed = oneTimeAllowedNavUrl
        if (allowed != null && allowed == url) {
            oneTimeAllowedNavUrl = null
            return true
        }
        return false
    }

    suspend fun evaluateUrlSafety(url: String, isRedirect: Boolean = false, originalUrl: String = ""): WebsiteThreatReport {
        val host = extractHost(url)
        if (host.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) {
            return WebsiteThreatReport(url, host, WebsiteVerdict.SAFE, "Local Preview", "Local downloaded HTML preview", "Local")
        }
        if (!settings.value.safeBrowsingEnabled) {
            return WebsiteThreatReport(url, host, WebsiteVerdict.SAFE, "Disabled", "Safe Browsing disabled", "Local")
        }
        return if (isRedirect && originalUrl.isNotBlank()) {
            threatEngine.evaluateRedirect(originalUrl, url, settings.value.cloudHashReputationEnabled)
        } else {
            threatEngine.evaluateNavigation(url, settings.value.cloudHashReputationEnabled)
        }
    }

    fun triggerWebsiteWarning(tabId: Long, report: WebsiteThreatReport, isIncognito: Boolean) {
        _pendingWebsiteWarning.value = PendingWebsiteWarning(tabId, report)
        webViewRegistry[tabId]?.stopLoading()
        viewModelScope.launch {
            val safeHost = report.host.ifBlank { "unknown-host" }
            if (!isIncognito) {
                repo.recordSecurityEvent(
                    category = "WEBSITE",
                    severity = if (report.verdict == WebsiteVerdict.DANGEROUS) "CRITICAL" else "WARNING",
                    title = "Blocked ${report.threatCategory}",
                    summary = "Blocked unsafe navigation to $safeHost (${report.source}).",
                    targetHostOrFile = safeHost,
                    actionTaken = "Warning Displayed"
                )
            }
            SecurityNotifications.notify(
                context = app,
                enabled = settings.value.securityNotificationsEnabled,
                kind = SecurityNotifications.AlertKind.SUSPICIOUS_WEBSITE,
                safeMessage = "Blocked unsafe site: $safeHost (${report.threatCategory})"
            )
            recordSuspiciousActivityHit()
        }
    }

    fun goBackFromWebsiteWarning() {
        val warning = _pendingWebsiteWarning.value ?: return
        _pendingWebsiteWarning.value = null
        val wv = webViewRegistry[warning.tabId]
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            goHomeInActiveTab()
        }
    }

    fun continueAnywayFromWebsiteWarning() {
        val warning = _pendingWebsiteWarning.value ?: return
        _pendingWebsiteWarning.value = null
        // Allow ONLY this single navigation request — never permanently whitelist the domain
        oneTimeAllowedNavUrl = warning.report.url
        val tab = tabs.value.firstOrNull { it.id == warning.tabId }
        if (tab?.isIncognito != true) {
            viewModelScope.launch {
                repo.recordSecurityEvent(
                    category = "WEBSITE",
                    severity = "WARNING",
                    title = "User bypassed website warning",
                    summary = "Single-navigation override allowed for ${warning.report.host} (not whitelisted).",
                    targetHostOrFile = warning.report.host,
                    actionTaken = "Single Navigation Allowed"
                )
            }
        }
        val initialTitle = warning.report.host.ifBlank { warning.report.url }
        updateRuntimeState(warning.tabId) {
            it.copy(
                currentUrl = warning.report.url,
                title = initialTitle,
                isLoading = true,
                progress = 10,
                errorBanner = null
            )
        }
        webViewRegistry[warning.tabId]?.loadUrl(warning.report.url)
    }

    private fun recordSuspiciousActivityHit() {
        sessionThreatHits++
        if (sessionThreatHits >= 3) {
            SecurityNotifications.notify(
                context = app,
                enabled = settings.value.securityNotificationsEnabled,
                kind = SecurityNotifications.AlertKind.REPEATED_SUSPICIOUS_ACTIVITY,
                safeMessage = "Weblo blocked $sessionThreatHits suspicious sites or downloads in this session."
            )
        }
    }

    fun openNewTab(
        url: String = "weblio://home",
        isIncognito: Boolean = false,
        groupName: String? = null,
        initialTitle: String? = null,
        desktopModeOverride: Boolean = false
    ) {
        viewModelScope.launch {
            val pid = activeProfileId.value
            val cleanUrl = if (url == "weblio://home") url else resolveInputToUrl(url, settings.value.defaultSearchEngine, settings.value.safeSearchEnabled)
            val resolvedTitle = when {
                cleanUrl == "weblio://home" -> "Home"
                !initialTitle.isNullOrBlank() -> initialTitle
                else -> cleanUrl
            }
            val shouldUseDesktop = desktopModeOverride || ExtensionEngine.isExtensionStoreUrl(cleanUrl)
            val newId = repo.createTab(
                profileId = pid,
                url = cleanUrl,
                title = resolvedTitle,
                isIncognito = isIncognito,
                groupName = groupName,
                desktopMode = shouldUseDesktop
            )
            updateRuntimeState(newId) {
                it.copy(
                    currentUrl = cleanUrl,
                    title = resolvedTitle,
                    isDesktopMode = shouldUseDesktop
                )
            }
            _activeTabId.value = newId
        }
    }

    fun openHtmlDownloadInNewTab(entity: DownloadEntity) {
        if (entity.quarantined || entity.status != DownloadStatus.COMPLETED.name) {
            showMessage("Resolve quarantine scan before previewing this HTML file")
            return
        }
        val file = File(entity.localPath)
        if (!file.exists() || !file.isFile) {
            showMessage("HTML file not found on storage")
            return
        }
        val previewUrl = downloadManager.buildLocalHtmlPreviewUrl(entity)
        val current = activeTab.value
        openNewTab(
            url = previewUrl,
            isIncognito = current?.isIncognito == true,
            groupName = current?.groupName,
            initialTitle = entity.fileName
        )
    }

    fun resolveDownloadForPreview(downloadId: Long): DownloadEntity? {
        return downloads.value.firstOrNull { it.id == downloadId }
            ?: kotlinx.coroutines.runBlocking { repo.getDownload(downloadId) }
    }

    fun selectTab(tabId: Long) {
        val now = System.currentTimeMillis()
        activeTab.value?.id?.takeIf { it != tabId }?.let { prevId ->
            tabLastLeftMs[prevId] = now
            webViewRegistry[prevId]?.let { prevWv ->
                pauseWebViewForBackground(prevId, prevWv)
            }
        }
        _activeTabId.value = tabId
        val targetTab = tabs.value.firstOrNull { it.id == tabId }
        val targetUrl = (_tabRuntime.value[tabId]?.currentUrl ?: targetTab?.url).orEmpty()
        val leftAt = tabLastLeftMs.remove(tabId)
        if (targetUrl.isNotBlank() && targetUrl != "weblio://home") {
            val wv = webViewRegistry[tabId]
            val staleByAge = leftAt != null && (now - leftAt) >= STALE_TAB_RELOAD_MS
            val surfaceMissing = wv != null && (wv.url.isNullOrBlank() || wv.contentHeight == 0)
            if (wv == null) {
                lastDispatchedUrl.remove(tabId)
                dispatchedWebViewIdentity.remove(tabId)
            } else if (staleByAge || surfaceMissing) {
                recoverStaleTab(tabId, forceRecreateWebView = wv.url.isNullOrBlank())
            } else {
                resumeWebViewForForeground(wv)
                touchWebViewLru(tabId)
            }
        }
        viewModelScope.launch {
            targetTab?.let {
                repo.updateTab(it.copy(lastActiveAt = now))
            }
        }
    }

    fun duplicateTab(tab: TabEntity) {
        openNewTab(url = tab.url, isIncognito = tab.isIncognito, groupName = tab.groupName)
    }

    fun assignTabGroup(tab: TabEntity, groupName: String?) {
        viewModelScope.launch {
            repo.updateTab(tab.copy(groupName = groupName?.trim()?.ifBlank { null }))
        }
    }

    fun toggleDesktopModeForActiveTab() {
        val tab = activeTab.value ?: return
        val nextDesktop = !tab.desktopMode
        updateRuntimeState(tab.id) { it.copy(isDesktopMode = nextDesktop) }
        viewModelScope.launch {
            val updated = tab.copy(desktopMode = nextDesktop)
            repo.updateTab(updated)
            val wv = webViewRegistry[tab.id]
            if (wv != null && tab.url != "weblio://home") {
                applyDesktopUserAgent(wv, updated.desktopMode)
                wv.reload()
            }
        }
    }

    fun toggleIncognitoNewTab() {
        openNewTab(url = "weblio://home", isIncognito = true)
    }

    fun closeTab(tab: TabEntity) {
        viewModelScope.launch {
            val remaining = tabs.value.filter { it.id != tab.id }
            unregisterWebView(tab.id)
            deleteTabPreview(tab.id)
            repo.closeTab(tab)
            if (tab.isIncognito && remaining.none { it.isIncognito }) {
                ProfileIsolationManager.clearEphemeralData()
            }
            if (_activeTabId.value == tab.id) {
                val sameModeTab = remaining.lastOrNull { it.isIncognito == tab.isIncognito }
                if (sameModeTab != null) {
                    _activeTabId.value = sameModeTab.id
                } else if (!tab.isIncognito) {
                    val freshId = repo.createTab(activeProfileId.value, "weblio://home", "Home", isIncognito = false)
                    _activeTabId.value = freshId
                } else if (remaining.isNotEmpty()) {
                    _activeTabId.value = remaining.last().id
                } else {
                    val freshId = repo.createTab(activeProfileId.value, "weblio://home", "Home")
                    _activeTabId.value = freshId
                }
            }
        }
    }

    fun closeAllTabs(incognitoOnly: Boolean? = null) {
        viewModelScope.launch {
            val toClose = when (incognitoOnly) {
                null -> tabs.value
                true -> tabs.value.filter { it.isIncognito }
                false -> tabs.value.filter { !it.isIncognito }
            }
            toClose.forEach {
                unregisterWebView(it.id)
                deleteTabPreview(it.id)
            }
            repo.closeAllTabs(activeProfileId.value, incognitoOnly)
            if (incognitoOnly != false) {
                ProfileIsolationManager.clearEphemeralData()
            }
            val remaining = tabs.value.filter { it !in toClose }
            if (remaining.isEmpty() || (incognitoOnly == false && remaining.none { !it.isIncognito })) {
                val freshId = repo.createTab(activeProfileId.value, "weblio://home", "Home", isIncognito = false)
                _activeTabId.value = freshId
            } else {
                _activeTabId.value = remaining.last().id
            }
        }
    }

    fun reopenClosedTab(closed: ClosedTabEntity) {
        viewModelScope.launch {
            val id = repo.reopenClosedTab(closed)
            _activeTabId.value = id
        }
    }

    fun goHomeInActiveTab() {
        val tab = activeTab.value ?: return
        deleteTabPreview(tab.id)
        unregisterWebView(tab.id)
        updateRuntimeState(tab.id) {
            TabRuntimeState(
                tabId = tab.id,
                currentUrl = "weblio://home",
                title = "Home",
                progress = 0,
                isLoading = false,
                canGoBack = false,
                canGoForward = false
            )
        }
        viewModelScope.launch {
            repo.updateTabUrlAndTitle(tab.id, "weblio://home", "Home", System.currentTimeMillis())
        }
    }

    fun webViewGoBack() {
        val tab = activeTab.value ?: return
        val wv = webViewRegistry[tab.id]
        val runtimeUrl = _tabRuntime.value[tab.id]?.currentUrl ?: tab.url
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            updateRuntimeState(tab.id) {
                it.copy(
                    currentUrl = wv.url ?: it.currentUrl,
                    canGoBack = wv.canGoBack(),
                    canGoForward = wv.canGoForward()
                )
            }
        } else if (runtimeUrl != "weblio://home") {
            goHomeInActiveTab()
        }
    }

    fun webViewGoForward() {
        val tab = activeTab.value ?: return
        val wv = webViewRegistry[tab.id] ?: return
        if (wv.canGoForward()) {
            wv.goForward()
            updateRuntimeState(tab.id) {
                it.copy(
                    currentUrl = wv.url ?: it.currentUrl,
                    canGoBack = wv.canGoBack(),
                    canGoForward = wv.canGoForward()
                )
            }
        }
    }

    fun webViewReloadOrStop() {
        val tab = activeTab.value ?: return
        val rt = _tabRuntime.value[tab.id]
        val wv = webViewRegistry[tab.id]
        if (wv == null || rt?.errorBanner != null || wv.url.isNullOrBlank()) {
            retryTabLoad(tab.id)
            return
        }
        if (rt?.isLoading == true) wv.stopLoading() else wv.reload()
    }

    fun updateRuntimeState(tabId: Long, transform: (TabRuntimeState) -> TabRuntimeState) {
        _tabRuntime.update { map ->
            val current = map[tabId] ?: TabRuntimeState(tabId = tabId)
            map + (tabId to transform(current))
        }
    }

    fun updateTabTitle(tabId: Long, rawTitle: String) {
        val clean = rawTitle.trim()
        if (clean.isBlank() || clean == "about:blank" || clean == "weblio://home") return
        val currentUrl = _tabRuntime.value[tabId]?.currentUrl
        if (currentUrl == "weblio://home") return
        updateRuntimeState(tabId) { it.copy(title = clean) }
        viewModelScope.launch {
            repo.updateTabTitle(tabId, clean)
        }
    }

    fun onPageCommitted(tab: TabEntity, url: String, title: String) {
        if (url == "weblio://home" || url == "about:blank") return
        val resolvedTitle = title.trim()
            .takeIf { it.isNotBlank() && it != "about:blank" }
            ?: extractHost(url).ifBlank { url }
        updateRuntimeState(tab.id) {
            it.copy(currentUrl = url, title = resolvedTitle)
        }
        viewModelScope.launch {
            repo.updateTabUrlAndTitle(tab.id, url, resolvedTitle, System.currentTimeMillis())
            val profile = activeProfile.value
            repo.recordHistory(
                profileId = tab.profileId,
                title = resolvedTitle,
                url = url,
                isIncognito = tab.isIncognito,
                isGuest = profile?.isGuest == true
            )
        }
    }

    // Find in Page
    fun toggleFindInPage(open: Boolean) {
        val tab = activeTab.value ?: return
        updateRuntimeState(tab.id) { it.copy(findInPageOpen = open, findQuery = if (!open) "" else it.findQuery) }
        if (!open) webViewRegistry[tab.id]?.clearMatches()
    }

    fun updateFindQuery(query: String) {
        val tab = activeTab.value ?: return
        updateRuntimeState(tab.id) { it.copy(findQuery = query) }
        if (query.isBlank()) {
            webViewRegistry[tab.id]?.clearMatches()
        } else {
            webViewRegistry[tab.id]?.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) {
        val tab = activeTab.value ?: return
        webViewRegistry[tab.id]?.findNext(forward)
    }

    // SSL Error handling — strict security, never silently bypass!
    fun showSslErrorPrompt(tabId: Long, url: String, error: SslError, onCancel: () -> Unit) {
        _sslPrompt.value = SslErrorPrompt(
            tabId = tabId,
            url = url,
            primaryError = error.primaryError,
            issuedTo = error.certificate?.issuedTo?.dName,
            issuedBy = error.certificate?.issuedBy?.dName,
            onCancel = onCancel
        )
    }

    fun dismissSslErrorPrompt() {
        _sslPrompt.value?.onCancel?.invoke()
        _sslPrompt.value = null
    }

    // Profile Management
    fun requestSwitchProfile(profile: ProfileEntity) {
        if (profile.id == activeProfileId.value) return
        if (profile.pinHash != null || profile.biometricEnabled) {
            _pendingProfileUnlock.value = profile
        } else {
            commitSwitchProfile(profile)
        }
    }

    fun cancelProfileUnlock() {
        _pendingProfileUnlock.value = null
    }

    fun verifyProfilePinAndSwitch(profile: ProfileEntity, pin: String): Boolean {
        val hash = profile.pinHash ?: return false
        val salt = profile.pinSalt ?: return false
        val ok = KeystoreCryptoManager.verifyPin(pin, hash, salt)
        if (ok) {
            _pendingProfileUnlock.value = null
            commitSwitchProfile(profile)
        }
        return ok
    }

    fun commitSwitchProfile(profile: ProfileEntity) {
        _pendingProfileUnlock.value = null
        _isPasswordVaultUnlocked.value = false
        viewModelScope.launch {
            webViewRegistry.keys.toList().forEach { unregisterWebView(it) }
            prefs.setActiveProfile(profile.id)
            val profileTabs = repo.observeTabs(profile.id).first()
            if (profileTabs.isEmpty()) {
                val id = repo.createTab(profile.id, "weblio://home", "Home")
                _activeTabId.value = id
            } else {
                _activeTabId.value = profileTabs.first().id
            }
            if (profile.isGuest) {
                ProfileIsolationManager.clearEphemeralData()
            }
            showMessage("Switched to ${profile.name} profile")
        }
    }

    fun createProfile(name: String, iconKey: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repo.createProfile(name, iconKey)
            val created = repo.getProfile(id)
            if (created != null) commitSwitchProfile(created)
        }
    }

    fun renameProfile(profile: ProfileEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { repo.updateProfile(profile.copy(name = newName.trim())) }
    }

    fun updateProfileIcon(profile: ProfileEntity, iconKey: String) {
        viewModelScope.launch { repo.updateProfile(profile.copy(iconKey = iconKey)) }
    }

    fun setProfilePin(profile: ProfileEntity, pin: String?) {
        viewModelScope.launch {
            if (pin.isNullOrBlank()) {
                repo.updateProfile(profile.copy(pinHash = null, pinSalt = null))
            } else {
                val (hash, salt) = KeystoreCryptoManager.hashPin(pin)
                repo.updateProfile(profile.copy(pinHash = hash, pinSalt = salt))
            }
        }
    }

    fun setProfileBiometric(profile: ProfileEntity, enabled: Boolean) {
        viewModelScope.launch { repo.updateProfile(profile.copy(biometricEnabled = enabled)) }
    }

    fun deleteProfile(profile: ProfileEntity) {
        if (profile.isGuest) return
        viewModelScope.launch {
            ProfileIsolationManager.clearProfileWebData(profile.webProfileName)
            repo.deleteProfile(profile)
            val remaining = repo.observeProfiles().first()
            if (profile.id == activeProfileId.value && remaining.isNotEmpty()) {
                commitSwitchProfile(remaining.first())
            }
        }
    }

    // Bookmarks & Quick Links
    fun toggleCurrentPageBookmark() {
        val tab = activeTab.value ?: return
        if (tab.url == "weblio://home") return
        viewModelScope.launch {
            val existing = repo.observeBookmarks(activeProfileId.value).first().firstOrNull { it.url == tab.url }
            if (existing != null) {
                repo.deleteBookmark(existing)
                showMessage("Bookmark removed")
            } else {
                repo.addBookmark(activeProfileId.value, tab.title, tab.url, "General")
                showMessage("Saved to Bookmarks")
            }
        }
    }

    fun addBookmark(title: String, url: String, folder: String) {
        viewModelScope.launch { repo.addBookmark(activeProfileId.value, title, url, folder) }
    }

    fun updateBookmark(bm: BookmarkEntity) {
        viewModelScope.launch { repo.updateBookmark(bm) }
    }

    fun deleteBookmark(bm: BookmarkEntity) {
        viewModelScope.launch { repo.deleteBookmark(bm) }
    }

    fun addQuickLink(title: String, url: String) {
        if (title.isBlank() || url.isBlank()) return
        val formatted = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        viewModelScope.launch { repo.addQuickLink(activeProfileId.value, title.trim(), formatted) }
    }

    fun deleteQuickLink(link: QuickLinkEntity) {
        viewModelScope.launch { repo.deleteQuickLink(link) }
    }

    // History
    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch { repo.deleteHistoryEntry(id) }
    }

    fun clearHistoryRange(hoursBack: Int?) {
        viewModelScope.launch {
            if (hoursBack == null) {
                repo.clearAllHistory(activeProfileId.value)
            } else {
                val since = System.currentTimeMillis() - hoursBack * 3600_000L
                repo.clearHistorySince(activeProfileId.value, since)
            }
            showMessage("Browsing history cleared")
        }
    }

    fun clearAllBrowsingData(clearCookies: Boolean, clearHistory: Boolean, clearCache: Boolean) {
        viewModelScope.launch {
            if (clearHistory) repo.clearAllHistory(activeProfileId.value)
            if (clearCookies) ProfileIsolationManager.clearAllCookiesAndStorage()
            if (clearCache) webViewRegistry.values.forEach { it.clearCache(true) }
            showMessage("Selected browsing data cleared")
        }
    }

    // Downloads
    fun requestDownload(url: String, fileName: String, mimeType: String, userAgent: String?) {
        if (settings.value.askBeforeDownload) {
            _downloadPrompt.value = PendingDownloadPrompt(url, fileName, mimeType, userAgent)
        } else {
            confirmDownload(url, fileName, mimeType, userAgent)
        }
    }

    fun dismissDownloadPrompt() {
        _downloadPrompt.value = null
    }

    fun confirmDownload(url: String, fileName: String, mimeType: String, userAgent: String?) {
        _downloadPrompt.value = null
        downloadManager.enqueueDownload(
            profileId = activeProfileId.value,
            url = url,
            suggestedFileName = fileName,
            mimeType = mimeType,
            userAgent = userAgent,
            showNotification = settings.value.downloadNotificationsEnabled
        )
        showMessage("Downloading $fileName")
    }

    fun viewModelScopeDeleteDownload(downloadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadManager.deleteDownloadedFile(downloadId)
        }
    }

    fun viewModelScopeClearDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = repo.observeDownloads(activeProfileId.value).first()
            downloadManager.clearAllDownloads(activeProfileId.value, currentList)
        }
    }

    fun renameDownload(item: DownloadEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadManager.renameDownloadedFile(item, newName)
                .onSuccess { updated -> showMessage("Renamed to ${updated.fileName}") }
                .onFailure { err -> showMessage(err.message ?: "Rename failed") }
        }
    }

    fun viewModelScopeInstallUserScript(name: String, rawScript: String) {
        viewModelScope.launch {
            when (val res = extensionEngine.installUserScript(activeProfileId.value, rawScript, name)) {
                is ExtensionEngine.InstallOutcome.Success -> {
                    handleExtensionSecurityResult(res.extension)
                }
                is ExtensionEngine.InstallOutcome.Failure -> showMessage("Script error: ${res.reason}")
            }
        }
    }

    fun resolveQuarantinedDownload(downloadId: Long, allowContinue: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadManager.resolveQuarantinedDownload(downloadId, allowContinue)
                .onSuccess { updated ->
                    showMessage(
                        if (allowContinue) "Saved ${updated.fileName} (marked user-overridden)"
                        else "Deleted quarantined download ${updated.fileName}"
                    )
                }
                .onFailure { err ->
                    showMessage(err.message ?: "Unable to resolve quarantined download")
                }
        }
    }

    fun requestDownloadReview(entity: DownloadEntity) {
        downloadManager.requestReviewFor(entity)
    }

    fun dismissDownloadReview(downloadId: Long) {
        downloadManager.dismissActiveReview(downloadId)
    }

    // Password Manager
    fun promptSaveCredential(host: String, username: String, password: String) {
        if (host.isBlank() || username.isBlank() || password.isBlank()) return
        if (activeProfile.value?.isGuest == true || activeTab.value?.isIncognito == true) return
        _credentialPrompt.value = PendingCredentialPrompt(host, username, password)
    }

    fun dismissCredentialPrompt() {
        _credentialPrompt.value = null
    }

    fun acceptSaveCredential() {
        val p = _credentialPrompt.value ?: return
        _credentialPrompt.value = null
        viewModelScope.launch {
            repo.savePassword(activeProfileId.value, p.host, p.username, p.password)
            showMessage("Password encrypted & saved to Keystore Vault")
        }
    }

    fun savePasswordManual(host: String, username: String, plain: String) {
        viewModelScope.launch {
            repo.savePassword(activeProfileId.value, host.trim(), username.trim(), plain)
            showMessage("Credential saved securely")
        }
    }

    fun deletePassword(entity: PasswordEntity) {
        viewModelScope.launch { repo.deletePassword(entity) }
    }

    fun decryptPassword(entity: PasswordEntity): String? = repo.decryptPassword(entity)

    // Extensions
    fun installExtensionFromUri(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            when (val res = extensionEngine.installFromUri(activeProfileId.value, uri, displayName)) {
                is ExtensionEngine.InstallOutcome.Success -> handleExtensionSecurityResult(res.extension)
                is ExtensionEngine.InstallOutcome.Failure -> showMessage("Install failed: ${res.reason}")
            }
        }
    }

    fun installExtensionFromStorePage(pageUrl: String, pageTitle: String?) {
        val ref = ExtensionEngine.extractStoreExtensionDownload(pageUrl)
        if (ref == null) {
            showMessage("Open an extension's detail page to install it into Weblo")
            return
        }
        val cleanName = pageTitle
            ?.replace(" - Chrome Web Store", "", ignoreCase = true)
            ?.replace(" - Microsoft Edge Addons", "", ignoreCase = true)
            ?.replace(" - Microsoft Edge Add-ons", "", ignoreCase = true)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
            ?: ref.extensionId
        installCrxFromDirectUrl(ref.crxDownloadUrl, cleanName)
    }

    fun installCrxFromDirectUrl(crxUrl: String, suggestedName: String?) {
        if (_isInstallingExtension.value) return
        viewModelScope.launch {
            _isInstallingExtension.value = true
            try {
                showMessage("Downloading ${suggestedName ?: "extension"}...")
                when (val res = extensionEngine.installCrxFromUrl(activeProfileId.value, crxUrl, suggestedName)) {
                    is ExtensionEngine.InstallOutcome.Success -> handleExtensionSecurityResult(res.extension)
                    is ExtensionEngine.InstallOutcome.Failure -> showMessage("Install failed: ${res.reason}")
                }
            } finally {
                _isInstallingExtension.value = false
            }
        }
    }

    private suspend fun handleExtensionSecurityResult(ext: ExtensionEntity) {
        if (ext.riskLevel == "HIGH" || ext.riskLevel == "SENSITIVE") {
            repo.recordSecurityEvent(
                category = "EXTENSION",
                severity = if (ext.riskLevel == "HIGH") "CRITICAL" else "WARNING",
                title = "${ext.riskLevel} risk extension quarantined (disabled)",
                summary = "${ext.name} requires explicit approval due to sensitive permissions.",
                targetHostOrFile = ext.name,
                actionTaken = "Installed Disabled"
            )
            if (ext.riskLevel == "HIGH") {
                SecurityNotifications.notify(
                    context = app,
                    enabled = settings.value.securityNotificationsEnabled,
                    kind = SecurityNotifications.AlertKind.MALICIOUS_EXTENSION,
                    safeMessage = "${ext.name} requested sensitive permissions and was installed disabled."
                )
            }
            _pendingExtensionReview.value = ext
            showMessage("${ext.name} installed disabled (${ext.riskLevel} risk — review permissions to enable)")
        } else {
            showMessage("Installed ${ext.name}")
        }
    }

    fun approvePendingExtension() {
        val ext = _pendingExtensionReview.value ?: return
        _pendingExtensionReview.value = null
        viewModelScope.launch {
            repo.updateExtension(ext.copy(enabled = true))
            repo.recordSecurityEvent(
                category = "EXTENSION",
                severity = "WARNING",
                title = "User enabled ${ext.riskLevel} risk extension",
                summary = "User reviewed permissions and explicitly enabled ${ext.name}.",
                targetHostOrFile = ext.name,
                actionTaken = "Enabled by User"
            )
            showMessage("${ext.name} enabled")
        }
    }

    fun dismissPendingExtensionReview() {
        val ext = _pendingExtensionReview.value ?: return
        _pendingExtensionReview.value = null
        showMessage("${ext.name} kept disabled — review anytime in Extensions")
    }

    fun removePendingExtension() {
        val ext = _pendingExtensionReview.value ?: return
        _pendingExtensionReview.value = null
        removeExtension(ext)
    }

    fun installBuiltInExtension(template: ExtensionEntity) {
        viewModelScope.launch {
            val already = extensions.value.any { it.extId == template.extId }
            if (already) {
                showMessage("${template.name} is already installed")
            } else {
                val assessment = ExtensionEngine.assessExtensionSecurity(
                    template.permissionsJson,
                    template.matchPatternsJson,
                    template.scriptContent,
                    template.cssContent
                )
                repo.upsertExtension(
                    template.copy(
                        id = 0,
                        profileId = activeProfileId.value,
                        enabled = true,
                        contentHashSha256 = assessment.contentHashSha256,
                        riskLevel = assessment.riskLevel,
                        permissionWarningsJson = org.json.JSONArray(assessment.sensitivePermissions + assessment.scriptWarnings).toString()
                    )
                )
                showMessage("Installed ${template.name}")
            }
        }
    }

    fun toggleExtension(ext: ExtensionEntity) {
        viewModelScope.launch {
            repo.updateExtension(ext.copy(enabled = !ext.enabled))
        }
    }

    fun reportExtensionTampering(ext: ExtensionEntity) {
        viewModelScope.launch {
            repo.updateExtension(ext.copy(enabled = false, lastError = "Disabled: SHA-256 integrity hash mismatch"))
            repo.recordSecurityEvent(
                category = "EXTENSION",
                severity = "CRITICAL",
                title = "Extension integrity mismatch",
                summary = "Blocked execution of ${ext.name} because script/CSS hash failed verification.",
                targetHostOrFile = ext.name,
                actionTaken = "Execution Blocked"
            )
            SecurityNotifications.notify(
                context = app,
                enabled = settings.value.securityNotificationsEnabled,
                kind = SecurityNotifications.AlertKind.MALICIOUS_EXTENSION,
                safeMessage = "Blocked ${ext.name}: integrity verification failed."
            )
        }
    }

    fun removeExtension(ext: ExtensionEntity) {
        viewModelScope.launch {
            extensionEngine.cleanupExtensionFiles(ext)
            repo.deleteExtension(ext)
            showMessage("Removed ${ext.name}")
        }
    }

    fun openExtensionPopup(ext: ExtensionEntity?) {
        _activeExtensionPopupId.value = ext?.id
    }

    fun runExtensionOnActiveTab(ext: ExtensionEntity) {
        if (!ext.enabled) {
            showMessage("Enable ${ext.name} first to run it")
            return
        }
        if (!ExtensionEngine.verifyExtensionIntegrity(ext)) {
            reportExtensionTampering(ext)
            return
        }
        val tab = activeTab.value
        val wv = tab?.let { webViewRegistry[it.id] }
        val currentUrl = tab?.let { _tabRuntime.value[it.id]?.currentUrl ?: it.url }.orEmpty()
        if (wv == null || currentUrl.isBlank() || currentUrl == "weblio://home") {
            showMessage("Open any website first to use ${ext.name}")
            return
        }
        val payload = extensionEngine.buildForceExecutionPayload(ext)
        if (payload.isBlank()) {
            showMessage("${ext.name} runs via its popup controls or manifest rules")
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            wv.evaluateJavascript(payload, null)
            showMessage("Ran ${ext.name} on ${extractHost(currentUrl).ifBlank { "current page" }}")
        }
    }

    fun executeExtensionScriptOnActiveTab(ext: ExtensionEntity, code: String) {
        if (!ext.enabled) return
        if (!ExtensionEngine.verifyExtensionIntegrity(ext)) {
            reportExtensionTampering(ext)
            return
        }
        val tab = activeTab.value ?: return
        val wv = webViewRegistry[tab.id] ?: return
        val currentUrl = _tabRuntime.value[tab.id]?.currentUrl ?: tab.url
        if (currentUrl == "weblio://home" || extractHost(currentUrl).equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) return
        val quotedToken = org.json.JSONObject.quote(extensionEngine.sessionBridgeToken)
        val wrapped = """
            ;(function(){
                var __TOKEN__ = $quotedToken;
                try {
                    $code
                } catch(e) {
                    if (window.WeblioExtBridge) window.WeblioExtBridge.reportError(__TOKEN__, ${org.json.JSONObject.quote(ext.extId)}, String(e));
                }
            })();
        """.trimIndent()
        viewModelScope.launch(Dispatchers.Main) {
            wv.evaluateJavascript(wrapped, null)
        }
    }

    fun insertExtensionCssOnActiveTab(ext: ExtensionEntity, css: String) {
        if (!ext.enabled || !ExtensionEngine.verifyExtensionIntegrity(ext)) return
        val tab = activeTab.value ?: return
        val wv = webViewRegistry[tab.id] ?: return
        val currentUrl = _tabRuntime.value[tab.id]?.currentUrl ?: tab.url
        if (currentUrl == "weblio://home" || extractHost(currentUrl).equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) return
        val js = """
            ;(function(){
                try {
                    var s = document.createElement('style');
                    s.setAttribute('data-weblio-ext', ${org.json.JSONObject.quote(ext.extId)});
                    s.textContent = ${org.json.JSONObject.quote(css)};
                    (document.head || document.documentElement).appendChild(s);
                } catch(e) {}
            })();
        """.trimIndent()
        viewModelScope.launch(Dispatchers.Main) {
            wv.evaluateJavascript(js, null)
        }
    }

    fun sendExtensionMessageToActiveTab(ext: ExtensionEntity, msgJson: String) {
        if (!ext.enabled || !ExtensionEngine.verifyExtensionIntegrity(ext)) return
        val tab = activeTab.value ?: return
        val wv = webViewRegistry[tab.id] ?: return
        val currentUrl = _tabRuntime.value[tab.id]?.currentUrl ?: tab.url
        if (currentUrl == "weblio://home" || extractHost(currentUrl).equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) return
        val js = """
            ;(function(){
                try {
                    var payload = JSON.parse(${org.json.JSONObject.quote(msgJson)});
                    if (window.__weblioDispatchExtMessage) {
                        window.__weblioDispatchExtMessage(${org.json.JSONObject.quote(ext.extId)}, payload);
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        viewModelScope.launch(Dispatchers.Main) {
            wv.evaluateJavascript(js, null)
        }
    }

    fun reloadActiveTabForExtension() {
        val tab = activeTab.value ?: return
        val wv = webViewRegistry[tab.id] ?: return
        viewModelScope.launch(Dispatchers.Main) {
            wv.reload()
        }
    }

    // AdBlock & Site Permissions
    fun onBlockedRequest(url: String) {
        pendingBlockedDelta.incrementAndGet()
        val tid = _activeTabId.value ?: activeTab.value?.id
        if (tid != null) {
            pendingPageBlockedCounts.computeIfAbsent(tid) { AtomicLong(0L) }.incrementAndGet()
            if (pageBlockedFlushScheduled.compareAndSet(false, true)) {
                viewModelScope.launch {
                    delay(350L)
                    pageBlockedFlushScheduled.set(false)
                    if (pendingPageBlockedCounts.isNotEmpty()) {
                        val updates = mutableMapOf<Long, Long>()
                        pendingPageBlockedCounts.keys().toList().forEach { key ->
                            val d = pendingPageBlockedCounts[key]?.getAndSet(0L) ?: 0L
                            if (d > 0L) updates[key] = d
                        }
                        if (updates.isNotEmpty()) {
                            _tabRuntime.update { map ->
                                var next = map
                                updates.forEach { (id, d) ->
                                    val cur = next[id] ?: TabRuntimeState(tabId = id)
                                    next = next + (id to cur.copy(blockedOnPage = cur.blockedOnPage + d.toInt()))
                                }
                                next
                            }
                        }
                    }
                }
            }
        }
        if (blockedFlushJob?.isActive != true) {
            blockedFlushJob = viewModelScope.launch {
                delay(3_000L)
                val delta = pendingBlockedDelta.getAndSet(0L)
                if (delta > 0L) {
                    prefs.incrementBlockedCount(delta)
                }
            }
        }
    }

    fun addCustomFilterRule(pattern: String, type: FilterRuleType) {
        if (pattern.isBlank()) return
        viewModelScope.launch { repo.addAdBlockRule(pattern, type) }
    }

    fun toggleAdBlockRule(rule: AdBlockRuleEntity) {
        viewModelScope.launch { repo.toggleAdBlockRule(rule) }
    }

    fun deleteAdBlockRule(rule: AdBlockRuleEntity) {
        viewModelScope.launch { repo.deleteAdBlockRule(rule) }
    }

    fun triggerFilterListUpdate() {
        runCatching {
            val req = OneTimeWorkRequestBuilder<FilterUpdateWorker>().build()
            WorkManager.getInstance(app).enqueue(req)
            showMessage("Updating filter lists in background...")
        }
    }

    fun toggleSiteAdBlock(host: String) {
        if (host.isBlank()) return
        viewModelScope.launch {
            val existing = repo.getSitePermission(activeProfileId.value, host)
            val updated = existing?.copy(adBlockEnabled = !existing.adBlockEnabled)
                ?: SitePermissionEntity(profileId = activeProfileId.value, originHost = host, adBlockEnabled = false)
            repo.upsertSitePermission(updated)
            activeTab.value?.id?.let { webViewRegistry[it]?.reload() }
        }
    }

    fun updateSitePermission(perm: SitePermissionEntity) {
        viewModelScope.launch { repo.upsertSitePermission(perm) }
    }

    // Settings updates
    fun setSearchEngine(engine: SearchEngine) = viewModelScope.launch { prefs.setSearchEngine(engine) }
    fun setSafeSearchEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setSafeSearchEnabled(enabled) }
    fun setHomepage(url: String) = viewModelScope.launch {
        val trimmed = url.trim()
        val safeUrl = when {
            trimmed.isBlank() || trimmed.equals("weblio://home", ignoreCase = true) -> "weblio://home"
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.contains("://") || trimmed.contains(":") -> "weblio://home"
            else -> "https://$trimmed"
        }
        prefs.setHomepage(safeUrl)
    }
    fun setStartupBehavior(b: StartupBehavior) = viewModelScope.launch { prefs.setStartupBehavior(b) }
    fun setThemeMode(mode: AppThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setWallpaperStyle(style: WallpaperStyle) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val dir = File(app.filesDir, "wallpapers")
            if (dir.exists()) runCatching { dir.deleteRecursively() }
        }
        prefs.setCustomHomeBackgroundPath("")
        prefs.setWallpaperStyle(style)
    }

    fun setCustomHomeBackground(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val wallpapersDir = File(app.filesDir, "wallpapers").apply { mkdirs() }
            val tempFile = File(wallpapersDir, "pending_${System.currentTimeMillis()}.tmp")
            val maxBytes = 25L * 1024L * 1024L
            val savedPath = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) {
                                error("Image exceeds 25 MB limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: error("Unable to read selected image")

                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    error("Unsupported or corrupted image file")
                }

                val destFile = File(wallpapersDir, "home_bg_${System.currentTimeMillis()}.img")
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                wallpapersDir.listFiles()?.forEach { existing ->
                    if (existing.absolutePath != destFile.absolutePath) {
                        existing.delete()
                    }
                }
                destFile.absolutePath
            }.onFailure {
                tempFile.delete()
            }

            savedPath.onSuccess { path ->
                prefs.setCustomHomeBackgroundPath(path)
                showMessage("Homepage background updated")
            }.onFailure { err ->
                showMessage(err.message ?: "Failed to set homepage background")
            }
        }
    }

    fun clearCustomHomeBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(app.filesDir, "wallpapers").deleteRecursively() }
            prefs.setCustomHomeBackgroundPath("")
            showMessage("Homepage background reset")
        }
    }
    fun setForceDarkWeb(enabled: Boolean) = viewModelScope.launch { prefs.setForceDarkWeb(enabled) }
    fun setShowQuickLinks(show: Boolean) = viewModelScope.launch { prefs.setShowQuickLinks(show) }
    fun setCompactToolbar(compact: Boolean) = viewModelScope.launch { prefs.setCompactToolbar(compact) }
    fun setAdBlockerEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setAdBlockerEnabled(enabled) }
    fun setTrackerProtection(enabled: Boolean) = viewModelScope.launch { prefs.setTrackerProtection(enabled) }
    fun setPopupBlocking(enabled: Boolean) = viewModelScope.launch { prefs.setPopupBlocking(enabled) }
    fun setThirdPartyBlocking(enabled: Boolean) = viewModelScope.launch { prefs.setThirdPartyBlocking(enabled) }
    fun setCookiePolicy(policy: CookiePolicy) = viewModelScope.launch { prefs.setCookiePolicy(policy) }
    fun setDnsFilterMode(mode: DnsFilterMode) = viewModelScope.launch { prefs.setDnsFilterMode(mode) }
    fun setCustomDohUrl(url: String) = viewModelScope.launch {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("https://", ignoreCase = true)) {
            prefs.setCustomDohUrl(trimmed)
        } else {
            showMessage("Custom DNS-over-HTTPS URL must use https://")
        }
    }
    fun setVpnDnsEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setVpnDnsEnabled(enabled) }
    fun setClearIncognitoOnExit(enabled: Boolean) = viewModelScope.launch { prefs.setClearIncognitoOnExit(enabled) }
    fun setSafeBrowsing(enabled: Boolean) = viewModelScope.launch { prefs.setSafeBrowsing(enabled) }
    fun setClipboardGuard(enabled: Boolean) = viewModelScope.launch { prefs.setClipboardGuard(enabled) }
    fun setBiometricAppLock(enabled: Boolean) = viewModelScope.launch { prefs.setBiometricAppLock(enabled) }
    fun setJavascriptEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setJavascriptEnabled(enabled) }
    fun setDesktopModeDefault(enabled: Boolean) = viewModelScope.launch { prefs.setDesktopModeDefault(enabled) }
    fun setHardwareAcceleration(enabled: Boolean) = viewModelScope.launch { prefs.setHardwareAcceleration(enabled) }
    fun setDownloadNotifications(enabled: Boolean) = viewModelScope.launch { prefs.setDownloadNotifications(enabled) }
    fun setAskBeforeDownload(ask: Boolean) = viewModelScope.launch { prefs.setAskBeforeDownload(ask) }
    fun setHttpsFirstEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setHttpsFirstEnabled(enabled) }
    fun setDownloadScanEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setDownloadScanEnabled(enabled) }
    fun setCloudHashReputationEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setCloudHashReputationEnabled(enabled) }
    fun setSecurityNotificationsEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setSecurityNotificationsEnabled(enabled) }

    fun runIntegrityCheck() {
        val report = integrityManager.verifyAppIntegrity()
        _appIntegrityReport.value = report
        if (report.state == SecurityState.THREAT_DETECTED || report.tamperWarnings.isNotEmpty()) {
            viewModelScope.launch {
                repo.recordSecurityEvent(
                    category = "INTEGRITY",
                    severity = if (report.state == SecurityState.THREAT_DETECTED) "CRITICAL" else "WARNING",
                    title = "Application integrity check: ${report.state.label}",
                    summary = report.tamperWarnings.joinToString("; ").ifBlank { report.playIntegrityNote },
                    targetHostOrFile = report.packageName,
                    actionTaken = "Logged"
                )
            }
            if (report.state == SecurityState.THREAT_DETECTED) {
                SecurityNotifications.notify(
                    context = app,
                    enabled = settings.value.securityNotificationsEnabled,
                    kind = SecurityNotifications.AlertKind.INTEGRITY_WARNING,
                    safeMessage = report.tamperWarnings.firstOrNull() ?: "APK signature or build integrity check failed."
                )
            }
        }
    }

    fun updateThreatDatabaseNow() {
        if (_isUpdatingThreatDb.value) return
        _isUpdatingThreatDb.value = true
        viewModelScope.launch(Dispatchers.IO) {
            threatEngine.updateThreatDatabase()
                .onSuccess { count ->
                    prefs.setLastSecurityUpdate(System.currentTimeMillis())
                    _threatDbCount.value = repo.countThreatIndicators()
                    repo.recordSecurityEvent(
                        category = "NETWORK",
                        severity = "INFO",
                        title = "Threat intelligence updated",
                        summary = "Synced $count local threat domain indicators over HTTPS.",
                        targetHostOrFile = "URLhaus Feed",
                        actionTaken = "Cached Locally"
                    )
                    showMessage("Threat DB updated ($count indicators)")
                }
                .onFailure { err ->
                    repo.recordSecurityEvent(
                        category = "NETWORK",
                        severity = "WARNING",
                        title = "Threat DB update failed",
                        summary = err.message ?: "Unable to fetch HTTPS threat feed",
                        targetHostOrFile = "Threat Feed",
                        actionTaken = "Using Local Cache"
                    )
                    SecurityNotifications.notify(
                        context = app,
                        enabled = settings.value.securityNotificationsEnabled,
                        kind = SecurityNotifications.AlertKind.THREAT_DB_UPDATE_FAILED,
                        safeMessage = "Threat intelligence update failed; continuing with local cache."
                    )
                    showMessage("Threat DB update failed: ${err.message ?: "Offline"}")
                }
            _isUpdatingThreatDb.value = false
        }
    }

    fun verifyUpdatePackageFile(apkFile: File, metadataJson: String): Result<UpdateVerificationResult> {
        val meta = updateVerifier.validateMetadata(metadataJson).getOrElse {
            viewModelScope.launch {
                repo.recordSecurityEvent(
                    category = "UPDATE",
                    severity = "WARNING",
                    title = "Update metadata rejected",
                    summary = it.message ?: "Invalid update metadata",
                    targetHostOrFile = apkFile.name,
                    actionTaken = "Rejected"
                )
            }
            return Result.failure(it)
        }
        val currentSigner = integrityManager.getCurrentSignerSha256()
        val result = updateVerifier.verifyUpdateApkFile(apkFile, meta, currentSigner)
        viewModelScope.launch {
            repo.recordSecurityEvent(
                category = "UPDATE",
                severity = if (result.isValid) "INFO" else "CRITICAL",
                title = if (result.isValid) "Update verified (${meta.versionName})" else "Update verification failed",
                summary = result.reason,
                targetHostOrFile = apkFile.name,
                actionTaken = if (result.isValid) "Verified" else "Blocked"
            )
        }
        return Result.success(result)
    }

    fun clearSecurityHistory() {
        viewModelScope.launch { repo.clearSecurityEvents() }
    }

    fun computeOverallSecurityState(): SecurityState {
        val integrity = _appIntegrityReport.value
        val s = settings.value
        val (hasDangerousQuarantine, hasAnyQuarantine) = quarantineSecurityFlags.value
        val hasHighRiskEnabledExt = extensions.value.any { it.enabled && it.riskLevel == "HIGH" }

        return when {
            integrity?.state == SecurityState.THREAT_DETECTED ||
                hasDangerousQuarantine ||
                _pendingWebsiteWarning.value != null -> SecurityState.THREAT_DETECTED

            !s.safeBrowsingEnabled ||
                !s.downloadScanEnabled ||
                !s.httpsFirstEnabled ||
                integrity?.state == SecurityState.ATTENTION_REQUIRED ||
                hasAnyQuarantine ||
                hasHighRiskEnabledExt -> SecurityState.ATTENTION_REQUIRED

            integrity?.state == SecurityState.VERIFICATION_UNAVAILABLE -> SecurityState.VERIFICATION_UNAVAILABLE

            else -> SecurityState.PROTECTED
        }
    }

    override fun onCleared() {
        webViewRegistry.keys.toList().forEach { destroyWebViewOnly(it) }
        _tabPreviews.value = emptyMap()
        super.onCleared()
    }

    companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val DESKTOP_VIEWPORT_WIDTH_DP = 1200f
        const val MAX_LIVE_WEBVIEWS = 3

        fun computeDesktopInitialScalePercent(
            viewWidthPx: Int,
            displayWidthPx: Int,
            density: Float,
            targetDesktopWidthDp: Float = DESKTOP_VIEWPORT_WIDTH_DP
        ): Int {
            val effectivePx = if (viewWidthPx > 0) viewWidthPx else displayWidthPx
            val safeDensity = if (density > 0f) density else 1f
            val dpWidth = (effectivePx / safeDensity).coerceAtLeast(320f)
            return ((dpWidth / targetDesktopWidthDp) * 100f).roundToInt().coerceIn(25, 100)
        }

        fun applyDesktopUserAgent(
            webView: WebView,
            desktop: Boolean,
            forceInitialScale: Boolean = false
        ) {
            val wasDesktop = webView.settings.userAgentString == DESKTOP_USER_AGENT
            if (desktop) {
                if (!wasDesktop) {
                    webView.settings.userAgentString = DESKTOP_USER_AGENT
                }
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                if (!wasDesktop || forceInitialScale || webView.url.isNullOrBlank()) {
                    val dm = webView.resources.displayMetrics
                    webView.setInitialScale(
                        computeDesktopInitialScalePercent(
                            viewWidthPx = webView.width,
                            displayWidthPx = dm.widthPixels,
                            density = dm.density
                        )
                    )
                }
            } else {
                if (wasDesktop) {
                    webView.settings.userAgentString = null
                    webView.setInitialScale(0)
                }
            }
        }

        fun resolveInputToUrl(
            raw: String,
            searchEngine: SearchEngine,
            safeSearchEnabled: Boolean = true
        ): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "weblio://home") return "weblio://home"
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("about:", ignoreCase = true)
            ) {
                return trimmed
            }
            val looksLikeHost = !trimmed.contains(" ") &&
                (trimmed.contains(".") || trimmed.startsWith("localhost", ignoreCase = true))
            return if (looksLikeHost) "https://$trimmed" else searchEngine.buildSearchUrl(trimmed, safeSearchEnabled)
        }

        fun extractHost(url: String): String {
            return runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        }

        fun computeDisplayedTabCount(tabs: List<TabEntity>, currentTab: TabEntity?): Int {
            val isIncognito = currentTab?.isIncognito == true
            return tabs.count { it.isIncognito == isIncognito }
        }

        const val STALE_TAB_RELOAD_MS = 10L * 60_000L
    }
}
