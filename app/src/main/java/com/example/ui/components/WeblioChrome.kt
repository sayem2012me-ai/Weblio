package com.example.ui.components

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.webkit.WebViewAssetLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.R
import com.example.data.db.ExtensionEntity
import com.example.data.db.ProfileEntity
import com.example.engine.ExtensionEngine
import com.example.security.SecurityState
import com.example.ui.PendingCredentialPrompt
import com.example.ui.PendingDownloadPrompt
import com.example.ui.SslErrorPrompt
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioDeepPurple
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioIncognitoAccent
import com.example.ui.theme.WeblioIncognitoSurface
import com.example.ui.theme.WeblioSuccess
import com.example.ui.theme.WeblioWarning
import java.io.ByteArrayInputStream
import java.io.File

@Composable
fun WeblioLogoMark(
    size: Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.weblio_logo),
        contentDescription = stringResource(id = R.string.cd_weblio_logo),
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
    )
}

@Composable
fun WebsiteOnlineLogo(
    logoUrl: String,
    contentDescription: String?,
    fallbackIcon: ImageVector,
    fallbackTint: Color,
    size: Dp = 26.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(logoUrl)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
        loading = {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = fallbackTint,
                modifier = Modifier.size(size * 0.8f)
            )
        },
        error = {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = fallbackTint,
                modifier = Modifier.size(size * 0.8f)
            )
        }
    )
}

@Composable
fun WeblioAddressBar(
    urlText: String,
    isHome: Boolean,
    isLoading: Boolean,
    progress: Int,
    isIncognito: Boolean,
    isSecureHttps: Boolean,
    isDesktopMode: Boolean,
    blockedCount: Int,
    onSubmitUrl: (String) -> Unit,
    onRefreshOrStop: () -> Unit,
    onQrClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onShieldClick: () -> Unit,
    modifier: Modifier = Modifier,
    searchEngineName: String = "Google",
    searchEngineLogoUrl: String? = null,
    onSearchEngineClick: (() -> Unit)? = null,
    securityState: SecurityState = SecurityState.PROTECTED,
    onOpenSearchPage: ((String) -> Unit)? = null,
    isMediaPlaying: Boolean = false,
    isAudioMuted: Boolean = false,
    onToggleMuteClick: (() -> Unit)? = null
) {
    var editing by remember(urlText) { mutableStateOf(false) }
    var draft by remember(urlText) { mutableStateOf(if (isHome) "" else urlText) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(editing) {
        if (editing && !isHome) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    val borderBrush = when {
        isIncognito -> Brush.horizontalGradient(listOf(WeblioDeepPurple, WeblioIncognitoAccent))
        isHome -> SolidColor(Color.Transparent)
        else -> SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    }

    val barContainerColor = if (isIncognito) {
        WeblioIncognitoSurface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(barContainerColor)
                .border(if (isHome && !isIncognito) 0.dp else 1.dp, borderBrush, RoundedCornerShape(28.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isHome) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { (onSearchEngineClick ?: onShieldClick).invoke() }
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("address_search_engine_button"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isIncognito && !searchEngineLogoUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .padding(3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            WebsiteOnlineLogo(
                                logoUrl = searchEngineLogoUrl,
                                contentDescription = searchEngineName,
                                fallbackIcon = Icons.Filled.Search,
                                fallbackTint = Color(0xFF242424),
                                size = 16.dp
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (isIncognito) Icons.Filled.VisibilityOff else Icons.Filled.Search,
                            contentDescription = "Select search engine",
                            tint = if (isIncognito) WeblioIncognitoAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onShieldClick,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("address_shield_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                securityState == SecurityState.THREAT_DETECTED -> Icons.Filled.Warning
                                isIncognito -> Icons.Filled.VisibilityOff
                                isSecureHttps -> Icons.Filled.Lock
                                else -> Icons.Filled.Shield
                            },
                            contentDescription = stringResource(id = R.string.cd_adblock_shield),
                            tint = when {
                                securityState == SecurityState.THREAT_DETECTED -> WeblioDanger
                                securityState == SecurityState.ATTENTION_REQUIRED -> WeblioWarning
                                isIncognito -> WeblioIncognitoAccent
                                isSecureHttps -> WeblioSuccess
                                else -> WeblioCyan
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        if (blockedCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(WeblioElectricBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (blockedCount > 9) "9+" else "$blockedCount",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // URL/Search input trigger (opens dedicated full-screen Search page when onOpenSearchPage != null)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        if (onOpenSearchPage != null) {
                            onOpenSearchPage(if (isHome) "" else urlText)
                        } else {
                            draft = if (isHome) "" else urlText
                            editing = true
                        }
                    }
                    .then(if (onOpenSearchPage != null) Modifier.testTag("address_input_field") else Modifier)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (onOpenSearchPage != null) {
                    if (isHome) {
                        Text(
                            text = "Search $searchEngineName or type a URL",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isDesktopMode) {
                                Icon(
                                    imageVector = Icons.Filled.DesktopWindows,
                                    contentDescription = "Desktop mode",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .padding(end = 4.dp)
                                )
                            }
                            Text(
                                text = urlText.removePrefix("https://").removePrefix("http://"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else if (editing || isHome) {
                    BasicTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            editing = true
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (draft.isNotBlank()) {
                                    editing = false
                                    onSubmitUrl(draft)
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("address_input_field"),
                        decorationBox = { innerTextField ->
                            if (draft.isEmpty()) {
                                Text(
                                    text = if (isHome) "Search $searchEngineName or type a URL" else stringResource(id = R.string.search_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isDesktopMode) {
                            Icon(
                                imageVector = Icons.Filled.DesktopWindows,
                                contentDescription = "Desktop mode",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .padding(end = 4.dp)
                            )
                        }
                        Text(
                            text = urlText.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (editing && draft.isNotEmpty()) {
                IconButton(
                    onClick = { draft = "" },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else if (isHome) {
                IconButton(
                    onClick = onQrClick,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("home_qr_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = stringResource(id = R.string.cd_qr_scanner),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onVoiceClick,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("home_voice_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = stringResource(id = R.string.cd_voice_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onExtensionsClick,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("home_ext_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = stringResource(id = R.string.cd_extensions),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                if ((isMediaPlaying || isAudioMuted) && onToggleMuteClick != null) {
                    IconButton(
                        onClick = onToggleMuteClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("address_audio_mute_button")
                    ) {
                        Icon(
                            imageVector = if (isAudioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isAudioMuted) "Unmute tab audio" else "Mute tab audio",
                            tint = if (isAudioMuted) WeblioDanger else WeblioCyan,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onExtensionsClick,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("browser_ext_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = stringResource(id = R.string.cd_extensions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = onRefreshOrStop,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("browser_refresh_button")
                ) {
                    Icon(
                        imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = stringResource(id = if (isLoading) R.string.cd_stop else R.string.cd_refresh),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = isLoading && !isHome) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(5, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 16.dp, end = 16.dp)
                    .height(2.5.dp)
                    .clip(CircleShape),
                color = if (isIncognito) WeblioIncognitoAccent else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun WeblioBottomNavBar(
    tabCount: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isHome: Boolean,
    compactMode: Boolean,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onHomeClick: () -> Unit,
    onTabsClick: () -> Unit,
    onToolsClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNewTabClick: (() -> Unit)? = null
) {
    val barHeight = if (compactMode) 48.dp else 56.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isHome) {
                    BottomNavItem(
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.cd_back),
                                tint = if (canGoBack) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                                modifier = Modifier.size(23.dp)
                            )
                        },
                        enabled = canGoBack,
                        onClick = onBackClick,
                        tag = "nav_back_button",
                        modifier = Modifier.weight(1f).height(barHeight)
                    )
                    BottomNavItem(
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(id = R.string.cd_forward),
                                tint = if (canGoForward) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                                modifier = Modifier.size(23.dp)
                            )
                        },
                        enabled = canGoForward,
                        onClick = onForwardClick,
                        tag = "nav_forward_button",
                        modifier = Modifier.weight(1f).height(barHeight)
                    )
                }

                // Home
                BottomNavItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Home,
                            contentDescription = stringResource(R.string.cd_home),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    enabled = true,
                    onClick = onHomeClick,
                    tag = "nav_home_button",
                    modifier = Modifier.weight(1f).height(barHeight)
                )

                if (isHome && onNewTabClick != null) {
                    BottomNavItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.cd_new_tab),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(25.dp)
                            )
                        },
                        enabled = true,
                        onClick = onNewTabClick,
                        tag = "nav_new_tab_button",
                        modifier = Modifier.weight(1f).height(barHeight)
                    )
                }

                // Tabs with rounded square count badge
                BottomNavItem(
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .border(
                                    width = 1.8.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(6.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (tabCount > 99) "99" else "$tabCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    enabled = true,
                    onClick = onTabsClick,
                    tag = "nav_tabs_button",
                    modifier = Modifier.weight(1f).height(barHeight)
                )

                // Browser Tools (clean globe icon matching reference screenshot)
                BottomNavItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = stringResource(R.string.cd_tools),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(23.dp)
                        )
                    },
                    enabled = true,
                    onClick = onToolsClick,
                    tag = "nav_tools_button",
                    modifier = Modifier.weight(1f).height(barHeight)
                )

                // Menu (3-line hamburger)
                BottomNavItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.cd_menu),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    enabled = true,
                    onClick = onMenuClick,
                    tag = "nav_menu_button",
                    modifier = Modifier.weight(1f).height(barHeight)
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
fun FindInPageBar(
    query: String,
    activeMatch: Int,
    totalMatches: Int,
    onQueryChange: (String) -> Unit,
    onNext: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Find in page") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (totalMatches > 0) {
                Text(
                    text = "${activeMatch + 1}/$totalMatches",
                    style = MaterialTheme.typography.labelSmall,
                    color = WeblioCyan
                )
            }
            IconButton(onClick = { onNext(false) }) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = { onNext(true) }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close find bar")
            }
        }
    }
}

@Composable
fun SslErrorAlertDialog(
    prompt: SslErrorPrompt,
    onBackToSafety: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBackToSafety,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = WeblioDanger) },
        title = { Text(stringResource(R.string.error_ssl_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.error_ssl_body))
                Text(
                    text = "URL: ${prompt.url}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                prompt.issuedTo?.let {
                    Text("Issued To: $it", style = MaterialTheme.typography.bodySmall)
                }
                prompt.issuedBy?.let {
                    Text("Issued By: $it", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = "Certificate validation is strictly enforced and cannot be bypassed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WeblioWarning
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onBackToSafety,
                colors = ButtonDefaults.buttonColors(containerColor = WeblioElectricBlue)
            ) {
                Text("Back to Safety")
            }
        }
    )
}

@Composable
fun SaveCredentialDialog(
    prompt: PendingCredentialPrompt,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = WeblioCyan) },
        title = { Text("Save Password to Vault?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Store encrypted credentials locally in Android Keystore for:")
                Text(prompt.host, fontWeight = FontWeight.Bold, color = WeblioCyan)
                Text("Username: ${prompt.username}", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = onSave) { Text("Save Encrypted") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Never") }
        }
    )
}

@Composable
fun DownloadConfirmDialog(
    prompt: PendingDownloadPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download File?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(prompt.fileName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Type: ${prompt.mimeType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = prompt.url,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Download") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PinUnlockDialog(
    profile: ProfileEntity,
    onVerifyPin: (String) -> Boolean,
    onBiometricClick: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = WeblioCyan) },
        title = { Text("Unlock ${profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (profile.pinHash != null) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it
                            errorText = null
                        },
                        label = { Text("Enter Profile PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (errorText != null) {
                    Text(errorText!!, color = WeblioDanger, style = MaterialTheme.typography.bodySmall)
                }
                if (profile.biometricEnabled && onBiometricClick != null) {
                    OutlinedButton(
                        onClick = onBiometricClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unlock with Biometrics")
                    }
                }
            }
        },
        confirmButton = {
            if (profile.pinHash != null) {
                Button(onClick = {
                    if (!onVerifyPin(pin)) {
                        errorText = "Incorrect PIN"
                    }
                }) {
                    Text("Unlock")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ExtensionPopupDialog(
    extension: ExtensionEntity,
    onDismiss: () -> Unit,
    extensionEngine: ExtensionEngine? = null,
    activeTabUrl: String = "weblio://home",
    activeTabTitle: String = "New Tab",
    onToggleExtension: (() -> Unit)? = null,
    onRunOnActiveTab: (() -> Unit)? = null,
    onExecuteScript: ((String) -> Unit)? = null,
    onInsertCss: ((String) -> Unit)? = null,
    onOpenTab: ((String) -> Unit)? = null,
    onReloadTab: (() -> Unit)? = null,
    onSendTabMessage: ((String) -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp)
                .testTag("extension_popup_dialog")
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExtensionLogo(
                        extension = extension,
                        size = 28.dp,
                        modifier = Modifier.testTag("popup_header_ext_logo")
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = extension.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "v${extension.version} • " + if (extension.enabled) "Active" else "Disabled",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (extension.enabled) WeblioSuccess else WeblioWarning
                        )
                    }
                    if (onToggleExtension != null) {
                        TextButton(
                            onClick = onToggleExtension,
                            modifier = Modifier.testTag("popup_dialog_toggle_btn")
                        ) {
                            Text(if (extension.enabled) "Pause" else "Enable")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close popup")
                    }
                }

                if (!extension.enabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Extension Disabled",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = WeblioWarning
                        )
                        Text(
                            text = "${extension.name} is currently paused (${extension.riskLevel} risk). Enable it to run its popup interface or page scripts on websites.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onToggleExtension != null) {
                            Button(
                                onClick = onToggleExtension,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("popup_dialog_enable_btn")
                            ) {
                                Text("Enable ${extension.name}")
                            }
                        }
                    }
                } else if (extension.hasPopup && extension.installPath.isNotBlank()) {
                    val context = LocalContext.current
                    val extBaseDir = runCatching { File(context.filesDir, "extensions").canonicalFile }.getOrNull()
                    val rootDir = runCatching { File(extension.installPath).canonicalFile }.getOrNull()
                    val isConfined = extBaseDir != null && rootDir != null &&
                        rootDir.path.startsWith(extBaseDir.path.trimEnd(File.separatorChar) + File.separatorChar)
                    val safePopupFile = if (isConfined) {
                        ExtensionEngine.resolveConfinedFile(rootDir, extension.popupPath)
                    } else null

                    if (rootDir == null || !isConfined || safePopupFile == null || !safePopupFile.exists()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Popup Unavailable", style = MaterialTheme.typography.titleMedium, color = WeblioWarning)
                            Text(
                                "This extension's popup package could not be verified inside isolated storage.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val relPopupPath = safePopupFile.canonicalPath
                            .removePrefix(rootDir.path.trimEnd(File.separatorChar) + File.separatorChar)
                            .replace(File.separatorChar, '/')
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            factory = { ctx ->
                                val loader = WebViewAssetLoader.Builder()
                                    .setDomain("ext.weblio.local")
                                    .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(ctx, rootDir))
                                    .build()
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    @Suppress("DEPRECATION")
                                    settings.allowFileAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    settings.allowUniversalAccessFromFileURLs = false
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    if (extensionEngine != null) {
                                        addJavascriptInterface(
                                            extensionEngine.createPopupJsBridge(
                                                ext = extension,
                                                activeTabUrl = activeTabUrl,
                                                onExecuteScript = { code -> onExecuteScript?.invoke(code) },
                                                onInsertCss = { css -> onInsertCss?.invoke(css) },
                                                onOpenTab = { url -> onOpenTab?.invoke(url) },
                                                onReloadTab = { onReloadTab?.invoke() },
                                                onSendTabMessage = { msg -> onSendTabMessage?.invoke(msg) }
                                            ),
                                            "WeblioPopupBridge"
                                        )
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val url = request?.url?.toString().orEmpty()
                                            if (url.startsWith("https://ext.weblio.local/", ignoreCase = true)) {
                                                return false
                                            }
                                            if (url.startsWith("https://", true) || url.startsWith("http://", true)) {
                                                onOpenTab?.invoke(url)
                                                onDismiss()
                                            }
                                            return true
                                        }

                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): WebResourceResponse? {
                                            val uri = request?.url ?: return null
                                            if (!uri.scheme.equals("https", ignoreCase = true) ||
                                                !uri.host.equals("ext.weblio.local", ignoreCase = true)
                                            ) {
                                                return WebResourceResponse(
                                                    "text/plain",
                                                    "UTF-8",
                                                    403,
                                                    "Blocked external resource in extension popup",
                                                    emptyMap(),
                                                    ByteArrayInputStream(ByteArray(0))
                                                )
                                            }
                                            val resp = loader.shouldInterceptRequest(uri) ?: return null
                                            val path = uri.path.orEmpty().lowercase()
                                            if (extensionEngine != null && (path.endsWith(".html") || path.endsWith(".htm"))) {
                                                val rawBytes = runCatching { resp.data.use { it.readBytes() } }.getOrNull()
                                                if (rawBytes != null) {
                                                    val wrapped = extensionEngine.injectPopupChromeRuntimeHtml(
                                                        rawHtmlBytes = rawBytes,
                                                        ext = extension,
                                                        activeTabUrl = activeTabUrl,
                                                        activeTabTitle = activeTabTitle
                                                    )
                                                    return WebResourceResponse(
                                                        "text/html",
                                                        "UTF-8",
                                                        ByteArrayInputStream(wrapped)
                                                    )
                                                }
                                            }
                                            return resp
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView?,
                                            detail: android.webkit.RenderProcessGoneDetail?
                                        ): Boolean {
                                            view?.let { dead ->
                                                dead.stopLoading()
                                                (dead.parent as? android.view.ViewGroup)?.removeView(dead)
                                                dead.destroy()
                                            }
                                            onDismiss()
                                            return true
                                        }
                                    }
                                    loadUrl("https://ext.weblio.local/$relPopupPath")
                                }
                            },
                            onRelease = { wv ->
                                runCatching {
                                    wv.stopLoading()
                                    wv.webChromeClient = null
                                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                                    wv.removeAllViews()
                                    wv.destroy()
                                }
                            }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Active Extension Runtime", style = MaterialTheme.typography.titleMedium, color = WeblioCyan)
                        Text(extension.description, style = MaterialTheme.typography.bodyMedium)
                        Text("Source: ${extension.sourceType}", style = MaterialTheme.typography.labelSmall)
                        Text("Run stage: ${extension.runAt}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = "Matches: ${extension.matchPatternsJson}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        extension.lastError?.let {
                            Text("Diagnostics: $it", style = MaterialTheme.typography.bodySmall, color = WeblioWarning)
                        }
                        Spacer(Modifier.weight(1f))
                        if (onRunOnActiveTab != null) {
                            Button(
                                onClick = {
                                    onRunOnActiveTab()
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("popup_dialog_run_now_btn")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Run on Current Tab")
                            }
                        }
                    }
                }
            }
        }
    }
}
