package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.WeblioRepository
import com.example.data.db.WeblioDatabase
import com.example.data.prefs.BrowserPreferences
import com.example.engine.AdBlockEngine
import com.example.engine.BackupRestoreManager
import com.example.engine.ExtensionEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: WeblioDatabase
    private lateinit var repo: WeblioRepository
    private lateinit var prefs: BrowserPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, WeblioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WeblioRepository(db)
        prefs = (context as WeblioApplication).preferences
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `app name matches Weblo branding`() {
        assertEquals("Weblo", context.getString(R.string.app_name))
    }

    @Test
    fun `profile isolation separates bookmarks tabs history and passwords`() = runTest {
        val personalId = repo.ensureDefaultsSeeded()
        val workId = repo.createProfile("Work Test", "work")

        repo.addBookmark(personalId, "Personal Site", "https://personal.example", "General")
        repo.addBookmark(workId, "Work Portal", "https://work.example", "Work")

        val personalBookmarks = repo.observeBookmarks(personalId).first()
        val workBookmarks = repo.observeBookmarks(workId).first()

        assertTrue(personalBookmarks.any { it.url == "https://personal.example" })
        assertFalse(personalBookmarks.any { it.url == "https://work.example" })
        assertTrue(workBookmarks.any { it.url == "https://work.example" })
        assertFalse(workBookmarks.any { it.url == "https://personal.example" })

        // Incognito and Guest history must never persist
        repo.recordHistory(personalId, "Secret", "https://secret.example", isIncognito = true, isGuest = false)
        repo.recordHistory(personalId, "Guest", "https://guest.example", isIncognito = false, isGuest = true)
        repo.recordHistory(personalId, "Normal", "https://normal.example", isIncognito = false, isGuest = false)

        val history = repo.observeHistory(personalId).first()
        assertEquals(1, history.size)
        assertEquals("https://normal.example", history.first().url)
    }

    @Test
    fun `tab close and reopen restores tab state`() = runTest {
        val pid = repo.ensureDefaultsSeeded()
        val tabId = repo.createTab(pid, "https://example.org", "Example Org")
        val tabsBefore = repo.observeTabs(pid).first()
        val target = tabsBefore.first { it.id == tabId }

        repo.closeTab(target)
        assertTrue(repo.observeTabs(pid).first().none { it.id == tabId })

        val closed = repo.observeClosedTabs(pid).first()
        assertTrue(closed.any { it.url == "https://example.org" })

        val reopenedId = repo.reopenClosedTab(closed.first())
        val tabsAfter = repo.observeTabs(pid).first()
        assertTrue(tabsAfter.any { it.id == reopenedId && it.url == "https://example.org" })
    }

    @Test
    fun `encrypted backup and restore preserves bookmarks rules and passwords`() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(30_000L) {
        val pid = repo.ensureDefaultsSeeded()
        repo.savePassword(pid, "vault.example.com", "alice", "S3cur3!Pass#99")
        val backupMgr = BackupRestoreManager(repo, prefs)

        val exportRes = backupMgr.exportEncryptedBackup(pid, "MasterPass123", includePasswords = true)
        assertTrue(exportRes.isSuccess)
        val raw = exportRes.getOrThrow()
        // Never include unencrypted password in backup output
        assertFalse(raw.contains("S3cur3!Pass#99"))

        // Wrong passphrase must fail integrity check
        val badRestore = backupMgr.importEncryptedBackup(pid, raw, "WrongPass")
        assertTrue(badRestore is BackupRestoreManager.RestoreResult.Error)

        // Restore into a clean secondary profile
        val targetPid = repo.createProfile("Restored", "person")
        val goodRestore = backupMgr.importEncryptedBackup(targetPid, raw, "MasterPass123")
        assertTrue(goodRestore is BackupRestoreManager.RestoreResult.Success)

        val restoredPw = repo.getPasswordsForHost(targetPid, "vault.example.com")
        assertEquals(1, restoredPw.size)
        assertEquals("S3cur3!Pass#99", restoredPw.first().second)
        }
    }

    @Test
    fun `userscript installation and matching works end to end`() = runTest {
        val pid = repo.ensureDefaultsSeeded()
        val engine = ExtensionEngine(context, repo)
        val script = """
            // ==UserScript==
            // @name         Dark Accent Helper
            // @version      1.2
            // @description  Test UserScript
            // @match        https://example.com/*
            // @run-at       document-end
            // ==/UserScript==
            document.body.dataset.weblio = '1';
        """.trimIndent()

        val outcome = engine.installUserScript(pid, script, "dark.user.js")
        assertTrue(outcome is ExtensionEngine.InstallOutcome.Success)

        val list = repo.observeExtensions(pid).first()
        val payloadMatch = engine.buildInjectionPayload(list, "https://example.com/page", "document_end")
        val payloadNoMatch = engine.buildInjectionPayload(list, "https://other.org/", "document_end")

        assertTrue(payloadMatch.contains("document.body.dataset.weblio"))
        assertFalse(payloadNoMatch.contains("document.body.dataset.weblio"))
    }

    @Test
    fun `netscape bookmark html export and import roundtrip`() = runTest {
        val pid = repo.createProfile("BM", "person")
        repo.addBookmark(pid, "Kotlin Docs", "https://kotlinlang.org", "Dev")
        val backupMgr = BackupRestoreManager(repo, prefs)

        val html = backupMgr.exportBookmarksHtml(pid)
        assertTrue(html.contains("https://kotlinlang.org"))

        val pid2 = repo.createProfile("BM2", "person")
        val imported = backupMgr.importBookmarksHtml(pid2, html)
        assertEquals(1, imported)
        val bms = repo.observeBookmarks(pid2).first()
        assertNotNull(bms.firstOrNull { it.url == "https://kotlinlang.org" })
    }

    @Test
    fun `adblock cleans tracking parameters from urls`() = runTest {
        val adBlock = AdBlockEngine(repo)
        val cleaned = adBlock.cleanTrackingUrl("https://example.com/news?id=42&utm_source=x&fbclid=abc123")
        assertEquals("https://example.com/news?id=42", cleaned)
    }

    @Test
    fun `fresh seeding does not pin default bookmarks and SafeSearch defaults to enabled`() = runTest {
        val personalId = repo.ensureDefaultsSeeded()
        val seededBookmarks = repo.observeBookmarks(personalId).first()
        assertTrue(seededBookmarks.isEmpty())

        val initialSettings = prefs.settingsFlow.first()
        assertTrue(initialSettings.safeSearchEnabled)

        prefs.setSafeSearchEnabled(false)
        assertFalse(prefs.settingsFlow.first().safeSearchEnabled)
    }

    @Test
    fun `legacy default bookmarks prune cleanly without deleting user bookmarks`() = runTest {
        val personalId = repo.ensureDefaultsSeeded()
        repo.addBookmark(personalId, "Wikipedia", "https://www.wikipedia.org", "Knowledge")
        repo.addBookmark(personalId, "MDN Web Docs", "https://developer.mozilla.org", "Developer")
        repo.addBookmark(personalId, "PrivacyTests.org", "https://privacytests.org", "Privacy")
        repo.addBookmark(personalId, "My Saved Site", "https://example.org", "General")

        repo.pruneLegacyDefaultBookmarks(personalId)
        val remaining = repo.observeBookmarks(personalId).first()
        assertEquals(1, remaining.size)
        assertEquals("https://example.org", remaining.first().url)
    }

    @Test
    fun `manifest registers WebKit DropDataContentProvider for package authority`() {
        val authority = "${context.packageName}.DropDataProvider"
        val providerInfo = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull("Expected DropDataProvider to be declared in AndroidManifest.xml", providerInfo)
        assertEquals("androidx.webkit.DropDataContentProvider", providerInfo?.name)
    }

    @Test
    fun `renderer process gone handles background reclaim and foreground crash safely`() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[com.example.ui.BrowserViewModel::class.java]
        val tabId = 99L

        try {
            // Background memory kill (didCrash = false) auto-bumps version without blocking banner
            vm.handleRendererProcessGone(tabId, null, didCrash = false)
            val afterKill = vm.tabRuntime.value[tabId]
            assertNotNull(afterKill)
            assertEquals(1, afterKill?.webViewInstanceVersion)
            assertEquals(1, afterKill?.rendererCrashCount)
            assertEquals(null, afterKill?.errorBanner)

            // Real renderer crash (didCrash = true) bumps version and surfaces recovery banner
            vm.handleRendererProcessGone(tabId, null, didCrash = true)
            val afterCrash = vm.tabRuntime.value[tabId]
            assertEquals(2, afterCrash?.webViewInstanceVersion)
            assertEquals(2, afterCrash?.rendererCrashCount)
            assertNotNull(afterCrash?.errorBanner)

            vm.dismissTabErrorBanner(tabId)
            assertEquals(null, vm.tabRuntime.value[tabId]?.errorBanner)
        } finally {
            vmStore.clear()
        }
    }

    @Test
    fun `extension manifest icon resolver selects highest raster icon and blocks path traversal`() {
        val sandboxRoot = Files.createTempDirectory("weblo_ext_icon_test").toFile()
        try {
            val extDir = File(sandboxRoot, "ext").apply { mkdirs() }
            val outsideFile = File(sandboxRoot, "outside.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertTrue(outsideFile.exists())

            val iconsDir = File(extDir, "icons").apply { mkdirs() }
            val icon16 = File(iconsDir, "16.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val icon128 = File(iconsDir, "128.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val iconSvg = File(iconsDir, "vector.svg").apply { writeText("<svg></svg>") }
            assertTrue(icon16.exists() && iconSvg.exists())

            File(extDir, "manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Demo Ext",
                  "version": "1.0",
                  "icons": {
                    "16": "icons/16.png",
                    "128": "icons/128.png",
                    "256": "../outside.png",
                    "512": "icons/vector.svg"
                  }
                }
                """.trimIndent()
            )

            val resolved = ExtensionEngine.resolveExtensionIconFile(extDir.absolutePath)
            assertNotNull(resolved)
            assertEquals(icon128.canonicalPath, resolved?.canonicalPath)
        } finally {
            sandboxRoot.deleteRecursively()
        }
    }

    @Test
    fun `custom home background copies image into internal storage and resets cleanly`() = runTest {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[com.example.ui.BrowserViewModel::class.java]
        val realPrefs = app.preferences

        // Store source image outside app.cacheDir so startup disk cache cleaner does not remove test fixture
        val inputDir = File(app.filesDir, "test_wallpaper_inputs").apply { mkdirs() }
        val sourceFile = File(inputDir, "sample_wallpaper.png")
        val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        sourceFile.outputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }

        try {
            vm.setCustomHomeBackground(android.net.Uri.fromFile(sourceFile))
            var savedPath = ""
            val deadline1 = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline1) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val candidate = realPrefs.settingsFlow.first().customHomeBackgroundPath
                if (candidate.isNotBlank()) {
                    savedPath = candidate
                    break
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(25) }
            }
            assertTrue("Expected non-blank custom wallpaper path", savedPath.isNotBlank())
            assertTrue("Expected copied file in wallpapers dir", File(savedPath).exists())
            assertTrue(savedPath.contains("wallpapers"))

            // Clearing resets preference and removes stored wallpaper file
            vm.clearCustomHomeBackground()
            var clearedPath = savedPath
            val deadline2 = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline2) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                clearedPath = realPrefs.settingsFlow.first().customHomeBackgroundPath
                if (clearedPath.isEmpty()) break
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(25) }
            }
            assertEquals("", clearedPath)
            assertFalse(File(savedPath).exists())

            // Re-apply then pick a preset wallpaper style -> should also clear custom image
            vm.setCustomHomeBackground(android.net.Uri.fromFile(sourceFile))
            var secondPath = ""
            val deadline3 = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline3) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val candidate = realPrefs.settingsFlow.first().customHomeBackgroundPath
                if (candidate.isNotBlank()) {
                    secondPath = candidate
                    break
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(25) }
            }
            assertTrue(secondPath.isNotBlank())

            vm.setWallpaperStyle(com.example.data.prefs.WallpaperStyle.ELECTRIC_AURORA)
            var afterPreset = secondPath
            val deadline4 = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline4) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val s = realPrefs.settingsFlow.first()
                if (s.customHomeBackgroundPath.isEmpty() && s.wallpaperStyle == com.example.data.prefs.WallpaperStyle.ELECTRIC_AURORA) {
                    afterPreset = ""
                    break
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(25) }
            }
            assertEquals("", afterPreset)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } finally {
            vmStore.clear()
            inputDir.deleteRecursively()
            File(app.filesDir, "wallpapers").deleteRecursively()
        }
    }

    @Test
    fun `default theme is White and default wallpaper is Electric Aurora`() = runTest {
        val defaults = prefs.settingsFlow.first()
        assertEquals(com.example.ui.theme.AppThemeMode.LIGHT, defaults.themeMode)
        assertEquals("White", defaults.themeMode.label)
        assertEquals(com.example.data.prefs.WallpaperStyle.ELECTRIC_AURORA, defaults.wallpaperStyle)
        assertEquals("Electric Aurora", defaults.wallpaperStyle.label)
    }

    @Test
    fun `startup and resume preserve WebView HTTP GPU and shader caches without auto disk wipes`() = runTest {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val dataRoot = File(context.applicationInfo.dataDir)
        val webViewDefault = File(dataRoot, "app_webview/Default")
        val httpCacheEntry = File(webViewDefault, "Cache/entry.bin").apply { parentFile?.mkdirs(); writeText("keep-http") }
        val webCodeCache = File(webViewDefault, "Code Cache/js/a.bin").apply { parentFile?.mkdirs(); writeText("keep-v8") }
        val gpuCache = File(webViewDefault, "GPUCache/shader.bin").apply { parentFile?.mkdirs(); writeText("keep-gpu") }
        val appHttpCache = File(context.cacheDir, "WebView/Default/HTTP Cache/item").apply { parentFile?.mkdirs(); writeText("keep-webview-cache") }

        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[com.example.ui.BrowserViewModel::class.java]

        try {
            vm.onAppBackgrounded()
            vm.onAppResumed(staleTabThresholdMs = 0L)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(100) }

            assertTrue("HTTP cache should remain preserved", httpCacheEntry.exists())
            assertTrue("Chromium Code Cache should remain preserved", webCodeCache.exists())
            assertTrue("Chromium GPUCache should remain preserved", gpuCache.exists())
            assertTrue("App WebView HTTP cache should remain preserved", appHttpCache.exists())
        } finally {
            vmStore.clear()
            File(dataRoot, "app_webview").deleteRecursively()
            File(context.cacheDir, "WebView").deleteRecursively()
        }
    }

    @Test
    fun `background renderer reclaim always auto-recovers without leaving blank error banner`() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[com.example.ui.BrowserViewModel::class.java]
        val tabId = 202L

        try {
            repeat(4) { idx ->
                vm.handleRendererProcessGone(tabId, null, didCrash = false)
                val rt = vm.tabRuntime.value[tabId]
                assertEquals(idx + 1, rt?.webViewInstanceVersion)
                assertEquals(null, rt?.errorBanner)
            }
        } finally {
            vmStore.clear()
        }
    }

    @Test
    fun `webView LRU pool caps background tabs and trims under memory pressure`() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[com.example.ui.BrowserViewModel::class.java]

        try {
            val wv1 = android.webkit.WebView(context)
            val wv2 = android.webkit.WebView(context)
            val wv3 = android.webkit.WebView(context)
            val wv4 = android.webkit.WebView(context)

            vm.registerWebView(11L, wv1)
            vm.registerWebView(12L, wv2)
            vm.registerWebView(13L, wv3)
            assertEquals(3, vm.getLiveWebViewCount())

            vm.registerWebView(14L, wv4)
            assertEquals(com.example.ui.BrowserViewModel.MAX_LIVE_WEBVIEWS, vm.getLiveWebViewCount())
            assertEquals(null, vm.getWebView(11L))
            assertTrue(vm.getWebView(14L) != null)

            vm.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(1, vm.getLiveWebViewCount())
        } finally {
            vmStore.clear()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun `qr scanner decodes standard, row-stride padded YUV frames, and inverted dark-mode QR codes`() {
        val payload = "https://weblio.example/qr-ok?x=1"
        val size = 240
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            payload,
            com.google.zxing.BarcodeFormat.QR_CODE,
            size,
            size
        )

        val normalBmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val invertedBmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val tightY = ByteArray(size * size)
        val invertedY = ByteArray(size * size)
        val rowStride = size + 64
        val paddedY = ByteArray(size * rowStride) { 0x55.toByte() }

        for (y in 0 until size) {
            for (x in 0 until size) {
                val black = matrix.get(x, y)
                normalBmp.setPixel(x, y, if (black) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                invertedBmp.setPixel(x, y, if (black) android.graphics.Color.WHITE else android.graphics.Color.BLACK)

                val luma: Byte = if (black) 16.toByte() else 235.toByte()
                val invLuma: Byte = if (black) 235.toByte() else 16.toByte()
                tightY[y * size + x] = luma
                invertedY[y * size + x] = invLuma
                paddedY[y * rowStride + x] = luma
            }
        }

        val reader = com.example.ui.screens.QrScannerDecoder.createQrReader()
        assertEquals(payload, com.example.ui.screens.QrScannerDecoder.decodeFromBitmap(normalBmp, reader))
        assertEquals(payload, com.example.ui.screens.QrScannerDecoder.decodeFromBitmap(invertedBmp, reader))
        assertEquals(payload, com.example.ui.screens.QrScannerDecoder.decodeFromYuvBytes(tightY, size, size, size, 1, reader))
        assertEquals(payload, com.example.ui.screens.QrScannerDecoder.decodeFromYuvBytes(paddedY, size, size, rowStride, 1, reader))
        assertEquals(payload, com.example.ui.screens.QrScannerDecoder.decodeFromYuvBytes(invertedY, size, size, size, 1, reader))
    }

    @Test
    fun `browser import manager discovers installed browsers excluding self`() {
        val shadowPm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val chromeIcon = android.graphics.drawable.ColorDrawable(android.graphics.Color.RED)
        val firefoxIcon = android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)

        val httpsIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://example.com")
        ).apply { addCategory(android.content.Intent.CATEGORY_BROWSABLE) }

        val chromeRi = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.android.chrome"
                name = "com.google.android.apps.chrome.Main"
            }
            nonLocalizedLabel = "Chrome"
        }
        val firefoxRi = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "org.mozilla.firefox"
                name = "org.mozilla.fenix.App"
            }
            nonLocalizedLabel = "Firefox"
        }
        val selfRi = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = context.packageName
                name = "com.example.MainActivity"
            }
            nonLocalizedLabel = "Weblo"
        }

        shadowPm.addResolveInfoForIntent(httpsIntent, chromeRi)
        shadowPm.addResolveInfoForIntent(httpsIntent, firefoxRi)
        shadowPm.addResolveInfoForIntent(httpsIntent, selfRi)
        shadowPm.setApplicationIcon("com.android.chrome", chromeIcon)
        shadowPm.setApplicationIcon("org.mozilla.firefox", firefoxIcon)

        val importer = com.example.engine.BrowserImportManager(repo)
        val discovered = importer.discoverInstalledBrowsers(context)

        assertTrue(discovered.any { it.packageName == "com.android.chrome" && it.appName == "Chrome" })
        assertTrue(discovered.any { it.packageName == "org.mozilla.firefox" && it.appName == "Firefox" })
        assertFalse(discovered.any { it.packageName == context.packageName })
    }

    @Test
    fun `browser import manager imports history JSON bookmarks HTML and passwords CSV cleanly`() = runTest {
        val pid = repo.ensureDefaultsSeeded()
        val importer = com.example.engine.BrowserImportManager(repo)

        val chromeHistoryJson = """
            {
              "Browser History": [
                {
                  "title": "GitHub Dashboard",
                  "url": "https://github.com/dashboard",
                  "time_usec": 1730000000000000
                },
                {
                  "title": "Kotlin Reference",
                  "url": "https://kotlinlang.org/docs/home.html",
                  "time_usec": 1730000500000000
                }
              ]
            }
        """.trimIndent()

        val htmlBookmarks = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
              <DT><H3>Tech Reads</H3>
              <DL><p>
                <DT><A HREF="https://developer.android.com/jetpack" ADD_DATE="1730000100">Android Jetpack</A>
              </DL><p>
            </DL><p>
        """.trimIndent()

        val passwordsCsv = """
            name,url,username,password
            Example Portal,https://portal.example.org/login,bob@example.org,P@ssw0rd!42
        """.trimIndent()

        val r1 = importer.parseAndImport(pid, chromeHistoryJson, "Chrome")
        val r2 = importer.parseAndImport(pid, htmlBookmarks, "Chrome")
        val r3 = importer.parseAndImport(pid, passwordsCsv, "Chrome")

        assertEquals(2, r1.historyCount)
        assertTrue(r1.quickLinkCount > 0)
        assertEquals(1, r2.bookmarkCount)
        assertEquals(1, r3.passwordCount)

        // Duplicate import should not duplicate history or bookmarks
        val r1Dup = importer.parseAndImport(pid, chromeHistoryJson, "Chrome")
        assertEquals(0, r1Dup.historyCount)

        val history = repo.observeHistory(pid).first()
        assertEquals(2, history.size)
        assertTrue(history.any { it.url == "https://github.com/dashboard" })

        val bookmarks = repo.observeBookmarks(pid).first()
        assertTrue(bookmarks.any { it.url == "https://developer.android.com/jetpack" && it.folder == "Tech Reads" })

        val savedPw = repo.getPasswordsForHost(pid, "portal.example.org")
        assertEquals(1, savedPw.size)
        assertEquals("P@ssw0rd!42", savedPw.first().second)
    }

    @Test
    fun `tab audio mute state and media controller JS builders work cleanly`() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        )[com.example.ui.BrowserViewModel::class.java]

        try {
            vm.updateRuntimeState(101L) {
                it.copy(
                    currentUrl = "https://youtube.com/watch?v=abc",
                    title = "Music Mix",
                    isMediaPlaying = true,
                    hasMediaSession = true,
                    mediaTitle = "Synthwave Live"
                )
            }
            vm.updateRuntimeState(102L) {
                it.copy(
                    currentUrl = "https://podcast.example.org/ep1",
                    title = "Tech Podcast",
                    isMediaPlaying = true,
                    hasMediaSession = true,
                    mediaTitle = "Episode 1"
                )
            }

            assertFalse(vm.isTabMuted(101L))
            vm.toggleTabMute(101L)
            assertTrue(vm.isTabMuted(101L))
            vm.toggleTabMute(101L)
            assertFalse(vm.isTabMuted(101L))

            vm.muteAllPlayingTabs()
            assertTrue(vm.isTabMuted(101L))
            assertTrue(vm.isTabMuted(102L))

            vm.setTabMuted(101L, false)
            assertFalse(vm.isTabMuted(101L))
            vm.handleMediaServiceCommand("toggle_mute")
            assertTrue(vm.isTabMuted(101L))

            val muteJs = com.example.ui.BrowserViewModel.buildTabMuteJs(true)
            assertTrue(muteJs.contains("window.__weblioTabMuted = true"))
            assertTrue(muteJs.contains("window.__weblioSetMuted(true)"))

            val seekJs = com.example.ui.BrowserViewModel.buildMediaCommandJs("seek", -10)
            assertTrue(seekJs.contains("__weblioSeekBy"))
            assertTrue(seekJs.contains("-10"))

            val togglePlayJs = com.example.ui.BrowserViewModel.buildMediaCommandJs("toggle_play")
            assertTrue(togglePlayJs.contains("__weblioTogglePlayPause"))
        } finally {
            vmStore.clear()
        }
    }
}
