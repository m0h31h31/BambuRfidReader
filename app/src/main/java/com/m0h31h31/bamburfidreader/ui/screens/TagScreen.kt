package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.ShareTagItem
import com.m0h31h31.bamburfidreader.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch

private fun uidDisplayName(fileName: String): String = fileName.removeSuffix(".txt")

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
            if (buffer.isNotEmpty()) {
                tokens += buffer.toString()
                buffer.clear()
            }
            lastType = -1
            continue
        }
        if (lastType != -1 && type != lastType && buffer.isNotEmpty()) {
            tokens += buffer.toString()
            buffer.clear()
        }
        buffer.append(c)
        lastType = type
    }
    if (buffer.isNotEmpty()) {
        tokens += buffer.toString()
    }
    return tokens.map { it.lowercase() }.filter { it.isNotBlank() }
}

@Preview
@Composable
fun TagScreen(
    items: List<ShareTagItem> = emptyList(),
    loading: Boolean = false,
    preselectedFileName: String? = null,
    refreshStatusMessage: String = "",
    writeStatusMessage: String = "",
    writeInProgress: Boolean = false,
    onRefresh: () -> String = { "" },
    onStartWrite: (ShareTagItem) -> Unit = {},
    onCancelWrite: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var hintMessage by remember { mutableStateOf("") }

    LaunchedEffect(preselectedFileName, items) {
        val target = preselectedFileName
        if (!target.isNullOrBlank() && items.any { it.fileName == target }) {
            selectedFileName = target
        }
    }

    val filteredItems = remember(items, query) {
        val tokens = tokenizeSearchQuery(query)
        if (tokens.isEmpty()) {
            items
        } else {
            items.filter { item ->
                val fields = listOf(
                    item.materialType.lowercase(),
                    item.colorName.lowercase(),
                    item.colorUid.lowercase(),
                    uidDisplayName(item.fileName).lowercase()
                )
                tokens.all { token -> fields.any { it.contains(token) } }
            }
        }
    }
    val selectedItem = items.firstOrNull { it.fileName == selectedFileName }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.tab_tag),
                style = MaterialTheme.typography.titleLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("按耗材/颜色筛选(支持 pla红)") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { hintMessage = onRefresh() },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("刷新")
                }
            }

            if (hintMessage.isNotBlank()) {
                Text(
                    text = hintMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (refreshStatusMessage.isNotBlank()) {
                Text(
                    text = refreshStatusMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "总数: ${items.size}    当前筛选: ${filteredItems.size}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.26f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredItems, key = { it.fileName }) { item ->
                            val selected = item.fileName == selectedFileName
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFileName = item.fileName },
                                border = if (selected) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.materialType.ifBlank { "未知" },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "UID: ${uidDisplayName(item.fileName)}    颜色ID: ${item.colorUid.ifBlank { "未知" }}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.colorName.ifBlank { "未知颜色" },
                                            fontSize = 12.sp
                                        )
                                        ColorSwatch(
                                            colorValues = item.colorValues,
                                            colorType = item.colorType,
                                            modifier = Modifier.width(42.dp).height(28.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (loading) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Text(text = "正在加载共享数据...", fontSize = 11.sp)
                        }
                    }
                }
            }

            HorizontalDivider()

            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "写入前请严格遵守：",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "1. 标签必须紧贴手机 NFC 区域，写入过程中不要移动",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "2. 写入完成按提示,移开标签,再贴上识别验证",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "3. 不要写入已有的标签,相同的标签会被识别为一卷料",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "4. 写入可能失败!作者不对任何后果负责!!!",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "5. 写入功能将在数据收集一段时间后开放,请大家积极共享",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (selectedItem != null) {
                Text(
                    text = "当前选择: ${uidDisplayName(selectedItem.fileName)}",
                    fontSize = 12.sp
                )
            }

//            Row(
//                modifier = Modifier.fillMaxWidth(),
//                horizontalArrangement = Arrangement.spacedBy(8.dp)
//            ) {
//                Button(
//                    onClick = {
//                        val item = selectedItem
//                        if (item != null) {
//                            onStartWrite(item)
//                        } else {
//                            hintMessage = "请先选择一条数据"
//                        }
//                    },
//                    enabled = !writeInProgress && selectedItem != null,
//                    modifier = Modifier.weight(1f)
//                ) {
//                    Text("开始写入")
//                }
//                TextButton(
//                    onClick = onCancelWrite,
//                    enabled = writeInProgress,
//                    modifier = Modifier.weight(1f)
//                ) {
//                    Text("取消写入")
//                }
//            }

            if (writeStatusMessage.isNotBlank()) {
                val statusColor = when {
                    writeStatusMessage.contains("成功") -> MaterialTheme.colorScheme.primary
                    writeStatusMessage.contains("失败") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = writeStatusMessage,
                    fontSize = 11.sp,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
