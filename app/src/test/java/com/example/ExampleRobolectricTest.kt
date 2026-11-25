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
        prefs = BrowserPreferences(context)
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
        kotlinx.coroutines.withTimeout(15_000L) {
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
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
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
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[com.example.ui.BrowserViewModel::class.java]
        val realPrefs = BrowserPreferences(app)

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
    fun `startup disk cache cleaner wipes HTTP and code caches while keeping cookies and open previews`() {
        val dataRoot = File(context.applicationInfo.dataDir)
        val webViewDefault = File(dataRoot, "app_webview/Default")
        val webHttpCache = File(webViewDefault, "Cache/index.bin").apply { parentFile?.mkdirs(); writeText("stale-cache") }
        val webCodeCache = File(webViewDefault, "Code Cache/js/a.bin").apply { parentFile?.mkdirs(); writeText("stale-js") }
        val swCache = File(webViewDefault, "Service Worker/CacheStorage/v1").apply { parentFile?.mkdirs(); writeText("sw") }
        val cookiesFile = File(webViewDefault, "Cookies").apply { writeText("session=keep") }
        val tempAppCache = File(context.cacheDir, "http_tmp_file").apply { writeText("tmp") }
        val previewsDir = File(context.cacheDir, "tab_previews").apply { mkdirs() }
        val openPreview = File(previewsDir, "tab_101.jpg").apply { writeText("keep-thumb") }
        val closedPreview = File(previewsDir, "tab_999.jpg").apply { writeText("drop-thumb") }

        try {
            com.example.ui.BrowserViewModel.cleanBrowserDiskCaches(context, activeTabIds = setOf(101L))

            assertFalse(webHttpCache.exists())
            assertFalse(webCodeCache.exists())
            assertFalse(swCache.exists())
            assertFalse(tempAppCache.exists())
            assertFalse(closedPreview.exists())
            assertTrue("Cookies must remain intact across cache clean", cookiesFile.exists())
            assertTrue("Active tab preview must remain intact", openPreview.exists())
        } finally {
            File(dataRoot, "app_webview").deleteRecursively()
            tempAppCache.delete()
            previewsDir.deleteRecursively()
        }
    }

    @Test
    fun `background renderer reclaim always auto-recovers without leaving blank error banner`() {
        val app = ApplicationProvider.getApplicationContext<WeblioApplication>()
        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
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
}
