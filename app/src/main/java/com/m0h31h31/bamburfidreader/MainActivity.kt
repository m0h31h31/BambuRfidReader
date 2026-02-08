package com.m0h31h31.bamburfidreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.m0h31h31.bamburfidreader.ui.navigation.AppNavigation
import com.m0h31h31.bamburfidreader.ui.theme.BambuRfidReaderTheme
import com.m0h31h31.bamburfidreader.util.normalizeColorValue
import com.m0h31h31.bamburfidreader.utils.ConfigManager
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

private const val LOG_TAG = "BambuRfidReader"
private const val FILAMENT_JSON_NAME = "filaments_color_codes.json"
private const val FILAMENTS_TYPE_MAPPING_FILE = "filaments_type_mapping.json"
private const val FILAMENT_DB_NAME = "filaments.db"
private const val FILAMENT_DB_VERSION = 13
private const val FILAMENT_TABLE = "filaments"
private const val FILAMENT_TYPE_MAPPING_TABLE = "filament_type_mapping"
private const val FILAMENT_META_TABLE = "meta_v2"
private const val FILAMENT_META_KEY_LAST_MODIFIED = "filaments_last_modified"
private const val FILAMENT_META_KEY_LOCALE = "filaments_locale"
private const val TRAY_UID_TABLE = "filament_inventory"
private const val DEFAULT_REMAINING_PERCENT = 100
private const val LOG_DIR_NAME = "logs"
private const val LOG_FILE_NAME = "bambu_rfid.log"

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
    val filaDetailedType: String = "",
    val colorNameZh: String,
    val colorValues: List<String>,
    val colorCount: Int
)

data class ParsedBlockData(
    val fields: List<ParsedField>,
    val materialId: String,
    val filamentType: String = "",
    val detailedFilamentType: String = "",
    val colorValues: List<String>
)

data class InventoryItem(
    val trayUid: String,
    val materialType: String,
    val materialDetailedType: String = "",
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
    private var readAllSectors by mutableStateOf(false) // 控制是否读取全部扇区，默认关闭
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var ttsLanguageReady by mutableStateOf(false)
    private var lastSpokenKey: String? = null
    private var shouldNavigateToReader by mutableStateOf(false)
    // 原始读卡临时缓存：readTag 仅负责写入；解析函数从此读取。
    private var latestRawTagData: RawTagReadData? = null

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
        lifecycleScope.launch(Dispatchers.IO) {
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
                    readAllSectors = readAllSectors,
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
                    onReadAllSectorsChange = {
                        readAllSectors = it
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
    
    /**
     * 保存全部扇区数据到文件
     */
    private fun saveAllSectorsData(uidHex: String, rawBlocks: List<ByteArray?>, sectorKeys: List<Pair<ByteArray?, ByteArray?>>) {
        try {
            // 创建rfid_files目录
            val externalDir = getExternalFilesDir(null)
            if (externalDir == null) {
                logDebug("无法访问存储目录")
                return
            }
            val rfidFilesDir = File(externalDir, "rfid_files")
            if (!rfidFilesDir.exists()) {
                rfidFilesDir.mkdirs()
            }
            
            // 创建以UID命名的文件
            val fileName = "${uidHex}.txt"
            val outputFile = File(rfidFilesDir, fileName)
            
            // 写入数据
            outputFile.bufferedWriter().use { writer ->
                writer.write("RFID Tag UID: $uidHex\n")
                writer.write("==============================\n")
                
                // 写入密钥信息
                writer.write("密钥信息:\n")
                for (sector in 0 until 16) {
                    val keyA = sectorKeys[sector].first
                    val keyB = sectorKeys[sector].second
                    writer.write("  扇区 $sector - 密钥A: ${keyA?.toHex().orEmpty()}, 密钥B: ${keyB?.toHex().orEmpty()}\n")
                }
                writer.write("==============================\n\n")
                
                // 按扇区组织数据
                for (sector in 0 until 16) {
                    writer.write("扇区 $sector:\n")
                    for (block in 0 until 4) {
                        val blockIndex = sector * 4 + block
                        if (blockIndex < rawBlocks.size) {
                            val data = rawBlocks[blockIndex]
                            val hex = data?.toHex().orEmpty()
                            writer.write("  区块 $blockIndex: $hex\n")
                        }
                    }
                    writer.write("\n")
                }
            }
            
            logDebug("全部扇区数据已保存到: ${outputFile.absolutePath}")
            LogCollector.append(this, "I", "全部扇区数据已保存到: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            logDebug("保存扇区数据失败: ${e.message}")
            LogCollector.append(this, "E", "保存扇区数据失败: ${e.message}")
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
                    Locale.Builder().setLanguage("zh").setRegion("CN").build(),
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
        // 第一阶段：仅做读卡，返回原始块数据，不做业务解析。
        val rawResult = NfcTagReader.readRaw(
            tag = tag,
            readAllSectors = readAllSectors,
            logger = ::logDebug,
            appendLog = { level, message -> LogCollector.append(applicationContext, level, message) }
        )

        return when (rawResult) {
            is RawTagReadResult.Success -> {
                // 成功后先缓存到临时变量，解析流程只依赖该临时变量。
                latestRawTagData = rawResult.data
                if (readAllSectors) {
                    // 按配置导出全部扇区原始数据（调试/排障用途）。
                    saveAllSectorsData(
                        uidHex = rawResult.data.uidHex,
                        rawBlocks = rawResult.data.rawBlocks,
                        sectorKeys = rawResult.data.sectorKeys
                    )
                }
                // 第二阶段：独立解析与入库。
                parseLatestRawTagData()
            }

            is RawTagReadResult.Failure -> {
                // 读卡失败直接映射为 UI 状态，不进入解析流程。
                when (rawResult.reason) {
                    RawTagReadFailureReason.UID_MISSING -> NfcUiState(
                        status = uiString(R.string.status_uid_missing)
                    )

                    RawTagReadFailureReason.MIFARE_UNSUPPORTED -> NfcUiState(
                        status = uiString(R.string.error_mifare_unsupported),
                        uidHex = rawResult.uidHex,
                        keyA0Hex = rawResult.keyA0Hex,
                        keyB0Hex = rawResult.keyB0Hex,
                        keyA1Hex = rawResult.keyA1Hex,
                        keyB1Hex = rawResult.keyB1Hex,
                        error = uiString(R.string.error_mifare_unsupported)
                    )

                    RawTagReadFailureReason.EXCEPTION -> NfcUiState(
                        status = uiString(R.string.status_read_failed),
                        uidHex = rawResult.uidHex,
                        keyA0Hex = rawResult.keyA0Hex,
                        keyB0Hex = rawResult.keyB0Hex,
                        keyA1Hex = rawResult.keyA1Hex,
                        keyB1Hex = rawResult.keyB1Hex,
                        error = rawResult.message.ifBlank { uiString(R.string.error_read_exception) }
                    )
                }
            }
        }
    }

    private fun parseLatestRawTagData(): NfcUiState {
        // 解析函数只从临时变量取数据，避免与读卡层耦合。
        val rawData = latestRawTagData ?: return NfcUiState(
            status = uiString(R.string.status_read_failed),
            error = uiString(R.string.error_read_exception)
        )

        // 执行解析 + 入库，返回结构化展示数据。
        val processed = NfcTagProcessor.parseAndPersist(
            rawData = rawData,
            dbHelper = filamentDbHelper,
            defaultRemainingPercent = DEFAULT_REMAINING_PERCENT.toFloat(),
            logger = ::logDebug,
            appendLog = { level, message -> LogCollector.append(applicationContext, level, message) }
        )

        // 依据原始读卡错误与有效块情况，统一生成最终状态文案。
        val status = when {
            rawData.errors.isEmpty() -> uiString(R.string.status_read_success)
            processed.blockHexes.any { it.isNotBlank() } -> uiString(R.string.status_read_partial)
            else -> uiString(R.string.status_read_failed)
        }
        if (rawData.errors.isNotEmpty()) {
            logDebug("读取错误: ${rawData.errors.joinToString(separator = "; ")}")
        }

        return NfcUiState(
            status = status,
            uidHex = rawData.uidHex,
            keyA0Hex = rawData.keyA0Hex,
            keyB0Hex = rawData.keyB0Hex,
            keyA1Hex = rawData.keyA1Hex,
            keyB1Hex = rawData.keyB1Hex,
            blockHexes = processed.blockHexes,
            parsedFields = processed.parsedFields,
            displayType = processed.displayData.type,
            displayColorName = processed.displayData.colorName,
            displayColorCode = processed.displayData.colorCode,
            displayColorType = processed.displayData.colorType,
            displayColors = processed.displayData.colorValues,
            secondaryFields = processed.displayData.secondaryFields,
            trayUidHex = processed.trayUidHex,
            remainingPercent = processed.remainingPercent,
            remainingGrams = processed.remainingGrams,
            totalWeightGrams = processed.totalWeightGrams,
            error = rawData.errors.joinToString(separator = "; ")
        )
    }
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
    val typeLastModifiedValue = typeSource.lastModified.toString()
    val storedColorVersion = dbHelper.getMetaValue(db, FILAMENT_META_KEY_LAST_MODIFIED)
    val storedTypeVersion = dbHelper.getMetaValue(db, "filaments_type_mapping_last_modified")
    val currentLocale = Locale.getDefault().language.lowercase(Locale.US)
    val storedLocale = dbHelper.getMetaValue(db, FILAMENT_META_KEY_LOCALE)
    
    // 检查是否需要更新
    if (storedColorVersion == colorLastModifiedValue && storedTypeVersion == typeLastModifiedValue && storedLocale == currentLocale) {
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
                if (entry is FilamentColorEntry) {
                    // 如果是 FilamentColorEntry，检查是否有 filaDetailedType 字段
                    val detailedType = entry.filaDetailedType
                    if (detailedType.isNotBlank()) {
                        values.put("fila_detailed_type", detailedType)
                    }
                }
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
        dbHelper.setMetaValue(db, "filaments_type_mapping_last_modified", typeLastModifiedValue)
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
                fila_detailed_type TEXT,
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
                total_weight_grams INTEGER,
                filament_id INTEGER,
                material_id TEXT,
                material_type TEXT,
                material_detailed_type TEXT,
                color_name TEXT,
                color_code TEXT,
                color_type TEXT,
                color_values TEXT,
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
                    total_weight_grams INTEGER,
                    filament_id INTEGER,
                    FOREIGN KEY (filament_id) REFERENCES $FILAMENT_TABLE(id)
                )
                """.trimIndent()
            )
            // 这里需要处理数据迁移，将旧表中的数据迁移到新表
            // 由于我们需要通过fila_id和color_code关联到filament表的id，这里需要使用临时方案
            // 实际应用中，可能需要更复杂的数据迁移逻辑
            db.execSQL(
                "INSERT INTO \"$tempInventoryTable\" (tray_uid, remaining_percent, remaining_grams, total_weight_grams) " +
                "SELECT tray_uid, remaining_percent, remaining_grams, total_weight_grams FROM \"$TRAY_UID_TABLE\""
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
        if (oldVersion < 11) {
            // 添加总克重字段
            addTrayColumn(db, "total_weight_grams", "INTEGER")
        }
        if (oldVersion < 12) {
            // 为filament表添加详细耗材类型字段
            try {
                db.execSQL("ALTER TABLE $FILAMENT_TABLE ADD COLUMN fila_detailed_type TEXT")
            } catch (_: Exception) {
                // Ignore duplicate column errors.
            }
        }
        if (oldVersion < 13) {
            // 为filament_inventory表添加详细材料类型字段
            addTrayColumn(db, "material_detailed_type", "TEXT")
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
        grams: Int?,
        totalGrams: Int? = null
    ) {
        val values = ContentValues()
        // 只保留1位小数
        val roundedPercent = Math.round(percent * 10) / 10f
        values.put("remaining_percent", roundedPercent)
        if (grams != null) {
            values.put("remaining_grams", grams)
        }
        if (totalGrams != null) {
            values.put("total_weight_grams", totalGrams)
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
        totalWeightGrams: Int? = null,
        filamentId: Long?,
        materialId: String? = null,
        materialType: String? = null,
        detailedMaterialType: String? = null,
        colorName: String? = null,
        colorCode: String? = null,
        colorType: String? = null,
        colorValues: String? = null
    ) {
        val values = ContentValues()
        values.put("tray_uid", trayUid)
        values.put("remaining_percent", remainingPercent)
        if (remainingGrams != null) {
            values.put("remaining_grams", remainingGrams)
        }
        if (totalWeightGrams != null) {
            values.put("total_weight_grams", totalWeightGrams)
        }
        if (filamentId != null) {
            values.put("filament_id", filamentId)
        }
        if (materialId != null) {
            values.put("material_id", materialId)
        }
        if (materialType != null) {
            values.put("material_type", materialType)
        }
        if (detailedMaterialType != null) {
            values.put("material_detailed_type", detailedMaterialType)
        }
        if (colorName != null) {
            values.put("color_name", colorName)
        }
        if (colorCode != null) {
            values.put("color_code", colorCode)
        }
        if (colorType != null) {
            values.put("color_type", colorType)
        }
        if (colorValues != null) {
            values.put("color_values", colorValues)
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
                tray_uid LIKE ? OR
                material_type LIKE ? OR
                material_detailed_type LIKE ? OR
                color_name LIKE ? OR
                color_code LIKE ? OR
                color_type LIKE ? OR
                color_values LIKE ? OR
                CAST(remaining_percent AS TEXT) LIKE ?
            """.trimIndent()
            val pattern = "%$trimmed%"
            selectionArgs = Array(8) { pattern }
        }
        val sql = """
            SELECT 
                tray_uid,
                material_type,
                material_detailed_type,
                color_name,
                color_code,
                color_type,
                color_values,
                remaining_percent,
                remaining_grams
            FROM 
                "$TRAY_UID_TABLE"
            ${if (selection != null) "WHERE $selection" else ""}
            ORDER BY 
                tray_uid ASC
        """.trimIndent()
        val cursor = db.rawQuery(sql, selectionArgs)
        cursor.use {
            val results = ArrayList<InventoryItem>()
            while (it.moveToNext()) {
                val colorValues = it.getString(6).orEmpty()
                    .split(",")
                    .map { value -> value.trim() }
                    .filter { value -> value.isNotBlank() }
                results.add(
                    InventoryItem(
                        trayUid = it.getString(0).orEmpty(),
                        materialType = it.getString(1).orEmpty(),
                        materialDetailedType = it.getString(2).orEmpty(),
                        colorName = it.getString(3).orEmpty(),
                        colorCode = it.getString(4).orEmpty(),
                        colorType = it.getString(5).orEmpty(),
                        colorValues = colorValues,
                        remainingPercent = it.getFloat(7),
                        remainingGrams = if (!it.isNull(8)) it.getInt(8) else null
                    )
                )
            }
            return results
        }
    }
    
    /**
     * 获取filament_inventory库的全部数据，用于数据页面显示
     */
    fun getAllInventory(db: SQLiteDatabase): List<InventoryItem> {
        val sql = """
            SELECT 
                tray_uid,
                material_type,
                material_detailed_type,
                color_name,
                color_code,
                color_type,
                color_values,
                remaining_percent,
                remaining_grams
            FROM 
                "$TRAY_UID_TABLE"
            ORDER BY 
                tray_uid ASC
        """.trimIndent()
        val cursor = db.rawQuery(sql, null)
        cursor.use {
            val results = ArrayList<InventoryItem>()
            while (it.moveToNext()) {
                val colorValues = it.getString(6).orEmpty()
                    .split(",")
                    .map { value -> value.trim() }
                    .filter { value -> value.isNotBlank() }
                results.add(
                    InventoryItem(
                        trayUid = it.getString(0).orEmpty(),
                        materialType = it.getString(1).orEmpty(),
                        materialDetailedType = it.getString(2).orEmpty(),
                        colorName = it.getString(3).orEmpty(),
                        colorCode = it.getString(4).orEmpty(),
                        colorType = it.getString(5).orEmpty(),
                        colorValues = colorValues,
                        remainingPercent = it.getFloat(7),
                        remainingGrams = if (!it.isNull(8)) it.getInt(8) else null
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

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02X".format(Locale.US, it) }
