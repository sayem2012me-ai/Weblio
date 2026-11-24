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
    fun `app name matches WEBLIO branding`() {
        assertEquals("WEBLIO", context.getString(R.string.app_name))
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
    fun `encrypted backup and restore preserves bookmarks rules and passwords`() = runTest {
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
}
