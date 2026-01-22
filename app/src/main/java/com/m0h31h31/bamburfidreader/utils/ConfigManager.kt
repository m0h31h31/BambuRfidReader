package com.m0h31h31.bamburfidreader.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.jvm.functions.Function0
import kotlin.jvm.functions.Function2

object ConfigManager {
    private const val TAG = "ConfigManager"
    
    // 文件路径
    private const val FILAMENTS_COLOR_CODES_FILE = "filaments_color_codes.json"
    private const val APP_CONFIG_FILE = "AppConfig.json"
    
    // 网络地址
    private const val FILAMENTS_COLOR_CODES_PRIMARY = "https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/app/src/main/assets/filaments_color_codes.json"
    private const val FILAMENTS_COLOR_CODES_BACKUP = "https://raw.githubusercontent.com/m0h31h31/BambuRfidReader/refs/heads/master/app/src/main/assets/filaments_color_codes.json"
    
    private const val APP_CONFIG_PRIMARY = "https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/AppConfig.json"
    private const val APP_CONFIG_BACKUP = "https://raw.githubusercontent.com/m0h31h31/BambuRfidReader/refs/heads/master/AppConfig.json"
    
    /**
     * 检查并更新配置文件
     */
    suspend fun checkAndUpdateConfig(context: Context, onUpdateAvailable: Function2<String, Function0<Unit>, Unit>) {
        withContext(Dispatchers.IO) {
            // 检查AppConfig
            checkAppConfig(context, onUpdateAvailable)
            
            // 检查颜色配置文件
            checkFilamentsColorCodes(context, onUpdateAvailable)
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
                
                // 这里可以添加版本检查逻辑
                // 例如，与当前应用版本比较
                // 如果不同，提示用户更新
            } else {
                Log.e(TAG, "Failed to fetch AppConfig")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking AppConfig", e)
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
                                // 这里可以添加更新数据库的逻辑
                            }
                        })
                    }
                }
            } else {
                Log.e(TAG, "Failed to fetch filaments_color_codes.json")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking filaments_color_codes.json", e)
        }
    }
    
    /**
     * 获取本地文件的哈希值
     */
    private fun getLocalFileHash(context: Context, fileName: String): String? {
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            return try {
                val fileBytes = file.readBytes()
                NetworkUtils.calculateHash(fileBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating local file hash", e)
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
            val file = File(context.filesDir, fileName)
            FileOutputStream(file).use {
                it.write(content)
            }
            Log.d(TAG, "File saved: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file: $fileName", e)
        }
    }
    
    /**
     * 获取本地配置文件内容
     */
    fun getLocalConfig(context: Context, fileName: String): String? {
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            return try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading local config: $fileName", e)
                null
            }
        }
        return null
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
                Log.e(TAG, "Error parsing AppConfig", e)
            }
        }
        return ""
    }
}
