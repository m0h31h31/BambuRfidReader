package com.m0h31h31.bamburfidreader

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

private const val KEY_LENGTH_BYTES = 6
private const val SECTOR_COUNT = 16

// === 稳定性/速度参数（按需调整） ===
private const val INTER_BLOCK_DELAY_MS = 0L    // 想更稳可设 5~10；追求极致速度保持 0
private const val AUTH_RETRY_COUNT = 2          // 认证失败重试次数（总尝试=1+重试）
private const val READ_BLOCK_RETRY_COUNT = 1    // 单块读失败重试次数

// Bambu RFID 的密钥派生固定盐值。
private val HKDF_SALT = byteArrayOf(
    0x9a.toByte(), 0x75.toByte(), 0x9c.toByte(), 0xf2.toByte(),
    0xc4.toByte(), 0xf7.toByte(), 0xca.toByte(), 0xff.toByte(),
    0x22.toByte(), 0x2c.toByte(), 0xb9.toByte(), 0x76.toByte(),
    0x9b.toByte(), 0x41.toByte(), 0xbc.toByte(), 0x96.toByte()
)

// 派生 KeyA / KeyB 时使用的 info 参数。
private val INFO_A = "RFID-A\u0000".toByteArray(Charsets.US_ASCII)
private val INFO_B = "RFID-B\u0000".toByteArray(Charsets.US_ASCII)

/**
 * 原始读卡结果（仅包含“读取层”的数据，不做业务解析）。
 */
data class RawTagReadData(
    val uidHex: String,
    val keyA0Hex: String,
    val keyB0Hex: String,
    val keyA1Hex: String,
    val keyB1Hex: String,
    val sectorKeys: List<Pair<ByteArray?, ByteArray?>>,
    val rawBlocks: List<ByteArray?>,
    val errors: List<String>
)

enum class RawTagReadFailureReason {
    UID_MISSING,
    MIFARE_UNSUPPORTED,
    EXCEPTION
}

sealed class RawTagReadResult {
    data class Success(val data: RawTagReadData) : RawTagReadResult()
    data class Failure(
        val reason: RawTagReadFailureReason,
        val message: String,
        val uidHex: String = "",
        val keyA0Hex: String = "",
        val keyB0Hex: String = "",
        val keyA1Hex: String = "",
        val keyB1Hex: String = ""
    ) : RawTagReadResult()
}

// 单个扇区读取的内部结果，仅在读卡模块内部使用。
private data class SectorReadResult(
    val blocks: List<ByteArray>,
    val error: String
)

/**
 * 纯读卡模块：
 * - 负责认证扇区并读取块；
 * - 不负责业务字段解析、库存计算、数据库写入。
 */
object NfcTagReader {

    // 按 UID 缓存派生密钥（频繁刷同一张卡时显著减少 CPU 开销）
    private val keyCache = HashMap<String, List<Pair<ByteArray?, ByteArray?>>>()

    fun readRaw(
        tag: Tag,
        readAllSectors: Boolean,
        logger: (String) -> Unit,
        appendLog: (String, String) -> Unit
    ): RawTagReadResult {

        // 1) UID
        val uid = tag.id ?: return RawTagReadResult.Failure(
            reason = RawTagReadFailureReason.UID_MISSING,
            message = "UID missing"
        )
        val uidHex = uid.toHex()
        logger("UID: $uidHex")
        appendLog("I", "开始读取标签 UID: $uidHex")

        // 2) 扇区密钥（缓存）
        val sectorKeys = keyCache[uidHex] ?: run {
            val keysA = deriveKeys(uid, INFO_A)
            val keysB = deriveKeys(uid, INFO_B)
            val sk = ArrayList<Pair<ByteArray?, ByteArray?>>(SECTOR_COUNT)
            for (i in 0 until SECTOR_COUNT) {
                sk.add(Pair(keysA.getOrNull(i), keysB.getOrNull(i)))
            }
            keyCache[uidHex] = sk
            sk
        }

        val keyA0Hex = sectorKeys.getOrNull(0)?.first?.toHex().orEmpty()
        val keyB0Hex = sectorKeys.getOrNull(0)?.second?.toHex().orEmpty()
        val keyA1Hex = sectorKeys.getOrNull(1)?.first?.toHex().orEmpty()
        val keyB1Hex = sectorKeys.getOrNull(1)?.second?.toHex().orEmpty()

        // 3) 仅处理 MifareClassic
        val mifare = MifareClassic.get(tag) ?: return RawTagReadResult.Failure(
            reason = RawTagReadFailureReason.MIFARE_UNSUPPORTED,
            message = "MIFARE Classic not supported",
            uidHex = uidHex,
            keyA0Hex = keyA0Hex,
            keyB0Hex = keyB0Hex,
            keyA1Hex = keyA1Hex,
            keyB1Hex = keyB1Hex
        )

        return try {
            // 4) connect 一次
            mifare.connect()

            // rawBlocks 用 mifare.blockCount 动态生成（兼容 1K/4K）
            val rawBlocks = MutableList<ByteArray?>(mifare.blockCount) { null }
            val errors = ArrayList<String>(4)

            // 读取策略：读取 trailer，便于在 UI 中展示和排障（如 block3/block7）。
            val readTrailer = true

            fun readAndFill(sector: Int, collectError: Boolean) {
                val keyA = sectorKeys.getOrNull(sector)?.first
                val keyB = sectorKeys.getOrNull(sector)?.second

                val result = readSectorOptimized(
                    mifare = mifare,
                    sectorIndex = sector,
                    keyA = keyA,
                    keyB = keyB,
                    readTrailer = readTrailer,
                    logger = logger
                )

                val rawStart = sector * 4
                if (result.blocks.isNotEmpty()) {
                    result.blocks.forEachIndexed { index, data ->
                        val rawIndex = rawStart + index
                        if (rawIndex in rawBlocks.indices) rawBlocks[rawIndex] = data
                    }
                }

                if (result.error.isNotBlank()) {
                    logger("扇区$sector 读取失败: ${result.error}")
                    appendLog("W", "扇区$sector 读取失败: ${result.error}")
                    if (collectError) errors.add(result.error)
                }
            }

            val sectorsToRead = if (readAllSectors) {
                0 until SECTOR_COUNT
            } else {
                0..4
            }

            for (sector in sectorsToRead) {
                readAndFill(sector, collectError = (sector == 0 || sector == 1))
            }

            if (!readAllSectors) {
                logger("未读取全部扇区（按配置跳过）")
                appendLog("I", "未读取全部扇区（按配置跳过）")
            }

            RawTagReadResult.Success(
                RawTagReadData(
                    uidHex = uidHex,
                    keyA0Hex = keyA0Hex,
                    keyB0Hex = keyB0Hex,
                    keyA1Hex = keyA1Hex,
                    keyB1Hex = keyB1Hex,
                    sectorKeys = sectorKeys,
                    rawBlocks = rawBlocks,
                    errors = errors
                )
            )
        } catch (e: Exception) {
            logger("读取异常: ${e.javaClass.simpleName}: ${e.message}")
            appendLog("E", "读取异常: ${e.javaClass.simpleName}: ${e.message}")
            RawTagReadResult.Failure(
                reason = RawTagReadFailureReason.EXCEPTION,
                message = e.message.orEmpty(),
                uidHex = uidHex,
                keyA0Hex = keyA0Hex,
                keyB0Hex = keyB0Hex,
                keyA1Hex = keyA1Hex,
                keyB1Hex = keyB1Hex
            )
        } finally {
            try {
                mifare.close()
                appendLog("I", "已断开 MIFARE Classic")
            } catch (_: IOException) {
            }
        }
    }
}

/**
 * 读取单个扇区（优化版）：
 * - 认证一次；
 * - 默认读取 3 个数据块（不读 trailer）；
 * - 读失败会返回明确 blockIndex；
 * - 可选每块间隔（默认 0ms）。
 */
private fun readSectorOptimized(
    mifare: MifareClassic,
    sectorIndex: Int,
    keyA: ByteArray?,
    keyB: ByteArray?,
    readTrailer: Boolean,
    logger: (String) -> Unit
): SectorReadResult {

    val authenticated = authenticateSectorWithRetry(
        mifare = mifare,
        sectorIndex = sectorIndex,
        keyA = keyA,
        keyB = keyB
    )

    if (!authenticated) {
        return SectorReadResult(emptyList(), "扇区 $sectorIndex 认证失败")
    }

    val startBlock = mifare.sectorToBlock(sectorIndex)

    // Classic 1K：每扇区 4 blocks，其中最后一个 trailer
    val blocksToRead = if (readTrailer) 4 else 3

    val blocks = ArrayList<ByteArray>(blocksToRead)
    for (offset in 0 until blocksToRead) {
        val absBlock = startBlock + offset
        var blockData: ByteArray? = null
        var lastError: Exception? = null

        for (attempt in 0..READ_BLOCK_RETRY_COUNT) {
            try {
                val raw = mifare.readBlock(absBlock)
                blockData = when {
                    raw.size == 16 -> raw
                    raw.size > 16 -> raw.copyOf(16)
                    else -> throw IOException("返回长度异常(${raw.size})")
                }
                break
            } catch (e: Exception) {
                lastError = e
                // 读失败后按 MCReader 思路重认证一次再重试。
                val reAuthOk = authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = sectorIndex,
                    keyA = keyA,
                    keyB = keyB
                )
                if (!reAuthOk) {
                    break
                }
            }
        }

        if (blockData == null) {
            val err = "读 block=$absBlock 失败: ${lastError?.javaClass?.simpleName}: ${lastError?.message}"
            return SectorReadResult(blocks, err)
        }
        blocks.add(blockData)

        if (INTER_BLOCK_DELAY_MS > 0) Thread.sleep(INTER_BLOCK_DELAY_MS)
    }

    return SectorReadResult(blocks, "")
}

private fun authenticateSectorWithRetry(
    mifare: MifareClassic,
    sectorIndex: Int,
    keyA: ByteArray?,
    keyB: ByteArray?
): Boolean {
    for (attempt in 0..AUTH_RETRY_COUNT) {
        try {
            val okA = keyA != null && mifare.authenticateSectorWithKeyA(sectorIndex, keyA)
            if (okA) return true
            val okB = keyB != null && mifare.authenticateSectorWithKeyB(sectorIndex, keyB)
            if (okB) return true
        } catch (_: Exception) {
            // Ignore and retry.
        }
    }
    return false
}

/**
 * 按 Bambu 规则基于 UID 派生 16 组扇区密钥。
 */
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

// HKDF-Extract
private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    return mac.doFinal(ikm)
}

// HKDF-Expand
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
    return output.toByteArray().copyOf(length)
}

// ByteArray 转大写十六进制字符串（不带空格）
private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02X".format(Locale.US, it) }

// ByteArray? 转十六进制（用于 logger，带空格更易读）
private fun ByteArray?.toHexOrNull(): String =
    this?.joinToString(" ") { "%02X".format(Locale.US, it) } ?: "null"
