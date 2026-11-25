package com.example.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.WeblioRepository
import com.example.data.db.DownloadEntity
import com.example.data.db.DownloadFileCategory
import com.example.data.db.DownloadStatus
import com.example.security.DownloadQuarantineManager
import com.example.security.DownloadScanReport
import com.example.security.DownloadSecurityScanner
import com.example.security.DownloadVerdict
import com.example.security.SecurityNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class PendingQuarantineReview(
    val download: DownloadEntity,
    val report: DownloadScanReport
)

data class ApkInstallInfo(
    val fileName: String,
    val fileSizeBytes: Long,
    val appLabel: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val securityNotice: String,
    val appIcon: Bitmap? = null
)

class WeblioDownloadManager(
    private val context: Context,
    private val repository: WeblioRepository,
    val quarantineManager: DownloadQuarantineManager = DownloadQuarantineManager(context),
    val scanner: DownloadSecurityScanner = DownloadSecurityScanner(repository)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    var securityNotificationsEnabled: Boolean = true

    private val _pendingReviews = MutableStateFlow<List<PendingQuarantineReview>>(emptyList())
    val pendingReviews: StateFlow<List<PendingQuarantineReview>> = _pendingReviews.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadsDir: File
        get() = quarantineManager.downloadsDir

    fun cleanupAbandonedQuarantine(activeQuarantinePaths: Set<String> = emptySet()) {
        scope.launch {
            quarantineManager.cleanupAbandonedFiles(
                maxAgeMillis = 24 * 60 * 60 * 1000L,
                activePaths = activeQuarantinePaths
            )
        }
    }

    @Volatile
    private var notificationChannelCreated = false
    private val lastNotifiedProgressPct = ConcurrentHashMap<Int, Int>()

    fun enqueueDownload(
        profileId: Long,
        url: String,
        suggestedFileName: String?,
        mimeType: String?,
        userAgent: String? = null,
        showNotification: Boolean = true
    ) {
        scope.launch {
            if (url.startsWith("data:", ignoreCase = true)) {
                handleDataUrlDownload(profileId, url, suggestedFileName, mimeType, showNotification)
                return@launch
            }
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return@launch
            }
            val fileName = sanitizeFileName(suggestedFileName ?: guessFileName(url, mimeType))
            val resolvedMime = mimeType?.takeIf { it.isNotBlank() && !it.equals("application/octet-stream", true) }
                ?: guessMime(fileName)
            val category = detectCategory(fileName, resolvedMime)
            val processKey = "dl_${profileId}_${url}_$fileName"
            if (!quarantineManager.tryAcquireProcessKey(processKey)) {
                return@launch
            }

            // Insert initial queued entity with temporary placeholder path inside private quarantine
            val draftEntity = DownloadEntity(
                profileId = profileId,
                url = url,
                fileName = fileName,
                mimeType = resolvedMime,
                category = category.name,
                localPath = File(quarantineManager.quarantineDir, "pending_${System.currentTimeMillis()}").absolutePath,
                status = DownloadStatus.QUEUED.name,
                quarantined = true
            )
            val id = repository.insertDownload(draftEntity)
            val qFile = quarantineManager.allocateQuarantineFile(id, fileName)
            repository.updateDownload(draftEntity.copy(id = id, localPath = qFile.absolutePath))
            startOrResumeJob(id, userAgent, showNotification, processKey)
        }
    }

    private suspend fun handleDataUrlDownload(
        profileId: Long,
        dataUrl: String,
        suggestedFileName: String?,
        mimeType: String?,
        showNotification: Boolean
    ) {
        val commaIdx = dataUrl.indexOf(',')
        if (commaIdx <= 5) return
        val meta = dataUrl.substring(5, commaIdx)
        val isBase64 = meta.endsWith(";base64", ignoreCase = true) || meta.contains(";base64;", ignoreCase = true)
        val headerMime = meta.substringBefore(';').trim().takeIf { it.contains('/') }
        val resolvedMime = headerMime ?: mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime) ?: "bin"
        val rawName = suggestedFileName?.takeIf { it.isNotBlank() && it != "download" }
            ?: "weblo_media_${System.currentTimeMillis()}.$ext"
        val fileName = sanitizeFileName(rawName)
        val category = detectCategory(fileName, resolvedMime)

        val payload = dataUrl.substring(commaIdx + 1)
        val bytes = runCatching {
            if (isBase64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }.getOrNull() ?: return

        val draftEntity = DownloadEntity(
            profileId = profileId,
            url = "data:$resolvedMime",
            fileName = fileName,
            mimeType = resolvedMime,
            category = category.name,
            localPath = File(quarantineManager.quarantineDir, "pending_${System.currentTimeMillis()}").absolutePath,
            totalBytes = bytes.size.toLong(),
            downloadedBytes = bytes.size.toLong(),
            status = DownloadStatus.SCANNING.name,
            quarantined = true
        )
        val id = repository.insertDownload(draftEntity)
        val qFile = quarantineManager.allocateQuarantineFile(id, fileName)
        runCatching {
            qFile.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(qFile), IO_BUFFER_SIZE).use { it.write(bytes) }
        }.onFailure { err ->
            repository.updateDownload(
                draftEntity.copy(
                    id = id,
                    localPath = qFile.absolutePath,
                    status = DownloadStatus.FAILED.name,
                    errorMessage = err.message ?: "Unable to save data payload",
                    quarantined = false
                )
            )
            return
        }

        val scanningItem = draftEntity.copy(id = id, localPath = qFile.absolutePath)
        repository.updateDownload(scanningItem)
        finalizeScannedQuarantineFile(
            scanningItem = scanningItem,
            quarantineFile = qFile,
            expectedBytes = bytes.size.toLong(),
            downloaded = bytes.size.toLong(),
            resolvedMime = resolvedMime,
            resolvedCategory = category,
            showNotification = showNotification
        )
    }

    fun pauseDownload(downloadId: Long) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        scope.launch {
            val current = repository.getDownload(downloadId) ?: return@launch
            if (current.status == DownloadStatus.DOWNLOADING.name || current.status == DownloadStatus.QUEUED.name) {
                repository.updateDownload(current.copy(status = DownloadStatus.PAUSED.name))
            }
        }
    }

    fun resumeOrRetry(downloadId: Long, showNotification: Boolean = true) {
        scope.launch {
            startOrResumeJob(downloadId, null, showNotification, "resume_$downloadId")
        }
    }

    fun cancelDownload(downloadId: Long) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        scope.launch {
            val current = repository.getDownload(downloadId) ?: return@launch
            quarantineManager.secureDelete(File(current.localPath))
            repository.updateDownload(
                current.copy(
                    status = DownloadStatus.CANCELLED.name,
                    downloadedBytes = 0L,
                    securityVerdict = "CANCELLED",
                    quarantined = false
                )
            )
            _pendingReviews.update { list -> list.filterNot { it.download.id == downloadId } }
        }
    }

    suspend fun resolveQuarantinedDownload(downloadId: Long, allowContinue: Boolean): Result<DownloadEntity> {
        val current = repository.getDownload(downloadId)
            ?: return Result.failure(IllegalArgumentException("Download record not found"))
        _pendingReviews.update { list -> list.filterNot { it.download.id == downloadId } }

        val quarantinedFile = File(current.localPath)
        if (!allowContinue) {
            quarantineManager.secureDelete(quarantinedFile)
            val cancelled = current.copy(
                status = DownloadStatus.CANCELLED.name,
                securityVerdict = "CANCELLED",
                quarantined = false,
                downloadedBytes = 0L,
                scanSummary = "Cancelled by user after security warning."
            )
            repository.updateDownload(cancelled)
            repository.recordSecurityEvent(
                category = "DOWNLOAD",
                severity = "INFO",
                title = "Quarantined download cancelled",
                summary = "Deleted ${current.fileName} (${current.securityVerdict}) from private quarantine.",
                targetHostOrFile = current.fileName,
                actionTaken = "Deleted"
            )
            return Result.success(cancelled)
        }

        if (!quarantinedFile.exists()) {
            return Result.failure(IllegalStateException("Quarantined file is no longer available on disk"))
        }
        val destFile = uniqueTargetFile(current.fileName)
        quarantineManager.promoteToDestination(quarantinedFile, destFile).getOrElse {
            return Result.failure(it)
        }
        val category = runCatching { DownloadFileCategory.valueOf(current.category) }
            .getOrElse { detectCategory(destFile.name, current.mimeType) }
        val resolvedMime = current.mimeType.ifBlank { guessMime(destFile.name) }
        val publicUriStr = publishToPublicStorage(destFile, destFile.name, resolvedMime, category)
        val keepInternalCopy = isApkOrHtmlFile(destFile.name, resolvedMime, category) ||
            publicUriStr == null ||
            !verifyPublicUriReadable(publicUriStr)
        if (!keepInternalCopy) {
            runCatching { destFile.delete() }
        }
        // Keep original securityVerdict (SUSPICIOUS/DANGEROUS/UNVERIFIED) & set userOverridden = true; NEVER auto-execute
        val promoted = current.copy(
            fileName = destFile.name,
            localPath = destFile.absolutePath,
            publicUri = publicUriStr,
            status = DownloadStatus.COMPLETED.name,
            userOverridden = true,
            quarantined = false,
            scanSummary = "${current.securityVerdict} (User Overridden — ${current.threatReason ?: "Review caution"})"
        )
        repository.updateDownload(promoted)
        repository.recordSecurityEvent(
            category = "DOWNLOAD",
            severity = "WARNING",
            title = "User overrode download security warning",
            summary = "Released ${promoted.fileName} from quarantine (${promoted.securityVerdict}). Never auto-executed.",
            targetHostOrFile = promoted.fileName,
            actionTaken = "Released (Overridden)"
        )
        return Result.success(promoted)
    }

    fun requestReviewFor(entity: DownloadEntity) {
        val report = DownloadScanReport(
            verdict = runCatching { DownloadVerdict.valueOf(entity.securityVerdict) }.getOrDefault(DownloadVerdict.SUSPICIOUS),
            fileName = entity.fileName,
            declaredMime = entity.mimeType,
            detectedTypeLabel = entity.category,
            sha256 = entity.sha256,
            actualBytes = entity.downloadedBytes,
            expectedBytes = entity.totalBytes,
            threatCategory = entity.threatCategory,
            detectionReason = entity.threatReason ?: "Requires manual user decision before saving to Downloads.",
            recommendation = entity.scanSummary ?: "Verify the source before continuing.",
            couldNotFullyVerify = entity.securityVerdict == DownloadVerdict.UNVERIFIED.name
        )
        _pendingReviews.update { current ->
            if (current.any { it.download.id == entity.id }) current else current + PendingQuarantineReview(entity, report)
        }
    }

    fun dismissActiveReview(downloadId: Long) {
        _pendingReviews.update { list -> list.filterNot { it.download.id == downloadId } }
    }

    fun inspectApkForInstall(entity: DownloadEntity): Result<ApkInstallInfo> {
        if (entity.quarantined || entity.status != DownloadStatus.COMPLETED.name) {
            return Result.failure(IllegalStateException("Resolve quarantine scan before installing this APK"))
        }
        val file = File(entity.localPath)
        if (!quarantineManager.isConfinedWithin(file, downloadsDir) || !file.exists() || !file.isFile || file.length() == 0L) {
            return Result.failure(IllegalStateException("APK file is missing, empty, or outside Downloads storage"))
        }

        var hasManifest = false
        var hasClassesDex = false
        val zipErr = runCatching {
            ZipInputStream(BufferedInputStream(FileInputStream(file), 16 * 1024)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val lower = entry.name.lowercase()
                    if (lower == "androidmanifest.xml") hasManifest = true
                    if (lower == "classes.dex") hasClassesDex = true
                    if (hasManifest && hasClassesDex) break
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.exceptionOrNull()

        if (zipErr != null || !hasManifest || !hasClassesDex) {
            return Result.failure(IllegalArgumentException("Invalid or corrupted APK package (missing AndroidManifest.xml or classes.dex)"))
        }

        val pm = context.packageManager
        val archiveInfo = loadPackageArchiveInfo(pm, file.absolutePath)
        archiveInfo?.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        val label = archiveInfo?.applicationInfo?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        val appIcon = extractApkIconBitmap(context, file.absolutePath)
        val verCode = archiveInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val notice = when {
            entity.securityVerdict == DownloadVerdict.DANGEROUS.name ->
                "WARNING: Flagged DANGEROUS during scan (${entity.threatCategory ?: "High risk"}). Install only if you fully trust this source."
            entity.userOverridden ->
                "Unverified sideload APK (user-approved from quarantine). Review Android system permissions carefully."
            else ->
                "Valid APK structure verified. Android will ask you to confirm system install."
        }

        return Result.success(
            ApkInstallInfo(
                fileName = entity.fileName,
                fileSizeBytes = file.length(),
                appLabel = label,
                packageName = archiveInfo?.packageName,
                versionName = archiveInfo?.versionName,
                versionCode = verCode,
                securityNotice = notice,
                appIcon = appIcon
            )
        )
    }

    fun buildLocalHtmlPreviewUrl(entity: DownloadEntity): String {
        val file = File(entity.localPath).canonicalFile
        val root = downloadsDir.canonicalFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separatorChar
        return if (file.path.startsWith(rootPrefix)) {
            val rel = file.path.removePrefix(rootPrefix).replace(File.separatorChar, '/')
            val encodedSegments = rel.split('/').joinToString("/") { Uri.encode(it) }
            "https://$LOCAL_PREVIEW_HOST/$encodedSegments"
        } else {
            "https://$LOCAL_PREVIEW_HOST/__id/${entity.id}/${Uri.encode(entity.fileName)}"
        }
    }

    fun openDownloadedFile(entity: DownloadEntity): Result<Intent> {
        if (entity.quarantined || entity.status != DownloadStatus.COMPLETED.name) {
            return Result.failure(IllegalStateException("File is quarantined or unverified and cannot be opened"))
        }
        val isApk = entity.fileName.endsWith(".apk", ignoreCase = true) ||
            entity.category == DownloadFileCategory.APK.name
        val resolvedMime = if (isApk) {
            "application/vnd.android.package-archive"
        } else {
            entity.mimeType.ifBlank { guessMime(entity.fileName) }
        }
        val file = File(entity.localPath)
        val hasConfinedLocalFile = quarantineManager.isConfinedWithin(file, downloadsDir) && file.exists()
        if (hasConfinedLocalFile) {
            return runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, resolvedMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        val contentUri = entity.publicUri?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.takeIf { it.scheme == "content" }
        }
        if (contentUri != null && verifyPublicUriReadable(entity.publicUri)) {
            return Result.success(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, resolvedMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        return Result.failure(IllegalStateException("File not found in Downloads storage"))
    }

    fun shareDownloadedFile(entity: DownloadEntity): Intent {
        if (!entity.quarantined && entity.status == DownloadStatus.COMPLETED.name) {
            val contentUri = entity.publicUri?.takeIf { verifyPublicUriReadable(it) }?.let { raw ->
                runCatching { Uri.parse(raw) }.getOrNull()?.takeIf { it.scheme == "content" }
            }
            val file = File(entity.localPath)
            val localUri = if (quarantineManager.isConfinedWithin(file, downloadsDir) && file.exists()) {
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
            } else {
                null
            }
            val shareUri = localUri ?: contentUri
            if (shareUri != null) {
                return Intent(Intent.ACTION_SEND).apply {
                    type = entity.mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_SUBJECT, entity.fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, entity.fileName)
            putExtra(Intent.EXTRA_TEXT, entity.url)
        }
    }

    suspend fun renameDownloadedFile(entity: DownloadEntity, rawNewName: String): Result<DownloadEntity> {
        if (entity.quarantined) {
            return Result.failure(IllegalStateException("Resolve quarantine review before renaming"))
        }
        val trimmed = rawNewName.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("File name cannot be empty"))
        val sanitized = sanitizeFileName(trimmed)
        val oldFile = File(entity.localPath)
        var newFile = File(downloadsDir, sanitized)
        if (!quarantineManager.isConfinedWithin(newFile, downloadsDir)) {
            return Result.failure(IllegalArgumentException("Invalid target filename"))
        }
        if (oldFile.exists()) {
            if (!quarantineManager.isConfinedWithin(oldFile, downloadsDir)) {
                return Result.failure(IllegalStateException("Source file is outside Downloads directory"))
            }
            if (oldFile.canonicalPath != newFile.canonicalPath) {
                if (newFile.exists()) {
                    newFile = uniqueTargetFile(sanitized)
                }
                val renamed = runCatching { oldFile.renameTo(newFile) }.getOrDefault(false)
                if (!renamed) {
                    return Result.failure(IllegalStateException("Unable to rename file on storage"))
                }
            }
        }
        val finalName = if (oldFile.exists()) newFile.name else sanitized
        val resolvedMime = guessMime(finalName)
        entity.publicUri?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                if (uri.scheme == "content") {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                    }
                    context.contentResolver.update(uri, values, null, null)
                }
            }
        }
        val updated = entity.copy(
            fileName = finalName,
            localPath = if (oldFile.exists()) newFile.absolutePath else entity.localPath,
            mimeType = resolvedMime,
            category = detectCategory(finalName, resolvedMime).name
        )
        repository.updateDownload(updated)
        return Result.success(updated)
    }

    suspend fun deleteDownloadedFile(downloadId: Long) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        _pendingReviews.update { list -> list.filterNot { it.download.id == downloadId } }
        val current = repository.getDownload(downloadId)
        if (current != null) {
            quarantineManager.secureDelete(File(current.localPath))
            deletePublicMediaEntry(current.publicUri)
        }
        repository.deleteDownload(downloadId)
    }

    suspend fun clearAllDownloads(profileId: Long, items: List<DownloadEntity>) {
        items.forEach { item ->
            activeJobs[item.id]?.cancel()
            activeJobs.remove(item.id)
            _pendingReviews.update { list -> list.filterNot { it.download.id == item.id } }
            quarantineManager.secureDelete(File(item.localPath))
            deletePublicMediaEntry(item.publicUri)
        }
        repository.clearDownloads(profileId)
    }

    fun getDeviceTotalBytes(): Long {
        return runCatching {
            android.os.StatFs(context.filesDir.absolutePath).totalBytes
        }.getOrDefault(0L)
    }

    private fun startOrResumeJob(
        downloadId: Long,
        userAgent: String?,
        showNotification: Boolean,
        processKey: String
    ) {
        activeJobs[downloadId]?.cancel()
        val job = scope.launch {
            try {
                val item = repository.getDownload(downloadId) ?: return@launch
                if (!item.url.startsWith("http://", ignoreCase = true) &&
                    !item.url.startsWith("https://", ignoreCase = true)
                ) {
                    repository.updateDownload(
                        item.copy(status = DownloadStatus.FAILED.name, errorMessage = "Unsupported URL scheme")
                    )
                    return@launch
                }
                val candidateQuarantine = File(item.localPath)
                val quarantineFile = if (quarantineManager.isConfinedWithin(candidateQuarantine, quarantineManager.quarantineDir)) {
                    candidateQuarantine
                } else {
                    quarantineManager.allocateQuarantineFile(downloadId, sanitizeFileName(item.fileName))
                }
                quarantineFile.parentFile?.mkdirs()

                val existingBytes = if (quarantineFile.exists() && item.supportsRange) quarantineFile.length() else 0L
                val downloadingItem = item.copy(
                    localPath = quarantineFile.absolutePath,
                    status = DownloadStatus.DOWNLOADING.name,
                    quarantined = true,
                    errorMessage = null
                )
                repository.updateDownload(downloadingItem)

                val reqBuilder = Request.Builder().url(item.url)
                if (!userAgent.isNullOrBlank()) reqBuilder.header("User-Agent", userAgent)
                if (existingBytes > 0L) reqBuilder.header("Range", "bytes=$existingBytes-")

                runCatching {
                    client.newCall(reqBuilder.build()).execute().use { response ->
                        val finalScheme = response.request.url.scheme
                        if (!finalScheme.equals("http", ignoreCase = true) &&
                            !finalScheme.equals("https", ignoreCase = true)
                        ) {
                            repository.updateDownload(
                                downloadingItem.copy(
                                    status = DownloadStatus.FAILED.name,
                                    errorMessage = "Blocked redirect to non-HTTP scheme"
                                )
                            )
                            return@launch
                        }
                        if (!response.isSuccessful && response.code != 206) {
                            repository.updateDownload(
                                downloadingItem.copy(
                                    status = DownloadStatus.FAILED.name,
                                    errorMessage = "HTTP ${response.code}"
                                )
                            )
                            return@launch
                        }

                        val body = response.body ?: throw IllegalStateException("Empty response body")
                        val contentDisposition = response.header("Content-Disposition")
                        val headerMime = response.header("Content-Type")?.substringBefore(';')?.trim()
                            ?.takeIf { it.isNotBlank() && !it.equals("application/octet-stream", ignoreCase = true) }
                        val resolvedMime = headerMime ?: downloadingItem.mimeType.ifBlank { guessMime(downloadingItem.fileName) }
                        val currentLooksGeneric = downloadingItem.fileName.endsWith(".bin", ignoreCase = true) ||
                            downloadingItem.fileName.startsWith("weblo_file_", ignoreCase = true) ||
                            downloadingItem.fileName.startsWith("weblio_file_", ignoreCase = true) ||
                            !downloadingItem.fileName.contains('.')
                        val effectiveFileName = if (currentLooksGeneric && (!contentDisposition.isNullOrBlank() || headerMime != null)) {
                            sanitizeFileName(URLUtil.guessFileName(item.url, contentDisposition, resolvedMime))
                        } else {
                            downloadingItem.fileName
                        }
                        val effectiveCategory = detectCategory(effectiveFileName, resolvedMime)
                        val activeDownloadItem = if (effectiveFileName != downloadingItem.fileName || resolvedMime != downloadingItem.mimeType) {
                            downloadingItem.copy(
                                fileName = effectiveFileName,
                                mimeType = resolvedMime,
                                category = effectiveCategory.name
                            ).also { repository.updateDownload(it) }
                        } else {
                            downloadingItem
                        }

                        val isPartial = response.code == 206
                        val acceptRanges = response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true || isPartial
                        val contentLen = body.contentLength()
                        val totalSize = if (isPartial && contentLen > 0) existingBytes + contentLen else contentLen

                        var downloaded = if (isPartial) existingBytes else 0L
                        var lastReportTime = 0L

                        BufferedInputStream(body.byteStream(), IO_BUFFER_SIZE).use { input ->
                            BufferedOutputStream(FileOutputStream(quarantineFile, isPartial), IO_BUFFER_SIZE).use { output ->
                                val buffer = ByteArray(IO_BUFFER_SIZE)
                                while (isActive) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportTime >= PROGRESS_REPORT_INTERVAL_MS) {
                                        lastReportTime = now
                                        repository.updateDownload(
                                            activeDownloadItem.copy(
                                                status = DownloadStatus.DOWNLOADING.name,
                                                downloadedBytes = downloaded,
                                                totalBytes = totalSize,
                                                supportsRange = acceptRanges
                                            )
                                        )
                                        if (showNotification) {
                                            postProgressNotification(downloadId.toInt(), effectiveFileName, downloaded, totalSize, false)
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }

                        if (!isActive) return@launch

                        // Phase 2: Enter Security Scanning inside private quarantine before exposing to Downloads / Gallery / Files
                        val scanningItem = activeDownloadItem.copy(
                            status = DownloadStatus.SCANNING.name,
                            downloadedBytes = downloaded,
                            totalBytes = if (totalSize > 0) totalSize else downloaded,
                            supportsRange = acceptRanges,
                            quarantined = true
                        )
                        repository.updateDownload(scanningItem)

                        finalizeScannedQuarantineFile(
                            scanningItem = scanningItem,
                            quarantineFile = quarantineFile,
                            expectedBytes = totalSize,
                            downloaded = downloaded,
                            resolvedMime = resolvedMime,
                            resolvedCategory = effectiveCategory,
                            showNotification = showNotification
                        )
                    }
                }.onFailure { err ->
                    if (isActive) {
                        lastNotifiedProgressPct.remove(downloadId.toInt())
                        repository.updateDownload(
                            downloadingItem.copy(
                                status = DownloadStatus.FAILED.name,
                                errorMessage = err.message ?: "Network error",
                                securityVerdict = "UNSCANNED"
                            )
                        )
                    }
                }
            } finally {
                activeJobs.remove(downloadId)
                quarantineManager.releaseProcessKey(processKey)
            }
        }
        activeJobs[downloadId] = job
    }

    private suspend fun finalizeScannedQuarantineFile(
        scanningItem: DownloadEntity,
        quarantineFile: File,
        expectedBytes: Long,
        downloaded: Long,
        resolvedMime: String,
        resolvedCategory: DownloadFileCategory,
        showNotification: Boolean
    ) {
        val report = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            scanner.scanQuarantinedFile(
                file = quarantineFile,
                fileName = scanningItem.fileName,
                declaredMime = resolvedMime,
                expectedBytes = expectedBytes
            )
        } ?: DownloadScanReport(
            verdict = DownloadVerdict.UNVERIFIED,
            fileName = scanningItem.fileName,
            declaredMime = resolvedMime,
            detectedTypeLabel = resolvedCategory.name,
            sha256 = "",
            actualBytes = quarantineFile.length(),
            expectedBytes = expectedBytes,
            threatCategory = "Scan Timeout",
            detectionReason = "Security verification exceeded ${SCAN_TIMEOUT_MS / 1000}s limit.",
            recommendation = "Proceed only if you trust the download source.",
            couldNotFullyVerify = true
        )

        // Downgrade SAFE over insecure plain HTTP to SUSPICIOUS if executable/APK/archive
        val finalReport = if (scanningItem.url.startsWith("http://", ignoreCase = true) &&
            report.verdict == DownloadVerdict.SAFE &&
            (resolvedCategory == DownloadFileCategory.APK ||
                resolvedCategory == DownloadFileCategory.ARCHIVE ||
                resolvedCategory == DownloadFileCategory.EXECUTABLE)
        ) {
            report.copy(
                verdict = DownloadVerdict.SUSPICIOUS,
                threatCategory = "Insecure HTTP Transfer",
                detectionReason = "File was downloaded over unencrypted HTTP without transport integrity.",
                recommendation = "Prefer HTTPS downloads whenever available."
            )
        } else {
            report
        }

        if (finalReport.verdict == DownloadVerdict.SAFE) {
            val destFile = uniqueTargetFile(scanningItem.fileName)
            quarantineManager.promoteToDestination(quarantineFile, destFile).getOrThrow()
            val publicUriStr = publishToPublicStorage(destFile, destFile.name, resolvedMime, resolvedCategory)
            val keepInternalCopy = isApkOrHtmlFile(destFile.name, resolvedMime, resolvedCategory) ||
                publicUriStr == null ||
                !verifyPublicUriReadable(publicUriStr)
            if (!keepInternalCopy) {
                runCatching { destFile.delete() }
            }
            val completed = scanningItem.copy(
                fileName = destFile.name,
                localPath = destFile.absolutePath,
                publicUri = publicUriStr,
                mimeType = resolvedMime,
                category = resolvedCategory.name,
                status = DownloadStatus.COMPLETED.name,
                sha256 = finalReport.sha256,
                securityVerdict = DownloadVerdict.SAFE.name,
                threatCategory = null,
                threatReason = null,
                scanSummary = "No known threats detected.",
                quarantined = false
            )
            repository.updateDownload(completed)
            repository.recordSecurityEvent(
                category = "DOWNLOAD",
                severity = "INFO",
                title = "Download verified clean",
                summary = "${completed.fileName}: No known threats detected.",
                targetHostOrFile = completed.fileName,
                actionTaken = if (publicUriStr != null) "Saved to Gallery / Files" else "Promoted to Downloads"
            )
            if (showNotification) {
                postProgressNotification(scanningItem.id.toInt(), completed.fileName, downloaded, downloaded, true)
            }
        } else {
            lastNotifiedProgressPct.remove(scanningItem.id.toInt())
            val quarantinedEntity = scanningItem.copy(
                status = DownloadStatus.QUARANTINED.name,
                sha256 = finalReport.sha256,
                securityVerdict = finalReport.verdict.name,
                threatCategory = finalReport.threatCategory,
                threatReason = finalReport.detectionReason,
                scanSummary = finalReport.recommendation,
                quarantined = true
            )
            repository.updateDownload(quarantinedEntity)
            val severity = if (finalReport.verdict == DownloadVerdict.DANGEROUS) "CRITICAL" else "WARNING"
            repository.recordSecurityEvent(
                category = "DOWNLOAD",
                severity = severity,
                title = "${finalReport.verdict.name} download quarantined",
                summary = "${quarantinedEntity.fileName}: ${finalReport.detectionReason}",
                targetHostOrFile = quarantinedEntity.fileName,
                actionTaken = "Held in Private Quarantine"
            )
            if (finalReport.verdict == DownloadVerdict.DANGEROUS) {
                SecurityNotifications.notify(
                    context = context,
                    enabled = securityNotificationsEnabled,
                    kind = SecurityNotifications.AlertKind.DANGEROUS_DOWNLOAD,
                    safeMessage = "Blocked ${quarantinedEntity.fileName}: ${finalReport.threatCategory ?: "Potential malware"}"
                )
            }
            _pendingReviews.update { current ->
                current.filterNot { it.download.id == quarantinedEntity.id } +
                    PendingQuarantineReview(quarantinedEntity, finalReport)
            }
        }
    }

    private fun isApkOrHtmlFile(fileName: String, mimeType: String, category: DownloadFileCategory): Boolean {
        val lower = fileName.lowercase()
        return category == DownloadFileCategory.APK ||
            lower.endsWith(".apk") ||
            mimeType.equals("application/vnd.android.package-archive", ignoreCase = true) ||
            lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml") ||
            mimeType.equals("text/html", ignoreCase = true) ||
            mimeType.equals("application/xhtml+xml", ignoreCase = true)
    }

    private fun verifyPublicUriReadable(uriStr: String?): Boolean {
        if (uriStr.isNullOrBlank()) return false
        return runCatching {
            val uri = Uri.parse(uriStr)
            if (uri.scheme != "content") return false
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length > 0L || afd.declaredLength > 0L
            } == true
        }.getOrDefault(false)
    }

    private fun deletePublicMediaEntry(uriStr: String?) {
        if (uriStr.isNullOrBlank()) return
        runCatching {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun publishToPublicStorage(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        category: DownloadFileCategory
    ): String? {
        if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() == 0L) return null
        val resolvedMime = normalizePublicMime(fileName, mimeType, category)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val (collectionUri, relativePath) = when {
                    category == DownloadFileCategory.IMAGE || resolvedMime.startsWith("image/") -> {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                            "${Environment.DIRECTORY_PICTURES}/Weblo"
                    }
                    category == DownloadFileCategory.VIDEO || resolvedMime.startsWith("video/") -> {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                            "${Environment.DIRECTORY_MOVIES}/Weblo"
                    }
                    category == DownloadFileCategory.AUDIO || resolvedMime.startsWith("audio/") -> {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                            "${Environment.DIRECTORY_MUSIC}/Weblo"
                    }
                    else -> {
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                            "${Environment.DIRECTORY_DOWNLOADS}/Weblo"
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, resolvedMime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val itemUri = resolver.insert(collectionUri, values) ?: return null
                try {
                    val outStream = resolver.openOutputStream(itemUri, "w")
                        ?: throw IllegalStateException("MediaStore output stream unavailable")
                    BufferedOutputStream(outStream, IO_BUFFER_SIZE).use { out ->
                        BufferedInputStream(FileInputStream(sourceFile), IO_BUFFER_SIZE).use { input ->
                            val buf = ByteArray(IO_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                            }
                        }
                        out.flush()
                    }
                    val finishValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, finishValues, null, null)
                    itemUri.toString()
                } catch (e: Exception) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    null
                }
            }.getOrNull()
        }

        // Android 7-9 fallback if legacy storage permission happens to be granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val baseDir = when {
                    category == DownloadFileCategory.IMAGE || resolvedMime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                    category == DownloadFileCategory.VIDEO || resolvedMime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                    category == DownloadFileCategory.AUDIO || resolvedMime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_DOWNLOADS
                }
                val targetDir = File(Environment.getExternalStoragePublicDirectory(baseDir), "Weblo").apply { mkdirs() }
                var target = File(targetDir, fileName)
                if (target.exists()) {
                    val dot = fileName.lastIndexOf('.')
                    val stem = if (dot > 0) fileName.substring(0, dot) else fileName
                    val ext = if (dot > 0) fileName.substring(dot) else ""
                    var c = 1
                    while (target.exists()) {
                        target = File(targetDir, "$stem ($c)$ext")
                        c++
                    }
                }
                BufferedInputStream(FileInputStream(sourceFile), IO_BUFFER_SIZE).use { input ->
                    BufferedOutputStream(FileOutputStream(target), IO_BUFFER_SIZE).use { out ->
                        input.copyTo(out, IO_BUFFER_SIZE)
                    }
                }
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(resolvedMime), null)
            }
        }
        return null
    }

    private fun normalizePublicMime(fileName: String, mimeType: String, category: DownloadFileCategory): String {
        val clean = mimeType.substringBefore(';').trim()
        if (clean.isNotBlank() && !clean.equals("application/octet-stream", ignoreCase = true)) {
            return clean
        }
        val guessed = guessMime(fileName)
        if (guessed != "application/octet-stream") return guessed
        return when (category) {
            DownloadFileCategory.IMAGE -> "image/jpeg"
            DownloadFileCategory.VIDEO -> "video/mp4"
            DownloadFileCategory.AUDIO -> "audio/mpeg"
            DownloadFileCategory.PDF -> "application/pdf"
            DownloadFileCategory.ARCHIVE -> "application/zip"
            DownloadFileCategory.APK -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun postProgressNotification(id: Int, fileName: String, current: Long, total: Long, completed: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!notificationChannelCreated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Weblo Downloads", NotificationManager.IMPORTANCE_LOW)
            )
            notificationChannelCreated = true
        }
        if (completed) {
            lastNotifiedProgressPct.remove(id)
        } else {
            val pct = if (total > 0) ((current * 100) / total).toInt().coerceIn(0, 100) else -1
            val prev = lastNotifiedProgressPct[id]
            if (prev != null && prev == pct && pct >= 0) return
            lastNotifiedProgressPct[id] = pct
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(fileName)
            .setOnlyAlertOnce(true)
        if (completed) {
            builder.setContentText("Saved to Gallery & Files • No known threats detected.")
                .setProgress(0, 0, false)
                .setAutoCancel(true)
        } else {
            val max = if (total > 0) 100 else 0
            val pct = if (total > 0) ((current * 100) / total).toInt().coerceIn(0, 100) else 0
            builder.setContentText(if (total > 0) "$pct% downloaded (quarantined)" else "Downloading to quarantine...")
                .setProgress(max, pct, total <= 0)
                .setOngoing(true)
        }
        runCatching { nm.notify(7000 + id, builder.build()) }
    }

    private fun uniqueTargetFile(baseName: String): File {
        val safeName = sanitizeFileName(baseName)
        var candidate = File(downloadsDir, safeName)
        if (!candidate.exists()) return candidate
        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(downloadsDir, "$stem ($counter)$ext")
            counter++
        }
        return candidate
    }

    companion object {
        const val LOCAL_PREVIEW_HOST = "downloads.weblio.local"
        private const val CHANNEL_ID = "weblio_downloads"
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_REPORT_INTERVAL_MS = 750L
        private val apkIconCache = LruCache<String, Bitmap>(32)

        private fun loadPackageArchiveInfo(pm: PackageManager, apkPath: String): PackageInfo? {
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(
                        apkPath,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA)
                }
            }.getOrNull()
        }

        fun extractApkIconBitmap(context: Context, filePath: String): Bitmap? {
            if (filePath.isBlank()) return null
            val file = File(filePath)
            if (!file.exists() || !file.isFile || file.length() < 4L) return null

            val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
            apkIconCache.get(cacheKey)?.let { return it }

            val isZip = runCatching {
                FileInputStream(file).use { fis ->
                    val magic = ByteArray(2)
                    fis.read(magic) == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
                }
            }.getOrDefault(false)
            if (!isZip) return null

            val pm = context.packageManager
            val archiveInfo = loadPackageArchiveInfo(pm, file.absolutePath) ?: return null
            val appInfo = archiveInfo.applicationInfo ?: return null
            if (appInfo.icon == 0) return null

            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath

            val drawable = runCatching { appInfo.loadIcon(pm) }.getOrNull() ?: return null
            val bitmap = runCatching { drawableToBitmap(drawable, sizePx = 128) }.getOrNull() ?: return null
            apkIconCache.put(cacheKey, bitmap)
            return bitmap
        }

        private fun drawableToBitmap(drawable: Drawable, sizePx: Int = 128): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val bmp = drawable.bitmap
                if (bmp.width <= sizePx && bmp.height <= sizePx) return bmp
                return Bitmap.createScaledBitmap(bmp, sizePx, sizePx, true)
            }
            val w = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(sizePx) ?: sizePx
            val h = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(sizePx) ?: sizePx
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        fun sanitizeFileName(raw: String): String {
            val cleaned = raw
                .replace('\\', '_')
                .replace('/', '_')
                .replace(Regex("[\\x00-\\x1F:*?\"<>|]"), "_")
                .replace(Regex("\\.{2,}"), ".")
                .trim()
                .trimStart('.')
                .take(160)
            return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
                "download_${System.currentTimeMillis()}"
            } else {
                cleaned
            }
        }

        fun guessFileName(url: String, mimeType: String?): String {
            val lastSeg = android.net.Uri.parse(url).lastPathSegment?.substringBefore('?')
            if (!lastSeg.isNullOrBlank() && lastSeg.contains('.')) return lastSeg
            val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
            return "weblo_file_${System.currentTimeMillis()}.$ext"
        }

        fun guessMime(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        fun detectCategory(fileName: String, mimeType: String): DownloadFileCategory {
            val lower = fileName.lowercase()
            val mime = mimeType.lowercase()
            return when {
                lower.endsWith(".apk") || mime.contains("android.package-archive") -> DownloadFileCategory.APK
                lower.endsWith(".pdf") || mime == "application/pdf" -> DownloadFileCategory.PDF
                lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz") -> DownloadFileCategory.ARCHIVE
                lower.endsWith(".exe") || lower.endsWith(".elf") || lower.endsWith(".dll") || lower.endsWith(".so") -> DownloadFileCategory.EXECUTABLE
                lower.endsWith(".sh") || lower.endsWith(".bat") || lower.endsWith(".ps1") || lower.endsWith(".vbs") -> DownloadFileCategory.SCRIPT
                mime.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp") -> DownloadFileCategory.IMAGE
                mime.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg") -> DownloadFileCategory.AUDIO
                mime.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") -> DownloadFileCategory.VIDEO
                mime.startsWith("text/") || lower.endsWith(".txt") || lower.endsWith(".doc") || lower.endsWith(".docx") -> DownloadFileCategory.DOCUMENT
                else -> DownloadFileCategory.OTHER
            }
        }
    }
}
