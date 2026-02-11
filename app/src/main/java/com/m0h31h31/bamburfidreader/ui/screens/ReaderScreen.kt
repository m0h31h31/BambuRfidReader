package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import android.widget.Toast
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import com.m0h31h31.bamburfidreader.NfcUiState
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.LogCollector
import com.m0h31h31.bamburfidreader.logDebug
import com.m0h31h31.bamburfidreader.openTtsSettings
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch
import com.m0h31h31.bamburfidreader.ui.components.InfoLine
import com.m0h31h31.bamburfidreader.ui.theme.BambuRfidReaderTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    state: NfcUiState,
    voiceEnabled: Boolean,
    ttsReady: Boolean,
    ttsLanguageReady: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onTrayOutbound: (String) -> Unit,
    onRemainingChange: (String, Float, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var logoTapCount by remember { mutableStateOf(0) }
    var logoLastTapAt by remember { mutableStateOf(0L) }
    var showOutboundConfirm by remember(state.trayUidHex) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .padding(bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.tab_reader),
                    style = MaterialTheme.typography.titleLarge
                )
                if (state.status.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(15.dp,5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.status,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            val voiceHint = when {
                                voiceEnabled && !ttsReady -> stringResource(
                                    R.string.voice_status_engine_not_ready
                                )

                                voiceEnabled && !ttsLanguageReady -> stringResource(
                                    R.string.voice_status_language_unavailable
                                )

                                voiceEnabled -> stringResource(R.string.voice_status_on)
                                else -> stringResource(R.string.voice_status_off)
                            }
                            val canOpenTtsSettings =
                                voiceEnabled && (!ttsReady || !ttsLanguageReady)
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Switch(
                                    checked = voiceEnabled,
                                    onCheckedChange = onVoiceEnabledChange,
                                    modifier = Modifier.scale(0.8f),
                                )
                                Text(
                                    text = if (canOpenTtsSettings) {
                                        stringResource(R.string.action_voice_settings)
                                    } else {
                                        stringResource(R.string.voice_status_prefix, voiceHint)
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (canOpenTtsSettings) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        textDecoration = if (canOpenTtsSettings) {
                                            TextDecoration.Underline
                                        } else {
                                            null
                                        }
                                    ),
                                    modifier = if (canOpenTtsSettings) {
                                        Modifier
                                            .padding(start = 6.dp)
                                            .clickable {
                                                val opened = openTtsSettings(context)
                                                if (!opened) {
                                                    logDebug("无法打开语音设置")
                                                }
                                            }
                                    } else {
                                        Modifier.padding(start = 6.dp)
                                    }
                                )
                            }
                        }
                    }
                }

                val trayUidAvailable = state.trayUidHex.isNotBlank()
                val totalWeight = state.totalWeightGrams
                val hasWeight = totalWeight > 0
                val derivedGrams = if (hasWeight) {
                    (totalWeight * state.remainingPercent / 100.0).roundToInt()
                } else {
                    0
                }
                var gramsValue by remember(
                    state.trayUidHex,
                    state.remainingPercent,
                    state.totalWeightGrams
                ) {
                    mutableStateOf(derivedGrams.toFloat())
                }
                var gramsText by remember(
                    state.trayUidHex,
                    state.remainingPercent,
                    state.totalWeightGrams
                ) {
                    mutableStateOf(if (hasWeight) derivedGrams.toString() else "")
                }
                val gramsInt = gramsValue.roundToInt().coerceIn(0, totalWeight.coerceAtLeast(0))
                val percentValue = if (hasWeight) {
                    ((gramsInt * 100f / totalWeight) * 10).roundToInt() / 10f
                } else {
                    state.remainingPercent.toFloat()
                }
                
                // 添加防抖机制
                val scope = rememberCoroutineScope()
                val debounceJob = remember {
                    mutableStateOf<Job?>(null)
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(120.dp)) {
                                ColorSwatch(
                                    colorValues = state.displayColors,
                                    colorType = state.displayColorType,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            val now = System.currentTimeMillis()
                                            if (now - logoLastTapAt > 1500) {
                                                logoTapCount = 0
                                            }
                                            logoLastTapAt = now
                                            logoTapCount += 1
                                            if (logoTapCount >= 5) {
                                                logoTapCount = 0
                                                val result = LogCollector.packageLogs(context)
                                                logDebug(result)
                                                Toast
                                                    .makeText(context, result, Toast.LENGTH_SHORT)
                                                    .show()
                                            }
                                        }
                                )
                                if (trayUidAvailable) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .clickable { showOutboundConfirm = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "出库",
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = state.displayType.ifBlank {
                                        stringResource(R.string.label_unknown)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = modifier.padding(3.dp),
                                    fontSize = 18.sp,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = state.displayColorName.ifBlank {
                                            stringResource(R.string.label_unknown)
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = modifier.padding(3.dp)
                                    )
                                    Text(
                                        text = "-",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = modifier.padding(3.dp)
                                    )
                                    Text(
                                        text = state.displayColorCode.ifBlank {
                                            stringResource(R.string.label_unknown)
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                                modifier = modifier.padding(3.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        QuantityButtonGroup(
                                            value = gramsText,
                                            enabled = trayUidAvailable && hasWeight,
                                            onValueChange = { text ->
                                                val digits = text.filter { it.isDigit() }
                                                if (trayUidAvailable && hasWeight) {
                                                    val next = digits.toIntOrNull()
                                                        ?.coerceIn(0, totalWeight) ?: 0
                                                    gramsValue = next.toFloat()
                                                    gramsText = next.toString()

                                                    // 添加防抖机制，500毫秒内无输入变化时自动存储
                                                    debounceJob.value?.cancel()
                                                    debounceJob.value = scope.launch {
                                                        delay(500)
                                                        val finalGrams = gramsText.toIntOrNull()
                                                            ?.coerceIn(0, totalWeight) ?: 0
                                                        val finalPercent = if (totalWeight > 0) {
                                                            ((finalGrams * 100f / totalWeight) * 10).roundToInt() / 10f
                                                        } else {
                                                            0f
                                                        }
                                                        onRemainingChange(state.trayUidHex, finalPercent, finalGrams)
                                                    }
                                                } else {
                                                    gramsText = digits
                                                }
                                            },
                                            onDecrease = {
                                                val next = (gramsInt - 1).coerceAtLeast(0)
                                                gramsValue = next.toFloat()
                                                gramsText = next.toString()
                                                onRemainingChange(
                                                    state.trayUidHex,
                                                    (next * 100f / totalWeight),
                                                    next
                                                )
                                            },
                                            onIncrease = {
                                                val next = (gramsInt + 1).coerceAtMost(totalWeight)
                                                gramsValue = next.toFloat()
                                                gramsText = next.toString()
                                                onRemainingChange(
                                                    state.trayUidHex,
                                                    (next * 100f / totalWeight),
                                                    next
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Slider(
                                value = gramsValue,
                                onValueChange = { value ->
                                    if (trayUidAvailable && hasWeight) {
                                        val next = value.roundToInt().coerceIn(0, totalWeight)
                                        gramsValue = next.toFloat()
                                        gramsText = next.toString()
                                    }
                                },
                                valueRange = 0f..(if (hasWeight) totalWeight.toFloat() else 1f),
                                enabled = trayUidAvailable && hasWeight,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp).padding(0.dp,3.dp),
                                onValueChangeFinished = {
                                    if (trayUidAvailable && hasWeight) {
                                        // 使用最终的gramsValue计算准确的百分比和克重
                                        val finalGrams = gramsValue.roundToInt().coerceIn(0, totalWeight)
                                        val finalPercent = if (totalWeight > 0) {
                                            ((finalGrams * 100f / totalWeight) * 10).roundToInt() / 10f
                                        } else {
                                            0f
                                        }
                                        onRemainingChange(state.trayUidHex, finalPercent, finalGrams)
                                    }
                                }
                            )

                            Text(
                                text = String.format("%.1f%%", percentValue),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
//                        if (!trayUidAvailable) {
//                            Text(
//                                text = stringResource(R.string.message_tray_uid_missing),
//                                style = MaterialTheme.typography.bodySmall,
//                                color = MaterialTheme.colorScheme.onSurfaceVariant
//                            )
//                        } else if (!hasWeight) {
//                            Text(
//                                text = stringResource(R.string.message_weight_missing),
//                                style = MaterialTheme.typography.bodySmall,
//                                color = MaterialTheme.colorScheme.onSurfaceVariant
//                            )
//                        } else {
//                            Text(
//                                text = stringResource(
//                                    R.string.format_total_weight,
//                                    totalWeight
//                                ),
//                                style = MaterialTheme.typography.bodySmall,
//                                color = MaterialTheme.colorScheme.onSurfaceVariant
//                            )
//                        }
                    }
                }
                if (showOutboundConfirm) {
                    AlertDialog(
                        onDismissRequest = { showOutboundConfirm = false },
                        title = { Text("确认出库") },
                        text = { Text("确定删除该料盘ID记录吗？") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onTrayOutbound(state.trayUidHex)
                                    showOutboundConfirm = false
                                }
                            ) {
                                Text("确定")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showOutboundConfirm = false }
                            ) {
                                Text("取消")
                            }
                        }
                    )
                }

//                if (state.secondaryFields.isNotEmpty()) {
                if (true) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.label_other_info),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                InfoLine(
                                    label = "卡UID",
                                    value = state.uidHex.ifBlank { "-" },
                                    style = MaterialTheme.typography.bodySmall
                                )
                                state.secondaryFields.forEach { field ->
                                    InfoLine(
                                        label = field.label,
                                        value = field.value,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.logo_mark),
                                contentDescription = stringResource(R.string.content_logo),
                                modifier = Modifier.size(80.dp, 250.dp)
                            )
                        }
                    }
                }
            }
//            BoostFooter(
//                modifier = Modifier
//                    .align(Alignment.BottomCenter)
//                    .padding(horizontal = 2.dp, vertical = 0.dp)
//            )
        }
    }
}

@Composable
private fun QuantityButtonGroup(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val leftShape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
    val rightShape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val leftBg = if (enabled) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val rightBg = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val valueBg = MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier
            .height(44.dp)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .background(color = MaterialTheme.colorScheme.surface, shape = shape)
    ) {
        Box(
            modifier = Modifier
                .weight(0.8f)
                .fillMaxHeight()
                .clip(leftShape)
                .background(leftBg, shape = leftShape)
                .clickable(enabled = enabled, onClick = onDecrease),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .background(borderColor)
                .size(width = 1.dp, height = 44.dp)
        )
        Box(
            modifier = Modifier
                .weight(2.1f)
                .fillMaxHeight()
                .background(valueBg),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(start = 6.dp, end = 28.dp)
                )
                Text(
                    text = "g",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .background(borderColor)
                .size(width = 1.dp, height = 44.dp)
        )
        Box(
            modifier = Modifier
                .weight(0.8f)
                .fillMaxHeight()
                .clip(rightShape)
                .background(rightBg, shape = rightShape)
                .clickable(enabled = enabled, onClick = onIncrease),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}


@Composable
private fun BoostFooter(modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val boostLink =
        "bambulab://bbl/design/model/detail?design_id=2020787&instance_id=2253290&appSharePlatform=copy"
    TextButton(
        onClick = { uriHandler.openUri(boostLink) },
        modifier = modifier.padding(0.dp)
    ) {
        Text(text = stringResource(R.string.action_boost_open_bambu))
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewReaderScreen() {
    BambuRfidReaderTheme {
        ReaderScreen(
            state = NfcUiState(
                status = "Read success",
                displayType = "Support For PLA-PETG",
                displayColorName = "Orange",
                displayColorCode = "10300",
                displayColorType = "单色",
                displayColors = listOf("#FF6A13FF"),
                trayUidHex = "AABBCCDDEEFF00112233445566778899",
                remainingPercent = 75.0f,
                totalWeightGrams = 1000
            ),
            voiceEnabled = false,
            ttsReady = true,
            ttsLanguageReady = true,
            onVoiceEnabledChange = {},
            onTrayOutbound = {},
            onRemainingChange = { _, _, _ -> }
)
    }
}
