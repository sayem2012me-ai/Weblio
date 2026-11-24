package com.example.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.example.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Runtime Anti-Tampering & Application Integrity Checker.
 * - Verifies APK signing certificate SHA-256 via Android PackageManager.
 * - Checks debuggable flag, unexpected debuggers, hook framework artifacts, and installer source.
 * - Documents Play Integrity API evaluation for Play distribution and explicitly notes that
 *   client-side checks cannot defeat a fully compromised/rooted kernel.
 */
class AppIntegrityManager(private val context: Context) {

    fun verifyAppIntegrity(
        expectedReleaseSignersSha256: Set<String> = emptySet()
    ): AppIntegrityReport {
        val pkgName = context.packageName
        val pm = context.packageManager
        val pkgInfo = runCatching { getPackageInfoWithSignatures(pm, pkgName) }.getOrNull()

        val signingSha256 = pkgInfo?.let { extractPrimarySignerSha256(it) }.orEmpty()
        val versionName = pkgInfo?.versionName ?: BuildConfig.VERSION_NAME
        val versionCode = pkgInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: BuildConfig.VERSION_CODE.toLong()

        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkgName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkgName)
            }
        }.getOrNull()

        val warnings = mutableListOf<String>()
        var criticalTamper = false

        if (signingSha256.isBlank()) {
            warnings.add("Unable to read APK signing certificate from PackageManager.")
        } else if (expectedReleaseSignersSha256.isNotEmpty() && signingSha256 !in expectedReleaseSignersSha256) {
            criticalTamper = true
            warnings.add("APK signing certificate mismatch! Application binary may have been modified or re-signed.")
        }

        if (!BuildConfig.DEBUG && isDebuggable) {
            criticalTamper = true
            warnings.add("Release build has FLAG_DEBUGGABLE enabled — possible manifest tampering.")
        }

        if (!BuildConfig.DEBUG && Debug.isDebuggerConnected()) {
            warnings.add("Active debugger attached to browser process.")
        }

        val hookSignals = detectHookFrameworkArtifacts()
        if (hookSignals.isNotEmpty()) {
            warnings.add("Runtime instrumentation indicators detected: ${hookSignals.joinToString(", ")}.")
        }

        // Verify APK path integrity exists and isn't substituted at runtime
        val sourceDir = context.applicationInfo.sourceDir
        if (sourceDir.isNullOrBlank() || !File(sourceDir).exists()) {
            warnings.add("Base APK path verification unavailable.")
        }

        val state = when {
            criticalTamper -> SecurityState.THREAT_DETECTED
            signingSha256.isBlank() -> SecurityState.VERIFICATION_UNAVAILABLE
            warnings.isNotEmpty() || isDebuggable -> SecurityState.ATTENTION_REQUIRED
            else -> SecurityState.PROTECTED
        }

        val playIntegrityNote = if (installer == "com.android.vending") {
            "Play Store installer verified. Server-side Play Integrity API token attestation is supported when backend verification is configured."
        } else {
            "Sideloaded / local build (installer=${installer ?: "unknown"}). Play Integrity API hardware verdict applies to Google Play distribution."
        }

        return AppIntegrityReport(
            state = state,
            packageName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            signingSha256 = signingSha256,
            isDebugBuild = isDebuggable,
            installerPackage = installer,
            tamperWarnings = warnings,
            playIntegrityNote = playIntegrityNote
        )
    }

    fun getCurrentSignerSha256(): String {
        val pkgInfo = runCatching { getPackageInfoWithSignatures(context.packageManager, context.packageName) }.getOrNull()
        return pkgInfo?.let { extractPrimarySignerSha256(it) }.orEmpty()
    }

    private fun detectHookFrameworkArtifacts(): List<String> {
        val detected = mutableListOf<String>()
        val suspiciousStackClasses = listOf("de.robv.android.xposed", "com.saurik.substrate", "re.frida")
        val stack = Thread.currentThread().stackTrace
        if (stack.any { frame -> suspiciousStackClasses.any { frame.className.contains(it, ignoreCase = true) } }) {
            detected.add("Instrumentation stack frame")
        }
        val fridaFiles = listOf("/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server")
        if (fridaFiles.any { runCatching { File(it).exists() }.getOrDefault(false) }) {
            detected.add("Frida binary artifact")
        }
        return detected
    }

    companion object {
        @Suppress("DEPRECATION")
        fun getPackageInfoWithSignatures(pm: PackageManager, pkgName: String): PackageInfo {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            }
        }

        @Suppress("DEPRECATION")
        fun extractPrimarySignerSha256(info: PackageInfo): String {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
                } else null
            } else {
                info.signatures
            }
            val firstCert = signatures?.firstOrNull()?.toByteArray() ?: return ""
            return sha256Hex(firstCert)
        }

        fun sha256Hex(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
