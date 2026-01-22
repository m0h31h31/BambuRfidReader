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
                                    if (pendingDelete == null) {
                                        pendingDelete = item
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
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 6.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFE54D4D))
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_delete),
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