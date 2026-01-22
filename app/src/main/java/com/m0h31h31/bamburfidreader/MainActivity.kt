package com.m0h31h31.bamburfidreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.m0h31h31.bamburfidreader.ui.navigation.AppNavigation
import com.m0h31h31.bamburfidreader.ui.theme.BambuRfidReaderTheme
import com.m0h31h31.bamburfidreader.util.normalizeColorValue
import com.m0h31h31.bamburfidreader.utils.ConfigManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject

private const val KEY_LENGTH_BYTES = 6
private const val SECTOR_COUNT = 16
private const val LOG_TAG = "BambuRfidReader"
private const val FILAMENT_JSON_NAME = "filaments_color_codes.json"
private const val FILAMENTS_TYPE_MAPPING_FILE = "filaments_type_mapping.json"
private const val FILAMENT_DB_NAME = "filaments.db"
private const val FILAMENT_DB_VERSION = 10
private const val FILAMENT_TABLE = "filaments"
private const val FILAMENT_TYPE_MAPPING_TABLE = "filament_type_mapping"
private const val FILAMENT_META_TABLE = "meta_v2"
private const val FILAMENT_META_KEY_LAST_MODIFIED = "filaments_last_modified"
private const val FILAMENT_META_KEY_LOCALE = "filaments_locale"
private const val TRAY_UID_TABLE = "filament_inventory"
private const val DEFAULT_REMAINING_PERCENT = 100
private const val LOG_DIR_NAME = "logs"
private const val LOG_FILE_NAME = "bambu_rfid.log"
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

object LogCollector {
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun append(context: Context?, level: String, message: String) {
        val targetContext = context ?: appContext ?: return
        val baseDir = targetContext.getExternalFilesDir(null) ?: targetContext.filesDir
        val logDir = File(baseDir, LOG_DIR_NAME)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val line = "${formatter.format(Date())} [$level] $message\n"
        synchronized(lock) {
            File(logDir, LOG_FILE_NAME).appendText(line, Charsets.UTF_8)
        }
    }

    fun packageLogs(context: Context): String {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val logDir = File(baseDir, LOG_DIR_NAME)
        if (!logDir.exists()) {
            return "没有日志可打包"
        }
        val logFiles = logDir.listFiles { file -> file.isFile }?.toList().orEmpty()
        if (logFiles.isEmpty()) {
            return "没有日志可打包"
        }
        val archiveName =
            "logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
        val archive = File(baseDir, archiveName)
        return try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                logFiles.forEach { file ->
                    FileInputStream(file).use { input ->
                        zip.putNextEntry(ZipEntry("${LOG_DIR_NAME}/${file.name}"))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
            "日志已打包到 ${archive.absolutePath}"
        } catch (e: Exception) {
            logDebug("日志打包失败: ${e.message}")
            "日志打包失败"
        }
    }
}

fun logDebug(message: String) {
    Log.d(LOG_TAG, message)
    LogCollector.append(null, "D", message)
}

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
    val trayUidHex: String = "",
    val remainingPercent: Float = DEFAULT_REMAINING_PERCENT.toFloat(),
    val remainingGrams: Int = 0,
    val totalWeightGrams: Int = 0,
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

data class InventoryItem(
    val trayUid: String,
    val materialType: String,
    val colorName: String,
    val colorCode: String,
    val colorType: String,
    val colorValues: List<String>,
    val remainingPercent: Float,
    val remainingGrams: Int? = null
)

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var uiState by mutableStateOf(NfcUiState(status = "正在等待NFC..."))
    private var filamentDbHelper: FilamentDbHelper? = null
    private var voiceEnabled by mutableStateOf(false)
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var ttsLanguageReady by mutableStateOf(false)
    private var lastSpokenKey: String? = null
    private var shouldNavigateToReader by mutableStateOf(false)

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        logEvent("收到NFC标签回调")
        val result = readTag(tag)
        runOnUiThread {
            uiState = result
            shouldNavigateToReader = true
            maybeSpeakResult(result)
        }
    }

    /**
     * 检查并更新配置文件
     */
    private fun checkAndUpdateConfig() {
        GlobalScope.launch(Dispatchers.IO) {
            com.m0h31h31.bamburfidreader.utils.ConfigManager.checkAndUpdateConfig(
                this@MainActivity,
                object : kotlin.jvm.functions.Function2<String, kotlin.jvm.functions.Function0<Unit>, Unit> {
                    override fun invoke(message: String, updateAction: kotlin.jvm.functions.Function0<Unit>) {
                        runOnUiThread {
                            val builder = android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("配置更新")
                                .setMessage(message)
                            
                            // 检查是否是版本更新提示
                            if (message == "版本更新请到原地址下载") {
                                // 版本更新提示只设置取消按钮
                                builder.setNegativeButton("取消", null)
                            } else {
                                // 颜色配置更新需要确认按钮
                                builder.setPositiveButton("确认") { _, _ ->
                                    updateAction.invoke()
                                    // 提示更新结果
                                    android.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("更新结果")
                                        .setMessage("颜色配置更新成功")
                                        .setPositiveButton("确定", null)
                                        .show()
                                }
                                .setNegativeButton("取消", null)
                            }
                            builder.show()
                        }
                    }
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LogCollector.init(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        filamentDbHelper = FilamentDbHelper(this)
        filamentDbHelper?.let { syncFilamentDatabase(this, it) }
        if (voiceEnabled) {
            initTts()
        }
        uiState = NfcUiState(status = initialStatus())
        logEvent("应用启动")
        logDeviceInfo()
        
        // 检查并更新配置文件
        checkAndUpdateConfig()
        
        setContent {
            BambuRfidReaderTheme {
                AppNavigation(
                    state = uiState,
                    voiceEnabled = voiceEnabled,
                    ttsReady = ttsReady,
                    ttsLanguageReady = ttsLanguageReady,
                    onVoiceEnabledChange = {
                        voiceEnabled = it
                        if (!it) {
                            tts?.stop()
                        } else if (!ttsReady) {
                            initTts()
                        }
                    },
                    onRemainingChange = { trayUid, percent, grams ->
                        updateTrayRemaining(trayUid, percent, grams)
                    },
                    dbHelper = filamentDbHelper,
                    onBackupDatabase = { backupDatabase() },
                    onImportDatabase = { importDatabase() },
                    onResetDatabase = { resetDatabase() },
                    navigateToReader = shouldNavigateToReader
                )
                // 重置导航标志
                if (shouldNavigateToReader) {
                    shouldNavigateToReader = false
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        logEvent("应用进入前台")
        if (voiceEnabled && !ttsReady) {
            initTts()
        }
        val adapter = nfcAdapter
        if (adapter == null) {
            uiState = uiState.copy(status = uiString(R.string.status_device_no_nfc))
            logEvent("设备不支持 NFC")
            return
        }
        if (!adapter.isEnabled) {
            uiState = uiState.copy(status = uiString(R.string.status_nfc_disabled))
            logEvent("NFC 未开启")
            return
        }
        val pm = packageManager
        val supportsA = pm.hasSystemFeature("android.hardware.nfc.a")
        val supportsB = pm.hasSystemFeature("android.hardware.nfc.b")
        val supportsF = pm.hasSystemFeature("android.hardware.nfc.f")
        val supportsV = pm.hasSystemFeature("android.hardware.nfc.v")
        if (!supportsA) {
            logEvent("设备未声明 NFC-A，可能影响 MIFARE Classic 读取")
        }
        val flags = NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(
            this,
            readerCallback,
            flags,
            null
        )
        uiState = uiState.copy(status = uiString(R.string.status_waiting_tag))
        logEvent("已启用 NFC 读卡模式")
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        logEvent("应用进入后台，已关闭 NFC 读卡模式")
    }

    override fun onDestroy() {
        super.onDestroy()
        logEvent("应用退出，准备打包日志")
        filamentDbHelper?.close()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        ttsLanguageReady = false
        val result = LogCollector.packageLogs(this)
        logDebug(result)
        LogCollector.append(this, "I", result)
    }

    private fun initialStatus(): String {
        val adapter = nfcAdapter
        return when {
            adapter == null -> uiString(R.string.status_device_no_nfc)
            !adapter.isEnabled -> uiString(R.string.status_nfc_disabled)
            else -> uiString(R.string.status_waiting_tag)
        }
    }

    private fun updateTrayRemaining(trayUidHex: String, percent: Float, grams: Int? = null) {
        if (trayUidHex.isBlank()) {
            return
        }
        val updatedPercent = percent.coerceIn(0f, 100f)
        val dbHelper = filamentDbHelper
        val db = dbHelper?.writableDatabase
        if (db != null) {
            dbHelper.upsertTrayRemaining(db, trayUidHex, updatedPercent, grams)
        }
        if (uiState.trayUidHex == trayUidHex) {
            uiState = uiState.copy(
                remainingPercent = updatedPercent,
                remainingGrams = grams ?: uiState.remainingGrams
            )
        }
        logDebug("更新耗材余量: $trayUidHex -> $updatedPercent%")
        LogCollector.append(this, "I", "更新耗材余量: $trayUidHex -> $updatedPercent%")
    }

    private fun logEvent(message: String) {
        logDebug(message)
    }

    private fun uiString(@StringRes id: Int, vararg args: Any): String {
        return if (args.isEmpty()) getString(id) else getString(id, *args)
    }

    private fun logDeviceInfo() {
        logDebug(
            "设备信息: 品牌=${Build.BRAND}, 型号=${Build.MODEL}, 制造商=${Build.MANUFACTURER}"
        )
        logDebug(
            "系统信息: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                    "构建=${Build.DISPLAY}, 指纹=${Build.FINGERPRINT}"
        )
        logDebug("内核版本: ${System.getProperty("os.version").orEmpty()}")
        val pm = packageManager
        val hasNfc = pm.hasSystemFeature("android.hardware.nfc")
        val hasHce = pm.hasSystemFeature("android.hardware.nfc.hce")
        val hasNfcF = pm.hasSystemFeature("android.hardware.nfc.hcef")
        val hasNfcA = pm.hasSystemFeature("android.hardware.nfc.a")
        val hasNfcB = pm.hasSystemFeature("android.hardware.nfc.b")
        val hasNfcV = pm.hasSystemFeature("android.hardware.nfc.v")
        val hasNfcU = pm.hasSystemFeature("android.hardware.nfc.uicc")
        logDebug(
            "NFC硬件特性: NFC=$hasNfc, HCE=$hasHce, HCEF=$hasNfcF, A=$hasNfcA, B=$hasNfcB, V=$hasNfcV, UICC=$hasNfcU"
        )
        val adapter = nfcAdapter
        if (adapter == null) {
            logDebug("NFC适配器: 未找到")
        } else {
            logDebug("NFC适配器: 已找到, 状态=${if (adapter.isEnabled) "开启" else "关闭"}")
        }
    }

    private fun backupDatabase(): String {
        val dbFile = getDatabasePath(FILAMENT_DB_NAME)
        if (!dbFile.exists()) {
            return "数据库文件不存在"
        }
        val externalDir = getExternalFilesDir(null)
        if (externalDir == null) {
            return "无法访问存储目录"
        }
        val backupFile = File(externalDir, "filaments_backup.db")
        return try {
            dbFile.copyTo(backupFile, overwrite = true)
            "数据库备份成功"
        } catch (e: Exception) {
            logDebug("数据库备份失败: ${e.message}")
            "数据库备份失败"
        }
    }

    private fun importDatabase(): String {
        val externalDir = getExternalFilesDir(null)
        if (externalDir == null) {
            return "无法访问存储目录"
        }
        val backupFile = File(externalDir, "filaments_backup.db")
        if (!backupFile.exists()) {
            return "未找到备份文件"
        }
        val dbFile = getDatabasePath(FILAMENT_DB_NAME)
        return try {
            filamentDbHelper?.close()
            backupFile.copyTo(dbFile, overwrite = true)
            filamentDbHelper?.writableDatabase
            "数据库导入成功"
        } catch (e: Exception) {
            logDebug("数据库导入失败: ${e.message}")
            "数据库导入失败"
        }
    }

    private fun resetDatabase(): String {
        val dbFile = getDatabasePath(FILAMENT_DB_NAME)
        return try {
            filamentDbHelper?.close()
            if (dbFile.exists()) {
                dbFile.delete()
            }
            filamentDbHelper = FilamentDbHelper(this)
            filamentDbHelper?.let { syncFilamentDatabase(this, it) }
            "数据库重置成功"
        } catch (e: Exception) {
            logDebug("数据库重置失败: ${e.message}")
            "数据库重置失败"
        }
    }

    private fun initTts() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        ttsLanguageReady = false
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val locales = listOf(
                    Locale.SIMPLIFIED_CHINESE,
                    Locale.CHINESE,
                    Locale("zh", "CN"),
                    Locale.getDefault()
                )
                for (locale in locales) {
                    val result = tts?.setLanguage(locale)
                    if (result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        ttsLanguageReady = true
                        logDebug("语音语言可用: $locale")
                        break
                    }
                }
                if (!ttsLanguageReady) {
                    logDebug("没有可用的语音语言")
                }
                logDebug("语音引擎初始化完成: $ttsReady，语言就绪: $ttsLanguageReady")
            } else {
                ttsReady = false
                ttsLanguageReady = false
                logDebug("语音引擎初始化失败")
            }
        }
    }

    private fun maybeSpeakResult(state: NfcUiState) {
        if (!voiceEnabled) {
            return
        }
        if (!ttsReady) {
            return
        }
        val type = state.displayType.trim()
        val colorName = state.displayColorName.trim()
        if (type.isBlank() && colorName.isBlank()) {
            return
        }
        val key = listOf(
            state.uidHex,
            type,
            colorName,
            state.displayColorCode,
            state.displayColorType,
            state.displayColors.joinToString(separator = ",")
        ).joinToString(separator = "|")
        if (key == lastSpokenKey) {
            return
        }
        lastSpokenKey = key
        val parts = ArrayList<String>()
        if (type.isNotBlank()) {
            val speechType = buildSpeechMaterialName(type)
            parts.add("耗材类型 $speechType")
        }
        if (colorName.isNotBlank()) {
            parts.add("颜色 $colorName")
        }
        val message = parts.joinToString(separator = "，")
        if (message.isNotBlank()) {
            logDebug("语音播报内容: $message")
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "scan_result")
        }
    }

    private fun buildSpeechMaterialName(raw: String): String {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return ""
        }
        return words.joinToString("、") { word ->
            if (isAllUppercaseWord(word)) {
                val letters = word.filter { it.isLetterOrDigit() }
                if (letters.isBlank()) {
                    word
                } else {
                    letters.map { it.lowercaseChar().toString() }.joinToString("、")
                }
            } else {
                word
            }
        }
    }

    private fun isAllUppercaseWord(word: String): Boolean {
        var hasLetter = false
        for (ch in word) {
            if (ch in 'a'..'z') {
                return false
            }
            if (ch in 'A'..'Z') {
                hasLetter = true
            }
        }
        return hasLetter
    }

    private fun readTag(tag: Tag): NfcUiState {
        val uid = tag.id ?: return NfcUiState(status = uiString(R.string.status_uid_missing))
        val uidHex = uid.toHex()
        logDebug("UID: $uidHex")
        LogCollector.append(applicationContext, "I", "开始读取标签 UID: $uidHex")
        logDebug("Tag tech list: ${tag.techList.joinToString()}")

        val keysA = deriveKeys(uid, INFO_A)
        val keysB = deriveKeys(uid, INFO_B)
        val keyA0 = keysA.getOrNull(0)
        val keyB0 = keysB.getOrNull(0)
        val keyA1 = keysA.getOrNull(1)
        val keyB1 = keysB.getOrNull(1)
        val keyA2 = keysA.getOrNull(2)
        val keyB2 = keysB.getOrNull(2)
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
        logDebug(
            "密钥A0: $keyA0Hex, 密钥B0: $keyB0Hex, 密钥A1: $keyA1Hex, 密钥B1: $keyB1Hex, 密钥A3: ${
                keyA3?.toHex().orEmpty()
            }, 密钥B3: ${keyB3?.toHex().orEmpty()}, 密钥A4: $keyA4Hex, 密钥B4: $keyB4Hex"
        )

        val mifare = MifareClassic.get(tag)
            ?: return NfcUiState(
                status = uiString(R.string.error_mifare_unsupported),
                uidHex = uidHex,
                keyA0Hex = keyA0Hex,
                keyB0Hex = keyB0Hex,
                keyA1Hex = keyA1Hex,
                keyB1Hex = keyB1Hex,
                error = uiString(R.string.error_mifare_unsupported)
            )

        return try {
            mifare.connect()
            LogCollector.append(applicationContext, "I", "已连接 MIFARE Classic")
            val blockData = MutableList<ByteArray?>(8) { null }
            val rawBlocks = MutableList<ByteArray?>(18) { null }
            var block12: ByteArray? = null
            var block16: ByteArray? = null
            val errors = ArrayList<String>(2)

            val sector0 = readSector(mifare, 0, keyA0, keyB0)
            if (sector0.blocks.isNotEmpty()) {
                logDebug("扇区0读取成功，读取到 ${sector0.blocks.size} 个区块")
                LogCollector.append(applicationContext, "I", "扇区0读取成功")
                sector0.blocks.forEachIndexed { index, data ->
                    blockData[index] = data
                    if (index < rawBlocks.size) {
                        rawBlocks[index] = data
                    }
                }
            } else if (sector0.error.isNotBlank()) {
                logDebug("扇区0读取失败: ${sector0.error}")
                LogCollector.append(applicationContext, "W", "扇区0读取失败: ${sector0.error}")
                errors.add(sector0.error)
            }

            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                // Ignore.
            }
            val sector1 = readSector(mifare, 1, keyA1, keyB1)
            if (sector1.blocks.isNotEmpty()) {
                logDebug("扇区1读取成功，读取到 ${sector1.blocks.size} 个区块")
                LogCollector.append(applicationContext, "I", "扇区1读取成功")
                sector1.blocks.forEachIndexed { index, data ->
                    blockData[index + 4] = data
                    val rawIndex = index + 4
                    if (rawIndex < rawBlocks.size) {
                        rawBlocks[rawIndex] = data
                    }
                }
            } else if (sector1.error.isNotBlank()) {
                logDebug("扇区1读取失败: ${sector1.error}")
                LogCollector.append(applicationContext, "W", "扇区1读取失败: ${sector1.error}")
                errors.add(sector1.error)
            }

            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                // Ignore.
            }
            val sector2 = readSector(mifare, 2, keyA2, keyB2)
            if (sector2.blocks.isNotEmpty()) {
                logDebug("扇区2认证成功，读取到 ${sector2.blocks.size} 个区块")
                LogCollector.append(applicationContext, "I", "扇区2读取成功")
                sector2.blocks.forEachIndexed { index, data ->
                    val rawIndex = index + 8
                    if (rawIndex < rawBlocks.size) {
                        rawBlocks[rawIndex] = data
                    }
                }
            } else if (sector2.error.isNotBlank()) {
                logDebug("扇区2认证失败: ${sector2.error}")
                LogCollector.append(applicationContext, "W", "扇区2读取失败: ${sector2.error}")
            }

            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                // Ignore.
            }
            val sector3 = readSector(mifare, 3, keyA3, keyB3)
            if (sector3.blocks.isNotEmpty()) {
                block12 = sector3.blocks.getOrNull(0)
                sector3.blocks.forEachIndexed { index, data ->
                    val rawIndex = index + 12
                    if (rawIndex < rawBlocks.size) {
                        rawBlocks[rawIndex] = data
                    }
                }
                if (block12 != null) {
                    logDebug("扇区3读取区块12: ${block12?.toHex().orEmpty()}")
                    LogCollector.append(applicationContext, "I", "扇区3读取成功")
                } else {
                    logDebug("扇区3未读取到区块12")
                }
            } else if (sector3.error.isNotBlank()) {
                logDebug("扇区3读取失败: ${sector3.error}")
                LogCollector.append(applicationContext, "W", "扇区3读取失败: ${sector3.error}")
            }

            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                // Ignore.
            }
            val sector4 = readSector(mifare, 4, keyA4, keyB4)
            if (sector4.blocks.isNotEmpty()) {
                block16 = sector4.blocks.getOrNull(0)
                sector4.blocks.forEachIndexed { index, data ->
                    val rawIndex = index + 16
                    if (rawIndex < rawBlocks.size) {
                        rawBlocks[rawIndex] = data
                    }
                }
                if (block16 != null) {
                    logDebug("扇区4读取区块16: ${block16?.toHex().orEmpty()}")
                    LogCollector.append(applicationContext, "I", "扇区4读取成功")
                } else {
                    logDebug("扇区4未读取到区块16")
                }
            } else if (sector4.error.isNotBlank()) {
                logDebug("扇区4读取失败: ${sector4.error}")
                LogCollector.append(applicationContext, "W", "扇区4读取失败: ${sector4.error}")
            }

            rawBlocks.forEachIndexed { index, data ->
                val hex = data?.toHex().orEmpty()
                if (hex.isNotBlank()) {
                    logDebug("原始区块 $index: $hex")
                } else {
                    logDebug("原始区块 $index: <空>")
                }
            }

            val trayUidHex = rawBlocks.getOrNull(9)?.toHex().orEmpty()
            var remainingPercent = DEFAULT_REMAINING_PERCENT.toFloat()
            var remainingGrams = 0
            if (trayUidHex.isNotBlank()) {
                val dbHelper = filamentDbHelper
                val db = dbHelper?.writableDatabase
                if (db != null) {
                    val stored = dbHelper.getTrayRemainingPercent(db, trayUidHex)
                    val storedGrams = dbHelper.getTrayRemainingGrams(db, trayUidHex)
                    remainingPercent = stored ?: DEFAULT_REMAINING_PERCENT.toFloat()
                    // 对于新刷的耗材，使用从NFC标签中读取的totalWeightGrams作为初始克重
                    remainingGrams = storedGrams ?: 0
                    dbHelper.upsertTrayRemaining(db, trayUidHex, remainingPercent, remainingGrams)
                }
                logDebug("??UID(??9): $trayUidHex, ??: $remainingPercent%")
                LogCollector.append(applicationContext, "I", "??UID(??9): $trayUidHex, ??: $remainingPercent%")
            } else {
                logDebug("??????9??UID")
                LogCollector.append(applicationContext, "W", "??????9??UID")
            }

            val blockHexes = blockData.map { data -> data?.toHex().orEmpty() }
            blockHexes.forEachIndexed { index, value ->
                if (value.isNotBlank()) {
                    logDebug("区块 $index: $value")
                }
            }
            val parsedBlockData = parseBlocks(blockData, block12, block16)
            val totalWeightGrams = extractWeightGrams(parsedBlockData.fields)
            // 对于新刷的耗材，如果克重为0且从NFC标签中读取到了克重，则使用NFC读取的克重
            if (remainingGrams == 0 && totalWeightGrams > 0) {
                remainingGrams = totalWeightGrams
                // 更新数据库中的克重值
                if (trayUidHex.isNotBlank()) {
                    val dbHelper = filamentDbHelper
                    val db = dbHelper?.writableDatabase
                    if (db != null) {
                        dbHelper.upsertTrayRemaining(db, trayUidHex, remainingPercent, remainingGrams)
                    }
                }
            }
            val displayData = buildDisplayData(parsedBlockData, filamentDbHelper)
            parsedBlockData.fields.forEach { field ->
                logDebug("解析字段: ${field.label}=${field.value}")
            }
            if (parsedBlockData.colorValues.isNotEmpty()) {
                logDebug("读取颜色值: ${parsedBlockData.colorValues.joinToString(separator = ",")}")
            }
            logDebug(
                "展示数据: 类型=${displayData.type}, 颜色名=${displayData.colorName}, 颜色代码=${displayData.colorCode}, 颜色类型=${displayData.colorType}, 颜色列表=${
                    displayData.colorValues.joinToString(
                        separator = ","
                    )
                }"
            )
            if (trayUidHex.isNotBlank()) {
                val dbHelper = filamentDbHelper
                val db = dbHelper?.writableDatabase
                if (db != null) {
                    // 获取filament_id
                    val filamentId = dbHelper.getFilamentId(
                        db,
                        parsedBlockData.materialId,
                        displayData.colorCode
                    )
                    dbHelper.upsertTrayInventory(
                        db,
                        trayUidHex,
                        remainingPercent,
                        remainingGrams,
                        filamentId
                    )
                }
            }
            val status = when {
                errors.isEmpty() -> uiString(R.string.status_read_success)
                blockHexes.any { it.isNotBlank() } -> uiString(R.string.status_read_partial)
                else -> uiString(R.string.status_read_failed)
            }
            if (errors.isNotEmpty()) {
                logDebug("读取错误: ${errors.joinToString(separator = "; ")}")
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
                trayUidHex = trayUidHex,
                remainingPercent = remainingPercent,
                remainingGrams = remainingGrams,
                totalWeightGrams = totalWeightGrams,
                error = errors.joinToString(separator = "; ")
            )
        } catch (e: Exception) {
            logDebug("读取异常: ${e.message}")
            LogCollector.append(applicationContext, "E", "读取异常: ${e.message}")
            NfcUiState(
                status = uiString(R.string.status_read_failed),
                uidHex = uidHex,
                keyA0Hex = keyA0Hex,
                keyB0Hex = keyB0Hex,
                keyA1Hex = keyA1Hex,
                keyB1Hex = keyB1Hex,
                error = e.message ?: uiString(R.string.error_read_exception)
            )
        } finally {
            try {
                mifare.close()
                LogCollector.append(applicationContext, "I", "已断开 MIFARE Classic")
            } catch (_: IOException) {
                // Ignore close errors.
            }
        }
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
        logDebug(
            "区块16多色数据: 标识=0x%04X 颜色数量=%d 颜色=%s".format(
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
        // "Block 2 Filament Type" to "耗材类型",
        // "Block 4 Detailed Filament Type" to "详细耗材类型",
        "Block 5 Spool Weight" to "耗材重量",
        "Block 5 Filament Diameter" to "耗材直径",
        "Block 6 Drying Temperature" to "烘干温度",
        "Block 6 Drying Time" to "烘干时间",
        // "Block 6 Bed Temperature Type" to "热床温度类型",
        // "Block 6 Bed Temperature" to "热床温度",
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

private data class FilamentTypeMappingEntry(
    val baseType: String,
    val specificType: String
)

internal fun syncFilamentDatabase(context: Context, dbHelper: FilamentDbHelper) {
    // 同步filaments_color_codes.json
    val colorSource = readFilamentJsonFromExternal(context) ?: return
    logDebug("配置文件更新时间: ${colorSource.lastModified}")
    val colorCacheFile = File(context.cacheDir, FILAMENT_JSON_NAME)
    try {
        colorCacheFile.writeText(colorSource.jsonText, Charsets.UTF_8)
    } catch (_: IOException) {
        // Ignore cache write failures.
    }

    // 同步filaments_type_mapping.json
    val typeSource = readFilamentTypeMappingFromExternal(context) ?: return
    logDebug("耗材类型映射文件更新时间: ${typeSource.lastModified}")
    val typeCacheFile = File(context.cacheDir, FILAMENTS_TYPE_MAPPING_FILE)
    try {
        typeCacheFile.writeText(typeSource.jsonText, Charsets.UTF_8)
    } catch (_: IOException) {
        // Ignore cache write failures.
    }

    val db = dbHelper.writableDatabase
    val colorLastModifiedValue = colorSource.lastModified.toString()
    val storedColorVersion = dbHelper.getMetaValue(db, FILAMENT_META_KEY_LAST_MODIFIED)
    val currentLocale = Locale.getDefault().language.lowercase(Locale.US)
    val storedLocale = dbHelper.getMetaValue(db, FILAMENT_META_KEY_LOCALE)
    
    // 检查是否需要更新
    if (storedColorVersion == colorLastModifiedValue && storedLocale == currentLocale) {
        logDebug("配置文件未变化，跳过更新")
        return
    }

    val entries = parseFilamentEntries(colorSource.jsonText)
    val typeEntries = parseFilamentTypeMappingEntries(typeSource.jsonText)
    db.beginTransaction()
    try {
        // 清空并重新写入filaments表
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
        
        // 清空并重新写入filament_type_mapping表
        db.delete(FILAMENT_TYPE_MAPPING_TABLE, null, null)
        typeEntries.forEach { entry ->
            values.clear()
            values.put("base_type", entry.baseType)
            values.put("specific_type", entry.specificType)
            db.insertWithOnConflict(
                FILAMENT_TYPE_MAPPING_TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        
        dbHelper.setMetaValue(db, FILAMENT_META_KEY_LAST_MODIFIED, colorLastModifiedValue)
        dbHelper.setMetaValue(db, FILAMENT_META_KEY_LOCALE, currentLocale)
        db.setTransactionSuccessful()
        logDebug("配置数据写入完成: ${entries.size} 个颜色配置, ${typeEntries.size} 个类型映射")
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

private fun readFilamentTypeMappingFromExternal(context: Context): FilamentJsonSource? {
    val externalDir = context.getExternalFilesDir(null) ?: return null
    val externalFile = File(externalDir, FILAMENTS_TYPE_MAPPING_FILE)
    if (!externalFile.exists()) {
        try {
            context.assets.open(FILAMENTS_TYPE_MAPPING_FILE).use { input ->
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
    val language = Locale.getDefault().language.lowercase(Locale.US)
    for (i in 0 until data.length()) {
        val item = data.optJSONObject(i) ?: continue
        val filaId = item.optString("fila_id")
        if (filaId.isBlank()) {
            continue
        }
        val colorNameZh = resolveColorName(item.optJSONObject("fila_color_name"), language)
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

private fun parseFilamentTypeMappingEntries(jsonText: String): List<FilamentTypeMappingEntry> {
    val root = try {
        JSONObject(jsonText)
    } catch (_: Exception) {
        return emptyList()
    }
    val entries = ArrayList<FilamentTypeMappingEntry>()
    val keys = root.keys()
    while (keys.hasNext()) {
        val baseType = keys.next()
        val specificTypes = root.optJSONArray(baseType)
        if (specificTypes != null) {
            for (i in 0 until specificTypes.length()) {
                val specificType = specificTypes.optString(i)
                if (specificType.isNotBlank()) {
                    entries.add(
                        FilamentTypeMappingEntry(
                            baseType = baseType,
                            specificType = specificType
                        )
                    )
                }
            }
        }
    }
    return entries
}

private fun resolveColorName(colorNames: JSONObject?, language: String): String {
    if (colorNames == null) {
        return ""
    }
    val normalized = language.lowercase(Locale.US)
    val direct = colorNames.optString(normalized).orEmpty()
    if (direct.isNotBlank()) {
        return direct
    }
    val fallback = colorNames.optString("en").orEmpty()
    if (fallback.isNotBlank()) {
        return fallback
    }
    val zh = colorNames.optString("zh").orEmpty()
    if (zh.isNotBlank()) {
        return zh
    }
    val keys = colorNames.keys()
    if (keys.hasNext()) {
        val firstKey = keys.next()
        return colorNames.optString(firstKey).orEmpty()
    }
    return ""
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



class FilamentDbHelper(context: Context) :
    SQLiteOpenHelper(context, FILAMENT_DB_NAME, null, FILAMENT_DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $FILAMENT_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fila_id TEXT NOT NULL,
                fila_color_code TEXT NOT NULL,
                fila_color_type TEXT,
                fila_type TEXT,
                color_name_zh TEXT,
                color_values TEXT,
                color_count INTEGER,
                UNIQUE (fila_id, fila_color_code)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_filaments_fila_id_color ON $FILAMENT_TABLE (fila_id, color_count)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $FILAMENT_META_TABLE (
                meta_key TEXT PRIMARY KEY,
                value TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS "$TRAY_UID_TABLE" (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tray_uid TEXT UNIQUE NOT NULL,
                remaining_percent REAL NOT NULL,
                remaining_grams INTEGER,
                filament_id INTEGER,
                FOREIGN KEY (filament_id) REFERENCES $FILAMENT_TABLE(id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $FILAMENT_TYPE_MAPPING_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                base_type TEXT NOT NULL,
                specific_type TEXT NOT NULL,
                UNIQUE (base_type, specific_type)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_filament_type_mapping_base_type ON $FILAMENT_TYPE_MAPPING_TABLE (base_type)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS $FILAMENT_TABLE")
            db.execSQL("DROP TABLE IF EXISTS $FILAMENT_META_TABLE")
            db.execSQL("DROP TABLE IF EXISTS \"$TRAY_UID_TABLE\"")
            onCreate(db)
            return
        }
        if (oldVersion < 5) {
            addTrayColumn(db, "material_id", "TEXT")
            addTrayColumn(db, "material_type", "TEXT")
            addTrayColumn(db, "color_name", "TEXT")
            addTrayColumn(db, "color_code", "TEXT")
            addTrayColumn(db, "color_type", "TEXT")
            addTrayColumn(db, "color_values", "TEXT")
        }
        if (oldVersion < 8) {
            addTrayColumn(db, "remaining_grams", "INTEGER")
        }
        if (oldVersion < 7) {
            db.execSQL("DROP TABLE IF EXISTS meta")
            db.execSQL("DROP TABLE IF EXISTS $FILAMENT_META_TABLE")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $FILAMENT_META_TABLE (
                    meta_key TEXT PRIMARY KEY,
                    value TEXT
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 9) {
            // 为filament表添加id字段
            val tempFilamentTable = "${FILAMENT_TABLE}_temp"
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $tempFilamentTable (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fila_id TEXT NOT NULL,
                    fila_color_code TEXT NOT NULL,
                    fila_color_type TEXT,
                    fila_type TEXT,
                    color_name_zh TEXT,
                    color_values TEXT,
                    color_count INTEGER,
                    UNIQUE (fila_id, fila_color_code)
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO $tempFilamentTable (fila_id, fila_color_code, fila_color_type, fila_type, color_name_zh, color_values, color_count) " +
                "SELECT fila_id, fila_color_code, fila_color_type, fila_type, color_name_zh, color_values, color_count FROM $FILAMENT_TABLE"
            )
            db.execSQL("DROP TABLE IF EXISTS $FILAMENT_TABLE")
            db.execSQL("ALTER TABLE $tempFilamentTable RENAME TO $FILAMENT_TABLE")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_filaments_fila_id_color ON $FILAMENT_TABLE (fila_id, color_count)"
            )
            
            // 为filament_inventory表添加id和filament_id字段
            val tempInventoryTable = "${TRAY_UID_TABLE}_temp"
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS "$tempInventoryTable" (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tray_uid TEXT UNIQUE NOT NULL,
                    remaining_percent REAL NOT NULL,
                    remaining_grams INTEGER,
                    filament_id INTEGER,
                    FOREIGN KEY (filament_id) REFERENCES $FILAMENT_TABLE(id)
                )
                """.trimIndent()
            )
            // 这里需要处理数据迁移，将旧表中的数据迁移到新表
            // 由于我们需要通过fila_id和color_code关联到filament表的id，这里需要使用临时方案
            // 实际应用中，可能需要更复杂的数据迁移逻辑
            db.execSQL(
                "INSERT INTO \"$tempInventoryTable\" (tray_uid, remaining_percent, remaining_grams) " +
                "SELECT tray_uid, remaining_percent, remaining_grams FROM \"$TRAY_UID_TABLE\""
            )
            db.execSQL("DROP TABLE IF EXISTS \"$TRAY_UID_TABLE\"")
            db.execSQL("ALTER TABLE \"$tempInventoryTable\" RENAME TO \"$TRAY_UID_TABLE\"")
        }
        if (oldVersion < 10) {
            // 创建filament_type_mapping表
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $FILAMENT_TYPE_MAPPING_TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    base_type TEXT NOT NULL,
                    specific_type TEXT NOT NULL,
                    UNIQUE (base_type, specific_type)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_filament_type_mapping_base_type ON $FILAMENT_TYPE_MAPPING_TABLE (base_type)"
            )
        }
    }

    private fun addTrayColumn(db: SQLiteDatabase, column: String, type: String) {
        try {
            db.execSQL("ALTER TABLE \"$TRAY_UID_TABLE\" ADD COLUMN $column $type")
        } catch (_: Exception) {
            // Ignore duplicate column errors.
        }
    }

    fun getMetaValue(db: SQLiteDatabase, key: String): String? {
        val cursor = db.query(
            FILAMENT_META_TABLE,
            arrayOf("value"),
            "meta_key = ?",
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
        values.put("meta_key", key)
        values.put("value", value)
        db.insertWithOnConflict(
            FILAMENT_META_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getTrayRemainingPercent(db: SQLiteDatabase, trayUid: String): Float? {
        val cursor = db.query(
            TRAY_UID_TABLE,
            arrayOf("remaining_percent"),
            "tray_uid = ?",
            arrayOf(trayUid),
            null,
            null,
            null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getFloat(0) else null
        }
    }

    fun getTrayRemainingGrams(db: SQLiteDatabase, trayUid: String): Int? {
        val cursor = db.query(
            TRAY_UID_TABLE,
            arrayOf("remaining_grams"),
            "tray_uid = ?",
            arrayOf(trayUid),
            null,
            null,
            null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else null
        }
    }

    fun upsertTrayRemaining(
        db: SQLiteDatabase,
        trayUid: String,
        percent: Float,
        grams: Int?
    ) {
        val values = ContentValues()
        values.put("remaining_percent", percent)
        if (grams != null) {
            values.put("remaining_grams", grams)
        }
        val updated = db.update(
            TRAY_UID_TABLE,
            values,
            "tray_uid = ?",
            arrayOf(trayUid)
        )
        if (updated == 0) {
            values.put("tray_uid", trayUid)
            db.insertWithOnConflict(
                TRAY_UID_TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    fun upsertTrayInventory(
        db: SQLiteDatabase,
        trayUid: String,
        remainingPercent: Float,
        remainingGrams: Int?,
        filamentId: Long?
    ) {
        val values = ContentValues()
        values.put("tray_uid", trayUid)
        values.put("remaining_percent", remainingPercent)
        if (remainingGrams != null) {
            values.put("remaining_grams", remainingGrams)
        }
        if (filamentId != null) {
            values.put("filament_id", filamentId)
        }
        db.insertWithOnConflict(
            TRAY_UID_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getFilamentId(db: SQLiteDatabase, filaId: String, filaColorCode: String): Long? {
        val cursor = db.query(
            FILAMENT_TABLE,
            arrayOf("id"),
            "fila_id = ? AND fila_color_code = ?",
            arrayOf(filaId, filaColorCode),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return null
    }

    fun queryInventory(db: SQLiteDatabase, keyword: String): List<InventoryItem> {
        val trimmed = keyword.trim()
        val selection: String?
        val selectionArgs: Array<String>?
        if (trimmed.isBlank()) {
            selection = null
            selectionArgs = null
        } else {
            selection = """
                t.tray_uid LIKE ? OR
                f.fila_id LIKE ? OR
                f.fila_type LIKE ? OR
                f.color_name_zh LIKE ? OR
                f.fila_color_code LIKE ? OR
                f.fila_color_type LIKE ? OR
                f.color_values LIKE ? OR
                CAST(t.remaining_percent AS TEXT) LIKE ?
            """.trimIndent()
            val pattern = "%$trimmed%"
            selectionArgs = Array(8) { pattern }
        }
        val sql = """
            SELECT 
                t.tray_uid,
                COALESCE(f.fila_type, '') as material_type,
                COALESCE(f.color_name_zh, '') as color_name,
                COALESCE(f.fila_color_code, '') as color_code,
                COALESCE(f.fila_color_type, '') as color_type,
                COALESCE(f.color_values, '') as color_values,
                t.remaining_percent,
                t.remaining_grams
            FROM 
                $TRAY_UID_TABLE t
            LEFT JOIN 
                $FILAMENT_TABLE f ON t.filament_id = f.id
            ${if (selection != null) "WHERE $selection" else ""}
            ORDER BY 
                t.tray_uid ASC
        """.trimIndent()
        val cursor = db.rawQuery(sql, selectionArgs)
        cursor.use {
            val results = ArrayList<InventoryItem>()
            while (it.moveToNext()) {
                val colorValues = it.getString(5).orEmpty()
                    .split(",")
                    .map { value -> value.trim() }
                    .filter { value -> value.isNotBlank() }
                results.add(
                    InventoryItem(
                        trayUid = it.getString(0).orEmpty(),
                        materialType = it.getString(1).orEmpty(),
                        colorName = it.getString(2).orEmpty(),
                        colorCode = it.getString(3).orEmpty(),
                        colorType = it.getString(4).orEmpty(),
                        colorValues = colorValues,
                        remainingPercent = it.getFloat(6),
                        remainingGrams = if (!it.isNull(7)) it.getInt(7) else null
                    )
                )
            }
            return results
        }
    }

    fun deleteTrayInventory(db: SQLiteDatabase, trayUid: String) {
        db.delete(
            TRAY_UID_TABLE,
            "tray_uid = ?",
            arrayOf(trayUid)
        )
    }

}

private fun extractWeightGrams(fields: List<ParsedField>): Int {
    val field = fields.firstOrNull { it.label == "Block 5 Spool Weight" } ?: return 0
    val digits = field.value.filter { it.isDigit() }
    return digits.toIntOrNull() ?: 0
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
