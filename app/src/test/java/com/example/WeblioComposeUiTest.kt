package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.data.db.DownloadEntity
import com.example.data.db.DownloadFileCategory
import com.example.data.db.DownloadStatus
import com.example.data.db.ExtensionEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.QuickLinkEntity
import com.example.data.db.TabEntity
import com.example.data.prefs.BrowserSettings
import com.example.data.prefs.SearchEngine
import com.example.ui.BrowserViewModel
import com.example.ui.TabRuntimeState
import com.example.ui.components.ExtensionPopupDialog
import com.example.ui.components.WeblioBottomNavBar
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.ExtensionSecurityReviewDialog
import com.example.ui.screens.ExtensionsQuickPopup
import com.example.ui.screens.ExtensionsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MainMenuBottomSheet
import com.example.ui.screens.TabsScreen
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.WeblioGrayBg
import com.example.ui.theme.WeblioLightBg
import com.example.ui.theme.WeblioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeblioComposeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreen_rendersBrandingSearchEnginesAndTriggersCallbacks() {
        var selectedEngine: SearchEngine? = null
        var qrClicked = false

        composeRule.setContent {
            WeblioTheme {
                HomeScreen(
                    settings = BrowserSettings(),
                    activeProfile = ProfileEntity(id = 1, name = "Personal", iconKey = "person"),
                    quickLinks = listOf(
                        QuickLinkEntity(id = 1, profileId = 1, title = "Wikipedia", url = "https://www.wikipedia.org", iconKey = "wiki")
                    ),
                    isIncognito = false,
                    sessionBlockedCount = 12L,
                    onNavigateUrl = {},
                    onSelectSearchEngine = { selectedEngine = it },
                    onQrClick = { qrClicked = true },
                    onVoiceClick = {},
                    onExtensionsClick = {},
                    onBookmarksClick = {},
                    onHistoryClick = {},
                    onAdBlockClick = {},
                    onProfileClick = {},
                    onAddQuickLink = { _, _ -> },
                    onDeleteQuickLink = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("WEBLIO").assertIsDisplayed()
        composeRule.onNodeWithText("EXPLORE. DISCOVER. EXPERIENCE.").assertIsDisplayed()
        composeRule.onNodeWithText("12 blocked").assertIsDisplayed()

        composeRule.onNodeWithTag("address_search_engine_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_engine_duckduckgo").performClick()
        composeRule.waitForIdle()
        assertEquals(SearchEngine.DUCKDUCKGO, selectedEngine)

        composeRule.onNodeWithTag("home_qr_button").performClick()
        composeRule.waitForIdle()
        assertTrue(qrClicked)
    }

    @Test
    fun bottomNavBar_triggersAllSixNavigationCallbacksOnWebPage() {
        var back = false
        var forward = false
        var home = false
        var tabs = false
        var tools = false
        var menu = false

        composeRule.setContent {
            WeblioTheme {
                WeblioBottomNavBar(
                    tabCount = 3,
                    canGoBack = true,
                    canGoForward = true,
                    isHome = false,
                    compactMode = false,
                    onBackClick = { back = true },
                    onForwardClick = { forward = true },
                    onHomeClick = { home = true },
                    onTabsClick = { tabs = true },
                    onToolsClick = { tools = true },
                    onMenuClick = { menu = true }
                )
            }
        }

        composeRule.onNodeWithTag("nav_back_button").performClick()
        composeRule.onNodeWithTag("nav_forward_button").performClick()
        composeRule.onNodeWithTag("nav_home_button").performClick()
        composeRule.onNodeWithTag("nav_tabs_button").performClick()
        composeRule.onNodeWithTag("nav_tools_button").performClick()
        composeRule.onNodeWithTag("nav_menu_button").performClick()
        composeRule.waitForIdle()

        assertTrue(back)
        assertTrue(forward)
        assertTrue(home)
        assertTrue(tabs)
        assertTrue(tools)
        assertTrue(menu)
    }

    @Test
    fun bottomNavBar_onHome_triggersNewTabPlusButton() {
        var newTabClicked = false

        composeRule.setContent {
            WeblioTheme {
                WeblioBottomNavBar(
                    tabCount = 1,
                    canGoBack = false,
                    canGoForward = false,
                    isHome = true,
                    compactMode = false,
                    onBackClick = {},
                    onForwardClick = {},
                    onHomeClick = {},
                    onTabsClick = {},
                    onToolsClick = {},
                    onMenuClick = {},
                    onNewTabClick = { newTabClicked = true }
                )
            }
        }

        composeRule.onNodeWithTag("nav_new_tab_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(newTabClicked)
    }

    @Test
    fun tabsScreen_displaysRealWebsiteTitleAndPreviewBitmap() {
        var selectedTab: Long? = null
        val previewBmp = Bitmap.createBitmap(120, 160, Bitmap.Config.RGB_565)
        val sampleTabs = listOf(
            TabEntity(id = 10, profileId = 1, url = "weblio://home", title = "Home"),
            TabEntity(id = 11, profileId = 1, url = "https://github.com", title = "New Tab")
        )
        val runtimeMap = mapOf(
            11L to TabRuntimeState(tabId = 11L, currentUrl = "https://github.com", title = "GitHub: Build software")
        )

        composeRule.setContent {
            WeblioTheme {
                TabsScreen(
                    tabs = sampleTabs,
                    closedTabs = emptyList(),
                    activeTabId = 10L,
                    onSelectTab = { selectedTab = it },
                    onNewTab = { _, _ -> },
                    onCloseTab = {},
                    onCloseAllTabs = {},
                    onDuplicateTab = {},
                    onAssignGroup = { _, _ -> },
                    onReopenClosed = {},
                    onBack = {},
                    tabPreviews = mapOf(11L to previewBmp),
                    tabRuntimeMap = runtimeMap
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search your tabs").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub: Build software").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_card_11").performClick()
        composeRule.waitForIdle()
        assertEquals(11L, selectedTab)
    }

    @Test
    fun extensionsScreen_searchesChromeCatalogAndBuildsStoreSearchUrl() {
        var openedStoreUrl: String? = null
        val catalog = listOf(
            ExtensionEntity(
                profileId = 1,
                extId = "weblio.darkreader.engine",
                name = "Dark Reader Engine",
                version = "4.9",
                description = "Dynamic dark mode CSS",
                sourceType = "BUILTIN_ENGINE"
            ),
            ExtensionEntity(
                profileId = 1,
                extId = "weblio.cws.translator",
                name = "Google Translate Floating Bar",
                version = "2.0",
                description = "Translate pages instantly",
                sourceType = "CHROME_WEBEXT"
            )
        )

        composeRule.setContent {
            WeblioTheme(themeMode = AppThemeMode.LIGHT) {
                ExtensionsScreen(
                    installedExtensions = emptyList(),
                    builtInCatalog = catalog,
                    onToggleExtension = {},
                    onRemoveExtension = {},
                    onOpenPopup = {},
                    onInstallFromUri = { _, _ -> },
                    onInstallBuiltIn = {},
                    onCreateUserScript = { _, _ -> },
                    onBack = {},
                    onOpenStoreUrl = { openedStoreUrl = it }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ext_search_input").performTextInput("Translate")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Google Translate Floating Bar").assertIsDisplayed()
        composeRule.onNodeWithTag("ext_open_cws_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("https://chromewebstore.google.com/search/Translate", openedStoreUrl)
        assertEquals(0xFF252525.toInt(), WeblioGrayBg.value.let { (it shr 32).toInt() })
        assertEquals(0xFFFFFFFF.toInt(), WeblioLightBg.value.let { (it shr 32).toInt() })
        assertEquals("White", AppThemeMode.LIGHT.label)
    }

    @Test
    fun downloadsScreen_rendersFiltersDateGroupsAndOverflowMenu() {
        var deletedId: Long? = null
        val now = System.currentTimeMillis()
        val older = now - 3L * 24 * 60 * 60 * 1000
        val sampleDownloads = listOf(
            DownloadEntity(
                id = 1L,
                profileId = 1L,
                url = "https://api.manus.im/iptrace.zip",
                fileName = "iptrace (1).zip",
                mimeType = "application/zip",
                category = DownloadFileCategory.ARCHIVE.name,
                localPath = "/tmp/iptrace.zip",
                totalBytes = 286 * 1024L,
                downloadedBytes = 286 * 1024L,
                status = DownloadStatus.COMPLETED.name,
                createdAt = now
            ),
            DownloadEntity(
                id = 2L,
                profileId = 1L,
                url = "https://gstatic.com/images.jpeg",
                fileName = "images (25).jpeg",
                mimeType = "image/jpeg",
                category = DownloadFileCategory.IMAGE.name,
                localPath = "/tmp/images.jpeg",
                totalBytes = 31539L,
                downloadedBytes = 31539L,
                status = DownloadStatus.COMPLETED.name,
                createdAt = older
            )
        )

        composeRule.setContent {
            WeblioTheme {
                DownloadsScreen(
                    downloads = sampleDownloads,
                    downloadLocationLabel = "App Sandbox",
                    onEnqueueUrl = { _, _ -> },
                    onPause = {},
                    onResumeOrRetry = {},
                    onCancel = {},
                    onDelete = { deletedId = it },
                    onOpenFile = { Result.failure(IllegalStateException("missing")) },
                    onClearAll = {},
                    onShowMessage = {},
                    onBack = {},
                    totalStorageBytesOverride = 20L * 1024 * 1024 * 1024
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeRule.onNodeWithTag("downloads_filter_all").assertIsDisplayed()
        composeRule.onNodeWithTag("downloads_filter_images").assertIsDisplayed()
        composeRule.onNodeWithTag("downloads_filter_other").assertIsDisplayed()
        composeRule.onNodeWithText("iptrace (1).zip").assertIsDisplayed()
        composeRule.onNodeWithText("images (25).jpeg").assertIsDisplayed()

        // Open 3-dot menu (Share / Rename / Delete) on file row
        composeRule.onNodeWithTag("download_more_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Share").assertIsDisplayed()
        composeRule.onNodeWithText("Rename").assertIsDisplayed()
        composeRule.onNodeWithTag("download_menu_delete_1").performClick()
        composeRule.waitForIdle()
        assertEquals(1L, deletedId)

        // Filter by Images only
        composeRule.onNodeWithTag("downloads_filter_images").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("images (25).jpeg").assertIsDisplayed()
    }

    @Test
    fun extensionsQuickPopup_showsDeleteAndChromeAndEdgeStoreOptions() {
        var removedExt: ExtensionEntity? = null
        val openedUrls = mutableListOf<String>()
        val installed = listOf(
            ExtensionEntity(
                id = 7L,
                profileId = 1L,
                extId = "weblio.darkreader.engine",
                name = "Dark Reader",
                version = "4.9",
                description = "Dark theme for every website",
                sourceType = "CHROME_WEBEXT",
                enabled = true
            )
        )

        composeRule.setContent {
            WeblioTheme {
                ExtensionsQuickPopup(
                    extensions = installed,
                    onDismiss = {},
                    onOpenStoreUrl = { openedUrls.add(it) },
                    onOpenPopup = {},
                    onToggleExtension = {},
                    onManageExtensions = {},
                    onRemoveExtension = { removedExt = it }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Dark Reader").assertIsDisplayed()
        composeRule.onNodeWithText("Chrome Web Store").assertIsDisplayed()
        composeRule.onNodeWithText("Microsoft Edge Add-ons").assertIsDisplayed()

        // Delete button removes installed extension directly from popup
        composeRule.onNodeWithTag("popup_delete_ext_7").performClick()
        composeRule.waitForIdle()
        assertEquals(7L, removedExt?.id)

        // Selecting Chrome Web Store opens desktop store URL
        composeRule.onNodeWithTag("popup_store_chrome").performClick()
        composeRule.waitForIdle()
        assertTrue(openedUrls.contains("https://chromewebstore.google.com"))

        // Selecting Microsoft Edge Add-ons opens desktop Edge store URL
        composeRule.onNodeWithTag("popup_store_edge").performClick()
        composeRule.waitForIdle()
        assertTrue(openedUrls.contains("https://microsoftedge.microsoft.com/addons"))
    }

    @Test
    fun extensionsScreen_edgeAddonsButtonBuildsEdgeStoreSearchUrl() {
        var openedStoreUrl: String? = null

        composeRule.setContent {
            WeblioTheme {
                ExtensionsScreen(
                    installedExtensions = emptyList(),
                    builtInCatalog = emptyList(),
                    onToggleExtension = {},
                    onRemoveExtension = {},
                    onOpenPopup = {},
                    onInstallFromUri = { _, _ -> },
                    onInstallBuiltIn = {},
                    onCreateUserScript = { _, _ -> },
                    onBack = {},
                    onOpenStoreUrl = { openedStoreUrl = it }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ext_search_input").performTextInput("uBlock")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ext_open_edge_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("https://microsoftedge.microsoft.com/addons/search?q=uBlock", openedStoreUrl)
    }

    @Test
    fun tabCounter_countsOnlyActiveModeTabsMatchingTabsScreen() {
        val normal1 = TabEntity(id = 1L, profileId = 1L, url = "https://chromewebstore.google.com", title = "Chrome Web Store", isIncognito = false)
        val normal2 = TabEntity(id = 2L, profileId = 1L, url = "https://support.google.com", title = "Chrome Web Store Help", isIncognito = false)
        val private1 = TabEntity(id = 3L, profileId = 1L, url = "weblio://home", title = "Home", isIncognito = true)
        val private2 = TabEntity(id = 4L, profileId = 1L, url = "https://duckduckgo.com", title = "DuckDuckGo", isIncognito = true)
        val allTabs = listOf(normal1, normal2, private1, private2)

        assertEquals(2, BrowserViewModel.computeDisplayedTabCount(allTabs, normal2))
        assertEquals(2, BrowserViewModel.computeDisplayedTabCount(allTabs, private1))
        assertEquals(2, BrowserViewModel.computeDisplayedTabCount(allTabs, null))
    }

    @Test
    fun desktopInitialScalePercent_fitsMobileAndTabletWithinBounds() {
        // 1080px phone @ 2.75x density (~392dp) vs 1200dp target -> ~33%
        val phoneScale = BrowserViewModel.computeDesktopInitialScalePercent(
            viewWidthPx = 1080,
            displayWidthPx = 1080,
            density = 2.75f
        )
        assertTrue(phoneScale in 25..40)

        // Fallback when viewWidthPx == 0 uses displayWidthPx
        val preLayoutScale = BrowserViewModel.computeDesktopInitialScalePercent(
            viewWidthPx = 0,
            displayWidthPx = 1080,
            density = 2.75f
        )
        assertEquals(phoneScale, preLayoutScale)

        // Large tablet >= 1200dp caps at 100%
        val tabletScale = BrowserViewModel.computeDesktopInitialScalePercent(
            viewWidthPx = 2560,
            displayWidthPx = 2560,
            density = 2.0f
        )
        assertEquals(100, tabletScale)
    }

    @Test
    fun extensionSecurityReviewDialog_displaysWarningsAndHandlesActions() {
        var enabled = false
        var keptDisabled = false
        var deleted = false

        val sampleExt = ExtensionEntity(
            id = 42L,
            profileId = 1L,
            extId = "c3c.fbstate",
            name = "C3C FBState Utility",
            version = "2.1.0",
            description = "Export cookies state",
            sourceType = "CHROME_WEBEXT",
            enabled = false,
            permissionsJson = """["cookies","tabs","<all_urls>"]""",
            matchPatternsJson = """["*://*.facebook.com/*"]""",
            riskLevel = "HIGH",
            permissionWarningsJson = """["Accesses browser cookies","Reads all visited URLs"]"""
        )

        composeRule.setContent {
            WeblioTheme {
                ExtensionSecurityReviewDialog(
                    extension = sampleExt,
                    onEnable = { enabled = true },
                    onKeepDisabled = { keptDisabled = true },
                    onDelete = { deleted = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("extension_security_review_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Review C3C FBState Utility").assertIsDisplayed()
        composeRule.onNodeWithText("v2.1.0 • HIGH Risk").assertIsDisplayed()
        composeRule.onNodeWithText("• Accesses browser cookies").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("ext_review_enable_button").performClick()
        composeRule.waitForIdle()
        assertTrue(enabled)

        composeRule.onNodeWithTag("ext_review_keep_disabled_button").performClick()
        composeRule.waitForIdle()
        assertTrue(keptDisabled)

        composeRule.onNodeWithTag("ext_review_delete_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(deleted)
    }

    @Test
    fun mainMenuBottomSheet_hasUseInstalledExtensionsButtonAndTriggersPopup() {
        var useExtensionsClicked = false
        val activeExt = ExtensionEntity(
            id = 9L,
            profileId = 1L,
            extId = "weblio.test.ext",
            name = "Test Helper",
            version = "1.0",
            description = "Helper",
            sourceType = "WEBEXT_ZIP",
            enabled = true
        )

        composeRule.setContent {
            WeblioTheme {
                MainMenuBottomSheet(
                    activeProfile = ProfileEntity(id = 1L, name = "Personal", iconKey = "person"),
                    settings = BrowserSettings(),
                    isDesktopMode = false,
                    isIncognitoTab = false,
                    onDismiss = {},
                    onOpenProfiles = {},
                    onOpenBookmarks = {},
                    onOpenHistory = {},
                    onOpenDownloads = {},
                    onOpenSettings = {},
                    onOpenExtensions = {},
                    onOpenPasswords = {},
                    onOpenTabs = {},
                    onOpenAdBlock = {},
                    onToggleDesktopMode = {},
                    onToggleIncognito = {},
                    onToggleDarkMode = {},
                    extensions = listOf(activeExt),
                    onUseExtensions = { useExtensionsClicked = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("menu_use_extensions_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Extension").assertIsDisplayed()
        composeRule.onNodeWithText("1 active").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_use_extensions_btn").performClick()
        composeRule.waitForIdle()
        assertTrue(useExtensionsClicked)
    }

    @Test
    fun extensionPopupDialog_showsEnableWhenPausedAndRunWhenActive() {
        var toggledCount = 0
        var runClicked = false

        val pausedExt = ExtensionEntity(
            id = 5L,
            profileId = 1L,
            extId = "weblio.paused.ext",
            name = "Page Helper",
            version = "1.2",
            description = "Enhances pages",
            sourceType = "WEBEXT_ZIP",
            enabled = false,
            hasPopup = false,
            scriptContent = "console.log('hi');"
        )

        composeRule.setContent {
            WeblioTheme {
                ExtensionPopupDialog(
                    extension = pausedExt,
                    onDismiss = {},
                    onToggleExtension = { toggledCount++ },
                    onRunOnActiveTab = { runClicked = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("popup_dialog_enable_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, toggledCount)
    }
}
