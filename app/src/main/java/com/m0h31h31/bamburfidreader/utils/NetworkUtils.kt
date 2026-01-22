package com.m0h31h31.bamburfidreader.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object NetworkUtils {
    private const val TAG = "NetworkUtils"
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
                Log.e(TAG, "Failed to fetch from primary URL: $primaryUrl", e)
                try {
                    fetchFileWithTimeout(backupUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch from backup URL: $backupUrl", e)
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
            Log.e(TAG, "Failed to calculate file hash", e)
            null
        }
    }
}
