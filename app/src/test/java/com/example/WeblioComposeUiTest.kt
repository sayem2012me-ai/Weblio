package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import com.example.data.db.DownloadEntity
import com.example.data.db.DownloadFileCategory
import com.example.data.db.DownloadStatus
import com.example.data.db.ExtensionEntity
import com.example.data.db.HistoryEntity
import com.example.data.db.ProfileEntity
import com.example.data.db.QuickLinkEntity
import com.example.data.db.TabEntity
import com.example.data.prefs.BrowserSettings
import com.example.data.prefs.SearchEngine
import com.example.ui.BrowserViewModel
import com.example.ui.TabRuntimeState
import com.example.ui.components.ExtensionPopupDialog
import com.example.ui.components.WeblioAddressBar
import com.example.ui.components.WeblioBottomNavBar
import com.example.ui.screens.BrowserToolsBottomSheet
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.ExtensionSecurityReviewDialog
import com.example.ui.screens.ExtensionsQuickPopup
import com.example.ui.screens.ExtensionsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImportBrowserDataScreen
import com.example.ui.screens.MainMenuBottomSheet
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TabsScreen
import com.example.engine.BrowserFamily
import com.example.engine.BrowserImportManager
import com.example.engine.InstalledBrowserInfo
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.WeblioGrayBg
import com.example.ui.theme.WeblioLightBg
import com.example.ui.theme.WeblioTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
        composeRule.onNodeWithText("Weblo").assertIsDisplayed()
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
        composeRule.onNodeWithTag("popup_ext_logo_7", useUnmergedTree = true).assertIsDisplayed()
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
        composeRule.onNodeWithTag("popup_header_ext_logo").assertIsDisplayed()
        composeRule.onNodeWithTag("popup_dialog_enable_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, toggledCount)
    }

    @Test
    fun extensionsScreen_installedTabDisplaysExtensionLogos() {
        val installed = listOf(
            ExtensionEntity(
                id = 12L,
                profileId = 1L,
                extId = "weblio.ublock.cosmetic",
                name = "uBlock Cosmetic & Scriptlets",
                version = "1.61.0",
                description = "Blocks residual ad containers",
                sourceType = "BUILTIN_ENGINE",
                enabled = true
            )
        )

        composeRule.setContent {
            WeblioTheme {
                ExtensionsScreen(
                    installedExtensions = installed,
                    builtInCatalog = emptyList(),
                    onToggleExtension = {},
                    onRemoveExtension = {},
                    onOpenPopup = {},
                    onInstallFromUri = { _, _ -> },
                    onInstallBuiltIn = {},
                    onCreateUserScript = { _, _ -> },
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Installed (1)").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ext_logo_12").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("uBlock Cosmetic & Scriptlets").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun homeScreen_searchBarClickTriggersFullScreenSearchCallback() {
        var searchBarClicked = false

        composeRule.setContent {
            WeblioTheme {
                HomeScreen(
                    settings = BrowserSettings(),
                    activeProfile = ProfileEntity(id = 1, name = "Personal", iconKey = "person"),
                    quickLinks = emptyList(),
                    isIncognito = false,
                    sessionBlockedCount = 0L,
                    onNavigateUrl = {},
                    onSelectSearchEngine = {},
                    onQrClick = {},
                    onVoiceClick = {},
                    onExtensionsClick = {},
                    onBookmarksClick = {},
                    onHistoryClick = {},
                    onAdBlockClick = {},
                    onProfileClick = {},
                    onAddQuickLink = { _, _ -> },
                    onDeleteQuickLink = {},
                    onSearchBarClick = { searchBarClicked = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("address_input_field").performClick()
        composeRule.waitForIdle()
        assertTrue(searchBarClicked)
    }

    @Test
    fun searchScreen_rendersSuggestionsHandlesFillArrowClearCloseAndEngineSwitch() {
        var submitted: String? = null
        var selectedEngine: SearchEngine? = null
        var backCalled = false
        val history = listOf(
            HistoryEntity(id = 1L, profileId = 1L, title = "Search Console Help", url = "https://search.google.com")
        )
        val suggestions = listOf("search", "search history", "search console")

        composeRule.setContent {
            WeblioTheme {
                SearchScreen(
                    initialQuery = "sesr",
                    searchEngine = SearchEngine.GOOGLE,
                    isIncognito = false,
                    suggestions = suggestions,
                    isLoadingSuggestions = false,
                    history = history,
                    bookmarks = emptyList(),
                    onQueryChange = {},
                    onSelectSearchEngine = { selectedEngine = it },
                    onSubmitQuery = { submitted = it },
                    onQrClick = {},
                    onVoiceClick = {},
                    onClearSuggestions = {},
                    onBack = { backCalled = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_screen_root").assertIsDisplayed()
        composeRule.onNodeWithTag("search_direct_query_row").assertIsDisplayed()
        composeRule.onNodeWithText("search history").assertIsDisplayed()

        // Tap '+' to reveal quick search engine switcher and switch to Brave
        composeRule.onNodeWithTag("search_plus_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Brave").performClick()
        composeRule.waitForIdle()
        assertEquals(SearchEngine.BRAVE, selectedEngine)

        // Tap fill arrow (↖) on the first autocomplete suggestion row (index 1 -> "search")
        // Verify input updates without submitting
        composeRule.onNodeWithTag("search_fill_arrow_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_page_input").assertTextContains("search")
        assertEquals(null, submitted)

        // Tap row to submit query
        composeRule.onNodeWithTag("search_suggestion_row_2").performClick()
        composeRule.waitForIdle()
        assertEquals("search history", submitted)

        // Tap X to clear non-empty input, then tap X again to close search page
        composeRule.onNodeWithTag("search_clear_close_button").performClick()
        composeRule.waitForIdle()
        assertEquals(false, backCalled)
        composeRule.onNodeWithTag("search_clear_close_button").performClick()
        composeRule.waitForIdle()
        assertTrue(backCalled)
    }

    @Test
    fun homeScreen_rendersCustomBackgroundOnlyInNormalMode() {
        val tempImg = File.createTempFile("weblo_home_bg_", ".png")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        tempImg.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        try {
            composeRule.setContent {
                WeblioTheme {
                    HomeScreen(
                        settings = BrowserSettings(customHomeBackgroundPath = tempImg.absolutePath),
                        activeProfile = ProfileEntity(id = 1, name = "Personal", iconKey = "person"),
                        quickLinks = emptyList(),
                        isIncognito = false,
                        sessionBlockedCount = 0L,
                        onNavigateUrl = {},
                        onSelectSearchEngine = {},
                        onQrClick = {},
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
            composeRule.onNodeWithTag("home_custom_background_image").assertIsDisplayed()
        } finally {
            tempImg.delete()
        }
    }

    @Test
    fun homeScreen_hidesCustomBackgroundInIncognitoMode() {
        val tempImg = File.createTempFile("weblo_incog_bg_", ".png")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        tempImg.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        try {
            composeRule.setContent {
                WeblioTheme {
                    HomeScreen(
                        settings = BrowserSettings(customHomeBackgroundPath = tempImg.absolutePath),
                        activeProfile = ProfileEntity(id = 1, name = "Personal", iconKey = "person"),
                        quickLinks = emptyList(),
                        isIncognito = true,
                        sessionBlockedCount = 0L,
                        onNavigateUrl = {},
                        onSelectSearchEngine = {},
                        onQrClick = {},
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
            composeRule.onNodeWithTag("home_custom_background_image").assertDoesNotExist()
        } finally {
            tempImg.delete()
        }
    }

    @Test
    fun settingsScreen_showsCustomBackgroundControlsAndPreviewWhenSet() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[BrowserViewModel::class.java]
        val tempImg = File.createTempFile("weblo_settings_bg_", ".png")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        tempImg.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        try {
            composeRule.setContent {
                WeblioTheme {
                    SettingsScreen(
                        settings = BrowserSettings(customHomeBackgroundPath = tempImg.absolutePath),
                        viewModel = vm,
                        onOpenExtensions = {},
                        onOpenAdBlock = {},
                        onOpenPasswords = {},
                        onOpenSitePermissions = {},
                        onOpenBackupRestore = {},
                        onBack = {}
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_custom_home_bg_preview"))
            composeRule.onNodeWithTag("settings_custom_home_bg_preview").assertIsDisplayed()
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_pick_home_bg_button"))
            composeRule.onNodeWithTag("settings_pick_home_bg_button").assertIsDisplayed()
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_clear_home_bg_button"))
            composeRule.onNodeWithTag("settings_clear_home_bg_button").assertIsDisplayed()
        } finally {
            vmStore.clear()
            tempImg.delete()
        }
    }

    @Test
    fun splashScreen_holdsUntilStartupReadyAndShowsOriginalBrandingWithoutIcon() {
        var ready by mutableStateOf(false)
        var finished = false

        composeRule.setContent {
            WeblioTheme {
                SplashScreen(
                    isReady = ready,
                    onFinished = { finished = true }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(2000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("splash_screen_root").assertIsDisplayed()
        composeRule.onNodeWithText("Weblo").assertIsDisplayed()
        composeRule.onNodeWithText("EXPLORE. DISCOVER. EXPERIENCE.").assertIsDisplayed()
        assertEquals(false, finished)

        ready = true
        composeRule.waitForIdle()
        assertTrue(finished)
    }

    @Test
    fun settingsScreen_showsImportDataButtonAndTriggersCallback() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[BrowserViewModel::class.java]
        var importClicked = false

        try {
            composeRule.setContent {
                WeblioTheme {
                    SettingsScreen(
                        settings = BrowserSettings(),
                        viewModel = vm,
                        onOpenExtensions = {},
                        onOpenAdBlock = {},
                        onOpenPasswords = {},
                        onOpenSitePermissions = {},
                        onOpenBackupRestore = {},
                        onBack = {},
                        onOpenImportData = { importClicked = true }
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_import_data_button"))
            composeRule.onNodeWithTag("settings_import_data_button").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertTrue(importClicked)
        } finally {
            vmStore.clear()
        }
    }

    @Test
    fun importBrowserDataScreen_rendersInstalledBrowserIconsAndOpensImportDialog() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val iconDrawable = android.graphics.drawable.BitmapDrawable(app.resources, bmp)
        val fakeBrowsers = listOf(
            InstalledBrowserInfo(
                packageName = "com.android.chrome",
                appName = "Chrome",
                icon = iconDrawable,
                family = BrowserFamily.CHROME,
                exportGuideSteps = listOf("Export from Chrome", "Pick files below")
            ),
            InstalledBrowserInfo(
                packageName = "org.mozilla.firefox",
                appName = "Firefox",
                icon = iconDrawable,
                family = BrowserFamily.FIREFOX,
                exportGuideSteps = listOf("Export from Firefox", "Pick files below")
            )
        )

        composeRule.setContent {
            WeblioTheme {
                ImportBrowserDataScreen(
                    profileId = 1L,
                    importManager = BrowserImportManager(app.repository),
                    onShowMessage = {},
                    onBack = {},
                    discoveredBrowsersOverride = fakeBrowsers
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("import_browser_card_com.android.chrome").assertIsDisplayed()
        composeRule.onNodeWithTag("import_browser_card_org.mozilla.firefox").assertIsDisplayed()
        composeRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeRule.onNodeWithText("Firefox").assertIsDisplayed()

        composeRule.onNodeWithTag("import_browser_card_com.android.chrome").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("import_browser_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Import from Chrome").assertIsDisplayed()
        composeRule.onNodeWithTag("import_pick_files_button").assertIsDisplayed()
        composeRule.onNodeWithTag("import_launch_browser_button").assertIsDisplayed()
    }

    @Test
    fun tabsScreen_displaysMediaControllerCardAndOneTapTabMuteButtons() {
        var mutedTabId: Long? = null
        var playPauseTabId: Long? = null
        var seekedTabId: Long? = null
        var seekDelta: Int? = null
        var muteAllClicked = false

        val tabs = listOf(
            TabEntity(id = 21L, profileId = 1L, url = "https://www.youtube.com/watch?v=1", title = "YouTube"),
            TabEntity(id = 22L, profileId = 1L, url = "https://soundcloud.com/mix", title = "SoundCloud")
        )
        val runtimeMap = mapOf(
            21L to TabRuntimeState(
                tabId = 21L,
                currentUrl = "https://www.youtube.com/watch?v=1",
                title = "Lo-Fi Study Beats",
                isMediaPlaying = true,
                hasMediaSession = true,
                mediaTitle = "Lo-Fi Study Beats",
                isAudioMuted = false
            ),
            22L to TabRuntimeState(
                tabId = 22L,
                currentUrl = "https://soundcloud.com/mix",
                title = "Focus Mix",
                isMediaPlaying = true,
                hasMediaSession = true,
                mediaTitle = "Focus Mix",
                isAudioMuted = false
            )
        )

        composeRule.setContent {
            WeblioTheme {
                TabsScreen(
                    tabs = tabs,
                    closedTabs = emptyList(),
                    activeTabId = 21L,
                    onSelectTab = {},
                    onNewTab = { _, _ -> },
                    onCloseTab = {},
                    onCloseAllTabs = {},
                    onDuplicateTab = {},
                    onAssignGroup = { _, _ -> },
                    onReopenClosed = {},
                    onBack = {},
                    tabRuntimeMap = runtimeMap,
                    onToggleMuteTab = { mutedTabId = it },
                    onToggleMediaPlayPause = { playPauseTabId = it },
                    onSeekMedia = { id, delta -> seekedTabId = id; seekDelta = delta },
                    onMuteAllPlaying = { muteAllClicked = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tabs_media_controller_card_21").assertIsDisplayed()
        composeRule.onNodeWithText("www.youtube.com • Playing").assertIsDisplayed()
        composeRule.onNodeWithTag("tabs_mute_all_button").assertIsDisplayed()

        composeRule.onNodeWithTag("media_ctrl_rewind_21").performClick()
        composeRule.waitForIdle()
        assertEquals(21L, seekedTabId)
        assertEquals(-10, seekDelta)

        composeRule.onNodeWithTag("media_ctrl_forward_21").performClick()
        composeRule.waitForIdle()
        assertEquals(10, seekDelta)

        composeRule.onNodeWithTag("media_ctrl_play_pause_21").performClick()
        composeRule.waitForIdle()
        assertEquals(21L, playPauseTabId)

        composeRule.onNodeWithTag("media_ctrl_mute_21").performClick()
        composeRule.waitForIdle()
        assertEquals(21L, mutedTabId)

        composeRule.onNodeWithTag("tab_mute_button_22").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(22L, mutedTabId)

        composeRule.onNodeWithTag("tabs_mute_all_button").performClick()
        composeRule.waitForIdle()
        assertTrue(muteAllClicked)
    }

    @Test
    fun addressBar_displaysOneTapMuteButtonWhenMediaPlayingAndTriggersCallback() {
        var muteClicked = false

        composeRule.setContent {
            WeblioTheme {
                WeblioAddressBar(
                    urlText = "https://www.youtube.com/watch?v=1",
                    isHome = false,
                    isLoading = false,
                    progress = 100,
                    isIncognito = false,
                    isSecureHttps = true,
                    isDesktopMode = false,
                    blockedCount = 2,
                    onSubmitUrl = {},
                    onRefreshOrStop = {},
                    onQrClick = {},
                    onVoiceClick = {},
                    onExtensionsClick = {},
                    onShieldClick = {},
                    isMediaPlaying = true,
                    isAudioMuted = false,
                    onToggleMuteClick = { muteClicked = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("address_audio_mute_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(muteClicked)
    }

    @Test
    fun browserToolsBottomSheet_displaysMediaControllerStripAndTriggersCallbacks() {
        var playPauseClicked = false
        var muteClicked = false

        composeRule.setContent {
            WeblioTheme {
                BrowserToolsBottomSheet(
                    currentUrl = "https://music.example.com/live",
                    isBookmarked = false,
                    siteAdBlockEnabled = true,
                    onDismiss = {},
                    onBookmarkToggle = {},
                    onFindInPage = {},
                    onShareLink = {},
                    onCopyLink = {},
                    onToggleSiteAdBlock = {},
                    onEnterPip = {},
                    onOpenSitePermissions = {},
                    onOpenAdBlockDashboard = {},
                    isMediaPlaying = true,
                    isAudioMuted = false,
                    hasMediaSession = true,
                    mediaTitle = "Chillhop Live",
                    onToggleMediaPlayPause = { playPauseClicked = true },
                    onToggleAudioMute = { muteClicked = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tools_media_controller_card").assertIsDisplayed()
        composeRule.onNodeWithText("Chillhop Live").assertIsDisplayed()

        composeRule.onNodeWithTag("tools_media_play_pause").performClick()
        composeRule.waitForIdle()
        assertTrue(playPauseClicked)

        composeRule.onNodeWithTag("tools_media_mute_button").performClick()
        composeRule.waitForIdle()
        assertTrue(muteClicked)
    }
}
