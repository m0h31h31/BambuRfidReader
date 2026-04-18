package com.m0h31h31.bamburfidreader.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

object NetworkUtils {
    private const val TIMEOUT_MS = 10000 // 10秒超时

    /**
     * 从网络获取文件内容
     * @param primaryUrl 主要地址
     * @param backupUrl 备用地址
     * @return 文件内容字节数组，失败返回null
     */
    suspend fun fetchFile(primaryUrl: String, backupUrl: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                fetchFileWithTimeout(primaryUrl)
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("Failed to fetch from primary URL: $primaryUrl, error: ${e.message}")
                try {
                    fetchFileWithTimeout(backupUrl)
                } catch (e: Exception) {
                    com.m0h31h31.bamburfidreader.logDebug("Failed to fetch from backup URL: $backupUrl, error: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * 带超时的文件获取
     */
    private fun fetchFileWithTimeout(urlString: String): ByteArray {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }

        return try {
            val inputStream = connection.inputStream
            readInputStream(inputStream)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 读取输入流内容
     */
    private fun readInputStream(inputStream: InputStream): ByteArray {
        val buffer = ByteArray(4096)
        val outputStream = ByteArrayOutputStream()
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
        }

        return outputStream.toByteArray()
    }

    /**
     * 计算字节数组的SHA-256哈希值
     */
    fun calculateHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算文件的SHA-256哈希值
     */
    fun calculateFileHash(filePath: String): String? {
        return try {
            val fileBytes = java.io.File(filePath).readBytes()
            calculateHash(fileBytes)
        } catch (e: Exception) {
            com.m0h31h31.bamburfidreader.logDebug("Failed to calculate file hash: ${e.message}")
            null
        }
    }

    suspend fun getJson(
        urlString: String,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        return withContext(Dispatchers.IO) {
            com.m0h31h31.bamburfidreader.logDebug("NetworkUtils GET $urlString")
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) ->
                    if (value.isNotBlank()) setRequestProperty(key, value)
                }
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    com.m0h31h31.bamburfidreader.logDebug("NetworkUtils GET $urlString → $code  body=$errBody")
                    return@withContext null
                }
                val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                com.m0h31h31.bamburfidreader.logDebug("NetworkUtils GET $urlString → $code OK")
                JSONObject(body)
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("NetworkUtils GET $urlString exception: ${e.message}")
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * GET，返回 JSONObject。网络或解析失败返回 null。
     */
    suspend fun getJson(
        urlString: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val fullUrl = if (query.isBlank()) urlString else "$urlString?$query"
        return getJson(fullUrl, headers)
    }

    /**
     * POST JSON，带下载进度回调，流式写入目标文件。
     * 成功返回 (200-299, null)，业务错误返回 (4xx/5xx, errorBodyBytes)，网络异常返回 null。
     * onProgress(0..100) 在 IO 线程回调，调用方自行 post 到主线程。
     */
    suspend fun postJsonDownloadToFile(
        urlString: String,
        payload: JSONObject,
        destFile: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Int) -> Unit)? = null
    ): Pair<Int, ByteArray?>? {
        return withContext(Dispatchers.IO) {
            com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(download) $urlString")
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = 120000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errBody = connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                    com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(download) → $code")
                    return@withContext Pair(code, errBody)
                }
                val total = connection.contentLengthLong
                var downloaded = 0L
                val buf = ByteArray(8192)
                destFile.outputStream().use { out ->
                    connection.inputStream.use { inp ->
                        var read: Int
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress?.invoke((downloaded * 100 / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                }
                onProgress?.invoke(100)
                com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(download) → $code size=${downloaded}B")
                Pair(code, null)
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(download) exception: ${e.message}")
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * POST JSON，返回服务端响应的 JSONObject。网络或解析失败返回 null。
     */
    suspend fun postJsonGetResponse(
        urlString: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        return withContext(Dispatchers.IO) {
            com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(json) $urlString")
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) ->
                    if (value.isNotBlank()) setRequestProperty(key, value)
                }
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(json) $urlString → $code body=$errBody")
                    return@withContext null
                }
                val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(json) $urlString → $code OK")
                JSONObject(body)
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST(json) $urlString exception: ${e.message}")
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun postJson(
        urlString: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): Boolean {
        return withContext(Dispatchers.IO) {
            com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST $urlString payload=${payload}")
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (key, value) ->
                    if (value.isNotBlank()) {
                        setRequestProperty(key, value)
                    }
                }
            }

            return@withContext try {
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST $urlString → $code  body=$errBody")
                    false
                } else {
                    com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST $urlString → $code OK")
                    true
                }
            } catch (e: Exception) {
                com.m0h31h31.bamburfidreader.logDebug("NetworkUtils POST $urlString exception: ${e.message}")
                false
            } finally {
                connection.disconnect()
            }
        }
    }
}
