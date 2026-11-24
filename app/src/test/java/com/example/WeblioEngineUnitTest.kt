package com.example

import com.example.data.prefs.SearchEngine
import com.example.data.prefs.websiteFaviconUrl
import com.example.data.security.KeystoreCryptoManager
import com.example.engine.AdBlockEngine
import com.example.engine.ExtensionEngine
import com.example.engine.FilterUpdateWorker
import com.example.engine.WeblioDnsVpnService
import com.example.engine.WeblioDownloadManager
import com.example.data.db.DownloadFileCategory
import com.example.data.db.FilterRuleType
import com.example.ui.BrowserViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WeblioEngineUnitTest {

    @Test
    fun `url resolver handles domains and search queries accurately`() {
        assertEquals("weblio://home", BrowserViewModel.resolveInputToUrl("", SearchEngine.GOOGLE))
        assertEquals("https://example.com", BrowserViewModel.resolveInputToUrl("example.com", SearchEngine.GOOGLE))
        assertEquals("http://localhost:8080", BrowserViewModel.resolveInputToUrl("http://localhost:8080", SearchEngine.GOOGLE))
        assertEquals(
            "https://duckduckgo.com/?q=weblio%20browser",
            BrowserViewModel.resolveInputToUrl("weblio browser", SearchEngine.DUCKDUCKGO)
        )
    }

    @Test
    fun `adblock pattern matcher blocks domains and wildcards`() {
        assertTrue(AdBlockEngine.matchesAdBlockPattern("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "pagead2.googlesyndication.com", "||googlesyndication.com^"))
        assertFalse(AdBlockEngine.matchesAdBlockPattern("https://wikipedia.org/wiki/Main", "wikipedia.org", "||googlesyndication.com^"))
        assertTrue(AdBlockEngine.matchesAdBlockPattern("https://cdn.example.com/ads/banner.js", "cdn.example.com", "*/ads/*"))
    }

    @Test
    fun `filter list parser extracts network allowlist cosmetic and hosts rules`() {
        val sample = """
            ! Comment line
            ||ads.tracker.net^
            @@||safe.tracker.net^
            ##.sponsored-box
            0.0.0.0 telemetry.vendor.io
        """.trimIndent()

        val parsed = FilterUpdateWorker.parseFilterList(sample, "TestList")
        assertEquals(4, parsed.size)
        assertEquals(FilterRuleType.NETWORK_BLOCK.name, parsed[0].ruleType)
        assertEquals(FilterRuleType.NETWORK_ALLOW.name, parsed[1].ruleType)
        assertEquals(FilterRuleType.COSMETIC_HIDE.name, parsed[2].ruleType)
        assertEquals(FilterRuleType.DNS_BLOCK.name, parsed[3].ruleType)
        assertEquals("telemetry.vendor.io", parsed[3].pattern)
    }

    @Test
    fun `pin hashing and verification validates matching pin and rejects mismatch`() {
        val (hash, salt) = KeystoreCryptoManager.hashPin("4829")
        assertTrue(KeystoreCryptoManager.verifyPin("4829", hash, salt))
        assertFalse(KeystoreCryptoManager.verifyPin("0000", hash, salt))
    }

    @Test
    fun `password generator produces high entropy passwords of requested length`() {
        val pw = KeystoreCryptoManager.generateStrongPassword(length = 20)
        assertEquals(20, pw.length)
    }

    @Test
    fun `crx3 header stripper extracts zip payload`() {
        val zipBody = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 1, 2)
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("Cr24".toByteArray(Charsets.US_ASCII))
            .putInt(3) // CRX3
            .putInt(4) // header size
            .putInt(0x11223344)
            .array()
        val combined = header + zipBody
        val extracted = ExtensionEngine.stripCrxHeader(combined)
        assertNotNull(extracted)
        assertEquals('P'.code.toByte(), extracted!![0])
        assertEquals('K'.code.toByte(), extracted[1])
        assertNull(ExtensionEngine.stripCrxHeader(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun `dns packet parser reads qname and builds nxdomain reply`() {
        // Minimal DNS query for "ads.example.com"
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            3, 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0,
            0x00, 0x01, 0x00, 0x01
        )
        assertEquals("ads.example.com", WeblioDnsVpnService.parseDnsQName(query))
        val nx = WeblioDnsVpnService.buildNxDomainResponse(query)
        assertEquals(3, nx[3].toInt() and 0x0F) // RCODE = 3 (NXDOMAIN)
    }

    @Test
    fun `download file category detection identifies apk pdf zip and media`() {
        assertEquals(DownloadFileCategory.APK, WeblioDownloadManager.detectCategory("app.apk", "application/vnd.android.package-archive"))
        assertEquals(DownloadFileCategory.PDF, WeblioDownloadManager.detectCategory("report.pdf", "application/pdf"))
        assertEquals(DownloadFileCategory.ARCHIVE, WeblioDownloadManager.detectCategory("ext.zip", "application/zip"))
        assertEquals(DownloadFileCategory.VIDEO, WeblioDownloadManager.detectCategory("clip.mp4", "video/mp4"))
    }

    @Test
    fun `online logo url resolver maps search engines and home websites to canonical favicon urls`() {
        assertEquals("https://www.google.com/s2/favicons?domain=google.com&sz=128", SearchEngine.GOOGLE.logoUrl())
        assertEquals("https://www.google.com/s2/favicons?domain=brave.com&sz=128", SearchEngine.BRAVE.logoUrl())
        assertEquals("https://www.google.com/s2/favicons?domain=youtube.com&sz=128", websiteFaviconUrl("https://m.youtube.com"))
        assertEquals("https://www.google.com/s2/favicons?domain=wikipedia.org&sz=128", websiteFaviconUrl("https://www.wikipedia.org"))
        assertEquals("https://www.google.com/s2/favicons?domain=github.com&sz=128", websiteFaviconUrl("github.com"))
    }
}
