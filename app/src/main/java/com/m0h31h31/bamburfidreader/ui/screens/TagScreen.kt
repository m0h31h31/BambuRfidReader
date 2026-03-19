package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.m0h31h31.bamburfidreader.ShareTagItem
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.ui.components.AppCircularProgressIndicator
import com.m0h31h31.bamburfidreader.ui.components.AppSearchBar
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch
import com.m0h31h31.bamburfidreader.ui.components.NeuButton
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import com.m0h31h31.bamburfidreader.ui.components.neuBackground
import com.m0h31h31.bamburfidreader.ui.theme.AppUiStyle
import com.m0h31h31.bamburfidreader.ui.theme.LocalAppUiStyle
import com.m0h31h31.bamburfidreader.util.parseColorValue
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val tagItemShape = RoundedCornerShape(24.dp)

private fun swatchTextColor(colorValues: List<String>, colorUid: String): Color {
    val first = colorValues.firstOrNull()?.trim()?.let { parseColorValue(it) }
        ?: parseColorValue(colorUid.trim())
    if (first != null) {
        if (first.alpha <= 0.5f) return Color.Black
        val brightness = 0.2126f * first.red + 0.7152f * first.green + 0.0722f * first.blue
        return if (brightness < 0.5f) Color.White else Color.Black
    }
    return Color.Black
}

private fun tokenizeSearchQuery(input: String): List<String> {
    val tokens = mutableListOf<String>()
    val buffer = StringBuilder()

    fun charType(c: Char): Int = when {
        c.isWhitespace() -> 0
        c.isLetterOrDigit() && c.code < 128 -> 1
        c.isLetterOrDigit() -> 2
        else -> 0
    }

    var lastType = -1
    for (c in input.trim()) {
        val type = charType(c)
        if (type == 0) {
            if (buffer.isNotEmpty()) { tokens += buffer.toString(); buffer.clear() }
            lastType = -1
            continue
        }
        if (lastType != -1 && type != lastType && buffer.isNotEmpty()) {
            tokens += buffer.toString(); buffer.clear()
        }
        buffer.append(c)
        lastType = type
    }
    if (buffer.isNotEmpty()) tokens += buffer.toString()
    return tokens.map { it.lowercase() }.filter { it.isNotBlank() }
}

private fun matchesQuery(item: ShareTagItem, tokens: List<String>): Boolean {
    if (tokens.isEmpty()) return true
    val fields = listOf(
        item.materialType.lowercase(),
        item.colorName.lowercase(),
        item.colorUid.lowercase(),
        item.sourceUid.lowercase()
    )
    return tokens.all { token -> fields.any { it.contains(token) } }
}

private data class ColorGroup(
    val colorUid: String,
    val colorName: String,
    val colorType: String,
    val colorValues: List<String>,
    val items: List<ShareTagItem>
)

private data class CategoryGroup(val materialType: String, val colorGroups: List<ColorGroup>)

private fun buildCategoryGroups(items: List<ShareTagItem>): List<CategoryGroup> {
    return items
        .groupBy { it.materialType.ifBlank { "未知" } }
        .entries
        .sortedBy { it.key }
        .map { (material, matItems) ->
            val colorGroups = matItems
                .groupBy { "${it.colorUid}|${it.colorName}" }
                .entries
                .sortedBy { it.key }
                .map { (_, groupItems) ->
                    val first = groupItems.first()
                    ColorGroup(
                        colorUid = first.colorUid,
                        colorName = first.colorName,
                        colorType = first.colorType,
                        colorValues = first.colorValues,
                        items = groupItems
                    )
                }
            CategoryGroup(materialType = material, colorGroups = colorGroups)
        }
}

@Composable
private fun TagListItem(
    item: ShareTagItem,
    selected: Boolean,
    onSelect: () -> Unit,
    onDeleteRequest: () -> Unit,
    unknownText: String,
    unknownColorText: String,
    unknownColorIdText: String,
    uiStyle: AppUiStyle
) {
    val selectedFillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
    val titleColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant

    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDeleteRequest()
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val deleteColor = if (uiStyle == AppUiStyle.MIUIX) MaterialTheme.colorScheme.errorContainer else Color(0xFFE54D4D)
            val deleteTextColor = if (uiStyle == AppUiStyle.MIUIX) MaterialTheme.colorScheme.onErrorContainer else Color.White
            Box(
                modifier = Modifier.fillMaxSize().padding(vertical = 3.dp)
                    .clip(tagItemShape).background(deleteColor).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.bodyMedium,
                    color = deleteTextColor, fontWeight = FontWeight.SemiBold)
            }
        }
    ) {
        NeuPanel(
            modifier = Modifier.fillMaxWidth().clickable { onSelect() },
            shape = tagItemShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(tagItemShape)
                    .background(if (selected) selectedFillColor else Color.Transparent)
                    .border(if (selected) 1.dp else 0.dp, if (selected) selectedBorderColor else Color.Transparent, tagItemShape)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.materialType.ifBlank { unknownText }, style = MaterialTheme.typography.bodyMedium, color = titleColor)
                    Text(
                        text = stringResource(R.string.tag_uid_color_id_format, item.sourceUid, item.colorUid.ifBlank { unknownColorIdText }),
                        fontSize = 12.sp, color = subtitleColor
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.colorName.ifBlank { unknownColorText }, fontSize = 12.sp, color = titleColor)
                    ColorSwatch(colorValues = item.colorValues, colorType = item.colorType, modifier = Modifier.width(42.dp).height(28.dp))
                }
            }
        }
    }
}

/** Single color swatch tile — tap to open UID selection dialog */
@Composable
private fun ColorSwatchTile(
    group: ColorGroup,
    onTap: () -> Unit
) {
    val swatchShape = RoundedCornerShape(10.dp)
    val tileWidth = 60.dp
    val textColor = swatchTextColor(group.colorValues, group.colorUid)

    Box(
        modifier = Modifier
            .width(tileWidth)
            .height(58.dp)
            .clip(swatchShape)
            .clickable { onTap() }
    ) {
        ColorSwatch(
            colorValues = group.colorValues,
            colorType = group.colorType,
            modifier = Modifier.fillMaxSize()
        )
        // Count badge — perfect circle with centered number
        if (group.items.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(16.dp)
                    .background(Color(0xAA000000), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${group.items.size}",
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentSize(Alignment.Center)
                )
            }
        }
        // Color name + ID centered inside swatch
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = group.colorName,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = group.colorUid,
                fontSize = 8.sp,
                color = textColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Dialog for UID selection within a color group */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UidSelectionDialog(
    materialType: String,
    group: ColorGroup,
    selectedRelativePath: String?,
    unknownText: String,
    unknownColorText: String,
    unknownColorIdText: String,
    onSelect: (ShareTagItem) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Title: swatch + type / name / uid
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        ColorSwatch(
                            colorValues = group.colorValues,
                            colorType = group.colorType,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Column {
                        Text(
                            text = materialType.ifBlank { unknownText },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = buildString {
                                append(group.colorName.ifBlank { unknownColorText })
                                if (group.colorUid.isNotBlank()) append("  ${group.colorUid}")
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // UID chips in FlowRow
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    group.items.forEach { item ->
                        val selected = item.relativePath == selectedRelativePath
                        val chipBg = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                        val chipText = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        Box(
                            modifier = Modifier
                                .background(chipBg, RoundedCornerShape(6.dp))
                                .clickable {
                                    onSelect(item)
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = item.sourceUid,
                                fontSize = 11.sp,
                                color = chipText,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Close button
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.data_close))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CategoryView(
    categories: List<CategoryGroup>,
    selectedRelativePath: String?,
    expandedCategoryKeys: List<String>,
    onExpandedCategoryKeysChange: (List<String>) -> Unit,
    categoryOrder: List<String>,
    onCategoryOrderChange: (List<String>) -> Unit,
    onSelect: (ShareTagItem) -> Unit,
    unknownText: String,
    unknownColorText: String,
    unknownColorIdText: String
) {
    val orderedCategories = remember(categories, categoryOrder) {
        val catMap = categories.associateBy { it.materialType }
        val ordered = categoryOrder.mapNotNull { catMap[it] }
        val remaining = categories.filter { it.materialType !in categoryOrder }
        ordered + remaining
    }

    // Which color group's dialog is open: pair of (materialType, ColorGroup)
    var openDialog by remember { mutableStateOf<Pair<String, ColorGroup>?>(null) }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newOrder = orderedCategories.map { it.materialType }.toMutableList()
        newOrder.add(to.index, newOrder.removeAt(from.index))
        onCategoryOrderChange(newOrder)
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(orderedCategories, key = { it.materialType }) { category ->
            ReorderableItem(reorderState, key = category.materialType) { _ ->
                val catExpanded = category.materialType in expandedCategoryKeys

                Column {
                    NeuPanel(
                        modifier = Modifier.fillMaxWidth().clickable {
                            val updated = if (catExpanded) expandedCategoryKeys - category.materialType
                            else expandedCategoryKeys + category.materialType
                            onExpandedCategoryKeysChange(updated)
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DragHandle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).draggableHandle(),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = category.materialType.ifBlank { unknownText },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "(${category.colorGroups.sumOf { it.items.size }})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (catExpanded) "▲" else "▼",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AnimatedVisibility(visible = catExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 4.dp)
                        ) {
                            val cellWidth = 60.dp
                            val minGap = 4.dp
                            val columns = ((maxWidth + minGap) / (cellWidth + minGap))
                                .toInt().coerceAtLeast(1)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                category.colorGroups.chunked(columns).forEach { rowGroups ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        rowGroups.forEach { group ->
                                            Box(modifier = Modifier.width(cellWidth), contentAlignment = Alignment.Center) {
                                                ColorSwatchTile(
                                                    group = group,
                                                    onTap = { openDialog = category.materialType to group }
                                                )
                                            }
                                        }
                                        repeat(columns - rowGroups.size) {
                                            Spacer(modifier = Modifier.width(cellWidth))
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

    // UID selection dialog
    val dialogData = openDialog
    if (dialogData != null) {
        UidSelectionDialog(
            materialType = dialogData.first,
            group = dialogData.second,
            selectedRelativePath = selectedRelativePath,
            unknownText = unknownText,
            unknownColorText = unknownColorText,
            unknownColorIdText = unknownColorIdText,
            onSelect = onSelect,
            onDismiss = { openDialog = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun TagScreen(
    items: List<ShareTagItem> = emptyList(),
    loading: Boolean = false,
    preselectedFileName: String? = null,
    writeStatusMessage: String = "",
    writeInProgress: Boolean = false,
    hideCopiedTags: Boolean = true,
    dualTagMode: Boolean = false,
    tagViewMode: String = "list",
    onStartWrite: (ShareTagItem) -> Unit = {},
    onDelete: (ShareTagItem) -> String = { "" },
    onCancelWrite: () -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalAppUiStyle.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var hintMessage by remember { mutableStateOf("") }
    var pendingDeleteItem by remember { mutableStateOf<ShareTagItem?>(null) }
    var showCuidVipDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(loading) {
        if (!loading && isRefreshing) {
            isRefreshing = false
        }
    }

    var expandedCategoryKeys by rememberSaveable { mutableStateOf(listOf<String>()) }
    var noticesExpanded by rememberSaveable {
        val saved = context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("tag_notices_expanded", true)
        mutableStateOf(saved)
    }
    var categoryOrder by rememberSaveable {
        val saved = context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
            .getString("tag_category_order", "") ?: ""
        mutableStateOf(if (saved.isBlank()) listOf() else saved.split("\u001F").filter { it.isNotBlank() })
    }

    val unknownText = stringResource(R.string.label_unknown)
    val unknownColorText = stringResource(R.string.data_unknown_color)
    val unknownColorIdText = stringResource(R.string.tag_unknown_color_id)
    val selectOneFirstText = stringResource(R.string.tag_select_one_first)

    LaunchedEffect(preselectedFileName, items) {
        val target = preselectedFileName
        if (!target.isNullOrBlank()) {
            val matched = items.firstOrNull { it.fileName == target }
            if (matched != null) selectedFileName = matched.relativePath
        }
    }

    val copyThreshold = if (dualTagMode) 2 else 1
    val visibleItems = remember(items, hideCopiedTags, dualTagMode) {
        if (hideCopiedTags) items.filter { it.copyCount < copyThreshold } else items
    }

    val filteredItems = remember(visibleItems, query) {
        val tokens = tokenizeSearchQuery(query)
        if (tokens.isEmpty()) visibleItems else visibleItems.filter { matchesQuery(it, tokens) }
    }

    val categories = remember(filteredItems) { buildCategoryGroups(filteredItems) }

    val selectedItem = items.firstOrNull { it.relativePath == selectedFileName }

    Surface(modifier = modifier.fillMaxSize().neuBackground(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.tag_search_placeholder),
                modifier = Modifier.fillMaxWidth()
            )

            if (hintMessage.isNotBlank()) {
                Text(text = hintMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(R.string.tag_summary_format, items.size, filteredItems.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            NeuPanel(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)
            ) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        if (!isRefreshing && !loading) {
                            isRefreshing = true
                            onRefresh()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    if (tagViewMode == "category") {
                        CategoryView(
                            categories = categories,
                            selectedRelativePath = selectedFileName,
                            expandedCategoryKeys = expandedCategoryKeys,
                            onExpandedCategoryKeysChange = { expandedCategoryKeys = it },
                            categoryOrder = categoryOrder,
                            onCategoryOrderChange = { newOrder ->
                                categoryOrder = newOrder
                                context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
                                    .edit().putString("tag_category_order", newOrder.joinToString("\u001F")).apply()
                            },
                            onSelect = { item -> selectedFileName = item.relativePath },
                            unknownText = unknownText,
                            unknownColorText = unknownColorText,
                            unknownColorIdText = unknownColorIdText
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredItems, key = { it.relativePath }) { item ->
                                TagListItem(
                                    item = item,
                                    selected = item.relativePath == selectedFileName,
                                    onSelect = { selectedFileName = item.relativePath },
                                    onDeleteRequest = { pendingDeleteItem = item },
                                    unknownText = unknownText,
                                    unknownColorText = unknownColorText,
                                    unknownColorIdText = unknownColorIdText,
                                    uiStyle = uiStyle
                                )
                            }
                        }
                    }
                    if (loading) {
                        Row(
                            modifier = Modifier.align(Alignment.Center).padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppCircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                            Text(text = stringResource(R.string.tag_loading_shared_data), fontSize = 13.sp)
                        }
                    }
                } // end Box
                } // end PullToRefreshBox
            }

            // Notice panel with show/hide toggle
            NeuPanel(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(9.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                        val v = !noticesExpanded
                        noticesExpanded = v
                        context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("tag_notices_expanded", v).apply()
                    },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.tag_write_notice_title),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (noticesExpanded) "▲" else "▼",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    AnimatedVisibility(visible = noticesExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(stringResource(R.string.tag_write_notice_1), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.tag_write_notice_2), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.tag_write_notice_3), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.tag_write_notice_4), color = MaterialTheme.colorScheme.error, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (selectedItem != null) {
                Text(
                    text = stringResource(R.string.tag_current_selection, selectedItem.sourceUid),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val deleteTarget = pendingDeleteItem
            if (deleteTarget != null) {
                AlertDialog(
                    onDismissRequest = { pendingDeleteItem = null },
                    title = { Text(stringResource(R.string.tag_delete_confirm_title)) },
                    text = { Text(stringResource(R.string.tag_delete_confirm_message, deleteTarget.sourceUid)) },
                    confirmButton = {
                        TextButton(onClick = {
                            val message = onDelete(deleteTarget)
                            hintMessage = message
                            val lowerMsg = message.lowercase()
                            val deleted = message.startsWith("删除成功") || message.contains("已从列表移除") ||
                                lowerMsg.startsWith("delete success") || lowerMsg.contains("removed from list")
                            if (deleted && selectedFileName == deleteTarget.relativePath) selectedFileName = null
                            pendingDeleteItem = null
                        }) { Text(stringResource(R.string.action_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteItem = null }) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }

            if (showCuidVipDialog) {
                AlertDialog(
                    onDismissRequest = { showCuidVipDialog = false },
                    title = { Text(stringResource(R.string.tag_cuid_vip_title)) },
                    text = { Text(stringResource(R.string.tag_cuid_vip_message)) },
                    confirmButton = {
                        TextButton(onClick = { showCuidVipDialog = false }) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeuButton(
                    text = stringResource(R.string.tag_start_write),
                    onClick = {
                        val item = selectedItem
                        if (item != null) onStartWrite(item) else hintMessage = selectOneFirstText
                    },
                    enabled = !writeInProgress && selectedItem != null,
                    modifier = Modifier.weight(1f)
                )
                NeuButton(
                    text = stringResource(R.string.tag_cancel_write),
                    onClick = onCancelWrite,
                    enabled = writeInProgress,
                    modifier = Modifier.weight(1f)
                )
                NeuButton(
                    text = stringResource(R.string.tag_cuid_change),
                    onClick = { showCuidVipDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }

            if (writeStatusMessage.isNotBlank()) {
                val statusColor = when {
                    writeStatusMessage.contains("成功", ignoreCase = true) ||
                        writeStatusMessage.contains("success", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                    writeStatusMessage.contains("失败", ignoreCase = true) ||
                        writeStatusMessage.contains("fail", ignoreCase = true) ||
                        writeStatusMessage.contains("error", ignoreCase = true) -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val writeInProgress = writeStatusMessage.contains("正在") ||
                    writeStatusMessage.contains("请稍候") ||
                    writeStatusMessage.contains("准备就绪") ||
                    writeStatusMessage.contains("请将目标")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (writeInProgress) {
                        AppCircularProgressIndicator(modifier = Modifier.size(15.dp))
                    }
                    Text(text = writeStatusMessage, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
