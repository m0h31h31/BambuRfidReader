package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m0h31h31.bamburfidreader.FilamentDbHelper
import com.m0h31h31.bamburfidreader.InventoryItem
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.ExperimentalFoundationApi

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

// 计算颜色亮度，返回值范围0-1，值越大越亮
private fun calculateBrightness(color: androidx.compose.ui.graphics.Color): Float {
    // 使用相对亮度公式：L = 0.2126 * R + 0.7152 * G + 0.0722 * B
    val r = color.red
    val g = color.green
    val b = color.blue
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

// 根据背景颜色获取合适的文字颜色
private fun getTextColorForBackground(colorValues: List<String>, colorCode: String): androidx.compose.ui.graphics.Color {
    // 尝试获取第一种颜色
    val firstColor = if (colorValues.isNotEmpty()) {
        try {
            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorValues[0].trim()))
        } catch (e: Exception) {
            null
        }
    } else if (colorCode.isNotEmpty()) {
        try {
            androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorCode))
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }
    
    // 如果获取到颜色，根据亮度决定文字颜色
    if (firstColor != null) {
        val brightness = calculateBrightness(firstColor)
        return if (brightness < 0.5) {
            // 深色背景，使用白色文字
            androidx.compose.ui.graphics.Color.White
        } else {
            // 浅色背景，使用黑色文字
            androidx.compose.ui.graphics.Color.Black
        }
    }
    
    // 默认使用黑色文字
    return androidx.compose.ui.graphics.Color.Black
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataScreen(dbHelper: FilamentDbHelper?, modifier: Modifier = Modifier) {
    val inventoryItems = remember { mutableStateOf<List<InventoryItem>>(emptyList()) }
    val groupedItems = remember { mutableStateOf(mapOf<String, List<InventoryItem>>()) }
    val isLoading = remember { mutableStateOf(true) }
    val useDetailedClassification = remember { mutableStateOf(false) }
    val mergeSameColorItems = remember { mutableStateOf(false) }
    val activeStackDialog = remember { mutableStateOf<StackedColorGroup?>(null) }

    LaunchedEffect(dbHelper, useDetailedClassification.value) {
        val db = dbHelper?.readableDatabase
        if (db != null) {
            val items = withContext(Dispatchers.IO) {
                dbHelper.getAllInventory(db)
            }
            inventoryItems.value = items
            val grouped = if (useDetailedClassification.value) {
                // 详细模式：使用materialDetailedType进行分类
                items.groupBy { 
                    val detailedType = it.materialDetailedType
                    if (detailedType.isNotEmpty()) detailedType else "未知"
                }
            } else {
                // 简洁模式：严格使用materialType进行分类
                items.groupBy { 
                    val type = it.materialType
                    if (type.isNotEmpty()) type else "未知"
                }
            }
            val sortedKeys = grouped.keys.sortedWith(Comparator {
                a, b ->
                val countA = grouped[a]?.size ?: 0
                val countB = grouped[b]?.size ?: 0
                if (countA != countB) {
                    // 按数量排序，数量多的排前面
                    countB.compareTo(countA)
                } else {
                    // 数量相同时，按名称排序
                    a.compareTo(b)
                }
            })
            val sortedGrouped = sortedKeys.associateWith { grouped[it]!! }
            groupedItems.value = sortedGrouped
        }
        isLoading.value = false
    }

    Column(
        modifier = modifier.fillMaxSize().padding(8.dp)
    ) {
        Text(
            text = "数据",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "分类方案：")
            Switch(
                checked = useDetailedClassification.value,
                onCheckedChange = { useDetailedClassification.value = it }
            )
            Text(text = if (useDetailedClassification.value) "详细" else "简洁")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "同类耗材合并：")
            Switch(
                checked = mergeSameColorItems.value,
                onCheckedChange = { mergeSameColorItems.value = it }
            )
            Text(text = if (mergeSameColorItems.value) "开启" else "关闭")
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading.value) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "加载中...")
            }
        } else if (groupedItems.value.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "暂无数据")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groupedItems.value.forEach { (materialType, items) ->
                    item {
                        val stackedGroups = if (mergeSameColorItems.value) {
                            items.groupBy { buildColorStackKey(it) }
                                .map { (stackKey, grouped) ->
                                    StackedColorGroup(
                                        stackKey = stackKey,
                                        displayItem = grouped.first(),
                                        items = grouped
                                    )
                                }
                                .sortedWith(
                                    compareByDescending<StackedColorGroup> { it.count }
                                        .thenBy { it.displayItem.colorName }
                                )
                        } else {
                            emptyList()
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "$materialType (${items.size})",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            // 使用FlowRow实现自适应宽度的色块布局
                            if (!mergeSameColorItems.value) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items.forEach { item ->
                                        val blockSize = 60.dp
                                        Column(
                                            modifier = Modifier.width(blockSize),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier.size(blockSize)
                                            ) {
                                                ColorSwatch(
                                                    colorValues = item.colorValues,
                                                    colorType = item.colorType,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                val textColor = getTextColorForBackground(
                                                    item.colorValues,
                                                    item.colorCode
                                                )
                                                Column(
                                                    modifier = Modifier
                                                        .align(Alignment.Center)
                                                        .padding(4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = item.colorName.take(4),
                                                        fontSize = 10.sp,
                                                        color = textColor,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.padding(bottom = 2.dp)
                                                    )
                                                    Text(
                                                        text = String.format("%.1f%%", item.remainingPercent),
                                                        fontSize = 10.sp,
                                                        color = textColor,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    stackedGroups.forEach { stack ->
                                        val blockSize = 60.dp
                                        Column(
                                            modifier = Modifier.width(blockSize),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(blockSize)
                                                    .clickable {
                                                        if (stack.count > 1) {
                                                            activeStackDialog.value = stack
                                                        }
                                                    }
                                            ) {
                                                ColorSwatch(
                                                    colorValues = stack.displayItem.colorValues,
                                                    colorType = stack.displayItem.colorType,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                val textColor = getTextColorForBackground(
                                                    stack.displayItem.colorValues,
                                                    stack.displayItem.colorCode
                                                )
                                                if (stack.count > 1) {
                                                    Text(
                                                        text = stack.displayItem.colorName.take(4),
                                                        fontSize = 10.sp,
                                                        color = textColor,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier
                                                            .align(Alignment.Center)
                                                            .padding(horizontal = 3.dp)
                                                    )
                                                } else {
                                                    Column(
                                                        modifier = Modifier
                                                            .align(Alignment.Center)
                                                            .padding(4.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(
                                                            text = stack.displayItem.colorName.take(4),
                                                            fontSize = 10.sp,
                                                            color = textColor,
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.padding(bottom = 2.dp)
                                                        )
                                                        Text(
                                                            text = String.format("%.1f%%", stack.displayItem.remainingPercent),
                                                            fontSize = 10.sp,
                                                            color = textColor,
                                                            textAlign = TextAlign.Center
                                                        )
                                                    }
                                                }
                                                if (stack.count > 1) {
                                                    Card(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(2.dp),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = Color(0xAA000000)
                                                        )
                                                    ) {
                                                        Text(
                                                            text = "${stack.count}",
                                                            fontSize = 9.sp,
                                                            color = Color.White,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
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
            }
        }
    }

    val dialogStack = activeStackDialog.value
    if (dialogStack != null) {
        AlertDialog(
            onDismissRequest = { activeStackDialog.value = null },
            title = {
                Text(
                    text = "${dialogStack.displayItem.colorName.ifBlank { "未知颜色" }} · ${dialogStack.count}",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(dialogStack.items) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
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
                                        text = item.colorName.ifBlank { "未知颜色" },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "UID ${item.trayUid.takeLast(8)}",
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
                    Text("关闭")
                }
            }
        )
    }
}
