package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.db.DownloadEntity
import com.example.data.db.DownloadFileCategory
import com.example.data.db.DownloadStatus
import com.example.engine.ApkInstallInfo
import com.example.engine.WeblioDownloadManager
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioSuccess
import com.example.ui.theme.WeblioWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class DownloadFilterTab(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    OTHER("Other")
}

private sealed interface DownloadBlock {
    data class ImageGrid(val items: List<DownloadEntity>) : DownloadBlock
    data class FeaturedImage(val item: DownloadEntity) : DownloadBlock
    data class FileRow(val item: DownloadEntity) : DownloadBlock
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntity>,
    downloadLocationLabel: String,
    onEnqueueUrl: (String, String?) -> Unit,
    onPause: (Long) -> Unit,
    onResumeOrRetry: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenFile: (DownloadEntity) -> Result<Intent>,
    onClearAll: () -> Unit,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit,
    askBeforeDownload: Boolean = true,
    downloadNotificationsEnabled: Boolean = true,
    onToggleAskBeforeDownload: (Boolean) -> Unit = {},
    onToggleDownloadNotifications: (Boolean) -> Unit = {},
    onShareFile: (DownloadEntity) -> Intent = { item ->
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, item.fileName)
            putExtra(Intent.EXTRA_TEXT, item.url)
        }
    },
    onRenameFile: (DownloadEntity, String) -> Unit = { _, _ -> },
    totalStorageBytesOverride: Long? = null,
    onReviewQuarantined: (DownloadEntity) -> Unit = {},
    onInspectApk: (DownloadEntity) -> Result<ApkInstallInfo> = { entity ->
        Result.success(
            ApkInstallInfo(
                fileName = entity.fileName,
                fileSizeBytes = entity.downloadedBytes.coerceAtLeast(0L),
                appLabel = null,
                packageName = null,
                versionName = null,
                versionCode = null,
                securityNotice = "Android will ask you to confirm installation."
            )
        )
    },
    onOpenHtmlPreview: (DownloadEntity) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf(DownloadFilterTab.ALL) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showNewDownloadDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var apkInstallPromptTarget by remember { mutableStateOf<Pair<DownloadEntity, ApkInstallInfo>?>(null) }
    var pendingUnknownSourcesApk by remember { mutableStateOf<DownloadEntity?>(null) }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = pendingUnknownSourcesApk
        pendingUnknownSourcesApk = null
        if (pending != null) {
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
            if (allowed) {
                handleOpenDownload(context, pending, onOpenFile, onShowMessage)
            } else {
                onShowMessage("Allow 'Install unknown apps' in Settings to install ${pending.fileName}")
            }
        }
    }

    val totalDeviceBytes = remember(totalStorageBytesOverride) {
        totalStorageBytesOverride ?: runCatching {
            StatFs(context.filesDir.absolutePath).totalBytes
        }.getOrDefault(0L)
    }
    val usedBytes = remember(downloads) {
        downloads.sumOf { item ->
            val diskLen = runCatching { File(item.localPath).takeIf { it.exists() }?.length() ?: 0L }.getOrDefault(0L)
            maxOf(diskLen, item.downloadedBytes.coerceAtLeast(0L))
        }
    }

    val filteredDownloads = remember(downloads, selectedFilter, searchQuery) {
        downloads.filter { item ->
            val matchesFilter = when (selectedFilter) {
                DownloadFilterTab.ALL -> true
                DownloadFilterTab.IMAGES -> item.isImageDownload()
                DownloadFilterTab.OTHER -> !item.isImageDownload()
            }
            val q = searchQuery.trim()
            val matchesQuery = q.isBlank() ||
                item.fileName.contains(q, ignoreCase = true) ||
                extractDomain(item.url).contains(q, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    val groupedSections = remember(filteredDownloads) {
        groupDownloadsByDate(filteredDownloads)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Downloads",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("downloads_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Download settings",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) searchQuery = ""
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("downloads_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search downloads",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("downloads_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close downloads",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                AnimatedVisibility(visible = searchOpen) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search downloads") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("downloads_search_input")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "storage_header") {
                val storageSummary = if (totalDeviceBytes > 0L) {
                    "Using ${formatStorageBytes(usedBytes)} of ${formatStorageCapacity(totalDeviceBytes)}"
                } else {
                    "Using ${formatStorageBytes(usedBytes)} • $downloadLocationLabel"
                }
                Text(
                    text = storageSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .testTag("downloads_storage_summary")
                )
            }

            item(key = "filter_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DownloadFilterTab.entries.forEach { tab ->
                        val selected = selectedFilter == tab
                        DownloadFilterChip(
                            label = tab.label,
                            icon = when (tab) {
                                DownloadFilterTab.ALL -> Icons.Filled.Check
                                DownloadFilterTab.IMAGES -> Icons.Outlined.Image
                                DownloadFilterTab.OTHER -> Icons.Outlined.Description
                            },
                            selected = selected,
                            onClick = { selectedFilter = tab },
                            modifier = Modifier.testTag("downloads_filter_${tab.name.lowercase()}")
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (groupedSections.isEmpty()) {
                item(key = "empty_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                tint = WeblioCyan,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = if (downloads.isEmpty()) "No downloads yet" else "No matching downloads",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Images, archives, PDFs, and media downloaded in WEBLIO appear here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { showNewDownloadDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Download from URL")
                            }
                        }
                    }
                }
            } else {
                groupedSections.forEach { (dateLabel, blocks) ->
                    item(key = "date_hdr_$dateLabel") {
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)
                        )
                    }

                    blocks.forEachIndexed { idx, block ->
                        when (block) {
                            is DownloadBlock.ImageGrid -> {
                                val rows = block.items.chunked(3)
                                rows.forEachIndexed { rowIdx, rowItems ->
                                    item(key = "grid_${dateLabel}_${idx}_$rowIdx") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            rowItems.forEach { imgItem ->
                                                DownloadImageGridTile(
                                                    item = imgItem,
                                                    modifier = Modifier.weight(1f),
                                                    onOpen = {
                                                        handleOpenDownload(context, imgItem, onOpenFile, onShowMessage)
                                                    },
                                                    onShare = {
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent.createChooser(onShareFile(imgItem), "Share ${imgItem.fileName}")
                                                            )
                                                        }.onFailure { onShowMessage("Unable to share file") }
                                                    },
                                                    onRename = { renameTarget = imgItem },
                                                    onDelete = { onDelete(imgItem.id) }
                                                )
                                            }
                                            repeat(3 - rowItems.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            is DownloadBlock.FeaturedImage -> {
                                item(key = "featured_${block.item.id}") {
                                    val item = block.item
                                    FeaturedImageDownloadCard(
                                        item = item,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        onOpen = { handleOpenDownload(context, item, onOpenFile, onShowMessage) },
                                        onShare = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent.createChooser(onShareFile(item), "Share ${item.fileName}")
                                                )
                                            }.onFailure { onShowMessage("Unable to share file") }
                                        },
                                        onRename = { renameTarget = item },
                                        onDelete = { onDelete(item.id) },
                                        onPause = { onPause(item.id) },
                                        onResumeOrRetry = { onResumeOrRetry(item.id) }
                                    )
                                }
                            }

                            is DownloadBlock.FileRow -> {
                                item(key = "file_${block.item.id}") {
                                    val item = block.item
                                    DownloadFileRowItem(
                                        item = item,
                                        onOpen = {
                                            when {
                                                item.quarantined || item.status == DownloadStatus.QUARANTINED.name -> {
                                                    onReviewQuarantined(item)
                                                }
                                                item.isApkDownload() -> {
                                                    onInspectApk(item)
                                                        .onSuccess { info -> apkInstallPromptTarget = item to info }
                                                        .onFailure { err -> onShowMessage(err.message ?: "Invalid APK file") }
                                                }
                                                item.isHtmlDownload() -> {
                                                    onOpenHtmlPreview(item)
                                                }
                                                else -> {
                                                    handleOpenDownload(context, item, onOpenFile, onShowMessage)
                                                }
                                            }
                                        },
                                        onShare = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent.createChooser(onShareFile(item), "Share ${item.fileName}")
                                                )
                                            }.onFailure { onShowMessage("Unable to share file") }
                                        },
                                        onRename = { renameTarget = item },
                                        onDelete = { onDelete(item.id) },
                                        onPause = { onPause(item.id) },
                                        onResumeOrRetry = { onResumeOrRetry(item.id) },
                                        onCancel = { onCancel(item.id) },
                                        onReview = { onReviewQuarantined(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Download Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Download location: $downloadLocationLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ask where to save files", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Confirm filename before starting download",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = askBeforeDownload,
                            onCheckedChange = onToggleAskBeforeDownload
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Download notifications", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Show progress & completion alerts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = downloadNotificationsEnabled,
                            onCheckedChange = onToggleDownloadNotifications
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showSettingsDialog = false
                                showNewDownloadDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New URL")
                        }
                        if (downloads.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    onClearAll()
                                    showSettingsDialog = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = WeblioDanger, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Clear all", color = WeblioDanger)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Done") }
            }
        )
    }

    if (showNewDownloadDialog) {
        var url by remember { mutableStateOf("") }
        var fileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDownloadDialog = false },
            title = { Text("Download File by URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("Optional File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEnqueueUrl(url.trim(), fileName.trim().ifBlank { null })
                        showNewDownloadDialog = false
                    },
                    enabled = url.startsWith("http://") || url.startsWith("https://")
                ) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { showNewDownloadDialog = false }) { Text("Cancel") } }
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.fileName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename file") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_download_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameFile(target, newName)
                        renameTarget = null
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    apkInstallPromptTarget?.let { (apkEntity, info) ->
        AlertDialog(
            onDismissRequest = { apkInstallPromptTarget = null },
            icon = {
                val dialogIcon = info.appIcon
                if (dialogIcon != null) {
                    Image(
                        bitmap = dialogIcon.asImageBitmap(),
                        contentDescription = info.appLabel ?: info.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .testTag("apk_install_dialog_icon")
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Android,
                        contentDescription = null,
                        tint = WeblioCyan
                    )
                }
            },
            title = {
                Text(
                    text = info.appLabel?.let { "Install $it?" } ?: "Install application?",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = info.fileName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    )
                    val metaLines = buildList {
                        add("Size: ${formatBytes(info.fileSizeBytes)}")
                        info.packageName?.takeIf { it.isNotBlank() }?.let { add("Package: $it") }
                        info.versionName?.takeIf { it.isNotBlank() }?.let { ver ->
                            val codeSuffix = info.versionCode?.let { " ($it)" } ?: ""
                            add("Version: $ver$codeSuffix")
                        }
                    }
                    Text(
                        text = metaLines.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = info.securityNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (apkEntity.securityVerdict == "DANGEROUS") WeblioDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        apkInstallPromptTarget = null
                        val needsUnknownSourcesGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            !context.packageManager.canRequestPackageInstalls()
                        if (needsUnknownSourcesGrant) {
                            val settingsIntent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")
                            )
                            if (settingsIntent.resolveActivity(context.packageManager) != null) {
                                pendingUnknownSourcesApk = apkEntity
                                runCatching { unknownSourcesLauncher.launch(settingsIntent) }
                                    .onFailure {
                                        pendingUnknownSourcesApk = null
                                        handleOpenDownload(context, apkEntity, onOpenFile, onShowMessage)
                                    }
                            } else {
                                handleOpenDownload(context, apkEntity, onOpenFile, onShowMessage)
                            }
                        } else {
                            handleOpenDownload(context, apkEntity, onOpenFile, onShowMessage)
                        }
                    },
                    modifier = Modifier.testTag("apk_install_confirm")
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { apkInstallPromptTarget = null },
                    modifier = Modifier.testTag("apk_install_cancel")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DownloadFilterChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f || isSystemInDarkTheme()
    val selectedBg = if (isDark) Color(0xFF174A7A) else WeblioElectricBlue.copy(alpha = 0.14f)
    val selectedFg = if (isDark) Color(0xFFD2E4FC) else WeblioElectricBlue
    val containerColor = if (selected) selectedBg else Color.Transparent
    val contentColor = if (selected) selectedFg else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (selected || icon != Icons.Filled.Check) {
            Icon(
                imageVector = if (selected) Icons.Filled.Check else icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = contentColor
        )
    }
}

@Composable
private fun DownloadImageGridTile(
    item: DownloadEntity,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .testTag("download_grid_item_${item.id}")
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(item.localPath))
                .crossfade(true)
                .build(),
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Image, contentDescription = item.fileName, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { menuExpanded = true }
                    .testTag("download_more_${item.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options for ${item.fileName}",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            DownloadOverflowMenu(
                expanded = menuExpanded,
                item = item,
                onDismiss = { menuExpanded = false },
                onShare = onShare,
                onRename = onRename,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun FeaturedImageDownloadCard(
    item: DownloadEntity,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onResumeOrRetry: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = item.status == DownloadStatus.COMPLETED.name, onClick = onOpen)
            .testTag("download_featured_card_${item.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(item.localPath))
                        .crossfade(true)
                        .build(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = WeblioCyan, modifier = Modifier.size(44.dp))
                    },
                    error = {
                        Icon(Icons.Filled.Image, contentDescription = item.fileName, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildDownloadSubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColorFor(item),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("download_more_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options for ${item.fileName}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DownloadOverflowMenu(
                        expanded = menuExpanded,
                        item = item,
                        onDismiss = { menuExpanded = false },
                        onShare = onShare,
                        onRename = onRename,
                        onDelete = onDelete,
                        onPause = onPause,
                        onResumeOrRetry = onResumeOrRetry
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadFileRowItem(
    item: DownloadEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onResumeOrRetry: () -> Unit,
    onCancel: () -> Unit,
    onReview: () -> Unit = {}
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val isApk = item.category == DownloadFileCategory.APK.name || item.isApkDownload()
    val apkIconBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = item.id,
        key2 = item.localPath,
        key3 = item.status
    ) {
        value = if (isApk && item.localPath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                WeblioDownloadManager.extractApkIconBitmap(context, item.localPath)
            }
        } else {
            null
        }
    }
    val icon = when {
        item.category == DownloadFileCategory.PDF.name -> Icons.Filled.PictureAsPdf
        isApk -> Icons.Filled.Android
        item.isHtmlDownload() -> Icons.Filled.Language
        item.category == DownloadFileCategory.ARCHIVE.name -> Icons.Filled.Archive
        item.category == DownloadFileCategory.IMAGE.name -> Icons.Filled.Image
        item.category == DownloadFileCategory.AUDIO.name -> Icons.Filled.AudioFile
        item.category == DownloadFileCategory.VIDEO.name -> Icons.Filled.VideoFile
        else -> Icons.Filled.Description
    }
    val isQuarantined = item.quarantined || item.status == DownloadStatus.QUARANTINED.name

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.status == DownloadStatus.COMPLETED.name || isQuarantined, onClick = onOpen)
            .padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .testTag("download_file_row_${item.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconShape = if (apkIconBitmap != null) RoundedCornerShape(10.dp) else CircleShape
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(iconShape)
                    .background(
                        if (apkIconBitmap != null) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bmp = apkIconBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .testTag("download_apk_icon_${item.id}")
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = item.category,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildDownloadSubtitle(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColorFor(item),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = WeblioDanger)
                }
            }

            if (item.status == DownloadStatus.QUARANTINED.name) {
                TextButton(
                    onClick = onReview,
                    modifier = Modifier.testTag("download_review_${item.id}")
                ) {
                    Text(
                        text = "Review",
                        color = if (item.securityVerdict == "DANGEROUS") WeblioDanger else WeblioWarning,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (item.status == DownloadStatus.DOWNLOADING.name || item.status == DownloadStatus.QUEUED.name) {
                IconButton(onClick = onPause, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (item.status == DownloadStatus.PAUSED.name || item.status == DownloadStatus.FAILED.name) {
                IconButton(onClick = onResumeOrRetry, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Retry", tint = WeblioCyan)
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("download_more_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options for ${item.fileName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadOverflowMenu(
                    expanded = menuExpanded,
                    item = item,
                    onDismiss = { menuExpanded = false },
                    onShare = onShare,
                    onRename = onRename,
                    onDelete = onDelete,
                    onPause = onPause,
                    onResumeOrRetry = onResumeOrRetry,
                    onCancel = onCancel
                )
            }
        }

        if (item.status == DownloadStatus.DOWNLOADING.name || item.status == DownloadStatus.PAUSED.name) {
            Spacer(Modifier.height(6.dp))
            if (item.totalBytes > 0) {
                val progress = (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp, end = 12.dp)
                        .clip(CircleShape)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp, end = 12.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun DownloadOverflowMenu(
    expanded: Boolean,
    item: DownloadEntity,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPause: (() -> Unit)? = null,
    onResumeOrRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if ((item.status == DownloadStatus.DOWNLOADING.name || item.status == DownloadStatus.QUEUED.name) && onPause != null) {
            DropdownMenuItem(
                text = { Text("Pause") },
                onClick = {
                    onDismiss()
                    onPause()
                }
            )
            if (onCancel != null) {
                DropdownMenuItem(
                    text = { Text("Cancel") },
                    onClick = {
                        onDismiss()
                        onCancel()
                    }
                )
            }
        } else if ((item.status == DownloadStatus.PAUSED.name || item.status == DownloadStatus.FAILED.name) && onResumeOrRetry != null) {
            DropdownMenuItem(
                text = { Text(if (item.status == DownloadStatus.PAUSED.name) "Resume" else "Retry") },
                onClick = {
                    onDismiss()
                    onResumeOrRetry()
                }
            )
        }

        DropdownMenuItem(
            text = { Text("Share") },
            onClick = {
                onDismiss()
                onShare()
            },
            modifier = Modifier.testTag("download_menu_share_${item.id}")
        )
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = {
                onDismiss()
                onRename()
            },
            modifier = Modifier.testTag("download_menu_rename_${item.id}")
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = {
                onDismiss()
                onDelete()
            },
            modifier = Modifier.testTag("download_menu_delete_${item.id}")
        )
    }
}

private fun handleOpenDownload(
    context: android.content.Context,
    item: DownloadEntity,
    onOpenFile: (DownloadEntity) -> Result<Intent>,
    onShowMessage: (String) -> Unit
) {
    onOpenFile(item).onSuccess { intent ->
        runCatching { context.startActivity(intent) }
            .onFailure { onShowMessage("No installed app can open ${item.mimeType}") }
    }.onFailure {
        onShowMessage(it.message ?: "File unavailable")
    }
}

private fun DownloadEntity.isImageDownload(): Boolean {
    return category == DownloadFileCategory.IMAGE.name ||
        mimeType.startsWith("image/", ignoreCase = true) ||
        fileName.lowercase().let {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") || it.endsWith(".gif")
        }
}

private fun DownloadEntity.isApkDownload(): Boolean {
    return category == DownloadFileCategory.APK.name ||
        mimeType.equals("application/vnd.android.package-archive", ignoreCase = true) ||
        fileName.endsWith(".apk", ignoreCase = true)
}

private fun DownloadEntity.isHtmlDownload(): Boolean {
    val lower = fileName.lowercase()
    return mimeType.equals("text/html", ignoreCase = true) ||
        mimeType.equals("application/xhtml+xml", ignoreCase = true) ||
        lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")
}

private fun groupDownloadsByDate(items: List<DownloadEntity>): List<Pair<String, List<DownloadBlock>>> {
    if (items.isEmpty()) return emptyList()
    val sorted = items.sortedByDescending { it.createdAt }
    val todayCal = Calendar.getInstance()
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val dayFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    val grouped = LinkedHashMap<String, MutableList<DownloadEntity>>()
    sorted.forEach { item ->
        val itemCal = Calendar.getInstance().apply { timeInMillis = item.createdAt }
        val formatted = dayFmt.format(Date(item.createdAt))
        val label = when {
            isSameDay(itemCal, todayCal) -> "Today - $formatted"
            isSameDay(itemCal, yesterdayCal) -> "Yesterday - $formatted"
            else -> formatted
        }
        grouped.getOrPut(label) { mutableListOf() }.add(item)
    }

    return grouped.map { (dateLabel, dayItems) ->
        dateLabel to segmentDownloadBlocks(dayItems)
    }
}

private fun segmentDownloadBlocks(dayItems: List<DownloadEntity>): List<DownloadBlock> {
    val blocks = mutableListOf<DownloadBlock>()
    var i = 0
    while (i < dayItems.size) {
        val current = dayItems[i]
        if (current.isImageDownload() && current.status == DownloadStatus.COMPLETED.name) {
            val imageRun = mutableListOf<DownloadEntity>()
            while (i < dayItems.size &&
                dayItems[i].isImageDownload() &&
                dayItems[i].status == DownloadStatus.COMPLETED.name
            ) {
                imageRun.add(dayItems[i])
                i++
            }
            if (imageRun.size >= 2) {
                blocks.add(DownloadBlock.ImageGrid(imageRun))
            } else {
                blocks.add(DownloadBlock.FeaturedImage(imageRun.first()))
            }
        } else {
            blocks.add(DownloadBlock.FileRow(current))
            i++
        }
    }
    return blocks
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean {
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

private fun extractDomain(url: String): String {
    return runCatching { Uri.parse(url).host?.removePrefix("www.") ?: "" }
        .getOrDefault("")
        .ifBlank { "local" }
}

@Composable
private fun subtitleColorFor(item: DownloadEntity): Color {
    return when {
        item.status == DownloadStatus.FAILED.name || item.status == DownloadStatus.CANCELLED.name -> WeblioDanger
        item.status == DownloadStatus.QUARANTINED.name && item.securityVerdict == "DANGEROUS" -> WeblioDanger
        item.status == DownloadStatus.QUARANTINED.name || item.userOverridden -> WeblioWarning
        item.status == DownloadStatus.PAUSED.name || item.status == DownloadStatus.SCANNING.name -> WeblioWarning
        item.securityVerdict == "SAFE" && item.status == DownloadStatus.COMPLETED.name -> WeblioSuccess
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun buildDownloadSubtitle(item: DownloadEntity): String {
    val sizeBytes = when {
        item.totalBytes > 0 -> item.totalBytes
        item.downloadedBytes > 0 -> item.downloadedBytes
        else -> 0L
    }
    val domain = extractDomain(item.url)
    return when (item.status) {
        DownloadStatus.COMPLETED.name -> {
            val badge = when {
                item.userOverridden -> " • ${item.securityVerdict} (Overridden)"
                item.securityVerdict == "SAFE" -> " • No known threats detected."
                else -> ""
            }
            "${formatBytes(sizeBytes)} • $domain$badge"
        }
        DownloadStatus.SCANNING.name -> "Scanning in private quarantine • ${formatBytes(sizeBytes)}"
        DownloadStatus.QUARANTINED.name -> {
            val reason = item.threatCategory ?: item.securityVerdict
            "Quarantined ($reason) • Tap Review"
        }
        DownloadStatus.DOWNLOADING.name -> {
            if (item.totalBytes > 0) {
                val pct = ((item.downloadedBytes * 100) / item.totalBytes).coerceIn(0, 100)
                "Downloading $pct% • ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
            } else {
                "Downloading • ${formatBytes(item.downloadedBytes)}"
            }
        }
        DownloadStatus.PAUSED.name -> "Paused • ${formatBytes(item.downloadedBytes)} • $domain"
        DownloadStatus.FAILED.name -> "Failed • $domain"
        DownloadStatus.CANCELLED.name -> "Cancelled • $domain"
        else -> "Queued • $domain"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.2f kB", kb).replace(".00 ", " ")
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatStorageBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.2f MB", mb)
    }
}

private fun formatStorageCapacity(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f GB", gb)
    } else {
        formatStorageBytes(bytes)
    }
}
