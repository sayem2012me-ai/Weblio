package com.example.security

import com.example.data.WeblioRepository
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Streaming, bounded-memory Scan-Before-Download Security Scanner.
 * - Uses 16KB chunked streaming for SHA-256 hashing and payload inspection (safe for large files).
 * - Inspects file magic-byte signatures, MIME/extension mismatches, double extensions, known malware hashes
 *   (including EICAR test signatures), ZIP archives (Zip-Slip / Zip-Bomb / nested binaries),
 *   APK structure, PDF active scripts, scripts/executables, corrupted/partial files.
 * - Never claims 100% malware immunity; SAFE verdict strictly uses "No known threats detected."
 */
class DownloadSecurityScanner(
    private val repository: WeblioRepository? = null
) {

    suspend fun scanQuarantinedFile(
        file: File,
        fileName: String,
        declaredMime: String,
        expectedBytes: Long
    ): DownloadScanReport {
        if (!file.exists() || !file.isFile) {
            return DownloadScanReport(
                verdict = DownloadVerdict.UNVERIFIED,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = "Missing file",
                sha256 = "",
                actualBytes = 0L,
                expectedBytes = expectedBytes,
                threatCategory = "Incomplete Download",
                detectionReason = "Quarantined file is missing or inaccessible.",
                recommendation = "Cancel and retry the download.",
                couldNotFullyVerify = true
            )
        }

        val actualBytes = file.length()
        if (actualBytes == 0L) {
            return DownloadScanReport(
                verdict = DownloadVerdict.SUSPICIOUS,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = "Empty File (0 bytes)",
                sha256 = "",
                actualBytes = 0L,
                expectedBytes = expectedBytes,
                threatCategory = "Corrupted / Empty File",
                detectionReason = "Downloaded file contains 0 bytes and appears incomplete or corrupted.",
                recommendation = "Cancel this download and verify the source.",
                couldNotFullyVerify = true
            )
        }

        // Partial download mismatch check
        if (expectedBytes > 0L && actualBytes != expectedBytes) {
            return DownloadScanReport(
                verdict = DownloadVerdict.SUSPICIOUS,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = "Partial File",
                sha256 = "",
                actualBytes = actualBytes,
                expectedBytes = expectedBytes,
                threatCategory = "Partial / Interrupted Download",
                detectionReason = "Expected $expectedBytes bytes, received $actualBytes bytes. File may be corrupted or truncated.",
                recommendation = "Cancel and retry so incomplete data is not used.",
                couldNotFullyVerify = true
            )
        }

        // Streamed hash + header + bounded payload inspection
        val digest = MessageDigest.getInstance("SHA-256")
        val headerBuf = ByteArray(1024)
        var headerLen = 0
        var hasEicarSignature = false
        var hasSuspiciousScriptPayload = false
        var hasPdfActiveAction = false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        var earlyMagic: MagicResult? = null
        var scanTextWindow = false
        var scanScriptRegex = false
        var scanPdfActions = false

        var tailOverlap = ""
        BufferedInputStream(FileInputStream(file), CHUNK_SIZE).use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                if (headerLen < headerBuf.size) {
                    val toCopy = minOf(read, headerBuf.size - headerLen)
                    System.arraycopy(buffer, 0, headerBuf, headerLen, toCopy)
                    headerLen += toCopy
                    if (headerLen >= 16 || read < CHUNK_SIZE) {
                        val hdr = headerBuf.copyOf(headerLen)
                        val m = detectMagicType(hdr, fileName)
                        earlyMagic = m
                        val isBinaryMediaOrArchive = m.kind in BINARY_MEDIA_OR_ARCHIVE_KINDS
                        scanTextWindow = !isBinaryMediaOrArchive && actualBytes <= MAX_TEXT_SCAN_BYTES
                        scanScriptRegex = scanTextWindow && ext !in SAFE_CODE_EXTENSIONS && m.kind != MagicKind.PDF
                        scanPdfActions = (ext == "pdf" || m.kind == MagicKind.PDF) && actualBytes <= MAX_PDF_ACTION_SCAN_BYTES
                    }
                }

                // Only run string/regex scans on bounded non-media files (never peg CPU on MP4/JPG/MP3 streams)
                if (scanTextWindow || scanPdfActions || actualBytes <= MAX_EICAR_SCAN_BYTES) {
                    val chunkText = tailOverlap + String(buffer, 0, read, Charsets.ISO_8859_1)
                    if (actualBytes <= MAX_EICAR_SCAN_BYTES && chunkText.contains(EICAR_TEST_SIGNATURE)) {
                        hasEicarSignature = true
                    }
                    if (scanScriptRegex && !hasSuspiciousScriptPayload && SUSPICIOUS_SCRIPT_PATTERNS.any { it.containsMatchIn(chunkText) }) {
                        hasSuspiciousScriptPayload = true
                    }
                    if (scanPdfActions && !hasPdfActiveAction && PDF_DANGEROUS_ACTIONS.any { chunkText.contains(it) }) {
                        hasPdfActiveAction = true
                    }
                    tailOverlap = if (chunkText.length > 128) chunkText.takeLast(128) else chunkText
                }
            }
        }

        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val header = headerBuf.copyOf(headerLen)
        val detectedMagic = earlyMagic ?: detectMagicType(header, fileName)
        val findings = mutableListOf<String>()

        // 1. Known Malware Hash Database (EICAR + bundled + Room cached hashes)
        val knownThreat = KNOWN_MALWARE_HASHES[sha256]
            ?: repository?.findThreatIndicator(sha256, "HASH_SHA256")?.let { "${it.category} (${it.source})" }
        if (knownThreat != null || hasEicarSignature) {
            return DownloadScanReport(
                verdict = DownloadVerdict.DANGEROUS,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = detectedMagic.label,
                sha256 = sha256,
                actualBytes = actualBytes,
                expectedBytes = expectedBytes,
                threatCategory = if (hasEicarSignature && knownThreat == null) "EICAR Antivirus Test Payload" else "Known Malware Hash",
                detectionReason = "File signature/SHA-256 ($sha256) matched known threat: ${knownThreat ?: "EICAR-Standard-AntiVirus-Test-File"}.",
                recommendation = "Cancel this download immediately. Do not open or execute this file.",
                findings = listOf("SHA-256 threat match", "Quarantined before disk exposure")
            )
        }

        // 2. Double Extension Check (e.g. invoice.pdf.exe, photo.jpg.apk, doc.txt.bat)
        val doubleExt = detectDoubleExtension(fileName)
        if (doubleExt != null) {
            return DownloadScanReport(
                verdict = DownloadVerdict.DANGEROUS,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = detectedMagic.label,
                sha256 = sha256,
                actualBytes = actualBytes,
                expectedBytes = expectedBytes,
                threatCategory = "Double Extension Masquerading",
                detectionReason = "Filename '$fileName' disguises an executable/package extension ($doubleExt).",
                recommendation = "Cancel download. Attackers use double extensions to trick users into running malware.",
                findings = listOf("Detected disguised extension: $doubleExt")
            )
        }

        // 3. Executable binary magic-byte detection (PE/MZ, ELF, Mach-O, Windows/Shell scripts)
        if (detectedMagic.kind == MagicKind.PE_EXE || detectedMagic.kind == MagicKind.ELF || detectedMagic.kind == MagicKind.MACHO) {
            return DownloadScanReport(
                verdict = DownloadVerdict.DANGEROUS,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = detectedMagic.label,
                sha256 = sha256,
                actualBytes = actualBytes,
                expectedBytes = expectedBytes,
                threatCategory = "Native Executable Binary",
                detectionReason = "File contains a compiled executable header (${detectedMagic.label}).",
                recommendation = "Cancel unless you explicitly trust the publisher and cryptographic signature.",
                findings = listOf("Binary magic signature: ${detectedMagic.label}")
            )
        }

        // 4. Extension vs Actual Magic Signature Mismatch
        val mismatchReason = detectTypeMismatch(ext, declaredMime, detectedMagic)
        if (mismatchReason != null) {
            return DownloadScanReport(
                verdict = DownloadVerdict.SUSPICIOUS,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = detectedMagic.label,
                sha256 = sha256,
                actualBytes = actualBytes,
                expectedBytes = expectedBytes,
                threatCategory = "File Type / MIME Mismatch",
                detectionReason = mismatchReason,
                recommendation = "Do not open this file unless you verified its true format with the sender.",
                findings = listOf(mismatchReason)
            )
        }

        // 5. ZIP / APK Inspection (Zip-Slip, Zip-Bomb, nested executables, APK manifest/dex check)
        if (detectedMagic.kind == MagicKind.ZIP || ext == "zip" || ext == "apk") {
            val archiveIssue = inspectZipOrApk(file, isApkExpected = ext == "apk" || declaredMime.contains("package-archive"), findings = findings)
            if (archiveIssue != null) return archiveIssue.copy(
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = detectedMagic.label,
                sha256 = sha256,
                actualBytes = actualBytes,
                expectedBytes = expectedBytes
            )
            if (ext == "apk" || declaredMime.contains("package-archive")) {
                return DownloadScanReport(
                    verdict = DownloadVerdict.SUSPICIOUS,
                    fileName = fileName,
                    declaredMime = declaredMime,
                    detectedTypeLabel = "Android Package (APK)",
                    sha256 = sha256,
                    actualBytes = actualBytes,
                    expectedBytes = expectedBytes,
                    threatCategory = "Unverified Android Installer (APK)",
                    detectionReason = "APK archive parsed cleanly, but sideloaded APKs bypass Play Store verification and may request sensitive permissions.",
                    recommendation = "Install only if obtained from an official, verified developer source.",
                    findings = findings + "APK structure intact (AndroidManifest.xml & classes.dex checked)"
                )
            }
        }

        // 6. PDF validation (header + embedded JS/Launch actions)
        if (ext == "pdf" || detectedMagic.kind == MagicKind.PDF) {
            if (detectedMagic.kind != MagicKind.PDF) {
                return DownloadScanReport(
                    verdict = DownloadVerdict.SUSPICIOUS,
                    fileName = fileName,
                    declaredMime = declaredMime,
                    detectedTypeLabel = detectedMagic.label,
                    sha256 = sha256,
                    actualBytes = actualBytes,
                    expectedBytes = expectedBytes,
                    threatCategory = "Corrupted or Disguised PDF",
                    detectionReason = "File is named .pdf but lacks a valid %PDF header.",
                    recommendation = "Cancel and obtain a valid document.",
                    findings = listOf("Missing %PDF- magic header")
                )
            }
            if (hasPdfActiveAction) {
                return DownloadScanReport(
                    verdict = DownloadVerdict.SUSPICIOUS,
                    fileName = fileName,
                    declaredMime = declaredMime,
                    detectedTypeLabel = "PDF Document (Active Script)",
                    sha256 = sha256,
                    actualBytes = actualBytes,
                    expectedBytes = expectedBytes,
                    threatCategory = "Active PDF Script / Launch Action",
                    detectionReason = "PDF contains embedded /JavaScript, /OpenAction, or /Launch triggers.",
                    recommendation = "Open only in a sandboxed PDF viewer without JavaScript execution.",
                    findings = listOf("Detected active PDF action keywords")
                )
            }
        }

        // 7. Script extensions or suspicious script payloads inside non-source files
        if (ext in DANGEROUS_EXTENSIONS || (hasSuspiciousScriptPayload && ext !in SAFE_CODE_EXTENSIONS)) {
            return DownloadScanReport(
                verdict = DownloadVerdict.SUSPICIOUS,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = detectedMagic.label,
                sha256 = sha256,
                actualBytes = actualBytes,
                expectedBytes = expectedBytes,
                threatCategory = "Suspicious Script / Executable Payload",
                detectionReason = if (ext in DANGEROUS_EXTENSIONS) {
                    "File uses executable/script extension '.$ext'."
                } else {
                    "Detected potentially obfuscated or shell execution payload inside file."
                },
                recommendation = "Inspect with a plain-text viewer or cancel download.",
                findings = listOf("Script / execution indicator detected")
            )
        }

        // 8. Unsupported / uninspectable binary formats
        val couldNotFullyVerify = detectedMagic.kind == MagicKind.UNKNOWN && ext !in KNOWN_TEXT_EXTENSIONS
        if (couldNotFullyVerify) {
            findings.add("Format '.$ext' could not be fully verified beyond SHA-256 reputation.")
            return DownloadScanReport(
                verdict = DownloadVerdict.UNVERIFIED,
                fileName = fileName,
                declaredMime = declaredMime,
                detectedTypeLabel = "Unrecognized Binary",
                sha256 = sha256,
                actualBytes = actualBytes,
                expectedBytes = expectedBytes,
                threatCategory = "Uninspectable Format",
                detectionReason = "This file format could not be fully verified locally; no known threat hash matched.",
                recommendation = "Proceed only if you trust the source website.",
                findings = findings,
                couldNotFullyVerify = true
            )
        }

        return DownloadScanReport(
            verdict = DownloadVerdict.SAFE,
            fileName = fileName,
            declaredMime = declaredMime,
            detectedTypeLabel = detectedMagic.label,
            sha256 = sha256,
            actualBytes = actualBytes,
            expectedBytes = expectedBytes,
            threatCategory = null,
            detectionReason = "No known threats detected.",
            recommendation = "Safe to open from Weblo Downloads.",
            findings = findings + listOf(
                "File signature verified: ${detectedMagic.label}",
                "SHA-256: ${sha256.take(16)}..."
            ),
            couldNotFullyVerify = false
        )
    }

    private fun inspectZipOrApk(
        file: File,
        isApkExpected: Boolean,
        findings: MutableList<String>
    ): DownloadScanReport? {
        var entryCount = 0
        var totalUncompressed = 0L
        var hasManifest = false
        var hasClassesDex = false
        val dangerousNested = mutableListOf<String>()

        val zipErr = runCatching {
            ZipInputStream(BufferedInputStream(FileInputStream(file), CHUNK_SIZE)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        return DownloadScanReport(
                            verdict = DownloadVerdict.DANGEROUS,
                            fileName = file.name,
                            declaredMime = "",
                            detectedTypeLabel = "Archive",
                            sha256 = "",
                            actualBytes = file.length(),
                            expectedBytes = file.length(),
                            threatCategory = "Zip-Slip Path Traversal",
                            detectionReason = "Archive contains path-traversal entry '$name'.",
                            recommendation = "Cancel download immediately. Do not extract this archive."
                        )
                    }
                    val lower = name.lowercase()
                    if (lower == "androidmanifest.xml") hasManifest = true
                    if (lower == "classes.dex") hasClassesDex = true
                    if (!isApkExpected && DANGEROUS_EXTENSIONS.any { lower.endsWith(".$it") }) {
                        dangerousNested.add(name)
                    }
                    if (entry.size > 0) totalUncompressed += entry.size
                    if (entryCount > MAX_ZIP_ENTRIES || totalUncompressed > MAX_ZIP_UNCOMPRESSED_BYTES) {
                        return DownloadScanReport(
                            verdict = DownloadVerdict.DANGEROUS,
                            fileName = file.name,
                            declaredMime = "",
                            detectedTypeLabel = "Archive",
                            sha256 = "",
                            actualBytes = file.length(),
                            expectedBytes = file.length(),
                            threatCategory = "Decompression Bomb Risk",
                            detectionReason = "Archive exceeds safe entry count or uncompressed size limit.",
                            recommendation = "Cancel download to prevent storage exhaustion."
                        )
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.exceptionOrNull()

        if (zipErr != null || entryCount == 0) {
            return DownloadScanReport(
                verdict = DownloadVerdict.SUSPICIOUS,
                fileName = file.name,
                declaredMime = "",
                detectedTypeLabel = "Corrupted Archive",
                sha256 = "",
                actualBytes = file.length(),
                expectedBytes = file.length(),
                threatCategory = "Corrupted ZIP / APK Archive",
                detectionReason = "Archive could not be parsed cleanly (${zipErr?.message ?: "empty archive"}).",
                recommendation = "Cancel and re-download the archive."
            )
        }

        if (isApkExpected && (!hasManifest || !hasClassesDex)) {
            return DownloadScanReport(
                verdict = DownloadVerdict.DANGEROUS,
                fileName = file.name,
                declaredMime = "",
                detectedTypeLabel = "Malformed APK",
                sha256 = "",
                actualBytes = file.length(),
                expectedBytes = file.length(),
                threatCategory = "Invalid / Tampered APK Package",
                detectionReason = "APK package is missing AndroidManifest.xml or classes.dex.",
                recommendation = "Cancel download; this package appears corrupted or tampered."
            )
        }

        if (dangerousNested.isNotEmpty()) {
            return DownloadScanReport(
                verdict = DownloadVerdict.SUSPICIOUS,
                fileName = file.name,
                declaredMime = "",
                detectedTypeLabel = "ZIP Archive",
                sha256 = "",
                actualBytes = file.length(),
                expectedBytes = file.length(),
                threatCategory = "Executable Content Inside Archive",
                detectionReason = "ZIP contains executable or script entries: ${dangerousNested.take(3).joinToString(", ")}.",
                recommendation = "Verify archive contents before opening or extracting."
            )
        }

        findings.add("Inspected $entryCount archive entries cleanly")
        return null
    }

    enum class MagicKind {
        PDF, ZIP, JPEG, PNG, GIF, WEBP, MP4, MP3, OGG, WAV, PE_EXE, ELF, MACHO, SCRIPT_SHEBANG, TEXT, UNKNOWN
    }

    data class MagicResult(val kind: MagicKind, val label: String)

    companion object {
        private const val CHUNK_SIZE = 64 * 1024
        private const val MAX_ZIP_ENTRIES = 5000
        private const val MAX_ZIP_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
        private const val MAX_TEXT_SCAN_BYTES = 10L * 1024 * 1024
        private const val MAX_EICAR_SCAN_BYTES = 1024L * 1024
        private const val MAX_PDF_ACTION_SCAN_BYTES = 25L * 1024 * 1024
        private val BINARY_MEDIA_OR_ARCHIVE_KINDS = setOf(
            MagicKind.JPEG,
            MagicKind.PNG,
            MagicKind.GIF,
            MagicKind.WEBP,
            MagicKind.MP4,
            MagicKind.MP3,
            MagicKind.OGG,
            MagicKind.WAV,
            MagicKind.ZIP
        )

        // Standard non-malicious EICAR antivirus test string & its SHA-256
        const val EICAR_TEST_SIGNATURE =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"
        const val EICAR_SHA256 =
            "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f"

        val KNOWN_MALWARE_HASHES: Map<String, String> = mapOf(
            EICAR_SHA256 to "EICAR-Standard-AntiVirus-Test-File"
        )

        val DANGEROUS_EXTENSIONS = setOf(
            "exe", "bat", "cmd", "com", "scr", "pif", "vbs", "vbe", "ps1", "psm1",
            "msi", "msp", "hta", "cpl", "jar", "sh", "elf", "bin", "so", "dll"
        )

        private val SAFE_CODE_EXTENSIONS = setOf("txt", "json", "md", "csv", "css", "html", "htm")
        private val KNOWN_TEXT_EXTENSIONS = setOf("txt", "csv", "json", "xml", "md", "log", "html", "htm", "svg")

        private val SUSPICIOUS_SCRIPT_PATTERNS = listOf(
            Regex("eval\\s*\\(\\s*atob\\s*\\(", RegexOption.IGNORE_CASE),
            Regex("powershell\\s+-(enc|encodedcommand|w\\s+hidden)", RegexOption.IGNORE_CASE),
            Regex("WScript\\.Shell", RegexOption.IGNORE_CASE),
            Regex("curl\\s+[^|]+\\|\\s*(ba)?sh", RegexOption.IGNORE_CASE)
        )

        private val PDF_DANGEROUS_ACTIONS = listOf("/JavaScript", "/JS ", "/Launch", "/OpenAction")

        fun detectDoubleExtension(fileName: String): String? {
            val parts = fileName.trim().lowercase().split('.').filter { it.isNotBlank() }
            if (parts.size < 3) return null
            val lastExt = parts.last()
            val prevExt = parts[parts.size - 2]
            val lureExtensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "jpg", "jpeg", "png", "txt", "zip", "mp3", "mp4")
            val dangerousTargets = DANGEROUS_EXTENSIONS + "apk"
            if (prevExt in lureExtensions && lastExt in dangerousTargets) {
                return ".$prevExt.$lastExt"
            }
            return null
        }

        fun detectMagicType(header: ByteArray, fileName: String): MagicResult {
            if (header.size >= 4) {
                if (header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && header[2] == 0x44.toByte() && header[3] == 0x46.toByte()) {
                    return MagicResult(MagicKind.PDF, "PDF Document (%PDF)")
                }
                if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
                ) {
                    return MagicResult(MagicKind.ZIP, "ZIP / OOXML / APK Container (PK)")
                }
                if (header[0] == 0x7F.toByte() && header[1] == 0x45.toByte() && header[2] == 0x4C.toByte() && header[3] == 0x46.toByte()) {
                    return MagicResult(MagicKind.ELF, "Linux / Android ELF Binary")
                }
                if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
                    return MagicResult(MagicKind.PNG, "PNG Image")
                }
                if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte()) {
                    return MagicResult(MagicKind.GIF, "GIF Image")
                }
                if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte()) {
                    val tag = if (header.size >= 12) String(header, 8, 4, Charsets.US_ASCII) else ""
                    return if (tag == "WEBP") MagicResult(MagicKind.WEBP, "WebP Image") else MagicResult(MagicKind.WAV, "WAV Audio")
                }
                if (header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
                ) {
                    return MagicResult(MagicKind.MP4, "MP4 / ISO Media Video")
                }
                if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() && header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) {
                    return MagicResult(MagicKind.OGG, "OGG Audio")
                }
                if ((header[0] == 0xFE.toByte() && header[1] == 0xED.toByte() && header[2] == 0xFA.toByte()) ||
                    (header[0] == 0xCF.toByte() && header[1] == 0xFA.toByte() && header[2] == 0xED.toByte())
                ) {
                    return MagicResult(MagicKind.MACHO, "Mach-O Executable")
                }
            }
            if (header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                return MagicResult(MagicKind.JPEG, "JPEG Image")
            }
            if (header.size >= 2 && header[0] == 0x4D.toByte() && header[1] == 0x5A.toByte()) {
                return MagicResult(MagicKind.PE_EXE, "Windows PE/MZ Executable")
            }
            if (header.size >= 3 && (String(header, 0, 3, Charsets.US_ASCII) == "ID3" ||
                    (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0))
            ) {
                return MagicResult(MagicKind.MP3, "MP3 Audio")
            }
            if (header.size >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) {
                return MagicResult(MagicKind.SCRIPT_SHEBANG, "Shell Script (#!)")
            }

            // Text readability check
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (header.isNotEmpty() && ext in KNOWN_TEXT_EXTENSIONS) {
                val printable = header.count { b ->
                    val u = b.toInt() and 0xFF
                    u == 9 || u == 10 || u == 13 || u in 32..126 || u >= 128
                }
                if (printable >= (header.size * 0.85)) {
                    return MagicResult(MagicKind.TEXT, "UTF-8 Text / Markup")
                }
            }
            return MagicResult(MagicKind.UNKNOWN, "Binary / Custom Format")
        }

        fun detectTypeMismatch(ext: String, declaredMime: String, magic: MagicResult): String? {
            if (magic.kind == MagicKind.SCRIPT_SHEBANG && ext in setOf("jpg", "jpeg", "png", "pdf", "mp3", "mp4")) {
                return "File claims to be '.$ext' ($declaredMime) but starts with an executable shell script header (#!)."
            }
            if (ext in setOf("jpg", "jpeg", "png", "gif", "webp")) {
                val validImageMagic = magic.kind in setOf(MagicKind.JPEG, MagicKind.PNG, MagicKind.GIF, MagicKind.WEBP)
                if (!validImageMagic && magic.kind != MagicKind.UNKNOWN) {
                    return "Image extension '.$ext' does not match detected file signature (${magic.label})."
                }
                if (ext == "png" && magic.kind == MagicKind.JPEG) {
                    return "File extension is .png but header is JPEG."
                }
            }
            if (ext == "zip" && magic.kind != MagicKind.ZIP) {
                return "File extension is .zip but lacks PK ZIP container header (${magic.label})."
            }
            if (declaredMime.startsWith("image/") && magic.kind == MagicKind.ZIP) {
                return "Declared MIME '$declaredMime' disguises a ZIP/APK archive."
            }
            return null
        }
    }
}
