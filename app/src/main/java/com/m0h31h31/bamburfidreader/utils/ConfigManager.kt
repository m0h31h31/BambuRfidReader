package com.m0h31h31.bamburfidreader.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object ConfigManager {
    data class AppLinkConfig(
        val type: String,
        val value: String
    ) {
        val isUsable: Boolean get() = value.isNotBlank()
    }

    private const val DEFAULT_BOOST_LINK =
        "bambulab://bbl/design/model/detail?design_id=2020787&instance_id=2253290&appSharePlatform=copy"
    private const val DEFAULT_USER_COUNT_ENDPOINT = "https://brr.jacki.cn/events"
    private const val DEFAULT_TAG_SHARE_ENDPOINT = "https://brr.jacki.cn/api/tags"
    private const val DEFAULT_TAG_DOWNLOAD_ENDPOINT = "https://brr.jacki.cn/api/public/tags/download"
    private const val DEFAULT_TAG_CAN_DOWNLOAD_ENDPOINT = "https://brr.jacki.cn/api/public/tags/can-download"
    private const val DEFAULT_ANOMALY_REPORT_ENDPOINT = "https://brr.jacki.cn/api/anomaly"
    private const val DEFAULT_ANOMALY_UIDS_ENDPOINT = "https://brr.jacki.cn/api/anomaly/uids"
    private const val DEFAULT_NICKNAME_ENDPOINT = "https://brr.jacki.cn/api/nickname"
    private const val DEFAULT_UPDATE_ENDPOINT = "https://brr.jacki.cn/api/update/latest"

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

    private const val CREALITY_MATERIAL_FILE = "creality_material_list.json"
    private const val CREALITY_MATERIAL_PRIMARY = "https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/app/src/main/assets/creality_material_list.json"
    private const val CREALITY_MATERIAL_BACKUP = "https://raw.githubusercontent.com/m0h31h31/BambuRfidReader/refs/heads/master/app/src/main/assets/creality_material_list.json"
    
    /**
     * 检查并更新配置文件
     */
    suspend fun checkAndUpdateConfig(context: Context, onUpdateAvailable: (String, () -> Unit) -> Unit) {
        withContext(Dispatchers.IO) {
            // 检查AppConfig
            checkAppConfig(context, onUpdateAvailable)
            
            // 检查颜色配置文件
            checkFilamentsColorCodes(context, onUpdateAvailable)
            
            // 检查耗材类型映射文件
            checkFilamentsTypeMapping(context, onUpdateAvailable)

            // 检查创想三维耗材列表
            checkCrealityMaterialList(context, onUpdateAvailable)
        }
    }
    
    /**
     * 检查并更新本地 AppConfig（仅同步远程配置，版本检查由后端 /api/update/latest 负责）
     */
    private suspend fun checkAppConfig(context: Context, onUpdateAvailable: (String, () -> Unit) -> Unit) {
        try {
            val remoteContent = NetworkUtils.fetchFile(APP_CONFIG_PRIMARY, APP_CONFIG_BACKUP)
            if (remoteContent != null) {
                saveFile(context, APP_CONFIG_FILE, remoteContent)
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
    private suspend fun checkFilamentsColorCodes(context: Context, onUpdateAvailable: (String, () -> Unit) -> Unit) {
        try {
            val remoteContent = NetworkUtils.fetchFile(FILAMENTS_COLOR_CODES_PRIMARY, FILAMENTS_COLOR_CODES_BACKUP)
            if (remoteContent != null) {
                val remoteHash = NetworkUtils.calculateHash(remoteContent)
                val localHash = getLocalFileHash(context, FILAMENTS_COLOR_CODES_FILE)
                
                if (remoteHash != localHash) {
                    // 哈希值不同，提示用户更新
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable("颜色配置文件有更新，是否更新？") {
                            saveFile(context, FILAMENTS_COLOR_CODES_FILE, remoteContent)
                            try {
                                val dbHelper = com.m0h31h31.bamburfidreader.FilamentDbHelper(context)
                                com.m0h31h31.bamburfidreader.syncFilamentDatabase(context, dbHelper)
                                com.m0h31h31.bamburfidreader.logDebug("颜色配置文件更新成功，数据库已更新")
                            } catch (e: Exception) {
                                com.m0h31h31.bamburfidreader.logDebug("更新数据库失败: ${e.message}")
                            }
                        }
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
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error reading asset config: $fileName, error: ${e.message}")
            null
        }
    }
    
    /**
     * 检查耗材类型映射文件
     */
    private suspend fun checkFilamentsTypeMapping(context: Context, onUpdateAvailable: (String, () -> Unit) -> Unit) {
        try {
            val remoteContent = NetworkUtils.fetchFile(FILAMENTS_TYPE_MAPPING_PRIMARY, FILAMENTS_TYPE_MAPPING_BACKUP)
            if (remoteContent != null) {
                val remoteHash = NetworkUtils.calculateHash(remoteContent)
                val localHash = getLocalFileHash(context, FILAMENTS_TYPE_MAPPING_FILE)
                
                if (remoteHash != localHash) {
                    // 哈希值不同，提示用户更新
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable("耗材类型映射文件有更新，是否更新？") {
                            saveFile(context, FILAMENTS_TYPE_MAPPING_FILE, remoteContent)
                            try {
                                val dbHelper = com.m0h31h31.bamburfidreader.FilamentDbHelper(context)
                                com.m0h31h31.bamburfidreader.syncFilamentDatabase(context, dbHelper)
                                com.m0h31h31.bamburfidreader.logDebug("耗材类型映射文件更新成功，数据库已更新")
                            } catch (e: Exception) {
                                com.m0h31h31.bamburfidreader.logDebug("更新数据库失败: ${e.message}")
                            }
                        }
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

    fun getAppConfigAdMessage(context: Context): String {
        val configContent = getLocalConfig(context, APP_CONFIG_FILE)
        if (configContent != null) {
            try {
                val json = JSONObject(configContent)
                return json.optString("adMessage", "")
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("Error parsing AppConfig adMessage: ${e.message}")
            }
        }
        return ""
    }

    fun getAppConfigBoostLink(context: Context): AppLinkConfig {
        val configContent = getLocalConfig(context, APP_CONFIG_FILE)
        val defaultValue = AppLinkConfig(type = "scheme", value = DEFAULT_BOOST_LINK)
        if (configContent != null) {
            try {
                val json = JSONObject(configContent)
                return parseLinkConfig(
                    json = json,
                    key = "boostLink",
                    defaultValue = defaultValue
                ) ?: defaultValue
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("Error parsing AppConfig boostLink: ${e.message}")
            }
        }
        return defaultValue
    }

    fun getAppConfigLogoLinks(context: Context): Map<String, AppLinkConfig> {
        val configContent = getLocalConfig(context, APP_CONFIG_FILE) ?: return emptyMap()
        return try {
            val json = JSONObject(configContent)
            val logoLinks = json.optJSONObject("logoLinks") ?: return emptyMap()
            buildMap {
                val keys = logoLinks.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    parseLinkConfig(logoLinks, key, null)?.let { put(key.lowercase(), it) }
                }
            }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error parsing AppConfig logoLinks: ${e.message}")
            emptyMap()
        }
    }

    fun getTagShareEndpoint(context: Context): AppLinkConfig {
        val defaultValue = AppLinkConfig(type = "url", value = DEFAULT_TAG_SHARE_ENDPOINT)
        val configContent = getLocalConfig(context, APP_CONFIG_FILE) ?: return defaultValue
        return (try {
            val json = JSONObject(configContent)
            parseLinkConfig(json, "tagShareEndpoint", null)?.takeIf {
                it.type.equals("url", ignoreCase = true) && it.value.isNotBlank()
            }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error parsing AppConfig tagShareEndpoint: ${e.message}")
            null
        }) ?: defaultValue
    }

    fun getTagCanDownloadEndpoint(context: Context): String {
        val configContent = getLocalConfig(context, APP_CONFIG_FILE)
        return try {
            configContent?.let {
                val json = JSONObject(it)
                parseLinkConfig(json, "tagCanDownloadEndpoint", null)
                    ?.takeIf { cfg -> cfg.type.equals("url", ignoreCase = true) && cfg.value.isNotBlank() }
                    ?.value
            }
        } catch (e: Exception) { null } ?: DEFAULT_TAG_CAN_DOWNLOAD_ENDPOINT
    }

    fun getTagDownloadEndpoint(context: Context): String {
        val configContent = getLocalConfig(context, APP_CONFIG_FILE)
        return try {
            configContent?.let {
                val json = JSONObject(it)
                parseLinkConfig(json, "tagDownloadEndpoint", null)
                    ?.takeIf { cfg -> cfg.type.equals("url", ignoreCase = true) && cfg.value.isNotBlank() }
                    ?.value
            }
        } catch (e: Exception) {
            null
        } ?: DEFAULT_TAG_DOWNLOAD_ENDPOINT
    }

    fun getAppConfigUserCountEndpoint(context: Context): AppLinkConfig {
        val defaultValue = AppLinkConfig(type = "url", value = DEFAULT_USER_COUNT_ENDPOINT)
        val configContent = getLocalConfig(context, APP_CONFIG_FILE) ?: return defaultValue
        return (try {
            val json = JSONObject(configContent)
            parseLinkConfig(json, "userCountEndpoint", null)?.takeIf {
                it.type.equals("url", ignoreCase = true) && it.value.isNotBlank()
            }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error parsing AppConfig userCountEndpoint: ${e.message}")
            null
        }) ?: defaultValue
    }

    fun getAnomalyReportEndpoint(context: Context): AppLinkConfig {
        val defaultValue = AppLinkConfig(type = "url", value = DEFAULT_ANOMALY_REPORT_ENDPOINT)
        val configContent = getLocalConfig(context, APP_CONFIG_FILE) ?: return defaultValue
        return (try {
            val json = JSONObject(configContent)
            parseLinkConfig(json, "anomalyReportEndpoint", null)?.takeIf {
                it.type.equals("url", ignoreCase = true) && it.value.isNotBlank()
            }
        } catch (e: Exception) {
            null
        }) ?: defaultValue
    }

    fun getAnomalyUidsEndpoint(context: Context): AppLinkConfig {
        val defaultValue = AppLinkConfig(type = "url", value = DEFAULT_ANOMALY_UIDS_ENDPOINT)
        val configContent = getLocalConfig(context, APP_CONFIG_FILE) ?: return defaultValue
        return (try {
            val json = JSONObject(configContent)
            parseLinkConfig(json, "anomalyUidsEndpoint", null)?.takeIf {
                it.type.equals("url", ignoreCase = true) && it.value.isNotBlank()
            }
        } catch (e: Exception) {
            null
        }) ?: defaultValue
    }

    fun getNicknameEndpoint(context: Context): AppLinkConfig {
        val defaultValue = AppLinkConfig(type = "url", value = DEFAULT_NICKNAME_ENDPOINT)
        val configContent = getLocalConfig(context, APP_CONFIG_FILE) ?: return defaultValue
        return (try {
            val json = JSONObject(configContent)
            parseLinkConfig(json, "nicknameEndpoint", null)?.takeIf {
                it.type.equals("url", ignoreCase = true) && it.value.isNotBlank()
            }
        } catch (e: Exception) {
            null
        }) ?: defaultValue
    }

    /**
     * 检查创想三维耗材列表
     */
    private suspend fun checkCrealityMaterialList(context: Context, onUpdateAvailable: (String, () -> Unit) -> Unit) {
        try {
            val remoteContent = NetworkUtils.fetchFile(CREALITY_MATERIAL_PRIMARY, CREALITY_MATERIAL_BACKUP)
            if (remoteContent != null) {
                val remoteHash = NetworkUtils.calculateHash(remoteContent)
                val localHash = getLocalFileHash(context, CREALITY_MATERIAL_FILE)
                if (remoteHash != localHash) {
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable("创想三维耗材列表有更新，是否更新？") {
                            saveFile(context, CREALITY_MATERIAL_FILE, remoteContent)
                            try {
                                val dbHelper = com.m0h31h31.bamburfidreader.FilamentDbHelper(context)
                                com.m0h31h31.bamburfidreader.syncCrealityMaterialDatabase(context, dbHelper)
                                com.m0h31h31.bamburfidreader.logDebug("创想三维耗材列表更新成功，数据库已更新")
                            } catch (e: Exception) {
                                com.m0h31h31.bamburfidreader.logDebug("更新创想三维数据库失败: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Error checking creality_material_list.json: ${e.message}")
        }
    }

    fun getUpdateEndpoint(context: Context): AppLinkConfig {
        val defaultValue = AppLinkConfig(type = "url", value = DEFAULT_UPDATE_ENDPOINT)
        val configContent = getLocalConfig(context, APP_CONFIG_FILE) ?: return defaultValue
        return (try {
            val json = JSONObject(configContent)
            parseLinkConfig(json, "updateEndpoint", null)?.takeIf {
                it.type.equals("url", ignoreCase = true) && it.value.isNotBlank()
            }
        } catch (e: Exception) {
            null
        }) ?: defaultValue
    }

    private fun parseLinkConfig(
        json: JSONObject,
        key: String,
        defaultValue: AppLinkConfig?
    ): AppLinkConfig? {
        val rawValue = json.opt(key) ?: return defaultValue
        return when (rawValue) {
            is JSONObject -> {
                val type = rawValue.optString("type", defaultValue?.type ?: "url")
                val value = rawValue.optString("value", defaultValue?.value.orEmpty())
                AppLinkConfig(type = type, value = value)
            }

            is String -> {
                if (rawValue.isBlank()) {
                    defaultValue
                } else {
                    val inferredType = if ("://" in rawValue && !rawValue.startsWith("http", ignoreCase = true)) {
                        "scheme"
                    } else {
                        "url"
                    }
                    AppLinkConfig(type = inferredType, value = rawValue)
                }
            }

            else -> defaultValue
        }
    }
}
