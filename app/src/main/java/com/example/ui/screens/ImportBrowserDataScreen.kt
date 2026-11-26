package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.BrowserImportManager
import com.example.engine.ImportDataSummary
import com.example.engine.ImportDataType
import com.example.engine.InstalledBrowserInfo
import com.example.ui.theme.WeblioCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportBrowserDataScreen(
    profileId: Long,
    importManager: BrowserImportManager,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit,
    discoveredBrowsersOverride: List<InstalledBrowserInfo>? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    var browsers by remember(discoveredBrowsersOverride) {
        mutableStateOf(discoveredBrowsersOverride ?: emptyList())
    }
    var isLoadingBrowsers by remember(discoveredBrowsersOverride) {
        mutableStateOf(discoveredBrowsersOverride == null)
    }
    var selectedTypes by remember {
        mutableStateOf(ImportDataType.entries.toSet())
    }
    var selectedBrowser by remember { mutableStateOf<InstalledBrowserInfo?>(null) }
    var activeImportSource by remember { mutableStateOf("Browser Export") }
    var isImporting by remember { mutableStateOf(false) }
    var lastSummary by remember { mutableStateOf<ImportDataSummary?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteDraft by remember { mutableStateOf("") }
    var pasteSourceName by remember { mutableStateOf("Copied Browser Data") }

    LaunchedEffect(discoveredBrowsersOverride) {
        if (discoveredBrowsersOverride == null) {
            isLoadingBrowsers = true
            val found = withContext(Dispatchers.IO) {
                importManager.discoverInstalledBrowsers(context)
            }
            browsers = found
            isLoadingBrowsers = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val sourceLabel = activeImportSource
            selectedBrowser = null
            isImporting = true
            scope.launch {
                val summary = importManager.importFromUris(
                    context = context,
                    profileId = profileId,
                    uris = uris,
                    sourceName = sourceLabel,
                    selectedTypes = selectedTypes
                )
                lastSummary = summary
                isImporting = false
                onShowMessage(summary.toUserMessage())
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Import Data") },
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
                .testTag("import_data_list")
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SyncAlt, contentDescription = null, tint = WeblioCyan)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Choose What to Import",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Tap any browser icon installed on your phone to import its visited sites, history, bookmarks, passwords, and top sites into Weblo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ImportDataType.entries.forEach { type ->
                                val isSelected = type in selectedTypes
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val next = if (isSelected) selectedTypes - type else selectedTypes + type
                                        if (next.isNotEmpty()) selectedTypes = next
                                    },
                                    label = { Text(type.label) },
                                    modifier = Modifier.testTag("import_type_${type.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }

            if (isImporting) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Importing data into your active profile...")
                        }
                    }
                }
            }

            lastSummary?.let { summary ->
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_summary_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = WeblioCyan)
                            Column {
                                Text(
                                    summary.toUserMessage(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Installed Browsers (${browsers.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = WeblioCyan
                )
            }

            if (isLoadingBrowsers) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (browsers.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_no_browsers_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "No other browsers detected on this device",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "You can still import any browser's exported HTML, JSON, or CSV file directly below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val rows = browsers.chunked(2)
                items(rows.size) { rowIndex ->
                    val pair = rows[rowIndex]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { browser ->
                            InstalledBrowserTile(
                                browser = browser,
                                onClick = {
                                    activeImportSource = browser.appName
                                    selectedBrowser = browser
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Direct File or Clipboard Import",
                    style = MaterialTheme.typography.labelLarge,
                    color = WeblioCyan,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Supports Bookmarks HTML, Chrome / Google Takeout History JSON, Firefox JSON, Passwords CSV, or copied website links.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                activeImportSource = "Export File"
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("import_direct_file_button")
                        ) {
                            Icon(Icons.Filled.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose Browser Export File(s)")
                        }
                        OutlinedButton(
                            onClick = {
                                pasteSourceName = "Clipboard Links"
                                pasteDraft = clipboard.getText()?.text.orEmpty()
                                showPasteDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("import_direct_paste_button")
                        ) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Paste Links / Export Text")
                        }
                    }
                }
            }
        }
    }

    selectedBrowser?.let { browser ->
        AlertDialog(
            onDismissRequest = { selectedBrowser = null },
            icon = {
                BrowserAppIcon(
                    drawable = browser.icon,
                    contentDescription = browser.appName,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { Text("Import from ${browser.appName}") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("import_browser_dialog")
                ) {
                    Text(
                        "Android isolates each browser's private app folder, so select exported data or paste links from ${browser.appName}:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    browser.exportGuideSteps.forEachIndexed { idx, step ->
                        Text(
                            "${idx + 1}. $step",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(browser.packageName)
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(launch) }
                                    .onFailure { onShowMessage("Could not open ${browser.appName}") }
                            } else {
                                onShowMessage("${browser.appName} cannot be launched directly")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_launch_browser_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open ${browser.appName}")
                    }
                    OutlinedButton(
                        onClick = {
                            pasteSourceName = browser.appName
                            pasteDraft = clipboard.getText()?.text.orEmpty()
                            selectedBrowser = null
                            showPasteDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_dialog_paste_button")
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste Links / Export Text")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeImportSource = browser.appName
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.testTag("import_pick_files_button")
                ) {
                    Text("Choose File(s) & Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedBrowser = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste Data from $pasteSourceName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste copied URLs, Chrome/Firefox JSON, Netscape Bookmarks HTML, or Passwords CSV:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pasteDraft,
                        onValueChange = { pasteDraft = it },
                        minLines = 4,
                        maxLines = 8,
                        placeholder = { Text("https://example.com\n...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_paste_text_field")
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = pasteDraft.isNotBlank() && !isImporting,
                    onClick = {
                        val content = pasteDraft
                        val source = pasteSourceName
                        showPasteDialog = false
                        isImporting = true
                        scope.launch {
                            val summary = importManager.parseAndImport(
                                profileId = profileId,
                                rawText = content,
                                sourceName = source,
                                selectedTypes = selectedTypes
                            )
                            lastSummary = summary
                            isImporting = false
                            onShowMessage(summary.toUserMessage())
                        }
                    },
                    modifier = Modifier.testTag("import_paste_confirm_button")
                ) {
                    Text("Import Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun InstalledBrowserTile(
    browser: InstalledBrowserInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("import_browser_card_${browser.packageName}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BrowserAppIcon(
                drawable = browser.icon,
                contentDescription = "${browser.appName} icon",
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = browser.appName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Tap to import",
                style = MaterialTheme.typography.labelSmall,
                color = WeblioCyan
            )
        }
    }
}

@Composable
private fun BrowserAppIcon(
    drawable: Drawable?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val imageBitmap: ImageBitmap? = remember(drawable) {
        drawable?.let { d ->
            runCatching {
                if (d is BitmapDrawable && d.bitmap != null) {
                    d.bitmap.asImageBitmap()
                } else {
                    val w = d.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(192) ?: 96
                    val h = d.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(192) ?: 96
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    d.setBounds(0, 0, canvas.width, canvas.height)
                    d.draw(canvas)
                    bmp.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            modifier = modifier.clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(
                imageVector = Icons.Filled.Public,
                contentDescription = contentDescription,
                tint = WeblioCyan,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
