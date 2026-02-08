package com.m0h31h31.bamburfidreader

import com.m0h31h31.bamburfidreader.util.normalizeColorValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class ProcessedTagData(
    val blockHexes: List<String>,
    val parsedFields: List<ParsedField>,
    val displayData: DisplayData,
    val trayUidHex: String,
    val remainingPercent: Float,
    val remainingGrams: Int,
    val totalWeightGrams: Int
)

/**
 * 数据处理模块：
 * - 输入：RawTagReadData（读卡层原始数据）
 * - 输出：ProcessedTagData（可直接用于 UI 的结构化数据）
 * - 副作用：按规则写入库存相关数据库字段
 */
object NfcTagProcessor {
    /**
     * 从原始块数据中解析业务字段，并同步库存数据。
     *
     * 该函数职责：
     * 1. 从原始块提取需要解析的块；
     * 2. 解析材料、颜色、重量等字段；
     * 3. 结合数据库历史记录计算余量；
     * 4. 持久化库存与显示关联字段；
     * 5. 组装 UI 需要的结果结构。
     */
    fun parseAndPersist(
        rawData: RawTagReadData,
        dbHelper: FilamentDbHelper?,
        defaultRemainingPercent: Float,
        logger: (String) -> Unit,
        appendLog: (String, String) -> Unit
    ): ProcessedTagData {
        val rawBlocks = rawData.rawBlocks
        // 业务解析当前只依赖 block0..7 + block12 + block16。
        val blocksForParsing = listOf(
            rawBlocks.getOrNull(0),
            rawBlocks.getOrNull(1),
            rawBlocks.getOrNull(2),
            rawBlocks.getOrNull(3),
            rawBlocks.getOrNull(4),
            rawBlocks.getOrNull(5),
            rawBlocks.getOrNull(6),
            rawBlocks.getOrNull(7)
        )
        val block12 = rawBlocks.getOrNull(12)
        val block16 = rawBlocks.getOrNull(16)

        val parsedBlockData = parseBlocks(blocksForParsing, block12, block16, logger)

        val totalWeightGrams = extractWeightGrams(parsedBlockData.fields)

        // 托盘 UID 存在于区块9，作为库存表主键。
        val trayUidHex = rawBlocks.getOrNull(9)?.toHex().orEmpty()
        var remainingPercent = defaultRemainingPercent
        var remainingGrams = 0

        // 读取并更新“剩余百分比/克重/总克重”。
        if (trayUidHex.isNotBlank()) {
            val db = dbHelper?.writableDatabase
            if (db != null) {
                val stored = dbHelper.getTrayRemainingPercent(db, trayUidHex)
                val storedGrams = dbHelper.getTrayRemainingGrams(db, trayUidHex)
                remainingPercent = stored ?: defaultRemainingPercent
                remainingGrams = storedGrams ?: 0
                if (remainingGrams == 0 && totalWeightGrams > 0) {
                    remainingGrams = totalWeightGrams
                }
                dbHelper.upsertTrayRemaining(
                    db,
                    trayUidHex,
                    remainingPercent,
                    remainingGrams,
                    totalWeightGrams
                )
            }
            logger("托盘UID(区块9): $trayUidHex, 余量: $remainingPercent%")
            appendLog("I", "托盘UID(区块9): $trayUidHex, 余量: $remainingPercent%")
        } else {
            logger("未在区块9读取到UID")
            appendLog("W", "未在区块9读取到UID")
        }

        // 解析显示字段（类型、颜色名、颜色代码、颜色展示方案）。
        val displayData = buildDisplayData(parsedBlockData, dbHelper, logger)
        // 持久化库存详情，便于库存页与数据页直接查询展示。
        if (trayUidHex.isNotBlank()) {
            val db = dbHelper?.writableDatabase
            if (db != null) {
                val filamentId =
                    dbHelper.getFilamentId(db, parsedBlockData.materialId, displayData.colorCode)
                dbHelper.upsertTrayInventory(
                    db = db,
                    trayUid = trayUidHex,
                    remainingPercent = remainingPercent,
                    remainingGrams = remainingGrams,
                    totalWeightGrams = totalWeightGrams,
                    filamentId = filamentId,
                    materialId = parsedBlockData.materialId,
                    materialType = parsedBlockData.filamentType,
                    detailedMaterialType = parsedBlockData.detailedFilamentType,
                    colorName = displayData.colorName,
                    colorCode = displayData.colorCode,
                    colorType = displayData.colorType,
                    colorValues = displayData.colorValues.joinToString(separator = ",")
                )
            }
        }

        // 返回给 UI 层使用的聚合数据。
        return ProcessedTagData(
            blockHexes = blocksForParsing.map { it?.toHex().orEmpty() },
            parsedFields = parsedBlockData.fields,
            displayData = displayData,
            trayUidHex = trayUidHex,
            remainingPercent = remainingPercent,
            remainingGrams = remainingGrams,
            totalWeightGrams = totalWeightGrams
        )
    }
}

/**
 * 解析关键块为结构化字段。
 */
private fun parseBlocks(
    blocks: List<ByteArray?>,
    block12: ByteArray?,
    block16: ByteArray?,
    logger: (String) -> Unit
): ParsedBlockData {
    val parsed = ArrayList<ParsedField>()
    var materialId = ""
    var filamentType = ""
    var detailedFilamentType = ""
    val colorValues = ArrayList<String>()

    // block1: 材料变体与材料 ID。
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

    // block2: 基础耗材类型（如 PLA、PETG 等）。
    val block2 = blocks.getOrNull(2)
    if (block2 != null && block2.size >= 16) {
        filamentType = asciiOrHex(block2.copyOfRange(0, 16))
        if (filamentType.isNotBlank()) {
            parsed.add(ParsedField("Block 2 Filament Type", filamentType))
        }
    }

    // block4: 更详细的耗材类型。
    val block4 = blocks.getOrNull(4)
    if (block4 != null && block4.size >= 16) {
        detailedFilamentType = asciiOrHex(block4.copyOfRange(0, 16))
        if (detailedFilamentType.isNotBlank()) {
            parsed.add(ParsedField("Block 4 Detailed Filament Type", detailedFilamentType))
        }
    }

    // block5: 颜色 RGBA、卷重、线径。
    val block5 = blocks.getOrNull(5)
    if (block5 != null && block5.size >= 16) {
        val colorRgba = "#" + block5.copyOfRange(0, 4).toHex()
        parsed.add(ParsedField("Block 5 Color RGBA", colorRgba))
        normalizeColorValue(colorRgba).takeIf { it.isNotBlank() }?.let { colorValues.add(it) }

        toUInt16LE(block5, 4)?.let {
            parsed.add(ParsedField("Block 5 Spool Weight", "$it 克"))
        }
        parseDiameter(block5).takeIf { it.isNotBlank() }?.let {
            parsed.add(ParsedField("Block 5 Filament Diameter", it))
        }
    }

    // block6: 烘干温度/时长、床温、喷嘴温度等工艺参数。
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

    // block12: 生产时间。
    if (block12 != null && block12.size >= 16) {
        formatProductionDate(asciiOrHex(block12.copyOfRange(0, 16)))
            .takeIf { it.isNotBlank() }
            ?.let { parsed.add(ParsedField("Block 12 Production Date", it)) }
    }

    // block16: 多色扩展信息（例如拼色、渐变）。
    colorValues.addAll(parseAdditionalColors(block16, logger))

    logger("解析到的颜色值: ${colorValues.joinToString(", ")}")

    return ParsedBlockData(
        fields = parsed,
        materialId = materialId,
        filamentType = filamentType,
        detailedFilamentType = detailedFilamentType,
        colorValues = colorValues
    )
}

/**
 * 解析 block16 的扩展颜色列表。
 *
 * 协议约束：
 * - offset 0..1 为 marker，必须是 0x0002；
 * - offset 2..3 为颜色数量；
 * - 后续每 4 字节一个 RGBA。
 */
private fun parseAdditionalColors(block16: ByteArray?, logger: (String) -> Unit): List<String> {
    if (block16 == null || block16.size < 8) return emptyList()

    val hex = block16.joinToString(" ") { "%02X".format(it) }

    logger("block16 HEX: $hex")
    val markerLe = toUInt16LE(block16, 0) ?: return emptyList()
    val markerBe = toUInt16BE(block16, 0) ?: return emptyList()
    if (markerLe != 0x0002 && markerBe != 0x0002) return emptyList()

    val countLe = toUInt16LE(block16, 2) ?: 0
    val countBe = toUInt16BE(block16, 2) ?: 0
    val slots = (block16.size - 4) / 4

    // 兼容两种协议语义：
    // 1) count = 总颜色数（包含主色）-> 需要减 1 得到附加色个数
    // 2) count = 附加色个数（不包含主色）-> 直接使用 count
    val candidateCounts = linkedSetOf(
        countLe,
        countLe - 1,
        countBe,
        countBe - 1
    ).filter { it > 0 }

    fun parseByLimit(limit: Int): List<String> {
        val actual = minOf(limit, slots)
        val colors = ArrayList<String>(actual)
        for (i in 0 until actual) {
            val start = 4 + i * 4
            val colorBytes = block16.copyOfRange(start, start + 4)
            if (colorBytes.all { it == 0.toByte() }) continue
            val normalized = normalizeColorValue("#" + colorBytes.reversedArray().toHex())
            if (normalized.isNotBlank()) colors.add(normalized)
        }
        return colors
    }

    val best = candidateCounts
        .map { candidate -> candidate to parseByLimit(candidate) }
        .maxByOrNull { (_, colors) -> colors.size }
        ?.second
        .orEmpty()

    if (best.isNotEmpty()) {
        logger(
            "区块16多色数据: 标识LE=0x%04X 标识BE=0x%04X 计数LE=%d 计数BE=%d 颜色=%s".format(
                markerLe,
                markerBe,
                countLe,
                countBe,
                best.joinToString(",")
            )
        )
    }
    return best
}

/**
 * 构建 UI 展示数据。
 *
 * 优先级：
 * 1. 优先使用数据库配置匹配结果；
 * 2. 匹配失败时回落到标签字段内容。
 */
private fun buildDisplayData(
    parsedBlockData: ParsedBlockData,
    dbHelper: FilamentDbHelper?,
    logger: (String) -> Unit
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
            parsedBlockData.colorValues,
            logger = logger
        )
        val matched = findMatchingEntry(entries, parsedBlockData.colorValues)
        val entry =
            matched ?: if (parsedBlockData.colorValues.isEmpty()) entries.firstOrNull() else null
        if (entry != null) {
            type = entry.filaType
            colorName = entry.colorNameZh
            colorCode = entry.colorCode
            colorType = entry.colorType
            if (entry.colorValues.isNotEmpty()) colorValues = entry.colorValues
        }
    }

    if (type.isBlank()) {
        type = findFieldValue(
            parsedBlockData.fields,
            "Block 4 Detailed Filament Type",
            "Block 2 Filament Type"
        )
        if (isLikelyHex(type)) type = ""
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

// 将内部解析字段映射为 UI 二级信息展示。
private fun buildSecondaryFields(fields: List<ParsedField>): List<ParsedField> {
    val labelMap = linkedMapOf(
        "Block 5 Spool Weight" to "耗材重量",
        "Block 5 Filament Diameter" to "耗材直径",
        "Block 6 Drying Temperature" to "烘干温度",
        "Block 6 Drying Time" to "烘干时间",
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

// 查询 filaments 表，按 materialId + 颜色数量（可选）定位候选项。
private fun queryFilamentEntries(
    dbHelper: FilamentDbHelper,
    filaId: String,
    readColors: List<String>,
    logger: (String) -> Unit
): List<FilamentColorEntry> {
    val db = dbHelper.readableDatabase
    val normalizedColors = readColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    logger("查询颜色: ${normalizedColors.joinToString(", ")}")

    fun queryBy(selection: String, selectionArgs: Array<String>): List<FilamentColorEntry> {
        val entries = ArrayList<FilamentColorEntry>()
        val cursor = db.query(
            "filaments",
            arrayOf(
                "fila_color_code",
                "fila_id",
                "fila_color_type",
                "fila_type",
                "fila_detailed_type",
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
                val colorValues = it.getString(6)
                    ?.split(',')
                    ?.map { value -> normalizeColorValue(value.trim()) }
                    ?.filter { value -> value.isNotEmpty() }
                    ?: emptyList()
                entries.add(
                    FilamentColorEntry(
                        colorCode = it.getString(0).orEmpty(),
                        filaId = it.getString(1).orEmpty(),
                        colorType = it.getString(2).orEmpty(),
                        filaType = it.getString(3).orEmpty(),
                        filaDetailedType = it.getString(4).orEmpty(),
                        colorNameZh = it.getString(5).orEmpty(),
                        colorValues = colorValues,
                        colorCount = it.getInt(7)
                    )
                )
            }
        }
        return entries
    }

    if (normalizedColors.isNotEmpty()) {
        val exactCountEntries = queryBy(
            selection = "fila_id = ? AND color_count = ?",
            selectionArgs = arrayOf(filaId, normalizedColors.size.toString())
        )
        if (exactCountEntries.isNotEmpty()) {
            return exactCountEntries
        }
        logger("按颜色数量未命中，回退为仅按 fila_id 查询")
    }
    return queryBy(
        selection = "fila_id = ?",
        selectionArgs = arrayOf(filaId)
    )
}

// 从“Block 5 Spool Weight”字段中提取数字克重。
private fun extractWeightGrams(fields: List<ParsedField>): Int {
    val field = fields.firstOrNull { it.label == "Block 5 Spool Weight" } ?: return 0
    val digits = field.value.filter { it.isDigit() }
    return digits.toIntOrNull() ?: 0
}

// 在候选中寻找颜色完全匹配的一条记录。
private fun findMatchingEntry(
    entries: List<FilamentColorEntry>,
    readColors: List<String>
): FilamentColorEntry? {
    val normalizedRead = readColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    if (normalizedRead.isEmpty()) return null
    return entries.firstOrNull { colorsMatch(it.colorValues, normalizedRead) }
}

// 严格比较颜色列表（数量一致且顺序一致）。
private fun colorsMatch(entryColors: List<String>, readColors: List<String>): Boolean {
    val normalizedEntry = entryColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    val normalizedRead = readColors.map { normalizeColorValue(it) }.filter { it.isNotBlank() }
    if (normalizedEntry.isEmpty() || normalizedRead.isEmpty()) return false
    if (normalizedEntry.size != normalizedRead.size) return false
    // 多色匹配改为“顺序无关”，避免数据库颜色顺序与标签顺序不一致时漏匹配。
    return normalizedEntry.sorted() == normalizedRead.sorted()
}

// 按优先顺序取第一个非空字段值。
private fun findFieldValue(fields: List<ParsedField>, vararg labels: String): String {
    for (label in labels) {
        val value = fields.firstOrNull { it.label == label }?.value.orEmpty()
        if (value.isNotBlank()) return value
    }
    return ""
}

// 粗略判断字符串是否更像原始十六进制数据而非可读文本。
private fun isLikelyHex(value: String): Boolean {
    val cleaned = value.replace(" ", "")
    if (cleaned.length < 8) return false
    return cleaned.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
}

// 优先返回可打印 ASCII，否则回落为 HEX。
private fun asciiOrHex(bytes: ByteArray): String {
    val trimmed = trimPadding(bytes)
    if (trimmed.isEmpty()) return ""
    val printable = trimmed.all { it in 0x20..0x7E }
    return if (printable) String(trimmed, Charsets.US_ASCII) else trimmed.toHex()
}

// 仅接受可打印 ASCII，不可打印时返回空字符串。
private fun asciiOnly(bytes: ByteArray): String {
    val trimmed = trimPadding(bytes)
    if (trimmed.isEmpty()) return ""
    val printable = trimmed.all { it in 0x20..0x7E }
    return if (printable) String(trimmed, Charsets.US_ASCII) else ""
}

// 去除尾部 0x00 / 0xFF 填充字节。
private fun trimPadding(bytes: ByteArray): ByteArray {
    var end = bytes.size
    while (end > 0) {
        val value = bytes[end - 1]
        if (value != 0x00.toByte() && value != 0xFF.toByte()) break
        end--
    }
    return bytes.copyOf(end)
}

// 解析耗材直径，兼容 float32 / float64 两种编码方式。
private fun parseDiameter(block5: ByteArray): String {
    if (block5.size < 12) return ""
    val trailingZeros = block5.copyOfRange(12, 16).all { it == 0.toByte() }
    val diameter = if (trailingZeros) {
        toFloat32LE(block5, 8)?.toDouble() ?: return ""
    } else {
        toFloat64LE(block5, 8) ?: return ""
    }
    return String.format(Locale.US, "%.3f 毫米", diameter)
}

// 规范化生产日期格式：YYYY_MM_DD_HH_MM -> YYYY年MM月DD日 HH时MM分。
private fun formatProductionDate(value: String): String {
    val raw = value.trim()
    val parts = raw.split('_')
    if (parts.size < 5) return raw
    val year = parts[0]
    val month = parts[1]
    val day = parts[2]
    val hour = parts[3]
    val minute = parts[4]
    val numeric = listOf(year, month, day, hour, minute).all { it.all(Char::isDigit) }
    if (!numeric) return raw
    return "${year}年${month}月${day}日 ${hour}时${minute}分"
}

// 读取 little-endian 16 位无符号整型。
private fun toUInt16LE(bytes: ByteArray, offset: Int): Int? {
    if (offset + 1 >= bytes.size) return null
    val low = bytes[offset].toInt() and 0xFF
    val high = bytes[offset + 1].toInt() and 0xFF
    return low or (high shl 8)
}

// 读取 big-endian 16 位无符号整型（用于兼容部分标签/固件写法）。
private fun toUInt16BE(bytes: ByteArray, offset: Int): Int? {
    if (offset + 1 >= bytes.size) return null
    val high = bytes[offset].toInt() and 0xFF
    val low = bytes[offset + 1].toInt() and 0xFF
    return (high shl 8) or low
}

// 读取 little-endian 32 位浮点数。
private fun toFloat32LE(bytes: ByteArray, offset: Int): Float? {
    if (offset + 3 >= bytes.size) return null
    return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
}

// 读取 little-endian 64 位浮点数。
private fun toFloat64LE(bytes: ByteArray, offset: Int): Double? {
    if (offset + 7 >= bytes.size) return null
    return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double
}

// ByteArray 转 HEX。
private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02X".format(Locale.US, it) }
