package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch
import com.m0h31h31.bamburfidreader.ui.components.NeuButton
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import com.m0h31h31.bamburfidreader.ui.components.NeuTextField
import com.m0h31h31.bamburfidreader.ui.components.neuBackground

private val tagItemShape = RoundedCornerShape(24.dp)

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
    onDelete: (ShareTagItem) -> String = { "" },
    onCancelWrite: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var hintMessage by remember { mutableStateOf("") }
    var pendingDeleteItem by remember { mutableStateOf<ShareTagItem?>(null) }
    val unknownText = stringResource(R.string.label_unknown)
    val unknownColorText = stringResource(R.string.data_unknown_color)
    val unknownColorIdText = stringResource(R.string.tag_unknown_color_id)
    val selectOneFirstText = stringResource(R.string.tag_select_one_first)

    LaunchedEffect(preselectedFileName, items) {
        val target = preselectedFileName
        if (!target.isNullOrBlank()) {
            val matched = items.firstOrNull { it.fileName == target }
            if (matched != null) {
                selectedFileName = matched.relativePath
            }
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
    val selectedItem = items.firstOrNull { it.relativePath == selectedFileName }

    Surface(
        modifier = modifier.fillMaxSize().neuBackground(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeuTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.tag_search_placeholder),
                    modifier = Modifier.weight(1f)
                )
                NeuButton(
                    text = stringResource(R.string.tag_refresh),
                    onClick = { hintMessage = onRefresh() },
                    modifier = Modifier.width(88.dp)
                )
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
                text = stringResource(
                    R.string.tag_summary_format,
                    items.size,
                    filteredItems.size
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            NeuPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredItems, key = { it.relativePath }) { item ->
                            val selected = item.relativePath == selectedFileName
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        pendingDeleteItem = item
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
                                            .padding(vertical = 3.dp)
                                            .clip(tagItemShape)
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
                                }
                            ) {
                                NeuPanel(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedFileName = item.relativePath },
                                    shape = tagItemShape,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.materialType.ifBlank { unknownText },
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.tag_uid_color_id_format,
                                                    uidDisplayName(item.fileName),
                                                    item.colorUid.ifBlank { unknownColorIdText }
                                                ),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = item.colorName.ifBlank { unknownColorText },
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
                            Text(text = stringResource(R.string.tag_loading_shared_data), fontSize = 11.sp)
                        }
                    }
                }
            }

            NeuPanel(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(9.dp)
            ) {
                Column(
                    modifier = Modifier,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        stringResource(R.string.tag_write_notice_title),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.tag_write_notice_1),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.tag_write_notice_2),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.tag_write_notice_3),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.tag_write_notice_4),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (selectedItem != null) {
                Text(
                    text = stringResource(
                        R.string.tag_current_selection,
                        uidDisplayName(selectedItem.fileName)
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val deleteTarget = pendingDeleteItem
            if (deleteTarget != null) {
                AlertDialog(
                    onDismissRequest = { pendingDeleteItem = null },
                    title = { Text(stringResource(R.string.tag_delete_confirm_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.tag_delete_confirm_message,
                                uidDisplayName(deleteTarget.fileName)
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val message = onDelete(deleteTarget)
                                hintMessage = message
                                val lowerMessage = message.lowercase()
                                val deleted = message.startsWith("删除成功") ||
                                    message.contains("已从列表移除") ||
                                    lowerMessage.startsWith("delete success") ||
                                    lowerMessage.contains("removed from list")
                                if (deleted && selectedFileName == deleteTarget.relativePath) {
                                    selectedFileName = null
                                }
                                pendingDeleteItem = null
                            }
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteItem = null }) {
                            Text(stringResource(R.string.action_cancel))
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
                        if (item != null) {
                            onStartWrite(item)
                        } else {
                            hintMessage = selectOneFirstText
                        }
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
