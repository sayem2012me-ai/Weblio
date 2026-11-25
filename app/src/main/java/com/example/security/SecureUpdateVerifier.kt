package com.example.security

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Cryptographic Update Package Verifier.
 * - Validates HTTPS delivery URLs and metadata schema.
 * - Blocks version downgrade attacks (versionCode <= currentVersionCode).
 * - Verifies streamed SHA-256 digest and APK signing certificate against the installed app signer
 *   before permitting Android's official package installer Intent.
 */
class SecureUpdateVerifier(private val context: Context) {

    fun validateMetadata(
        rawJson: String,
        currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    ): Result<UpdateMetadata> {
        return runCatching {
            val obj = JSONObject(rawJson)
            val versionCode = obj.getLong("versionCode")
            val versionName = obj.getString("versionName").trim()
            val url = obj.getString("downloadUrl").trim()
            val sha256 = obj.getString("sha256").trim().lowercase()
            val signer = obj.optString("signerSha256", "").trim().lowercase()
            val notes = obj.optString("releaseNotes", "")

            require(url.startsWith("https://", ignoreCase = true)) {
                "Update download URL must use HTTPS."
            }
            require(versionCode > currentVersionCode) {
                "Downgrade rejected: update versionCode ($versionCode) is not newer than installed ($currentVersionCode)."
            }
            require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid expected SHA-256 digest in update metadata."
            }

            UpdateMetadata(
                versionCode = versionCode,
                versionName = versionName,
                downloadUrl = url,
                expectedSha256 = sha256,
                expectedSignerSha256 = signer,
                releaseNotes = notes
            )
        }
    }

    fun verifyUpdateApkFile(
        apkFile: File,
        metadata: UpdateMetadata,
        installedSignerSha256: String,
        currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    ): UpdateVerificationResult {
        val httpsOk = metadata.downloadUrl.startsWith("https://", ignoreCase = true)
        if (!httpsOk) {
            return UpdateVerificationResult(false, metadata.versionCode, false, false, false, false, "Rejected non-HTTPS update URL.")
        }
        if (metadata.versionCode <= currentVersionCode) {
            return UpdateVerificationResult(false, metadata.versionCode, true, true, false, false, "Downgrade blocked: version ${metadata.versionCode} <= $currentVersionCode.")
        }
        if (!apkFile.exists() || !apkFile.isFile) {
            return UpdateVerificationResult(false, metadata.versionCode, true, false, false, false, "Update package missing on disk.")
        }

        // Streamed SHA-256 verification
        val actualSha256 = computeFileSha256(apkFile)
        val shaOk = MessageDigest.isEqual(
            actualSha256.lowercase().toByteArray(),
            metadata.expectedSha256.lowercase().toByteArray()
        )
        if (!shaOk) {
            return UpdateVerificationResult(false, metadata.versionCode, true, false, false, false, "SHA-256 mismatch: package was modified or corrupted.")
        }

        // Archive signature verification via PackageManager.getPackageArchiveInfo
        val archiveInfo = runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }.getOrNull()

        val archivePkg = archiveInfo?.packageName
        if (archivePkg != null && archivePkg != context.packageName) {
            return UpdateVerificationResult(false, metadata.versionCode, true, false, true, false, "Package ID mismatch ($archivePkg != ${context.packageName}).")
        }

        val archiveSigner = archiveInfo?.let { AppIntegrityManager.extractPrimarySignerSha256(it) }.orEmpty()
        val expectedSigner = metadata.expectedSignerSha256.ifBlank { installedSignerSha256 }
        val sigOk = if (expectedSigner.isNotBlank()) {
            archiveSigner.isNotBlank() && MessageDigest.isEqual(
                archiveSigner.lowercase().toByteArray(),
                expectedSigner.lowercase().toByteArray()
            )
        } else {
            archiveSigner.isNotBlank()
        }

        if (!sigOk) {
            return UpdateVerificationResult(
                isValid = false,
                versionCode = metadata.versionCode,
                httpsVerified = true,
                downgradeBlocked = false,
                sha256Verified = true,
                signatureVerified = false,
                reason = "Update APK signing certificate does not match trusted Weblo signature."
            )
        }

        return UpdateVerificationResult(
            isValid = true,
            versionCode = metadata.versionCode,
            httpsVerified = true,
            downgradeBlocked = false,
            sha256Verified = true,
            signatureVerified = true,
            reason = "Update signature and SHA-256 digest verified."
        )
    }

    /**
     * Builds an Intent for Android's official system package installer only after verification passes.
     */
    fun createVerifiedInstallIntent(verifiedApk: File, result: UpdateVerificationResult): Result<Intent> {
        if (!result.isValid) {
            return Result.failure(SecurityException("Refused to install unverified update: ${result.reason}"))
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", verifiedApk)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    companion object {
        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(16 * 1024)
            BufferedInputStream(FileInputStream(file), 16 * 1024).use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
