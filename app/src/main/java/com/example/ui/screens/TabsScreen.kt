package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.ClosedTabEntity
import com.example.data.db.TabEntity
import com.example.data.prefs.websiteFaviconUrl
import com.example.ui.BrowserViewModel
import com.example.ui.TabRuntimeState
import com.example.ui.components.WeblioLogoMark
import com.example.ui.components.WebsiteOnlineLogo
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioIncognitoAccent
import com.example.ui.theme.WeblioIncognitoSurface
import com.example.ui.theme.WeblioPeriwinkle

@Composable
fun TabsScreen(
    tabs: List<TabEntity>,
    closedTabs: List<ClosedTabEntity>,
    activeTabId: Long?,
    onSelectTab: (Long) -> Unit,
    onNewTab: (Boolean, String?) -> Unit,
    onCloseTab: (TabEntity) -> Unit,
    onCloseAllTabs: (Boolean?) -> Unit,
    onDuplicateTab: (TabEntity) -> Unit,
    onAssignGroup: (TabEntity, String?) -> Unit,
    onReopenClosed: (ClosedTabEntity) -> Unit,
    onBack: () -> Unit,
    tabPreviews: Map<Long, Bitmap> = emptyMap(),
    tabRuntimeMap: Map<Long, TabRuntimeState> = emptyMap(),
    onToggleMuteTab: (Long) -> Unit = {},
    onToggleMediaPlayPause: (Long) -> Unit = {},
    onSeekMedia: (Long, Int) -> Unit = { _, _ -> },
    onMuteAllPlaying: () -> Unit = {}
) {
    var showIncognito by remember { mutableIntStateOf(0) } // 0 = Normal, 1 = Private, 2 = Recently Closed
    var isGridView by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var groupDialogTab by remember { mutableStateOf<TabEntity?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val normalTabs = tabs.filter { !it.isIncognito }
    val privateTabs = tabs.filter { it.isIncognito }
    val activeSource = if (showIncognito == 1) privateTabs else normalTabs
    val groups = activeSource.mapNotNull { it.groupName }.distinct()

    val filteredTabs = activeSource.filter { tab ->
        val rt = tabRuntimeMap[tab.id]
        val liveTitle = rt?.title ?: tab.title
        val liveUrl = rt?.currentUrl ?: tab.url
        val matchesQuery = searchQuery.isBlank() ||
            liveTitle.contains(searchQuery, ignoreCase = true) ||
            liveUrl.contains(searchQuery, ignoreCase = true)
        val matchesGroup = selectedGroup == null || tab.groupName == selectedGroup
        matchesQuery && matchesGroup
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Top bar matching Screenshot 4: [+]  [ [N] | Private | Closed ]  [⋮]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Pastel periwinkle rounded-square '+' New Tab button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (showIncognito == 1) WeblioIncognitoAccent else WeblioPeriwinkle)
                        .clickable {
                            onNewTab(showIncognito == 1, selectedGroup)
                            onBack()
                        }
                        .testTag("tabs_new_fab"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New tab",
                        tint = Color(0xFF141414),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Centered segmented switcher pill
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.height(44.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Normal Tabs segment
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (showIncognito == 0) WeblioPeriwinkle else Color.Transparent)
                                .clickable { showIncognito = 0; selectedGroup = null }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .border(
                                        width = 1.6.dp,
                                        color = if (showIncognito == 0) Color(0xFF141414) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        shape = RoundedCornerShape(5.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${normalTabs.size}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (showIncognito == 0) Color(0xFF141414) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Private Tabs segment
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (showIncognito == 1) WeblioIncognitoAccent else Color.Transparent)
                                .clickable { showIncognito = 1; selectedGroup = null }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VisibilityOff,
                                    contentDescription = "Private tabs (${privateTabs.size})",
                                    tint = if (showIncognito == 1) Color(0xFF141414) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                if (privateTabs.isNotEmpty()) {
                                    Text(
                                        text = "${privateTabs.size}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (showIncognito == 1) Color(0xFF141414) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Closed Tabs segment
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (showIncognito == 2) WeblioPeriwinkle else Color.Transparent)
                                .clickable { showIncognito = 2 }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "Recently closed (${closedTabs.size})",
                                tint = if (showIncognito == 2) Color(0xFF141414) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                // Overflow / View controls on right
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Tab options",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isGridView) "List view" else "Grid view") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.testTag("tabs_view_toggle"),
                            onClick = {
                                isGridView = !isGridView
                                showOverflowMenu = false
                            }
                        )
                        if (showIncognito != 2) {
                            DropdownMenuItem(
                                text = { Text("Close all tabs") },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                modifier = Modifier.testTag("tabs_close_all_button"),
                                onClick = {
                                    onCloseAllTabs(showIncognito == 1)
                                    showOverflowMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Back to browser") },
                            onClick = {
                                showOverflowMenu = false
                                onBack()
                            }
                        )
                    }
                }
            }

            if (showIncognito != 2) {
                // Full-width matte gray pill search bar ("Search your tabs") matching Screenshot 4
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(WeblioPeriwinkle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tabs_search_input"),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search your tabs",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    )
                }

                if (groups.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedGroup == null,
                            onClick = { selectedGroup = null },
                            label = { Text("All") }
                        )
                        groups.forEach { group ->
                            FilterChip(
                                selected = selectedGroup == group,
                                onClick = { selectedGroup = if (selectedGroup == group) null else group },
                                label = { Text(group) },
                                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                val mediaTabs = activeSource.filter { t ->
                    val rt = tabRuntimeMap[t.id]
                    rt?.isMediaPlaying == true || rt?.hasMediaSession == true
                }
                if (mediaTabs.isNotEmpty()) {
                    val primaryMediaTab = mediaTabs.firstOrNull { tabRuntimeMap[it.id]?.isMediaPlaying == true }
                        ?: mediaTabs.first()
                    val mediaRt = tabRuntimeMap[primaryMediaTab.id]
                    val mediaPlaying = mediaRt?.isMediaPlaying == true
                    val mediaMuted = mediaRt?.isAudioMuted == true
                    val mediaUrl = mediaRt?.currentUrl ?: primaryMediaTab.url
                    val mediaDisplayTitle = mediaRt?.mediaTitle?.takeIf { it.isNotBlank() }
                        ?: resolveTabDisplayTitle(primaryMediaTab, mediaRt, mediaUrl)
                    val hostLabel = BrowserViewModel.extractHost(mediaUrl).ifBlank { "Playing Media" }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (mediaMuted) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                            else WeblioCyan.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("tabs_media_controller_card_${primaryMediaTab.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (mediaMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    tint = if (mediaMuted) MaterialTheme.colorScheme.error else WeblioCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            onSelectTab(primaryMediaTab.id)
                                            onBack()
                                        }
                                ) {
                                    Text(
                                        text = mediaDisplayTitle,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (mediaMuted) "$hostLabel • Muted" else if (mediaPlaying) "$hostLabel • Playing" else "$hostLabel • Paused",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (mediaTabs.count { tabRuntimeMap[it.id]?.isAudioMuted != true } > 1) {
                                    TextButton(
                                        onClick = onMuteAllPlaying,
                                        modifier = Modifier.testTag("tabs_mute_all_button")
                                    ) {
                                        Text("Mute all", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onSeekMedia(primaryMediaTab.id, -10) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("media_ctrl_rewind_${primaryMediaTab.id}")
                                ) {
                                    Icon(Icons.Filled.Replay10, contentDescription = "Rewind 10 seconds")
                                }
                                IconButton(
                                    onClick = { onToggleMediaPlayPause(primaryMediaTab.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("media_ctrl_play_pause_${primaryMediaTab.id}")
                                ) {
                                    Icon(
                                        imageVector = if (mediaPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (mediaPlaying) "Pause media" else "Play media",
                                        tint = WeblioCyan
                                    )
                                }
                                IconButton(
                                    onClick = { onSeekMedia(primaryMediaTab.id, 10) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("media_ctrl_forward_${primaryMediaTab.id}")
                                ) {
                                    Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds")
                                }
                                IconButton(
                                    onClick = { onToggleMuteTab(primaryMediaTab.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("media_ctrl_mute_${primaryMediaTab.id}")
                                ) {
                                    Icon(
                                        imageVector = if (mediaMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (mediaMuted) "Unmute tab audio" else "Mute tab audio",
                                        tint = if (mediaMuted) MaterialTheme.colorScheme.error else WeblioCyan
                                    )
                                }
                            }
                        }
                    }
                }

                if (filteredTabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (showIncognito == 1) Icons.Filled.VisibilityOff else Icons.Filled.Public,
                                contentDescription = null,
                                tint = WeblioPeriwinkle,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = if (showIncognito == 1) "No private tabs open" else "No matching tabs",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                onNewTab(showIncognito == 1, selectedGroup)
                                onBack()
                            }) {
                                Text(if (showIncognito == 1) "Open Private Tab" else "Open New Tab")
                            }
                        }
                    }
                } else if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredTabs, key = { it.id }) { tab ->
                            TabPreviewCard(
                                tab = tab,
                                runtime = tabRuntimeMap[tab.id],
                                previewBitmap = tabPreviews[tab.id],
                                isActive = tab.id == activeTabId,
                                onSelect = {
                                    onSelectTab(tab.id)
                                    onBack()
                                },
                                onClose = { onCloseTab(tab) },
                                onDuplicate = { onDuplicateTab(tab) },
                                onGroupClick = { groupDialogTab = tab },
                                onToggleMute = { onToggleMuteTab(tab.id) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredTabs, key = { it.id }) { tab ->
                            TabListRow(
                                tab = tab,
                                runtime = tabRuntimeMap[tab.id],
                                previewBitmap = tabPreviews[tab.id],
                                isActive = tab.id == activeTabId,
                                onSelect = {
                                    onSelectTab(tab.id)
                                    onBack()
                                },
                                onClose = { onCloseTab(tab) },
                                onDuplicate = { onDuplicateTab(tab) },
                                onGroupClick = { groupDialogTab = tab },
                                onToggleMute = { onToggleMuteTab(tab.id) }
                            )
                        }
                    }
                }
            } else {
                // Recently Closed Tabs
                if (closedTabs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = WeblioPeriwinkle, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No recently closed tabs", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(closedTabs, key = { it.id }) { closed ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onReopenClosed(closed)
                                        onBack()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Restore, contentDescription = null, tint = WeblioPeriwinkle)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(closed.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                        Text(closed.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    groupDialogTab?.let { target ->
        var groupInput by remember(target.id) { mutableStateOf(target.groupName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { groupDialogTab = null },
            title = { Text("Assign Tab Group") },
            text = {
                OutlinedTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it },
                    label = { Text("Group name (leave empty to clear)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onAssignGroup(target, groupInput)
                    groupDialogTab = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { groupDialogTab = null }) { Text("Cancel") }
            }
        )
    }
}

private fun resolveTabDisplayTitle(tab: TabEntity, runtime: TabRuntimeState?, liveUrl: String): String {
    if (liveUrl == "weblio://home") return "New tab"
    val rtTitle = runtime?.title?.trim().orEmpty()
    if (rtTitle.isNotBlank() && rtTitle != "New Tab" && rtTitle != "Home" && rtTitle != "about:blank") {
        return rtTitle
    }
    val dbTitle = tab.title.trim()
    if (dbTitle.isNotBlank() && dbTitle != "New Tab" && dbTitle != "Home" && dbTitle != "about:blank") {
        return dbTitle
    }
    return BrowserViewModel.extractHost(liveUrl).ifBlank { liveUrl }
}

@Composable
private fun TabPreviewCard(
    tab: TabEntity,
    runtime: TabRuntimeState?,
    previewBitmap: Bitmap?,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: () -> Unit,
    onGroupClick: () -> Unit,
    onToggleMute: () -> Unit = {}
) {
    val liveUrl = runtime?.currentUrl ?: tab.url
    val displayTitle = resolveTabDisplayTitle(tab, runtime, liveUrl)
    val isPlaying = runtime?.isMediaPlaying == true
    val isMuted = runtime?.isAudioMuted == true
    val showAudioIndicator = isPlaying || isMuted || runtime?.hasMediaSession == true
    val frameColor = when {
        isActive && tab.isIncognito -> WeblioIncognitoAccent
        isActive -> WeblioPeriwinkle
        tab.isIncognito -> WeblioIncognitoSurface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val headerContentColor = if (isActive) Color(0xFF141414) else MaterialTheme.colorScheme.onSurface

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = frameColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(244.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onSelect)
            .testTag("tab_card_${tab.id}")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Card Header with Favicon + Website Title + Audio Indicator + Close
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (liveUrl == "weblio://home") {
                    WeblioLogoMark(size = 18.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        WebsiteOnlineLogo(
                            logoUrl = websiteFaviconUrl(liveUrl, 64),
                            contentDescription = displayTitle,
                            fallbackIcon = Icons.Filled.Public,
                            fallbackTint = Color(0xFF141414),
                            size = 14.dp
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = headerContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (showAudioIndicator) {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(CircleShape)
                            .background(
                                if (isMuted) Color(0xFFE53935).copy(alpha = 0.2f)
                                else if (isActive) Color(0xFF141414).copy(alpha = 0.14f)
                                else WeblioCyan.copy(alpha = 0.2f)
                            )
                            .clickable(onClick = onToggleMute)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("tab_mute_button_${tab.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isMuted) "Unmute tab" else "Mute tab",
                            tint = if (isMuted) Color(0xFFFF5252) else if (isActive) Color(0xFF141414) else WeblioCyan,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close tab",
                        tint = headerContentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Inner Preview Box (real captured WebView thumbnail or Home miniature + crisp non-overlapping footer)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 5.dp, end = 5.dp, bottom = 5.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (liveUrl == "weblio://home") {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Weblo",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = "Search or type URL",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 8.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFF6B77F8)))
                                Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFE5E5E5)))
                                Box(Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                            }
                        }
                    } else if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                WebsiteOnlineLogo(
                                    logoUrl = websiteFaviconUrl(liveUrl, 128),
                                    contentDescription = displayTitle,
                                    fallbackIcon = Icons.Filled.Public,
                                    fallbackTint = Color(0xFF141414),
                                    size = 28.dp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = BrowserViewModel.extractHost(liveUrl).ifBlank { liveUrl },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (liveUrl != "weblio://home") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tab.groupName ?: BrowserViewModel.extractHost(liveUrl).ifBlank { "Group" },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onGroupClick)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        IconButton(onClick = onDuplicate, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Duplicate tab",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabListRow(
    tab: TabEntity,
    runtime: TabRuntimeState?,
    previewBitmap: Bitmap?,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: () -> Unit,
    onGroupClick: () -> Unit,
    onToggleMute: () -> Unit = {}
) {
    val liveUrl = runtime?.currentUrl ?: tab.url
    val displayTitle = resolveTabDisplayTitle(tab, runtime, liveUrl)
    val isPlaying = runtime?.isMediaPlaying == true
    val isMuted = runtime?.isAudioMuted == true
    val showAudioIndicator = isPlaying || isMuted || runtime?.hasMediaSession == true
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = if (isActive) BorderStroke(1.5.dp, WeblioPeriwinkle) else null,
        color = if (tab.isIncognito) WeblioIncognitoSurface else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (previewBitmap != null && liveUrl != "weblio://home") {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 48.dp, height = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else if (liveUrl != "weblio://home") {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    WebsiteOnlineLogo(
                        logoUrl = websiteFaviconUrl(liveUrl, 64),
                        contentDescription = displayTitle,
                        fallbackIcon = Icons.Filled.Public,
                        fallbackTint = Color(0xFF141414),
                        size = 20.dp
                    )
                }
            } else {
                Icon(
                    imageVector = if (tab.isIncognito) Icons.Filled.VisibilityOff else Icons.Filled.Public,
                    contentDescription = null,
                    tint = if (tab.isIncognito) WeblioIncognitoAccent else WeblioPeriwinkle
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(liveUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tab.groupName?.let {
                    Text("Group: $it", style = MaterialTheme.typography.labelSmall, color = WeblioCyan)
                }
            }
            if (showAudioIndicator) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.testTag("tab_list_mute_button_${tab.id}")
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute tab" else "Mute tab",
                        tint = if (isMuted) MaterialTheme.colorScheme.error else WeblioCyan
                    )
                }
            }
            IconButton(onClick = onGroupClick) { Icon(Icons.Filled.Folder, contentDescription = "Group") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
    }
}
