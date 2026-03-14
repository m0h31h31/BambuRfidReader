package com.m0h31h31.bamburfidreader.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
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
import com.m0h31h31.bamburfidreader.ui.components.AppSwitch
import com.m0h31h31.bamburfidreader.ui.components.neuBackground
import com.m0h31h31.bamburfidreader.ui.theme.AppUiStyle
import com.m0h31h31.bamburfidreader.ui.theme.LocalAppUiStyle
import com.m0h31h31.bamburfidreader.ui.theme.ThemeMode
import com.m0h31h31.bamburfidreader.utils.ConfigManager
import kotlinx.coroutines.delay

private enum class StatusTone {
    SUCCESS,
    ERROR,
    WARNING,
    INFO
}

private const val MISC_PREFS = "misc_screen_prefs"
private const val KEY_DISMISSED_NOTICE_MESSAGE = "dismissed_notice_message"
private const val KEY_DISMISSED_AD_MESSAGE = "dismissed_ad_message"

private fun resolveStatusTone(message: String): StatusTone {
    val text = message.lowercase()
    return when {
        listOf("失败", "错误", "异常", "取消", "不可用", "failed", "error", "cancel", "unavailable").any { it in text } -> StatusTone.ERROR
        listOf("成功", "完成", "已保存", "已打包", "已停止", "已导入", "success", "completed", "saved", "exported", "stopped", "imported").any { it in text } -> StatusTone.SUCCESS
        listOf("提醒", "警告", "请", "等待", "准备", "覆盖", "warning", "please", "wait", "ready", "overwrite").any { it in text } -> StatusTone.WARNING
        else -> StatusTone.INFO
    }
}

private fun normalizeConfigMessage(message: String): String {
    return message
        .replace("\r\n", "\n")
        .trim()
}

@Composable
private fun statusToneColor(tone: StatusTone): Color {
    val uiStyle = LocalAppUiStyle.current
    return when (tone) {
        StatusTone.SUCCESS -> if (uiStyle == AppUiStyle.MIUIX) {
            MaterialTheme.colorScheme.primary
        } else {
            Color(0xFF2E8B57)
        }
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        StatusTone.WARNING -> if (uiStyle == AppUiStyle.MIUIX) {
            MaterialTheme.colorScheme.tertiary
        } else {
            Color(0xFFB7791F)
        }
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
    onClearSelfTags: () -> String = { "" },
    onClearShareTags: () -> String = { "" },
    onResetDatabase: () -> String = { "" },
    miscStatusMessage: String = "",
    onExportTagPackage: () -> String = { "" },
    onSelectImportTagPackage: () -> String = { "" },
    selfTagCount: Int = 0,
    appConfigMessage: String = "",
    appConfigAdMessage: String = "",
    boostLink: ConfigManager.AppLinkConfig = ConfigManager.AppLinkConfig("", ""),
    logoLinks: Map<String, ConfigManager.AppLinkConfig> = emptyMap(),
    uiStyle: AppUiStyle = AppUiStyle.NEUMORPHIC,
    onUiStyleChange: (AppUiStyle) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    readAllSectors: Boolean = false,
    onReadAllSectorsChange: (Boolean) -> Unit = {},
    saveKeysToFile: Boolean = false,
    onSaveKeysToFileChange: (Boolean) -> Unit = {},
    formatTagDebugEnabled: Boolean = false,
    onFormatTagDebugEnabledChange: (Boolean) -> Unit = {},
    forceOverwriteImport: Boolean = false,
    onForceOverwriteImportChange: (Boolean) -> Unit = {},
    formatInProgress: Boolean = false,
    inventoryEnabled: Boolean = false,
    onInventoryEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val miscPrefs = remember(context) {
        context.getSharedPreferences(MISC_PREFS, android.content.Context.MODE_PRIVATE)
    }
    val logoOrder = remember {
        listOf("makerworld", "douyin", "qq", "gitee", "github")
    }
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }
    val normalizedNoticeMessage = remember(appConfigMessage) {
        normalizeConfigMessage(appConfigMessage)
    }
    val normalizedAdMessage = remember(appConfigAdMessage) {
        normalizeConfigMessage(appConfigAdMessage)
    }
    var message by remember { mutableStateOf("") }
    var visibleStatusMessage by remember { mutableStateOf("") }
    var lastMiscStatusMessage by remember { mutableStateOf(miscStatusMessage) }
    var lastPageMessage by remember { mutableStateOf(message) }
    var dismissedStatusMessage by rememberSaveable { mutableStateOf("") }
    var dismissedNoticeMessage by remember {
        mutableStateOf(miscPrefs.getString(KEY_DISMISSED_NOTICE_MESSAGE, "").orEmpty())
    }
    var dismissedAdMessage by remember {
        mutableStateOf(miscPrefs.getString(KEY_DISMISSED_AD_MESSAGE, "").orEmpty())
    }
    var showReadAllSectorsDialog by remember { mutableStateOf(false) }
    var showImportDatabaseConfirmDialog by remember { mutableStateOf(false) }
    var showClearSelfTagsConfirmDialog by remember { mutableStateOf(false) }
    var showClearShareTagsConfirmDialog by remember { mutableStateOf(false) }
    var versionTapCount by rememberSaveable { mutableStateOf(0) }
    var versionEggVisible by remember { mutableStateOf(false) }
    var versionEggNonce by remember { mutableStateOf(0) }
    val versionEggPalette = if (uiStyle == AppUiStyle.MIUIX) {
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        )
    } else {
        listOf(
            Color(0xFFE8F2FF),
            Color(0xFFFFF4DD),
            Color(0xFFEAF9F0),
            Color(0xFFFFEBF1),
            Color(0xFFF2ECFF)
        )
    }
    val versionEggAccent = versionEggPalette[versionEggNonce % versionEggPalette.size]
    val versionEggMessageRes = when {
        versionTapCount >= 8 -> R.string.misc_easter_egg_5
        versionTapCount >= 6 -> R.string.misc_easter_egg_4
        versionTapCount >= 4 -> R.string.misc_easter_egg_3
        versionTapCount >= 2 -> R.string.misc_easter_egg_2
        else -> R.string.misc_easter_egg_1
    }

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

    LaunchedEffect(versionEggVisible, versionEggNonce) {
        if (!versionEggVisible) return@LaunchedEffect
        delay(900)
        versionEggVisible = false
        versionTapCount = 0
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
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(text = stringResource(R.string.misc_ui_style))
                            Text(
                                text = if (uiStyle == AppUiStyle.MIUIX) {
                                    stringResource(R.string.misc_ui_style_miuix)
                                } else {
                                    stringResource(R.string.misc_ui_style_neumorphism)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppSwitch(
                            checked = uiStyle == AppUiStyle.MIUIX,
                            onCheckedChange = {
                                onUiStyleChange(
                                    if (it) AppUiStyle.MIUIX else AppUiStyle.NEUMORPHIC
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.misc_theme_mode),
                            modifier = Modifier.weight(1f)
                        )
                        val themeModes = listOf(
                            ThemeMode.LIGHT to stringResource(R.string.misc_theme_mode_light),
                            ThemeMode.DARK to stringResource(R.string.misc_theme_mode_dark),
                            ThemeMode.SYSTEM to stringResource(R.string.misc_theme_mode_system)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            themeModes.forEach { (mode, label) ->
                                val selected = themeMode == mode
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    border = if (selected) {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    },
                                    modifier = Modifier.clickable { onThemeModeChange(mode) }
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.misc_read_all_sectors))
                        AppSwitch(
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
                        AppSwitch(
                            checked = saveKeysToFile,
                            onCheckedChange = onSaveKeysToFileChange
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(text = stringResource(R.string.config_inventory_feature))
                            Text(
                                text = stringResource(R.string.config_inventory_feature_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppSwitch(
                            checked = inventoryEnabled,
                            onCheckedChange = onInventoryEnabledChange
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

            if (showClearSelfTagsConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showClearSelfTagsConfirmDialog = false },
                    title = { Text(text = stringResource(R.string.misc_clear_self_tags_title)) },
                    text = {
                        Text(text = stringResource(R.string.misc_clear_self_tags_message))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showClearSelfTagsConfirmDialog = false
                                message = onClearSelfTags()
                            }
                        ) {
                            Text(text = stringResource(R.string.action_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearSelfTagsConfirmDialog = false }) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            if (showClearShareTagsConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showClearShareTagsConfirmDialog = false },
                    title = { Text(text = stringResource(R.string.misc_clear_share_tags_title)) },
                    text = {
                        Text(text = stringResource(R.string.misc_clear_share_tags_message))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showClearShareTagsConfirmDialog = false
                                message = onClearShareTags()
                            }
                        ) {
                            Text(text = stringResource(R.string.action_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearShareTagsConfirmDialog = false }) {
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
                    AppSwitch(
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
                    text = stringResource(R.string.misc_export_tag_package_with_count, selfTagCount),
                    onClick = { message = onExportTagPackage() },
                    modifier = Modifier.weight(1f)
                )
                NeuButton(
                    text = stringResource(R.string.misc_import_tag_package),
                    onClick = { message = onSelectImportTagPackage() },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NeuButton(
                    text = stringResource(R.string.misc_clear_self_tags),
                    onClick = { showClearSelfTagsConfirmDialog = true },
                    modifier = Modifier.weight(1f)
                )
                NeuButton(
                    text = stringResource(R.string.misc_clear_share_tags),
                    onClick = { showClearShareTagsConfirmDialog = true },
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
                    AppSwitch(
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

            val visibleNoticeMessage = normalizedNoticeMessage.takeIf {
                it.isNotBlank() && it != dismissedNoticeMessage
            }
            if (visibleNoticeMessage != null) {
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.misc_notice_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(
                                    onClick = {
                                        dismissedNoticeMessage = visibleNoticeMessage
                                        miscPrefs.edit()
                                            .putString(KEY_DISMISSED_NOTICE_MESSAGE, visibleNoticeMessage)
                                            .apply()
                                    }
                                ) {
                                    Text(text = stringResource(R.string.action_hide))
                                }
                            }
                            Text(
                                text = visibleNoticeMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            val visibleAdMessage = normalizedAdMessage.takeIf {
                it.isNotBlank() && it != dismissedAdMessage
            }
            if (visibleAdMessage != null) {
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.misc_ad_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(
                                    onClick = {
                                        dismissedAdMessage = visibleAdMessage
                                        miscPrefs.edit()
                                            .putString(KEY_DISMISSED_AD_MESSAGE, visibleAdMessage)
                                            .apply()
                                    }
                                ) {
                                    Text(text = stringResource(R.string.action_hide))
                                }
                            }
                            Text(
                                text = visibleAdMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (appVersion.isNotBlank()) {
                val versionTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                val versionEggTextColor = MaterialTheme.colorScheme.onSurface
                Box(modifier = Modifier.fillMaxWidth()) {
                    NeuPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                versionTapCount += 1
                                versionEggNonce += 1
                                versionEggVisible = false
                                versionEggVisible = true
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.misc_version_format, appVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = versionTextColor
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = versionEggVisible,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-8).dp, y = (-12).dp),
                        enter = fadeIn() + scaleIn(
                            initialScale = 0.85f,
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f)
                        ),
                        exit = fadeOut() + scaleOut(targetScale = 0.92f)
                    ) {
                        Surface(
                            shape = logoShape,
                            color = versionEggAccent,
                            tonalElevation = 0.dp,
                            shadowElevation = 6.dp,
                            border = BorderStroke(1.dp, versionEggAccent.copy(alpha = 0.95f))
                        ) {
                            Text(
                                text = stringResource(versionEggMessageRes),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = versionEggTextColor
                            )
                        }
                    }
                }
            }
        }
    }
}
