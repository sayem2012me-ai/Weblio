package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.db.ExtensionEntity
import com.example.engine.BrowserEngineInfo
import com.example.engine.SupportLevel
import com.example.ui.components.ChromeStoreLogoMark
import com.example.ui.components.EdgeAddonsLogoMark
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioSuccess
import com.example.ui.theme.WeblioWarning
import org.json.JSONArray
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    installedExtensions: List<ExtensionEntity>,
    builtInCatalog: List<ExtensionEntity>,
    onToggleExtension: (ExtensionEntity) -> Unit,
    onRemoveExtension: (ExtensionEntity) -> Unit,
    onOpenPopup: (ExtensionEntity) -> Unit,
    onInstallFromUri: (android.net.Uri, String?) -> Unit,
    onInstallBuiltIn: (ExtensionEntity) -> Unit,
    onCreateUserScript: (String, String) -> Unit,
    onBack: () -> Unit,
    onOpenStoreUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(1) } // Start or easily switch to Chrome Extensions search & Installed
    var searchQuery by remember { mutableStateOf("") }
    var inspectExt by remember { mutableStateOf<ExtensionEntity?>(null) }
    var pendingEnableConfirm by remember { mutableStateOf<ExtensionEntity?>(null) }
    var showCreateScriptDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else uri.lastPathSegment
            } ?: uri.lastPathSegment
            onInstallFromUri(uri, name)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Extensions & Scripts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCreateScriptDialog = true },
                        modifier = Modifier.testTag("ext_add_userscript_button")
                    ) {
                        Icon(Icons.Filled.Code, contentDescription = "Write UserScript")
                    }
                    IconButton(
                        onClick = {
                            filePicker.launch(arrayOf("application/zip", "application/x-chrome-extension", "application/javascript", "text/javascript", "*/*"))
                        },
                        modifier = Modifier.testTag("ext_install_file_button")
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "Install .zip, .crx, or .user.js")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Live Search bar for finding Chrome Web Extensions & installed scripts
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search Chrome extensions (e.g. Dark Reader, AdBlock, Translate)") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("ext_search_input")
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Installed (${installedExtensions.size})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Web Stores") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Compatibility") })
            }

            val filteredInstalled = installedExtensions.filter { ext ->
                searchQuery.isBlank() ||
                    ext.name.contains(searchQuery, ignoreCase = true) ||
                    ext.description.contains(searchQuery, ignoreCase = true)
            }
            val filteredCatalog = builtInCatalog.filter { item ->
                searchQuery.isBlank() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.description.contains(searchQuery, ignoreCase = true) ||
                    item.extId.contains(searchQuery, ignoreCase = true)
            }

            when (selectedTab) {
                0 -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            InstallBannerCard(
                                onPickBundle = {
                                    filePicker.launch(arrayOf("*/*"))
                                },
                                onWriteScript = { showCreateScriptDialog = true }
                            )
                        }
                        if (filteredInstalled.isEmpty()) {
                            item {
                                Text(
                                    text = if (searchQuery.isNotBlank()) {
                                        "No installed extension matches \"$searchQuery\". Check the Chrome Web tab or search Chrome Web Store."
                                    } else {
                                        "No extensions installed in this profile. Install a .zip WebExtension, .crx bundle, .user.js script, or pick from Chrome Web."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                        } else {
                            items(filteredInstalled, key = { it.id }) { ext ->
                                InstalledExtensionCard(
                                    ext = ext,
                                    onToggle = {
                                        if (!ext.enabled && (ext.riskLevel == "SENSITIVE" || ext.riskLevel == "HIGH")) {
                                            pendingEnableConfirm = ext
                                        } else {
                                            onToggleExtension(ext)
                                        }
                                    },
                                    onOpenPopup = { onOpenPopup(ext) },
                                    onInspect = { inspectExt = ext },
                                    onRemove = { onRemoveExtension(ext) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    val storeSearchUrl = if (searchQuery.isBlank()) {
                        "https://chromewebstore.google.com"
                    } else {
                        "https://chromewebstore.google.com/search/${URLEncoder.encode(searchQuery.trim(), "UTF-8")}"
                    }
                    val edgeSearchUrl = if (searchQuery.isBlank()) {
                        "https://microsoftedge.microsoft.com/addons"
                    } else {
                        "https://microsoftedge.microsoft.com/addons/search?q=${URLEncoder.encode(searchQuery.trim(), "UTF-8")}"
                    }
                    val storeBannerItem: @Composable () -> Unit = {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, WeblioCyan.copy(alpha = 0.45f)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Storefront, contentDescription = null, tint = WeblioCyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Extension Stores (Desktop View)", style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    text = "Select Chrome Web Store or Microsoft Edge Add-ons to browse & download extensions in Desktop mode, or import a .crx / .zip / .user.js file.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onOpenStoreUrl(storeSearchUrl) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("ext_open_cws_button")
                                    ) {
                                        ChromeStoreLogoMark(size = 18.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (searchQuery.isBlank()) "Chrome Web Store" else "Search CWS")
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenStoreUrl(edgeSearchUrl) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("ext_open_edge_button")
                                    ) {
                                        EdgeAddonsLogoMark(size = 18.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (searchQuery.isBlank()) "Microsoft Edge" else "Search Edge")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { filePicker.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Import .crx / .zip / .user.js from Device")
                                }
                            }
                        }
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (searchQuery.isBlank()) {
                            item { storeBannerItem() }
                        } else {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onOpenStoreUrl(storeSearchUrl) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("ext_open_cws_button")
                                    ) {
                                        ChromeStoreLogoMark(size = 18.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Search \"${searchQuery.trim()}\" in Chrome Web Store")
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenStoreUrl(edgeSearchUrl) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("ext_open_edge_button")
                                    ) {
                                        EdgeAddonsLogoMark(size = 18.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Search \"${searchQuery.trim()}\" in Microsoft Edge Add-ons")
                                    }
                                }
                            }
                        }

                        if (filteredCatalog.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "No built-in extension matches \"$searchQuery\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(filteredCatalog, key = { it.extId }) { item ->
                                val installedMatch = installedExtensions.firstOrNull { it.extId == item.extId }
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Filled.Extension,
                                                contentDescription = null,
                                                tint = if (installedMatch?.enabled == true) WeblioSuccess else WeblioCyan
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                                Text(
                                                    text = "v${item.version} • ${if (item.sourceType == "CHROME_WEBEXT") "Chrome WebExtension" else "Weblo Engine"}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = WeblioCyan
                                                )
                                            }
                                            if (installedMatch != null) {
                                                Switch(
                                                    checked = installedMatch.enabled,
                                                    onCheckedChange = { onToggleExtension(installedMatch) }
                                                )
                                            }
                                        }
                                        Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (installedMatch == null) {
                                                Button(
                                                    onClick = { onInstallBuiltIn(item) },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("Install & Use Extension")
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { onToggleExtension(installedMatch) },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(if (installedMatch.enabled) "Active (Tap to Pause)" else "Enable Extension")
                                                }
                                                TextButton(onClick = { onOpenPopup(installedMatch) }) {
                                                    Text("Runtime")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, WeblioElectricBlue.copy(alpha = 0.5f)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Engine Architecture & CWS Limitations", style = MaterialTheme.typography.titleMedium, color = WeblioCyan)
                                    Text(
                                        text = BrowserEngineInfo.SELECTED_ENGINE,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(
                                        text = "Android System WebView does not embed Chromium's desktop C++ extension daemon, so arbitrary Chrome Web Store .crx binaries using nativeMessaging, chrome.debugger, or MV3 offscreen service workers cannot run unmodified. Weblo executes real .user.js UserScripts, unpacked .zip/.crx content scripts, CSS stylesheets, and popup HTML via our WebExtension JS bridge.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        items(BrowserEngineInfo.testedCompatibilityMatrix) { entry ->
                            val badgeColor = when (entry.supportLevel) {
                                SupportLevel.FULL_VERIFIED -> WeblioSuccess
                                SupportLevel.PARTIAL_SHIM -> WeblioWarning
                                SupportLevel.UNSUPPORTED_NATIVE -> WeblioDanger
                            }
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (entry.supportLevel == SupportLevel.FULL_VERIFIED) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = badgeColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(entry.extensionName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        Text(entry.supportLevel.label, style = MaterialTheme.typography.labelSmall, color = badgeColor)
                                    }
                                    Text(entry.testedBehavior, style = MaterialTheme.typography.bodySmall)
                                    Text(entry.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inspectExt?.let { ext ->
        val integrityOk = com.example.engine.ExtensionEngine.verifyExtensionIntegrity(ext)
        AlertDialog(
            onDismissRequest = { inspectExt = null },
            title = { Text(ext.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Version: ${ext.version}", style = MaterialTheme.typography.bodyMedium)
                    Text("Type: ${ext.sourceType}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "Risk Level: ${ext.riskLevel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (ext.riskLevel) {
                            "HIGH" -> WeblioDanger
                            "SENSITIVE" -> WeblioWarning
                            else -> WeblioSuccess
                        }
                    )
                    Text(
                        text = if (integrityOk) "Integrity: Verified" else "Integrity: Tampered Hash Mismatch!",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (integrityOk) WeblioSuccess else WeblioDanger
                    )
                    if (ext.contentHashSha256.isNotBlank()) {
                        Text("SHA-256: ${ext.contentHashSha256.take(24)}...", style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Run At: ${ext.runAt}", style = MaterialTheme.typography.labelSmall)
                    Text("Permissions: ${ext.permissionsJson}", style = MaterialTheme.typography.labelSmall)
                    Text("URL Matches: ${ext.matchPatternsJson}", style = MaterialTheme.typography.labelSmall)
                    if (ext.permissionWarningsJson != "[]" && ext.permissionWarningsJson.isNotBlank()) {
                        Text(
                            text = "Security Warnings: ${ext.permissionWarningsJson}",
                            style = MaterialTheme.typography.bodySmall,
                            color = WeblioWarning
                        )
                    }
                    ext.lastError?.let {
                        Text("Status / Diagnostics: $it", style = MaterialTheme.typography.bodySmall, color = WeblioWarning)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectExt = null }) { Text("Close") }
            }
        )
    }

    pendingEnableConfirm?.let { ext ->
        AlertDialog(
            onDismissRequest = { pendingEnableConfirm = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = WeblioDanger) },
            title = { Text("Enable ${ext.riskLevel} Risk Extension?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${ext.name} requests sensitive permissions or accesses page scripts across sites.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Permissions: ${ext.permissionsJson}", style = MaterialTheme.typography.labelSmall)
                    if (ext.permissionWarningsJson != "[]") {
                        Text("Warnings: ${ext.permissionWarningsJson}", style = MaterialTheme.typography.bodySmall, color = WeblioWarning)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onToggleExtension(ext)
                    pendingEnableConfirm = null
                }) { Text("Enable Anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEnableConfirm = null }) { Text("Cancel") }
            }
        )
    }

    if (showCreateScriptDialog) {
        CreateUserScriptDialog(
            onDismiss = { showCreateScriptDialog = false },
            onSave = { name, script ->
                onCreateUserScript(name, script)
                showCreateScriptDialog = false
            }
        )
    }
}

@Composable
private fun InstallBannerCard(
    onPickBundle: () -> Unit,
    onWriteScript: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, WeblioCyan.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = WeblioCyan)
                Spacer(Modifier.width(8.dp))
                Text("Install Local Extension or UserScript", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = "Load real unpacked .zip WebExtensions, .crx packages, or Tampermonkey .user.js scripts from device storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickBundle, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import .zip/.crx/.js")
                }
                OutlinedButton(onClick = onWriteScript, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Script")
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    ext: ExtensionEntity,
    onToggle: () -> Unit,
    onOpenPopup: () -> Unit,
    onInspect: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Extension, contentDescription = null, tint = if (ext.enabled) WeblioCyan else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(ext.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "v${ext.version} • ${ext.sourceType} • Risk: ${ext.riskLevel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (ext.riskLevel) {
                            "HIGH" -> WeblioDanger
                            "SENSITIVE" -> WeblioWarning
                            else -> WeblioCyan
                        }
                    )
                }
                Switch(checked = ext.enabled, onCheckedChange = { onToggle() })
            }
            Text(ext.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ext.riskLevel != "LOW" && ext.permissionWarningsJson != "[]") {
                Text(
                    text = "Security notice: ${ext.permissionWarningsJson}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ext.riskLevel == "HIGH") WeblioDanger else WeblioWarning
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenPopup) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (ext.hasPopup) "Open Popup" else "Runtime")
                }
                TextButton(onClick = onInspect) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag("ext_delete_${ext.id}")
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove extension", tint = WeblioDanger)
                }
            }
        }
    }
}

@Composable
private fun CreateUserScriptDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("Custom Page Tweaker") }
    var scriptBody by remember {
        mutableStateOf(
            """
            // ==UserScript==
            // @name         Custom Page Tweaker
            // @version      1.0
            // @description  Example UserScript
            // @match        *://*/*
            // @run-at       document-end
            // ==/UserScript==
            console.log('Weblo UserScript active on', location.href);
            """.trimIndent()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create UserScript (.user.js)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Script Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = scriptBody,
                    onValueChange = { scriptBody = it },
                    label = { Text("JavaScript Code") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave("$name.user.js", scriptBody) }, enabled = scriptBody.isNotBlank()) {
                Text("Install Script")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ExtensionSecurityReviewDialog(
    extension: ExtensionEntity,
    onEnable: () -> Unit,
    onKeepDisabled: () -> Unit,
    onDelete: () -> Unit
) {
    val isHighRisk = extension.riskLevel == "HIGH"
    val riskColor = if (isHighRisk) WeblioDanger else WeblioWarning
    val permissionsList = remember(extension.permissionsJson) {
        parseJsonStringList(extension.permissionsJson)
    }
    val matchesList = remember(extension.matchPatternsJson) {
        parseJsonStringList(extension.matchPatternsJson)
    }
    val warningsList = remember(extension.permissionWarningsJson) {
        parseJsonStringList(extension.permissionWarningsJson)
    }

    AlertDialog(
        onDismissRequest = onKeepDisabled,
        modifier = Modifier.testTag("extension_security_review_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = riskColor,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Review ${extension.name}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = riskColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, riskColor.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "v${extension.version} • ${extension.riskLevel} Risk",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = riskColor
                        )
                        Text(
                            text = "Installed disabled by Weblo Extension Guard. Review requested permissions before enabling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (permissionsList.isNotEmpty()) {
                    Text(
                        text = "Requested Permissions",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = permissionsList.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (matchesList.isNotEmpty()) {
                    Text(
                        text = "Site Access",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = matchesList.take(4).joinToString(", ") +
                            if (matchesList.size > 4) " (+${matchesList.size - 4} more)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (warningsList.isNotEmpty()) {
                    Text(
                        text = "Security Findings",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = riskColor
                    )
                    warningsList.forEach { warning ->
                        Text(
                            text = "• $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = riskColor
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDelete,
                    border = BorderStroke(1.dp, WeblioDanger.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_review_delete_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = WeblioDanger,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Remove Extension", color = WeblioDanger)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onEnable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isHighRisk) WeblioDanger else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.testTag("ext_review_enable_button")
            ) {
                Text("Enable Extension")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepDisabled,
                modifier = Modifier.testTag("ext_review_keep_disabled_button")
            ) {
                Text("Keep Disabled")
            }
        }
    )
}

private fun parseJsonStringList(rawJson: String): List<String> {
    if (rawJson.isBlank() || rawJson == "[]") return emptyList()
    return runCatching {
        val arr = JSONArray(rawJson)
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotBlank()) add(item)
            }
        }
    }.getOrDefault(emptyList())
}

