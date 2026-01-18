package com.m0h31h31.bamburfidreader

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.ui.theme.BambuRfidReaderTheme
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

private const val KEY_LENGTH_BYTES = 6
private const val SECTOR_COUNT = 16
private const val LOG_TAG = "BambuRfidReader"
private const val FILAMENT_JSON_NAME = "filaments_color_codes.json"
private const val FILAMENT_DB_NAME = "filaments.db"
private const val FILAMENT_DB_VERSION = 3
private const val FILAMENT_TABLE = "filaments"
private const val FILAMENT_META_TABLE = "meta"
private const val FILAMENT_META_KEY_LAST_MODIFIED = "filaments_last_modified"
private val HKDF_SALT = byteArrayOf(
    0x9a.toByte(),
    0x75.toByte(),
    0x9c.toByte(),
    0xf2.toByte(),
    0xc4.toByte(),
    0xf7.toByte(),
    0xca.toByte(),
    0xff.toByte(),
    0x22.toByte(),
    0x2c.toByte(),
    0xb9.toByte(),
    0x76.toByte(),
    0x9b.toByte(),
    0x41.toByte(),
    0xbc.toByte(),
    0x96.toByte()
)
private val INFO_A = "RFID-A\u0000".toByteArray(Charsets.US_ASCII)
private val INFO_B = "RFID-B\u0000".toByteArray(Charsets.US_ASCII)

data class NfcUiState(
    val status: String,
    val uidHex: String = "",
    val keyA0Hex: String = "",
    val keyB0Hex: String = "",
    val keyA1Hex: String = "",
    val keyB1Hex: String = "",
    val blockHexes: List<String> = List(8) { "" },
    val parsedFields: List<ParsedField> = emptyList(),
    val displayType: String = "",
    val displayColorName: String = "",
    val displayColorCode: String = "",
    val displayColorType: String = "",
    val displayColors: List<String> = emptyList(),
    val secondaryFields: List<ParsedField> = emptyList(),
    val error: String = ""
)

data class ParsedField(
    val label: String,
    val value: String
)

data class DisplayData(
    val type: String,
    val colorName: String,
    val colorCode: String,
    val colorType: String,
    val colorValues: List<String>,
    val secondaryFields: List<ParsedField>
)

data class FilamentColorEntry(
    val colorCode: String,
    val filaId: String,
    val colorType: String,
    val filaType: String,
    val colorNameZh: String,
    val colorValues: List<String>,
    val colorCount: Int
)

data class ParsedBlockData(
    val fields: List<ParsedField>,
    val materialId: String,
    val colorValues: List<String>
)

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var uiState by mutableStateOf(NfcUiState(status = "正在初始化 NFC..."))
    private var filamentDbHelper: FilamentDbHelper? = null

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        val result = readTag(tag)
        runOnUiThread { uiState = result }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        filamentDbHelper = FilamentDbHelper(this)
        filamentDbHelper?.let { syncFilamentDatabase(this, it) }
        uiState = NfcUiState(status = initialStatus())
        setContent {
            BambuRfidReaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NfcScreen(
                        state = uiState,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        if (adapter == null) {
            uiState = uiState.copy(status = "此设备不支持 NFC")
            return
        }
        if (!adapter.isEnabled) {
            uiState = uiState.copy(status = "NFC 已关闭，请开启后贴卡")
            return
        }
        adapter.enableReaderMode(
            this,
            readerCallback,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        uiState = uiState.copy(status = "请贴近 RFID 标签")
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        filamentDbHelper?.close()
    }

    private fun initialStatus(): String {
        val adapter = nfcAdapter
        return when {
            adapter == null -> "此设备不支持 NFC"
            !adapter.isEnabled -> "NFC 已关闭，请开启后贴卡"
            else -> "请贴近 RFID 标签"
        }
    }

    private fun readTag(tag: Tag): NfcUiState {
        val uid = tag.id ?: return NfcUiState(status = "未读取到 UID")
        val uidHex = uid.toHex()
        Log.d(LOG_TAG, "UID: $uidHex")

        val keysA = deriveKeys(uid, INFO_A)
        val keysB = deriveKeys(uid, INFO_B)
        val keyA0 = keysA.getOrNull(0)
        val keyB0 = keysB.getOrNull(0)
        val keyA1 = keysA.getOrNull(1)
        val keyB1 = keysB.getOrNull(1)
        val keyA3 = keysA.getOrNull(3)
        val keyB3 = keysB.getOrNull(3)
        val keyA4 = keysA.getOrNull(4)
        val keyB4 = keysB.getOrNull(4)

        val keyA0Hex = keyA0?.toHex().orEmpty()
        val keyB0Hex = keyB0?.toHex().orEmpty()
        val keyA1Hex = keyA1?.toHex().orEmpty()
        val keyB1Hex = keyB1?.toHex().orEmpty()
        val keyA4Hex = keyA4?.toHex().orEmpty()
        val keyB4Hex = keyB4?.toHex().orEmpty()
        Log.d(
            LOG_TAG,
            "密钥A0: $keyA0Hex, 密钥B0: $keyB0Hex, 密钥A1: $keyA1Hex, 密钥B1: $keyB1Hex, 密钥A3: ${
                keyA3?.toHex().orEmpty()
            }, 密钥B3: ${keyB3?.toHex().orEmpty()}, 密钥A4: $keyA4Hex, 密钥B4: $keyB4Hex"
        )

        val mifare = MifareClassic.get(tag)
            ?: return NfcUiState(
                status = "不支持的标签",
                uidHex = uidHex,
                keyA0Hex = keyA0Hex,
                keyB0Hex = keyB0Hex,
                keyA1Hex = keyA1Hex,
                keyB1Hex = keyB1Hex,
                error = "该标签不是 MIFARE Classic"
            )

        return try {
            mifare.connect()
            val blockData = MutableList<ByteArray?>(8) { null }
            var block12: ByteArray? = null
            var block16: ByteArray? = null
            val errors = ArrayList<String>(2)

            val sector0 = readSector(mifare, 0, keyA0, keyB0)
            if (sector0.blocks.isNotEmpty()) {
                Log.d(LOG_TAG, "扇区0认证成功，读取到 ${sector0.blocks.size} 个区块")
                sector0.blocks.forEachIndexed { index, data ->
                    blockData[index] = data
                }
            } else if (sector0.error.isNotBlank()) {
                Log.d(LOG_TAG, "扇区0认证失败：${sector0.error}")
                errors.add(sector0.error)
            }

            val sector1 = readSector(mifare, 1, keyA1, keyB1)
            if (sector1.blocks.isNotEmpty()) {
                Log.d(LOG_TAG, "扇区1认证成功，读取到 ${sector1.blocks.size} 个区块")
                sector1.blocks.forEachIndexed { index, data ->
                    blockData[index + 4] = data
                }
            } else if (sector1.error.isNotBlank()) {
                Log.d(LOG_TAG, "扇区1认证失败：${sector1.error}")
                errors.add(sector1.error)
            }

            val sector3 = readSector(mifare, 3, keyA3, keyB3)
            if (sector3.blocks.isNotEmpty()) {
                block12 = sector3.blocks.getOrNull(0)
                if (block12 != null) {
                    Log.d(LOG_TAG, "扇区3认证成功，区块12: ${block12?.toHex().orEmpty()}")
                } else {
                    Log.d(LOG_TAG, "扇区3认证成功，但未读取到区块12")
                }
            } else if (sector3.error.isNotBlank()) {
                Log.d(LOG_TAG, "扇区3认证失败：${sector3.error}")
            }

            val sector4 = readSector(mifare, 4, keyA4, keyB4)
            if (sector4.blocks.isNotEmpty()) {
                block16 = sector4.blocks.getOrNull(0)
                if (block16 != null) {
                    Log.d(LOG_TAG, "扇区4认证成功，区块16: ${block16?.toHex().orEmpty()}")
                } else {
                    Log.d(LOG_TAG, "扇区4认证成功，但未读取到区块16")
                }
            } else if (sector4.error.isNotBlank()) {
                Log.d(LOG_TAG, "扇区4认证失败：${sector4.error}")
            }

            val blockHexes = blockData.map { data -> data?.toHex().orEmpty() }
            blockHexes.forEachIndexed { index, value ->
                if (value.isNotBlank()) {
                    Log.d(LOG_TAG, "区块 $index: $value")
                }
            }
            val parsedBlockData = parseBlocks(blockData, block12, block16)
            val displayData = buildDisplayData(parsedBlockData, filamentDbHelper)
            parsedBlockData.fields.forEach { field ->
                Log.d(LOG_TAG, "解析字段: ${field.label}=${field.value}")
            }
            if (parsedBlockData.colorValues.isNotEmpty()) {
                Log.d(
                    LOG_TAG,
                    "读取到颜色列表: ${parsedBlockData.colorValues.joinToString(separator = ",")}"
                )
            }
            Log.d(
                LOG_TAG,
                "展示数据: 类型=${displayData.type}, 颜色名=${displayData.colorName}, 颜色代码=${displayData.colorCode}, 颜色类型=${displayData.colorType}, 颜色列表=${
                    displayData.colorValues.joinToString(
                        separator = ","
                    )
                }"
            )
            val status = when {
                errors.isEmpty() -> "读取成功"
                blockHexes.any { it.isNotBlank() } -> "部分读取成功"
                else -> "认证失败"
            }
            if (errors.isNotEmpty()) {
                Log.d(LOG_TAG, "读取错误: ${errors.joinToString(separator = "; ")}")
            }

            NfcUiState(
                status = status,
                uidHex = uidHex,
                keyA0Hex = keyA0Hex,
                keyB0Hex = keyB0Hex,
                keyA1Hex = keyA1Hex,
                keyB1Hex = keyB1Hex,
                blockHexes = blockHexes,
                parsedFields = parsedBlockData.fields,
                displayType = displayData.type,
                displayColorName = displayData.colorName,
                displayColorCode = displayData.colorCode,
                displayColorType = displayData.colorType,
                displayColors = displayData.colorValues,
                secondaryFields = displayData.secondaryFields,
                error = errors.joinToString(separator = "; ")
            )
        } catch (e: Exception) {
            Log.d(LOG_TAG, "读取失败: ${e.message}")
            NfcUiState(
                status = "读取失败",
                uidHex = uidHex,
                keyA0Hex = keyA0Hex,
                keyB0Hex = keyB0Hex,
                keyA1Hex = keyA1Hex,
                keyB1Hex = keyB1Hex,
                error = e.message ?: "读取失败"
            )
        } finally {
            try {
                mifare.close()
            } catch (_: IOException) {
                // Ignore close errors.
            }
        }
    }
}

@Composable
private fun BoostFooter(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val boostLink =
        "bambulab://bbl/design/model/detail?design_id=2019552&instance_id=2251734&appSharePlatform=copy"
    TextButton(
        onClick = { uriHandler.openUri(boostLink) },
        modifier = modifier
    ) {
        Text(text = "助力：打开 Bambu APP")
    }
}

@Composable
private fun NfcScreen(state: NfcUiState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .padding(bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "Bambu RFID Reader", style = MaterialTheme.typography.titleLarge)
                if (state.status.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

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
                                text = "耗材类型",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = state.displayType.ifBlank { "未知" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "中文颜色名",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = state.displayColorName.ifBlank { "未知" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "颜色代码",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = state.displayColorCode.ifBlank { "未知" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        ColorSwatch(
                            colorValues = state.displayColors,
                            colorType = state.displayColorType,
                            modifier = Modifier.size(120.dp)
                        )
                    }
                }

                if (state.secondaryFields.isNotEmpty()) {
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
                                Text(text = "其他信息", style = MaterialTheme.typography.titleSmall)
                                state.secondaryFields.forEach { field ->
                                    InfoLine(
                                        label = field.label,
                                        value = field.value,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Image(
                                painter = painterResource(id = R.drawable.logo_mark),
                                contentDescription = "Logo",
                                modifier = Modifier.size(80.dp, 200.dp)
                            )
                        }
                    }
                }

                // 调试显示信息已注释，避免展示 ID/十六进制数据。
                // InfoLine(label = "UID", value = state.uidHex)
                // InfoLine(label = "密钥A(扇区0)", value = state.keyA0Hex)
                // InfoLine(label = "密钥B(扇区0)", value = state.keyB0Hex)
                // InfoLine(label = "密钥A(扇区1)", value = state.keyA1Hex)
                // InfoLine(label = "密钥B(扇区1)", value = state.keyB1Hex)
                // Text(text = "原始区块", style = MaterialTheme.typography.titleMedium)
                // state.blockHexes.forEachIndexed { index, value ->
                //     InfoLine(label = "区块 $index", value = value)
                // }
                // InfoLine(label = "错误", value = state.error)
            }
            BoostFooter(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    if (value.isNotBlank()) {
        Text(text = "$label: $value", style = style, color = color)
    }
}

@Composable
private fun ColorSwatch(
    colorValues: List<String>,
    colorType: String,
    modifier: Modifier = Modifier
) {
    val parsedColors = colorValues.mapNotNull { parseColorValue(it) }
    val colors = if (parsedColors.isNotEmpty()) {
        parsedColors
    } else {
        listOf(MaterialTheme.colorScheme.surface)
    }
    val resolvedType = when {
        colorType.isNotBlank() -> colorType
        colors.size > 1 -> "多拼色"
        else -> "单色"
    }
    val shape = RoundedCornerShape(14.dp)

    when (resolvedType) {
        "渐变色" -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(Brush.horizontalGradient(colors))
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            )
        }

        "多拼色" -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    colors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(color)
                        )
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(colors.first())
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNfcScreen() {
    BambuRfidReaderTheme {
        NfcScreen(
            state = NfcUiState(
                status = "请贴近 MIFARE Classic 卡",
                displayType = "PLA Basic",
                displayColorName = "橙色",
                displayColorCode = "10300",
                displayColorType = "单色",
                displayColors = listOf("#FF6A13FF"),
                secondaryFields = listOf(
                    ParsedField(label = "线轴重量", value = "1000 克"),
                    ParsedField(label = "耗材直径", value = "1.75 毫米"),
                    ParsedField(label = "生产日期", value = "2024年06月30日 12时45分"),
                    ParsedField(label = "烘干温度", value = "45 ℃"),
                    ParsedField(label = "烘干时间", value = "8 小时"),
                    ParsedField(label = "热床温度", value = "60 ℃"),
                    ParsedField(label = "喷嘴最高温度", value = "220 ℃"),
                    ParsedField(label = "喷嘴最低温度", value = "190 ℃")
                )
            )
        )
    }
}

private data class SectorReadResult(
    val blocks: List<ByteArray>,
    val error: String
)

private fun readSector(
    mifare: MifareClassic,
    sectorIndex: Int,
    keyA: ByteArray?,
    keyB: ByteArray?
): SectorReadResult {
    val authenticated = (keyA != null && mifare.authenticateSectorWithKeyA(sectorIndex, keyA)) ||
            (keyB != null && mifare.authenticateSectorWithKeyB(sectorIndex, keyB))
    if (!authenticated) {
        return SectorReadResult(emptyList(), "扇区 $sectorIndex 认证失败")
    }

    val blockCount = mifare.getBlockCountInSector(sectorIndex)
    val startBlock = mifare.sectorToBlock(sectorIndex)
    val blocks = ArrayList<ByteArray>(blockCount)
    for (offset in 0 until blockCount) {
        blocks.add(mifare.readBlock(startBlock + offset))
    }
    return SectorReadResult(blocks, "")
}

private fun parseBlocks(
    blocks: List<ByteArray?>,
    block12: ByteArray?,
    block16: ByteArray?
): ParsedBlockData {
    val parsed = ArrayList<ParsedField>()
    var materialId = ""
    val colorValues = ArrayList<String>()

    val block0 = blocks.getOrNull(0)
    if (block0 != null && block0.size >= 16) {
        val uid = block0.copyOfRange(0, 4).toHex()
        val manufacturer = block0.copyOfRange(4, 16).toHex()
//        parsed.add(ParsedField("Block 0 UID", uid))
//        parsed.add(ParsedField("Block 0 Manufacturer", manufacturer))
    }

    val block1 = blocks.getOrNull(1)
    if (block1 != null && block1.size >= 16) {
        val variantId = asciiOrHex(block1.copyOfRange(0, 8))
        val materialIdBytes = block1.copyOfRange(8, 16)
        materialId = asciiOnly(materialIdBytes)
        val materialDisplay = materialId.ifBlank { materialIdBytes.toHex() }
        if (variantId.isNotBlank()) {
            parsed.add(ParsedField("Block 1 Material Variant ID", variantId))
        }
        if (materialDisplay.isNotBlank()) {
            parsed.add(ParsedField("Block 1 Material ID", materialDisplay))
        }
    }

    val block2 = blocks.getOrNull(2)
    if (block2 != null && block2.size >= 16) {
        val filamentType = asciiOrHex(block2.copyOfRange(0, 16))
        if (filamentType.isNotBlank()) {
            parsed.add(ParsedField("Block 2 Filament Type", filamentType))
        }
    }

    val block4 = blocks.getOrNull(4)
    if (block4 != null && block4.size >= 16) {
        val detailedType = asciiOrHex(block4.copyOfRange(0, 16))
        if (detailedType.isNotBlank()) {
            parsed.add(ParsedField("Block 4 Detailed Filament Type", detailedType))
        }
    }

    val block5 = blocks.getOrNull(5)
    if (block5 != null && block5.size >= 16) {
        val colorRgba = "#" + block5.copyOfRange(0, 4).toHex()
        parsed.add(ParsedField("Block 5 Color RGBA", colorRgba))
        val normalized = normalizeColorValue(colorRgba)
        if (normalized.isNotBlank()) {
            colorValues.add(normalized)
        }

        val spoolWeight = toUInt16LE(block5, 4)
        if (spoolWeight != null) {
            parsed.add(ParsedField("Block 5 Spool Weight", "$spoolWeight 克"))
        }

        val diameter = parseDiameter(block5)
        if (diameter.isNotBlank()) {
            parsed.add(ParsedField("Block 5 Filament Diameter", diameter))
        }
    }

    val block6 = blocks.getOrNull(6)
    if (block6 != null && block6.size >= 16) {
        toUInt16LE(block6, 0)?.let {
            parsed.add(
                ParsedField(
                    "Block 6 Drying Temperature",
                    "$it ℃"
                )
            )
        }
        toUInt16LE(block6, 2)?.let { parsed.add(ParsedField("Block 6 Drying Time", "$it 小时")) }
        toUInt16LE(block6, 4)?.let {
            parsed.add(
                ParsedField(
                    "Block 6 Bed Temperature Type",
                    "$it"
                )
            )
        }
        toUInt16LE(block6, 6)?.let { parsed.add(ParsedField("Block 6 Bed Temperature", "$it ℃")) }
        toUInt16LE(block6, 8)?.let {
            parsed.add(
                ParsedField(
                    "Block 6 Max Hotend Temperature",
                    "$it ℃"
                )
            )
        }
        toUInt16LE(block6, 10)?.let {
            parsed.add(
                ParsedField(
                    "Block 6 Min Hotend Temperature",
                    "$it ℃"
                )
            )
        }
    }

    if (block12 != null && block12.size >= 16) {
        val production = formatProductionDate(asciiOrHex(block12.copyOfRange(0, 16)))
        if (production.isNotBlank()) {
            parsed.add(ParsedField("Block 12 Production Date", production))
        }
    }

    val extraColors = parseAdditionalColors(block16)
    colorValues.addAll(extraColors)

    return ParsedBlockData(parsed, materialId, colorValues)
}

private fun parseAdditionalColors(block16: ByteArray?): List<String> {
    if (block16 == null || block16.size < 8) {
        return emptyList()
    }
    val marker = toUInt16LE(block16, 0) ?: return emptyList()
    if (marker != 0x0002) {
        return emptyList()
    }
    val count = toUInt16LE(block16, 2) ?: return emptyList()
    if (count <= 1) {
        return emptyList()
    }
    val maxColors = minOf(count - 1, (block16.size - 4) / 4)
    val colors = ArrayList<String>(maxColors)
    for (i in 0 until maxColors) {
        val start = 4 + i * 4
        val colorBytes = block16.copyOfRange(start, start + 4)
        if (colorBytes.all { it == 0.toByte() }) {
            continue
        }
        val reversed = colorBytes.reversedArray()
        val normalized = normalizeColorValue("#" + reversed.toHex())
        if (normalized.isNotBlank()) {
            colors.add(normalized)
        }
    }
    if (colors.isNotEmpty()) {
        Log.d(
            LOG_TAG,
            "区块16颜色扩展: 标识=0x%04X 颜色数量=%d 颜色=%s".format(
                marker,
                count,
                colors.joinToString(",")
            )
        )
    }
    return colors
}

private fun asciiOrHex(bytes: ByteArray): String {
    val trimmed = trimPadding(bytes)
    if (trimmed.isEmpty()) {
        return ""
    }
    val printable = trimmed.all { it in 0x20..0x7E }
    return if (printable) {
        String(trimmed, Charsets.US_ASCII)
    } else {
        trimmed.toHex()
    }
}

private fun trimPadding(bytes: ByteArray): ByteArray {
    var end = bytes.size
    while (end > 0) {
        val value = bytes[end - 1]
        if (value != 0x00.toByte() && value != 0xFF.toByte()) {
            break
        }
        end--
    }
    return bytes.copyOf(end)
}

private fun asciiOnly(bytes: ByteArray): String {
    val trimmed = trimPadding(bytes)
    if (trimmed.isEmpty()) {
        return ""
    }
    val printable = trimmed.all { it in 0x20..0x7E }
    return if (printable) {
        String(trimmed, Charsets.US_ASCII)
    } else {
        ""
    }
}

private fun parseDiameter(block5: ByteArray): String {
    if (block5.size < 12) {
        return ""
    }
    val trailingZeros = block5.copyOfRange(12, 16).all { it == 0.toByte() }
    val diameter = if (trailingZeros) {
        val floatValue = toFloat32LE(block5, 8) ?: return ""
        floatValue.toDouble()
    } else {
        val doubleValue = toFloat64LE(block5, 8) ?: return ""
        doubleValue
    }
    return String.format(Locale.US, "%.3f 毫米", diameter)
}

private fun toUInt16LE(bytes: ByteArray, offset: Int): Int? {
    if (offset + 1 >= bytes.size) {
        return null
    }
    val low = bytes[offset].toInt() and 0xFF
    val high = bytes[offset + 1].toInt() and 0xFF
    return low or (high shl 8)
}

private fun toFloat32LE(bytes: ByteArray, offset: Int): Float? {
    if (offset + 3 >= bytes.size) {
        return null
    }
    val buffer = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
    return buffer.float
}

private fun toFloat64LE(bytes: ByteArray, offset: Int): Double? {
    if (offset + 7 >= bytes.size) {
        return null
    }
    val buffer = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
    return buffer.double
}

private fun buildDisplayData(
    parsedBlockData: ParsedBlockData,
    dbHelper: FilamentDbHelper?
): DisplayData {
    var type = ""
    var colorName = ""
    var colorCode = ""
    var colorType = ""
    var colorValues = parsedBlockData.colorValues

    if (dbHelper != null && parsedBlockData.materialId.isNotBlank()) {
        val entries = queryFilamentEntries(
            dbHelper,
            parsedBlockData.materialId,
            parsedBlockData.colorValues
        )
        val matched = findMatchingEntry(entries, parsedBlockData.colorValues)
        val entry =
            matched ?: if (parsedBlockData.colorValues.isEmpty()) entries.firstOrNull() else null
        if (entry != null) {
            type = entry.filaType
            colorName = entry.colorNameZh
            colorCode = entry.colorCode
            colorType = entry.colorType
            if (entry.colorValues.isNotEmpty()) {
                colorValues = entry.colorValues
            }
        }
    }

    if (type.isBlank()) {
        type = findFieldValue(
            parsedBlockData.fields,
            "Block 4 Detailed Filament Type",
            "Block 2 Filament Type"
        )
        if (isLikelyHex(type)) {
            type = ""
        }
    }

    val secondaryFields = buildSecondaryFields(parsedBlockData.fields).toMutableList()
    if (colorType.isNotBlank()) {
        secondaryFields.add(0, ParsedField("颜色类型", colorType))
    }
    return DisplayData(
        type = type,
        colorName = colorName,
        colorCode = colorCode,
        colorType = colorType,
        colorValues = colorValues,
        secondaryFields = secondaryFields
    )
}

private fun buildSecondaryFields(fields: List<ParsedField>): List<ParsedField> {
    val labelMap = linkedMapOf(
        "Block 2 Filament Type" to "耗材类型",
        "Block 4 Detailed Filament Type" to "详细耗材类型",
        "Block 5 Spool Weight" to "线轴重量",
        "Block 5 Filament Diameter" to "耗材直径",
        "Block 6 Drying Temperature" to "烘干温度",
        "Block 6 Drying Time" to "烘干时间",
        "Block 6 Bed Temperature Type" to "热床类型",
        "Block 6 Bed Temperature" to "热床温度",
        "Block 6 Max Hotend Temperature" to "喷嘴最高温度",
        "Block 6 Min Hotend Temperature" to "喷嘴最低温度",
        "Block 12 Production Date" to "生产日期"
    )

    val result = ArrayList<ParsedField>()
    for ((label, displayLabel) in labelMap) {
        val value = fields.firstOrNull { it.label == label }?.value.orEmpty()
        if (value.isNotBlank() && !isLikelyHex(value)) {
            result.add(ParsedField(displayLabel, value))
        }
    }
    return result
}

private fun formatProductionDate(value: String): String {
    val raw = value.trim()
    val parts = raw.split('_')
    if (parts.size < 5) {
        return raw
    }
    val year = parts[0]
    val month = parts[1]
    val day = parts[2]
    val hour = parts[3]
    val minute = parts[4]
    val numeric = listOf(year, month, day, hour, minute).all { it.all(Char::isDigit) }
    if (!numeric) {
        return raw
    }
    return "${year}年${month}月${day}日 ${hour}时${minute}分"
}

private fun findMatchingEntry(
    entries: List<FilamentColorEntry>,
    readColors: List<String>
): FilamentColorEntry? {
    val normalizedRead = readColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    if (normalizedRead.isEmpty()) {
        return null
    }
    return entries.firstOrNull { colorsMatch(it.colorValues, normalizedRead) }
}

private fun colorsMatch(entryColors: List<String>, readColors: List<String>): Boolean {
    val normalizedEntry = entryColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    val normalizedRead = readColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    if (normalizedEntry.isEmpty() || normalizedRead.isEmpty()) {
        return false
    }
    if (normalizedEntry.size != normalizedRead.size) {
        return false
    }
    return normalizedEntry.sorted() == normalizedRead.sorted()
}

private fun findFieldValue(fields: List<ParsedField>, vararg labels: String): String {
    for (label in labels) {
        val value = fields.firstOrNull { it.label == label }?.value.orEmpty()
        if (value.isNotBlank()) {
            return value
        }
    }
    return ""
}

private fun isLikelyHex(value: String): Boolean {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length < 8) {
        return false
    }
    return normalized.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
}

private data class FilamentJsonSource(
    val jsonText: String,
    val lastModified: Long
)

private fun syncFilamentDatabase(context: Context, dbHelper: FilamentDbHelper) {
    val source = readFilamentJsonFromExternal(context) ?: return
    Log.d(LOG_TAG, "配置来源: ${source.lastModified}")
    val cacheFile = File(context.cacheDir, FILAMENT_JSON_NAME)
    try {
        cacheFile.writeText(source.jsonText, Charsets.UTF_8)
    } catch (_: IOException) {
        // Ignore cache write failures.
    }

    val db = dbHelper.writableDatabase
    val lastModifiedValue = source.lastModified.toString()
    val storedVersion = dbHelper.getMetaValue(db, FILAMENT_META_KEY_LAST_MODIFIED)
    if (storedVersion == lastModifiedValue) {
        Log.d(LOG_TAG, "配置未变化，跳过数据库更新")
        return
    }

    val entries = parseFilamentEntries(source.jsonText)
    db.beginTransaction()
    try {
        db.delete(FILAMENT_TABLE, null, null)
        val values = ContentValues()
        entries.forEach { entry ->
            values.clear()
            values.put("fila_id", entry.filaId)
            values.put("fila_color_code", entry.colorCode)
            values.put("fila_color_type", entry.colorType)
            values.put("fila_type", entry.filaType)
            values.put("color_name_zh", entry.colorNameZh)
            values.put("color_values", entry.colorValues.joinToString(separator = ","))
            values.put("color_count", entry.colorCount)
            db.insertWithOnConflict(
                FILAMENT_TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        dbHelper.setMetaValue(db, FILAMENT_META_KEY_LAST_MODIFIED, lastModifiedValue)
        db.setTransactionSuccessful()
        Log.d(LOG_TAG, "数据库更新完成，条目数=${entries.size}")
    } finally {
        db.endTransaction()
    }
}

private fun readFilamentJsonFromExternal(context: Context): FilamentJsonSource? {
    val externalDir = context.getExternalFilesDir(null) ?: return null
    val externalFile = File(externalDir, FILAMENT_JSON_NAME)
    if (!externalFile.exists()) {
        try {
            context.assets.open(FILAMENT_JSON_NAME).use { input ->
                externalFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: IOException) {
            return null
        }
    }
    if (!externalFile.exists()) {
        return null
    }
    val jsonText = try {
        externalFile.readText(Charsets.UTF_8)
    } catch (_: IOException) {
        return null
    }
    return FilamentJsonSource(jsonText, externalFile.lastModified())
}

private fun parseFilamentEntries(jsonText: String): List<FilamentColorEntry> {
    val root = try {
        JSONObject(jsonText)
    } catch (_: Exception) {
        return emptyList()
    }
    val data = root.optJSONArray("data") ?: JSONArray()
    val entries = ArrayList<FilamentColorEntry>(data.length())
    for (i in 0 until data.length()) {
        val item = data.optJSONObject(i) ?: continue
        val filaId = item.optString("fila_id")
        if (filaId.isBlank()) {
            continue
        }
        val colorNameZh = item.optJSONObject("fila_color_name")?.optString("zh").orEmpty()
        val colorsArray = item.optJSONArray("fila_color")
        val colorValues = ArrayList<String>()
        if (colorsArray != null) {
            for (j in 0 until colorsArray.length()) {
                val value = normalizeColorValue(colorsArray.optString(j))
                if (value.isNotBlank()) {
                    colorValues.add(value)
                }
            }
        }
        entries.add(
            FilamentColorEntry(
                colorCode = item.optString("fila_color_code"),
                filaId = filaId,
                colorType = item.optString("fila_color_type"),
                filaType = item.optString("fila_type"),
                colorNameZh = colorNameZh,
                colorValues = colorValues.toList(),
                colorCount = colorValues.size
            )
        )
    }
    return entries
}

private fun queryFilamentEntries(
    dbHelper: FilamentDbHelper,
    filaId: String,
    readColors: List<String>
): List<FilamentColorEntry> {
    val db = dbHelper.readableDatabase
    val entries = ArrayList<FilamentColorEntry>()
    val normalizedColors = readColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    val selection: String
    val selectionArgs: Array<String>
    if (normalizedColors.isNotEmpty()) {
        selection = "fila_id = ? AND color_count = ?"
        selectionArgs = arrayOf(filaId, normalizedColors.size.toString())
    } else {
        selection = "fila_id = ?"
        selectionArgs = arrayOf(filaId)
    }
    val cursor = db.query(
        FILAMENT_TABLE,
        arrayOf(
            "fila_color_code",
            "fila_id",
            "fila_color_type",
            "fila_type",
            "color_name_zh",
            "color_values",
            "color_count"
        ),
        selection,
        selectionArgs,
        null,
        null,
        "fila_color_code ASC"
    )
    cursor.use {
        while (it.moveToNext()) {
            val colorValues = it.getString(5)
                ?.split(',')
                ?.map { value -> value.trim() }
                ?.filter { value -> value.isNotEmpty() }
                ?: emptyList()
            val colorCount = it.getInt(6)
            entries.add(
                FilamentColorEntry(
                    colorCode = it.getString(0).orEmpty(),
                    filaId = it.getString(1).orEmpty(),
                    colorType = it.getString(2).orEmpty(),
                    filaType = it.getString(3).orEmpty(),
                    colorNameZh = it.getString(4).orEmpty(),
                    colorValues = colorValues,
                    colorCount = colorCount
                )
            )
        }
    }
    return entries
}

private fun normalizeColorValue(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    return if (trimmed.startsWith("#")) {
        "#" + trimmed.substring(1).uppercase(Locale.US)
    } else {
        "#" + trimmed.uppercase(Locale.US)
    }
}

private fun parseColorValue(value: String): Color? {
    val normalized = normalizeColorValue(value)
    val hex = normalized.removePrefix("#")
    return try {
        val argb = when (hex.length) {
            8 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                val a = hex.substring(6, 8).toInt(16)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }

            6 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            else -> return null
        }
        Color(argb)
    } catch (_: Exception) {
        null
    }
}

private class FilamentDbHelper(context: Context) :
    SQLiteOpenHelper(context, FILAMENT_DB_NAME, null, FILAMENT_DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $FILAMENT_TABLE (
                fila_id TEXT NOT NULL,
                fila_color_code TEXT NOT NULL,
                fila_color_type TEXT,
                fila_type TEXT,
                color_name_zh TEXT,
                color_values TEXT,
                color_count INTEGER,
                PRIMARY KEY (fila_id, fila_color_code)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_filaments_fila_id_color ON $FILAMENT_TABLE (fila_id, color_count)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $FILAMENT_META_TABLE (
                key TEXT PRIMARY KEY,
                value TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS $FILAMENT_TABLE")
            db.execSQL("DROP TABLE IF EXISTS $FILAMENT_META_TABLE")
            onCreate(db)
        }
    }

    fun getMetaValue(db: SQLiteDatabase, key: String): String? {
        val cursor = db.query(
            FILAMENT_META_TABLE,
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun setMetaValue(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues()
        values.put("key", key)
        values.put("value", value)
        db.insertWithOnConflict(
            FILAMENT_META_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}

private fun deriveKeys(uid: ByteArray, info: ByteArray): List<ByteArray> {
    val prk = hkdfExtract(HKDF_SALT, uid)
    val okm = hkdfExpand(prk, info, KEY_LENGTH_BYTES * SECTOR_COUNT)
    val keys = ArrayList<ByteArray>(SECTOR_COUNT)
    for (i in 0 until SECTOR_COUNT) {
        val start = i * KEY_LENGTH_BYTES
        keys.add(okm.copyOfRange(start, start + KEY_LENGTH_BYTES))
    }
    return keys
}

private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    return mac.doFinal(ikm)
}

private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val hashLen = mac.macLength
    val blocks = ceil(length.toDouble() / hashLen.toDouble()).toInt()
    var t = ByteArray(0)
    val output = ByteArrayOutputStream()
    for (i in 1..blocks) {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(t)
        mac.update(info)
        mac.update(i.toByte())
        t = mac.doFinal()
        output.write(t)
    }
    val okm = output.toByteArray()
    return okm.copyOf(length)
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02X".format(Locale.US, it) }
