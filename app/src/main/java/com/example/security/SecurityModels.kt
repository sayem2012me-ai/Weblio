package com.example.security

enum class SecurityState(val label: String) {
    PROTECTED("Protected"),
    ATTENTION_REQUIRED("Attention Required"),
    THREAT_DETECTED("Threat Detected"),
    VERIFICATION_UNAVAILABLE("Verification Unavailable")
}

enum class DownloadVerdict {
    SAFE,
    SUSPICIOUS,
    DANGEROUS,
    UNVERIFIED
}

data class DownloadScanReport(
    val verdict: DownloadVerdict,
    val fileName: String,
    val declaredMime: String,
    val detectedTypeLabel: String,
    val sha256: String,
    val actualBytes: Long,
    val expectedBytes: Long,
    val threatCategory: String?,
    val detectionReason: String,
    val recommendation: String,
    val findings: List<String> = emptyList(),
    val couldNotFullyVerify: Boolean = false
)

enum class WebsiteVerdict {
    SAFE,
    SUSPICIOUS,
    DANGEROUS
}

data class WebsiteThreatReport(
    val url: String,
    val host: String,
    val verdict: WebsiteVerdict,
    val threatCategory: String,
    val reason: String,
    val source: String
)

data class AppIntegrityReport(
    val state: SecurityState,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingSha256: String,
    val isDebugBuild: Boolean,
    val installerPackage: String?,
    val tamperWarnings: List<String>,
    val playIntegrityNote: String
)

data class UpdateMetadata(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val expectedSignerSha256: String,
    val releaseNotes: String = ""
)

data class UpdateVerificationResult(
    val isValid: Boolean,
    val versionCode: Long,
    val httpsVerified: Boolean,
    val downgradeBlocked: Boolean,
    val sha256Verified: Boolean,
    val signatureVerified: Boolean,
    val reason: String
)

data class ExtensionSecurityAssessment(
    val riskLevel: String, // LOW, SENSITIVE, HIGH
    val sensitivePermissions: List<String>,
    val scriptWarnings: List<String>,
    val contentHashSha256: String,
    val summary: String
)
