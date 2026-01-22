package com.m0h31h31.bamburfidreader

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech

fun openTtsSettings(context: Context): Boolean {
    val intents = listOf(
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent("android.speech.tts.engine.TTS_SETTINGS"),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    val packageManager = context.packageManager
    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) != null) {
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                logDebug("打开语音设置失败: ${e.message}")
                false
            }
        }
    }
    return false
}
