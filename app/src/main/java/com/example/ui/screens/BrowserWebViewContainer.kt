package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.data.db.DownloadStatus
import com.example.data.db.ExtensionEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.SitePermissionEntity
import com.example.data.db.TabEntity
import com.example.data.prefs.BrowserSettings
import com.example.data.prefs.CookiePolicy
import com.example.engine.ExtensionEngine
import com.example.engine.ProfileIsolationManager
import com.example.engine.WeblioDownloadManager
import com.example.engine.WeblioMediaService
import com.example.security.WebsiteVerdict
import com.example.ui.BrowserViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID

private data class SiteOriginPermissionPrompt(
    val origin: String,
    val host: String,
    val isGeo: Boolean,
    val resources: Array<String>,
    val webRequest: PermissionRequest? = null,
    val geoCallback: GeolocationPermissions.Callback? = null
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebViewContainer(
    tab: TabEntity,
    activeProfile: ProfileEntity?,
    settings: BrowserSettings,
    extensions: List<ExtensionEntity>,
    sitePermissions: List<SitePermissionEntity>,
    viewModel: BrowserViewModel,
    onChromeWebStoreDetected: () -> Unit,
    onFullscreenVideoChanged: (View?) -> Unit,
    modifier: Modifier = Modifier,
    webViewInstanceVersion: Int = 0,
    errorBanner: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestTab by rememberUpdatedState(tab)
    val latestExtensions by rememberUpdatedState(extensions)
    val hasEnabledExtensions = remember(extensions) { extensions.any { it.enabled } }
    val latestHasEnabledExtensions by rememberUpdatedState(hasEnabledExtensions)
    val latestPermissions by rememberUpdatedState(sitePermissions)
    val permByHost = remember(sitePermissions) { sitePermissions.associateBy { it.originHost } }
    val latestPermByHost by rememberUpdatedState(permByHost)
    val currentPageHost = remember(tab.url) { BrowserViewModel.extractHost(tab.url) }
    val latestPageHost by rememberUpdatedState(currentPageHost)
    val latestSettings by rememberUpdatedState(settings)

    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    var pendingWebPermission by remember { mutableStateOf<Pair<PermissionRequest, Array<String>>?>(null) }
    val webPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val pending = pendingWebPermission
        pendingWebPermission = null
        if (pending != null) {
            val (req, allowedResources) = pending
            val grantedResources = allowedResources.filter { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    else -> false
                }
            }.toTypedArray()
            if (grantedResources.isNotEmpty()) {
                req.grant(grantedResources)
            } else {
                req.deny()
            }
        }
    }

    var pendingGeoCallback by remember { mutableStateOf<Pair<String, GeolocationPermissions.Callback>?>(null) }
    val geoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingGeoCallback
        pendingGeoCallback = null
        if (pending != null) {
            pending.second.invoke(pending.first, granted, false)
        }
    }

    var siteOriginPrompt by remember { mutableStateOf<SiteOriginPermissionPrompt?>(null) }

    fun proceedWithWebResources(req: PermissionRequest, allowedResources: Array<String>) {
        val needed = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowedResources &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowedResources &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) {
            req.grant(allowedResources)
        } else {
            pendingWebPermission = req to allowedResources
            webPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun proceedWithGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            callback.invoke(origin, true, false)
        } else {
            pendingGeoCallback = origin to callback
            geoPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    val localDownloadsAssetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .setDomain(WeblioDownloadManager.LOCAL_PREVIEW_HOST)
            .addPathHandler(
                "/",
                WebViewAssetLoader.InternalStoragePathHandler(
                    context,
                    viewModel.downloadManager.quarantineManager.downloadsDir
                )
            )
            .build()
    }

    val nativeBridgeToken = remember(tab.id, activeProfile?.id) { UUID.randomUUID().toString() }

    val webViewBackgroundArgb = androidx.compose.material3.MaterialTheme.colorScheme.background.toArgb()
    val webView = remember(tab.id, activeProfile?.id, webViewInstanceVersion) {
        viewModel.getWebView(tab.id) ?: WebView(context).apply {
            setBackgroundColor(webViewBackgroundArgb)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            ProfileIsolationManager.applyProfileToWebView(
                webView = this,
                webProfileName = activeProfile?.webProfileName ?: "weblio_personal",
                isIncognito = tab.isIncognito,
                isGuest = activeProfile?.isGuest == true
            )
            val initialHost = BrowserViewModel.extractHost(tab.url)
            val initialPerm = sitePermissions.firstOrNull { it.originHost == initialHost }
            val initialDesktop = tab.desktopMode ||
                initialPerm?.desktopMode == true ||
                viewModel.tabRuntime.value[tab.id]?.isDesktopMode == true ||
                ExtensionEngine.isExtensionStoreUrl(tab.url) ||
                ExtensionEngine.isExtensionStoreUrl(viewModel.tabRuntime.value[tab.id]?.currentUrl.orEmpty())
            configureWebSettings(
                webView = this,
                settings = settings,
                desktopMode = initialDesktop,
                isIncognito = tab.isIncognito,
                sitePermission = initialPerm
            )
            setFindListener { activeOrdinal, numberOfMatches, _ ->
                viewModel.updateRuntimeState(tab.id) {
                    it.copy(findActiveMatch = activeOrdinal, findTotalMatches = numberOfMatches)
                }
            }
            addJavascriptInterface(
                viewModel.extensionEngine.createJsBridge { extId, err ->
                    viewModel.showMessage("Extension $extId error: $err")
                },
                "WeblioExtBridge"
            )
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onCredentialsSubmitted(token: String, user: String, pass: String) {
                        if (token != nativeBridgeToken) return
                        this@apply.post {
                            val currentUrl = this@apply.url.orEmpty()
                            if (!currentUrl.startsWith("https://", ignoreCase = true)) return@post
                            val realHost = BrowserViewModel.extractHost(currentUrl)
                            if (realHost.isBlank() || realHost.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) {
                                return@post
                            }
                            scope.launch {
                                viewModel.promptSaveCredential(realHost, user, pass)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onMediaStateChanged(token: String, playing: Boolean, title: String) {
                        if (token != nativeBridgeToken) return
                        this@apply.post {
                            val currentUrl = this@apply.url.orEmpty()
                            if (!currentUrl.startsWith("https://", ignoreCase = true) &&
                                !currentUrl.startsWith("http://", ignoreCase = true)
                            ) {
                                return@post
                            }
                            val realHost = BrowserViewModel.extractHost(currentUrl)
                            if (realHost.isBlank() || realHost.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) {
                                return@post
                            }
                            val safeTitle = title.trim().take(120)
                            viewModel.updateRuntimeState(tab.id) { it.copy(isMediaPlaying = playing) }
                            if (playing) {
                                val svc = Intent(context, WeblioMediaService::class.java).apply {
                                    action = WeblioMediaService.ACTION_START
                                    putExtra(WeblioMediaService.EXTRA_TITLE, safeTitle.ifBlank { tab.title })
                                    putExtra(WeblioMediaService.EXTRA_HOST, realHost)
                                    putExtra(WeblioMediaService.EXTRA_IS_PLAYING, true)
                                }
                                runCatching {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(svc)
                                    } else {
                                        context.startService(svc)
                                    }
                                }
                            } else {
                                val stopSvc = Intent(context, WeblioMediaService::class.java).apply {
                                    action = WeblioMediaService.ACTION_STOP
                                }
                                runCatching { context.startService(stopSvc) }
                            }
                        }
                    }
                },
                "WeblioNative"
            )
            viewModel.registerWebView(tab.id, this)
        }
    }

    val currentHostSitePerm = permByHost[currentPageHost]
    // Sync only WebView-relevant settings so unrelated counter increments or same-host navigations don't re-apply JNI settings
    LaunchedEffect(
        settings.javascriptEnabled,
        settings.safeBrowsingEnabled,
        settings.forceDarkWebPages,
        settings.hardwareAccelerationEnabled,
        settings.desktopModeDefault,
        settings.cookiePolicy,
        tab.desktopMode,
        currentPageHost,
        currentHostSitePerm,
        ExtensionEngine.isExtensionStoreUrl(tab.url)
    ) {
        val useDesktop = tab.desktopMode || (currentHostSitePerm?.desktopMode == true) || ExtensionEngine.isExtensionStoreUrl(tab.url)
        configureWebSettings(webView, settings, useDesktop, tab.isIncognito, currentHostSitePerm)
    }

    var lastZoomFitUrl by remember(tab.id, webViewInstanceVersion) { mutableStateOf<String?>(null) }

    // Attach dynamic clients that read latest state
    DisposableEffect(tab.id, webView) {
        var lastReportedProgress = -1
        var desktopShimInjectedInProgress = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                val uri = request.url
                val reqScheme = uri?.scheme?.lowercase().orEmpty()
                if (reqScheme == "file" || reqScheme == "content" || reqScheme == "javascript") {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked subresource scheme",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                if (uri != null && uri.host.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) {
                    return interceptLocalDownloadPreview(
                        uri = uri,
                        assetLoader = localDownloadsAssetLoader,
                        resolveDownloadById = { id -> viewModel.resolveDownloadForPreview(id) },
                        downloadsRoot = viewModel.downloadManager.quarantineManager.downloadsDir
                    )
                }
                val pageHost = latestPageHost
                val sitePerm = latestPermByHost[pageHost]
                val siteAdBlock = sitePerm?.adBlockEnabled ?: true
                return viewModel.adBlock.interceptRequest(
                    request = request,
                    pageHost = pageHost,
                    siteAdBlockEnabled = siteAdBlock,
                    onBlocked = { viewModel.onBlockedRequest(it.url) }
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                val urlStr = uri.toString()

                if (uri.host.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) {
                    return false
                }

                val isStoreUrl = ExtensionEngine.isExtensionStoreUrl(urlStr)
                val isStoreOrDesktop = isStoreUrl ||
                    latestTab.desktopMode ||
                    viewModel.tabRuntime.value[latestTab.id]?.isDesktopMode == true
                if (isStoreOrDesktop) {
                    if (view?.settings?.userAgentString != BrowserViewModel.DESKTOP_USER_AGENT) {
                        view?.let { BrowserViewModel.applyDesktopUserAgent(it, true) }
                    }
                    if (!latestTab.desktopMode) {
                        viewModel.enableDesktopModeForTab(latestTab.id)
                    }
                    if (isStoreUrl && (urlStr.contains("chromewebstore.google.com") || urlStr.contains("chrome.google.com/webstore"))) {
                        onChromeWebStoreDetected()
                    }
                }

                if (scheme == "http" || scheme == "https") {
                    var targetUrl = viewModel.adBlock.cleanTrackingUrl(urlStr)
                    if (latestSettings.httpsFirstEnabled && scheme == "http") {
                        val h = BrowserViewModel.extractHost(targetUrl).lowercase()
                        if (h.isNotBlank() && h != "localhost" && !h.startsWith("127.") && !h.startsWith("192.168.") && !h.startsWith("10.")) {
                            targetUrl = "https://" + targetUrl.removePrefix("http://").removePrefix("HTTP://")
                        }
                    }
                    if (request.isForMainFrame && latestSettings.safeSearchEnabled) {
                        targetUrl = com.example.data.prefs.SearchEngine.enforceSafeSearchOnUrl(targetUrl, true)
                    }

                    if (latestSettings.safeBrowsingEnabled) {
                        val syncThreat = viewModel.threatEngine.quickEvaluateSync(targetUrl)
                        if (syncThreat != null && !viewModel.consumeOneTimeAllowance(targetUrl)) {
                            viewModel.triggerWebsiteWarning(latestTab.id, syncThreat, latestTab.isIncognito)
                            return true
                        }
                        if (request.isForMainFrame) {
                            val previousUrl = latestTab.url
                            val isRedirectFlag = request.isRedirect
                            scope.launch {
                                val report = viewModel.evaluateUrlSafety(
                                    url = targetUrl,
                                    isRedirect = isRedirectFlag,
                                    originalUrl = previousUrl
                                )
                                if (report.verdict != WebsiteVerdict.SAFE && !viewModel.consumeOneTimeAllowance(targetUrl)) {
                                    viewModel.triggerWebsiteWarning(latestTab.id, report, latestTab.isIncognito)
                                }
                            }
                        }
                    }

                    if (targetUrl != urlStr) {
                        view?.loadUrl(targetUrl)
                        return true
                    }
                    return false
                }

                // Safely block internal dangerous schemes and sanitize external app intents
                if (scheme == "javascript" || scheme == "file" || scheme == "content" || scheme == "data") {
                    return true
                }
                return runCatching {
                    val intent = if (scheme == "intent") {
                        Intent.parseUri(urlStr, Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            component = null
                            selector = null
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, uri).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                    }
                    if (intent.action != null && intent.action != Intent.ACTION_VIEW) {
                        return@runCatching true
                    }
                    // Strip dangerous flags & clip data, and block intents targeting WEBLIO internal components
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    intent.clipData = null
                    if (intent.`package` == context.packageName) return@runCatching true
                    val resolvedInfo = intent.resolveActivityInfo(context.packageManager, 0)
                    if (resolvedInfo != null && resolvedInfo.packageName != context.packageName && resolvedInfo.exported) {
                        context.startActivity(intent)
                    }
                    true
                }.getOrDefault(true)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val targetUrl = url ?: return
                val isLocalPreview = BrowserViewModel.extractHost(targetUrl)
                    .equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)
                if (!isLocalPreview && latestSettings.safeBrowsingEnabled &&
                    (targetUrl.startsWith("http://", true) || targetUrl.startsWith("https://", true))
                ) {
                    val syncThreat = viewModel.threatEngine.quickEvaluateSync(targetUrl)
                    if (syncThreat != null && !viewModel.consumeOneTimeAllowance(targetUrl)) {
                        view?.stopLoading()
                        viewModel.triggerWebsiteWarning(latestTab.id, syncThreat, latestTab.isIncognito)
                        return
                    }
                    scope.launch {
                        val report = viewModel.evaluateUrlSafety(targetUrl)
                        if (report.verdict != WebsiteVerdict.SAFE && !viewModel.consumeOneTimeAllowance(targetUrl)) {
                            view?.post { view.stopLoading() }
                            viewModel.triggerWebsiteWarning(latestTab.id, report, latestTab.isIncognito)
                        }
                    }
                }
                val isStoreTarget = ExtensionEngine.isExtensionStoreUrl(targetUrl)
                val shouldDesktop = isStoreTarget ||
                    latestTab.desktopMode ||
                    viewModel.tabRuntime.value[latestTab.id]?.isDesktopMode == true ||
                    latestSettings.desktopModeDefault
                if (isStoreTarget) {
                    viewModel.enableDesktopModeForTab(latestTab.id)
                }
                if (shouldDesktop && view != null && view.settings.userAgentString != BrowserViewModel.DESKTOP_USER_AGENT) {
                    BrowserViewModel.applyDesktopUserAgent(view, true)
                    view.stopLoading()
                    viewModel.markUrlDispatched(latestTab.id, targetUrl)
                    view.loadUrl(targetUrl)
                    return
                }
                lastReportedProgress = -1
                desktopShimInjectedInProgress = false
                if (lastZoomFitUrl != targetUrl) {
                    lastZoomFitUrl = null
                }
                viewModel.markUrlDispatched(latestTab.id, targetUrl)
                viewModel.updateRuntimeState(latestTab.id) {
                    val urlChanged = it.currentUrl != targetUrl
                    it.copy(
                        currentUrl = targetUrl,
                        title = if (urlChanged) BrowserViewModel.extractHost(targetUrl).ifBlank { targetUrl } else it.title,
                        isLoading = true,
                        progress = 10,
                        isSecureHttps = targetUrl.startsWith("https://"),
                        blockedOnPage = 0,
                        errorBanner = null,
                        canGoBack = view?.canGoBack() ?: false,
                        canGoForward = view?.canGoForward() ?: false,
                        isDesktopMode = shouldDesktop
                    )
                }
                if (shouldDesktop) {
                    injectDesktopEnvironmentShim(view, injectViewport = true)
                }
                if (!isLocalPreview && latestHasEnabledExtensions) {
                    val startScript = viewModel.extensionEngine.buildInjectionPayload(
                        extensions = latestExtensions,
                        url = targetUrl,
                        stage = "document_start",
                        onIntegrityViolation = { violated -> viewModel.reportExtensionTampering(violated) }
                    )
                    if (startScript.isNotBlank()) {
                        view?.evaluateJavascript(startScript, null)
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val targetUrl = url ?: return
                if (targetUrl == "about:blank" || targetUrl == "weblio://home") return
                val isStoreUrl = ExtensionEngine.isExtensionStoreUrl(targetUrl)
                val shouldDesktop = isStoreUrl ||
                    latestTab.desktopMode ||
                    viewModel.tabRuntime.value[latestTab.id]?.isDesktopMode == true ||
                    latestSettings.desktopModeDefault
                if (isStoreUrl) {
                    view?.let { BrowserViewModel.applyDesktopUserAgent(it, true) }
                    viewModel.enableDesktopModeForTab(latestTab.id)
                }
                viewModel.markUrlDispatched(latestTab.id, targetUrl)
                viewModel.updateRuntimeState(latestTab.id) {
                    it.copy(
                        currentUrl = targetUrl,
                        isSecureHttps = targetUrl.startsWith("https://"),
                        canGoBack = view?.canGoBack() ?: false,
                        canGoForward = view?.canGoForward() ?: false,
                        isDesktopMode = shouldDesktop
                    )
                }
                if (shouldDesktop) {
                    injectDesktopEnvironmentShim(view, injectViewport = true)
                }
                val title = view?.title?.trim().orEmpty()
                if (title.isNotBlank() && title != "about:blank") {
                    viewModel.onPageCommitted(latestTab, targetUrl, title)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val targetUrl = url ?: return
                val pageTitle = view?.title?.trim().orEmpty()
                viewModel.updateRuntimeState(latestTab.id) {
                    it.copy(
                        currentUrl = targetUrl,
                        title = pageTitle.ifBlank { BrowserViewModel.extractHost(targetUrl).ifBlank { targetUrl } },
                        isLoading = false,
                        progress = 100,
                        isSecureHttps = targetUrl.startsWith("https://"),
                        canGoBack = view?.canGoBack() ?: false,
                        canGoForward = view?.canGoForward() ?: false,
                        rendererCrashCount = 0
                    )
                }
                viewModel.onPageCommitted(latestTab, targetUrl, pageTitle)
                // Defer thumbnail capture briefly so Chromium finishes compositing without main-thread frame hitch
                view?.postDelayed({ viewModel.captureTabPreview(latestTab.id) }, 450L)

                // Inject Cosmetic AdBlock CSS
                val pageHost = BrowserViewModel.extractHost(targetUrl)
                val siteAdBlock = latestPermByHost[pageHost]?.adBlockEnabled ?: true
                val cosmeticCss = viewModel.adBlock.buildCosmeticCss(siteAdBlock)
                if (cosmeticCss.isNotBlank()) {
                    val cssJs = """
                        (function(){
                            var s=document.createElement('style');
                            s.textContent=${JSONObject.quote(cosmeticCss)};
                            (document.head||document.documentElement).appendChild(s);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(cssJs, null)
                }

                // Inject matching WebExtensions & UserScripts (document_end) — never into local HTML previews
                if (latestHasEnabledExtensions && !pageHost.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) {
                    val endScript = viewModel.extensionEngine.buildInjectionPayload(
                        extensions = latestExtensions,
                        url = targetUrl,
                        stage = "document_end",
                        onIntegrityViolation = { violated -> viewModel.reportExtensionTampering(violated) }
                    )
                    if (endScript.isNotBlank()) {
                        view?.evaluateJavascript(endScript, null)
                    }
                }

                // Enforce desktop viewport (1200px) & platform shim when desktop mode is active
                val isDesktopFinished = latestTab.desktopMode ||
                    viewModel.tabRuntime.value[latestTab.id]?.isDesktopMode == true ||
                    latestSettings.desktopModeDefault ||
                    ExtensionEngine.isExtensionStoreUrl(targetUrl)
                if (isDesktopFinished) {
                    injectDesktopEnvironmentShim(view, injectViewport = true)
                    if (view != null && lastZoomFitUrl != targetUrl) {
                        val checkUrl = targetUrl
                        view.postDelayed({
                            if (lastZoomFitUrl == checkUrl) return@postDelayed
                            view.evaluateJavascript(
                                """
                                (function(){
                                    try {
                                        if (window.scrollX !== 0) window.scrollTo(0, window.scrollY);
                                        var iw = window.innerWidth || 0;
                                        var sw = (document.documentElement && document.documentElement.scrollWidth) || 0;
                                        return iw + ',' + sw;
                                    } catch(e) { return '0,0'; }
                                })();
                                """.trimIndent()
                            ) { raw ->
                                val parts = raw?.trim('"').orEmpty().split(',')
                                val iw = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
                                val sw = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                                if (iw > 0f && sw >= 900f && iw < (sw * 0.85f)) {
                                    lastZoomFitUrl = checkUrl
                                    val factor = (iw / maxOf(sw, BrowserViewModel.DESKTOP_VIEWPORT_WIDTH_DP))
                                        .coerceIn(0.25f, 0.92f)
                                    view.zoomBy(factor)
                                    view.evaluateJavascript("window.scrollTo(0, window.scrollY);", null)
                                } else if (iw >= 900f) {
                                    lastZoomFitUrl = checkUrl
                                }
                            }
                        }, 150L)
                    }
                }

                // Inject Password Autofill & Credential Capture + HTML5 Media Observer
                injectCoreBrowserHelpers(
                    webView = view,
                    url = targetUrl,
                    host = pageHost,
                    tab = latestTab,
                    profile = activeProfile,
                    viewModel = viewModel,
                    scope = scope,
                    nativeBridgeToken = nativeBridgeToken
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    viewModel.updateRuntimeState(tab.id) {
                        it.copy(
                            isLoading = false,
                            errorBanner = error?.description?.toString() ?: "Failed to load page"
                        )
                    }
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Strictly cancel untrusted certificates; NEVER call handler.proceed() silently
                handler?.cancel()
                if (error != null) {
                    viewModel.showSslErrorPrompt(
                        tabId = tab.id,
                        url = error.url ?: tab.url,
                        error = error,
                        onCancel = {
                            if (view?.canGoBack() == true) view.goBack() else viewModel.goHomeInActiveTab()
                        }
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash() ?: true
                } else {
                    true
                }
                view?.let { dead ->
                    dead.stopLoading()
                    dead.webChromeClient = null
                    (dead.parent as? ViewGroup)?.removeView(dead)
                    dead.destroy()
                }
                viewModel.handleRendererProcessGone(latestTab.id, view, didCrash)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100 || newProgress <= 10 || kotlin.math.abs(newProgress - lastReportedProgress) >= 5) {
                    lastReportedProgress = newProgress
                    viewModel.updateRuntimeState(tab.id) {
                        it.copy(progress = newProgress, isLoading = newProgress < 100)
                    }
                }
                if (!desktopShimInjectedInProgress && newProgress in 15..95) {
                    desktopShimInjectedInProgress = true
                    val urlNow = view?.url.orEmpty()
                    val isDesktopNow = latestTab.desktopMode ||
                        viewModel.tabRuntime.value[latestTab.id]?.isDesktopMode == true ||
                        latestSettings.desktopModeDefault ||
                        ExtensionEngine.isExtensionStoreUrl(urlNow)
                    if (isDesktopNow) {
                        injectDesktopEnvironmentShim(view, injectViewport = true)
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    viewModel.updateTabTitle(latestTab.id, title)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (latestSettings.popupBlockingEnabled && !isUserGesture) {
                    viewModel.onBlockedRequest("popup://blocked")
                    return false
                }
                val inheritDesktopBase = latestTab.desktopMode ||
                    viewModel.tabRuntime.value[latestTab.id]?.isDesktopMode == true ||
                    ExtensionEngine.isExtensionStoreUrl(latestTab.url)
                val hit = view?.hitTestResult
                val extraUrl = hit?.extra
                if (!extraUrl.isNullOrBlank()) {
                    viewModel.openNewTab(
                        url = extraUrl,
                        isIncognito = latestTab.isIncognito,
                        desktopModeOverride = inheritDesktopBase || ExtensionEngine.isExtensionStoreUrl(extraUrl)
                    )
                    return false
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(context).apply {
                    this.settings.javaScriptEnabled = false
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = false
                    this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                var tempDestroyed = false
                fun destroyTempWebView() {
                    if (tempDestroyed) return
                    tempDestroyed = true
                    runCatching {
                        tempWebView.stopLoading()
                        (tempWebView.parent as? ViewGroup)?.removeView(tempWebView)
                        tempWebView.removeAllViews()
                        tempWebView.destroy()
                    }
                }
                tempWebView.webViewClient = object : WebViewClient() {
                    private var handled = false
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val target = req?.url?.toString()
                        if (!handled && !target.isNullOrBlank() &&
                            (target.startsWith("http://", true) || target.startsWith("https://", true))
                        ) {
                            handled = true
                            viewModel.openNewTab(
                                url = target,
                                isIncognito = latestTab.isIncognito,
                                desktopModeOverride = inheritDesktopBase || ExtensionEngine.isExtensionStoreUrl(target)
                            )
                        }
                        destroyTempWebView()
                        return true
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        if (!handled && !url.isNullOrBlank() &&
                            (url.startsWith("http://", true) || url.startsWith("https://", true))
                        ) {
                            handled = true
                            viewModel.openNewTab(
                                url = url,
                                isIncognito = latestTab.isIncognito,
                                desktopModeOverride = inheritDesktopBase || ExtensionEngine.isExtensionStoreUrl(url)
                            )
                        }
                        destroyTempWebView()
                    }

                    override fun onRenderProcessGone(
                        v: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        destroyTempWebView()
                        return true
                    }
                }
                tempWebView.postDelayed({ destroyTempWebView() }, 10_000L)
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val mimeTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray()
                    ?: arrayOf("*/*")
                filePickerLauncher.launch(if (mimeTypes.isEmpty()) arrayOf("*/*") else mimeTypes)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val allowedRes = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }.toTypedArray()
                if (allowedRes.isEmpty()) {
                    request.deny()
                    return
                }
                val originUri = request.origin
                val originScheme = originUri?.scheme?.lowercase().orEmpty()
                val originHost = originUri?.host?.lowercase().orEmpty()
                val isLocalhost = originHost == "localhost" || originHost == "127.0.0.1"
                if (originHost.isBlank() ||
                    originHost == WeblioDownloadManager.LOCAL_PREVIEW_HOST ||
                    (originScheme != "https" && !(originScheme == "http" && isLocalhost))
                ) {
                    request.deny()
                    return
                }
                val existing = latestPermissions.firstOrNull { it.originHost == originHost }
                val asksCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowedRes
                val asksMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowedRes
                if ((asksCamera && existing?.cameraAllowed == "DENY") ||
                    (asksMic && existing?.micAllowed == "DENY")
                ) {
                    request.deny()
                    return
                }
                val cameraAlreadyAllowed = !asksCamera || existing?.cameraAllowed == "ALLOW"
                val micAlreadyAllowed = !asksMic || existing?.micAllowed == "ALLOW"
                if (cameraAlreadyAllowed && micAlreadyAllowed) {
                    proceedWithWebResources(request, allowedRes)
                } else {
                    siteOriginPrompt = SiteOriginPermissionPrompt(
                        origin = originUri.toString(),
                        host = originHost,
                        isGeo = false,
                        resources = allowedRes,
                        webRequest = request
                    )
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return
                val parsed = runCatching { Uri.parse(origin) }.getOrNull()
                val originScheme = parsed?.scheme?.lowercase().orEmpty()
                val originHost = parsed?.host?.lowercase().orEmpty()
                val isLocalhost = originHost == "localhost" || originHost == "127.0.0.1"
                if (originHost.isBlank() ||
                    originHost == WeblioDownloadManager.LOCAL_PREVIEW_HOST ||
                    (originScheme != "https" && !(originScheme == "http" && isLocalhost))
                ) {
                    callback.invoke(origin, false, false)
                    return
                }
                val existing = latestPermissions.firstOrNull { it.originHost == originHost }
                when (existing?.locationAllowed) {
                    "DENY" -> callback.invoke(origin, false, false)
                    "ALLOW" -> proceedWithGeolocation(origin, callback)
                    else -> {
                        siteOriginPrompt = SiteOriginPermissionPrompt(
                            origin = origin,
                            host = originHost,
                            isGeo = true,
                            resources = emptyArray(),
                            geoCallback = callback
                        )
                    }
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null) {
                    viewModel.updateRuntimeState(tab.id) { it.copy(isFullScreenVideo = true) }
                    onFullscreenVideoChanged(view)
                }
            }

            override fun onHideCustomView() {
                viewModel.updateRuntimeState(tab.id) { it.copy(isFullScreenVideo = false) }
                onFullscreenVideoChanged(null)
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val guessed = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val isDirectCrx = guessed.endsWith(".crx", ignoreCase = true) ||
                mimetype.equals("application/x-chrome-extension", ignoreCase = true) ||
                url.contains("clients2.google.com/service/update2/crx", ignoreCase = true) ||
                url.contains("edge.microsoft.com/extensionwebstorebase/v1/crx", ignoreCase = true)
            if (isDirectCrx && url.startsWith("https://", ignoreCase = true)) {
                viewModel.installCrxFromDirectUrl(url, guessed)
            } else {
                viewModel.requestDownload(
                    url = url,
                    fileName = guessed,
                    mimeType = mimetype ?: "application/octet-stream",
                    userAgent = userAgent
                )
            }
        }

        runCatching {
            webView.onResume()
            webView.resumeTimers()
        }

        onDispose { }
    }

    LaunchedEffect(tab.id, tab.url, webViewInstanceVersion, errorBanner) {
        if (errorBanner == null && viewModel.shouldDispatchInitialLoad(tab.id, tab.url, webView)) {
            if (tab.desktopMode || settings.desktopModeDefault || ExtensionEngine.isExtensionStoreUrl(tab.url)) {
                BrowserViewModel.applyDesktopUserAgent(webView, true, forceInitialScale = true)
            }
            webView.loadUrl(tab.url)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                FrameLayout(ctx).apply {
                    addView(webView)
                }
            },
            update = { container ->
                if (webView.parent !== container) {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    container.removeAllViews()
                    container.addView(webView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (!errorBanner.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .testTag("webview_error_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = errorBanner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.retryTabLoad(tab.id) },
                        modifier = Modifier.testTag("webview_error_reload_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reload",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Reload")
                    }
                    IconButton(
                        onClick = { viewModel.dismissTabErrorBanner(tab.id) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    siteOriginPrompt?.let { prompt ->
        val capLabels = buildList {
            if (prompt.isGeo) add("Location")
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in prompt.resources) add("Camera")
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in prompt.resources) add("Microphone")
        }.joinToString(" & ")
        AlertDialog(
            onDismissRequest = {
                siteOriginPrompt = null
                prompt.webRequest?.deny()
                prompt.geoCallback?.invoke(prompt.origin, false, false)
            },
            title = { Text("Site Permission Request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${prompt.host} wants to access your $capLabels.")
                    Text("Origin: ${prompt.origin}")
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        siteOriginPrompt = null
                        if (prompt.isGeo && prompt.geoCallback != null) {
                            proceedWithGeolocation(prompt.origin, prompt.geoCallback)
                        } else if (prompt.webRequest != null) {
                            proceedWithWebResources(prompt.webRequest, prompt.resources)
                        }
                    }) {
                        Text("Allow Once")
                    }
                    OutlinedButton(onClick = {
                        siteOriginPrompt = null
                        val existing = latestPermissions.firstOrNull { it.originHost == prompt.host }
                            ?: SitePermissionEntity(
                                profileId = activeProfile?.id ?: 1L,
                                originHost = prompt.host
                            )
                        val updated = existing.copy(
                            locationAllowed = if (prompt.isGeo) "ALLOW" else existing.locationAllowed,
                            cameraAllowed = if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in prompt.resources) "ALLOW" else existing.cameraAllowed,
                            micAllowed = if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in prompt.resources) "ALLOW" else existing.micAllowed
                        )
                        viewModel.updateSitePermission(updated)
                        if (prompt.isGeo && prompt.geoCallback != null) {
                            proceedWithGeolocation(prompt.origin, prompt.geoCallback)
                        } else if (prompt.webRequest != null) {
                            proceedWithWebResources(prompt.webRequest, prompt.resources)
                        }
                    }) {
                        Text("Always Allow for Site")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    siteOriginPrompt = null
                    val existing = latestPermissions.firstOrNull { it.originHost == prompt.host }
                        ?: SitePermissionEntity(
                            profileId = activeProfile?.id ?: 1L,
                            originHost = prompt.host
                        )
                    val updated = existing.copy(
                        locationAllowed = if (prompt.isGeo) "DENY" else existing.locationAllowed,
                        cameraAllowed = if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in prompt.resources) "DENY" else existing.cameraAllowed,
                        micAllowed = if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in prompt.resources) "DENY" else existing.micAllowed
                    )
                    viewModel.updateSitePermission(updated)
                    prompt.webRequest?.deny()
                    prompt.geoCallback?.invoke(prompt.origin, false, false)
                }) {
                    Text("Deny")
                }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebSettings(
    webView: WebView,
    settings: BrowserSettings,
    desktopMode: Boolean,
    isIncognito: Boolean,
    sitePermission: SitePermissionEntity? = null
) {
    val ws = webView.settings
    ws.cacheMode = WebSettings.LOAD_DEFAULT
    ws.javaScriptEnabled = settings.javascriptEnabled && (sitePermission?.javascriptEnabled ?: true)
    ws.domStorageEnabled = !isIncognito
    ws.databaseEnabled = !isIncognito
    ws.mediaPlaybackRequiresUserGesture = false
    ws.builtInZoomControls = true
    ws.displayZoomControls = false
    ws.useWideViewPort = true
    ws.loadWithOverviewMode = true
    ws.allowFileAccess = false
    ws.allowContentAccess = false
    @Suppress("DEPRECATION")
    ws.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    ws.allowUniversalAccessFromFileURLs = false
    ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    ws.setSupportMultipleWindows(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching { webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
    }

    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(ws, settings.safeBrowsingEnabled)
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, settings.forceDarkWebPages)
    }
    if (!settings.hardwareAccelerationEnabled) {
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    } else {
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    BrowserViewModel.applyDesktopUserAgent(webView, desktopMode || settings.desktopModeDefault)

    val cm = CookieManager.getInstance()
    when {
        isIncognito -> {
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, false)
        }
        settings.cookiePolicy == CookiePolicy.BLOCK_ALL -> {
            cm.setAcceptCookie(false)
            cm.setAcceptThirdPartyCookies(webView, false)
        }
        settings.cookiePolicy == CookiePolicy.BLOCK_THIRD_PARTY -> {
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, false)
        }
        else -> {
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)
        }
    }
}

private fun injectDesktopEnvironmentShim(webView: WebView?, injectViewport: Boolean) {
    if (webView == null) return
    val dm = webView.resources.displayMetrics
    val scalePercent = BrowserViewModel.computeDesktopInitialScalePercent(
        viewWidthPx = webView.width,
        displayWidthPx = dm.widthPixels,
        density = dm.density
    )
    val fitScaleStr = String.format(Locale.US, "%.2f", scalePercent / 100f)
    val viewportContent =
        "width=${BrowserViewModel.DESKTOP_VIEWPORT_WIDTH_DP.toInt()}, initial-scale=$fitScaleStr, minimum-scale=0.2, maximum-scale=3.0, user-scalable=yes"
    val js = """
        ;(function(){
            try {
                if (!window.__weblioDesktopSpoofed) {
                    window.__weblioDesktopSpoofed = true;
                    try { Object.defineProperty(navigator, 'platform', { get: function(){ return 'Linux x86_64'; }, configurable: true }); } catch(e) {}
                    try { Object.defineProperty(navigator, 'maxTouchPoints', { get: function(){ return 0; }, configurable: true }); } catch(e) {}
                    if (navigator.userAgentData) {
                        try {
                            Object.defineProperty(navigator, 'userAgentData', {
                                get: function() {
                                    return {
                                        brands: [{brand:'Chromium',version:'131'},{brand:'Google Chrome',version:'131'},{brand:'Not_A Brand',version:'24'}],
                                        mobile: false,
                                        platform: 'Linux',
                                        getHighEntropyValues: function() {
                                            return Promise.resolve({ platform: 'Linux', architecture: 'x86', mobile: false, model: '', uaFullVersion: '131.0.0.0' });
                                        }
                                    };
                                },
                                configurable: true
                            });
                        } catch(e) {}
                    }
                }
                ${if (injectViewport) """
                var headOrDoc = document.head || document.documentElement;
                if (headOrDoc) {
                    var m = document.querySelector('meta[name="viewport"]');
                    if (!m) {
                        m = document.createElement('meta');
                        m.name = 'viewport';
                        headOrDoc.appendChild(m);
                    }
                    var desired = '$viewportContent';
                    if (m.getAttribute('content') !== desired) {
                        m.setAttribute('content', desired);
                    }
                }
                """ else ""}
            } catch(e) {}
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

private fun injectCoreBrowserHelpers(
    webView: WebView?,
    url: String,
    host: String,
    tab: TabEntity,
    profile: ProfileEntity?,
    viewModel: BrowserViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    nativeBridgeToken: String
) {
    if (webView == null || host.isBlank()) return
    // Never inject credential capture or saved password autofill into local HTML file previews
    if (host.equals(WeblioDownloadManager.LOCAL_PREVIEW_HOST, ignoreCase = true)) return
    val quotedNativeToken = JSONObject.quote(nativeBridgeToken)
    // Password capture & HTML5 media playback observer
    val helperJs = """
        ;(function(){
            var __NATIVE_TOKEN__ = $quotedNativeToken;
            if (!window.__weblioHelpersBound) {
                window.__weblioHelpersBound = true;
                document.addEventListener('submit', function(ev) {
                    try {
                        var form = ev.target;
                        if (!form || !form.querySelector) return;
                        var pwInput = form.querySelector('input[type="password"]');
                        if (!pwInput || !pwInput.value) return;
                        var userInput = form.querySelector('input[type="email"], input[type="text"], input[name*="user"], input[name*="login"]');
                        var u = userInput ? userInput.value : '';
                        if (u && window.WeblioNative) {
                            window.WeblioNative.onCredentialsSubmitted(__NATIVE_TOKEN__, u, pwInput.value);
                        }
                    } catch(e) {}
                }, true);

                var bindMedia = function(el) {
                    if (el.__weblioBound) return;
                    el.__weblioBound = true;
                    el.addEventListener('play', function() {
                        if (window.WeblioNative) window.WeblioNative.onMediaStateChanged(__NATIVE_TOKEN__, true, document.title || location.hostname);
                    });
                    el.addEventListener('pause', function() {
                        if (window.WeblioNative) window.WeblioNative.onMediaStateChanged(__NATIVE_TOKEN__, false, document.title || location.hostname);
                    });
                    el.addEventListener('ended', function() {
                        if (window.WeblioNative) window.WeblioNative.onMediaStateChanged(__NATIVE_TOKEN__, false, document.title || location.hostname);
                    });
                };
                document.querySelectorAll('video, audio').forEach(bindMedia);
            }
        })();
    """.trimIndent()
    webView.evaluateJavascript(helperJs, null)

    // Autofill saved password strictly on HTTPS pages if not Guest/Incognito
    val isHttpsOrigin = url.startsWith("https://", ignoreCase = true)
    if (isHttpsOrigin && !tab.isIncognito && profile?.isGuest != true) {
        scope.launch {
            val saved = viewModel.repo.getPasswordsForHost(tab.profileId, host).firstOrNull() ?: return@launch
            val (entity, plain) = saved
            val fillJs = """
                ;(function(){
                    try {
                        var pw = document.querySelector('input[type="password"]');
                        if (!pw || pw.value) return;
                        var u = document.querySelector('input[type="email"], input[type="text"], input[name*="user"], input[name*="login"]');
                        if (u && !u.value) u.value = ${JSONObject.quote(entity.username)};
                        pw.value = ${JSONObject.quote(plain)};
                    } catch(e) {}
                })();
            """.trimIndent()
            webView.post { webView.evaluateJavascript(fillJs, null) }
        }
    }
}

private fun interceptLocalDownloadPreview(
    uri: Uri,
    assetLoader: WebViewAssetLoader,
    resolveDownloadById: (Long) -> com.example.data.db.DownloadEntity?,
    downloadsRoot: File
): WebResourceResponse? {
    val rawPath = uri.path.orEmpty()
    val canonicalRoot = runCatching { downloadsRoot.canonicalFile }.getOrNull() ?: return null
    val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separatorChar

    if (rawPath.startsWith("/__id/")) {
        val downloadId = uri.pathSegments.getOrNull(1)?.toLongOrNull()
        val entity = downloadId?.let { id ->
            resolveDownloadById(id)?.takeIf {
                !it.quarantined && it.status == DownloadStatus.COMPLETED.name
            }
        }
        val file = entity?.let { runCatching { File(it.localPath).canonicalFile }.getOrNull() }
            ?.takeIf { it.path.startsWith(rootPrefix) && it.exists() && it.isFile }
        if (file != null) {
            return runCatching {
                WebResourceResponse(resolvePreviewMime(file.name), "UTF-8", FileInputStream(file))
            }.getOrNull()
        }
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    val loaderResponse = assetLoader.shouldInterceptRequest(uri)
    if (loaderResponse != null) {
        if (loaderResponse.mimeType.isNullOrBlank() &&
            (rawPath.endsWith(".html", true) || rawPath.endsWith(".htm", true))
        ) {
            loaderResponse.mimeType = "text/html"
        }
        return loaderResponse
    }
    return null
}

private fun resolvePreviewMime(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "xhtml" -> "application/xhtml+xml"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> WeblioDownloadManager.guessMime(fileName)
    }
}
