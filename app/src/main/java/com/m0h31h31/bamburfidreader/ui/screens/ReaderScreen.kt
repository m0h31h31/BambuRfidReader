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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
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
import com.m0h31h31.bamburfidreader.CrealityMaterial
import com.m0h31h31.bamburfidreader.CrealityTagData
import com.m0h31h31.bamburfidreader.NfcUiState
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.ReaderBrand
import com.m0h31h31.bamburfidreader.SnapmakerTagData
import com.m0h31h31.bamburfidreader.LogCollector
import com.m0h31h31.bamburfidreader.logDebug
import com.m0h31h31.bamburfidreader.openTtsSettings
import com.m0h31h31.bamburfidreader.ui.components.ColorSwatch
import com.m0h31h31.bamburfidreader.ui.components.InfoLine
import com.m0h31h31.bamburfidreader.ui.components.AppSlider
import com.m0h31h31.bamburfidreader.ui.components.AppSwitch
import com.m0h31h31.bamburfidreader.ui.components.AppCircularProgressIndicator
import com.m0h31h31.bamburfidreader.ui.components.NeuButton
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import com.m0h31h31.bamburfidreader.ui.components.neuBackground
import com.m0h31h31.bamburfidreader.ui.theme.AppUiStyle
import com.m0h31h31.bamburfidreader.ui.theme.BambuRfidReaderTheme
import com.m0h31h31.bamburfidreader.ui.theme.LocalAppUiStyle
import com.m0h31h31.bamburfidreader.util.parseColorValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.selection.SelectionContainer

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

private const val MERIT_PREFS = "merit_prefs"
private const val MERIT_KEY_COUNT = "merit_count"
private const val MERIT_KEY_HMAC = "merit_hmac"
private val MERIT_HMAC_SECRET = byteArrayOf(
    0x4d, 0x65, 0x72, 0x69, 0x74, 0x5f, 0x42, 0x61,
    0x6d, 0x62, 0x75, 0x5f, 0x52, 0x46, 0x49, 0x44
)

private fun computeMeritHmac(count: Long): String {
    return try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(MERIT_HMAC_SECRET, "HmacSHA256"))
        mac.doFinal(count.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }
}

private fun loadMeritCount(context: android.content.Context): Long {
    val prefs = context.getSharedPreferences(MERIT_PREFS, android.content.Context.MODE_PRIVATE)
    val count = prefs.getLong(MERIT_KEY_COUNT, 0L)
    val storedHmac = prefs.getString(MERIT_KEY_HMAC, "").orEmpty()
    return if (storedHmac == computeMeritHmac(count)) count else 0L
}

private fun saveMeritCount(context: android.content.Context, count: Long) {
    context.getSharedPreferences(MERIT_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putLong(MERIT_KEY_COUNT, count)
        .putString(MERIT_KEY_HMAC, computeMeritHmac(count))
        .apply()
}

@Composable
fun ReaderScreen(
    state: NfcUiState,
    voiceEnabled: Boolean,
    ttsReady: Boolean,
    ttsLanguageReady: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onTrayOutbound: (String) -> Unit,
    showRecoveryAction: Boolean,
    onAttemptRecovery: () -> Unit,
    onRemainingChange: (String, Float, Int?) -> Unit,
    onNotesChange: (String, String, String) -> Unit = { _, _, _ -> },
    readerBrand: ReaderBrand = ReaderBrand.BAMBU,
    onBrandChange: (ReaderBrand) -> Unit = {},
    readerCrealityTagData: CrealityTagData? = null,
    readerCrealityMaterial: CrealityMaterial? = null,
    readerSnapmakerTagData: SnapmakerTagData? = null,
    readerBrandStatus: String = "",
    onReportAnomaly: ((cardUid: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalAppUiStyle.current
    val meritToastPalette = if (uiStyle == AppUiStyle.MIUIX) {
        listOf(
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
        )
    } else {
        listOf(
            Color(0xFFE8F5E9) to Color(0xFF2E7D32),
            Color(0xFFE3F2FD) to Color(0xFF1565C0),
            Color(0xFFFFF3E0) to Color(0xFFEF6C00),
            Color(0xFFFCE4EC) to Color(0xFFC2185B),
            Color(0xFFEDE7F6) to Color(0xFF5E35B1),
            Color(0xFFE0F2F1) to Color(0xFF00695C)
        )
    }
    val context = LocalContext.current
    var logoTapCount by remember { mutableStateOf(0) }
    var logoLastTapAt by remember { mutableStateOf(0L) }
    var meritToastVisible by remember { mutableStateOf(false) }
    var meritToastNonce by remember { mutableStateOf(0) }
    var meritTotal by remember { mutableStateOf(loadMeritCount(context)) }
    var meritToastPaletteIndex by remember { mutableStateOf(0) }
    var showOutboundConfirm by remember(state.trayUidHex) { mutableStateOf(false) }
    var showAnomalyConfirm by remember { mutableStateOf(false) }
    var anomalyReportResult by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var editOriginalMaterial by remember { mutableStateOf(state.originalMaterial) }
    var editNotes by remember { mutableStateOf(state.notes) }
    val notesDebounceJob = remember { mutableStateOf<Job?>(null) }
    // 扫卡或 UID 切换时，从 state 同步最新的原始耗材与备注（DB 中存储的值）
    LaunchedEffect(state.trayUidHex, state.originalMaterial, state.notes) {
        editOriginalMaterial = state.originalMaterial
        editNotes = state.notes
    }
    val baseLogoTint = state.displayColors.firstNotNullOfOrNull { parseColorValue(it) }
        ?: parseColorValue(state.displayColorCode)
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    val logoTintColor = if (baseLogoTint.alpha < 0.45f) {
        baseLogoTint.copy(alpha = 0.75f)
    } else {
        baseLogoTint
    }
    val animatedLogoTintColor by animateColorAsState(
        targetValue = logoTintColor,
        animationSpec = tween(durationMillis = 550),
        label = "reader_logo_tint"
    )
    val meritToastShape = RoundedCornerShape(14.dp)
    val meritToastColors = meritToastPalette[meritToastPaletteIndex]
    val meritToastBackgroundColor by animateColorAsState(
        targetValue = meritToastColors.first.copy(alpha = 0.97f),
        animationSpec = tween(durationMillis = 280),
        label = "merit_toast_background"
    )
    val meritToastTextColor by animateColorAsState(
        targetValue = meritToastColors.second,
        animationSpec = tween(durationMillis = 280),
        label = "merit_toast_text"
    )
    val meritToastBorderColor by animateColorAsState(
        targetValue = meritToastColors.second.copy(alpha = 0.24f),
        animationSpec = tween(durationMillis = 280),
        label = "merit_toast_border"
    )
    LaunchedEffect(meritToastNonce) {
        if (meritToastNonce == 0) return@LaunchedEffect
        meritToastVisible = true
        delay(720)
        meritToastVisible = false
    }
    Surface(
        modifier = modifier.fillMaxSize().neuBackground(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .padding(bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 状态文本：Bambu 用 state.status，其他品牌用 readerBrandStatus（始终显示）
                val displayStatus = if (readerBrand == ReaderBrand.BAMBU) state.status else readerBrandStatus
                if (readerBrand != ReaderBrand.BAMBU || displayStatus.isNotBlank()) {
                    NeuPanel(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val statusIsWaiting = displayStatus.contains("正在") ||
                                displayStatus.contains("请稍候") ||
                                displayStatus.contains("准备就绪") ||
                                displayStatus.contains("请将目标")
                            if (statusIsWaiting) {
                                AppCircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = displayStatus,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            val voiceHint = when {
                                voiceEnabled && !ttsReady -> stringResource(R.string.voice_status_engine_not_ready)
                                voiceEnabled && !ttsLanguageReady -> stringResource(R.string.voice_status_language_unavailable)
                                voiceEnabled -> stringResource(R.string.voice_status_on)
                                else -> stringResource(R.string.voice_status_off)
                            }
                            val canOpenTtsSettings = voiceEnabled && (!ttsReady || !ttsLanguageReady)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppSwitch(
                                    checked = voiceEnabled,
                                    onCheckedChange = onVoiceEnabledChange,
                                    modifier = Modifier.scale(0.8f),
                                )
                                Text(
                                    text = if (canOpenTtsSettings) stringResource(R.string.action_voice_settings)
                                           else stringResource(R.string.voice_status_prefix, voiceHint),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (canOpenTtsSettings) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        textDecoration = if (canOpenTtsSettings) TextDecoration.Underline else null
                                    ),
                                    modifier = if (canOpenTtsSettings) {
                                        Modifier.padding(start = 6.dp).clickable {
                                            val opened = openTtsSettings(context)
                                            if (!opened) logDebug("无法打开语音设置")
                                        }
                                    } else {
                                        Modifier.padding(start = 6.dp)
                                    }
                                )
                            }
                        }
                    }
                }

                // ── 主卡片面板（全品牌显示）──────────────────────────────────────────
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
                    mutableStateOf(if (hasWeight && derivedGrams > 0) derivedGrams.toString() else "")
                }
                val gramsInt = gramsValue.roundToInt().coerceIn(0, totalWeight.coerceAtLeast(0))
                val percentValue = if (hasWeight) {
                    ((gramsInt * 100f / totalWeight) * 10).roundToInt() / 10f
                } else {
                    state.remainingPercent
                }
                
                // 添加防抖机制
                val scope = rememberCoroutineScope()
                val debounceJob = remember {
                    mutableStateOf<Job?>(null)
                }
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ── 左侧色块 ──────────────────────────────────────────────
                            Box(modifier = Modifier.size(120.dp)) {
                                when (readerBrand) {
                                    ReaderBrand.BAMBU -> {
                                        ColorSwatch(
                                            colorValues = state.displayColors,
                                            colorType = state.displayColorType,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable {
                                                    val now = System.currentTimeMillis()
                                                    if (now - logoLastTapAt > 1500) logoTapCount = 0
                                                    logoLastTapAt = now
                                                    logoTapCount += 1
                                                    if (logoTapCount >= 5) {
                                                        logoTapCount = 0
                                                        val result = LogCollector.packageLogs(context)
                                                        logDebug(result)
                                                        Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                        )
                                        if (trayUidAvailable) {
                                            val outboundContainerColor = if (uiStyle == AppUiStyle.MIUIX) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer
                                            val outboundContentColor  = if (uiStyle == AppUiStyle.MIUIX) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(6.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(outboundContainerColor)
                                                    .border(1.dp, outboundContentColor.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                                    .clickable { showOutboundConfirm = true },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.reader_outbound),
                                                    color = outboundContentColor,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }
                                    ReaderBrand.CREALITY -> {
                                        val hex = readerCrealityTagData?.colorHex ?: ""
                                        ColorSwatch(
                                            colorValues = if (hex.isNotBlank()) listOf(hex) else emptyList(),
                                            colorType = "",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    ReaderBrand.SNAPMAKER -> {
                                        val hex = readerSnapmakerTagData?.let { "%06X".format(it.rgb1) } ?: ""
                                        ColorSwatch(
                                            colorValues = if (hex.isNotBlank()) listOf(hex) else emptyList(),
                                            colorType = "",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }

                            // ── 右侧文字信息 ──────────────────────────────────────────
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                when (readerBrand) {
                                    ReaderBrand.BAMBU -> {
                                        Text(
                                            text = state.displayType.ifBlank { stringResource(R.string.label_unknown) },
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
                                                text = state.displayColorName.ifBlank { stringResource(R.string.label_unknown) },
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = modifier.padding(3.dp)
                                            )
                                            Text(text = "-", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(3.dp))
                                            Text(
                                                text = state.displayColorCode.ifBlank { stringResource(R.string.label_unknown) },
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = modifier.padding(3.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                QuantityButtonGroup(
                                                    value = gramsText,
                                                    enabled = trayUidAvailable && hasWeight,
                                                    onValueChange = { text ->
                                                        val digits = text.filter { it.isDigit() }
                                                        if (trayUidAvailable && hasWeight) {
                                                            val next = digits.toIntOrNull()?.coerceIn(0, totalWeight) ?: 0
                                                            gramsValue = next.toFloat()
                                                            gramsText = if (digits.isEmpty() || next == 0) "" else next.toString()
                                                            debounceJob.value?.cancel()
                                                            debounceJob.value = scope.launch {
                                                                delay(500)
                                                                val finalGrams = gramsText.toIntOrNull()?.coerceIn(0, totalWeight) ?: 0
                                                                val finalPercent = if (totalWeight > 0) ((finalGrams * 100f / totalWeight) * 10).roundToInt() / 10f else 0f
                                                                onRemainingChange(state.trayUidHex, finalPercent, finalGrams)
                                                            }
                                                        } else {
                                                            gramsText = digits
                                                        }
                                                    },
                                                    onDecrease = {
                                                        val next = (gramsInt - 1).coerceAtLeast(0)
                                                        gramsValue = next.toFloat(); gramsText = next.toString()
                                                        onRemainingChange(state.trayUidHex, (next * 100f / totalWeight), next)
                                                    },
                                                    onIncrease = {
                                                        val next = (gramsInt + 1).coerceAtMost(totalWeight)
                                                        gramsValue = next.toFloat(); gramsText = next.toString()
                                                        onRemainingChange(state.trayUidHex, (next * 100f / totalWeight), next)
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                    ReaderBrand.CREALITY -> {
                                        val mat = readerCrealityMaterial
                                        Text(
                                            text = mat?.brand?.ifBlank { "-" } ?: "-",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = modifier.padding(3.dp),
                                            fontSize = 18.sp,
                                        )
                                        Text(
                                            text = mat?.materialType?.ifBlank { "-" } ?: "-",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = modifier.padding(3.dp)
                                        )
                                        Text(
                                            text = mat?.name?.ifBlank { "-" } ?: "-",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = modifier.padding(3.dp)
                                        )
                                    }
                                    ReaderBrand.SNAPMAKER -> {
                                        val d = readerSnapmakerTagData
                                        Text(
                                            text = d?.vendor?.ifBlank { "-" } ?: "-",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = modifier.padding(3.dp),
                                            fontSize = 18.sp,
                                        )
                                        Text(
                                            text = if (d != null) "${d.mainType} ${d.subType}".trim().ifBlank { "-" } else "-",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = modifier.padding(3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // ── 余量滑块：仅拓竹品牌显示 ─────────────────────────────────
                        if (readerBrand == ReaderBrand.BAMBU) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f).padding(vertical = 3.dp)) {
                                    AppSlider(
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
                                        modifier = Modifier.fillMaxWidth().height(30.dp),
                                        onValueChangeFinished = {
                                            if (trayUidAvailable && hasWeight) {
                                                val finalGrams = gramsValue.roundToInt().coerceIn(0, totalWeight)
                                                val finalPercent = if (totalWeight > 0) ((finalGrams * 100f / totalWeight) * 10).roundToInt() / 10f else 0f
                                                onRemainingChange(state.trayUidHex, finalPercent, finalGrams)
                                            }
                                        }
                                    )
                                }
                                Text(
                                    text = String.format("%.1f%%", percentValue),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp).width(56.dp),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }
                if (showRecoveryAction) {
                    NeuButton(
                        text = stringResource(R.string.reader_recovery),
                        onClick = onAttemptRecovery,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (showOutboundConfirm) {
                    AlertDialog(
                        onDismissRequest = { showOutboundConfirm = false },
                        title = { Text(stringResource(R.string.reader_outbound_confirm_title)) },
                        text = { Text(stringResource(R.string.reader_outbound_confirm_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onTrayOutbound(state.trayUidHex)
                                    showOutboundConfirm = false
                                }
                            ) {
                                Text(stringResource(R.string.action_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showOutboundConfirm = false }
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }
                if (showAnomalyConfirm && onReportAnomaly != null) {
                    val reportSuccessText = stringResource(R.string.anomaly_report_success)
                    val reportFailText = stringResource(R.string.anomaly_report_fail)
                    AlertDialog(
                        onDismissRequest = { showAnomalyConfirm = false },
                        title = { Text(stringResource(R.string.anomaly_dialog_title)) },
                        text = { Text(stringResource(R.string.anomaly_dialog_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val cardUid = state.uidHex
                                    showAnomalyConfirm = false
                                    coroutineScope.launch {
                                        onReportAnomaly(cardUid)
                                        anomalyReportResult = reportSuccessText
                                        kotlinx.coroutines.delay(3000)
                                        anomalyReportResult = ""
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.anomaly_dialog_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAnomalyConfirm = false }) {
                                Text(stringResource(R.string.anomaly_dialog_cancel))
                            }
                        }
                    )
                }
                if (anomalyReportResult.isNotBlank()) {
                    Text(
                        text = anomalyReportResult,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

//                if (state.blockHexes.any { it.isNotBlank() }) {
//                    Card(modifier = Modifier.fillMaxWidth()) {
//                        Column(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .padding(12.dp),
//                            verticalArrangement = Arrangement.spacedBy(4.dp)
//                        ) {
//                            Text(
//                                text = "原始区块（含Trailer）",
//                                style = MaterialTheme.typography.titleSmall
//                            )
//                            state.blockHexes.forEachIndexed { index, hex ->
//                                if (hex.isNotBlank()) {
//                                    val isTrailer = index % 4 == 3
//                                    InfoLine(
//                                        label = if (isTrailer) "Block$index (Trailer)" else "Block$index",
//                                        value = hex,
//                                        style = MaterialTheme.typography.bodySmall
//                                    )
//                                }
//                            }
//                        }
//                    }
//                }

                // ── 品牌切换按钮行 ─────────────────────────────────────────────────────
                NeuPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val brands = listOf(
                            ReaderBrand.BAMBU    to "拓竹",
                            ReaderBrand.CREALITY to "创想",
                            ReaderBrand.SNAPMAKER to "快造"
                        )
                        brands.forEach { (brand, label) ->
                            val selected = readerBrand == brand
                            val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                          else MaterialTheme.colorScheme.surfaceVariant
                            val textColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bgColor)
                                    .clickable { onBrandChange(brand) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = textColor,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // ── 信息面板：填满剩余高度，支持滚动 ──────────────────────────────────
                NeuPanel(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val infoScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(infoScrollState)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                when (readerBrand) {
                                    ReaderBrand.BAMBU -> {
                                        SelectionContainer {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = stringResource(R.string.label_other_info),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        InfoLine(
                                            label = stringResource(R.string.reader_card_uid),
                                            value = state.uidHex.ifBlank { "-" },
                                            style = MaterialTheme.typography.bodySmall,
                                            inline = true
                                        )
                                        state.secondaryFields.forEach { field ->
                                            InfoLine(
                                                label = field.label,
                                                value = field.value,
                                                style = MaterialTheme.typography.bodySmall,
                                                inline = true
                                            )
                                        }
                                        } // end SelectionContainer Column
                                        } // end SelectionContainer
                                        if (trayUidAvailable) {
                                            CompactField(
                                                value = editOriginalMaterial,
                                                onValueChange = { newVal ->
                                                    editOriginalMaterial = newVal
                                                    notesDebounceJob.value?.cancel()
                                                    notesDebounceJob.value = scope.launch {
                                                        delay(500)
                                                        onNotesChange(state.trayUidHex, editOriginalMaterial, editNotes)
                                                    }
                                                },
                                                label = stringResource(R.string.reader_original_material),
                                                modifier = Modifier.fillMaxWidth(0.7f)
                                            )
                                            CompactField(
                                                value = editNotes,
                                                onValueChange = { newVal ->
                                                    editNotes = newVal
                                                    notesDebounceJob.value?.cancel()
                                                    notesDebounceJob.value = scope.launch {
                                                        delay(500)
                                                        onNotesChange(state.trayUidHex, editOriginalMaterial, editNotes)
                                                    }
                                                },
                                                label = stringResource(R.string.reader_notes),
                                                modifier = Modifier.fillMaxWidth(0.7f)
                                            )
                                        }
                                    }
                                    ReaderBrand.CREALITY -> {
                                        SelectionContainer {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = stringResource(R.string.label_other_info),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        if (readerCrealityTagData != null) {
                                            val d = readerCrealityTagData
                                            if (d.uidHex.isNotBlank())
                                                InfoLine(label = "卡片UID",  value = d.uidHex, style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "耗材ID",   value = d.materialId, style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "颜色",     value = "#${d.colorHex}", style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "重量",     value = d.weight, style = MaterialTheme.typography.bodySmall, inline = true)
                                            if (d.serial.isNotBlank())
                                                InfoLine(label = "序列号", value = d.serial, style = MaterialTheme.typography.bodySmall, inline = true)
                                            if (d.vendorId.isNotBlank())
                                                InfoLine(label = "厂商ID", value = d.vendorId, style = MaterialTheme.typography.bodySmall, inline = true)
                                            if (d.mfDate.isNotBlank())
                                                InfoLine(label = "生产日期", value = d.mfDate, style = MaterialTheme.typography.bodySmall, inline = true)
                                            if (d.batch.isNotBlank())
                                                InfoLine(label = "批次",   value = d.batch, style = MaterialTheme.typography.bodySmall, inline = true)
                                            readerCrealityMaterial?.let { mat ->
                                                if (mat.minTemp > 0 || mat.maxTemp > 0)
                                                    InfoLine(label = "打印温度", value = "${mat.minTemp}–${mat.maxTemp} °C", style = MaterialTheme.typography.bodySmall, inline = true)
                                                if (mat.diameter.isNotBlank())
                                                    InfoLine(label = "线径", value = "${mat.diameter} mm", style = MaterialTheme.typography.bodySmall, inline = true)
                                            }
                                        } else {
                                            Text(
                                                text = "请将创想三维耗材标签靠近读取",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        } // end Column
                                        } // end SelectionContainer
                                    }
                                    ReaderBrand.SNAPMAKER -> {
                                        SelectionContainer {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "快造 耗材信息",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        if (readerSnapmakerTagData != null) {
                                            val d = readerSnapmakerTagData
                                            InfoLine(label = "品牌",     value = d.vendor, style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "类型",     value = "${d.mainType} ${d.subType}".trim(), style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "颜色数",   value = "${d.colorCount}", style = MaterialTheme.typography.bodySmall, inline = true)
                                            val colorHexStr = buildString {
                                                val colors = listOf(d.rgb1, d.rgb2, d.rgb3, d.rgb4, d.rgb5)
                                                    .take(d.colorCount.coerceIn(1, 5))
                                                append(colors.joinToString(" ") { "#%06X".format(it) })
                                            }
                                            InfoLine(label = "颜色",     value = colorHexStr, style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "线径",     value = if (d.diameter > 0) "${"%.2f".format(d.diameter / 100.0)} mm" else "-", style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "重量",     value = if (d.weight > 0) "${d.weight} g" else "-", style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "打印温度", value = if (d.hotendMinTemp > 0) "${d.hotendMinTemp}–${d.hotendMaxTemp} °C" else "-", style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "热床温度", value = if (d.bedTemp > 0) "${d.bedTemp} °C" else "-", style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "烘料",     value = if (d.dryingTemp > 0) "${d.dryingTemp} °C / ${d.dryingTime} h" else "-", style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "生产日期", value = d.mfDate, style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "官方认证", value = if (d.isOfficial) "✓" else "✗", style = MaterialTheme.typography.bodySmall, inline = true)
                                            InfoLine(label = "卡片UID",  value = d.uidHex, style = MaterialTheme.typography.bodySmall, inline = true)
                                        } else {
                                            Text(
                                                text = "请将快造耗材标签靠近读取",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        } // end Column
                                        } // end SelectionContainer
                                    }
                                }
                            }
                            // 右侧 Logo / 品牌占位
                            Box(
                                modifier = Modifier.size(88.dp, 250.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when (readerBrand) {
                                    ReaderBrand.BAMBU -> {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                        if (onReportAnomaly != null && state.uidHex.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .width(74.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFFD32F2F))
                                                    .clickable { showAnomalyConfirm = true }
                                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "异常\n上报",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }
                                        androidx.compose.foundation.Image(
                                            painter = painterResource(id = R.drawable.logo_mark),
                                            contentDescription = stringResource(R.string.content_logo),
                                            colorFilter = ColorFilter.tint(animatedLogoTintColor),
                                            modifier = Modifier
                                                .size(80.dp, 200.dp)
                                                .clickable(
                                                    indication = null,
                                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                                ) {
                                                    meritToastVisible = false
                                                    meritTotal += 1
                                                    saveMeritCount(context, meritTotal)
                                                    meritToastPaletteIndex = Random.nextInt(meritToastPalette.size)
                                                    meritToastNonce += 1
                                                }
                                        )
                                        }
                                    }
                                    ReaderBrand.CREALITY -> {
                                        val crealityHex = readerCrealityTagData?.colorHex ?: ""
                                        val crealityBase = parseColorValue(crealityHex)
                                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                                        val crealityTint = if (crealityBase.alpha < 0.45f) crealityBase.copy(alpha = 0.75f) else crealityBase
                                        val animatedCrealityTint by animateColorAsState(
                                            targetValue = crealityTint,
                                            animationSpec = tween(durationMillis = 550),
                                            label = "creality_logo_tint"
                                        )
                                        androidx.compose.foundation.Image(
                                            painter = painterResource(id = R.drawable.creality_logo_mask),
                                            contentDescription = "Creality",
                                            colorFilter = ColorFilter.tint(animatedCrealityTint),
                                            modifier = Modifier.size(80.dp, 250.dp)
                                        )
                                    }
                                    ReaderBrand.SNAPMAKER -> {
                                        val snapHex = readerSnapmakerTagData?.let { "%06X".format(it.rgb1) } ?: ""
                                        val snapBase = parseColorValue(snapHex)
                                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                                        val snapTint = if (snapBase.alpha < 0.45f) snapBase.copy(alpha = 0.75f) else snapBase
                                        val animatedSnapTint by animateColorAsState(
                                            targetValue = snapTint,
                                            animationSpec = tween(durationMillis = 550),
                                            label = "snapmaker_logo_tint"
                                        )
                                        androidx.compose.foundation.Image(
                                            painter = painterResource(id = R.drawable.snapmaker_logo_mask),
                                            contentDescription = "Snapmaker",
                                            colorFilter = ColorFilter.tint(animatedSnapTint),
                                            modifier = Modifier.size(80.dp, 250.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
//            BoostFooter(
//                modifier = Modifier
//                    .align(Alignment.BottomCenter)
//                    .padding(horizontal = 2.dp, vertical = 0.dp)
//            )
            androidx.compose.animation.AnimatedVisibility(
                visible = meritToastVisible,
                enter = fadeIn(
                    animationSpec = tween(180)
                ) + scaleIn(
                    initialScale = 0.86f,
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = FastOutSlowInEasing
                    )
                ) + slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = tween(260)
                ),
                exit = fadeOut(
                    animationSpec = tween(150)
                ) + scaleOut(
                    targetScale = 0.92f,
                    animationSpec = tween(150)
                ) + slideOutVertically(
                    targetOffsetY = { -it / 3 },
                    animationSpec = tween(150)
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 132.dp, end = 18.dp)
                    .zIndex(10f)
            ) {
                Box(
                    modifier = Modifier
                        .clip(meritToastShape)
                        .background(meritToastBackgroundColor)
                        .border(
                            width = 1.dp,
                            color = meritToastBorderColor,
                            shape = meritToastShape
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.reader_merit_format,
                            meritTotal.coerceAtLeast(1)
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = meritToastTextColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
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
private fun BoostFooter(boostLink: String, modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    if (boostLink.isBlank()) return
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
            showRecoveryAction = true,
            onAttemptRecovery = {},
            onRemainingChange = { _, _, _ -> }
)
    }
}
