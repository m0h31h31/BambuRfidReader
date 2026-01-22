package com.m0h31h31.bamburfidreader.ui.screens

import android.graphics.drawable.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.FilamentDbHelper
import com.m0h31h31.bamburfidreader.InventoryItem
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch
import com.m0h31h31.bamburfidreader.ui.theme.BambuRfidReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    dbHelper: FilamentDbHelper?,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<InventoryItem>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<InventoryItem?>(null) }
    var pendingEdit by remember { mutableStateOf<InventoryItem?>(null) }
    var editPercent by remember { mutableStateOf(0f) }
    var editGrams by remember { mutableStateOf<Int?>(null) }
    var editTotalGrams by remember { mutableStateOf(0) }
    var sortByRemaining by remember { mutableStateOf(false) }
    var sortDescending by remember { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()

    LaunchedEffect(dbHelper, query, refreshKey, sortByRemaining, sortDescending) {
        val db = dbHelper?.readableDatabase
        val result = if (db != null) {
            val inventoryItems = dbHelper.queryInventory(db, query)
            if (sortByRemaining) {
                if (sortDescending) {
                    // 降序排序：余量从多到少
                    inventoryItems.sortedByDescending { it.remainingPercent }
                } else {
                    // 升序排序：余量从少到多
                    inventoryItems.sortedBy { it.remainingPercent }
                }
            } else {
                inventoryItems
            }
        } else {
            emptyList()
        }
        // 确保在主线程中更新状态并滚动到顶部
        withContext(Dispatchers.Main) {
            items = result
            // 数据更新后滚动到列表顶部
            lazyListState.scrollToItem(0)
        }
    }

    fun toggleSort() {
        if (!sortByRemaining) {
            // 如果未启用排序，则启用排序并默认降序
            sortByRemaining = true
            sortDescending = true
        } else if (sortDescending) {
            // 如果是降序排序，则切换为升序排序
            sortDescending = false
        } else {
            // 如果是升序排序，则禁用排序
            sortByRemaining = false
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.dialog_delete_title)) },
            text = { Text(text = stringResource(R.string.dialog_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = pendingDelete
                        if (target != null) {
                            val db = dbHelper?.writableDatabase
                            if (db != null) {
                                dbHelper.deleteTrayInventory(db, target.trayUid)
                            }
                            items = items.filter { it.trayUid != target.trayUid }
                        }
                        pendingDelete = null
                    }
                ) {
                    Text(text = stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (pendingEdit != null) {
        AlertDialog(
            onDismissRequest = { pendingEdit = null },
            title = { Text(text = "编辑耗材") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val target = pendingEdit
                                if (target != null && editTotalGrams > 0) {
                                    val next = (editGrams ?: 0) - 1
                                    val clamped = next.coerceAtLeast(0)
                                    editGrams = clamped
                                    editPercent = (clamped * 100f / editTotalGrams)
                                }
                            },
                            enabled = pendingEdit != null && editTotalGrams > 0
                        ) {
                            Text(text = "-")
                        }
                        TextField(
                            value = editGrams?.toString() ?: "",
                            onValueChange = { text ->
                                val value = text.filter { it.isDigit() }
                                val intValue = value.toIntOrNull() ?: 0
                                editGrams = intValue
                                val target = pendingEdit
                                if (target != null && editTotalGrams > 0) {
                                    editPercent = (intValue * 100f / editTotalGrams)
                                }
                            },
                            label = { Text(text = "剩余克重(g)") },
                            singleLine = true,
                            modifier = modifier.weight(1f)
                        )
//                        Text(
//                            text = "g",
//                            style = MaterialTheme.typography.bodyMedium,
//                            modifier = Modifier.padding(start = 8.dp)
//                        )
                        Button(
                            onClick = {
                                val target = pendingEdit
                                if (target != null && editTotalGrams > 0) {
                                    val next = (editGrams ?: 0) + 1
                                    val clamped = next.coerceAtMost(editTotalGrams)
                                    editGrams = clamped
                                    editPercent = (clamped * 100f / editTotalGrams)
                                }
                            },
                            enabled = pendingEdit != null && editTotalGrams > 0
                        ) {
                            Text(text = "+")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = editGrams?.toFloat() ?: 0f,
                            onValueChange = { value ->
                                val target = pendingEdit
                                if (target != null && editTotalGrams > 0) {
                                    val intValue = Math.round(value).toInt().coerceIn(0, editTotalGrams)
                                    editGrams = intValue
                                    editPercent = (intValue * 100f / editTotalGrams)
                                }
                            },
                            valueRange = 0f..editTotalGrams.toFloat(),
                            enabled = pendingEdit != null && editTotalGrams > 0,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = String.format("%.1f%%", editPercent),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pendingEdit
                        if (target != null) {
                            val db = dbHelper?.writableDatabase
                            if (db != null) {
                                // 更新百分比和克重
                                dbHelper.upsertTrayRemaining(db, target.trayUid, editPercent, editGrams)
                            }
                            // 更新本地列表
                            items = items.map { 
                                if (it.trayUid == target.trayUid) {
                                    it.copy(remainingPercent = editPercent, remainingGrams = editGrams)
                                } else {
                                    it
                                }
                            }
                        }
                        pendingEdit = null
                    }
                ) {
                    Text(text = "确定")
                }
            },
            dismissButton = {
                Button(onClick = { pendingEdit = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.inventory_title),
                style = MaterialTheme.typography.titleLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(text = stringResource(R.string.inventory_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 0.dp),
                )
                IconButton(
                    onClick = { toggleSort() },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(
                            if (sortByRemaining) MaterialTheme.colorScheme.primaryContainer 
                            else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector = if (sortByRemaining && sortDescending) 
                            Icons.Filled.ArrowDownward 
                        else if (sortByRemaining) 
                            Icons.Filled.ArrowUpward 
                        else
                            Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "排序",
                        tint = if (sortByRemaining) MaterialTheme.colorScheme.primary 
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.inventory_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.trayUid }) { item ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    // 右滑删除
                                    if (pendingDelete == null) {
                                        pendingDelete = item
                                    }
                                    false
                                } else if (value == SwipeToDismissBoxValue.StartToEnd) {
                                // 左滑编辑
                                if (pendingEdit == null) {
                                    pendingEdit = item
                                    editPercent = item.remainingPercent
                                    editGrams = item.remainingGrams
                                    // 假设总克重为1000g，实际应用中可能需要从数据库中获取
                                    editTotalGrams = 1000
                                }
                                false
                            } else {
                                false
                            }
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                    Color(0xFFE54D4D) // 删除红色
                                } else {
                                    Color(0xFF4CAF50) // 编辑绿色
                                }
                                val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                    Alignment.CenterEnd
                                } else {
                                    Alignment.CenterStart
                                }
                                val text = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                    stringResource(R.string.action_delete)
                                } else {
                                    "编辑"
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 6.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(color)
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = alignment
                                ) {
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            },
                            content = {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            ColorSwatch(
                                                colorValues = item.colorValues,
                                                colorType = item.colorType,
                                                modifier = Modifier.size(36.dp)
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(start = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = item.materialType.ifBlank {
                                                        stringResource(
                                                            R.string.inventory_unknown_material
                                                        )
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = item.colorName.ifBlank {
                                                            stringResource(
                                                                R.string.inventory_unknown_color
                                                            )
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = if (item.colorCode.isNotBlank()) {
                                                            stringResource(
                                                                R.string.inventory_color_code_format,
                                                                item.colorCode
                                                            )
                                                        } else {
                                                            stringResource(
                                                                R.string.inventory_color_code_unknown
                                                            )
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = if (item.remainingGrams != null) {
                                                            "剩余克重: ${item.remainingGrams}g"
                                                        } else {
                                                            "剩余克重: 未知"
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            LinearProgressIndicator(
                                                progress = { item.remainingPercent / 100f },
                                                modifier = Modifier
                                                    .weight(0.6f)
                                                    .height(6.dp)
                                                    .padding(end = 8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.format_percent_decimal,
                                                    item.remainingPercent
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewInventoryScreen() {
    BambuRfidReaderTheme {
        InventoryScreen(
            dbHelper = null
        )
    }
}