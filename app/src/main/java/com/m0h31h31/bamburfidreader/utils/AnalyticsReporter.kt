package com.m0h31h31.bamburfidreader.utils

import android.content.Context
import android.os.Build
import com.m0h31h31.bamburfidreader.BuildConfig
import org.json.JSONObject
import java.util.UUID

object AnalyticsReporter {
    private const val PREFS_NAME = "analytics_prefs"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_INSTALL_REPORTED = "install_reported"

    suspend fun reportInstallAndLaunch(context: Context) {
        val endpoint = ConfigManager.getAppConfigUserCountEndpoint(context).value
        if (endpoint.isBlank()) return
        val headers = buildMap {
            if (BuildConfig.EVENT_API_KEY.isNotBlank()) {
                put("X-API-Key", BuildConfig.EVENT_API_KEY)
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val installId = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val commonPayload = JSONObject().apply {
            put("install_id", installId)
            put("package_name", context.packageName)
            put("version_name", packageInfo.versionName.orEmpty())
            put("version_code", packageInfo.longVersionCode)
            put("platform", "android")
            put("sdk_int", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER.orEmpty())
            put("model", Build.MODEL.orEmpty())
            put("timestamp_ms", System.currentTimeMillis())
        }

        if (!prefs.getBoolean(KEY_INSTALL_REPORTED, false)) {
            val installPayload = JSONObject(commonPayload.toString()).apply {
                put("event", "install")
            }
            val installSent = NetworkUtils.postJson(endpoint, installPayload, headers)
            if (installSent) {
                prefs.edit().putBoolean(KEY_INSTALL_REPORTED, true).apply()
            }
        }

        val launchPayload = JSONObject(commonPayload.toString()).apply {
            put("event", "launch")
        }
        NetworkUtils.postJson(endpoint, launchPayload, headers)
    }
}
