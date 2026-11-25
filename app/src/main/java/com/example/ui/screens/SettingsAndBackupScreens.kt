package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricManager
import com.example.data.db.SitePermissionEntity
import com.example.data.prefs.BrowserSettings
import com.example.data.prefs.CookiePolicy
import com.example.data.prefs.SearchEngine
import com.example.data.prefs.StartupBehavior
import com.example.data.prefs.WallpaperStyle
import com.example.engine.BackupRestoreManager
import com.example.ui.BrowserViewModel
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: BrowserSettings,
    viewModel: BrowserViewModel,
    onOpenExtensions: () -> Unit,
    onOpenAdBlock: () -> Unit,
    onOpenPasswords: () -> Unit,
    onOpenSitePermissions: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onBack: () -> Unit,
    onOpenSecurityCenter: () -> Unit = {}
) {
    val context = LocalContext.current
    var showClearDataDialog by remember { mutableStateOf(false) }
    var homepageDraft by remember(settings.homepageUrl) { mutableStateOf(settings.homepageUrl) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // GENERAL
            item { SettingsSectionHeader("General") }
            item {
                SettingsCard {
                    Text("Default Search Engine", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        SearchEngine.entries.take(3).forEach { e ->
                            FilterChip(
                                selected = settings.defaultSearchEngine == e,
                                onClick = { viewModel.setSearchEngine(e) },
                                label = { Text(e.displayName) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        SearchEngine.entries.drop(3).forEach { e ->
                            FilterChip(
                                selected = settings.defaultSearchEngine == e,
                                onClick = { viewModel.setSearchEngine(e) },
                                label = { Text(e.displayName) }
                            )
                        }
                    }
                    SettingsSwitchRow(
                        title = "SafeSearch (Filter Explicit Results)",
                        checked = settings.safeSearchEnabled,
                        switchTag = "settings_safe_search_switch"
                    ) { viewModel.setSafeSearchEnabled(it) }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = homepageDraft,
                        onValueChange = { homepageDraft = it },
                        label = { Text("Homepage URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { viewModel.setHomepage(homepageDraft); viewModel.showMessage("Homepage saved") }) {
                        Text("Save Homepage")
                    }
                    Text("Startup Behavior", style = MaterialTheme.typography.labelLarge)
                    StartupBehavior.entries.forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = settings.startupBehavior == b,
                                onClick = { viewModel.setStartupBehavior(b) },
                                label = { Text(b.label) }
                            )
                        }
                    }
                }
            }

            // APPEARANCE
            item { SettingsSectionHeader("Appearance") }
            item {
                SettingsCard {
                    Text("Theme Mode", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("theme_mode_${mode.label.lowercase()}")
                            )
                        }
                    }
                    SettingsSwitchRow(
                        "White Theme",
                        settings.themeMode == AppThemeMode.LIGHT
                    ) { isWhite ->
                        viewModel.setThemeMode(if (isWhite) AppThemeMode.LIGHT else AppThemeMode.DARK)
                    }
                    Text("Home Wallpaper", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WallpaperStyle.entries.take(2).forEach { ws ->
                            FilterChip(
                                selected = settings.wallpaperStyle == ws,
                                onClick = { viewModel.setWallpaperStyle(ws) },
                                label = { Text(ws.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WallpaperStyle.entries.drop(2).forEach { ws ->
                            FilterChip(
                                selected = settings.wallpaperStyle == ws,
                                onClick = { viewModel.setWallpaperStyle(ws) },
                                label = { Text(ws.label) }
                            )
                        }
                    }
                    SettingsSwitchRow("Force Dark Mode on Web Pages", settings.forceDarkWebPages) { viewModel.setForceDarkWeb(it) }
                    SettingsSwitchRow("Show Quick Links on Home", settings.showQuickLinksOnHome) { viewModel.setShowQuickLinks(it) }
                    SettingsSwitchRow("Compact Bottom Toolbar", settings.compactBottomToolbar) { viewModel.setCompactToolbar(it) }
                }
            }

            // PRIVACY & SECURITY
            item { SettingsSectionHeader("Privacy & Security") }
            item {
                SettingsCard {
                    SettingsButtonRow("Weblo Security Center", onClick = onOpenSecurityCenter)
                    SettingsButtonRow("Ad Blocker & DNS Shield", onClick = onOpenAdBlock)
                    SettingsButtonRow("Password Manager (Keystore)", onClick = onOpenPasswords)
                    SettingsButtonRow("Website Permissions Manager", onClick = onOpenSitePermissions)
                    Text("Cookie Controls", style = MaterialTheme.typography.labelLarge)
                    CookiePolicy.entries.forEach { cp ->
                        FilterChip(
                            selected = settings.cookiePolicy == cp,
                            onClick = { viewModel.setCookiePolicy(cp) },
                            label = { Text(cp.label) }
                        )
                    }
                    SettingsSwitchRow("Clear Incognito Data on Exit", settings.clearIncognitoOnExit) { viewModel.setClearIncognitoOnExit(it) }
                    SettingsSwitchRow("Safe Browsing Protection", settings.safeBrowsingEnabled) { viewModel.setSafeBrowsing(it) }
                    SettingsSwitchRow("HTTPS-First Navigation", settings.httpsFirstEnabled) { viewModel.setHttpsFirstEnabled(it) }
                    SettingsSwitchRow("Scan Downloads in Quarantine", settings.downloadScanEnabled) { viewModel.setDownloadScanEnabled(it) }
                    SettingsSwitchRow("Cloud Hash Reputation (8-char SHA-256 Prefix Only)", settings.cloudHashReputationEnabled) { viewModel.setCloudHashReputationEnabled(it) }
                    SettingsSwitchRow("Security Notifications", settings.securityNotificationsEnabled) { viewModel.setSecurityNotificationsEnabled(it) }
                    SettingsSwitchRow("Clipboard Privacy Guard", settings.clipboardGuardEnabled) { viewModel.setClipboardGuard(it) }
                    SettingsSwitchRow("Biometric Application Lock", settings.biometricAppLockEnabled) { enable ->
                        if (enable) {
                            val bm = BiometricManager.from(context)
                            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            if (bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
                                viewModel.setBiometricAppLock(true)
                            } else {
                                viewModel.showMessage("Set up a device screen lock or biometrics in Android Settings first")
                            }
                        } else {
                            viewModel.setBiometricAppLock(false)
                        }
                    }
                    OutlinedButton(onClick = { showClearDataDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear Browsing Data")
                    }
                }
            }

            // DOWNLOADS & EXTENSIONS
            item { SettingsSectionHeader("Downloads & Extensions") }
            item {
                SettingsCard {
                    Text("Location: ${settings.downloadLocationLabel}", style = MaterialTheme.typography.bodySmall, color = WeblioCyan)
                    SettingsSwitchRow("Download Notifications", settings.downloadNotificationsEnabled) { viewModel.setDownloadNotifications(it) }
                    SettingsSwitchRow("Ask Before Downloading", settings.askBeforeDownload) { viewModel.setAskBeforeDownload(it) }
                    SettingsButtonRow("Manage Extensions & UserScripts", onClick = onOpenExtensions)
                    SettingsButtonRow("Local Encrypted Backup & Restore", onClick = onOpenBackupRestore)
                }
            }

            // ADVANCED
            item { SettingsSectionHeader("Advanced Engine") }
            item {
                SettingsCard {
                    SettingsSwitchRow("Desktop Mode by Default", settings.desktopModeDefault) { viewModel.setDesktopModeDefault(it) }
                    SettingsSwitchRow("Enable JavaScript", settings.javascriptEnabled) { viewModel.setJavascriptEnabled(it) }
                    SettingsSwitchRow("Hardware Acceleration", settings.hardwareAccelerationEnabled) { viewModel.setHardwareAcceleration(it) }
                    OutlinedButton(
                        onClick = { viewModel.clearAllBrowsingData(clearCookies = false, clearHistory = false, clearCache = true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Engine Web Cache")
                    }
                }
            }
        }
    }

    if (showClearDataDialog) {
        var cHist by remember { mutableStateOf(true) }
        var cCook by remember { mutableStateOf(true) }
        var cCache by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear Browsing Data") },
            text = {
                Column {
                    SettingsSwitchRow("Browsing History", cHist) { cHist = it }
                    SettingsSwitchRow("Cookies & Site Storage", cCook) { cCook = it }
                    SettingsSwitchRow("Cached Files & Images", cCache) { cCache = it }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearAllBrowsingData(cCook, cHist, cCache)
                    showClearDataDialog = false
                }) { Text("Clear Selected") }
            },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    profileId: Long,
    backupManager: BackupRestoreManager,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var includePasswords by remember { mutableStateOf(true) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                backupManager.exportEncryptedBackup(profileId, passphrase, includePasswords)
                    .onSuccess { json ->
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        statusBanner = "Encrypted backup saved successfully."
                        onShowMessage("Backup exported")
                    }
                    .onFailure {
                        statusBanner = it.message ?: "Export failed"
                    }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                when (val res = backupManager.importEncryptedBackup(profileId, raw, passphrase)) {
                    is BackupRestoreManager.RestoreResult.Success -> {
                        statusBanner = res.summary
                        onShowMessage("Restore complete")
                    }
                    is BackupRestoreManager.RestoreResult.Error -> {
                        statusBanner = res.message
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = WeblioCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("AES-256-GCM Encrypted Local Backup", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "Backups never leave your device unless you move the file. Saved passwords are always encrypted inside the backup envelope with your passphrase.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Backup Passphrase (min 6 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsSwitchRow("Include Encrypted Passwords", includePasswords) { includePasswords = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { exportLauncher.launch("weblo_backup_${System.currentTimeMillis()}.weblo") },
                        enabled = passphrase.length >= 6,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Backup, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = passphrase.length >= 6,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Restore, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Restore")
                    }
                }
            }

            statusBanner?.let { msg ->
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(msg, modifier = Modifier.padding(14.dp), color = WeblioCyan)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitePermissionsScreen(
    permissions: List<SitePermissionEntity>,
    onUpdatePermission: (SitePermissionEntity) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Website Permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (permissions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No per-site overrides yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Use Browser Tools -> Ad Shield on any website to configure per-site permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(permissions, key = { it.id }) { perm ->
                    SettingsCard {
                        Text(perm.originHost, fontWeight = FontWeight.Bold, color = WeblioCyan)
                        SettingsSwitchRow("Ad Blocker Enabled", perm.adBlockEnabled) {
                            onUpdatePermission(perm.copy(adBlockEnabled = it))
                        }
                        SettingsSwitchRow("JavaScript Enabled", perm.javascriptEnabled) {
                            onUpdatePermission(perm.copy(javascriptEnabled = it))
                        }
                        SettingsSwitchRow("Desktop Mode", perm.desktopMode) {
                            onUpdatePermission(perm.copy(desktopMode = it))
                        }
                        SiteCapabilityRow("Camera", perm.cameraAllowed) {
                            onUpdatePermission(perm.copy(cameraAllowed = it))
                        }
                        SiteCapabilityRow("Microphone", perm.micAllowed) {
                            onUpdatePermission(perm.copy(micAllowed = it))
                        }
                        SiteCapabilityRow("Location", perm.locationAllowed) {
                            onUpdatePermission(perm.copy(locationAllowed = it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteCapabilityRow(
    label: String,
    currentState: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        listOf("ASK", "ALLOW", "DENY").forEach { mode ->
            FilterChip(
                selected = currentState.equals(mode, ignoreCase = true),
                onClick = { onSelect(mode) },
                label = { Text(mode.lowercase().replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = WeblioCyan,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    switchTag: String? = null,
    onChanged: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            modifier = if (switchTag != null) Modifier.testTag(switchTag) else Modifier
        )
    }
}

@Composable
private fun SettingsButtonRow(title: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title)
    }
}
