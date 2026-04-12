package com.m0h31h31.bamburfidreader.utils

import android.content.Context
import com.m0h31h31.bamburfidreader.RawTagReadData
import com.m0h31h31.bamburfidreader.logDebug
import org.json.JSONArray
import org.json.JSONObject

object TagShareUploader {

    private const val PREFS_NAME = "tag_share_prefs"
    private const val KEY_UPLOADED_UIDS = "uploaded_uids"

    // ── 完整性判断 ───────────────────────────────────────────────────────────

    /**
     * 标签完整性判断：uid 非空，且至少读到部分 block 数据。
     */
    fun isComplete(rawData: RawTagReadData): Boolean {
        return rawData.uidHex.isNotBlank() &&
                rawData.rawBlocks.any { it != null && it.isNotEmpty() }
    }

    // ── 本地已上传 UID 缓存 ───────────────────────────────────────────────────

    private fun getUploadedUids(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_UPLOADED_UIDS, emptySet())!!.toMutableSet()
    }

    private fun markUploaded(context: Context, uid: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = getUploadedUids(context)
        set.add(uid.uppercase().trim())
        prefs.edit().putStringSet(KEY_UPLOADED_UIDS, set).apply()
    }

    // ── 上传 ─────────────────────────────────────────────────────────────────

    /**
     * 上传标签原始数据（拓竹 / 快造通用）：发送 brand、uid、blocks、keys、device_id。
     * 若本地已记录该 UID 上传成功（或服务端已有），直接跳过，避免冗余请求。
     */
    suspend fun uploadRawTag(context: Context, brand: String, rawData: RawTagReadData): Boolean {
        val uid = rawData.uidHex.uppercase().trim()
        if (uid in getUploadedUids(context)) {
            logDebug("TagShareUploader: uid=$uid 已上传过，跳过")
            return true
        }
        val endpoint = ConfigManager.getTagShareEndpoint(context)
        logDebug("TagShareUploader.uploadRawTag endpoint=${endpoint.value} isUsable=${endpoint.isUsable} brand=$brand uid=$uid")
        if (!endpoint.isUsable) {
            logDebug("TagShareUploader: tagShareEndpoint 未配置，跳过上传")
            return false
        }
        val deviceId = AnalyticsReporter.getInstallId(context)
        return try {
            val ok = NetworkUtils.postJson(endpoint.value, buildRawPayload(brand, rawData, deviceId), AnalyticsReporter.apiKeyHeaders())
            if (ok) {
                // 服务端返回 2xx（无论是新插入还是已存在），本地标记为已上传
                markUploaded(context, uid)
                logDebug("TagShareUploader: 上传成功 brand=$brand uid=$uid")
            } else {
                logDebug("TagShareUploader: 上传失败 brand=$brand uid=$uid")
            }
            ok
        } catch (e: Exception) {
            logDebug("TagShareUploader: 上传异常: ${e.message}")
            false
        }
    }

    // ── Payload 构建 ─────────────────────────────────────────────────────────

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun buildRawPayload(brand: String, rawData: RawTagReadData, deviceId: String): JSONObject {
        val blocksArray = JSONArray().apply {
            rawData.rawBlocks.forEachIndexed { blockIndex, block ->
                val blockInSector = blockIndex % 4
                if (blockInSector == 3 && block != null) {
                    // 重建 trailer：用真实密钥替换硬件返回的 0x00 占位
                    val sector = blockIndex / 4
                    val trailer = block.copyOf()
                    rawData.sectorKeys.getOrNull(sector)?.first
                        ?.takeIf { it.size == 6 }?.copyInto(trailer, 0)
                    rawData.sectorKeys.getOrNull(sector)?.second
                        ?.takeIf { it.size == 6 }?.copyInto(trailer, 10)
                    put(trailer.toHex())
                } else {
                    put(block?.toHex() ?: "")
                }
            }
        }
        val keysArray = JSONArray().apply {
            rawData.sectorKeys.forEach { (keyA, keyB) ->
                put(JSONObject().apply {
                    put("a", keyA?.toHex() ?: "")
                    put("b", keyB?.toHex() ?: "")
                })
            }
        }
        return JSONObject().apply {
            put("brand", brand)
            put("uid", rawData.uidHex)
            put("blocks", blocksArray)
            put("keys", keysArray)
            put("device_id", deviceId)
        }
    }
}
