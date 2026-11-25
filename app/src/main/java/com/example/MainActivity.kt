package com.example

import android.app.PictureInPictureParams
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.db.DownloadStatus
import com.example.engine.ExtensionEngine
import com.example.engine.WeblioDnsVpnService
import com.example.security.SecurityState
import com.example.ui.BrowserViewModel
import com.example.ui.components.ChromeStoreLogoMark
import com.example.ui.components.DownloadConfirmDialog
import com.example.ui.components.EdgeAddonsLogoMark
import com.example.ui.components.ExtensionPopupDialog
import com.example.ui.components.FindInPageBar
import com.example.ui.components.PinUnlockDialog
import com.example.ui.components.SaveCredentialDialog
import com.example.ui.components.SslErrorAlertDialog
import com.example.ui.components.WeblioAddressBar
import com.example.ui.components.WeblioBottomNavBar
import com.example.ui.navigation.WeblioRoute
import com.example.ui.screens.AdBlockScreen
import com.example.ui.screens.BackupRestoreScreen
import com.example.ui.screens.BookmarksScreen
import com.example.ui.screens.BrowserToolsBottomSheet
import com.example.ui.screens.BrowserWebViewContainer
import com.example.ui.screens.DangerousWebsiteWarningScreen
import com.example.ui.screens.DownloadSecurityDecisionDialog
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.ExtensionSecurityReviewDialog
import com.example.ui.screens.ExtensionsQuickPopup
import com.example.ui.screens.ExtensionsScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MainMenuBottomSheet
import com.example.ui.screens.PasswordManagerScreen
import com.example.ui.screens.ProfilesScreen
import com.example.ui.screens.QrScannerScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SecurityCenterScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SitePermissionsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TabsScreen
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioTheme
import java.util.Locale

class MainActivity : FragmentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            val isAppLocked by viewModel.isAppLocked.collectAsState()
            var lockErrorMessage by remember { mutableStateOf<String?>(null) }

            WeblioTheme(themeMode = settings.themeMode) {
                if (isAppLocked) {
                    AppLockGateScreen(
                        errorMessage = lockErrorMessage,
                        onUnlockRequested = {
                            authenticateWithBiometrics(
                                title = "Unlock Weblo Browser",
                                subtitle = "Verify your identity to access your browser session",
                                onSuccess = {
                                    lockErrorMessage = null
                                    viewModel.unlockApp()
                                },
                                onFailure = {
                                    lockErrorMessage = it
                                    viewModel.showMessage(it)
                                }
                            )
                        }
                    )
                } else {
                    WeblioAppRoot(
                        viewModel = viewModel,
                        onRequestBiometric = { title, subtitle, onSuccess ->
                            authenticateWithBiometrics(
                                title = title,
                                subtitle = subtitle,
                                onSuccess = onSuccess,
                                onFailure = { viewModel.showMessage(it) }
                            )
                        },
                        onEnterPip = { enterBrowserPipMode() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppResumed()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.onAppBackgrounded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataUrl = intent.dataString
            if (!dataUrl.isNullOrBlank() && (dataUrl.startsWith("http://") || dataUrl.startsWith("https://"))) {
                viewModel.openNewTab(url = dataUrl)
            }
        }
    }

    private fun enterBrowserPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(false)
            }
            runCatching { enterPictureInPictureMode(builder.build()) }
        } else {
            viewModel.showMessage("Picture-in-Picture requires Android 8.0 or higher")
        }
    }

    private fun authenticateWithBiometrics(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val bm = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (bm.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onFailure("Device screen lock or biometrics must be enrolled in Android Settings to unlock.")
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun AppLockGateScreen(
    errorMessage: String? = null,
    onUnlockRequested: () -> Unit
) {
    LaunchedEffect(Unit) { onUnlockRequested() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = WeblioCyan, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(14.dp))
                Text("Weblo Locked", style = MaterialTheme.typography.headlineMedium)
                if (!errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onUnlockRequested) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Unlock with Biometrics")
                }
            }
        }
    }
}

@Composable
private fun WeblioAppRoot(
    viewModel: BrowserViewModel,
    onRequestBiometric: (String, String, () -> Unit) -> Unit,
    onEnterPip: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by viewModel.settings.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val quickLinks by viewModel.quickLinks.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val sitePermissions by viewModel.sitePermissions.collectAsState()

    val sslPrompt by viewModel.sslPrompt.collectAsState()
    val credPrompt by viewModel.credentialPrompt.collectAsState()
    val downloadPrompt by viewModel.downloadPrompt.collectAsState()
    val pendingProfileUnlock by viewModel.pendingProfileUnlock.collectAsState()
    val activeExtensionPopup by viewModel.activeExtensionPopup.collectAsState()
    val snackbarMsg by viewModel.snackbarMessage.collectAsState()
    val vaultUnlocked by viewModel.isPasswordVaultUnlocked.collectAsState()

    val quarantineSecurityFlags by viewModel.quarantineSecurityFlags.collectAsState()
    val pendingQuarantineReviews by viewModel.pendingQuarantineReviews.collectAsState()
    val pendingWebsiteWarning by viewModel.pendingWebsiteWarning.collectAsState()
    val appIntegrityReport by viewModel.appIntegrityReport.collectAsState()
    val threatDbCount by viewModel.threatDbCount.collectAsState()
    val isUpdatingThreatDb by viewModel.isUpdatingThreatDb.collectAsState()
    val isInstallingExtension by viewModel.isInstallingExtension.collectAsState()
    val pendingExtensionReview by viewModel.pendingExtensionReview.collectAsState()
    val overallSecurityState = remember(
        appIntegrityReport,
        settings,
        quarantineSecurityFlags,
        extensions,
        pendingWebsiteWarning
    ) {
        viewModel.computeOverallSecurityState()
    }

    var showMainMenu by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var showExtensionsPopup by remember { mutableStateOf(false) }
    var showCwsCompatibilityDialog by remember { mutableStateOf(false) }
    var hasShownCwsTip by remember { mutableStateOf(false) }
    var fullscreenVideoView by remember { mutableStateOf<View?>(null) }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            viewModel.openUrlInActiveTab(spoken)
        }
    }

    fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search with Weblo")
        }
        runCatching { voiceLauncher.launch(intent) }
            .onFailure { viewModel.showMessage("Voice recognition service unavailable on this device") }
    }

    LaunchedEffect(snackbarMsg) {
        val msg = snackbarMsg
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val isStartupReady by viewModel.isStartupReady.collectAsState()
    var splashDismissed by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = WeblioRoute.Browser
        ) {
            composable<WeblioRoute.Splash> {
                SplashScreen(
                    isReady = isStartupReady,
                    onFinished = {
                        navController.navigate(WeblioRoute.Browser) {
                            popUpTo(WeblioRoute.Splash) { inclusive = true }
                        }
                    }
                )
            }

        composable<WeblioRoute.Browser> {
            val currentTab = activeTab
            val tabRuntimeMap by viewModel.tabRuntime.collectAsState()
            val runtime = currentTab?.let { tabRuntimeMap[it.id] }
            val currentUrl = runtime?.currentUrl ?: currentTab?.url ?: "weblio://home"
            val isHome = currentUrl == "weblio://home"
            val host = BrowserViewModel.extractHost(currentUrl)
            val siteAdBlock = sitePermissions.firstOrNull { it.originHost == host }?.adBlockEnabled ?: true
            val browserBookmarks by viewModel.bookmarks.collectAsState()
            val isBookmarked = remember(browserBookmarks, currentUrl) {
                browserBookmarks.any { it.url == currentUrl }
            }

            BackHandler(enabled = !isHome) {
                viewModel.webViewGoBack()
            }

            if (fullscreenVideoView != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                val v = fullscreenVideoView
                                (v?.parent as? ViewGroup)?.removeView(v)
                                if (v != null) addView(v)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    topBar = {
                        if (!isHome && currentTab != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .statusBarsPadding()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val effectiveDesktopMode = currentTab.desktopMode ||
                                        runtime?.isDesktopMode == true ||
                                        ExtensionEngine.isExtensionStoreUrl(currentUrl)
                                    WeblioAddressBar(
                                        urlText = currentUrl,
                                        isHome = false,
                                        isLoading = runtime?.isLoading ?: false,
                                        progress = runtime?.progress ?: 0,
                                        isIncognito = currentTab.isIncognito,
                                        isSecureHttps = runtime?.isSecureHttps ?: currentUrl.startsWith("https://"),
                                        isDesktopMode = effectiveDesktopMode,
                                        blockedCount = runtime?.blockedOnPage ?: 0,
                                        searchEngineName = settings.defaultSearchEngine.displayName,
                                        securityState = overallSecurityState,
                                        onSubmitUrl = { viewModel.openUrlInActiveTab(it) },
                                        onRefreshOrStop = { viewModel.webViewReloadOrStop() },
                                        onQrClick = { navController.navigate(WeblioRoute.QrScanner) },
                                        onVoiceClick = { launchVoiceSearch() },
                                        onExtensionsClick = { showExtensionsPopup = true },
                                        onShieldClick = { navController.navigate(WeblioRoute.SecurityCenter) { launchSingleTop = true } },
                                        onOpenSearchPage = { query ->
                                            navController.navigate(WeblioRoute.Search(initialQuery = query)) {
                                                launchSingleTop = true
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            viewModel.captureActiveTabPreview()
                                            viewModel.openNewTab(
                                                url = "weblio://home",
                                                isIncognito = currentTab.isIncognito,
                                                groupName = currentTab.groupName
                                            )
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("browser_new_tab_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = stringResource(id = R.string.cd_new_tab),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                if (runtime?.findInPageOpen == true) {
                                    Spacer(Modifier.height(6.dp))
                                    FindInPageBar(
                                        query = runtime.findQuery,
                                        activeMatch = runtime.findActiveMatch,
                                        totalMatches = runtime.findTotalMatches,
                                        onQueryChange = { viewModel.updateFindQuery(it) },
                                        onNext = { viewModel.findNext(it) },
                                        onClose = { viewModel.toggleFindInPage(false) }
                                    )
                                }
                            }
                        }
                    },
                    bottomBar = {
                        val displayedTabCount = BrowserViewModel.computeDisplayedTabCount(tabs, currentTab)
                        WeblioBottomNavBar(
                            tabCount = displayedTabCount,
                            canGoBack = (runtime?.canGoBack == true) || !isHome,
                            canGoForward = runtime?.canGoForward == true,
                            isHome = isHome,
                            compactMode = settings.compactBottomToolbar,
                            onBackClick = { viewModel.webViewGoBack() },
                            onForwardClick = { viewModel.webViewGoForward() },
                            onHomeClick = { viewModel.goHomeInActiveTab() },
                            onTabsClick = {
                                viewModel.captureActiveTabPreview()
                                navController.navigate(WeblioRoute.Tabs) { launchSingleTop = true }
                            },
                            onToolsClick = { showToolsSheet = true },
                            onMenuClick = { showMainMenu = true },
                            onNewTabClick = {
                                viewModel.captureActiveTabPreview()
                                viewModel.openNewTab(
                                    url = "weblio://home",
                                    isIncognito = currentTab?.isIncognito == true,
                                    groupName = currentTab?.groupName
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .clipToBounds()
                    ) {
                        if (isHome || currentTab == null) {
                            val homeSessionBlockedCount by viewModel.adBlock.sessionBlockedCount.collectAsState()
                            HomeScreen(
                                settings = settings,
                                activeProfile = activeProfile,
                                quickLinks = quickLinks,
                                isIncognito = currentTab?.isIncognito == true,
                                sessionBlockedCount = homeSessionBlockedCount,
                                onNavigateUrl = { viewModel.openUrlInActiveTab(it) },
                                onSelectSearchEngine = { viewModel.setSearchEngine(it) },
                                onQrClick = { navController.navigate(WeblioRoute.QrScanner) { launchSingleTop = true } },
                                onVoiceClick = { launchVoiceSearch() },
                                onExtensionsClick = { showExtensionsPopup = true },
                                onBookmarksClick = { navController.navigate(WeblioRoute.Bookmarks) { launchSingleTop = true } },
                                onHistoryClick = { navController.navigate(WeblioRoute.History) { launchSingleTop = true } },
                                onAdBlockClick = { navController.navigate(WeblioRoute.AdBlock) { launchSingleTop = true } },
                                onProfileClick = { navController.navigate(WeblioRoute.Profiles) { launchSingleTop = true } },
                                onAddQuickLink = { t, u -> viewModel.addQuickLink(t, u) },
                                onDeleteQuickLink = { viewModel.deleteQuickLink(it) },
                                onSearchBarClick = {
                                    navController.navigate(WeblioRoute.Search()) { launchSingleTop = true }
                                }
                            )
                        } else {
                            val storeExtRef = remember(currentUrl) {
                                ExtensionEngine.extractStoreExtensionDownload(currentUrl)
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (storeExtRef != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("store_install_extension_banner")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (storeExtRef.storeName.contains("Edge", ignoreCase = true)) {
                                                EdgeAddonsLogoMark(size = 24.dp)
                                            } else {
                                                ChromeStoreLogoMark(size = 24.dp)
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = runtime?.title?.takeIf { it.isNotBlank() } ?: storeExtRef.storeName,
                                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${storeExtRef.storeName} • Desktop Mode",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Button(
                                                enabled = !isInstallingExtension,
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                onClick = {
                                                    viewModel.installExtensionFromStorePage(
                                                        pageUrl = currentUrl,
                                                        pageTitle = runtime?.title ?: currentTab.title
                                                    )
                                                },
                                                modifier = Modifier.testTag("store_install_extension_button")
                                            ) {
                                                if (isInstallingExtension) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Filled.Download,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = if (isInstallingExtension) "Installing..." else "Add to Weblo",
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                                val effectiveTab = if ((runtime?.isDesktopMode == true || ExtensionEngine.isExtensionStoreUrl(currentUrl)) && !currentTab.desktopMode) {
                                    currentTab.copy(desktopMode = true)
                                } else {
                                    currentTab
                                }
                                BrowserWebViewContainer(
                                    tab = effectiveTab,
                                    activeProfile = activeProfile,
                                    settings = settings,
                                    extensions = extensions,
                                    sitePermissions = sitePermissions,
                                    viewModel = viewModel,
                                    onChromeWebStoreDetected = {
                                        if (!hasShownCwsTip) {
                                            hasShownCwsTip = true
                                            viewModel.showMessage("Desktop view enabled for extension store. Open any extension and tap 'Add to Weblo'.")
                                        }
                                    },
                                    onFullscreenVideoChanged = { fullscreenVideoView = it },
                                    webViewInstanceVersion = runtime?.webViewInstanceVersion ?: 0,
                                    errorBanner = runtime?.errorBanner,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            if (showMainMenu) {
                MainMenuBottomSheet(
                    activeProfile = activeProfile,
                    settings = settings,
                    isDesktopMode = currentTab?.desktopMode == true,
                    isIncognitoTab = currentTab?.isIncognito == true,
                    onDismiss = { showMainMenu = false },
                    onOpenProfiles = { navController.navigate(WeblioRoute.Profiles) },
                    onOpenBookmarks = { navController.navigate(WeblioRoute.Bookmarks) },
                    onOpenHistory = { navController.navigate(WeblioRoute.History) },
                    onOpenDownloads = { navController.navigate(WeblioRoute.Downloads) },
                    onOpenSettings = { navController.navigate(WeblioRoute.Settings) },
                    onOpenExtensions = { navController.navigate(WeblioRoute.Extensions) },
                    onOpenPasswords = { navController.navigate(WeblioRoute.Passwords) { launchSingleTop = true } },
                    onOpenTabs = {
                        viewModel.captureActiveTabPreview()
                        navController.navigate(WeblioRoute.Tabs) { launchSingleTop = true }
                    },
                    onOpenAdBlock = { navController.navigate(WeblioRoute.AdBlock) { launchSingleTop = true } },
                    onToggleDesktopMode = { viewModel.toggleDesktopModeForActiveTab() },
                    onToggleIncognito = { viewModel.toggleIncognitoNewTab() },
                    onToggleDarkMode = {
                        val next = if (settings.themeMode == AppThemeMode.DARK) AppThemeMode.LIGHT else AppThemeMode.DARK
                        viewModel.setThemeMode(next)
                    },
                    onNewTab = { viewModel.openNewTab("weblio://home", false, null) },
                    onSetDefaultBrowser = {
                        runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val rm = context.getSystemService(RoleManager::class.java)
                                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                                    context.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                                    return@runCatching
                                }
                            }
                            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        }.onFailure {
                            viewModel.showMessage("Open system Default Apps settings to set Weblo as default browser")
                        }
                    },
                    onOpenSecurityCenter = {
                        navController.navigate(WeblioRoute.SecurityCenter) { launchSingleTop = true }
                    },
                    extensions = extensions,
                    onUseExtensions = { showExtensionsPopup = true }
                )
            }

            if (showExtensionsPopup) {
                ExtensionsQuickPopup(
                    extensions = extensions,
                    onDismiss = { showExtensionsPopup = false },
                    onOpenStoreUrl = { url -> viewModel.openExtensionStoreUrl(url) },
                    onOpenPopup = { ext -> viewModel.openExtensionPopup(ext) },
                    onToggleExtension = { ext -> viewModel.toggleExtension(ext) },
                    onManageExtensions = { navController.navigate(WeblioRoute.Extensions) },
                    onRemoveExtension = { ext -> viewModel.removeExtension(ext) }
                )
            }

            if (showToolsSheet) {
                BrowserToolsBottomSheet(
                    currentUrl = currentUrl,
                    isBookmarked = isBookmarked,
                    siteAdBlockEnabled = siteAdBlock,
                    onDismiss = { showToolsSheet = false },
                    onBookmarkToggle = { viewModel.toggleCurrentPageBookmark() },
                    onFindInPage = { viewModel.toggleFindInPage(true) },
                    onShareLink = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, currentUrl)
                        }
                        context.startActivity(Intent.createChooser(share, "Share link via"))
                    },
                    onCopyLink = {
                        viewModel.copyToClipboard(currentUrl, isSensitive = false, label = "Weblo Link")
                        viewModel.showMessage(if (settings.clipboardGuardEnabled) "Link copied (Clipboard Guard active)" else "Link copied")
                    },
                    onToggleSiteAdBlock = { viewModel.toggleSiteAdBlock(host) },
                    onEnterPip = onEnterPip,
                    onOpenSitePermissions = { navController.navigate(WeblioRoute.SitePermissions) },
                    onOpenAdBlockDashboard = { navController.navigate(WeblioRoute.AdBlock) }
                )
            }
        }

        composable<WeblioRoute.Tabs> {
            LaunchedEffect(Unit) { viewModel.ensureTabPreviewsLoaded() }
            val closedTabs by viewModel.closedTabs.collectAsState()
            val tabPreviews by viewModel.tabPreviews.collectAsState()
            val tabRuntimeMap by viewModel.tabRuntime.collectAsState()
            TabsScreen(
                tabs = tabs,
                closedTabs = closedTabs,
                activeTabId = activeTab?.id,
                onSelectTab = { viewModel.selectTab(it) },
                onNewTab = { incognito, group -> viewModel.openNewTab("weblio://home", incognito, group) },
                onCloseTab = { viewModel.closeTab(it) },
                onCloseAllTabs = { viewModel.closeAllTabs(it) },
                onDuplicateTab = { viewModel.duplicateTab(it) },
                onAssignGroup = { tab, g -> viewModel.assignTabGroup(tab, g) },
                onReopenClosed = { viewModel.reopenClosedTab(it) },
                onBack = { navController.popBackStack() },
                tabPreviews = tabPreviews,
                tabRuntimeMap = tabRuntimeMap
            )
        }

        composable<WeblioRoute.Bookmarks> {
            val bookmarks by viewModel.bookmarks.collectAsState()
            BookmarksScreen(
                bookmarks = bookmarks,
                onOpenUrl = { viewModel.openUrlInActiveTab(it) },
                onAddBookmark = { t, u, f -> viewModel.addBookmark(t, u, f) },
                onUpdateBookmark = { viewModel.updateBookmark(it) },
                onDeleteBookmark = { viewModel.deleteBookmark(it) },
                onExportHtml = { viewModel.backupManager.exportBookmarksHtml(activeProfile?.id ?: 1L) },
                onImportHtml = { html -> viewModel.backupManager.importBookmarksHtml(activeProfile?.id ?: 1L, html) },
                onShowMessage = { viewModel.showMessage(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.History> {
            val history by viewModel.history.collectAsState()
            HistoryScreen(
                history = history,
                onOpenUrl = { viewModel.openUrlInActiveTab(it) },
                onDeleteEntry = { viewModel.deleteHistoryEntry(it) },
                onClearRange = { viewModel.clearHistoryRange(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.Downloads> {
            val downloads by viewModel.downloads.collectAsState()
            DownloadsScreen(
                downloads = downloads,
                downloadLocationLabel = settings.downloadLocationLabel,
                onEnqueueUrl = { url, name -> viewModel.confirmDownload(url, name ?: "download", "application/octet-stream", null) },
                onPause = { viewModel.downloadManager.pauseDownload(it) },
                onResumeOrRetry = { viewModel.downloadManager.resumeOrRetry(it, settings.downloadNotificationsEnabled) },
                onCancel = { viewModel.downloadManager.cancelDownload(it) },
                onDelete = { viewModel.viewModelScopeDeleteDownload(it) },
                onOpenFile = { viewModel.downloadManager.openDownloadedFile(it) },
                onClearAll = { viewModel.viewModelScopeClearDownloads() },
                onShowMessage = { viewModel.showMessage(it) },
                onBack = { navController.popBackStack() },
                askBeforeDownload = settings.askBeforeDownload,
                downloadNotificationsEnabled = settings.downloadNotificationsEnabled,
                onToggleAskBeforeDownload = { viewModel.setAskBeforeDownload(it) },
                onToggleDownloadNotifications = { viewModel.setDownloadNotifications(it) },
                onShareFile = { item -> viewModel.downloadManager.shareDownloadedFile(item) },
                onRenameFile = { item, newName -> viewModel.renameDownload(item, newName) },
                onReviewQuarantined = { item -> viewModel.requestDownloadReview(item) },
                onInspectApk = { item -> viewModel.downloadManager.inspectApkForInstall(item) },
                onOpenHtmlPreview = { item ->
                    viewModel.openHtmlDownloadInNewTab(item)
                    navController.popBackStack(WeblioRoute.Browser, false)
                }
            )
        }

        composable<WeblioRoute.Extensions> {
            val catalog = remember(activeProfile?.id) {
                viewModel.repo.builtInExtensionCatalog(activeProfile?.id ?: 1L)
            }
            ExtensionsScreen(
                installedExtensions = extensions,
                builtInCatalog = catalog,
                onToggleExtension = { viewModel.toggleExtension(it) },
                onRemoveExtension = { viewModel.removeExtension(it) },
                onOpenPopup = { viewModel.openExtensionPopup(it) },
                onInstallFromUri = { uri, name -> viewModel.installExtensionFromUri(uri, name) },
                onInstallBuiltIn = { viewModel.installBuiltInExtension(it) },
                onCreateUserScript = { name, body ->
                    viewModel.viewModelScopeInstallUserScript(name, body)
                },
                onBack = { navController.popBackStack() },
                onOpenStoreUrl = { url ->
                    viewModel.openExtensionStoreUrl(url)
                    navController.popBackStack(WeblioRoute.Browser, false)
                }
            )
        }

        composable<WeblioRoute.AdBlock> {
            val adBlockRules by viewModel.adBlockRules.collectAsState()
            val subscriptions by viewModel.filterSubscriptions.collectAsState()
            val sessionBlockedCount by viewModel.adBlock.sessionBlockedCount.collectAsState()
            val recentBlocked by viewModel.adBlock.recentBlocked.collectAsState()
            val vpnRunning by WeblioDnsVpnService.isRunning.collectAsState()
            val dnsBlockedCount by WeblioDnsVpnService.dnsBlockedCount.collectAsState()
            AdBlockScreen(
                settings = settings,
                sessionBlockedCount = sessionBlockedCount,
                recentBlocked = recentBlocked,
                rules = adBlockRules,
                subscriptions = subscriptions,
                vpnRunning = vpnRunning,
                dnsBlockedCount = dnsBlockedCount,
                onToggleGlobal = { viewModel.setAdBlockerEnabled(it) },
                onToggleTrackerProtection = { viewModel.setTrackerProtection(it) },
                onTogglePopupBlocking = { viewModel.setPopupBlocking(it) },
                onToggleThirdParty = { viewModel.setThirdPartyBlocking(it) },
                onSelectDnsMode = { viewModel.setDnsFilterMode(it) },
                onUpdateCustomDoh = { viewModel.setCustomDohUrl(it) },
                onAddRule = { p, t -> viewModel.addCustomFilterRule(p, t) },
                onToggleRule = { viewModel.toggleAdBlockRule(it) },
                onDeleteRule = { viewModel.deleteAdBlockRule(it) },
                onUpdateFiltersNow = { viewModel.triggerFilterListUpdate() },
                onVpnToggleState = { viewModel.setVpnDnsEnabled(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.Profiles> {
            ProfilesScreen(
                profiles = profiles,
                activeProfileId = activeProfile?.id ?: 1L,
                onSwitchProfile = { viewModel.requestSwitchProfile(it) },
                onCreateProfile = { name, icon -> viewModel.createProfile(name, icon) },
                onRenameProfile = { p, name -> viewModel.renameProfile(p, name) },
                onUpdateIcon = { p, icon -> viewModel.updateProfileIcon(p, icon) },
                onSetPin = { p, pin -> viewModel.setProfilePin(p, pin) },
                onSetBiometric = { p, en -> viewModel.setProfileBiometric(p, en) },
                onDeleteProfile = { viewModel.deleteProfile(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.Passwords> {
            val passwords by viewModel.passwords.collectAsState()
            DisposableEffect(Unit) {
                onDispose { viewModel.setPasswordVaultUnlocked(false) }
            }
            PasswordManagerScreen(
                passwords = passwords,
                isUnlocked = vaultUnlocked,
                onRequestBiometricUnlock = {
                    onRequestBiometric(
                        "Unlock Password Vault",
                        "Authenticate to view encrypted credentials"
                    ) {
                        viewModel.setPasswordVaultUnlocked(true)
                    }
                },
                onDecryptPassword = { viewModel.decryptPassword(it) },
                onSavePassword = { h, u, p -> viewModel.savePasswordManual(h, u, p) },
                onDeletePassword = { viewModel.deletePassword(it) },
                onShowMessage = { viewModel.showMessage(it) },
                onBack = { navController.popBackStack() },
                onCopyPassword = { secret ->
                    viewModel.copyToClipboard(secret, isSensitive = true, label = "Weblo Password")
                }
            )
        }

        composable<WeblioRoute.Settings> {
            SettingsScreen(
                settings = settings,
                viewModel = viewModel,
                onOpenExtensions = { navController.navigate(WeblioRoute.Extensions) },
                onOpenAdBlock = { navController.navigate(WeblioRoute.AdBlock) },
                onOpenPasswords = { navController.navigate(WeblioRoute.Passwords) },
                onOpenSitePermissions = { navController.navigate(WeblioRoute.SitePermissions) },
                onOpenBackupRestore = { navController.navigate(WeblioRoute.BackupRestore) },
                onBack = { navController.popBackStack() },
                onOpenSecurityCenter = { navController.navigate(WeblioRoute.SecurityCenter) { launchSingleTop = true } }
            )
        }

        composable<WeblioRoute.SecurityCenter> {
            val secDownloads by viewModel.downloads.collectAsState()
            val securityEvents by viewModel.securityEvents.collectAsState()
            val quarantinedList = remember(secDownloads) {
                secDownloads.filter { it.status == DownloadStatus.QUARANTINED.name || it.quarantined }
            }
            SecurityCenterScreen(
                overallState = overallSecurityState,
                settings = settings,
                integrityReport = appIntegrityReport,
                threatDbCount = threatDbCount,
                isUpdatingThreatDb = isUpdatingThreatDb,
                quarantinedDownloads = quarantinedList,
                extensions = extensions,
                securityEvents = securityEvents,
                onReviewQuarantined = { dl -> viewModel.requestDownloadReview(dl) },
                onRunIntegrityCheck = { viewModel.runIntegrityCheck() },
                onUpdateThreatDb = { viewModel.updateThreatDatabaseNow() },
                onVerifyUpdatePackage = { apkFile, metaJson -> viewModel.verifyUpdatePackageFile(apkFile, metaJson) },
                onToggleCloudHashLookup = { viewModel.setCloudHashReputationEnabled(it) },
                onOpenExtensions = { navController.navigate(WeblioRoute.Extensions) { launchSingleTop = true } },
                onOpenDownloads = { navController.navigate(WeblioRoute.Downloads) { launchSingleTop = true } },
                onClearHistory = { viewModel.clearSecurityHistory() },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.BackupRestore> {
            BackupRestoreScreen(
                profileId = activeProfile?.id ?: 1L,
                backupManager = viewModel.backupManager,
                onShowMessage = { viewModel.showMessage(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.SitePermissions> {
            SitePermissionsScreen(
                permissions = sitePermissions,
                onUpdatePermission = { viewModel.updateSitePermission(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.QrScanner> {
            QrScannerScreen(
                onQrDecoded = { text ->
                    viewModel.openUrlInActiveTab(text)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<WeblioRoute.Search> { backStackEntry ->
            val route = backStackEntry.toRoute<WeblioRoute.Search>()
            val suggestions by viewModel.searchSuggestions.collectAsState()
            val isLoadingSuggestions by viewModel.isLoadingSuggestions.collectAsState()
            val searchHistory by viewModel.history.collectAsState()
            val searchBookmarks by viewModel.bookmarks.collectAsState()
            SearchScreen(
                initialQuery = route.initialQuery,
                searchEngine = settings.defaultSearchEngine,
                isIncognito = activeTab?.isIncognito == true,
                suggestions = suggestions,
                isLoadingSuggestions = isLoadingSuggestions,
                history = searchHistory,
                bookmarks = searchBookmarks,
                onQueryChange = { q -> viewModel.updateSearchQuery(q, settings.defaultSearchEngine) },
                onSelectSearchEngine = { engine -> viewModel.setSearchEngine(engine) },
                onSubmitQuery = { query ->
                    viewModel.clearSearchSuggestions()
                    navController.popBackStack()
                    viewModel.openUrlInActiveTab(query)
                },
                onQrClick = {
                    navController.navigate(WeblioRoute.QrScanner) { launchSingleTop = true }
                },
                onVoiceClick = {
                    navController.popBackStack()
                    launchVoiceSearch()
                },
                onClearSuggestions = { viewModel.clearSearchSuggestions() },
                onBack = { navController.popBackStack() }
            )
        }
    }

    // Global Overlays & Security Prompts
    sslPrompt?.let { prompt ->
        SslErrorAlertDialog(
            prompt = prompt,
            onBackToSafety = { viewModel.dismissSslErrorPrompt() }
        )
    }

    credPrompt?.let { prompt ->
        SaveCredentialDialog(
            prompt = prompt,
            onSave = { viewModel.acceptSaveCredential() },
            onDismiss = { viewModel.dismissCredentialPrompt() }
        )
    }

    downloadPrompt?.let { prompt ->
        DownloadConfirmDialog(
            prompt = prompt,
            onConfirm = { viewModel.confirmDownload(prompt.url, prompt.fileName, prompt.mimeType, prompt.userAgent) },
            onDismiss = { viewModel.dismissDownloadPrompt() }
        )
    }

    pendingProfileUnlock?.let { prof ->
        PinUnlockDialog(
            profile = prof,
            onVerifyPin = { pin -> viewModel.verifyProfilePinAndSwitch(prof, pin) },
            onBiometricClick = if (prof.biometricEnabled) {
                {
                    onRequestBiometric("Unlock ${prof.name}", "Verify to switch browser profile") {
                        viewModel.commitSwitchProfile(prof)
                    }
                }
            } else null,
            onDismiss = { viewModel.cancelProfileUnlock() }
        )
    }

    activeExtensionPopup?.let { ext ->
        val popupTab = activeTab
        val popupRuntimeMap by viewModel.tabRuntime.collectAsState()
        val popupRuntime = popupTab?.let { popupRuntimeMap[it.id] }
        val popupUrl = popupRuntime?.currentUrl ?: popupTab?.url ?: "weblio://home"
        val popupTitle = popupRuntime?.title ?: popupTab?.title ?: "New Tab"
        ExtensionPopupDialog(
            extension = ext,
            onDismiss = { viewModel.openExtensionPopup(null) },
            extensionEngine = viewModel.extensionEngine,
            activeTabUrl = popupUrl,
            activeTabTitle = popupTitle,
            onToggleExtension = { viewModel.toggleExtension(ext) },
            onRunOnActiveTab = { viewModel.runExtensionOnActiveTab(ext) },
            onExecuteScript = { code -> viewModel.executeExtensionScriptOnActiveTab(ext, code) },
            onInsertCss = { css -> viewModel.insertExtensionCssOnActiveTab(ext, css) },
            onOpenTab = { url -> viewModel.openNewTab(url = url, isIncognito = popupTab?.isIncognito == true) },
            onReloadTab = { viewModel.reloadActiveTabForExtension() },
            onSendTabMessage = { msgJson -> viewModel.sendExtensionMessageToActiveTab(ext, msgJson) }
        )
    }

    pendingWebsiteWarning?.let { warning ->
        DangerousWebsiteWarningScreen(
            report = warning.report,
            onGoBack = { viewModel.goBackFromWebsiteWarning() },
            onContinueAnyway = { viewModel.continueAnywayFromWebsiteWarning() }
        )
    }

    pendingQuarantineReviews.firstOrNull()?.let { review ->
        DownloadSecurityDecisionDialog(
            review = review,
            onContinueAnyway = { dlId -> viewModel.resolveQuarantinedDownload(dlId, allowContinue = true) },
            onCancelDownload = { dlId -> viewModel.resolveQuarantinedDownload(dlId, allowContinue = false) },
            onDismiss = { viewModel.dismissDownloadReview(review.download.id) }
        )
    }

    pendingExtensionReview?.let { ext ->
        ExtensionSecurityReviewDialog(
            extension = ext,
            onEnable = { viewModel.approvePendingExtension() },
            onKeepDisabled = { viewModel.dismissPendingExtensionReview() },
            onDelete = { viewModel.removePendingExtension() }
        )
    }

    if (showCwsCompatibilityDialog) {
        AlertDialog(
            onDismissRequest = { showCwsCompatibilityDialog = false },
            title = { Text("Chrome Web Store Compatibility") },
            text = {
                Text(
                    "Android System WebView does not natively support 1-click Chrome Web Store .crx installation. To use extensions in Weblo, import compatible .zip/.crx bundles or .user.js scripts inside the Extensions Manager, or enable Weblo's verified Dark Reader, uBlock, Privacy Badger, and Tampermonkey engines."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showCwsCompatibilityDialog = false
                    navController.navigate(WeblioRoute.Extensions)
                }) { Text("Open Extensions") }
            },
            dismissButton = {
                TextButton(onClick = { showCwsCompatibilityDialog = false }) { Text("Dismiss") }
            }
        )
    }

        AnimatedVisibility(
            visible = !splashDismissed,
            exit = fadeOut(animationSpec = tween(260))
        ) {
            SplashScreen(
                isReady = isStartupReady,
                onFinished = { splashDismissed = true }
            )
        }
    }
}
