package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.db.ExtensionEntity
import com.example.data.db.ProfileEntity
import com.example.data.prefs.BrowserSettings
import com.example.ui.components.ChromeStoreLogoMark
import com.example.ui.components.EdgeAddonsLogoMark
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioIncognitoAccent
import com.example.ui.theme.WeblioPeriwinkle
import com.example.ui.theme.WeblioSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuBottomSheet(
    activeProfile: ProfileEntity?,
    settings: BrowserSettings,
    isDesktopMode: Boolean,
    isIncognitoTab: Boolean,
    onDismiss: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenPasswords: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenAdBlock: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleIncognito: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onNewTab: () -> Unit = {},
    onSetDefaultBrowser: () -> Unit = {},
    onOpenSecurityCenter: () -> Unit = {},
    extensions: List<ExtensionEntity> = emptyList(),
    onUseExtensions: () -> Unit = onOpenExtensions
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Profile Row matching reference screenshot 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onDismiss(); onOpenProfiles() }
                    .padding(vertical = 4.dp)
                    .testTag("menu_profile_row"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE5E5E5)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeProfile?.name ?: "Personal",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (activeProfile?.isGuest == true) {
                            "Guest profile • Ephemeral browsing"
                        } else {
                            "Switch or manage isolated local profiles"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Switch profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 4 Quick Action Rounded Square Tiles (Bookmarks, History, Download, Settings)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MenuTile("Bookmarks", Icons.Outlined.BookmarkBorder, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); onOpenBookmarks()
                }
                MenuTile("History", Icons.Outlined.History, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); onOpenHistory()
                }
                MenuTile("Download", Icons.Outlined.Download, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); onOpenDownloads()
                }
                MenuTile("Settings", Icons.Outlined.Settings, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); onOpenSettings()
                }
            }

            // Grouped Rounded List Card matching Screenshot 2
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    val activeExtCount = extensions.count { it.enabled }
                    val extBadgeLabel = when {
                        extensions.isEmpty() -> "Get"
                        activeExtCount > 0 -> "$activeExtCount active"
                        else -> "${extensions.size} paused"
                    }
                    MenuListRow(
                        icon = Icons.Filled.PlayArrow,
                        title = "Extension",
                        trailingLabel = extBadgeLabel,
                        onClick = { onDismiss(); onUseExtensions() },
                        modifier = Modifier.testTag("menu_use_extensions_btn")
                    )
                    MenuListRow(
                        icon = Icons.Outlined.Extension,
                        title = "Manage & Store Extensions",
                        showChevron = true,
                        onClick = { onDismiss(); onOpenExtensions() },
                        modifier = Modifier.testTag("menu_manage_extensions_btn")
                    )
                    MenuListRow(
                        icon = Icons.Outlined.DesktopWindows,
                        title = "Use desktop mode",
                        trailingLabel = if (isDesktopMode) "On" else null,
                        onClick = { onToggleDesktopMode(); onDismiss() }
                    )
                    MenuListRow(
                        icon = Icons.Outlined.AddBox,
                        title = "New Tab",
                        onClick = { onDismiss(); onNewTab() }
                    )
                    MenuListRow(
                        icon = Icons.Outlined.VisibilityOff,
                        title = "Incognito Mode",
                        trailingLabel = if (isIncognitoTab) "Close" else null,
                        onClick = { onToggleIncognito(); onDismiss() }
                    )
                    MenuListRow(
                        icon = Icons.Outlined.DarkMode,
                        title = "Dark Mode",
                        trailingLabel = when (settings.themeMode) {
                            AppThemeMode.DARK -> "On"
                            AppThemeMode.LIGHT -> "White"
                            AppThemeMode.SYSTEM -> "Auto"
                        },
                        onClick = { onToggleDarkMode() }
                    )
                    MenuListRow(
                        icon = Icons.Outlined.Tab,
                        title = "Window Management",
                        showChevron = true,
                        onClick = { onDismiss(); onOpenTabs() }
                    )
                    MenuListRow(
                        icon = Icons.Outlined.Lock,
                        title = "Password Management",
                        showChevron = true,
                        onClick = { onDismiss(); onOpenPasswords() }
                    )
                    MenuListRow(
                        icon = Icons.Outlined.Shield,
                        title = "Ad Shield & Privacy",
                        showChevron = true,
                        onClick = { onDismiss(); onOpenAdBlock() }
                    )
                    MenuListRow(
                        icon = Icons.Outlined.Security,
                        title = "WEBLIO Security Center",
                        showChevron = true,
                        onClick = { onDismiss(); onOpenSecurityCenter() }
                    )
                }
            }

            // Full-width High-Contrast Pill Button matching Screenshot 2 & White Theme
            Button(
                onClick = { onDismiss(); onSetDefaultBrowser() },
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("menu_set_default_browser_btn")
            ) {
                Text(
                    text = "Set as default browser",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserToolsBottomSheet(
    currentUrl: String,
    isBookmarked: Boolean,
    siteAdBlockEnabled: Boolean,
    onDismiss: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onFindInPage: () -> Unit,
    onShareLink: () -> Unit,
    onCopyLink: () -> Unit,
    onToggleSiteAdBlock: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenSitePermissions: () -> Unit,
    onOpenAdBlockDashboard: () -> Unit
) {
    val isWebPage = currentUrl != "weblio://home" && currentUrl.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Browser Tools", style = MaterialTheme.typography.titleLarge)

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 4,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MenuTile(
                    label = if (isBookmarked) "Saved" else "Bookmark",
                    icon = Icons.Outlined.Bookmarks,
                    tint = if (isBookmarked) WeblioPeriwinkle else MaterialTheme.colorScheme.onSurface
                ) { onBookmarkToggle(); onDismiss() }

                MenuTile("Find", Icons.Outlined.FindInPage, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); if (isWebPage) onFindInPage()
                }
                MenuTile("Share", Icons.Outlined.Share, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); if (isWebPage) onShareLink()
                }
                MenuTile("Copy Link", Icons.Outlined.ContentCopy, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); if (isWebPage) onCopyLink()
                }
                MenuTile("PiP Mode", Icons.Outlined.PictureInPictureAlt, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); onEnterPip()
                }
                MenuTile("Permissions", Icons.Outlined.Security, MaterialTheme.colorScheme.onSurface) {
                    onDismiss(); onOpenSitePermissions()
                }
                MenuTile("Shield", Icons.Outlined.Shield, WeblioSuccess) {
                    onDismiss(); onOpenAdBlockDashboard()
                }
            }

            if (isWebPage) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                MenuToggleRow(
                    icon = Icons.Outlined.Shield,
                    title = "Ad & Tracker Blocking on This Site",
                    checked = siteAdBlockEnabled,
                    onToggle = { onToggleSiteAdBlock() }
                )
            }
        }
    }
}

/**
 * Floating Extensions Popup Card anchored above the toolbar:
 * - Lists installed extensions with Pin/Toggle, Popup, and Delete actions
 * - Shows selectable Desktop Extension Stores (Chrome Web Store & Microsoft Edge Add-ons) with logos & names
 */
@Composable
fun ExtensionsQuickPopup(
    extensions: List<ExtensionEntity>,
    onDismiss: () -> Unit,
    onOpenStoreUrl: (String) -> Unit,
    onOpenPopup: (ExtensionEntity) -> Unit,
    onToggleExtension: (ExtensionEntity) -> Unit,
    onManageExtensions: () -> Unit,
    onRemoveExtension: (ExtensionEntity) -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
                .padding(end = 14.dp, bottom = 72.dp, start = 28.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 10.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .widthIn(max = 350.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
                    .testTag("extensions_quick_popup")
            ) {
                Column(modifier = Modifier.padding(top = 14.dp, bottom = 10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Extensions",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    if (extensions.isEmpty()) {
                        Text(
                            text = "No extensions installed yet. Select a store below to download extensions in desktop view.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    } else {
                        Text(
                            text = "Installed (${extensions.size})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            extensions.forEach { ext ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onDismiss()
                                            onOpenPopup(ext)
                                        }
                                        .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Extension,
                                        contentDescription = null,
                                        tint = if (ext.enabled) WeblioPeriwinkle else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ext.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (ext.enabled) "Active • v${ext.version}" else "Paused • v${ext.version}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            onDismiss()
                                            onOpenPopup(ext)
                                        },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("popup_use_ext_${ext.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "Use ${ext.name}",
                                            tint = WeblioCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onToggleExtension(ext) },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("popup_toggle_ext_${ext.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PushPin,
                                            contentDescription = if (ext.enabled) "Disable extension" else "Enable extension",
                                            tint = if (ext.enabled) WeblioCyan else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveExtension(ext) },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("popup_delete_ext_${ext.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = "Delete ${ext.name}",
                                            tint = WeblioDanger,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Get Extensions (Desktop Store)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExtensionStoreChooserRow(
                            title = "Chrome Web Store",
                            subtitle = "Chrome Web Extensions • Desktop View",
                            logo = { ChromeStoreLogoMark(size = 28.dp) },
                            onClick = {
                                onDismiss()
                                onOpenStoreUrl("https://chromewebstore.google.com")
                            },
                            modifier = Modifier.testTag("popup_store_chrome")
                        )
                        ExtensionStoreChooserRow(
                            title = "Microsoft Edge Add-ons",
                            subtitle = "Microsoft Edge Extensions • Desktop View",
                            logo = { EdgeAddonsLogoMark(size = 28.dp) },
                            onClick = {
                                onDismiss()
                                onOpenStoreUrl("https://microsoftedge.microsoft.com/addons")
                            },
                            modifier = Modifier.testTag("popup_store_edge")
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onManageExtensions()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("popup_manage_extensions"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Manage extensions",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionStoreChooserRow(
    title: String,
    subtitle: String,
    logo: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            logo()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MenuTile(
    label: String,
    icon: ImageVector,
    tint: Color,
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
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MenuListRow(
    icon: ImageVector,
    title: String,
    trailingLabel: String? = null,
    showChevron: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailingLabel != null) {
            Text(
                text = trailingLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MenuToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    accent: Color = WeblioCyan,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
