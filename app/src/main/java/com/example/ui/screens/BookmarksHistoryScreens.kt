package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.BookmarkEntity
import com.example.data.db.HistoryEntity
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarks: List<BookmarkEntity>,
    onOpenUrl: (String) -> Unit,
    onAddBookmark: (String, String, String) -> Unit,
    onUpdateBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onExportHtml: suspend () -> String,
    onImportHtml: suspend (String) -> Int,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var editingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val html = onExportHtml()
                context.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
                onShowMessage("Bookmarks exported to HTML")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val html = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                val count = onImportHtml(html)
                onShowMessage("Imported $count bookmarks")
            }
        }
    }

    val folders = bookmarks.map { it.folder }.distinct()
    val filtered = bookmarks.filter { bm ->
        (selectedFolder == null || bm.folder == selectedFolder) &&
            (query.isBlank() || bm.title.contains(query, true) || bm.url.contains(query, true))
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks (${bookmarks.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("text/html", "*/*")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "Import bookmarks HTML")
                    }
                    IconButton(onClick = { exportLauncher.launch("weblo_bookmarks.html") }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Export bookmarks HTML")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = WeblioElectricBlue) {
                Icon(Icons.Filled.Add, contentDescription = "Add bookmark")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search bookmarks...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (folders.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFolder == null,
                        onClick = { selectedFolder = null },
                        label = { Text("All") }
                    )
                    folders.forEach { folder ->
                        FilterChip(
                            selected = selectedFolder == folder,
                            onClick = { selectedFolder = if (selectedFolder == folder) null else folder },
                            label = { Text(folder) }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Bookmark, contentDescription = null, tint = WeblioCyan, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No bookmarks found", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { bm ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUrl(bm.url); onBack() }
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = WeblioCyan)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(bm.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(bm.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(bm.folder, style = MaterialTheme.typography.labelSmall, color = WeblioElectricBlue)
                                }
                                IconButton(onClick = { editingBookmark = bm }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit bookmark")
                                }
                                IconButton(onClick = { onDeleteBookmark(bm) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark", tint = WeblioDanger)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        BookmarkEditDialog(
            title = "Add Bookmark",
            initialTitle = "",
            initialUrl = "",
            initialFolder = selectedFolder ?: "General",
            onDismiss = { showAddDialog = false },
            onConfirm = { t, u, f ->
                onAddBookmark(t, u, f)
                showAddDialog = false
            }
        )
    }

    editingBookmark?.let { bm ->
        BookmarkEditDialog(
            title = "Edit Bookmark",
            initialTitle = bm.title,
            initialUrl = bm.url,
            initialFolder = bm.folder,
            onDismiss = { editingBookmark = null },
            onConfirm = { t, u, f ->
                onUpdateBookmark(bm.copy(title = t, url = u, folder = f))
                editingBookmark = null
            }
        )
    }
}

@Composable
private fun BookmarkEditDialog(
    title: String,
    initialTitle: String,
    initialUrl: String,
    initialFolder: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var bTitle by remember { mutableStateOf(initialTitle) }
    var bUrl by remember { mutableStateOf(initialUrl) }
    var bFolder by remember { mutableStateOf(initialFolder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = bTitle, onValueChange = { bTitle = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = bUrl, onValueChange = { bUrl = it }, label = { Text("URL") }, singleLine = true)
                OutlinedTextField(value = bFolder, onValueChange = { bFolder = it }, label = { Text("Folder") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val formatted = if (bUrl.startsWith("http")) bUrl else "https://$bUrl"
                    onConfirm(bTitle.ifBlank { formatted }, formatted, bFolder.ifBlank { "General" })
                },
                enabled = bUrl.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    history: List<HistoryEntity>,
    onOpenUrl: (String) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onClearRange: (Int?) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var clearMenuExpanded by remember { mutableStateOf(false) }

    val filtered = history.filter {
        query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true)
    }

    val grouped = remember(filtered) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - 86_400_000L

        filtered.groupBy { entry ->
            when {
                entry.visitedAt >= todayStart -> "Today"
                entry.visitedAt >= yesterdayStart -> "Yesterday"
                else -> SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(entry.visitedAt))
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { clearMenuExpanded = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear history")
                        }
                        DropdownMenu(
                            expanded = clearMenuExpanded,
                            onDismissRequest = { clearMenuExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text("Clear Last Hour") }, onClick = { clearMenuExpanded = false; onClearRange(1) })
                            DropdownMenuItem(text = { Text("Clear Last 24 Hours") }, onClick = { clearMenuExpanded = false; onClearRange(24) })
                            DropdownMenuItem(text = { Text("Clear Last 7 Days") }, onClick = { clearMenuExpanded = false; onClearRange(24 * 7) })
                            DropdownMenuItem(text = { Text("Clear All History") }, onClick = { clearMenuExpanded = false; onClearRange(null) })
                        }
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search browsing history...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.History, contentDescription = null, tint = WeblioCyan, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No browsing history", style = MaterialTheme.typography.titleMedium)
                        Text("Incognito & Guest browsing is never saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    grouped.forEach { (dateLabel, entries) ->
                        item(key = "header_$dateLabel") {
                            Text(
                                text = dateLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = WeblioCyan,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenUrl(entry.url); onBack() }
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Public, contentDescription = null, tint = WeblioElectricBlue)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(timeFmt.format(Date(entry.visitedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    IconButton(onClick = { onDeleteEntry(entry.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete entry", tint = WeblioDanger)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
