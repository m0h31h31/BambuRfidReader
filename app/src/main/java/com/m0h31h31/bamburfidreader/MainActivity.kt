package com.m0h31h31.bamburfidreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
private const val SHARE_BUNDLE_ZIP_NAME = "rfid_data.zip"
private const val SHARE_EXTRACT_MARKER_FILE = ".bundle_extracted"
private const val SHARE_IMPORT_ZIP_MIME = "application/zip"
private const val WRITE_KEY_LENGTH_BYTES = 6
private const val WRITE_SECTOR_COUNT = 16
private const val RW_AUTH_RETRY_COUNT = 2
private const val RW_BLOCK_RETRY_COUNT = 1
private const val WRITE_RESUME_MAX_ATTEMPTS = 3
private val WRITE_HKDF_SALT = byteArrayOf(
    0x9a.toByte(), 0x75.toByte(), 0x9c.toByte(), 0xf2.toByte(),
    0xc4.toByte(), 0xf7.toByte(), 0xca.toByte(), 0xff.toByte(),
    0x22.toByte(), 0x2c.toByte(), 0xb9.toByte(), 0x76.toByte(),
    0x9b.toByte(), 0x41.toByte(), 0xbc.toByte(), 0x96.toByte()
)
private val WRITE_INFO_A = "RFID-A\u0000".toByteArray(Charsets.US_ASCII)
private val WRITE_INFO_B = "RFID-B\u0000".toByteArray(Charsets.US_ASCII)

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

data class ShareTagItem(
    val relativePath: String,
    val fileName: String,
    val sourceUid: String,
    val trayUid: String,
    val materialType: String,
    val colorUid: String,
    val colorName: String,
    val colorType: String,
    val colorValues: List<String>,
    val rawBlocks: List<ByteArray?>
)

private data class WriteResumePoint(
    val sector: Int,
    val blockOffset: Int
)

private enum class WritePrecheckAction {
    START_FROM_BEGINNING,
    RESUME_FROM_POINT,
    ALREADY_MATCHED,
    BLOCKED_CONFLICT,
    BLOCKED_UNREADABLE
}

private data class WritePrecheckResult(
    val action: WritePrecheckAction,
    val resumePoint: WriteResumePoint = WriteResumePoint(0, 0),
    val message: String = ""
)

class MainActivity : ComponentActivity() {
    private enum class FeedbackTone {
        SUCCESS,
        FAILURE
    }

    private var nfcAdapter: NfcAdapter? = null
    private var uiState by mutableStateOf(NfcUiState(status = "正在等待NFC..."))
    private var filamentDbHelper: FilamentDbHelper? = null
    private var voiceEnabled by mutableStateOf(false)
    private var readAllSectors by mutableStateOf(false) // 控制是否读取全部扇区，默认关闭
    private var saveKeysToFile by mutableStateOf(false) // 控制是否额外导出秘钥文件
    private var forceOverwriteImport by mutableStateOf(false) // 控制导入标签包时是否覆盖同UID文件
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var ttsLanguageReady by mutableStateOf(false)
    private var lastSpokenKey: String? = null
    private var shouldNavigateToReader by mutableStateOf(false)
    private var shouldNavigateToTag by mutableStateOf(false)
    private var tagPreselectedFileName by mutableStateOf<String?>(null)
    // 原始读卡临时缓存：readTag 仅负责写入；解析函数从此读取。
    private var latestRawTagData: RawTagReadData? = null
    private var shareTagItems by mutableStateOf<List<ShareTagItem>>(emptyList())
    private var writeStatusMessage by mutableStateOf("")
    private var pendingWriteItem by mutableStateOf<ShareTagItem?>(null)
    private var pendingVerifyItem by mutableStateOf<ShareTagItem?>(null)
    private var shareLoading by mutableStateOf(false)
    private var shareRefreshStatusMessage by mutableStateOf("")
    private var shareRefreshStatusClearJob: Job? = null
    private var miscStatusMessage by mutableStateOf("")
    // 防止 readerCallback 并发触发导致 "Close other technology first!"。
    private val readingInProgress = AtomicBoolean(false)
    // 防止共享目录重复并发扫描。
    private val shareLoadingInProgress = AtomicBoolean(false)
    private var toneGenerator: ToneGenerator? = null
    private val importTagPackageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                miscStatusMessage = "已取消选择标签包"
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val message = importTagPackageFromZipUri(uri)
                withContext(Dispatchers.Main) {
                    miscStatusMessage = message
                    if (message.contains("成功")) {
                        refreshShareTagItemsAsync()
                    }
                }
            }
        }

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        if (!readingInProgress.compareAndSet(false, true)) {
            logEvent("读卡请求被忽略：上一次读卡尚未完成")
            return@ReaderCallback
        }
        logEvent("收到NFC标签回调")
        try {
            runOnUiThread {
                if (pendingWriteItem != null) {
                    writeStatusMessage = "正在写入，请保持标签稳定贴合，切勿移动..."
                } else if (pendingVerifyItem != null) {
                    writeStatusMessage = "正在校验，请保持标签稳定贴合，切勿移动..."
                }
            }
            if (pendingWriteItem != null) {
                val targetItem = pendingWriteItem
                val result = if (targetItem != null) {
                    writeTagFromDump(tag, targetItem)
                } else {
                    "写入任务为空"
                }
                runOnUiThread {
                    writeStatusMessage = result
                    if (result.contains("成功")) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                        pendingWriteItem = null
                        pendingVerifyItem = targetItem
                        writeStatusMessage = "写入已完成，请移开标签后再次贴卡进行校验。"
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                }
            } else if (pendingVerifyItem != null) {
                val targetItem = pendingVerifyItem
                val result = if (targetItem != null) {
                    verifyTagAgainstDump(tag, targetItem)
                } else {
                    "校验任务为空"
                }
                runOnUiThread {
                    writeStatusMessage = result
                    if (result.contains("成功")) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                        pendingVerifyItem = null
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                }
            } else {
                val result = readTag(tag)
                runOnUiThread {
                    uiState = result
                    shouldNavigateToReader = true
                    maybeSpeakResult(result)
                }
            }
        } finally {
            readingInProgress.set(false)
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
        ensureShareDirectory()
        lifecycleScope.launch(Dispatchers.IO) {
            ensureBundledShareDataExtracted()
        }
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
                    saveKeysToFile = saveKeysToFile,
                    forceOverwriteImport = forceOverwriteImport,
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
                    onSaveKeysToFileChange = {
                        saveKeysToFile = it
                    },
                    onForceOverwriteImportChange = {
                        forceOverwriteImport = it
                    },
                    onTrayOutbound = { trayUid ->
                        removeTrayFromInventory(trayUid)
                    },
                    showRecoveryAction = uiState.status == uiString(R.string.status_read_partial) &&
                        uiState.uidHex.isNotBlank(),
                    onAttemptRecovery = { attemptRecoveryFromPartialRead() },
                    onRemainingChange = { trayUid, percent, grams ->
                        updateTrayRemaining(trayUid, percent, grams)
                    },
                    dbHelper = filamentDbHelper,
                    onBackupDatabase = { backupDatabase() },
                    onImportDatabase = { importDatabase() },
                    onResetDatabase = { resetDatabase() },
                    miscStatusMessage = miscStatusMessage,
                    onExportTagPackage = {
                        miscStatusMessage = "正在打包标签数据，请稍候..."
                        lifecycleScope.launch(Dispatchers.IO) {
                            val result = exportSelfTagPackageToDownloads()
                            withContext(Dispatchers.Main) {
                                miscStatusMessage = result
                            }
                        }
                        "正在打包标签数据，请稍候..."
                    },
                    onSelectImportTagPackage = {
                        openTagPackagePicker()
                        val message = "请选择要导入的标签包(.zip)"
                        miscStatusMessage = message
                        message
                    },
                    navigateToReader = shouldNavigateToReader,
                    navigateToTag = shouldNavigateToTag,
                    shareTagItems = shareTagItems,
                    tagPreselectedFileName = tagPreselectedFileName,
                    shareLoading = shareLoading,
                    shareRefreshStatusMessage = shareRefreshStatusMessage,
                    writeStatusMessage = writeStatusMessage,
                    writeInProgress = pendingWriteItem != null || pendingVerifyItem != null,
                    onTagScreenEnter = {
                        refreshShareTagItemsAsync()
                    },
                    onRefreshShareFiles = {
                        if (refreshShareTagItemsAsync()) {
                            ""
                        } else {
                            shareRefreshStatusMessage = "共享数据正在刷新中，请稍候"
                            scheduleClearShareRefreshStatusMessage()
                            ""
                        }
                    },
                    onStartWriteTag = { item ->
                        enqueueWriteTask(item)
                    },
                    onDeleteTagItem = { item ->
                        deleteShareTagItem(item)
                    },
                    onCancelWriteTag = {
                        pendingWriteItem = null
                        pendingVerifyItem = null
                        writeStatusMessage = "已取消写入任务"
                    }
                )
                // 重置导航标志
                if (shouldNavigateToReader) {
                    shouldNavigateToReader = false
                }
                if (shouldNavigateToTag) {
                    shouldNavigateToTag = false
                }
            }
        }
    }

    private fun enqueueWriteTask(item: ShareTagItem) {
        val trayUid = item.trayUid.trim()
        if (trayUid.isNotBlank() && isTrayUidExists(trayUid)) {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("料盘ID重复")
                .setMessage("库存中已存在料盘ID：$trayUid，是否仍然继续复制写入？")
                .setPositiveButton("继续复制") { _, _ ->
                    pendingWriteItem = item
                    pendingVerifyItem = null
                    writeStatusMessage = "写入准备就绪：请将目标空白标签紧贴 NFC 区域，保持静止直到完成。"
                }
                .setNegativeButton("取消") { _, _ ->
                    writeStatusMessage = "已取消：检测到重复料盘ID，未开始写入"
                }
                .show()
        } else {
            pendingWriteItem = item
            pendingVerifyItem = null
            writeStatusMessage = "写入准备就绪：请将目标空白标签紧贴 NFC 区域，保持静止直到完成。"
        }
    }

    private fun attemptRecoveryFromPartialRead() {
        val uid = uiState.uidHex.trim().uppercase(Locale.US)
        if (uid.isBlank()) {
            writeStatusMessage = "修复失败：未读取到UID"
            return
        }
        writeStatusMessage = "正在尝试修复：按UID匹配共享文件..."
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = loadShareTagItems()
            val matched = loaded.firstOrNull { it.sourceUid.uppercase(Locale.US) == uid }
            withContext(Dispatchers.Main) {
                shareTagItems = loaded
                shouldNavigateToTag = true
                if (matched != null) {
                    tagPreselectedFileName = matched.fileName
                    enqueueWriteTask(matched)
                    writeStatusMessage = "已找到匹配文件并进入恢复写入：${matched.fileName.removeSuffix(".txt")}"
                } else {
                    tagPreselectedFileName = null
                    writeStatusMessage = "未找到UID=$uid 对应的数据文件，请在标签页手动选择"
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
        toneGenerator?.release()
        toneGenerator = null
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

    private fun removeTrayFromInventory(trayUidHex: String) {
        if (trayUidHex.isBlank()) {
            uiState = uiState.copy(status = "出库失败：未读取到料盘ID")
            return
        }
        val db = filamentDbHelper?.writableDatabase
        if (db == null) {
            uiState = uiState.copy(status = "出库失败：数据库不可用")
            return
        }
        try {
            filamentDbHelper?.deleteTrayInventory(db, trayUidHex)
            uiState = NfcUiState(
                status = "出库成功"
            )
            logDebug("出库成功: $trayUidHex")
            LogCollector.append(this, "I", "出库成功: $trayUidHex")
        } catch (e: Exception) {
            uiState = uiState.copy(status = "出库失败：${e.message.orEmpty()}")
            logDebug("出库失败: ${e.message}")
            LogCollector.append(this, "E", "出库失败: ${e.message}")
        }
    }

    private fun logEvent(message: String) {
        logDebug(message)
    }

    private fun uiString(@StringRes id: Int, vararg args: Any): String {
        return if (args.isEmpty()) getString(id) else getString(id, *args)
    }

    private fun playFeedbackTone(type: FeedbackTone) {
        val generator = toneGenerator ?: runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        }.getOrNull()?.also { toneGenerator = it } ?: return

        when (type) {
            FeedbackTone.SUCCESS -> generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            FeedbackTone.FAILURE -> generator.startTone(ToneGenerator.TONE_PROP_NACK, 220)
        }
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

    private fun openTagPackagePicker() {
        importTagPackageLauncher.launch(
            arrayOf(
                SHARE_IMPORT_ZIP_MIME,
                "application/x-zip-compressed",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    private fun exportSelfTagPackageToDownloads(): String {
        val externalDir = getExternalFilesDir(null) ?: return "无法访问应用存储目录"
        val sourceDir = File(externalDir, "rfid_files/self_${getDeviceIdSuffix()}")
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            return "未找到标签数据目录: ${sourceDir.name}"
        }
        val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            return "标签数据目录为空，无法打包"
        }

        val zipName =
            "tag_package_${sourceDir.name}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, zipName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, SHARE_IMPORT_ZIP_MIME)
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val resolver = contentResolver
                val uri =
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return "创建下载文件失败"
                resolver.openOutputStream(uri)?.use { output ->
                    ZipOutputStream(output.buffered()).use { zip ->
                        files.forEach { file ->
                            val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                            zip.putNextEntry(ZipEntry("${sourceDir.name}/$relative"))
                            file.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                } ?: return "打开下载文件失败"
            } else {
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, zipName)
                outFile.outputStream().use { output ->
                    ZipOutputStream(output.buffered()).use { zip ->
                        files.forEach { file ->
                            val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                            zip.putNextEntry(ZipEntry("${sourceDir.name}/$relative"))
                            file.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            "标签数据打包成功: Download/$zipName"
        } catch (e: Exception) {
            logDebug("标签数据打包失败: ${e.message}")
            "标签数据打包失败: ${e.message.orEmpty()}"
        }
    }

    private fun importTagPackageFromZipUri(uri: Uri): String {
        val externalDir = getExternalFilesDir(null) ?: return "无法访问应用存储目录"
        val shareDir = File(externalDir, "rfid_files/share")
        if (!shareDir.exists()) {
            shareDir.mkdirs()
        }
        return try {
            var extractedCount = 0
            var skippedCount = 0
            var overwrittenCount = 0
            val existingTxtFiles = shareDir
                .walkTopDown()
                .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
                .toList()
            val existingUidSet = existingTxtFiles
                .map { it.nameWithoutExtension.uppercase(Locale.US) }
                .toMutableSet()
            val uidFilesByUid = mutableMapOf<String, MutableList<File>>()
            existingTxtFiles.forEach { file ->
                val uid = file.nameWithoutExtension.uppercase(Locale.US)
                if (uid.isNotBlank()) {
                    uidFilesByUid.getOrPut(uid) { mutableListOf() }.add(file)
                }
            }
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase(Locale.US).endsWith(".txt")) {
                            val incomingUid = File(entry.name).nameWithoutExtension.uppercase(Locale.US)
                            val alreadyExists = incomingUid.isNotBlank() && existingUidSet.contains(incomingUid)
                            if (alreadyExists && !forceOverwriteImport) {
                                skippedCount++
                                zip.closeEntry()
                                entry = zip.nextEntry
                                continue
                            }
                            if (alreadyExists && forceOverwriteImport && incomingUid.isNotBlank()) {
                                // 强制覆盖：先删除 share 下任意子目录中同 UID 的旧文件，避免跨目录重复。
                                uidFilesByUid[incomingUid].orEmpty().forEach { oldFile ->
                                    if (oldFile.exists() && !oldFile.delete()) {
                                        logDebug("删除旧标签文件失败: ${oldFile.absolutePath}")
                                    }
                                }
                                uidFilesByUid[incomingUid] = mutableListOf()
                            }
                            unzipEntryToDir(zip, entry, shareDir)
                            extractedCount++
                            if (alreadyExists && forceOverwriteImport) {
                                overwrittenCount++
                            }
                            if (incomingUid.isNotBlank()) {
                                existingUidSet.add(incomingUid)
                                uidFilesByUid.getOrPut(incomingUid) { mutableListOf() }
                                    .add(File(shareDir, entry.name))
                            }
                        } else {
                            unzipEntryToDir(zip, entry, shareDir)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return "读取标签包失败"

            if (extractedCount == 0 && skippedCount == 0) {
                "导入完成，但压缩包内未发现 txt 标签数据"
            } else if (extractedCount == 0 && skippedCount > 0) {
                "导入完成：全部为重复UID文件，已跳过 $skippedCount 个"
            } else {
                if (forceOverwriteImport) {
                    "标签包导入完成: 导入 $extractedCount 个（覆盖 $overwrittenCount 个），跳过重复UID $skippedCount 个"
                } else {
                    "标签包导入完成: 导入 $extractedCount 个，跳过重复UID $skippedCount 个"
                }
            }
        } catch (e: Exception) {
            logDebug("导入标签包失败: ${e.message}")
            "导入标签包失败: ${e.message.orEmpty()}"
        }
    }
    
    /**
     * 保存全部扇区数据到文件
     */
    private fun saveAllSectorsData(uidHex: String, rawBlocks: List<ByteArray?>, sectorKeys: List<Pair<ByteArray?, ByteArray?>>) {
        try {
            val externalDir = getExternalFilesDir(null)
            if (externalDir == null) {
                logDebug("无法访问存储目录")
                return
            }
            val deviceIdSuffix = getDeviceIdSuffix()
            val rfidFilesDir = File(externalDir, "rfid_files/self_$deviceIdSuffix")
            if (!rfidFilesDir.exists()) {
                rfidFilesDir.mkdirs()
            }

            val outputFile = File(rfidFilesDir, "${uidHex}.txt")
            val accessBitsHex = "87878769"

            // 仅输出原始16进制文本：
            // 1. 每行一个区块（共64行）
            // 2. 无任何结构化说明文字
            // 3. 每个扇区尾块（sector*4+3）写入 KeyA + 87878769 + KeyB
            outputFile.bufferedWriter().use { writer ->
                for (sector in 0 until 16) {
                    for (block in 0 until 4) {
                        val blockIndex = sector * 4 + block
                        val lineHex = if (block == 3 && sector < sectorKeys.size) {
                            val keyAHex = sectorKeys[sector].first?.toHex()
                            val keyBHex = sectorKeys[sector].second?.toHex()
                            if (!keyAHex.isNullOrBlank() && !keyBHex.isNullOrBlank()) {
                                keyAHex + accessBitsHex + keyBHex
                            } else {
                                rawBlocks.getOrNull(blockIndex)?.toHex().orEmpty()
                            }
                        } else {
                            rawBlocks.getOrNull(blockIndex)?.toHex().orEmpty()
                        }
                        writer.write(lineHex)
                        writer.newLine()
                    }
                }
            }

            logDebug("全部扇区数据已保存到: ${outputFile.absolutePath}")
            LogCollector.append(this, "I", "全部扇区数据已保存到: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            logDebug("保存扇区数据失败: ${e.message}")
            LogCollector.append(this, "E", "保存扇区数据失败: ${e.message}")
        }
    }

    /**
     * 保存秘钥到文件：
     * - 路径：rfid_files/keys
     * - 命名：UID.txt
     * - 格式：每行一个秘钥（按扇区顺序：KeyA、KeyB）
     */
    private fun saveSectorKeysToFile(
        uidHex: String,
        sectorKeys: List<Pair<ByteArray?, ByteArray?>>
    ) {
        try {
            val externalDir = getExternalFilesDir(null)
            if (externalDir == null) {
                logDebug("无法访问存储目录，秘钥文件未保存")
                return
            }
            val keysDir = File(externalDir, "rfid_files/keys")
            if (!keysDir.exists()) {
                keysDir.mkdirs()
            }
            val outputFile = File(keysDir, "${uidHex}.txt")
            outputFile.bufferedWriter().use { writer ->
                for (sector in 0 until WRITE_SECTOR_COUNT) {
                    val keyAHex = sectorKeys.getOrNull(sector)?.first?.toHex().orEmpty()
                    val keyBHex = sectorKeys.getOrNull(sector)?.second?.toHex().orEmpty()
                    writer.write(keyAHex)
                    writer.newLine()
                    writer.write(keyBHex)
                    writer.newLine()
                }
            }
            logDebug("秘钥已保存到: ${outputFile.absolutePath}")
            LogCollector.append(this, "I", "秘钥已保存到: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            logDebug("保存秘钥失败: ${e.message}")
            LogCollector.append(this, "E", "保存秘钥失败: ${e.message}")
        }
    }

    /**
     * 获取用于文件夹后缀的设备唯一ID（优先 ANDROID_ID）。
     * 仅用于本地目录命名，做最小化清洗避免路径非法字符。
     */
    private fun getDeviceIdSuffix(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val rawId = when {
            !androidId.isNullOrBlank() -> androidId
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> Build.SERIAL.orEmpty()
            else -> ""
        }.ifBlank { "unknown" }

        return rawId
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]"), "_")
            .take(32)
            .ifBlank { "unknown" }
    }

    private fun ensureShareDirectory() {
        val externalDir = getExternalFilesDir(null) ?: return
        val shareDir = File(externalDir, "rfid_files/share")
        if (!shareDir.exists()) {
            shareDir.mkdirs()
        }
    }

    /**
     * 首次安装后自动把 assets/rfid_data.zip 解压到 rfid_files/share。
     * 已有 txt 数据或已写入标记时不会重复解压，避免覆盖用户内容。
     */
    private fun ensureBundledShareDataExtracted() {
        val externalDir = getExternalFilesDir(null) ?: return
        val shareDir = File(externalDir, "rfid_files/share")
        if (!shareDir.exists()) {
            shareDir.mkdirs()
        }

        val markerFile = File(shareDir, SHARE_EXTRACT_MARKER_FILE)
        val hasTxtFiles = shareDir.walkTopDown().any { file ->
            file.isFile && file.extension.equals("txt", ignoreCase = true)
        }
        if (markerFile.exists() || hasTxtFiles) {
            return
        }

        try {
            assets.open(SHARE_BUNDLE_ZIP_NAME).use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        unzipEntryToDir(zip, entry, shareDir)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            markerFile.writeText(
                "extracted_at=${System.currentTimeMillis()}",
                Charsets.UTF_8
            )
            logDebug("基础共享数据已解压到: ${shareDir.absolutePath}")
        } catch (e: Exception) {
            // 允许 assets 中不存在该 zip，不阻塞应用启动。
            logDebug("基础共享数据解压跳过/失败: ${e.message}")
        }
    }

    private fun unzipEntryToDir(zip: ZipInputStream, entry: ZipEntry, targetDir: File) {
        val outFile = File(targetDir, entry.name)
        val targetPath = targetDir.canonicalPath
        val outPath = outFile.canonicalPath
        if (!outPath.startsWith("$targetPath${File.separator}") && outPath != targetPath) {
            throw IOException("非法压缩路径: ${entry.name}")
        }
        if (entry.isDirectory) {
            outFile.mkdirs()
            return
        }
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { output ->
            zip.copyTo(output)
        }
    }

    private fun loadShareTagItems(): List<ShareTagItem> {
        val externalDir = getExternalFilesDir(null) ?: return emptyList()
        val shareDir = File(externalDir, "rfid_files/share")
        if (!shareDir.exists()) {
            shareDir.mkdirs()
            return emptyList()
        }

        val files = shareDir
            .walkTopDown()
            .filter { file ->
                file.isFile && file.extension.equals("txt", ignoreCase = true)
            }
            .sortedBy { file ->
                file.relativeTo(shareDir).path.lowercase(Locale.US)
            }
            .toList()

        val result = ArrayList<ShareTagItem>()
        files.forEach { file ->
            try {
                val rawBlocks = parseHexDumpFile(file) ?: return@forEach
                // 共享文件批量扫描时关闭详细日志，避免大文件量下频繁日志影响性能。
                val preview = NfcTagProcessor.parseForPreview(rawBlocks, filamentDbHelper) { }
                val relativePath = file.relativeTo(shareDir).path.replace('\\', '/')
                result.add(
                    ShareTagItem(
                        relativePath = relativePath,
                        fileName = file.name,
                        sourceUid = file.nameWithoutExtension,
                        trayUid = preview.trayUidHex,
                        materialType = preview.displayData.type,
                        colorUid = preview.displayData.colorCode,
                        colorName = preview.displayData.colorName,
                        colorType = preview.displayData.colorType,
                        colorValues = preview.displayData.colorValues,
                        rawBlocks = rawBlocks
                    )
                )
            } catch (e: Exception) {
                logDebug("解析共享文件失败 ${file.name}: ${e.message}")
            }
        }
        return result
    }

    private fun deleteShareTagItem(item: ShareTagItem): String {
        return try {
            val externalDir = getExternalFilesDir(null) ?: return "删除失败：无法访问应用存储目录"
            val shareDir = File(externalDir, "rfid_files/share")
            val relativePath = item.relativePath.ifBlank { item.fileName }
            val targetFile = File(shareDir, relativePath)
            val sharePath = shareDir.canonicalPath
            val targetPath = targetFile.canonicalPath
            if (!targetPath.startsWith("$sharePath${File.separator}")) {
                return "删除失败：非法文件路径"
            }
            if (!targetFile.exists()) {
                shareTagItems = shareTagItems.filterNot { it.relativePath == item.relativePath }
                return "文件不存在，已从列表移除"
            }
            if (!targetFile.isFile) {
                return "删除失败：目标不是文件"
            }
            if (!targetFile.delete()) {
                return "删除失败：无法删除文件"
            }
            shareTagItems = shareTagItems.filterNot { it.relativePath == item.relativePath }
            "删除成功：${item.fileName.removeSuffix(".txt")}"
        } catch (e: Exception) {
            logDebug("删除共享标签失败: ${e.message}")
            "删除失败: ${e.message.orEmpty()}"
        }
    }

    private fun refreshShareTagItemsAsync(): Boolean {
        if (!shareLoadingInProgress.compareAndSet(false, true)) {
            return false
        }
        shareLoading = true
        shareRefreshStatusClearJob?.cancel()
        shareRefreshStatusMessage = "正在后台刷新共享数据..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ensureBundledShareDataExtracted()
                val loadedItems = loadShareTagItems()
                withContext(Dispatchers.Main) {
                    shareTagItems = loadedItems
                    shareLoading = false
                    shareRefreshStatusMessage = "已刷新共享数据，共 ${loadedItems.size} 条"
                    scheduleClearShareRefreshStatusMessage()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    shareRefreshStatusMessage = "刷新失败: ${e.message.orEmpty()}"
                    scheduleClearShareRefreshStatusMessage()
                }
            } finally {
                shareLoadingInProgress.set(false)
                runOnUiThread {
                    shareLoading = false
                }
            }
        }
        return true
    }

    private fun scheduleClearShareRefreshStatusMessage() {
        shareRefreshStatusClearJob?.cancel()
        shareRefreshStatusClearJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(3000)
            shareRefreshStatusMessage = ""
        }
    }

    private fun isTrayUidExists(trayUid: String): Boolean {
        val db = filamentDbHelper?.readableDatabase ?: return false
        val cursor = db.query(
            TRAY_UID_TABLE,
            arrayOf("tray_uid"),
            "tray_uid = ?",
            arrayOf(trayUid),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            return it.moveToFirst()
        }
    }

    private fun parseHexDumpFile(file: File): List<ByteArray?>? {
        val lines = file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return null
        }
        val blocks = MutableList<ByteArray?>(64) { null }
        lines.take(64).forEachIndexed { index, line ->
            val hex = line.replace(" ", "").uppercase(Locale.US)
            if (hex.length == 32 && hex.all { it in '0'..'9' || it in 'A'..'F' }) {
                blocks[index] = hexToBytes(hex)
            }
        }
        return blocks
    }

    private fun writeTagFromDump(tag: Tag, item: ShareTagItem): String {
        val mifare = MifareClassic.get(tag) ?: return "写入失败：标签不支持 MIFARE Classic"
        val sourceBlocks = item.rawBlocks
        if (sourceBlocks.isEmpty()) {
            return "写入失败：源文件数据为空"
        }

        val ffKey = ByteArray(6) { 0xFF.toByte() }

        return try {
            var resumePoint = WriteResumePoint(sector = 0, blockOffset = 0)
            var recoverAttempts = 0

            while (resumePoint.sector < WRITE_SECTOR_COUNT) {
                try {
                    if (!mifare.isConnected) {
                        mifare.connect()
                        // 首次/重连后给用户与链路一点稳定时间。
                        Thread.sleep(if (recoverAttempts == 0) 700 else 300)
                    }

                    // 仅在首次写入前执行一次硬性预检查，避免 FUID 卡写坏。
                    if (recoverAttempts == 0 && resumePoint.sector == 0 && resumePoint.blockOffset == 0) {
                        val precheck = precheckBeforeWrite(mifare, sourceBlocks)
                        when (precheck.action) {
                            WritePrecheckAction.ALREADY_MATCHED -> {
                                return "写入前检查：目标卡已是目标数据，无需重复写入"
                            }
                            WritePrecheckAction.BLOCKED_CONFLICT,
                            WritePrecheckAction.BLOCKED_UNREADABLE -> {
                                return "写入前检查失败：${precheck.message}"
                            }
                            WritePrecheckAction.RESUME_FROM_POINT -> {
                                resumePoint = precheck.resumePoint
                            }
                            WritePrecheckAction.START_FROM_BEGINNING -> {
                                resumePoint = WriteResumePoint(0, 0)
                            }
                        }
                    }

                    for (sector in resumePoint.sector until WRITE_SECTOR_COUNT) {
                        val trailerIndex = sector * 4 + 3
                        val trailerData = sourceBlocks.getOrNull(trailerIndex)
                        val sourceKeyA = if (trailerData != null && trailerData.size == 16) {
                            trailerData.copyOfRange(0, 6)
                        } else null
                        val sourceKeyB = if (trailerData != null && trailerData.size == 16) {
                            trailerData.copyOfRange(10, 16)
                        } else null

                        val authenticated = authenticateSectorWithRetry(
                            mifare = mifare,
                            sectorIndex = sector,
                            keysA = listOf(ffKey, sourceKeyA),
                            keysB = listOf(ffKey, sourceKeyB)
                        )
                        if (!authenticated) {
                            return "写入失败：扇区 $sector 认证失败"
                        }

                        val startBlock = mifare.sectorToBlock(sector)
                        val startOffset = if (sector == resumePoint.sector) {
                            resumePoint.blockOffset
                        } else {
                            0
                        }

                        for (offset in startOffset until 4) {
                            val blockIndex = startBlock + offset
                            // 严格 1:1 按文件写入（包括 trailer 密钥与权限位）。
                            val targetData = sourceBlocks.getOrNull(blockIndex)
                                ?: return "写入失败：区块 $blockIndex 源数据缺失"
                            if (targetData.size != 16) {
                                return "写入失败：区块 $blockIndex 数据长度异常"
                            }

                            val writeOk = writeBlockWithRetry(mifare, blockIndex, targetData)
                            if (!writeOk) {
                                throw IOException("区块 $blockIndex 写入异常")
                            }
                            // 每块间隔一点点，降低连续写导致的链路抖动。
                            Thread.sleep(20)
                        }
                    }

                    // 全部写完，跳出 while。
                    resumePoint = WriteResumePoint(WRITE_SECTOR_COUNT, 0)
                } catch (e: Exception) {
                    recoverAttempts++
                    try {
                        mifare.close()
                    } catch (_: Exception) {
                    }
                    if (recoverAttempts > WRITE_RESUME_MAX_ATTEMPTS) {
                        return "写入失败：${e.message.orEmpty()}（已超过续写重试次数）"
                    }
                    val detected = detectWriteResumePoint(tag, sourceBlocks)
                    if (detected == null) {
                        return "写入失败：中断后无法定位断点"
                    }
                    if (detected.sector >= WRITE_SECTOR_COUNT) {
                        // 探测到全卡已是目标内容，视为成功。
                        return "写入成功：断线后校验断点显示已完成全部写入"
                    }
                    resumePoint = detected
                }
            }

            "写入成功：已完成全部区块写入"
        } catch (e: Exception) {
            "写入失败：${e.message.orEmpty()}"
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun verifyTagAgainstDump(tag: Tag, item: ShareTagItem): String {
        val mifare = MifareClassic.get(tag) ?: return "校验失败：标签不支持 MIFARE Classic"
        val sourceBlocks = item.rawBlocks
        if (sourceBlocks.size < 64) {
            return "校验失败：源数据不足 64 区块"
        }

        return try {
            mifare.connect()
            val readBackBlocks = MutableList<ByteArray?>(64) { null }
            for (sector in 0 until 16) {
                val trailerIndex = sector * 4 + 3
                val trailerData = sourceBlocks.getOrNull(trailerIndex)
                    ?: return "校验失败：扇区 $sector trailer 缺失"
                if (trailerData.size != 16) {
                    return "校验失败：扇区 $sector trailer 长度异常"
                }
                val sourceKeyA = trailerData.copyOfRange(0, 6)
                val sourceKeyB = trailerData.copyOfRange(10, 16)
                val authenticated = authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = sector,
                    keysA = listOf(sourceKeyA),
                    keysB = listOf(sourceKeyB)
                )
                if (!authenticated) {
                    return "校验失败：扇区 $sector 使用文件秘钥认证失败"
                }

                val startBlock = mifare.sectorToBlock(sector)
                for (offset in 0 until 4) {
                    val blockIndex = startBlock + offset
                    val actual = readBlockWithRetry(mifare, blockIndex)
                        ?: return "校验失败：区块 $blockIndex 读取异常"
                    readBackBlocks[blockIndex] = actual
                }
            }

            // 按“每行16进制文本”逐行比对，和源文件格式一致。
            // trailer 块不校检秘钥位（0..5 和 10..15），仅校检访问位 6..9。
            fun maskForCompare(blockIndex: Int, block: ByteArray?): ByteArray {
                val data = (block ?: ByteArray(16)).copyOf()
                if (blockIndex % 4 == 3 && data.size == 16) {
                    for (i in 0..5) data[i] = 0x00
                    for (i in 10..15) data[i] = 0x00
                }
                return data
            }

            val expectedLines = sourceBlocks.mapIndexed { index, block ->
                maskForCompare(index, block).toHex().uppercase(Locale.US)
            }
            val actualLines = readBackBlocks.mapIndexed { index, block ->
                maskForCompare(index, block).toHex().uppercase(Locale.US)
            }
            for (index in 0 until 64) {
                val expected = expectedLines.getOrNull(index).orEmpty()
                val actual = actualLines.getOrNull(index).orEmpty()
                if (expected != actual) {
                    return "校验失败：第 ${index + 1} 行不一致，期望=$expected，实际=$actual"
                }
            }

            "校验成功"
        } catch (e: Exception) {
            "校验失败：${e.message.orEmpty()}"
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 断线后用“源标签密钥”探测写入进度，返回应继续写入的扇区/区块位置。
     * 规则：
     * 1) 优先用源 trailer 里的 KeyA/KeyB 认证；
     * 2) 对已可读扇区逐块比较目标内容（trailer 仅比较访问位 6..9）；
     * 3) 返回第一个不一致块作为续写起点。
     */
    private fun detectWriteResumePoint(
        tag: Tag,
        sourceBlocks: List<ByteArray?>
    ): WriteResumePoint? {
        val mifare = MifareClassic.get(tag) ?: return null
        return try {
            mifare.connect()
            for (sector in 0 until WRITE_SECTOR_COUNT) {
                val trailerIndex = sector * 4 + 3
                val trailerData = sourceBlocks.getOrNull(trailerIndex) ?: return WriteResumePoint(sector, 0)
                if (trailerData.size != 16) return WriteResumePoint(sector, 0)
                val sourceKeyA = trailerData.copyOfRange(0, 6)
                val sourceKeyB = trailerData.copyOfRange(10, 16)

                val authBySourceKey = authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = sector,
                    keysA = listOf(sourceKeyA),
                    keysB = listOf(sourceKeyB)
                )
                if (!authBySourceKey) {
                    // 该扇区大概率尚未写到 trailer（或写入未完成），从此扇区起继续。
                    return WriteResumePoint(sector, 0)
                }

                val startBlock = mifare.sectorToBlock(sector)
                for (offset in 0 until 4) {
                    val blockIndex = startBlock + offset
                    val expected = sourceBlocks.getOrNull(blockIndex) ?: return WriteResumePoint(sector, offset)
                    if (expected.size != 16) return WriteResumePoint(sector, offset)
                    val actual = readBlockWithRetry(mifare, blockIndex) ?: return WriteResumePoint(sector, offset)

                    if (!isBlockEquivalentForResume(blockIndex, expected, actual)) {
                        return WriteResumePoint(sector, offset)
                    }
                }
            }
            WriteResumePoint(WRITE_SECTOR_COUNT, 0)
        } catch (_: Exception) {
            null
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 写入前预检查：
     * - 空白卡：从头写；
     * - 已部分写入且前缀内容一致：从断点续写；
     * - 已写入其他内容/不可识别：阻止写入。
     */
    private fun precheckBeforeWrite(
        mifare: MifareClassic,
        sourceBlocks: List<ByteArray?>
    ): WritePrecheckResult {
        val ffKey = ByteArray(6) { 0xFF.toByte() }
        var resumePoint: WriteResumePoint? = null
        var matchedAnyBlock = false

        for (sector in 0 until WRITE_SECTOR_COUNT) {
            val trailerIndex = sector * 4 + 3
            val trailerData = sourceBlocks.getOrNull(trailerIndex)
                ?: return WritePrecheckResult(
                    action = WritePrecheckAction.BLOCKED_CONFLICT,
                    message = "源数据缺少扇区 $sector trailer"
                )
            if (trailerData.size != 16) {
                return WritePrecheckResult(
                    action = WritePrecheckAction.BLOCKED_CONFLICT,
                    message = "源数据扇区 $sector trailer 长度异常"
                )
            }
            val sourceKeyA = trailerData.copyOfRange(0, 6)
            val sourceKeyB = trailerData.copyOfRange(10, 16)
            val authBySource = authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = sector,
                keysA = listOf(sourceKeyA),
                keysB = listOf(sourceKeyB)
            )
            val authByFF = if (!authBySource) {
                authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = sector,
                    keysA = listOf(ffKey),
                    keysB = listOf(ffKey)
                )
            } else {
                false
            }

            if (!authBySource && !authByFF) {
                return WritePrecheckResult(
                    action = WritePrecheckAction.BLOCKED_UNREADABLE,
                    message = "扇区 $sector 无法认证（既非空白卡也非目标卡）"
                )
            }

            val startBlock = mifare.sectorToBlock(sector)
            for (offset in 0 until 4) {
                val blockIndex = startBlock + offset
                val expected = sourceBlocks.getOrNull(blockIndex)
                    ?: return WritePrecheckResult(
                        action = WritePrecheckAction.BLOCKED_CONFLICT,
                        message = "源数据缺少区块 $blockIndex"
                    )
                if (expected.size != 16) {
                    return WritePrecheckResult(
                        action = WritePrecheckAction.BLOCKED_CONFLICT,
                        message = "源数据区块 $blockIndex 长度异常"
                    )
                }
                val actual = readBlockWithRetry(mifare, blockIndex)
                    ?: return WritePrecheckResult(
                        action = WritePrecheckAction.BLOCKED_UNREADABLE,
                        message = "读取区块 $blockIndex 失败"
                    )

                val matched = isBlockEquivalentForResume(blockIndex, expected, actual)
                val blankLike = isBlankLikeBlock(blockIndex, actual)
                if (matched) {
                    matchedAnyBlock = true
                    continue
                }
                if (blankLike) {
                    if (resumePoint == null) {
                        resumePoint = WriteResumePoint(sector, offset)
                    }
                    // 第一个空白断点之后不再强制要求连续匹配，续写将覆盖后续。
                    break
                }
                if (authByFF) {
                    // 该扇区仍可用默认 FF 密钥认证，按可覆盖区处理，不再阻止写入。
                    if (resumePoint == null) {
                        resumePoint = WriteResumePoint(sector, offset)
                    }
                    break
                }
                return WritePrecheckResult(
                    action = WritePrecheckAction.BLOCKED_CONFLICT,
                    message = "区块 $blockIndex 存在非目标数据，已阻止写入"
                )
            }
            if (resumePoint != null) {
                break
            }
        }

        return when {
            resumePoint != null && (resumePoint.sector > 0 || resumePoint.blockOffset > 0) ->
                WritePrecheckResult(
                    action = WritePrecheckAction.RESUME_FROM_POINT,
                    resumePoint = resumePoint
                )
            matchedAnyBlock && resumePoint == null ->
                WritePrecheckResult(action = WritePrecheckAction.ALREADY_MATCHED)
            else ->
                WritePrecheckResult(action = WritePrecheckAction.START_FROM_BEGINNING)
        }
    }

    private fun isBlockEquivalentForResume(
        blockIndex: Int,
        expected: ByteArray,
        actual: ByteArray
    ): Boolean {
        if (blockIndex % 4 != 3) {
            return expected.contentEquals(actual)
        }
        // trailer：很多设备无法读出密钥位，仅比较访问控制位 6..9。
        for (i in 6..9) {
            if (expected[i] != actual[i]) return false
        }
        return true
    }

    private fun isBlankLikeBlock(blockIndex: Int, block: ByteArray): Boolean {
        if (block.all { it == 0.toByte() } || block.all { it == 0xFF.toByte() }) {
            return true
        }
        if (blockIndex % 4 != 3) {
            return false
        }
        // 常见空白 trailer: FFFFFFFFFFFF + FF078069 + FFFFFFFFFFFF
        val keyAAllFF = (0..5).all { block[it] == 0xFF.toByte() }
        val acDefault = block[6] == 0xFF.toByte() &&
            block[7] == 0x07.toByte() &&
            block[8] == 0x80.toByte() &&
            block[9] == 0x69.toByte()
        val keyBAllFF = (10..15).all { block[it] == 0xFF.toByte() }
        return keyAAllFF && acDefault && keyBAllFF
    }

    private fun authenticateSectorWithRetry(
        mifare: MifareClassic,
        sectorIndex: Int,
        keysA: List<ByteArray?>,
        keysB: List<ByteArray?>
    ): Boolean {
        for (attempt in 0..RW_AUTH_RETRY_COUNT) {
            try {
                keysA.forEach { key ->
                    if (key != null && mifare.authenticateSectorWithKeyA(sectorIndex, key)) {
                        return true
                    }
                }
                keysB.forEach { key ->
                    if (key != null && mifare.authenticateSectorWithKeyB(sectorIndex, key)) {
                        return true
                    }
                }
            } catch (_: Exception) {
                // Ignore and retry.
            }
        }
        return false
    }

    private fun writeBlockWithRetry(
        mifare: MifareClassic,
        blockIndex: Int,
        data: ByteArray
    ): Boolean {
        for (attempt in 0..RW_BLOCK_RETRY_COUNT) {
            try {
                mifare.writeBlock(blockIndex, data)
                return true
            } catch (_: Exception) {
                // Ignore and retry.
            }
        }
        return false
    }

    private fun readBlockWithRetry(
        mifare: MifareClassic,
        blockIndex: Int
    ): ByteArray? {
        for (attempt in 0..RW_BLOCK_RETRY_COUNT) {
            try {
                val raw = mifare.readBlock(blockIndex)
                return when {
                    raw.size == 16 -> raw
                    raw.size > 16 -> raw.copyOf(16)
                    else -> null
                }
            } catch (_: Exception) {
                // Ignore and retry.
            }
        }
        return null
    }

    private fun deriveWriteKeys(uid: ByteArray, info: ByteArray): List<ByteArray> {
        val prk = hkdfExtractForWrite(WRITE_HKDF_SALT, uid)
        val okm = hkdfExpandForWrite(prk, info, WRITE_KEY_LENGTH_BYTES * WRITE_SECTOR_COUNT)
        val keys = ArrayList<ByteArray>(WRITE_SECTOR_COUNT)
        for (i in 0 until WRITE_SECTOR_COUNT) {
            val start = i * WRITE_KEY_LENGTH_BYTES
            keys.add(okm.copyOfRange(start, start + WRITE_KEY_LENGTH_BYTES))
        }
        return keys
    }

    private fun hkdfExtractForWrite(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpandForWrite(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val hashLen = mac.macLength
        val blocks = ceil(length.toDouble() / hashLen.toDouble()).toInt()
        var t = ByteArray(0)
        val output = java.io.ByteArrayOutputStream()
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

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val result = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            result[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return result
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
                if (saveKeysToFile) {
                    saveSectorKeysToFile(
                        uidHex = rawResult.data.uidHex,
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
