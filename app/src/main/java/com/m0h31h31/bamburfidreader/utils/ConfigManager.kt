package com.m0h31h31.bamburfidreader.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.jvm.functions.Function0
import kotlin.jvm.functions.Function2

object ConfigManager {
    
    // 文件路径
    private const val FILAMENTS_COLOR_CODES_FILE = "filaments_color_codes.json"
    private const val APP_CONFIG_FILE = "AppConfig.json"
    private const val FILAMENTS_TYPE_MAPPING_FILE = "filaments_type_mapping.json"
    
    // 网络地址
    private const val FILAMENTS_COLOR_CODES_PRIMARY = "https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/app/src/main/assets/filaments_color_codes.json"
    private const val FILAMENTS_COLOR_CODES_BACKUP = "https://raw.githubusercontent.com/m0h31h31/BambuRfidReader/refs/heads/master/app/src/main/assets/filaments_color_codes.json"
    
    private const val APP_CONFIG_PRIMARY = "https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/app/src/main/assets/AppConfig.json"
    private const val APP_CONFIG_BACKUP = "https://raw.githubusercontent.com/m0h31h31/BambuRfidReader/refs/heads/master/app/src/main/assets/AppConfig.json"
    
    private const val FILAMENTS_TYPE_MAPPING_PRIMARY = "https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/app/src/main/assets/filaments_type_mapping.json"
    private const val FILAMENTS_TYPE_MAPPING_BACKUP = "https://raw.githubusercontent.com/m0h31h31/BambuRfidReader/refs/heads/master/app/src/main/assets/filaments_type_mapping.json"
    
    /**
     * 检查并更新配置文件
     */
    suspend fun checkAndUpdateConfig(context: Context, onUpdateAvailable: Function2<String, Function0<Unit>, Unit>) {
        withContext(Dispatchers.IO) {
            // 检查AppConfig
            checkAppConfig(context, onUpdateAvailable)
            
            // 检查颜色配置文件
            checkFilamentsColorCodes(context, onUpdateAvailable)
            
            // 检查耗材类型映射文件
            checkFilamentsTypeMapping(context, onUpdateAvailable)
        }
    }
    
    /**
     * 检查AppConfig
     */
    private suspend fun checkAppConfig(context: Context, onUpdateAvailable: Function2<String, Function0<Unit>, Unit>) {
        try {
            val remoteContent = NetworkUtils.fetchFile(APP_CONFIG_PRIMARY, APP_CONFIG_BACKUP)
            if (remoteContent != null) {
                val remoteJson = JSONObject(String(remoteContent))
                val remoteVersion = remoteJson.optString("version", "")
                val message = remoteJson.optString("message", "")
                
                // 保存到本地
                saveFile(context, APP_CONFIG_FILE, remoteContent)
                
                // 版本检查逻辑
                val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                if (remoteVersion != currentVersion && remoteVersion.isNotEmpty()) {
                    // 版本不同，提示用户更新
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable.invoke("版本更新请到原地址下载", object : Function0<Unit> {
                            override fun invoke() {
                                // 空实现，因为只需要取消按钮
                            }
                        })
                    }
                }
            } else {
                com.m0h31h31.bamburfidreader.logDebug("Failed to fetch AppConfig")
            }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error checking AppConfig: ${e.message}")
        }
    }
    
    /**
     * 检查颜色配置文件
     */
    private suspend fun checkFilamentsColorCodes(context: Context, onUpdateAvailable: Function2<String, Function0<Unit>, Unit>) {
        try {
            val remoteContent = NetworkUtils.fetchFile(FILAMENTS_COLOR_CODES_PRIMARY, FILAMENTS_COLOR_CODES_BACKUP)
            if (remoteContent != null) {
                val remoteHash = NetworkUtils.calculateHash(remoteContent)
                val localHash = getLocalFileHash(context, FILAMENTS_COLOR_CODES_FILE)
                
                if (remoteHash != localHash) {
                    // 哈希值不同，提示用户更新
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable.invoke("颜色配置文件有更新，是否更新？", object : Function0<Unit> {
                            override fun invoke() {
                                // 用户确认更新
                                saveFile(context, FILAMENTS_COLOR_CODES_FILE, remoteContent)
                                // 更新数据库的逻辑
                                try {
                                    // 读取更新后的文件内容
                                    val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
                                    val updatedFile = File(externalDir, FILAMENTS_COLOR_CODES_FILE)
                                    val updatedContent = updatedFile.readText()
                                    // 重新同步数据库
                                    val dbHelper = com.m0h31h31.bamburfidreader.FilamentDbHelper(context)
                                    com.m0h31h31.bamburfidreader.syncFilamentDatabase(context, dbHelper)
                                    com.m0h31h31.bamburfidreader.logDebug("颜色配置文件更新成功，数据库已更新")
                                } catch (e: Exception) {
                                    com.m0h31h31.bamburfidreader.logDebug("更新数据库失败: ${e.message}")
                                }
                            }
                        })
                    }
                }
            } else {
                com.m0h31h31.bamburfidreader.logDebug("Failed to fetch filaments_color_codes.json")
            }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error checking filaments_color_codes.json: ${e.message}")
        }
    }
    
    /**
     * 获取本地文件的哈希值
     */
    private fun getLocalFileHash(context: Context, fileName: String): String? {
        val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(externalDir, fileName)
        if (file.exists()) {
            return try {
                val fileBytes = file.readBytes()
                NetworkUtils.calculateHash(fileBytes)
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("Error calculating local file hash: ${e.message}")
                null
            }
        }
        return null
    }
    
    /**
     * 保存文件到本地
     */
    private fun saveFile(context: Context, fileName: String, content: ByteArray) {
        try {
            val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(externalDir, fileName)
            FileOutputStream(file).use {
                it.write(content)
            }
            com.m0h31h31.bamburfidreader.logDebug("File saved: $fileName to ${file.absolutePath}")
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error saving file: $fileName, error: ${e.message}")
        }
    }
    
    /**
     * 获取本地配置文件内容
     */
    fun getLocalConfig(context: Context, fileName: String): String? {
        val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(externalDir, fileName)
        if (file.exists()) {
            return try {
                file.readText()
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("Error reading local config: $fileName, error: ${e.message}")
                null
            }
        }
        return null
    }
    
    /**
     * 检查耗材类型映射文件
     */
    private suspend fun checkFilamentsTypeMapping(context: Context, onUpdateAvailable: Function2<String, Function0<Unit>, Unit>) {
        try {
            val remoteContent = NetworkUtils.fetchFile(FILAMENTS_TYPE_MAPPING_PRIMARY, FILAMENTS_TYPE_MAPPING_BACKUP)
            if (remoteContent != null) {
                val remoteHash = NetworkUtils.calculateHash(remoteContent)
                val localHash = getLocalFileHash(context, FILAMENTS_TYPE_MAPPING_FILE)
                
                if (remoteHash != localHash) {
                    // 哈希值不同，提示用户更新
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable.invoke("耗材类型映射文件有更新，是否更新？", object : Function0<Unit> {
                            override fun invoke() {
                                // 用户确认更新
                                saveFile(context, FILAMENTS_TYPE_MAPPING_FILE, remoteContent)
                                // 更新数据库的逻辑
                                try {
                                    // 读取更新后的文件内容
                                    val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
                                    val updatedFile = File(externalDir, FILAMENTS_TYPE_MAPPING_FILE)
                                    val updatedContent = updatedFile.readText()
                                    // 重新同步数据库
                                    val dbHelper = com.m0h31h31.bamburfidreader.FilamentDbHelper(context)
                                    com.m0h31h31.bamburfidreader.syncFilamentDatabase(context, dbHelper)
                                    com.m0h31h31.bamburfidreader.logDebug("耗材类型映射文件更新成功，数据库已更新")
                                } catch (e: Exception) {
                                    com.m0h31h31.bamburfidreader.logDebug("更新数据库失败: ${e.message}")
                                }
                            }
                        })
                    }
                }
            } else {
                com.m0h31h31.bamburfidreader.logDebug("Failed to fetch filaments_type_mapping.json")
            }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error checking filaments_type_mapping.json: ${e.message}")
        }
    }

    /**
     * 获取AppConfig中的消息
     */
    fun getAppConfigMessage(context: Context): String {
        val configContent = getLocalConfig(context, APP_CONFIG_FILE)
        if (configContent != null) {
            try {
                val json = JSONObject(configContent)
                return json.optString("message", "")
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("Error parsing AppConfig: ${e.message}")
            }
        }
        return ""
    }
}
