package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.db.ProfileEntity
import com.example.data.db.QuickLinkEntity
import com.example.data.prefs.BrowserSettings
import com.example.data.prefs.SearchEngine
import com.example.data.prefs.WallpaperStyle
import com.example.data.prefs.websiteFaviconUrl
import com.example.ui.components.WeblioAddressBar
import com.example.ui.components.WeblioLogoMark
import com.example.ui.components.WebsiteOnlineLogo
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDeepPurple
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioIncognitoAccent
import com.example.ui.theme.WeblioIncognitoSurface
import com.example.ui.theme.WeblioPeriwinkle
import com.example.ui.theme.WeblioSuccess

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    settings: BrowserSettings,
    activeProfile: ProfileEntity?,
    quickLinks: List<QuickLinkEntity>,
    isIncognito: Boolean,
    sessionBlockedCount: Long,
    onNavigateUrl: (String) -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onQrClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onAdBlockClick: () -> Unit,
    onProfileClick: () -> Unit,
    onAddQuickLink: (String, String) -> Unit,
    onDeleteQuickLink: (QuickLinkEntity) -> Unit,
    modifier: Modifier = Modifier,
    onSearchBarClick: (() -> Unit)? = null
) {
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var editingQuickLinks by remember { mutableStateOf(false) }
    var showEnginePickerCard by remember { mutableStateOf(false) }

    val themeBg = MaterialTheme.colorScheme.background
    val themeSurface = MaterialTheme.colorScheme.surface
    val bgBrush = when {
        isIncognito -> Brush.verticalGradient(listOf(WeblioIncognitoSurface, themeBg))
        settings.wallpaperStyle == WallpaperStyle.ELECTRIC_AURORA -> Brush.verticalGradient(
            listOf(WeblioElectricBlue.copy(alpha = 0.12f), themeSurface, themeBg)
        )
        settings.wallpaperStyle == WallpaperStyle.DEEP_PURPLE_NEBULA -> Brush.verticalGradient(
            listOf(WeblioDeepPurple.copy(alpha = 0.12f), themeSurface, themeBg)
        )
        else -> Brush.verticalGradient(listOf(themeBg, themeBg))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBg)
            .background(bgBrush),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Minimal subtle top status row (Profile & Shield)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onProfileClick)
                        .testTag("home_profile_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (activeProfile?.iconKey) {
                                "work" -> Icons.Filled.Work
                                "guest" -> Icons.Filled.VisibilityOff
                                else -> Icons.Filled.Person
                            },
                            contentDescription = "Active profile",
                            tint = if (isIncognito) WeblioIncognitoAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isIncognito) "Incognito" else (activeProfile?.name ?: "Personal"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onAdBlockClick)
                        .testTag("home_shield_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = "Shield status",
                            tint = if (settings.adBlockerEnabled) WeblioSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${settings.totalBlockedCount + sessionBlockedCount} blocked",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(68.dp))

            // Centered WEBLIO Branding matching the clean screenshot aesthetic
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                WeblioLogoMark(size = 44.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isIncognito) "Weblo Private" else "Weblo",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isIncognito) WeblioIncognitoAccent else MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.tagline),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                color = if (isIncognito) WeblioIncognitoAccent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(26.dp))

            // Matte Dark Gray Rounded Search Bar (with search engine dropdown + QR, Voice, Extensions)
            WeblioAddressBar(
                urlText = "",
                isHome = true,
                isLoading = false,
                progress = 0,
                isIncognito = isIncognito,
                isSecureHttps = false,
                isDesktopMode = false,
                blockedCount = 0,
                searchEngineName = settings.defaultSearchEngine.displayName,
                searchEngineLogoUrl = settings.defaultSearchEngine.logoUrl(),
                onSearchEngineClick = { showEnginePickerCard = !showEnginePickerCard },
                onSubmitUrl = onNavigateUrl,
                onRefreshOrStop = {},
                onQrClick = onQrClick,
                onVoiceClick = onVoiceClick,
                onExtensionsClick = onExtensionsClick,
                onShieldClick = onAdBlockClick,
                onOpenSearchPage = onSearchBarClick?.let { click -> { _ -> click() } }
            )

            // Expandable Search Engine Selector Card (matching reference screenshot 5)
            AnimatedVisibility(visible = showEnginePickerCard) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        maxItemsInEachRow = 4,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SearchEngine.entries.forEach { engine ->
                            val isSelected = settings.defaultSearchEngine == engine
                            val badgeTint = when (engine) {
                                SearchEngine.GOOGLE -> WeblioElectricBlue
                                SearchEngine.DUCKDUCKGO -> Color(0xFFDE5833)
                                SearchEngine.BRAVE -> Color(0xFFFB542B)
                                SearchEngine.BING -> Color(0xFF2563EB)
                                SearchEngine.ECOSIA -> WeblioSuccess
                            }
                            Column(
                                modifier = Modifier
                                    .width(70.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        onSelectSearchEngine(engine)
                                        showEnginePickerCard = false
                                    }
                                    .padding(vertical = 4.dp)
                                    .testTag("search_engine_${engine.name.lowercase()}"),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFF5F5F5))
                                        .then(
                                            if (isSelected) Modifier.border(2.2.dp, WeblioPeriwinkle, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    WebsiteOnlineLogo(
                                        logoUrl = engine.logoUrl(),
                                        contentDescription = engine.displayName,
                                        fallbackIcon = when (engine) {
                                            SearchEngine.ECOSIA -> Icons.Filled.Eco
                                            SearchEngine.BRAVE -> Icons.Filled.Shield
                                            SearchEngine.DUCKDUCKGO -> Icons.Filled.Security
                                            else -> Icons.Filled.Search
                                        },
                                        fallbackTint = badgeTint,
                                        size = 28.dp
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = engine.displayName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Clean Circular Quick Links Grid (Bookmarks, History, Quick Links, Add) matching screenshot 3
            if (settings.showQuickLinksOnHome) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    maxItemsInEachRow = 4
                ) {
                    CircularShortcutTile(
                        title = "Bookmarks",
                        icon = Icons.Filled.Bookmarks,
                        circleColor = Color(0xFF6B77F8),
                        iconTint = Color.White,
                        onClick = onBookmarksClick
                    )
                    CircularShortcutTile(
                        title = "History",
                        icon = Icons.Filled.History,
                        circleColor = Color(0xFFEAEAEA),
                        iconTint = Color(0xFF242424),
                        onClick = onHistoryClick
                    )
                    quickLinks.forEach { link ->
                        QuickLinkCircularTile(
                            link = link,
                            editMode = editingQuickLinks,
                            onClick = { onNavigateUrl(link.url) },
                            onLongClick = { editingQuickLinks = !editingQuickLinks },
                            onDelete = { onDeleteQuickLink(link) }
                        )
                    }
                    CircularShortcutTile(
                        title = "Add",
                        icon = Icons.Filled.Add,
                        circleColor = MaterialTheme.colorScheme.surfaceVariant,
                        iconTint = MaterialTheme.colorScheme.onSurface,
                        onClick = { showAddShortcutDialog = true }
                    )
                }

                if (quickLinks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = { editingQuickLinks = !editingQuickLinks },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (editingQuickLinks) "Done" else "Edit shortcuts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showAddShortcutDialog) {
        AddQuickLinkDialog(
            onDismiss = { showAddShortcutDialog = false },
            onConfirm = { title, url ->
                onAddQuickLink(title, url)
                showAddShortcutDialog = false
            }
        )
    }
}

@Composable
private fun CircularShortcutTile(
    title: String,
    icon: ImageVector,
    circleColor: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickLinkCircularTile(
    link: QuickLinkEntity,
    editMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val (icon, tint) = when (link.iconKey) {
        "wiki" -> Icons.Filled.MenuBook to WeblioCyan
        "code" -> Icons.Filled.Code to WeblioPeriwinkle
        "video" -> Icons.Filled.PlayCircle to Color(0xFFFF5A5A)
        "shield" -> Icons.Filled.Security to WeblioIncognitoAccent
        "docs" -> Icons.Filled.Article to WeblioSuccess
        "news" -> Icons.Filled.Language to Color(0xFFF59E0B)
        else -> Icons.Filled.Public to MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = Modifier.width(74.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF3F4F6)),
                contentAlignment = Alignment.Center
            ) {
                WebsiteOnlineLogo(
                    logoUrl = websiteFaviconUrl(link.url),
                    contentDescription = link.title,
                    fallbackIcon = icon,
                    fallbackTint = tint,
                    size = 28.dp
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = link.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (editMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun AddQuickLinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Quick Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Website URL (e.g. reddit.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, url) },
                enabled = title.isNotBlank() && url.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
