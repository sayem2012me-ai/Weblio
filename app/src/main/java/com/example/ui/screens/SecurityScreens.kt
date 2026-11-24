package com.example.ui.screens

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.DownloadEntity
import com.example.data.db.ExtensionEntity
import com.example.data.db.SecurityEventEntity
import com.example.data.prefs.BrowserSettings
import com.example.engine.PendingQuarantineReview
import com.example.security.AppIntegrityReport
import com.example.security.DownloadVerdict
import com.example.security.SecurityState
import com.example.security.UpdateVerificationResult
import com.example.security.WebsiteThreatReport
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioSuccess
import com.example.ui.theme.WeblioWarning
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun DangerousWebsiteWarningScreen(
    report: WebsiteThreatReport,
    onGoBack: () -> Unit,
    onContinueAnyway: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSecondaryOverrideConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("dangerous_website_warning_screen"),
        color = Color(0xFF2B090C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(WeblioDanger.copy(alpha = 0.22f))
                    .border(2.dp, WeblioDanger, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.GppBad,
                    contentDescription = "Dangerous Website",
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "DANGEROUS WEBSITE!",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color(0xFFFF8A8A)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "WEBLIO Security Protection has detected a potential threat. This website may attempt to steal your information, install malicious software, or compromise your privacy.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.94f)
            )

            Spacer(Modifier.height(18.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3E1216)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Threat Category: ${report.threatCategory}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFFFB4B4)
                    )
                    Text(
                        text = "Host: ${report.host.ifBlank { report.url }}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White
                    )
                    Text(
                        text = "Reason: ${report.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = "Detection Engine: ${report.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onGoBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF2B090C)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("website_warning_goback")
            ) {
                Text(
                    text = "GO BACK — Recommended",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showSecondaryOverrideConfirm = true },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB4B4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("website_warning_continue")
            ) {
                Text("CONTINUE ANYWAY")
            }
        }
    }

    if (showSecondaryOverrideConfirm) {
        AlertDialog(
            onDismissRequest = { showSecondaryOverrideConfirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = WeblioDanger) },
            title = { Text("Proceed to Unverified Site?") },
            text = {
                Text(
                    "Visiting ${report.host.ifBlank { report.url }} may expose passwords, cookies, or trigger malicious downloads. WEBLIO will allow this single navigation only and will NOT whitelist this site."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSecondaryOverrideConfirm = false
                        onContinueAnyway()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WeblioDanger),
                    modifier = Modifier.testTag("website_warning_confirm_override")
                ) {
                    Text("Proceed (Single Visit)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSecondaryOverrideConfirm = false }) {
                    Text("Stay Safe")
                }
            }
        )
    }
}

@Composable
fun DownloadSecurityDecisionDialog(
    review: PendingQuarantineReview,
    onContinueAnyway: (Long) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val report = review.report
    val isDangerous = report.verdict == DownloadVerdict.DANGEROUS
    var showDangerOverrideConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isDangerous) Icons.Filled.GppBad else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (isDangerous) WeblioDanger else WeblioWarning,
                modifier = Modifier.size(34.dp)
            )
        },
        title = {
            Text(
                text = when (report.verdict) {
                    DownloadVerdict.DANGEROUS -> "Dangerous Download Blocked"
                    DownloadVerdict.UNVERIFIED -> "Download Could Not Be Fully Verified"
                    else -> "Suspicious File Quarantined"
                },
                color = if (isDangerous) WeblioDanger else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isDangerous) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = WeblioDanger.copy(alpha = 0.16f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "DANGER: This file is isolated in WEBLIO private quarantine and has NOT been placed in Downloads or executed.",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = WeblioDanger,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                Text("File name: ${report.fileName}", fontWeight = FontWeight.Bold)
                Text("File type: ${report.detectedTypeLabel} (${report.declaredMime})", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "Size: ${Formatter.formatShortFileSize(context, report.actualBytes)}" +
                        if (report.expectedBytes > 0) " / expected ${Formatter.formatShortFileSize(context, report.expectedBytes)}" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!report.threatCategory.isNullOrBlank()) {
                    Text(
                        text = "Threat category: ${report.threatCategory}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isDangerous) WeblioDanger else WeblioWarning
                    )
                }
                Text("Detection reason: ${report.detectionReason}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "Recommendation: ${report.recommendation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WeblioCyan
                )
                if (report.sha256.isNotBlank()) {
                    Text(
                        text = "SHA-256: ${report.sha256.take(24)}…",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCancelDownload(review.download.id) },
                colors = ButtonDefaults.buttonColors(containerColor = WeblioElectricBlue),
                modifier = Modifier.testTag("download_decision_cancel")
            ) {
                Text("CANCEL DOWNLOAD")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isDangerous) {
                        showDangerOverrideConfirm = true
                    } else {
                        onContinueAnyway(review.download.id)
                    }
                },
                modifier = Modifier.testTag("download_decision_continue")
            ) {
                Text(
                    text = "CONTINUE ANYWAY",
                    color = if (isDangerous) WeblioDanger else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )

    if (showDangerOverrideConfirm) {
        AlertDialog(
            onDismissRequest = { showDangerOverrideConfirm = false },
            icon = { Icon(Icons.Filled.GppBad, contentDescription = null, tint = WeblioDanger) },
            title = { Text("Confirm Risky File Override") },
            text = {
                Text(
                    "\"${report.fileName}\" matched critical threat indicators (${report.threatCategory ?: "Malware/Tampered"}).\n\n" +
                        "Even if saved, WEBLIO will keep it flagged as Dangerous (User Overridden) and will never auto-execute it. Save anyway?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDangerOverrideConfirm = false
                        onContinueAnyway(review.download.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WeblioDanger),
                    modifier = Modifier.testTag("download_decision_confirm_dangerous")
                ) {
                    Text("Save Marked Overridden")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDangerOverrideConfirm = false }) {
                    Text("Keep Quarantined")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCenterScreen(
    overallState: SecurityState,
    settings: BrowserSettings,
    integrityReport: AppIntegrityReport?,
    threatDbCount: Int,
    isUpdatingThreatDb: Boolean,
    quarantinedDownloads: List<DownloadEntity>,
    extensions: List<ExtensionEntity>,
    securityEvents: List<SecurityEventEntity>,
    onReviewQuarantined: (DownloadEntity) -> Unit,
    onRunIntegrityCheck: () -> Unit,
    onUpdateThreatDb: () -> Unit,
    onVerifyUpdatePackage: (File, String) -> Result<UpdateVerificationResult>,
    onToggleCloudHashLookup: (Boolean) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenDownloads: () -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showVerifyUpdateDialog by remember { mutableStateOf(false) }

    val (stateColor, stateIcon, stateDesc) = when (overallState) {
        SecurityState.PROTECTED -> Triple(
            WeblioSuccess,
            Icons.Filled.VerifiedUser,
            "Active safeguards enabled. Integrity, quarantine scanning, HTTPS-first, and local threat shields are operational."
        )
        SecurityState.ATTENTION_REQUIRED -> Triple(
            WeblioWarning,
            Icons.Filled.GppMaybe,
            "One or more security protections are disabled, debug flags are active, or quarantined items await review."
        )
        SecurityState.THREAT_DETECTED -> Triple(
            WeblioDanger,
            Icons.Filled.GppBad,
            "Dangerous download, unsafe website, or integrity anomaly detected. Review pending actions below."
        )
        SecurityState.VERIFICATION_UNAVAILABLE -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Security,
            "Hardware/build verification is unavailable in this environment."
        )
    }

    val riskyExtensionsCount = extensions.count { it.riskLevel == "HIGH" || it.riskLevel == "SENSITIVE" }
    val enabledHighRiskCount = extensions.count { it.enabled && it.riskLevel == "HIGH" }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("WEBLIO Security Center") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (securityEvents.isNotEmpty()) {
                        IconButton(onClick = onClearHistory) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear threat log")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("security_center_screen")
        ) {
            // Honest Security State Banner (no fake percentages/scores)
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = stateColor.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(stateIcon, contentDescription = null, tint = stateColor, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = overallState.label,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = stateColor,
                                    modifier = Modifier.testTag("security_center_state_label")
                                )
                                Text(
                                    text = "Last Threat DB Sync: " + if (settings.lastSecurityUpdateMillis > 0L) {
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(settings.lastSecurityUpdateMillis))
                                    } else {
                                        "Local bundled rules active"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(stateDesc, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "Transparency note: Client-side integrity checks cannot guarantee safety on rooted devices, modified ROMs, or compromised operating systems.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Pending Actions
            if (quarantinedDownloads.isNotEmpty() || enabledHighRiskCount > 0) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = WeblioWarning.copy(alpha = 0.14f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Pending Security Actions", style = MaterialTheme.typography.titleMedium, color = WeblioWarning)
                            quarantinedDownloads.forEach { dl ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(dl.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${dl.securityVerdict} • Held in private quarantine", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Button(onClick = { onReviewQuarantined(dl) }) {
                                        Text("Review")
                                    }
                                }
                            }
                            if (enabledHighRiskCount > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "$enabledHighRiskCount HIGH-risk extension(s) currently active",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onOpenExtensions) { Text("Inspect") }
                                }
                            }
                        }
                    }
                }
            }

            // Subsystem: Safe Browsing & Threat Intelligence
            item {
                SecuritySubsystemCard(
                    title = "Safe Browsing & Threat Intelligence",
                    icon = Icons.Filled.Shield,
                    statusLabel = if (settings.safeBrowsingEnabled) "Active ($threatDbCount cached indicators)" else "Disabled",
                    statusColor = if (settings.safeBrowsingEnabled) WeblioSuccess else WeblioWarning
                ) {
                    Text(
                        text = "Inspects URLs, redirects, homograph punycode domains, and known phishing indicators locally before page load.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isUpdatingThreatDb) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Privacy-Preserving Cloud Hash Lookup", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Sends only 8-char SHA-256 host prefixes over HTTPS. Full URLs and history are never uploaded.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.cloudHashReputationEnabled,
                            onCheckedChange = onToggleCloudHashLookup
                        )
                    }
                    OutlinedButton(
                        onClick = onUpdateThreatDb,
                        enabled = !isUpdatingThreatDb,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isUpdatingThreatDb) "Updating Threat Database..." else "Update Threat Database Over HTTPS")
                    }
                }
            }

            // Subsystem: Download Quarantine Scanner
            item {
                SecuritySubsystemCard(
                    title = "Download Quarantine & Streaming Scanner",
                    icon = Icons.Filled.Lock,
                    statusLabel = if (settings.downloadScanEnabled) {
                        "${quarantinedDownloads.size} Quarantined"
                    } else {
                        "Disabled"
                    },
                    statusColor = if (quarantinedDownloads.isNotEmpty()) WeblioWarning else WeblioSuccess
                ) {
                    Text(
                        text = "Downloads stage in private app quarantine, undergo chunked SHA-256, magic-byte, ZIP/APK, PDF script, and double-extension inspection before release.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Downloads & Quarantine (${quarantinedDownloads.size})")
                    }
                }
            }

            // Subsystem: Extension Isolation & Integrity
            item {
                SecuritySubsystemCard(
                    title = "Extension Security & Sandbox",
                    icon = Icons.Filled.Extension,
                    statusLabel = "${extensions.size} Installed ($riskyExtensionsCount flagged)",
                    statusColor = if (enabledHighRiskCount > 0) WeblioWarning else WeblioSuccess
                ) {
                    Text(
                        text = "Each extension gets isolated storage, SHA-256 integrity checks before injection, and permission warnings before activation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onOpenExtensions, modifier = Modifier.fillMaxWidth()) {
                        Text("Review Extension Permissions")
                    }
                }
            }

            // Subsystem: Application Integrity & Update Verification
            item {
                val intState = integrityReport?.state ?: SecurityState.VERIFICATION_UNAVAILABLE
                val intColor = when (intState) {
                    SecurityState.PROTECTED -> WeblioSuccess
                    SecurityState.ATTENTION_REQUIRED -> WeblioWarning
                    SecurityState.THREAT_DETECTED -> WeblioDanger
                    SecurityState.VERIFICATION_UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                SecuritySubsystemCard(
                    title = "App Integrity & Anti-Tampering",
                    icon = Icons.Filled.Security,
                    statusLabel = intState.label,
                    statusColor = intColor
                ) {
                    if (integrityReport != null) {
                        Text(
                            text = "Signer SHA-256: ${integrityReport.signingSha256.take(24)}…",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Text(
                            text = "Build: v${integrityReport.versionName} (${integrityReport.versionCode}) • Debuggable: ${integrityReport.isDebugBuild} • Installer: ${integrityReport.installerPackage ?: "Sideload/Local"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = integrityReport.playIntegrityNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        integrityReport.tamperWarnings.forEach { warn ->
                            Text("• $warn", style = MaterialTheme.typography.bodySmall, color = WeblioWarning)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onRunIntegrityCheck, modifier = Modifier.weight(1f)) {
                            Text("Re-verify")
                        }
                        OutlinedButton(onClick = { showVerifyUpdateDialog = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Verify APK Update")
                        }
                    }
                }
            }

            // Threat Detection History
            item {
                Text(
                    text = "Threat Detection History (${securityEvents.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (securityEvents.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = WeblioSuccess)
                            Spacer(Modifier.width(12.dp))
                            Text("No threats or warnings recorded yet.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                items(securityEvents, key = { it.id }) { ev ->
                    val evColor = when (ev.severity) {
                        "CRITICAL" -> WeblioDanger
                        "WARNING" -> WeblioWarning
                        else -> WeblioCyan
                    }
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${ev.category} • ${ev.title}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = evColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = ev.actionTaken,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(ev.summary, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(ev.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showVerifyUpdateDialog) {
        VerifyUpdatePackageDialog(
            onVerify = { uri, metaJson ->
                val tmp = File(context.cacheDir, "update_verify_${System.currentTimeMillis()}.apk")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot read selected APK")
                    onVerifyUpdatePackage(tmp, metaJson)
                }.also { tmp.delete() }.getOrElse { Result.failure(it) }
            },
            onDismiss = { showVerifyUpdateDialog = false }
        )
    }
}

@Composable
private fun SecuritySubsystemCard(
    title: String,
    icon: ImageVector,
    statusLabel: String,
    statusColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun VerifyUpdatePackageDialog(
    onVerify: (Uri, String) -> Result<UpdateVerificationResult>,
    onDismiss: () -> Unit
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var metadataJson by remember {
        mutableStateOf(
            """{"versionCode":2,"versionName":"1.1.0","downloadUrl":"https://updates.weblio.example/weblio.apk","sha256":"<64-hex-sha256>","signerSha256":""}"""
        )
    }
    var resultText by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cryptographic Update Verification") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Validates HTTPS source URL, blocks version downgrades, verifies streamed SHA-256, and checks APK signing certificate.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedUri?.lastPathSegment ?: "Select Update APK File")
                }
                OutlinedTextField(
                    value = metadataJson,
                    onValueChange = { metadataJson = it },
                    label = { Text("Update Metadata JSON") },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                resultText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = WeblioCyan)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedUri != null && metadataJson.isNotBlank(),
                onClick = {
                    val uri = selectedUri ?: return@Button
                    val res = onVerify(uri, metadataJson)
                    resultText = res.fold(
                        onSuccess = { r -> if (r.isValid) "VERIFIED: ${r.reason}" else "BLOCKED: ${r.reason}" },
                        onFailure = { e -> "REJECTED: ${e.message}" }
                    )
                }
            ) {
                Text("Verify Package")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
