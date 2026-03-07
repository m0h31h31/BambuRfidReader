package com.m0h31h31.bamburfidreader.ui.screens

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.FilamentDbHelper
import com.m0h31h31.bamburfidreader.InventoryItem
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import com.m0h31h31.bamburfidreader.ui.components.neuBackground
import com.m0h31h31.bamburfidreader.util.parseColorValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class StackedColorGroup(
    val stackKey: String,
    val displayItem: InventoryItem,
    val items: List<InventoryItem>
) {
    val count: Int get() = items.size
}

private fun buildColorStackKey(item: InventoryItem): String {
    val normalizedValues = item.colorValues
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .sorted()
        .joinToString(",")
    return listOf(
        item.colorType.trim().lowercase(),
        item.colorName.trim().lowercase(),
        if (normalizedValues.isNotBlank()) normalizedValues else item.colorCode.trim().lowercase()
    ).joinToString("|")
}

private fun colorSortValue(item: InventoryItem): Long {
    val raw = (item.colorValues.firstOrNull() ?: item.colorCode)
        .trim()
        .removePrefix("#")
        .uppercase()
    return raw.toLongOrNull(16) ?: Long.MAX_VALUE
}

private fun calculateBrightness(color: Color): Float {
    return 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}

private fun getTextColorForBackground(colorValues: List<String>, colorCode: String): Color {
    val firstColor = if (colorValues.isNotEmpty()) {
        parseColorValue(colorValues[0].trim())
    } else {
        parseColorValue(colorCode.trim())
    }
    if (firstColor != null) {
        if (firstColor.alpha <= 0.5f) return Color.Black
        return if (calculateBrightness(firstColor) < 0.5f) Color.White else Color.Black
    }
    return Color.Black
}

private fun needsCheckerboardBackground(colorValues: List<String>, colorCode: String): Boolean {
    val candidates = if (colorValues.isNotEmpty()) colorValues else listOf(colorCode)
    return candidates.any { raw ->
        val alpha = parseColorValue(raw.trim())?.alpha ?: 1f
        alpha < 1f
    }
}

@Composable
private fun TransparencyCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val tileSize = 6.dp.toPx()
        if (tileSize <= 0f) return@Canvas
        val columns = (size.width / tileSize).roundToInt() + 1
        val rows = (size.height / tileSize).roundToInt() + 1
        val light = Color(0xFFF6F6F6)
        val dark = Color(0xFFE1E1E1)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                drawRect(
                    color = if ((x + y) % 2 == 0) light else dark,
                    topLeft = Offset(x * tileSize, y * tileSize),
                    size = androidx.compose.ui.geometry.Size(tileSize, tileSize)
                )
            }
        }
    }
}

private val swatchShape = RoundedCornerShape(14.dp)
private const val DATA_SCREEN_PREFS = "data_screen_prefs"
private const val KEY_USE_DETAILED_CLASSIFICATION = "use_detailed_classification"
private const val KEY_MERGE_SAME_COLOR_ITEMS = "merge_same_color_items"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataScreen(dbHelper: FilamentDbHelper?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val unknownColorText = stringResource(R.string.data_unknown_color)
    val prefs = remember(context) {
        context.getSharedPreferences(DATA_SCREEN_PREFS, Context.MODE_PRIVATE)
    }
    val groupedItems = remember { mutableStateOf(mapOf<String, List<InventoryItem>>()) }
    val isLoading = remember { mutableStateOf(true) }
    val useDetailedClassification = remember {
        mutableStateOf(prefs.getBoolean(KEY_USE_DETAILED_CLASSIFICATION, false))
    }
    val mergeSameColorItems = remember {
        mutableStateOf(prefs.getBoolean(KEY_MERGE_SAME_COLOR_ITEMS, false))
    }
    val activeStackDialog = remember { mutableStateOf<StackedColorGroup?>(null) }

    LaunchedEffect(useDetailedClassification.value) {
        prefs.edit().putBoolean(
            KEY_USE_DETAILED_CLASSIFICATION,
            useDetailedClassification.value
        ).apply()
    }

    LaunchedEffect(mergeSameColorItems.value) {
        prefs.edit().putBoolean(
            KEY_MERGE_SAME_COLOR_ITEMS,
            mergeSameColorItems.value
        ).apply()
    }

    LaunchedEffect(dbHelper, useDetailedClassification.value) {
        val db = dbHelper?.readableDatabase
        if (db != null) {
            val items = withContext(Dispatchers.IO) { dbHelper.getAllInventory(db) }
            val grouped = if (useDetailedClassification.value) {
                items.groupBy { item -> item.materialDetailedType.ifBlank { context.getString(R.string.data_unknown_group) } }
            } else {
                items.groupBy { item -> item.materialType.ifBlank { context.getString(R.string.data_unknown_group) } }
            }
            val sortedKeys = grouped.keys.sortedWith { a, b ->
                val countA = grouped[a]?.size ?: 0
                val countB = grouped[b]?.size ?: 0
                if (countA != countB) countB.compareTo(countA) else a.compareTo(b)
            }
            groupedItems.value = sortedKeys.associateWith { grouped[it].orEmpty() }
        } else {
            groupedItems.value = emptyMap()
        }
        isLoading.value = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .neuBackground()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.data_grouping))
                Switch(
                    checked = useDetailedClassification.value,
                    onCheckedChange = { useDetailedClassification.value = it }
                )
                Text(
                    text = if (useDetailedClassification.value) {
                        stringResource(R.string.data_grouping_detailed)
                    } else {
                        stringResource(R.string.data_grouping_simple)
                    }
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.data_merge))
                Switch(
                    checked = mergeSameColorItems.value,
                    onCheckedChange = { mergeSameColorItems.value = it }
                )
                Text(
                    text = if (mergeSameColorItems.value) {
                        stringResource(R.string.data_switch_on)
                    } else {
                        stringResource(R.string.data_switch_off)
                    }
                )
            }
        }
        Spacer(modifier = Modifier.padding(top = 3.dp))

        if (isLoading.value) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.data_loading))
            }
        } else if (groupedItems.value.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.data_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedItems.value.forEach { (materialType, items) ->
                    item {
                        val sortedItems = items.sortedByDescending { colorSortValue(it) }
                        val stackedGroups = if (mergeSameColorItems.value) {
                            sortedItems.groupBy { buildColorStackKey(it) }
                                .map { (stackKey, grouped) ->
                                    StackedColorGroup(stackKey, grouped.first(), grouped)
                                }
                                .sortedByDescending { colorSortValue(it.displayItem) }
                        } else {
                            emptyList()
                        }

                        NeuPanel(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(
                                        R.string.data_group_title_format,
                                        materialType,
                                        items.size
                                    ),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                    val cellWidth = 58.dp
                                    val minGap = 6.dp
                                    val columns = ((maxWidth + minGap) / (cellWidth + minGap))
                                        .toInt()
                                        .coerceAtLeast(1)
                                    if (!mergeSameColorItems.value) {
                                        SwatchGrid(
                                            cellWidth = cellWidth,
                                            columns = columns,
                                            itemCount = sortedItems.size
                                        ) { index ->
                                            val item = sortedItems[index]
                                            SwatchCell(
                                                size = cellWidth,
                                                colorValues = item.colorValues,
                                                colorCode = item.colorCode,
                                                colorType = item.colorType,
                                                title = item.colorName.take(4),
                                                subtitle = String.format("%.1f%%", item.remainingPercent)
                                            )
                                        }
                                    } else {
                                        SwatchGrid(
                                            cellWidth = cellWidth,
                                            columns = columns,
                                            itemCount = stackedGroups.size
                                        ) { index ->
                                            val stack = stackedGroups[index]
                                            SwatchCell(
                                                size = cellWidth,
                                                colorValues = stack.displayItem.colorValues,
                                                colorCode = stack.displayItem.colorCode,
                                                colorType = stack.displayItem.colorType,
                                                title = stack.displayItem.colorName.take(4),
                                                subtitle = if (stack.count > 1) null else String.format("%.1f%%", stack.displayItem.remainingPercent),
                                                badgeText = if (stack.count > 1) "${stack.count}" else null,
                                                modifier = Modifier.clickable {
                                                    if (stack.count > 1) {
                                                        activeStackDialog.value = stack
                                                    }
                                                }
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
    }

    val dialogStack = activeStackDialog.value
    if (dialogStack != null) {
        AlertDialog(
            onDismissRequest = { activeStackDialog.value = null },
            title = {
                Text(
                    text = stringResource(
                        R.string.data_stack_title_format,
                        dialogStack.displayItem.colorName.ifBlank {
                            context.getString(R.string.data_unknown_color)
                        },
                        dialogStack.count
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(dialogStack.items) { item ->
                        NeuPanel(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ColorSwatch(
                                    colorValues = item.colorValues,
                                    colorType = item.colorType,
                                    modifier = Modifier.size(30.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.colorName.ifBlank {
                                            unknownColorText
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.data_uid_short,
                                            item.trayUid.takeLast(8)
                                        ),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = String.format("%.1f%%", item.remainingPercent),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeStackDialog.value = null }) {
                    Text(stringResource(R.string.data_close))
                }
            }
        )
    }
}

@Composable
private fun SwatchGrid(
    cellWidth: Dp,
    columns: Int,
    itemCount: Int,
    itemContent: @Composable (Int) -> Unit
) {
    val rowSpacing = 6.dp
    Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        val rows = (0 until itemCount).chunked(columns)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { index ->
                    Box(modifier = Modifier.width(cellWidth), contentAlignment = Alignment.Center) {
                        itemContent(index)
                    }
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.width(cellWidth))
                }
            }
        }
    }
}

@Composable
private fun SwatchCell(
    size: Dp,
    colorValues: List<String>,
    colorCode: String,
    colorType: String,
    title: String,
    subtitle: String?,
    badgeText: String? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(size)) {
        if (needsCheckerboardBackground(colorValues, colorCode)) {
            TransparencyCheckerboard(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(swatchShape)
            )
        }
        ColorSwatch(
            colorValues = colorValues,
            colorType = colorType,
            modifier = Modifier.fillMaxSize()
        )
        val textColor = getTextColorForBackground(colorValues, colorCode)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 10.sp,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = if (subtitle != null) 2.dp else 0.dp)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = textColor,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (badgeText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .background(Color(0x99000000), shape = RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    style = TextStyle(
                        fontSize = 9.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
