package com.m0h31h31.bamburfidreader.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.ui.components.NeuButton
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import com.m0h31h31.bamburfidreader.ui.components.neuBackground
import com.m0h31h31.bamburfidreader.utils.ConfigManager
import kotlinx.coroutines.delay

private enum class StatusTone {
    SUCCESS,
    ERROR,
    WARNING,
    INFO
}

private fun resolveStatusTone(message: String): StatusTone {
    val text = message.lowercase()
    return when {
        listOf("失败", "错误", "异常", "取消", "不可用", "failed", "error", "cancel", "unavailable").any { it in text } -> StatusTone.ERROR
        listOf("成功", "完成", "已保存", "已打包", "已停止", "已导入", "success", "completed", "saved", "exported", "stopped", "imported").any { it in text } -> StatusTone.SUCCESS
        listOf("提醒", "警告", "请", "等待", "准备", "覆盖", "warning", "please", "wait", "ready", "overwrite").any { it in text } -> StatusTone.WARNING
        else -> StatusTone.INFO
    }
}

@Composable
private fun statusToneColor(tone: StatusTone): Color {
    return when (tone) {
        StatusTone.SUCCESS -> Color(0xFF2E8B57)
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        StatusTone.WARNING -> Color(0xFFB7791F)
        StatusTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Preview
@Composable
fun MiscScreen(
    onBackupDatabase: () -> String = { "" },
    onImportDatabase: () -> String = { "" },
    onClearFuid: () -> String = { "" },
    onCancelClearFuid: () -> String = { "" },
    onResetDatabase: () -> String = { "" },
    miscStatusMessage: String = "",
    onExportTagPackage: () -> String = { "" },
    onSelectImportTagPackage: () -> String = { "" },
    appConfigMessage: String = "",
    appConfigAdMessage: String = "",
    boostLink: ConfigManager.AppLinkConfig = ConfigManager.AppLinkConfig("", ""),
    logoLinks: Map<String, ConfigManager.AppLinkConfig> = emptyMap(),
    readAllSectors: Boolean = false,
    onReadAllSectorsChange: (Boolean) -> Unit = {},
    saveKeysToFile: Boolean = false,
    onSaveKeysToFileChange: (Boolean) -> Unit = {},
    formatTagDebugEnabled: Boolean = false,
    onFormatTagDebugEnabledChange: (Boolean) -> Unit = {},
    forceOverwriteImport: Boolean = false,
    onForceOverwriteImportChange: (Boolean) -> Unit = {},
    formatInProgress: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val logoOrder = remember {
        listOf("makerworld", "douyin", "qq", "gitee", "github")
    }
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }
    var message by remember { mutableStateOf("") }
    var visibleStatusMessage by remember { mutableStateOf("") }
    var lastMiscStatusMessage by remember { mutableStateOf(miscStatusMessage) }
    var lastPageMessage by remember { mutableStateOf(message) }
    var dismissedStatusMessage by rememberSaveable { mutableStateOf("") }
    var showReadAllSectorsDialog by remember { mutableStateOf(false) }
    var showImportDatabaseConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(miscStatusMessage, message) {
        val trimmedMiscStatus = miscStatusMessage.trim()
        val trimmedPageMessage = message.trim()
        if (
            dismissedStatusMessage.isNotBlank() &&
            trimmedMiscStatus != dismissedStatusMessage &&
            trimmedPageMessage != dismissedStatusMessage
        ) {
            dismissedStatusMessage = ""
        }
        val nextMessage = when {
            trimmedPageMessage != lastPageMessage && trimmedPageMessage.isNotBlank() -> trimmedPageMessage
            trimmedMiscStatus != lastMiscStatusMessage && trimmedMiscStatus.isNotBlank() -> trimmedMiscStatus
            trimmedPageMessage.isNotBlank() -> trimmedPageMessage
            else -> trimmedMiscStatus
        }
        lastMiscStatusMessage = trimmedMiscStatus
        lastPageMessage = trimmedPageMessage
        if (nextMessage.isBlank()) {
            visibleStatusMessage = ""
            return@LaunchedEffect
        }
        if (nextMessage == dismissedStatusMessage) {
            visibleStatusMessage = ""
            return@LaunchedEffect
        }
        visibleStatusMessage = nextMessage
        delay(10000)
        if (visibleStatusMessage == nextMessage) {
            visibleStatusMessage = ""
            dismissedStatusMessage = nextMessage
        }
    }

    fun handleReadAllSectorsChange(checked: Boolean) {
        if (checked) {
            showReadAllSectorsDialog = true
        } else {
            onReadAllSectorsChange(false)
        }
    }

    fun confirmReadAllSectors() {
        showReadAllSectorsDialog = false
        onReadAllSectorsChange(true)
    }

    fun confirmImportDatabase() {
        showImportDatabaseConfirmDialog = false
        message = onImportDatabase()
    }

    val footerLogos = remember(context) {
        runCatching {
            context.assets.list("logos")
                .orEmpty()
                .sortedWith(
                    compareBy<String> {
                        val baseName = it.substringBeforeLast('.').lowercase()
                        logoOrder.indexOf(baseName).let { index ->
                            if (index >= 0) index else Int.MAX_VALUE
                        }
                    }.thenBy { it.lowercase() }
                )
                .mapNotNull { fileName ->
                    context.assets.open("logos/$fileName").use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()?.let { bitmap ->
                            fileName to bitmap
                        }
                    }
                }
        }.getOrDefault(emptyList())
    }

    val logoShape = remember { androidx.compose.foundation.shape.RoundedCornerShape(14.dp) }

    Surface(
        modifier = modifier.fillMaxSize().neuBackground(),
        color = MaterialTheme.colorScheme.background
    ) {
        val statusText = visibleStatusMessage.ifBlank { stringResource(R.string.misc_status_idle) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val visibleStatusColor = statusToneColor(resolveStatusTone(visibleStatusMessage))
            NeuPanel(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (visibleStatusMessage.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            visibleStatusColor
                        }
                    )
                }
            }

            NeuPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.misc_read_all_sectors))
                        Switch(
                            checked = readAllSectors,
                            onCheckedChange = ::handleReadAllSectorsChange
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.misc_save_keys))
                        Switch(
                            checked = saveKeysToFile,
                            onCheckedChange = onSaveKeysToFileChange
                        )
                    }
                }
            }

            if (showReadAllSectorsDialog) {
                AlertDialog(
                    onDismissRequest = { showReadAllSectorsDialog = false },
                    title = { Text(text = stringResource(R.string.misc_read_all_title)) },
                    text = {
                        Text(
                            text = stringResource(R.string.misc_read_all_message)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = ::confirmReadAllSectors) {
                            Text(text = stringResource(R.string.action_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showReadAllSectorsDialog = false }) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            if (showImportDatabaseConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showImportDatabaseConfirmDialog = false },
                    title = { Text(text = stringResource(R.string.misc_import_db_title)) },
                    text = {
                        Text(text = stringResource(R.string.misc_import_db_message))
                    },
                    confirmButton = {
                        TextButton(onClick = ::confirmImportDatabase) {
                            Text(text = stringResource(R.string.misc_confirm_import))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportDatabaseConfirmDialog = false }) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NeuButton(
                    text = stringResource(R.string.action_backup_db),
                    onClick = { message = onBackupDatabase() },
                    modifier = Modifier.weight(1f)
                )
                NeuButton(
                    text = stringResource(R.string.action_import_db),
                    onClick = { showImportDatabaseConfirmDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }

            NeuButton(
                text = if (formatInProgress) {
                    stringResource(R.string.misc_cancel_format)
                } else {
                    stringResource(R.string.misc_format_tag)
                },
                onClick = {
                    message = if (formatInProgress) {
                        onCancelClearFuid()
                    } else {
                        onClearFuid()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            NeuPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.misc_format_debug))
                    Switch(
                        checked = formatTagDebugEnabled,
                        onCheckedChange = onFormatTagDebugEnabledChange
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NeuButton(
                    text = stringResource(R.string.misc_export_tag_package),
                    onClick = { message = onExportTagPackage() },
                    modifier = Modifier.weight(1f)
                )
                NeuButton(
                    text = stringResource(R.string.misc_import_tag_package),
                    onClick = { message = onSelectImportTagPackage() },
                    modifier = Modifier.weight(1f)
                )
            }

            NeuPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.misc_force_overwrite_import))
                    Switch(
                        checked = forceOverwriteImport,
                        onCheckedChange = onForceOverwriteImportChange
                    )
                }
            }

            if (boostLink.isUsable) {
                NeuButton(
                    text = stringResource(R.string.action_boost_open_bambu),
                    onClick = { uriHandler.openUri(boostLink.value) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (footerLogos.isNotEmpty()) {
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(15.dp)
                    ) {
                        footerLogos.forEach { (fileName, logoBitmap) ->
                            val logoKey = fileName.substringBeforeLast('.').lowercase()
                            val linkConfig = logoLinks[logoKey]
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(logoShape)
                                    .let { base ->
                                        if (linkConfig?.isUsable == true) {
                                            base.clickable { uriHandler.openUri(linkConfig.value) }
                                        } else {
                                            base
                                        }
                                    }
                            ) {
                                Image(
                                    bitmap = logoBitmap,
                                    contentDescription = fileName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            }

            if (appConfigMessage.isNotBlank()) {
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.misc_notice_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = appConfigMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (appConfigAdMessage.isNotBlank()) {
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.misc_ad_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = appConfigAdMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (appVersion.isNotBlank()) {
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.misc_version_format, appVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
