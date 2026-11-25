package com.example.ui.screens

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.data.db.BookmarkEntity
import com.example.data.db.HistoryEntity
import com.example.data.prefs.SearchEngine
import com.example.engine.SearchSuggestionEngine
import com.example.ui.components.WebsiteOnlineLogo
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioIncognitoAccent
import com.example.ui.theme.WeblioIncognitoSurface
import kotlinx.coroutines.delay

private sealed class OmniboxRowItem(val stableKey: String) {
    data class DirectQuery(val query: String, val isUrl: Boolean) : OmniboxRowItem("direct_$query")
    data class ClipboardUrl(val url: String) : OmniboxRowItem("clip_$url")
    data class LocalMatch(
        val title: String,
        val url: String,
        val isBookmark: Boolean
    ) : OmniboxRowItem("local_${if (isBookmark) "bm" else "hist"}_$url")

    data class EngineSuggestion(val text: String) : OmniboxRowItem("sug_$text")
}

@Composable
fun SearchScreen(
    initialQuery: String,
    searchEngine: SearchEngine,
    isIncognito: Boolean,
    suggestions: List<String>,
    isLoadingSuggestions: Boolean,
    history: List<HistoryEntity>,
    bookmarks: List<BookmarkEntity>,
    onQueryChange: (String) -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onSubmitQuery: (String) -> Unit,
    onQrClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onClearSuggestions: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var fieldValue by remember(initialQuery) {
        mutableStateOf(
            TextFieldValue(
                text = initialQuery,
                selection = TextRange(0, initialQuery.length)
            )
        )
    }
    var showEngineToolbox by remember { mutableStateOf(false) }

    val clipboardUrl = remember {
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (clipText.startsWith("http://", ignoreCase = true) || clipText.startsWith("https://", ignoreCase = true)) {
                clipText.take(240)
            } else {
                null
            }
        }.getOrNull()
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) {
            onQueryChange(initialQuery)
        }
        delay(50)
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    DisposableEffect(Unit) {
        onDispose { onClearSuggestions() }
    }

    val trimmedQuery = fieldValue.text.trim()

    val rows = remember(trimmedQuery, suggestions, history, bookmarks, clipboardUrl, isIncognito) {
        buildList {
            if (trimmedQuery.isNotBlank()) {
                val isUrl = SearchSuggestionEngine.looksLikeExplicitUrl(trimmedQuery) ||
                    (!trimmedQuery.contains(" ") && trimmedQuery.contains("."))
                add(OmniboxRowItem.DirectQuery(trimmedQuery, isUrl))

                val seenUrls = HashSet<String>()
                bookmarks
                    .filter {
                        it.title.contains(trimmedQuery, ignoreCase = true) ||
                            it.url.contains(trimmedQuery, ignoreCase = true)
                    }
                    .take(2)
                    .forEach { bm ->
                        if (seenUrls.add(bm.url.lowercase())) {
                            add(OmniboxRowItem.LocalMatch(title = bm.title, url = bm.url, isBookmark = true))
                        }
                    }

                if (!isIncognito) {
                    history
                        .filter {
                            it.title.contains(trimmedQuery, ignoreCase = true) ||
                                it.url.contains(trimmedQuery, ignoreCase = true)
                        }
                        .take(3)
                        .forEach { h ->
                            if (seenUrls.add(h.url.lowercase())) {
                                add(OmniboxRowItem.LocalMatch(title = h.title, url = h.url, isBookmark = false))
                            }
                        }
                }

                suggestions.forEach { sug ->
                    if (!sug.equals(trimmedQuery, ignoreCase = true)) {
                        add(OmniboxRowItem.EngineSuggestion(sug))
                    }
                }
            } else {
                if (clipboardUrl != null) {
                    add(OmniboxRowItem.ClipboardUrl(clipboardUrl))
                }
                val seenUrls = HashSet<String>()
                if (!isIncognito) {
                    history.take(5).forEach { h ->
                        if (seenUrls.add(h.url.lowercase())) {
                            add(OmniboxRowItem.LocalMatch(title = h.title, url = h.url, isBookmark = false))
                        }
                    }
                }
                bookmarks.take(4).forEach { bm ->
                    if (seenUrls.add(bm.url.lowercase())) {
                        add(OmniboxRowItem.LocalMatch(title = bm.title, url = bm.url, isBookmark = true))
                    }
                }
            }
        }
    }

    val containerBg = if (isIncognito) WeblioIncognitoSurface else MaterialTheme.colorScheme.background
    val barBg = if (isIncognito) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("search_screen_root"),
        color = containerBg
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Top Search Bar Pill matching user's screenshot (+ | input | X)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(barBg)
                        .then(
                            if (isIncognito) {
                                Modifier.border(
                                    1.dp,
                                    WeblioIncognitoAccent.copy(alpha = 0.55f),
                                    RoundedCornerShape(28.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showEngineToolbox = !showEngineToolbox },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("search_plus_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Switch search engine or tools",
                            tint = if (showEngineToolbox) WeblioCyan else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = { next ->
                                fieldValue = next
                                onQueryChange(next.text)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(
                                if (isIncognito) WeblioIncognitoAccent else MaterialTheme.colorScheme.primary
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    val q = fieldValue.text.trim()
                                    if (q.isNotBlank()) {
                                        onSubmitQuery(q)
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag("search_page_input"),
                            decorationBox = { innerTextField ->
                                if (fieldValue.text.isEmpty()) {
                                    Text(
                                        text = if (isIncognito) {
                                            "Private search with ${searchEngine.displayName}"
                                        } else {
                                            "Search ${searchEngine.displayName} or type URL"
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }

                    IconButton(
                        onClick = {
                            if (fieldValue.text.isNotEmpty()) {
                                fieldValue = TextFieldValue("")
                                onQueryChange("")
                                runCatching { focusRequester.requestFocus() }
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("search_clear_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = if (fieldValue.text.isNotEmpty()) "Clear search" else "Close search",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Expandable quick engine switcher + QR/Voice actions triggered from the '+' button
                AnimatedVisibility(visible = showEngineToolbox) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SearchEngine.entries.forEach { engine ->
                                val selected = engine == searchEngine
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .then(
                                            if (selected) Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(16.dp)
                                            ) else Modifier
                                        )
                                        .clickable {
                                            onSelectSearchEngine(engine)
                                            if (fieldValue.text.isNotBlank()) {
                                                onQueryChange(fieldValue.text)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(androidx.compose.ui.graphics.Color.White)
                                            .padding(2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        WebsiteOnlineLogo(
                                            logoUrl = engine.logoUrl(),
                                            contentDescription = engine.displayName,
                                            fallbackIcon = Icons.Filled.Search,
                                            fallbackTint = MaterialTheme.colorScheme.primary,
                                            size = 14.dp
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = engine.displayName,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    showEngineToolbox = false
                                    onVoiceClick()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = "Voice search", tint = WeblioCyan)
                            }
                            IconButton(
                                onClick = {
                                    showEngineToolbox = false
                                    onQrClick()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR code", tint = WeblioCyan)
                            }
                        }
                    }
                }

                if (isLoadingSuggestions) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .height(2.dp)
                            .clip(CircleShape),
                        color = if (isIncognito) WeblioIncognitoAccent else WeblioCyan,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                }

                if (rows.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_suggestions_list")
                        ) {
                            itemsIndexed(
                                items = rows,
                                key = { _, item -> item.stableKey }
                            ) { index, item ->
                                when (item) {
                                    is OmniboxRowItem.DirectQuery -> {
                                        SuggestionRow(
                                            leadingIcon = if (item.isUrl) Icons.Outlined.Public else Icons.Filled.Search,
                                            primaryText = AnnotatedString(item.query),
                                            secondaryText = if (item.isUrl) "Open website" else null,
                                            showFillArrow = false,
                                            onRowClick = { onSubmitQuery(item.query) },
                                            onFillClick = {},
                                            modifier = Modifier.testTag("search_direct_query_row")
                                        )
                                    }

                                    is OmniboxRowItem.ClipboardUrl -> {
                                        SuggestionRow(
                                            leadingIcon = Icons.Filled.ContentPaste,
                                            primaryText = AnnotatedString("Link you copied"),
                                            secondaryText = item.url,
                                            showFillArrow = true,
                                            onRowClick = { onSubmitQuery(item.url) },
                                            onFillClick = {
                                                fieldValue = TextFieldValue(item.url, TextRange(item.url.length))
                                                onQueryChange(item.url)
                                            },
                                            modifier = Modifier.testTag("search_clipboard_row")
                                        )
                                    }

                                    is OmniboxRowItem.LocalMatch -> {
                                        SuggestionRow(
                                            leadingIcon = if (item.isBookmark) Icons.Filled.Star else Icons.Filled.History,
                                            primaryText = highlightQueryMatch(
                                                text = item.title.ifBlank { item.url },
                                                query = trimmedQuery
                                            ),
                                            secondaryText = item.url,
                                            showFillArrow = true,
                                            onRowClick = { onSubmitQuery(item.url) },
                                            onFillClick = {
                                                fieldValue = TextFieldValue(item.url, TextRange(item.url.length))
                                                onQueryChange(item.url)
                                            },
                                            modifier = Modifier.testTag("search_local_row_$index")
                                        )
                                    }

                                    is OmniboxRowItem.EngineSuggestion -> {
                                        SuggestionRow(
                                            leadingIcon = Icons.Filled.Search,
                                            primaryText = highlightAutocompleteSuffix(
                                                suggestion = item.text,
                                                query = trimmedQuery
                                            ),
                                            secondaryText = null,
                                            showFillArrow = true,
                                            onRowClick = { onSubmitQuery(item.text) },
                                            onFillClick = {
                                                fieldValue = TextFieldValue(item.text, TextRange(item.text.length))
                                                onQueryChange(item.text)
                                            },
                                            modifier = Modifier.testTag("search_suggestion_row_$index"),
                                            fillTag = "search_fill_arrow_$index"
                                        )
                                    }
                                }

                                if (index < rows.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    leadingIcon: ImageVector,
    primaryText: AnnotatedString,
    secondaryText: String?,
    showFillArrow: Boolean,
    onRowClick: () -> Unit,
    onFillClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillTag: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(start = 16.dp, end = if (showFillArrow) 4.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!secondaryText.isNullOrBlank()) {
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = WeblioCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showFillArrow) {
            IconButton(
                onClick = onFillClick,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (fillTag != null) Modifier.testTag(fillTag) else Modifier)
            ) {
                Icon(
                    imageVector = Icons.Filled.NorthWest,
                    contentDescription = "Insert into search bar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

internal fun highlightAutocompleteSuffix(suggestion: String, query: String): AnnotatedString {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return AnnotatedString(suggestion)

    val lowerSug = suggestion.lowercase()
    val lowerQ = cleanQuery.lowercase()

    return if (lowerSug.startsWith(lowerQ)) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Normal)) {
                append(suggestion.substring(0, cleanQuery.length))
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(suggestion.substring(cleanQuery.length))
            }
        }
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(suggestion)
            }
        }
    }
}

private fun highlightQueryMatch(text: String, query: String): AnnotatedString {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return AnnotatedString(text)
    val idx = text.indexOf(cleanQuery, ignoreCase = true)
    if (idx < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, idx))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(idx, idx + cleanQuery.length))
        }
        append(text.substring(idx + cleanQuery.length))
    }
}
