package com.m0h31h31.bamburfidreader

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m0h31h31.bamburfidreader.ui.navigation.AppNavigation
import com.m0h31h31.bamburfidreader.ui.screens.NdefWriteRequest
import com.m0h31h31.bamburfidreader.ui.screens.NdefWriteType
import com.m0h31h31.bamburfidreader.ui.theme.AppUiStyle
import com.m0h31h31.bamburfidreader.ui.theme.ColorPalette
import com.m0h31h31.bamburfidreader.ui.theme.ThemeMode
import com.m0h31h31.bamburfidreader.ui.theme.BambuRfidReaderTheme
import com.m0h31h31.bamburfidreader.util.normalizeColorValue
import androidx.core.content.FileProvider
import com.m0h31h31.bamburfidreader.utils.AnalyticsReporter
import com.m0h31h31.bamburfidreader.utils.UpdateInfo
import com.m0h31h31.bamburfidreader.utils.ConfigManager
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.exception.ZipException as Zip4jException
import java.io.IOException
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
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
private const val FILAMENT_DB_VERSION = 21
private const val CREALITY_MATERIAL_FILE = "creality_material_list.json"
private const val CREALITY_MATERIAL_TABLE = "creality_materials"
private const val FILAMENT_TABLE = "filaments"
private const val FILAMENT_TYPE_MAPPING_TABLE = "filament_type_mapping"
private const val FILAMENT_META_TABLE = "meta_v2"
private const val FILAMENT_META_KEY_LAST_MODIFIED = "filaments_last_modified"
private const val FILAMENT_META_KEY_LOCALE = "filaments_locale"
private const val TRAY_UID_TABLE = "filament_inventory"
private const val SHARE_TAGS_TABLE = "share_tags"
private const val SNAPMAKER_SHARE_TAGS_TABLE = "snapmaker_share_tags"
private const val ANOMALY_UIDS_TABLE = "anomaly_uids"
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
private const val RW_RECONNECT_DELAY_MS = 35L
private const val UI_PREFS_NAME = "ui_prefs"
private const val KEY_VOICE_ENABLED = "voice_enabled"
private const val KEY_UI_STYLE = "ui_style"
private const val KEY_BOOST_REMIND_LAST_MS = "boost_remind_last_ms"
private const val BOOST_REMIND_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000   // 一周
private const val BOOST_DESIGN_URI = "bambulab://bbl/design/model/detail?design_id=2020787&appSharePlatform=copy"
private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_INVENTORY_ENABLED = "inventory_enabled"
private const val KEY_HIDE_COPIED_TAGS = "hide_copied_tags"
private const val KEY_DUAL_TAG_MODE = "dual_tag_mode"
private const val KEY_TAG_VIEW_MODE = "tag_view_mode"
private const val KEY_COLOR_PALETTE = "color_palette"
private const val KEY_USER_AGREEMENT_VERSION = "user_agreement_version"
private const val CURRENT_USER_AGREEMENT_VERSION = 1
private const val KEY_BAMBU_TAG_ENABLED = "bambu_tag_enabled"
private const val KEY_CREALITY_ENABLED = "creality_enabled"
private const val KEY_SNAPMAKER_TAG_ENABLED = "snapmaker_tag_enabled"
private const val KEY_AUTO_SHARE_TAG = "auto_share_tag"
private const val KEY_AUTO_DETECT_BRAND = "auto_detect_brand"
private const val KEY_NOTICE_GUIDE_SHOWN = "notice_guide_shown"

// Creality AES keys
private val CREALITY_KEY_DERIVE = byteArrayOf(
    113, 51, 98, 117, 94, 116, 49, 110,
    113, 102, 90, 40, 112, 102, 36, 49
)
private val CREALITY_KEY_DATA = byteArrayOf(
    72, 64, 67, 70, 107, 82, 110, 122,
    64, 75, 65, 116, 66, 74, 112, 50
)
private val CREALITY_LENGTH_TO_WEIGHT = mapOf(
    "0330" to "1 KG", "0247" to "750 G", "0198" to "600 G",
    "0165" to "500 G", "0082" to "250 G"
)
private val CREALITY_WEIGHT_TO_LENGTH: Map<String, String> =
    CREALITY_LENGTH_TO_WEIGHT.entries.associate { (k, v) -> v to k }

// ── 快造 (Snapmaker) RFID 密钥派生盐值 ────────────────────────────────────────
private val SNAPMAKER_SALT_A = "Snapmaker_qwertyuiop[,.;]".toByteArray(Charsets.US_ASCII)
private val SNAPMAKER_SALT_B = "Snapmaker_qwertyuiop[,.;]_1q2w3e".toByteArray(Charsets.US_ASCII)

private val SNAPMAKER_MAIN_TYPE_MAP = mapOf(
    0 to "Reserved", 1 to "PLA", 2 to "PETG", 3 to "ABS", 4 to "TPU", 5 to "PVA"
)
private val SNAPMAKER_SUB_TYPE_MAP = mapOf(
    0 to "Reserved", 1 to "Basic", 2 to "Matte", 3 to "SnapSpeed",
    4 to "Silk", 5 to "Support", 6 to "HF", 7 to "95A", 8 to "95A HF"
)

// 快造 RSA 公钥 (PKCS#1 PEM, key version 0‑9)
private val SNAPMAKER_RSA_KEYS = arrayOf(
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA8oEF7YuKO863TbUxnrvY
H1JFrvCnMapm8Ho952KlfNWbf6IEDMlX6QJpBuvUkrkjWpLJJQurIWL3KFeLUhCh
POrYdiGrdsUlp4YO037iLSlgmzo1dUdgbawAcGox1PvR/Naw5ADibubO2rN49WQR
+BkxxigvoWHSFetaoMCswQ5B/niq3byhzktgmWOcv71F4yFwcxivF8R+s0gSBL4i
/1zNeSUZkbvP4/T0B08i3D+e6fl9xpCnINZ3P9OWcx+p3SB2o4TdmAeKV4hkT9n7
o+/OWr92fx6qbiNKJr04oMhrRsFK6w7hitp2n8RGS64w9lhtplnBgxtbgxAYyUnp
qwIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA8nbtQNABbc5PkyzI0A5m
VH/E8y23Wld0iykvTOoBYJOrPwJDmXsnSyyX84Nv6voSr8FYv3Fb2SqSdOgQLFqp
BXvntXew8rPpq5Ll8gSzLRxE1VmEOVtZWCTJ4Wxwwi79rrFmpa/nAtUeYZIGiiud
w2MzCHXW5G3c1FWhQ0C8vUUMfBQXmGnoHGsul6R8xld6CDCWY8ia/FvfR+KCtMRn
VYyYguYsq4rODWJHiFCOef4FZconUR3RTh0ojvq78CsHk94goxidWzZoKcVnvWhh
bOixTjU37W4JDECEOui3ObMMvJkzxkZo1irlAH7jTiPqhP94U/JbRDpBlHOOn67b
GQIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxZQPYewwMFaPlcEHq+SH
QS1C1NhVmAaY56qxLyHJ4aNc2iWdCx4/9ZKY4CL6xkeCD88Zndv/xzImplRdoAzo
whD47Vm4iuq8+NqHUI8na6ISd+MZ/O6/eo/ggaEZBX8lR+Yf0qfWtntsI9flUOoJ
mq1IXvNXqOxflUmPyffT40QSkAN4Rr3scB3ozlxuJZehWM/lUmZ1H5PQDwAqsM0T
Rj6ChzVmUbSvwEvbDTwpXkpMA0C5//OW0T//IKDEBYxEl928vYbraLRDRIetgdaD
o+77+ztfOv4AyP/ipikprHwIWi7yga5KUXq/XpNPy6cPISZD+/LBUJBxLELspREP
rQIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvK8cJyeFeTkFgkSLCCAg
EgR9KAvIHmvK8CRdtn+W6PiIbN04MFIg8jiYW/3fq+AcBFFMo+HtR2gym8JNVx2I
RDI4WdfbR/0gaIHjOQ41OwlXmqqSkDsFmjxVI6bDRZYpHkOfkC+9Vi1Aii4l/Yq9
O7s+2j4zP9GoUWWJPb3mW07Vu+EnHB/XIuaoDJVQAS+ov3xTotCeKdcdgySnNP5g
kOvWUvWtwNQldCRcQ0eo3j5RO+4J4IRK2J8q7BrdV/gbJUE/BBPIOuURPLzNJJO3
wgx4PEwlb5uYEUL35ARL7NzL8ZOxebzs5H4tXuWrBhALw6O33Tfg3TmTmwR2JUpv
7QIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvafhk7Bdb3F+5B9w7YXv
chrNzl09QkZc27NLxL0ViRitGQhX9KC/xVg+XkBGI8XfioAwYkJ3jYgwmci5gJOL
ofPyNXcFtvtzq2NZNuDZY26krrXLORhS1o8ue92RB2gM92Rc2heWVrsvLycNl2Qz
OUjUEGmWpSMo98xIsgkTZJ4aYxWVN86yqknOcHVpTmcr5SBRB90K9hTRtsaMD97O
FYVc7AA/TGwqFJOnXXzWczWtg7kUY2vqCHwsvKs3G/EIFKOIe1n37V94OcxHTySC
co9Kc6Y0bGFIwIruinH1WkFVt6TAzo+0ZdZy5Sq493AG9y1RZ5nYj5qUmc1PMmrD
gwIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxWdxd7qeouSFbZ2Sldv3
apDrgAupOYiDRkO85C+qkZaezOzqW0EsOV0x7nG/smw++TRfHyGIK4gXCdg1JfNR
WYjqckRdnLYMzGdDk24VV5Bbrsgska0v0Oy1ucz3CYu+F22ais00OqK0MY0B96MI
/B/0pRSTAIyxvC6LjhHy8DYyPdqNF9EMikKfAfcn7ytsH1PoSSGVtrZqyNe5OLrW
yAw+FQsTg/VFJcYxPTQJ1ymwQmDCdKgApe3PVajyYswoIA7R0S8ujau0aAFEO3dU
GDEwjOnaHfwFlg3OKMFJTxc2sl/WEB8xtWuKl0Guf0VnzWJ6noxqf/DiaN1fuHG0
AwIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqF+YJNHLHC6c25oTDgNg
liahUxWBPSkgght1/gJu5vBRDKWEn6i/RuKAFdTOsH+Hlvr5qWms7bBUHx78UMF+
FF1Nq9tb4jhFuqq4HWsBBjNnU6O0JhFTjKJU2nudmphXlpdLQfcKSIYMQe795GHL
izh8WsNTcTHNNBkjhi7y4c4RUqnJso0L6vrf0B3EB/9DDUJitrwfw+1/OrKOEVEP
624sEa802cHfb+BG9zKBXjFwzYCYF9gWey9yeA3UA7EYmPpqA1lqNv8m0r7YjZ4n
uGBDjs+AXaGtdqrW3IUtkUF2vWwNSRncbcXi3mNfzslrtPhsDVAFki4vDSw7yNht
2wIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuKWRCTTgxPltfflWHdhu
2ITxWC/LTEl7OtatNWFhMFQZF2J5SN/45bjH6xIPTcDglTSl2/UMC1D/ugiq+j0z
dGSdE7xn3ZSzLTMCwgRkvXmd8aQgafBYbB7E6oAgus+6lRXZPwnMfZAe0yaJNHyt
1Wd8ZUlRY7BHSPPtmG1liVEzxoTb6urB6mK49r24+oC7xa65q5NSdlZWSTeaK4Xt
DVVDiwe+uubNTm59KnVAKgBMNd3qN942pH6fo/dBz++BzJVEG/qJewHUTGZAeIl+
CgqhSEbmEIgolsDgaKY99ZWa2FWJdo+ohYhmjc92TyB9kWw6yIwez+tlRUkssLGt
SwIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAt7XOTs6P2xB8v8/xWVdR
wVefphRDXSuv74RObtr0pwLTc7BytkcDw8r60BNPv9hGDpW2S1szxqS8x4EaOHP7
81qNpIUULlUdXxty1RvpSdfRb044kpwl7A/s4OEakkyJZF1ed+Qte1FqOFDDIZ+l
g+Co8FjOwWixoSyIlR22mEP7r6Y98GL5tnSohkVoGAgEipswWb6549mssjZmES+J
hB0axY6Dl/LlDYxN6jjUZwSIo7bw0GXGm9ScW2qTVaT1m2A9etpD6OIG+iQVLQqP
whVBs5q0o/EM4nBN88RBsF2OmfkcZPJ2NdX6o3qx+pCZ9NDgkHjGDZdnGEnM5Lu2
dwIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent(),
    """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAz/d5C5FpqlcF7NbUEvBN
fiDJWH0BF63PEwHPiX+cS6l+q4NqqYI167u1pGkZGJV1njgGYFTM08x2KO7/bk6o
CWcGuKWNM8Tp1+tv3XioNGVCnIpHmdUx5F9qcXlPtDx74wQk/+JZLQ/sLnLvHcV3
YTaz55fpyzVUHkgXusdVynSyAt3ywWWQRcjp3sspGa/udC0j6LCvrzqLACv3gMGA
Id0b6REzjSn03UzkwBIwSb8DszieeNhaCOK4M/TxPFNyrhQRYcAvhiZJu+tylqJs
VP+gaWFvElFeFkxcHvYXHdJPlJLjYeT51hm/pdll26yYLhpeBa0inHwSqv4D3jFZ
PQIDAQAB
-----END RSA PUBLIC KEY-----""".trimIndent()
)

private val WRITE_HKDF_SALT = byteArrayOf(
    0x9a.toByte(), 0x75.toByte(), 0x9c.toByte(), 0xf2.toByte(),
    0xc4.toByte(), 0xf7.toByte(), 0xca.toByte(), 0xff.toByte(),
    0x22.toByte(), 0x2c.toByte(), 0xb9.toByte(), 0x76.toByte(),
    0x9b.toByte(), 0x41.toByte(), 0xbc.toByte(), 0x96.toByte()
)
private val WRITE_INFO_A = "RFID-A\u0000".toByteArray(Charsets.US_ASCII)
private val WRITE_INFO_B = "RFID-B\u0000".toByteArray(Charsets.US_ASCII)

@Composable
private fun NoticeGuideDialog(
    onDismiss: () -> Unit,
    onGoToNotice: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                text = androidx.compose.ui.res.stringResource(R.string.notice_guide_title)
            )
        },
        text = {
            androidx.compose.material3.Text(
                text = androidx.compose.ui.res.stringResource(R.string.notice_guide_message)
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onGoToNotice) {
                androidx.compose.material3.Text(
                    text = androidx.compose.ui.res.stringResource(R.string.notice_guide_go)
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(
                    text = androidx.compose.ui.res.stringResource(R.string.notice_guide_skip)
                )
            }
        }
    )
}

@Composable
private fun BoostReminderDialog(
    onDismiss: () -> Unit,
    onBoost: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "⏰ 别让助力券过期！",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "如果你有多余的助力券，不妨把它用在有意义的地方——\n\n给作者的 MakerWorld 主页 @m0h31h31 助力！主页上的其他模型同样欢迎助力 🎉\n\n📌 每年可助力 5 个模型，每个模型最多助力 2 次。\n\n你的每一次助力，都是对作者坚持创作最真诚的支持 ❤️",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("知道了")
                    }
                    Button(onClick = onBoost) {
                        Text("去助力 🚀")
                    }
                }
            }
        }
    }
}

@Composable
private fun UserAgreementDialog(
    onDecline: () -> Unit,
    onAccept: () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.user_agreement_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.user_agreement_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.user_agreement_content),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    OutlinedButton(onClick = onDecline) {
                        Text(text = stringResource(R.string.user_agreement_decline))
                    }
                    Button(onClick = onAccept) {
                        Text(text = stringResource(R.string.user_agreement_accept))
                    }
                }
            }
        }
    }
}

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
    val originalMaterial: String = "",
    val notes: String = "",
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
    val remainingGrams: Int? = null,
    val originalMaterial: String = "",
    val notes: String = ""
)

data class ShareTagDbMeta(
    val id: Long = -1L,
    val copyCount: Int = 0,
    val verified: Boolean = false
)

data class ShareTagDbRow(
    val id: Long,
    val fileUid: String,
    val trayUid: String?,
    val materialType: String?,
    val colorUid: String?,
    val colorName: String?,
    val colorType: String?,
    val colorValues: String?,
    val rawData: String?,
    val copyCount: Int,
    val verified: Boolean,
    val productionDate: String?
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
    val rawBlocks: List<ByteArray?>,
    val dbId: Long = -1L,
    val copyCount: Int = 0,
    val verified: Boolean = false,
    val productionDate: String = ""
)

data class CModifyRecoveryInfo(
    val originalUid: String,
    val targetUid: String,
    val originalKeysA: List<String>,
    val originalKeysB: List<String>,
    val targetKeysA: List<String>,
    val targetKeysB: List<String>
)

data class CrealityMaterial(
    val materialId: String,
    val brand: String,
    val materialType: String,
    val name: String,
    val minTemp: Int,
    val maxTemp: Int,
    val diameter: String
)

data class CrealityTagData(
    val materialId: String,
    val colorHex: String,
    val weight: String,
    val serial: String,
    val vendorId: String,
    val batch: String,
    val lengthCode: String,
    val rawPlaintext: String,
    val uidHex: String = "",
    val mfDate: String = ""
)

data class SnapmakerTagData(
    val vendor: String,
    val manufacturer: String,
    val mainType: String,
    val subType: String,
    val colorCount: Int,
    val rgb1: Int,
    val rgb2: Int,
    val rgb3: Int,
    val rgb4: Int,
    val rgb5: Int,
    val diameter: Int,   // unit: 0.01 mm, e.g. 175 = 1.75 mm
    val weight: Int,     // grams
    val dryingTemp: Int,
    val dryingTime: Int, // hours
    val hotendMaxTemp: Int,
    val hotendMinTemp: Int,
    val bedTemp: Int,
    val mfDate: String,
    val isOfficial: Boolean,
    val uidHex: String,
    val rsaKeyVersion: Int
)

enum class ReaderBrand { BAMBU, CREALITY, SNAPMAKER }

data class SnapmakerShareTagItem(
    val uid: String,
    val vendor: String,
    val manufacturer: String,
    val mainType: Int,
    val subType: Int = 0,
    val diameter: Int,   // unit: 0.01 mm
    val weight: Int,     // grams
    val rgb1: Int,
    val mfDate: String,
    val rawBlocks: List<ByteArray?>,
    val dbId: Long = -1L,
    val copyCount: Int = 0
)

data class CrealityWritePending(
    val materialId: String,
    val colorHex: String,
    val weight: String
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

private data class SelfTagPackageExport(
    val sourceDir: File,
    val files: List<File>,
    val zipName: String
)

class MainActivity : ComponentActivity() {
    private enum class FeedbackTone {
        SUCCESS,
        FAILURE
    }

    private var nfcAdapter: NfcAdapter? = null
    private var uiState by mutableStateOf(NfcUiState(status = "Waiting for RFID tag"))
    private var filamentDbHelper: FilamentDbHelper? = null
    private var voiceEnabled by mutableStateOf(false)
    private var uiStyle by mutableStateOf(AppUiStyle.NEUMORPHIC)
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var colorPalette by mutableStateOf(ColorPalette.OCEAN)
    private var bambuTagEnabled by mutableStateOf(true) // 控制拓竹RFID页面显示
    private var crealityEnabled by mutableStateOf(false) // 控制创想三维RFID页面显示
    private var snapmakerTagEnabled by mutableStateOf(false) // 控制快造复制页面显示
    private var snapmakerShareTagItems by mutableStateOf<List<SnapmakerShareTagItem>>(emptyList())
    private var snapmakerShareLoading by mutableStateOf(false)
    private var snapmakerWriteStatusMessage by mutableStateOf("")
    private var pendingSnapmakerWriteItem by mutableStateOf<SnapmakerShareTagItem?>(null)
    private var crealityStatusMessage by mutableStateOf("")
    private var crealityLastTagData by mutableStateOf<CrealityTagData?>(null)
    private var pendingCrealityWrite by mutableStateOf<CrealityWritePending?>(null)
    private var currentActiveRoute by mutableStateOf("reader")
    private var readAllSectors by mutableStateOf(false) // 控制是否读取全部扇区，默认关闭
    private var saveKeysToFile by mutableStateOf(false) // 控制是否额外导出秘钥文件
    private var forceOverwriteImport by mutableStateOf(false) // 控制导入标签包时是否覆盖同UID文件
    private var formatTagDebugEnabled by mutableStateOf(false) // 控制格式化标签调试弹窗
    private var inventoryEnabled by mutableStateOf(false) // 控制库存和数据页面显示
    private var autoDetectBrand by mutableStateOf(false)  // 自动识别RFID品牌并跳转
    private var autoShareTag by mutableStateOf(true)     // 读取完整数据后自动上传到共享服务器
    private var hideCopiedTags by mutableStateOf(true)   // 隐藏已复制标签
    private var dualTagMode by mutableStateOf(false)      // 双标签模式：复制2次才隐藏
    private var tagViewMode by mutableStateOf("list")     // 复制页视图：list/category
    private var readerBrand by mutableStateOf(ReaderBrand.BAMBU)   // 识别页品牌选择
    private var readerSnapmakerTagData by mutableStateOf<SnapmakerTagData?>(null)
    private var readerCrealityTagData by mutableStateOf<CrealityTagData?>(null)
    private var readerCrealityMaterial by mutableStateOf<CrealityMaterial?>(null)
    private var readerBrandStatus by mutableStateOf("")
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var ttsLanguageReady by mutableStateOf(false)
    private var lastSpokenKey: String? = null
    private var shouldNavigateToReader by mutableStateOf(false)
    private var shouldNavigateToTag by mutableStateOf(false)
    private var shouldNavigateToMisc by mutableStateOf(false)
    private var shouldScrollToNotice by mutableStateOf(false)
    private var tagPreselectedFileName by mutableStateOf<String?>(null)
    // 原始读卡临时缓存：readTag 仅负责写入；解析函数从此读取。
    private var latestRawTagData: RawTagReadData? = null
    private var latestSnapmakerRawData: RawTagReadData? = null
    private var shareTagItems by mutableStateOf<List<ShareTagItem>>(emptyList())
    private var writeStatusMessage by mutableStateOf("")
    private var pendingWriteItem by mutableStateOf<ShareTagItem?>(null)
    private var pendingVerifyItem by mutableStateOf<ShareTagItem?>(null)
    private var pendingCModifyItem by mutableStateOf<ShareTagItem?>(null)
    private var cModifyRecoveryInfo by mutableStateOf<CModifyRecoveryInfo?>(null)
    private var pendingClearFuid by mutableStateOf(false)
    private var pendingCuidTest by mutableStateOf(false)
    private var pendingNdefWriteRequest by mutableStateOf<NdefWriteRequest?>(null)
    private var shareLoading by mutableStateOf(false)
    private var shareRefreshStatusMessage by mutableStateOf("")
    private var shareRefreshStatusClearJob: Job? = null
    private var miscStatusMessage by mutableStateOf("")
    private var anomalyUids by mutableStateOf<Map<String, Int>>(emptyMap())
    private var pendingUpdateInfo by mutableStateOf<UpdateInfo?>(null)
    private var isDownloadingUpdate by mutableStateOf(false)
    private var updateDownloadId = -1L
    private var writeToolStatusMessage by mutableStateOf("")
    private var selfTagCount by mutableStateOf(0)
    private var debugInfoDialog: android.app.AlertDialog? = null
    private val debugInfoBuffer = StringBuilder()
    private val debugInfoLock = Any()
    // 防止 readerCallback 并发触发导致 "Close other technology first!"。
    private val readingInProgress = AtomicBoolean(false)
    // 防止共享目录重复并发扫描。
    private val shareLoadingInProgress = AtomicBoolean(false)
    private var toneGenerator: ToneGenerator? = null
    private val importTagPackageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                miscStatusMessage = uiString(R.string.misc_select_tag_package_canceled)
                return@registerForActivityResult
            }
            miscStatusMessage = uiString(R.string.misc_importing_tag_package)
            lifecycleScope.launch(Dispatchers.IO) {
                val message = importTagPackageFromZipUri(uri)
                withContext(Dispatchers.Main) {
                    miscStatusMessage = message
                    refreshShareTagItemsAsync()
                }
            }
        }
    private val importSnapmakerTagPackageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                miscStatusMessage = uiString(R.string.misc_select_tag_package_canceled)
                return@registerForActivityResult
            }
            miscStatusMessage = uiString(R.string.misc_importing_tag_package)
            lifecycleScope.launch(Dispatchers.IO) {
                val message = importSnapmakerTagPackageFromZipUri(uri)
                withContext(Dispatchers.Main) {
                    miscStatusMessage = message
                    refreshSnapmakerShareTagItemsAsync()
                }
            }
        }
    private val exportTagPackageLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(SHARE_IMPORT_ZIP_MIME)) { uri: Uri? ->
            if (uri == null) {
                miscStatusMessage = "已取消导出标签包"
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val result = exportSelfTagPackageToUri(uri)
                withContext(Dispatchers.Main) {
                    miscStatusMessage = result
                }
            }
        }

    private fun resetDebugInfoDialog(title: String = "调试信息") {
        synchronized(debugInfoLock) {
            debugInfoBuffer.clear()
        }
        if (!formatTagDebugEnabled) {
            runOnUiThread {
                try {
                    debugInfoDialog?.dismiss()
                } catch (_: Exception) {
                }
                debugInfoDialog = null
            }
            return
        }
        runOnUiThread {
            try {
                debugInfoDialog?.dismiss()
            } catch (_: Exception) {
            }
            debugInfoDialog = android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("")
                .setPositiveButton("关闭", null)
                .create().also { it.show() }
        }
    }

    private fun appendDebugInfoDialog(message: String) {
        if (!formatTagDebugEnabled) return
        val text = synchronized(debugInfoLock) {
            if (debugInfoBuffer.isNotEmpty()) {
                debugInfoBuffer.insert(0, '\n')
            }
            debugInfoBuffer.insert(0, message)
            debugInfoBuffer.toString()
        }
        runOnUiThread {
            val dialog = debugInfoDialog
            if (dialog == null || !dialog.isShowing) {
                debugInfoDialog = android.app.AlertDialog.Builder(this)
                    .setTitle("调试信息")
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .create().also { it.show() }
            } else {
                dialog.setMessage(text)
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
                    writeStatusMessage = uiString(R.string.copy_write_in_progress)
                } else if (pendingVerifyItem != null) {
                    writeStatusMessage = uiString(R.string.copy_verify_in_progress)
                } else if (pendingCModifyItem != null) {
                    writeStatusMessage = uiString(R.string.tag_c_modify_in_progress)
                } else if (pendingNdefWriteRequest != null) {
                    writeToolStatusMessage = uiString(R.string.copy_ndef_in_progress)
                } else if (pendingClearFuid) {
                    miscStatusMessage = "正在格式化"
                } else if (pendingCuidTest) {
                    miscStatusMessage = "正在检测..."
                } else if (pendingSnapmakerWriteItem != null) {
                    snapmakerWriteStatusMessage = "正在写入快造标签..."
                } else if (pendingCrealityWrite != null) {
                    crealityStatusMessage = uiString(R.string.creality_write_in_progress)
                }
            }
            if (pendingWriteItem != null) {
                val targetItem = pendingWriteItem
                val writeResult = if (targetItem != null) {
                    writeTagFromDump(tag, targetItem) { status ->
                        runOnUiThread { writeStatusMessage = status }
                    }
                } else {
                    uiString(R.string.copy_write_task_empty)
                }
                if (writeResult.contains("成功") || writeResult.contains("success", ignoreCase = true)) {
                    // 写入成功后自动校验（无需重复贴卡）
                    runOnUiThread { writeStatusMessage = "写入完成，正在自动校验..." }
                    val verifyResult = if (targetItem != null) {
                        verifyTagAgainstDump(tag, targetItem)
                    } else "校验失败：任务为空"
                    runOnUiThread {
                        if (verifyResult.contains("成功") || verifyResult.contains("success", ignoreCase = true)) {
                            playFeedbackTone(FeedbackTone.SUCCESS)
                            if (targetItem != null && targetItem.dbId > 0) {
                                filamentDbHelper?.writableDatabase?.let { db ->
                                    filamentDbHelper!!.incrementShareTagCopyCount(db, targetItem.dbId)
                                    filamentDbHelper!!.setShareTagVerified(db, targetItem.dbId, true)
                                }
                                shareTagItems = shareTagItems.map { si ->
                                    if (si.dbId == targetItem.dbId) si.copy(copyCount = si.copyCount + 1, verified = true) else si
                                }
                            }
                            pendingWriteItem = null
                            writeStatusMessage = "写入并校验成功"
                        } else {
                            // 校验失败但写入成功：递增次数，保留 pendingVerifyItem 供手动重试
                            playFeedbackTone(FeedbackTone.FAILURE)
                            if (targetItem != null && targetItem.dbId > 0) {
                                filamentDbHelper?.writableDatabase?.let { db ->
                                    filamentDbHelper!!.incrementShareTagCopyCount(db, targetItem.dbId)
                                }
                                shareTagItems = shareTagItems.map { si ->
                                    if (si.dbId == targetItem.dbId) si.copy(copyCount = si.copyCount + 1) else si
                                }
                            }
                            pendingWriteItem = null
                            pendingVerifyItem = targetItem
                            writeStatusMessage = "写入成功，自动校验失败：$verifyResult，请再次贴卡校验"
                        }
                    }
                } else {
                    runOnUiThread {
                        writeStatusMessage = writeResult
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                }
            } else if (pendingVerifyItem != null) {
                val targetItem = pendingVerifyItem
                val result = if (targetItem != null) {
                    verifyTagAgainstDump(tag, targetItem)
                } else {
                    uiString(R.string.copy_verify_task_empty)
                }
                runOnUiThread {
                    writeStatusMessage = result
                    if (result.contains("成功") || result.contains("success", ignoreCase = true)) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                        // 校验成功：标记已校验
                        if (targetItem != null && targetItem.dbId > 0) {
                            filamentDbHelper?.writableDatabase?.let { db ->
                                filamentDbHelper!!.setShareTagVerified(db, targetItem.dbId, true)
                            }
                            shareTagItems = shareTagItems.map { si ->
                                if (si.dbId == targetItem.dbId) si.copy(verified = true) else si
                            }
                        }
                        pendingVerifyItem = null
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                }
            } else if (pendingCModifyItem != null) {
                val targetItem = pendingCModifyItem
                val result = if (targetItem != null) {
                    writeCModifyTag(tag, targetItem) { status ->
                        runOnUiThread { writeStatusMessage = status }
                    }
                } else {
                    "C卡修改任务为空"
                }
                runOnUiThread {
                    writeStatusMessage = result
                    if (result.contains("成功")) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                        pendingCModifyItem = null
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                }
            } else if (pendingNdefWriteRequest != null) {
                val request = pendingNdefWriteRequest
                val result = if (request != null) {
                    writeNdefDataAndVerify(tag, request)
                } else {
                    uiString(R.string.copy_ndef_task_empty)
                }
                runOnUiThread {
                    writeToolStatusMessage = result
                    if (result.contains("成功") || result.contains("success", ignoreCase = true)) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                    pendingNdefWriteRequest = null
                }
            } else if (pendingClearFuid) {
                val result = clearFuidAndResetTag(tag) { status ->
                    runOnUiThread {
                        miscStatusMessage = status
                    }
                }
                runOnUiThread {
                    miscStatusMessage = result
                    if (result.contains("成功") || result.contains("success", ignoreCase = true)) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                        // 格式化成功：重置对应 share_tags 记录的复制次数和校验标记
                        val trayUid = uiState.trayUidHex
                        if (trayUid.isNotBlank()) {
                            filamentDbHelper?.writableDatabase?.let { db ->
                                filamentDbHelper!!.resetShareTagByTrayUid(db, trayUid)
                            }
                            shareTagItems = shareTagItems.map { si ->
                                if (si.trayUid == trayUid) si.copy(copyCount = 0, verified = false) else si
                            }
                        }
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                    pendingClearFuid = false
                }
            } else if (pendingCuidTest) {
                val result = testCuidCard(tag) { status ->
                    runOnUiThread { miscStatusMessage = status }
                }
                runOnUiThread {
                    miscStatusMessage = result
                    if (result == uiString(R.string.misc_cuid_available)) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                    pendingCuidTest = false
                }
            } else if (pendingSnapmakerWriteItem != null) {
                val targetItem = pendingSnapmakerWriteItem!!
                val writeResult = writeSnapmakerTagFromDump(tag, targetItem) { status ->
                    runOnUiThread { snapmakerWriteStatusMessage = status }
                }
                runOnUiThread {
                    if (writeResult.contains("成功") || writeResult.contains("success", ignoreCase = true)) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                        if (targetItem.dbId > 0) {
                            filamentDbHelper?.writableDatabase?.let { db ->
                                filamentDbHelper!!.incrementSnapmakerShareTagCopyCount(db, targetItem.dbId)
                            }
                            snapmakerShareTagItems = snapmakerShareTagItems.map { si ->
                                if (si.dbId == targetItem.dbId) si.copy(copyCount = si.copyCount + 1) else si
                            }
                        }
                        pendingSnapmakerWriteItem = null
                        snapmakerWriteStatusMessage = "写入成功"
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                        snapmakerWriteStatusMessage = writeResult
                    }
                }
            } else if (pendingCrealityWrite != null) {
                val target = pendingCrealityWrite!!
                val result = writeCrealityTag(tag, target)
                runOnUiThread {
                    crealityStatusMessage = result
                    if (result.contains("成功") || result.contains("success", ignoreCase = true)) {
                        playFeedbackTone(FeedbackTone.SUCCESS)
                        pendingCrealityWrite = null
                    } else {
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                }
            } else if (crealityEnabled && currentActiveRoute == "creality") {
                // On the Creality screen: only attempt Creality read, stay on current screen
                val crealityResult = readCrealityTag(tag)
                runOnUiThread {
                    if (crealityResult != null) {
                        crealityLastTagData = crealityResult
                        crealityStatusMessage = uiString(R.string.creality_read_success)
                        playFeedbackTone(FeedbackTone.SUCCESS)
                    } else {
                        crealityStatusMessage = uiString(R.string.creality_read_failed)
                        playFeedbackTone(FeedbackTone.FAILURE)
                    }
                }
            } else {
                // 自动品牌检测：派生秘钥认证sector0，切换品牌后读取
                if (autoDetectBrand) {
                    val detected = detectBrandBySector0(tag)
                    if (detected != null && detected != readerBrand) {
                        runOnUiThread {
                            readerBrand = detected
                            readerBrandStatus = if (detected == ReaderBrand.BAMBU) "" else uiString(R.string.status_waiting_tag)
                        }
                        Thread.sleep(100)
                    }
                }
                when (readerBrand) {
                    ReaderBrand.BAMBU -> {
                        val result = readTag(tag)
                        runOnUiThread {
                            uiState = result
                            shouldNavigateToReader = true
                            maybeSpeakResult(result)
                        }
                        val rawSnapshot = latestRawTagData
                        if (autoShareTag && rawSnapshot != null &&
                            com.m0h31h31.bamburfidreader.utils.TagShareUploader.isComplete(rawSnapshot)
                        ) {
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                com.m0h31h31.bamburfidreader.utils.TagShareUploader.uploadRawTag(
                                    applicationContext, "bambu", rawSnapshot
                                )
                            }
                        }
                    }
                    ReaderBrand.CREALITY -> {
                        val result = readCrealityTag(tag)
                        val material = result?.let {
                            filamentDbHelper?.getCrealityMaterialById(
                                filamentDbHelper!!.readableDatabase, it.materialId
                            )
                        }
                        runOnUiThread {
                            if (result != null) {
                                readerCrealityTagData = result
                                readerCrealityMaterial = material
                                readerBrandStatus = "读取成功"
                                playFeedbackTone(FeedbackTone.SUCCESS)
                            } else {
                                readerBrandStatus = "读取失败"
                                playFeedbackTone(FeedbackTone.FAILURE)
                            }
                        }
                    }
                    ReaderBrand.SNAPMAKER -> {
                        val result = readSnapmakerTag(tag)
                        runOnUiThread {
                            if (result != null) {
                                readerSnapmakerTagData = result
                                readerBrandStatus = "读取成功"
                                playFeedbackTone(FeedbackTone.SUCCESS)
                            } else {
                                readerBrandStatus = "读取失败"
                                playFeedbackTone(FeedbackTone.FAILURE)
                            }
                        }
                        val snapRaw = latestSnapmakerRawData
                        if (autoShareTag && snapRaw != null &&
                            com.m0h31h31.bamburfidreader.utils.TagShareUploader.isComplete(snapRaw)
                        ) {
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                com.m0h31h31.bamburfidreader.utils.TagShareUploader.uploadRawTag(
                                    applicationContext, "snapmaker", snapRaw
                                )
                            }
                        }
                    }
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
                this@MainActivity
            ) { message, updateAction ->
                runOnUiThread {
                    val builder = android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("配置更新")
                        .setMessage(message)

                    if (message == "版本更新请到原地址下载") {
                        builder.setPositiveButton("我知道了", null)
                    } else {
                        builder.setPositiveButton("我知道了") { _, _ ->
                            updateAction()
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("更新结果")
                                .setMessage("配置更新成功")
                                .setPositiveButton("我知道了", null)
                                .show()
                        }
                    }
                    builder.show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        voiceEnabled = uiPrefs.getBoolean(KEY_VOICE_ENABLED, false)
        bambuTagEnabled = uiPrefs.getBoolean(KEY_BAMBU_TAG_ENABLED, true)
        crealityEnabled = uiPrefs.getBoolean(KEY_CREALITY_ENABLED, false)
        snapmakerTagEnabled = uiPrefs.getBoolean(KEY_SNAPMAKER_TAG_ENABLED, false)
        inventoryEnabled = uiPrefs.getBoolean(KEY_INVENTORY_ENABLED, true)
        autoDetectBrand = uiPrefs.getBoolean(KEY_AUTO_DETECT_BRAND, false)
        autoShareTag = uiPrefs.getBoolean(KEY_AUTO_SHARE_TAG, true)
        hideCopiedTags = uiPrefs.getBoolean(KEY_HIDE_COPIED_TAGS, true)
        dualTagMode = uiPrefs.getBoolean(KEY_DUAL_TAG_MODE, false)
        tagViewMode = uiPrefs.getString(KEY_TAG_VIEW_MODE, "list") ?: "list"
        uiStyle = runCatching {
            AppUiStyle.valueOf(uiPrefs.getString(KEY_UI_STYLE, AppUiStyle.NEUMORPHIC.name).orEmpty())
        }.getOrDefault(AppUiStyle.NEUMORPHIC)
        themeMode = runCatching {
            ThemeMode.valueOf(uiPrefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name).orEmpty())
        }.getOrDefault(ThemeMode.SYSTEM)
        colorPalette = runCatching {
            ColorPalette.valueOf(uiPrefs.getString(KEY_COLOR_PALETTE, ColorPalette.OCEAN.name).orEmpty())
        }.getOrDefault(ColorPalette.OCEAN)
        var showUserAgreement by mutableStateOf(
            uiPrefs.getInt(KEY_USER_AGREEMENT_VERSION, 0) < CURRENT_USER_AGREEMENT_VERSION
        )
        val lastBoostRemind = uiPrefs.getLong(KEY_BOOST_REMIND_LAST_MS, 0L)
        var showBoostReminder by mutableStateOf(
            !showUserAgreement &&
            System.currentTimeMillis() - lastBoostRemind >= BOOST_REMIND_INTERVAL_MS
        )
        val noticeGuideShown = uiPrefs.getBoolean(KEY_NOTICE_GUIDE_SHOWN, false)
        var showNoticeGuide by mutableStateOf(false)
        LogCollector.init(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        filamentDbHelper = FilamentDbHelper(this)
        filamentDbHelper?.let {
            syncFilamentDatabase(this, it)
            syncCrealityMaterialDatabase(this, it)
            // 加载本地缓存的异常UID，然后后台同步最新列表
            anomalyUids = it.getAnomalyUids(it.readableDatabase)
        }
        syncAnomalyUidsAsync()
        ensureShareDirectory()
        refreshSelfTagCount()
        lifecycleScope.launch(Dispatchers.IO) {
            ensureBundledShareDataExtracted()
        }
        if (voiceEnabled) {
            initTts()
        }
        uiState = NfcUiState(status = initialStatus())
        logEvent("应用启动")
        logDeviceInfo()
        lifecycleScope.launch(Dispatchers.IO) {
            AnalyticsReporter.reportInstallAndLaunch(this@MainActivity)
        }
        
        // 检查并更新配置文件
        checkAndUpdateConfig()

        // 检查在线更新
        checkForUpdateAsync()

        // 注册下载完成广播（ContextCompat 兼容 API 28-）
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            updateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        
        setContent {
            BambuRfidReaderTheme(themeMode = themeMode, uiStyle = uiStyle, colorPalette = colorPalette) {
                AppNavigation(
                    state = uiState,
                    voiceEnabled = voiceEnabled,
                    uiStyle = uiStyle,
                    readAllSectors = readAllSectors,
                    saveKeysToFile = saveKeysToFile,
                    formatTagDebugEnabled = formatTagDebugEnabled,
                    forceOverwriteImport = forceOverwriteImport,
                    ttsReady = ttsReady,
                    ttsLanguageReady = ttsLanguageReady,
                    onVoiceEnabledChange = {
                        voiceEnabled = it
                        uiPrefs.edit().putBoolean(KEY_VOICE_ENABLED, it).apply()
                        if (!it) {
                            tts?.stop()
                        } else if (!ttsReady) {
                            initTts()
                        }
                    },
                    onUiStyleChange = {
                        uiStyle = it
                        uiPrefs.edit().putString(KEY_UI_STYLE, it.name).apply()
                    },
                    themeMode = themeMode,
                    onThemeModeChange = {
                        themeMode = it
                        uiPrefs.edit().putString(KEY_THEME_MODE, it.name).apply()
                    },
                    colorPalette = colorPalette,
                    onColorPaletteChange = {
                        colorPalette = it
                        uiPrefs.edit().putString(KEY_COLOR_PALETTE, it.name).apply()
                    },
                    onReadAllSectorsChange = {
                        readAllSectors = it
                    },
                    onSaveKeysToFileChange = {
                        saveKeysToFile = it
                    },
                    onFormatTagDebugEnabledChange = {
                        formatTagDebugEnabled = it
                        if (!it) {
                            try {
                                debugInfoDialog?.dismiss()
                            } catch (_: Exception) {
                            }
                            debugInfoDialog = null
                        }
                    },
                    onForceOverwriteImportChange = {
                        forceOverwriteImport = it
                    },
                    bambuTagEnabled = bambuTagEnabled,
                    onBambuTagEnabledChange = { enabled ->
                        bambuTagEnabled = enabled
                        uiPrefs.edit().putBoolean(KEY_BAMBU_TAG_ENABLED, enabled).apply()
                    },
                    crealityEnabled = crealityEnabled,
                    onCrealityEnabledChange = { enabled ->
                        crealityEnabled = enabled
                        uiPrefs.edit().putBoolean(KEY_CREALITY_ENABLED, enabled).apply()
                    },
                    snapmakerTagEnabled = snapmakerTagEnabled,
                    onSnapmakerTagEnabledChange = { enabled ->
                        snapmakerTagEnabled = enabled
                        uiPrefs.edit().putBoolean(KEY_SNAPMAKER_TAG_ENABLED, enabled).apply()
                    },
                    snapmakerShareTagItems = snapmakerShareTagItems,
                    snapmakerShareLoading = snapmakerShareLoading,
                    snapmakerWriteStatusMessage = snapmakerWriteStatusMessage,
                    snapmakerWriteInProgress = pendingSnapmakerWriteItem != null,
                    onSnapmakerTagScreenEnter = { refreshSnapmakerShareTagItemsAsync() },
                    onStartWriteSnapmakerTag = { item -> enqueueSnapmakerWriteTask(item) },
                    onDeleteSnapmakerTagItem = { item -> deleteSnapmakerShareTagItem(item) },
                    onCancelSnapmakerWrite = {
                        pendingSnapmakerWriteItem = null
                        snapmakerWriteStatusMessage = ""
                    },
                    onSelectImportSnapmakerTagPackage = {
                        openSnapmakerTagPackagePicker()
                        val msg = uiString(R.string.misc_select_tag_package_prompt)
                        miscStatusMessage = msg
                        msg
                    },
                    crealityTagData = crealityLastTagData,
                    crealityStatusMessage = crealityStatusMessage,
                    crealityWriteInProgress = pendingCrealityWrite != null,
                    onCrealityPrepareWrite = { materialId, colorHex, weight ->
                        pendingCrealityWrite = CrealityWritePending(materialId, colorHex, weight)
                        crealityStatusMessage = uiString(R.string.creality_write_ready)
                    },
                    onCrealityCancelWrite = {
                        pendingCrealityWrite = null
                        crealityStatusMessage = ""
                    },
                    onCrealityClearTagData = {
                        crealityLastTagData = null
                    },
                    onActiveRouteChange = { route -> currentActiveRoute = route },
                    readerBrand = readerBrand,
                    onReaderBrandChange = { brand ->
                        readerBrand = brand
                        readerBrandStatus = if (brand == ReaderBrand.BAMBU) "" else uiString(R.string.status_waiting_tag)
                        readerCrealityTagData = null
                        readerCrealityMaterial = null
                        readerSnapmakerTagData = null
                    },
                    readerCrealityTagData = readerCrealityTagData,
                    readerCrealityMaterial = readerCrealityMaterial,
                    readerSnapmakerTagData = readerSnapmakerTagData,
                    readerBrandStatus = readerBrandStatus,
                    inventoryEnabled = inventoryEnabled,
                    onInventoryEnabledChange = { enabled ->
                        inventoryEnabled = enabled
                        uiPrefs.edit().putBoolean(KEY_INVENTORY_ENABLED, enabled).apply()
                    },
                    autoDetectBrand = autoDetectBrand,
                    onAutoDetectBrandChange = { enabled ->
                        autoDetectBrand = enabled
                        uiPrefs.edit().putBoolean(KEY_AUTO_DETECT_BRAND, enabled).apply()
                    },
                    autoShareTag = autoShareTag,
                    onAutoShareTagChange = { enabled ->
                        autoShareTag = enabled
                        uiPrefs.edit().putBoolean(KEY_AUTO_SHARE_TAG, enabled).apply()
                    },
                    onCheckDownloadPermission = {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            checkTagDownloadPermission()
                        }
                    },
                    onDownloadTagPackage = { brand, onProgress, onImportStatus ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            downloadAndImportTagPackage(brand, onProgress, onImportStatus)
                        }
                    },
                    hideCopiedTags = hideCopiedTags,
                    onHideCopiedTagsChange = { enabled ->
                        hideCopiedTags = enabled
                        uiPrefs.edit().putBoolean(KEY_HIDE_COPIED_TAGS, enabled).apply()
                    },
                    dualTagMode = dualTagMode,
                    onDualTagModeChange = { enabled ->
                        dualTagMode = enabled
                        uiPrefs.edit().putBoolean(KEY_DUAL_TAG_MODE, enabled).apply()
                    },
                    tagViewMode = tagViewMode,
                    onTagViewModeChange = { mode ->
                        tagViewMode = mode
                        uiPrefs.edit().putString(KEY_TAG_VIEW_MODE, mode).apply()
                    },
                    onNotesChange = { trayUid, originalMaterial, notes ->
                        updateTrayNotes(trayUid, originalMaterial, notes)
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
                    onClearFuid = { enqueueClearFuidTask() },
                    onCancelClearFuid = {
                        pendingClearFuid = false
                        miscStatusMessage = uiString(R.string.misc_cancel_format_task)
                        miscStatusMessage
                    },
                    onClearSelfTags = { clearSelfTagFiles() },
                    onClearShareTags = { clearShareTagFiles() },
                    onEnqueueCuidTest = { enqueueCuidTestTask() },
                    onCancelCuidTest = {
                        pendingCuidTest = false
                        val msg = "已取消CUID检测"
                        miscStatusMessage = msg
                        msg
                    },
                    cuidTestInProgress = pendingCuidTest,
                    onResetDatabase = { resetDatabase() },
                    selfTagCount = selfTagCount,
                    miscStatusMessage = miscStatusMessage,
                    onExportTagPackage = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            miscStatusMessage = uiString(R.string.misc_exporting_tag_package)
                            lifecycleScope.launch(Dispatchers.IO) {
                                val result = exportSelfTagPackageToDownloads()
                                withContext(Dispatchers.Main) {
                                    miscStatusMessage = result
                                }
                            }
                            uiString(R.string.misc_exporting_tag_package)
                        } else {
                            val result = exportSelfTagPackageToDownloads()
                            miscStatusMessage = result
                            result
                        }
                    },
                    onSelectImportTagPackage = {
                        openTagPackagePicker()
                        val message = uiString(R.string.misc_select_tag_package_prompt)
                        miscStatusMessage = message
                        message
                    },
                    navigateToReader = shouldNavigateToReader,
                    navigateToTag = shouldNavigateToTag,
                    navigateToMisc = shouldNavigateToMisc,
                    scrollToNotice = shouldScrollToNotice,
                    onScrollToNoticeDone = { shouldScrollToNotice = false },
                    shareTagItems = shareTagItems,
                    tagPreselectedFileName = tagPreselectedFileName,
                    shareLoading = shareLoading,
                    writeStatusMessage = writeStatusMessage,
                    writeToolStatusMessage = writeToolStatusMessage,
                    writeInProgress = pendingWriteItem != null || pendingVerifyItem != null,
                    cModifyInProgress = pendingCModifyItem != null,
                    formatInProgress = pendingClearFuid,
                    anomalyUids = anomalyUids,
                    onReportAnomaly = { cardUid ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            com.m0h31h31.bamburfidreader.utils.AnalyticsReporter.reportAnomaly(applicationContext, cardUid)
                        }
                    },
                    onTagScreenEnter = {
                        refreshShareTagItemsAsync()
                        syncAnomalyUidsAsync()
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
                        pendingCModifyItem = null
                        writeStatusMessage = uiString(R.string.copy_write_stopped_leave_page)
                    },
                    onStartCModifyTag = { item ->
                        enqueueCModifyTask(item)
                    },
                    cModifyRecoveryInfo = cModifyRecoveryInfo,
                    onDismissCModifyRecovery = { cModifyRecoveryInfo = null },
                    onStartNdefWrite = { request ->
                        enqueueNdefWriteTask(request)
                    },
                    pendingUpdateInfo = pendingUpdateInfo,
                    isDownloadingUpdate = isDownloadingUpdate,
                    onStartUpdate = { info -> startUpdate(info) },
                    onDismissUpdate = { pendingUpdateInfo = null }
                )
                // 重置导航标志
                if (shouldNavigateToReader) {
                    shouldNavigateToReader = false
                }
                if (shouldNavigateToTag) {
                    shouldNavigateToTag = false
                }
                if (shouldNavigateToMisc) {
                    shouldNavigateToMisc = false
                }
                if (showUserAgreement) {
                    UserAgreementDialog(
                        onDecline = {
                            finishAffinity()
                        },
                        onAccept = {
                            uiPrefs.edit()
                                .putInt(KEY_USER_AGREEMENT_VERSION, CURRENT_USER_AGREEMENT_VERSION)
                                .apply()
                            showUserAgreement = false
                            if (System.currentTimeMillis() - lastBoostRemind < BOOST_REMIND_INTERVAL_MS
                                && !noticeGuideShown) {
                                showNoticeGuide = true
                            }
                        }
                    )
                }
                if (showBoostReminder) {
                    BoostReminderDialog(
                        onDismiss = {
                            uiPrefs.edit()
                                .putLong(KEY_BOOST_REMIND_LAST_MS, System.currentTimeMillis())
                                .apply()
                            showBoostReminder = false
                            if (!noticeGuideShown) showNoticeGuide = true
                        },
                        onBoost = {
                            uiPrefs.edit()
                                .putLong(KEY_BOOST_REMIND_LAST_MS, System.currentTimeMillis())
                                .apply()
                            showBoostReminder = false
                            if (!noticeGuideShown) showNoticeGuide = true
                            runCatching {
                                startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(BOOST_DESIGN_URI)
                                    )
                                )
                            }
                        }
                    )
                }
                if (showNoticeGuide) {
                    NoticeGuideDialog(
                        onDismiss = {
                            uiPrefs.edit().putBoolean(KEY_NOTICE_GUIDE_SHOWN, true).apply()
                            showNoticeGuide = false
                        },
                        onGoToNotice = {
                            uiPrefs.edit().putBoolean(KEY_NOTICE_GUIDE_SHOWN, true).apply()
                            showNoticeGuide = false
                            shouldNavigateToMisc = true
                            shouldScrollToNotice = true
                        }
                    )
                }
            }
        }
    }

    private fun enqueueWriteTask(item: ShareTagItem) {
        if (pendingClearFuid || pendingCuidTest || pendingNdefWriteRequest != null) {
            writeStatusMessage = uiString(R.string.copy_finish_current_task_first)
            return
        }
        val trayUid = item.trayUid.trim()
        if (trayUid.isNotBlank() && isTrayUidExists(trayUid)) {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(uiString(R.string.copy_duplicate_tray_title))
                .setMessage(uiString(R.string.copy_duplicate_tray_message, trayUid))
                .setPositiveButton(uiString(R.string.copy_continue)) { _, _ ->
                    pendingWriteItem = item
                    pendingVerifyItem = null
                    writeStatusMessage = uiString(R.string.copy_write_ready)
                }
                .setNegativeButton(uiString(R.string.action_cancel)) { _, _ ->
                    writeStatusMessage = uiString(R.string.copy_duplicate_canceled)
                }
                .show()
        } else {
            pendingWriteItem = item
            pendingVerifyItem = null
            writeStatusMessage = uiString(R.string.copy_write_ready)
        }
    }

    private fun enqueueCModifyTask(item: ShareTagItem) {
        if (pendingWriteItem != null || pendingVerifyItem != null || pendingClearFuid || pendingCuidTest || pendingNdefWriteRequest != null) {
            writeStatusMessage = uiString(R.string.copy_finish_current_task_first)
            return
        }
        pendingCModifyItem = item
        writeStatusMessage = uiString(R.string.tag_c_modify_ready)
    }

    private fun attemptRecoveryFromPartialRead() {
        val uid = uiState.uidHex.trim().uppercase(Locale.US)
        if (uid.isBlank()) {
            writeStatusMessage = uiString(R.string.copy_recovery_uid_missing)
            return
        }
        writeStatusMessage = uiString(R.string.copy_recovery_searching)
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = loadShareTagItems()
            val matched = loaded.firstOrNull { it.sourceUid.uppercase(Locale.US) == uid }
            withContext(Dispatchers.Main) {
                shareTagItems = loaded
                shouldNavigateToTag = true
                if (matched != null) {
                    tagPreselectedFileName = matched.fileName
                    enqueueWriteTask(matched)
                    writeStatusMessage = uiString(
                        R.string.copy_recovery_found,
                        matched.fileName.removeSuffix(".txt")
                    )
                } else {
                    tagPreselectedFileName = null
                    writeStatusMessage = uiString(R.string.copy_recovery_not_found, uid)
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
        try { unregisterReceiver(updateDownloadReceiver) } catch (_: Exception) {}
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

    private fun updateTrayNotes(trayUidHex: String, originalMaterial: String, notes: String) {
        if (trayUidHex.isBlank()) return
        val dbHelper = filamentDbHelper
        val db = dbHelper?.writableDatabase
        if (db != null) {
            dbHelper.upsertTrayNotes(db, trayUidHex, originalMaterial, notes)
        }
        if (uiState.trayUidHex == trayUidHex) {
            uiState = uiState.copy(originalMaterial = originalMaterial, notes = notes)
        }
    }

    private fun removeTrayFromInventory(trayUidHex: String) {
        if (trayUidHex.isBlank()) {
            uiState = uiState.copy(status = uiString(R.string.inventory_outbound_failed_uid_missing))
            return
        }
        val db = filamentDbHelper?.writableDatabase
        if (db == null) {
            uiState = uiState.copy(status = uiString(R.string.inventory_outbound_failed_db_unavailable))
            return
        }
        try {
            filamentDbHelper?.deleteTrayInventory(db, trayUidHex)
            uiState = NfcUiState(
                status = uiString(R.string.inventory_outbound_success)
            )
            logDebug("出库成功: $trayUidHex")
            LogCollector.append(this, "I", "出库成功: $trayUidHex")
        } catch (e: Exception) {
            uiState = uiState.copy(
                status = uiString(R.string.inventory_outbound_failed_detail, e.message.orEmpty())
            )
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
        val (export, errorMessage) = prepareSelfTagPackageExport()
        if (export == null) return errorMessage ?: "无法准备标签包导出"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, export.zipName)
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
                writeSelfTagPackageToUri(uri, export) ?: return "打开下载文件失败"
            } else {
                exportTagPackageLauncher.launch(export.zipName)
                return "请选择保存位置"
            }
            "标签数据打包成功: Download/${export.zipName}"
        } catch (e: Exception) {
            logDebug("标签数据打包失败: ${e.message}")
            "标签数据打包失败: ${e.message.orEmpty()}"
        }
    }

    private fun exportSelfTagPackageToUri(uri: Uri): String {
        val (export, errorMessage) = prepareSelfTagPackageExport()
        if (export == null) return errorMessage ?: "无法准备标签包导出"
        return try {
            writeSelfTagPackageToUri(uri, export) ?: return "打开导出文件失败"
            "标签数据打包成功: ${export.zipName}"
        } catch (e: Exception) {
            logDebug("标签数据打包失败: ${e.message}")
            "标签数据打包失败: ${e.message.orEmpty()}"
        }
    }

    private fun prepareSelfTagPackageExport(): Pair<SelfTagPackageExport?, String?> {
        val externalDir = getExternalFilesDir(null)
            ?: return null to "无法访问应用存储目录"
        val sourceDir = File(externalDir, "rfid_files/self_${getDeviceIdSuffix()}")
        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            return null to "未找到标签数据目录: ${sourceDir.name}"
        }
        val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            return null to "标签数据目录为空，无法打包"
        }
        val zipName =
            "tag_package_${sourceDir.name}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
        return SelfTagPackageExport(
            sourceDir = sourceDir,
            files = files,
            zipName = zipName
        ) to null
    }

    private fun writeSelfTagPackageToUri(uri: Uri, export: SelfTagPackageExport): Uri? {
        val resolver = contentResolver
        resolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                export.files.forEach { file ->
                    val relative = file.relativeTo(export.sourceDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("${export.sourceDir.name}/$relative"))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: return null
        return uri
    }

    /** 计算标签包 ZIP 密码（与服务器算法一致：SHA-256(install_id:TAG_PACKAGE_KEY)[:16]）*/
    private fun computeTagZipPassword(): String {
        val installId = com.m0h31h31.bamburfidreader.utils.AnalyticsReporter.getInstallId(this)
        val raw = "$installId:${BuildConfig.TAG_PACKAGE_KEY}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 将 URI 内容复制到临时文件，用 zip4j 打开（自动处理加密/非加密），
     * 返回 (entryName, bytes) 列表。处理完成后删除临时文件。
     */
    private fun extractZipEntries(uri: Uri): List<Pair<String, ByteArray>> {
        val tmp = File(cacheDir, "import_${System.currentTimeMillis()}.zip")
        try {
            contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { inp.copyTo(it) }
            }
            val zipFile = Zip4jFile(tmp)
            if (zipFile.isEncrypted) {
                zipFile.setPassword(computeTagZipPassword().toCharArray())
            }
            val entries = mutableListOf<Pair<String, ByteArray>>()
            for (header in zipFile.fileHeaders) {
                if (!header.isDirectory) {
                    val bytes = zipFile.getInputStream(header).use { it.readBytes() }
                    entries.add(Pair(header.fileName, bytes))
                }
            }
            return entries
        } catch (e: Zip4jException) {
            logDebug("ZIP 解压失败（密码错误或格式不支持）: ${e.message}")
            return emptyList()
        } finally {
            tmp.delete()
        }
    }

    private fun importTagPackageFromZipUri(uri: Uri): String {
        val dbHelper = filamentDbHelper ?: return "数据库不可用"
        val db = dbHelper.writableDatabase
        return try {
            var extractedCount = 0
            var skippedCount = 0
            var invalidCount = 0
            var overwrittenCount = 0

            // 从 DB 加载已有的 file_uid 和 tray_uid，用于去重（纯 DB 操作）
            val existingRows = dbHelper.getAllShareTagRows(db)
            val existingFileUidSet = existingRows.map { it.fileUid.uppercase(Locale.US) }.toMutableSet()
            val existingTrayUidSet = existingRows
                .mapNotNull { it.trayUid?.uppercase(Locale.US)?.ifBlank { null } }
                .toMutableSet()

            val zipEntries = extractZipEntries(uri)
            if (zipEntries.isEmpty() && !(cacheDir.listFiles()?.any { it.name.startsWith("import_") } ?: false)) {
                return "读取标签包失败（文件损坏或密码错误）"
            }
            for ((entryName, bytes) in zipEntries) {
                if (!entryName.lowercase(Locale.US).endsWith(".txt")) continue
                val incomingUid = File(entryName).nameWithoutExtension.uppercase(Locale.US)
                val alreadyExists = incomingUid.isNotBlank() && existingFileUidSet.contains(incomingUid)

                if (alreadyExists && !forceOverwriteImport) { skippedCount++; continue }

                val content = String(bytes, Charsets.UTF_8)
                val rawBlocks = parseHexTagFileStrict(content)
                if (rawBlocks == null) { invalidCount++; continue }
                if (!isValidBambuTag(rawBlocks)) { invalidCount++; continue }

                val preview = NfcTagProcessor.parseForPreview(rawBlocks, filamentDbHelper) { }
                val trayUid = preview.trayUidHex.trim()
                if (trayUid.isNotBlank() && existingTrayUidSet.contains(trayUid.uppercase())) {
                    skippedCount++; continue
                }

                if (alreadyExists && forceOverwriteImport && incomingUid.isNotBlank()) {
                    dbHelper.deleteShareTagByFileUid(db, incomingUid)
                }

                val normalized = content.trim().lines()
                    .map { it.trim() }.filter { it.isNotBlank() }.take(64).joinToString("\n")
                val productionDate = extractProductionDate(rawBlocks)
                dbHelper.insertShareTag(
                    db,
                    fileUid = incomingUid,
                    trayUid = trayUid.ifBlank { null },
                    materialType = preview.displayData.type.ifBlank { null },
                    colorUid = preview.displayData.colorCode.ifBlank { null },
                    colorName = preview.displayData.colorName.ifBlank { null },
                    colorType = preview.displayData.colorType.ifBlank { null },
                    colorValues = preview.displayData.colorValues.joinToString(",").ifBlank { null },
                    rawData = normalized,
                    productionDate = productionDate
                )
                extractedCount++
                if (alreadyExists && forceOverwriteImport) overwrittenCount++
                if (incomingUid.isNotBlank()) existingFileUidSet.add(incomingUid)
                if (trayUid.isNotBlank()) existingTrayUidSet.add(trayUid.uppercase())
            }

            when {
                extractedCount == 0 && skippedCount == 0 && invalidCount == 0 ->
                    "导入完成，但压缩包内未发现 txt 标签数据"
                extractedCount == 0 ->
                    "导入完成：格式无效 $invalidCount 个，重复跳过 $skippedCount 个"
                forceOverwriteImport ->
                    "标签包导入完成: 导入 $extractedCount 个（覆盖 $overwrittenCount 个），格式无效 $invalidCount 个，跳过重复 $skippedCount 个"
                else ->
                    "标签包导入完成: 导入 $extractedCount 个，格式无效 $invalidCount 个，跳过重复 $skippedCount 个"
            }
        } catch (e: Exception) {
            logDebug("导入标签包失败: ${e.message}")
            "导入标签包失败: ${e.message.orEmpty()}"
        }
    }
    
    // ── 在线下载共享标签包 ──────────────────────────────────────────────────────

    // ── 在线下载权限检查 ──────────────────────────────────────────────────────

    /** 检查当前设备是否有资格下载标签包。IO 线程调用。返回 null 表示允许，非 null 为拒绝原因。 */
    private fun checkTagDownloadPermission(): String? {
        val installId = com.m0h31h31.bamburfidreader.utils.AnalyticsReporter.getInstallId(this)
        val endpoint = com.m0h31h31.bamburfidreader.utils.ConfigManager.getTagCanDownloadEndpoint(this)
        return try {
            val url = java.net.URL("$endpoint?install_id=${java.net.URLEncoder.encode(installId, "UTF-8")}")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            val body = if (code in 200..299)
                conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            else
                conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
            conn.disconnect()
            val json = org.json.JSONObject(body)
            if (json.optBoolean("allowed", false)) null
            else json.optString("reason", getString(R.string.download_tag_package_failed))
        } catch (e: Exception) {
            logDebug("checkTagDownloadPermission error: ${e.message}")
            getString(R.string.download_tag_package_failed)
        }
    }

    // ── 在线下载并导入标签包 ──────────────────────────────────────────────────

    /**
     * 下载指定品牌的标签包并导入（suspend）。
     * onProgress(0..100) 在 IO 线程回调，调用方用 withContext(Main) 更新 UI。
     */
    private suspend fun downloadAndImportTagPackage(
        brand: String,
        onProgress: (Int) -> Unit,
        onImportStatus: (String) -> Unit
    ): String {
        val installId = com.m0h31h31.bamburfidreader.utils.AnalyticsReporter.getInstallId(this)
        val endpoint = com.m0h31h31.bamburfidreader.utils.ConfigManager.getTagDownloadEndpoint(this)
        val payload = org.json.JSONObject().apply {
            put("install_id", installId)
            put("brand", brand)
        }
        val tmp = java.io.File(cacheDir, "dl_${brand}_${System.currentTimeMillis()}.zip")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        return try {
            val result = com.m0h31h31.bamburfidreader.utils.NetworkUtils.postJsonDownloadToFile(
                endpoint, payload, tmp, onProgress = onProgress
            ) ?: return getString(R.string.download_tag_package_failed)

            val (code, errBody) = result
            if (code !in 200..299) {
                val detail = try {
                    org.json.JSONObject(errBody!!.toString(Charsets.UTF_8)).optString("detail", "")
                } catch (_: Exception) { "" }
                return if (detail.isNotBlank()) detail
                       else getString(R.string.download_tag_package_failed)
            }
            importTagPackageFromZipFile(tmp, snapmaker = brand == "snapmaker") { cur, total ->
                mainHandler.post { onImportStatus("正在导入 ($cur/$total)…") }
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * 直接从 zip 文件导入标签包（不经过 ContentResolver/FileProvider），
     * 复用现有解析逻辑，适用于下载后的临时文件。
     */
    private fun importTagPackageFromZipFile(
        zipFile: java.io.File,
        snapmaker: Boolean,
        onProgress: ((cur: Int, total: Int) -> Unit)? = null
    ): String {
        val dbHelper = filamentDbHelper ?: return "数据库不可用"
        val db = dbHelper.writableDatabase
        return try {
            // 用 zip4j 直接打开文件（与 extractZipEntries 相同逻辑，跳过 URI 拷贝）
            val zip4j = net.lingala.zip4j.ZipFile(zipFile)
            if (zip4j.isEncrypted) {
                zip4j.setPassword(computeTagZipPassword().toCharArray())
            }
            val entries = mutableListOf<Pair<String, ByteArray>>()
            for (header in zip4j.fileHeaders) {
                if (!header.isDirectory) {
                    val bytes = zip4j.getInputStream(header).use { it.readBytes() }
                    entries.add(Pair(header.fileName, bytes))
                }
            }
            if (snapmaker) processSnapmakerZipEntries(entries, db, dbHelper, onProgress)
            else processBambuZipEntries(entries, db, dbHelper, onProgress)
        } catch (e: Exception) {
            logDebug("importTagPackageFromZipFile error: ${e.message}")
            "导入失败: ${e.message.orEmpty()}"
        }
    }

    // ── 快造标签包导入 ──────────────────────────────────────────────────────────

    private fun openSnapmakerTagPackagePicker() {
        importSnapmakerTagPackageLauncher.launch(
            arrayOf(
                SHARE_IMPORT_ZIP_MIME,
                "application/x-zip-compressed",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    private fun importSnapmakerTagPackageFromZipUri(uri: Uri): String {
        val dbHelper = filamentDbHelper ?: return "数据库不可用"
        val db = dbHelper.writableDatabase
        return try {
            var extractedCount = 0
            var skippedCount = 0
            var invalidCount = 0

            val existingUids = dbHelper.getAllSnapmakerShareTagUids(db)
            val existingUidSet = existingUids.map { it.uppercase(Locale.US) }.toMutableSet()

            val zipEntries = extractZipEntries(uri)
            for ((entryName, bytes) in zipEntries) {
                if (!entryName.lowercase(Locale.US).endsWith(".txt")) continue
                val incomingUid = File(entryName).nameWithoutExtension.uppercase(Locale.US)
                val alreadyExists = incomingUid.isNotBlank() && existingUidSet.contains(incomingUid)
                if (alreadyExists) { skippedCount++; continue }

                val content = String(bytes, Charsets.UTF_8)
                val rawBlocks = parseHexTagFileStrict(content)
                if (rawBlocks == null) { invalidCount++; continue }
                if (!isValidSnapmakerTag(rawBlocks)) { invalidCount++; continue }

                val fields = parseSnapmakerShareFields(rawBlocks)
                val normalized = content.trim().lines()
                    .map { it.trim() }.filter { it.isNotBlank() }.take(64).joinToString("\n")

                dbHelper.insertSnapmakerShareTag(
                    db,
                    uid = incomingUid,
                    vendor = fields.vendor,
                    manufacturer = fields.manufacturer,
                    mainType = fields.mainType,
                    diameter = fields.diameter,
                    weight = fields.weight,
                    rgb1 = fields.rgb1,
                    mfDate = fields.mfDate,
                    rawData = normalized
                )
                extractedCount++
                existingUidSet.add(incomingUid)
            }

            when {
                extractedCount == 0 && skippedCount == 0 && invalidCount == 0 ->
                    "导入完成，但压缩包内未发现 txt 标签数据"
                extractedCount == 0 ->
                    "导入完成：格式无效 $invalidCount 个，重复跳过 $skippedCount 个"
                else ->
                    "快造标签包导入完成: 导入 $extractedCount 个，格式无效 $invalidCount 个，跳过重复 $skippedCount 个"
            }
        } catch (e: Exception) {
            logDebug("导入快造标签包失败: ${e.message}")
            "导入快造标签包失败: ${e.message.orEmpty()}"
        }
    }

    // ── zip entries 通用处理（供 URI 导入和文件直接导入共用）───────────────────

    private fun processBambuZipEntries(
        entries: List<Pair<String, ByteArray>>,
        db: android.database.sqlite.SQLiteDatabase,
        dbHelper: FilamentDbHelper,
        onProgress: ((cur: Int, total: Int) -> Unit)? = null
    ): String {
        val helper = dbHelper
        var extractedCount = 0; var skippedCount = 0
        var invalidCount = 0;   var overwrittenCount = 0
        val txtEntries = entries.filter { it.first.lowercase(Locale.US).endsWith(".txt") }
        val total = txtEntries.size
        var processed = 0
        val existingRows = helper.getAllShareTagRows(db)
        val existingFileUidSet = existingRows.map { it.fileUid.uppercase(Locale.US) }.toMutableSet()
        val existingTrayUidSet = existingRows
            .mapNotNull { it.trayUid?.uppercase(Locale.US)?.ifBlank { null } }.toMutableSet()
        for ((entryName, bytes) in txtEntries) {
            val incomingUid = File(entryName).nameWithoutExtension.uppercase(Locale.US)
            val alreadyExists = incomingUid.isNotBlank() && existingFileUidSet.contains(incomingUid)
            if (alreadyExists && !forceOverwriteImport) { skippedCount++; processed++; onProgress?.invoke(processed, total); continue }
            val content = String(bytes, Charsets.UTF_8)
            val rawBlocks = parseHexTagFileStrict(content)
            if (rawBlocks == null) { invalidCount++; processed++; onProgress?.invoke(processed, total); continue }
            if (!isValidBambuTag(rawBlocks)) { invalidCount++; processed++; onProgress?.invoke(processed, total); continue }
            val preview = NfcTagProcessor.parseForPreview(rawBlocks, filamentDbHelper) { }
            val trayUid = preview.trayUidHex.trim()
            if (trayUid.isNotBlank() && existingTrayUidSet.contains(trayUid.uppercase())) {
                skippedCount++; processed++; onProgress?.invoke(processed, total); continue
            }
            if (alreadyExists && forceOverwriteImport && incomingUid.isNotBlank()) {
                helper.deleteShareTagByFileUid(db, incomingUid)
            }
            val normalized = content.trim().lines()
                .map { it.trim() }.filter { it.isNotBlank() }.take(64).joinToString("\n")
            val productionDate = extractProductionDate(rawBlocks)
            helper.insertShareTag(
                db, fileUid = incomingUid, trayUid = trayUid.ifBlank { null },
                materialType = preview.displayData.type.ifBlank { null },
                colorUid = preview.displayData.colorCode.ifBlank { null },
                colorName = preview.displayData.colorName.ifBlank { null },
                colorType = preview.displayData.colorType.ifBlank { null },
                colorValues = preview.displayData.colorValues.joinToString(",").ifBlank { null },
                rawData = normalized, productionDate = productionDate
            )
            extractedCount++
            if (alreadyExists && forceOverwriteImport) overwrittenCount++
            if (incomingUid.isNotBlank()) existingFileUidSet.add(incomingUid)
            if (trayUid.isNotBlank()) existingTrayUidSet.add(trayUid.uppercase())
            processed++
            onProgress?.invoke(processed, total)
        }
        return when {
            extractedCount == 0 && skippedCount == 0 && invalidCount == 0 ->
                "导入完成，但压缩包内未发现标签数据"
            extractedCount == 0 ->
                "导入完成：格式无效 $invalidCount 个，重复跳过 $skippedCount 个"
            forceOverwriteImport ->
                "Bambu 标签包导入完成：新增 $extractedCount 个（覆盖 $overwrittenCount 个），无效 $invalidCount 个，跳过 $skippedCount 个"
            else ->
                "Bambu 标签包导入完成：新增 $extractedCount 个，无效 $invalidCount 个，跳过 $skippedCount 个"
        }
    }

    private fun processSnapmakerZipEntries(
        entries: List<Pair<String, ByteArray>>,
        db: android.database.sqlite.SQLiteDatabase,
        dbHelper: FilamentDbHelper,
        onProgress: ((cur: Int, total: Int) -> Unit)? = null
    ): String {
        val helper = dbHelper
        var extractedCount = 0; var skippedCount = 0; var invalidCount = 0
        val txtEntries = entries.filter { it.first.lowercase(Locale.US).endsWith(".txt") }
        val total = txtEntries.size
        var processed = 0
        val existingUidSet = helper.getAllSnapmakerShareTagUids(db)
            .map { it.uppercase(Locale.US) }.toMutableSet()
        for ((entryName, bytes) in txtEntries) {
            val incomingUid = File(entryName).nameWithoutExtension.uppercase(Locale.US)
            if (incomingUid.isNotBlank() && existingUidSet.contains(incomingUid)) {
                skippedCount++; processed++; onProgress?.invoke(processed, total); continue
            }
            val content = String(bytes, Charsets.UTF_8)
            val rawBlocks = parseHexTagFileStrict(content)
            if (rawBlocks == null) { invalidCount++; processed++; onProgress?.invoke(processed, total); continue }
            if (!isValidSnapmakerTag(rawBlocks)) { invalidCount++; processed++; onProgress?.invoke(processed, total); continue }
            val fields = parseSnapmakerShareFields(rawBlocks)
            val normalized = content.trim().lines()
                .map { it.trim() }.filter { it.isNotBlank() }.take(64).joinToString("\n")
            helper.insertSnapmakerShareTag(
                db, uid = incomingUid, vendor = fields.vendor,
                manufacturer = fields.manufacturer, mainType = fields.mainType,
                diameter = fields.diameter, weight = fields.weight,
                rgb1 = fields.rgb1, mfDate = fields.mfDate, rawData = normalized
            )
            extractedCount++
            existingUidSet.add(incomingUid)
            processed++
            onProgress?.invoke(processed, total)
        }
        return when {
            extractedCount == 0 && skippedCount == 0 && invalidCount == 0 ->
                "导入完成，但压缩包内未发现标签数据"
            extractedCount == 0 ->
                "导入完成：格式无效 $invalidCount 个，重复跳过 $skippedCount 个"
            else ->
                "Snapmaker 标签包导入完成：新增 $extractedCount 个，无效 $invalidCount 个，跳过 $skippedCount 个"
        }
    }

    private data class SnapmakerShareFields(
        val vendor: String,
        val manufacturer: String,
        val mainType: Int,
        val subType: Int,
        val diameter: Int,
        val weight: Int,
        val rgb1: Int,
        val mfDate: String
    )

    private fun parseSnapmakerShareFields(rawBlocks: List<ByteArray?>): SnapmakerShareFields {
        fun le16(block: ByteArray?, offset: Int): Int {
            if (block == null || offset + 1 >= block.size) return 0
            return ((block[offset + 1].toInt() and 0xFF) shl 8) or (block[offset].toInt() and 0xFF)
        }
        fun readRgb(block: ByteArray?, offset: Int): Int {
            if (block == null || offset + 2 >= block.size) return 0
            return ((block[offset].toInt() and 0xFF) shl 16) or
                   ((block[offset + 1].toInt() and 0xFF) shl 8) or
                   (block[offset + 2].toInt() and 0xFF)
        }

        val block1 = rawBlocks.getOrNull(1)  // sector0 block1: vendor
        val block2 = rawBlocks.getOrNull(2)  // sector0 block2: manufacturer
        val block4 = rawBlocks.getOrNull(4)  // sector1 block0: mainType/subType/colors
        val block5 = rawBlocks.getOrNull(5)  // sector1 block1: rgb values
        val block8 = rawBlocks.getOrNull(8)  // sector2 block0: diameter/weight
        val block10 = rawBlocks.getOrNull(10) // sector2 block2: mfDate

        val vendor = block1?.let { String(it, 0, minOf(16, it.size), Charsets.US_ASCII).trimEnd('\u0000') }.orEmpty()
        val manufacturer = block2?.let { String(it, 0, minOf(16, it.size), Charsets.US_ASCII).trimEnd('\u0000') }.orEmpty()
        val mainType = le16(block4, 2)
        val subType = le16(block4, 4)
        val diameter = le16(block8, 0)
        val weight = le16(block8, 2)
        val rgb1 = readRgb(block5, 0)
        val mfDate = block10?.let { String(it, 0, minOf(8, it.size), Charsets.US_ASCII).trimEnd('\u0000') }.orEmpty()

        return SnapmakerShareFields(vendor, manufacturer, mainType, subType, diameter, weight, rgb1, mfDate)
    }

    private fun isValidSnapmakerTag(rawBlocks: List<ByteArray?>): Boolean {
        // 校验 1：所有 16 个扇区的 trailer 权限位必须为 87 87 87 69
        for (sector in 0 until 16) {
            val trailer = rawBlocks.getOrNull(sector * 4 + 3) ?: return false
            if (trailer.size < 16) return false
            if (trailer[6] != 0x87.toByte() ||
                trailer[7] != 0x87.toByte() ||
                trailer[8] != 0x87.toByte() ||
                trailer[9] != 0x69.toByte()
            ) return false
        }

        // 校验 2：使用 UID 派生快造密钥，逐扇区比对 KeyA 和 KeyB
        val block0 = rawBlocks.getOrNull(0) ?: return false
        if (block0.size < 4) return false
        val uid = block0.copyOfRange(0, 4)
        val (expectedKeysA, expectedKeysB) = try {
            deriveSnapmakerKeys(uid)
        } catch (_: Exception) {
            return false
        }
        for (sector in 0 until 16) {
            val trailer = rawBlocks[sector * 4 + 3]!!
            val actualKeyA = trailer.copyOfRange(0, 6)
            val actualKeyB = trailer.copyOfRange(10, 16)
            if (!actualKeyA.contentEquals(expectedKeysA.getOrNull(sector) ?: return false)) return false
            if (!actualKeyB.contentEquals(expectedKeysB.getOrNull(sector) ?: return false)) return false
        }
        return true
    }

    private fun refreshSnapmakerShareTagItemsAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dbHelper = filamentDbHelper ?: return@launch
            withContext(Dispatchers.Main) { snapmakerShareLoading = true }
            val rows = dbHelper.getAllSnapmakerShareTagRows(dbHelper.readableDatabase)
            val items = rows.mapNotNull { row ->
                val rawData = row.rawData ?: return@mapNotNull null
                val rawBlocks = parseHexTagFileStrict(rawData) ?: return@mapNotNull null
                val block4 = rawBlocks.getOrNull(4)
                val subType = if (block4 != null && block4.size >= 6)
                    ((block4[5].toInt() and 0xFF) shl 8) or (block4[4].toInt() and 0xFF)
                else 0
                SnapmakerShareTagItem(
                    uid = row.uid,
                    vendor = row.vendor.orEmpty(),
                    manufacturer = row.manufacturer.orEmpty(),
                    mainType = row.mainType,
                    subType = subType,
                    diameter = row.diameter,
                    weight = row.weight,
                    rgb1 = row.rgb1,
                    mfDate = row.mfDate.orEmpty(),
                    rawBlocks = rawBlocks,
                    dbId = row.id,
                    copyCount = row.copyCount
                )
            }
            withContext(Dispatchers.Main) {
                snapmakerShareTagItems = items
                snapmakerShareLoading = false
            }
        }
    }

    private fun deleteSnapmakerShareTagItem(item: SnapmakerShareTagItem): String {
        val dbHelper = filamentDbHelper ?: return "数据库不可用"
        dbHelper.deleteSnapmakerShareTagByUid(dbHelper.writableDatabase, item.uid)
        snapmakerShareTagItems = snapmakerShareTagItems.filter { it.uid != item.uid }
        return "已删除"
    }

    private fun enqueueSnapmakerWriteTask(item: SnapmakerShareTagItem) {
        pendingSnapmakerWriteItem = item
        snapmakerWriteStatusMessage = "请将空白卡贴近手机..."
    }

    private fun writeSnapmakerTagFromDump(
        tag: Tag,
        item: SnapmakerShareTagItem,
        onStatusUpdate: ((String) -> Unit)? = null
    ): String {
        val mifare = MifareClassic.get(tag) ?: return "写入失败：标签不支持 MIFARE Classic"
        val sourceBlocks = item.rawBlocks
        if (sourceBlocks.isEmpty()) return "写入失败：源数据为空"

        val uid = tag.id ?: return "写入失败：无法读取卡 UID"
        val ffKey = ByteArray(6) { 0xFF.toByte() }
        val targetSectorCount = minOf(16, mifare.sectorCount)
        val fullFfTrailer = ByteArray(16).apply {
            for (i in 0..5) this[i] = 0xFF.toByte()
            this[6] = 0xFF.toByte(); this[7] = 0x07.toByte()
            this[8] = 0x80.toByte(); this[9] = 0x69.toByte()
            for (i in 10..15) this[i] = 0xFF.toByte()
        }

        return try {
            mifare.connect()
            Thread.sleep(700)

            // Phase 0: 检测卡片状态——先尝试 FF 秘钥，再尝试快造派生秘钥
            val (snapKeysA, snapKeysB) = deriveSnapmakerKeys(uid)
            val sector0FfAuth = authenticateSectorWithRetry(mifare, 0, listOf(ffKey), listOf(ffKey))

            if (!sector0FfAuth) {
                // 非空白卡，检查是否为已写入的快造卡
                reconnectMifareClassic(mifare)
                val sector0DerivedAuth = authenticateSectorWithRetry(
                    mifare, 0, listOf(snapKeysA[0]), listOf(snapKeysB[0])
                )
                if (!sector0DerivedAuth) {
                    return "写入失败：标签认证失败（非空白卡且无法通过快造派生秘钥认证）"
                }

                // Phase 1: 将所有扇区从派生秘钥重置为全 FF
                onStatusUpdate?.invoke("检测到已写入的快造标签，正在重置...")
                for (sector in 0 until targetSectorCount) {
                    onStatusUpdate?.invoke("正在重置扇区 ${sector + 1}/$targetSectorCount...")
                    val trailerBlock = mifare.sectorToBlock(sector) + 3
                    val curKeyA = snapKeysA[sector]
                    val curKeyB = snapKeysB[sector]

                    // 步骤1：仅用派生 KeyB 认证，将权限位改为 FF078069（保留派生秘钥）
                    reconnectMifareClassic(mifare)
                    if (!authenticateSectorWithRetry(mifare, sector, emptyList(), listOf(curKeyB))) {
                        return "写入失败：重置扇区 $sector 派生 KeyB 认证失败"
                    }
                    val step1Trailer = ByteArray(16).apply {
                        System.arraycopy(curKeyA, 0, this, 0, 6)
                        this[6] = 0xFF.toByte(); this[7] = 0x07.toByte()
                        this[8] = 0x80.toByte(); this[9] = 0x69.toByte()
                        System.arraycopy(curKeyB, 0, this, 10, 6)
                    }
                    if (!writeBlockWithRetry(mifare, trailerBlock, step1Trailer)) {
                        return "写入失败：重置扇区 $sector 修改权限位失败"
                    }
                    Thread.sleep(15)

                    // 步骤2：权限位已是 FF078069，将 KeyA/B 改为 FF
                    if (!authenticateSectorWithRetry(mifare, sector, listOf(curKeyA, ffKey), listOf(curKeyB, ffKey))) {
                        return "写入失败：重置扇区 $sector 步骤2认证失败"
                    }
                    if (!writeBlockWithRetry(mifare, trailerBlock, fullFfTrailer)) {
                        return "写入失败：重置扇区 $sector 重置秘钥为 FF 失败"
                    }
                    Thread.sleep(15)

                    // 验证 FF 秘钥可用
                    if (!authenticateSectorWithRetry(mifare, sector, listOf(ffKey), listOf(ffKey))) {
                        return "写入失败：重置扇区 $sector FF 秘钥验证失败"
                    }
                }
                onStatusUpdate?.invoke("重置完成，正在写入数据...")
                Thread.sleep(100)
            }

            // Phase 2: 使用 FF 秘钥写入源数据
            for (sector in 0 until targetSectorCount) {
                reconnectMifareClassic(mifare)
                val trailerData = sourceBlocks.getOrNull(sector * 4 + 3)
                val sourceKeyA = trailerData?.takeIf { it.size == 16 }?.copyOfRange(0, 6)
                val sourceKeyB = trailerData?.takeIf { it.size == 16 }?.copyOfRange(10, 16)
                val authenticated = authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = sector,
                    keysA = listOf(ffKey, sourceKeyA),
                    keysB = listOf(ffKey, sourceKeyB)
                )
                if (!authenticated) return "写入失败：扇区 $sector 认证失败"

                onStatusUpdate?.invoke("正在写入扇区 ${sector + 1}/$targetSectorCount...")
                val startBlock = mifare.sectorToBlock(sector)
                for (offset in 0 until 4) {
                    val blockIndex = startBlock + offset
                    val targetData = sourceBlocks.getOrNull(blockIndex)
                        ?: return "写入失败：区块 $blockIndex 源数据缺失"
                    if (targetData.size != 16) return "写入失败：区块 $blockIndex 数据长度异常"
                    if (!writeBlockWithRetry(mifare, blockIndex, targetData)) {
                        return "写入失败：区块 $blockIndex 写入异常"
                    }
                    Thread.sleep(20)
                }
            }
            "写入成功"
        } catch (e: Exception) {
            "写入失败：${e.message.orEmpty()}"
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
    }

    /**
     * 保存全部扇区数据到文件
     */
    private fun saveAllSectorsData(uidHex: String, rawBlocks: List<ByteArray?>, sectorKeys: List<Pair<ByteArray?, ByteArray?>>) {
        try {
            val rfidFilesDir = resolveSelfRfidDirectory()
            if (rfidFilesDir == null) {
                val message = "保存扇区数据失败：无法创建 self 目录"
                logDebug(message)
                LogCollector.append(this, "E", message)
                return
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
            refreshSelfTagCount()
        } catch (e: Exception) {
            logDebug("保存扇区数据失败: ${e.message}\n${Log.getStackTraceString(e)}")
            LogCollector.append(this, "E", "保存扇区数据失败: ${e.message}")
        }
    }

    private fun enqueueNdefWriteTask(request: NdefWriteRequest): String {
        if (pendingWriteItem != null || pendingVerifyItem != null || pendingClearFuid || pendingCuidTest || pendingNdefWriteRequest != null) {
            return uiString(R.string.write_finish_current_task_first)
        }

        val validationError = validateNdefWriteRequest(request)
        if (validationError != null) {
            writeToolStatusMessage = validationError
            return validationError
        }

        pendingNdefWriteRequest = request
        writeToolStatusMessage = uiString(R.string.write_ndef_ready)
        return writeToolStatusMessage
    }

    private fun validateNdefWriteRequest(request: NdefWriteRequest): String? {
        return when (request.type) {
            NdefWriteType.TEXT -> {
                if (request.textContent.isBlank()) "请输入要写入的文本内容" else null
            }
            NdefWriteType.URL -> {
                if (request.url.isBlank()) "请输入网页地址" else null
            }
            NdefWriteType.PHONE -> {
                if (request.phone.isBlank()) "请输入电话号码" else null
            }
            NdefWriteType.WIFI -> {
                when {
                    request.wifiSsid.isBlank() -> "请输入WiFi名称(SSID)"
                    request.wifiSecurity.isBlank() -> "请输入WiFi加密类型（WPA/WEP/NONE）"
                    else -> null
                }
            }
        }
    }

    private fun enqueueClearFuidTask(): String {
        if (pendingWriteItem != null || pendingVerifyItem != null || pendingCuidTest || pendingNdefWriteRequest != null) {
            return uiString(R.string.misc_finish_current_write_verify)
        }
        resetDebugInfoDialog("格式化标签调试")
        appendDebugInfoDialog("已进入等待贴卡状态")
        pendingClearFuid = true
        miscStatusMessage = uiString(R.string.misc_format_ready)
        return miscStatusMessage
    }

    private fun enqueueCuidTestTask(): String {
        if (pendingWriteItem != null || pendingVerifyItem != null || pendingClearFuid || pendingNdefWriteRequest != null) {
            return uiString(R.string.misc_finish_current_write_verify)
        }
        pendingCuidTest = true
        miscStatusMessage = uiString(R.string.misc_cuid_test_ready)
        return miscStatusMessage
    }

    private fun testCuidCard(
        tag: Tag,
        onStatusUpdate: ((String) -> Unit)? = null
    ): String {
        val mifare = MifareClassic.get(tag)
            ?: return uiString(R.string.misc_cuid_test_failed, "标签不支持 MIFARE Classic")
        val ffKey = ByteArray(6) { 0xFF.toByte() }
        return try {
            mifare.connect()
            onStatusUpdate?.invoke("正在检测...")

            // Step 1: Authenticate sector 0 with FF key
            val authOk = authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = 0,
                keysA = listOf(ffKey),
                keysB = listOf(ffKey)
            )
            if (!authOk) {
                return uiString(R.string.misc_cuid_format_first)
            }

            // Step 2: Read block 0 (save original)
            val originalBlock0 = readBlockWithRetry(mifare, 0)
                ?: return uiString(R.string.misc_cuid_test_failed, "读取块0失败")

            // Step 3: Read sector 0 trailer (block 3, save original)
            val trailerBlock = mifare.sectorToBlock(0) + 3
            val originalTrailer = readBlockWithRetry(mifare, trailerBlock)
                ?: return uiString(R.string.misc_cuid_test_failed, "读取权限位失败")

            // Step 4: Modify sector 0 access bits to 878787 — must use Key B to write trailer
            val newTrailer = ByteArray(16).apply {
                for (i in 0..5) this[i] = 0xFF.toByte()       // KeyA: FF*6
                this[6] = 0x87.toByte()
                this[7] = 0x87.toByte()
                this[8] = 0x87.toByte()
                this[9] = originalTrailer[9]                   // preserve user data byte
                for (i in 10..15) this[i] = 0xFF.toByte()     // KeyB: FF*6
            }
            val authKeyBStep4 = authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = 0,
                keysA = emptyList(),
                keysB = listOf(ffKey)
            )
            if (!authKeyBStep4 || !writeBlockWithRetry(mifare, trailerBlock, newTrailer)) {
                return uiString(R.string.misc_cuid_test_failed, "无法修改权限位")
            }

            // Step 5: Re-authenticate after trailer change
            val reAuthOk = authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = 0,
                keysA = listOf(ffKey),
                keysB = listOf(ffKey)
            )
            if (!reAuthOk) {
                return uiString(R.string.misc_cuid_test_failed, "修改权限后认证失败，请手动重置卡片")
            }

            // Step 6: Try to write block 0 with test data using KeyA FF
            val testData = hexToBytes("11223344440804006263646566676869")
            val writeOk = writeBlockWithRetry(mifare, 0, testData)

            // Step 7: Restore block 0 if it was modified
            if (writeOk) {
                authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = 0,
                    keysA = listOf(ffKey),
                    keysB = listOf(ffKey)
                )
                writeBlockWithRetry(mifare, 0, originalBlock0)
            }

            // Step 8: Restore sector 0 trailer to default FF078069 — must use Key B to write trailer
            val defaultTrailer = ByteArray(16).apply {
                for (i in 0..5) this[i] = 0xFF.toByte()    // KeyA: FF*6
                val access = hexToBytes("FF078069")
                System.arraycopy(access, 0, this, 6, 4)    // Access: FF 07 80 69
                for (i in 10..15) this[i] = 0xFF.toByte()  // KeyB: FF*6
            }
            authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = 0,
                keysA = emptyList(),
                keysB = listOf(ffKey)
            )
            writeBlockWithRetry(mifare, trailerBlock, defaultTrailer)

            if (writeOk) uiString(R.string.misc_cuid_not_available)
            else uiString(R.string.misc_cuid_available)
        } catch (e: Exception) {
            uiString(R.string.misc_cuid_test_failed, e.message.orEmpty())
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
    }

    private fun resolveSelfRfidDirectory(): File? {
        val deviceIdSuffix = getDeviceIdSuffix()
        val relativePath = "rfid_files/self_$deviceIdSuffix"
        val externalDir = getExternalFilesDir(null)
        val candidates = buildList {
            if (externalDir != null) add(File(externalDir, relativePath))
            add(File(filesDir, relativePath))
        }
        candidates.forEach { dir ->
            logDebug("尝试创建 self 目录: ${dir.absolutePath}")
            if (ensureDirectoryWritable(dir)) {
                logDebug("self 目录可用: ${dir.absolutePath}")
                return dir
            }
            logDebug("self 目录不可用: ${dir.absolutePath}")
        }
        return null
    }

    private fun countSelfTagFiles(): Int {
        val dir = resolveSelfRfidDirectory() ?: return 0
        return dir.walkTopDown()
            .count { it.isFile && it.extension.equals("txt", ignoreCase = true) }
    }

    private fun refreshSelfTagCount() {
        selfTagCount = countSelfTagFiles()
    }

    private fun clearSelfTagFiles(): String {
        val dir = resolveSelfRfidDirectory() ?: return "未找到自有标签目录"
        return try {
            var deleted = 0
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    if (file.delete()) {
                        deleted++
                    }
                }
            refreshSelfTagCount()
            "已清空自有标签，共删除 $deleted 个文件"
        } catch (e: Exception) {
            logDebug("清空自有标签失败: ${e.message}")
            "清空自有标签失败: ${e.message.orEmpty()}"
        }
    }

    private fun clearShareTagFiles(): String {
        // 同时清空拓竹和快造 DB 表及本地文件
        var dbDeleted = 0
        var snapmakerDbDeleted = 0
        filamentDbHelper?.writableDatabase?.let { db ->
            dbDeleted = filamentDbHelper!!.clearShareTagsTable(db)
            snapmakerDbDeleted = filamentDbHelper!!.clearSnapmakerShareTagsTable(db)
        }
        shareTagItems = emptyList()
        snapmakerShareTagItems = emptyList()
        val externalDir = getExternalFilesDir(null) ?: filesDir
        val shareDir = File(externalDir, "rfid_files/share")
        if (!shareDir.exists()) return "已清空标签数据库，共删除 ${dbDeleted + snapmakerDbDeleted} 条数据"
        return try {
            shareDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file -> file.delete() }
            "已清空标签数据库，共删除 ${dbDeleted + snapmakerDbDeleted} 条数据"
        } catch (e: Exception) {
            logDebug("清空标签数据库失败: ${e.message}")
            "清空标签数据库失败: ${e.message.orEmpty()}"
        }
    }

    private fun ensureDirectoryWritable(dir: File): Boolean {
        return try {
            if (dir.exists()) {
                if (!dir.isDirectory) {
                    logDebug("路径存在但不是目录: ${dir.absolutePath}")
                    return false
                }
            } else {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) {
                    logDebug("创建目录失败: ${dir.absolutePath}")
                    return false
                }
            }
            if (!dir.canWrite()) {
                logDebug("目录不可写: ${dir.absolutePath}")
                return false
            }
            true
        } catch (e: Exception) {
            logDebug("目录检查失败: ${dir.absolutePath}, err=${e.message}")
            false
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
        val rawId = androidId.orEmpty().ifBlank { "unknown" }

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
        val dbHelper = filamentDbHelper ?: return emptyList()
        val db = dbHelper.writableDatabase
        val rows = dbHelper.getAllShareTagRows(db)
        val result = ArrayList<ShareTagItem>(rows.size)
        for (row in rows) {
            val rawData = row.rawData ?: continue
            val rawBlocks = parseHexTagFileStrict(rawData) ?: continue
            val colorValuesList = row.colorValues?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            // 若 DB 中尚无生产日期，尝试从 raw_data 提取并回填
            val productionDate = if (!row.productionDate.isNullOrBlank()) {
                row.productionDate
            } else {
                extractProductionDate(rawBlocks)?.also { date ->
                    dbHelper.updateShareTagProductionDate(db, row.fileUid, date)
                }.orEmpty()
            }
            result.add(
                ShareTagItem(
                    relativePath = row.fileUid.lowercase(Locale.US),
                    fileName = "${row.fileUid}.txt",
                    sourceUid = row.fileUid,
                    trayUid = row.trayUid.orEmpty(),
                    materialType = row.materialType.orEmpty(),
                    colorUid = row.colorUid.orEmpty(),
                    colorName = row.colorName.orEmpty(),
                    colorType = row.colorType.orEmpty(),
                    colorValues = colorValuesList,
                    rawBlocks = rawBlocks,
                    dbId = row.id,
                    copyCount = row.copyCount,
                    verified = row.verified,
                    productionDate = productionDate
                )
            )
        }
        return result
    }

    /**
     * 将磁盘上的 share 目录 txt 文件迁移到 DB（含 raw_data）。
     * 仅在首次升级/安装后运行一次，之后由 DB meta key 标记跳过。
     */
    private fun migrateDiskFilesToDb() {
        val dbHelper = filamentDbHelper ?: return
        val db = dbHelper.writableDatabase
        if (dbHelper.getMetaValue(db, "share_disk_migration_v1") != null) return

        val externalDir = getExternalFilesDir(null)
        val shareDir = externalDir?.let { File(it, "rfid_files/share") }
        if (shareDir == null || !shareDir.exists()) {
            dbHelper.setMetaValue(db, "share_disk_migration_v1", "done")
            return
        }

        val existingDbUids = dbHelper.getAllShareTagRows(db)
            .map { it.fileUid.uppercase(Locale.US) }
            .toSet()

        shareDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            .forEach { file ->
                val fileUid = file.nameWithoutExtension.uppercase(Locale.US)
                try {
                    val content = file.readText(Charsets.UTF_8)
                    val rawBlocks = parseHexTagFileStrict(content) ?: return@forEach
                    val normalized = content.trim().lines()
                        .map { it.trim() }.filter { it.isNotBlank() }.take(64).joinToString("\n")
                    if (fileUid in existingDbUids) {
                        // Update raw_data for existing DB rows that lack it
                        dbHelper.updateShareTagRawData(db, fileUid, normalized)
                    } else {
                        val preview = NfcTagProcessor.parseForPreview(rawBlocks, filamentDbHelper) {}
                        val trayUid = preview.trayUidHex.trim()
                        dbHelper.insertShareTag(
                            db,
                            fileUid = fileUid,
                            trayUid = trayUid.ifBlank { null },
                            materialType = preview.displayData.type.ifBlank { null },
                            colorUid = preview.displayData.colorCode.ifBlank { null },
                            colorName = preview.displayData.colorName.ifBlank { null },
                            colorType = preview.displayData.colorType.ifBlank { null },
                            colorValues = preview.displayData.colorValues.joinToString(",").ifBlank { null },
                            rawData = normalized
                        )
                    }
                } catch (e: Exception) {
                    logDebug("迁移标签文件失败 ${file.name}: ${e.message}")
                }
            }
        dbHelper.setMetaValue(db, "share_disk_migration_v1", "done")
    }

    private fun deleteShareTagItem(item: ShareTagItem): String {
        return try {
            filamentDbHelper?.writableDatabase?.let { db ->
                filamentDbHelper!!.deleteShareTagByFileUid(db, item.sourceUid.uppercase(Locale.US))
            }
            // 同时尝试删除磁盘文件（兼容旧版迁移数据）
            try {
                val externalDir = getExternalFilesDir(null)
                val shareDir = externalDir?.let { File(it, "rfid_files/share") }
                if (shareDir != null) {
                    shareDir.walkTopDown()
                        .filter { it.isFile && it.nameWithoutExtension.equals(item.sourceUid, ignoreCase = true) }
                        .forEach { it.delete() }
                }
            } catch (_: Exception) { }
            shareTagItems = shareTagItems.filterNot { it.relativePath == item.relativePath }
            "删除成功：${item.sourceUid}"
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
                migrateDiskFilesToDb()
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

    private fun syncAnomalyUidsAsync() {
        val dbHelper = filamentDbHelper ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uids = com.m0h31h31.bamburfidreader.utils.AnalyticsReporter.fetchAnomalyUids(applicationContext)
                if (uids != null) {
                    dbHelper.saveAnomalyUids(dbHelper.writableDatabase, uids)
                    withContext(Dispatchers.Main) {
                        anomalyUids = uids
                    }
                } else {
                    // 拉取失败时，用本地缓存
                    val cached = dbHelper.getAnomalyUids(dbHelper.readableDatabase)
                    withContext(Dispatchers.Main) {
                        anomalyUids = cached
                    }
                }
            } catch (_: Exception) {
                // 静默失败，不影响主流程
            }
        }
    }

    // ── 在线更新 ──────────────────────────────────────────────────────────────

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L && id == updateDownloadId) {
                installDownloadedApk(id)
            }
        }
    }

    private fun checkForUpdateAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = AnalyticsReporter.checkForUpdate(applicationContext) ?: return@launch
                withContext(Dispatchers.Main) {
                    pendingUpdateInfo = info
                    startUpdate(info)   // 检测到新版本立即自动下载
                }
            } catch (_: Exception) {}
        }
    }

    fun startUpdate(info: UpdateInfo) {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }
        startApkDownload(info.downloadUrl)
    }

    private fun startApkDownload(downloadUrl: String) {
        if (isDownloadingUpdate) return
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dest = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BambuRfidReader_update.apk")
        if (dest.exists()) dest.delete()
        val request = DownloadManager.Request(android.net.Uri.parse(downloadUrl))
            .setTitle(getString(R.string.update_download_notification_title))
            .setDescription(getString(R.string.update_downloading))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(android.net.Uri.fromFile(dest))
            .setMimeType("application/vnd.android.package-archive")
        updateDownloadId = dm.enqueue(request)
        isDownloadingUpdate = true
        Toast.makeText(this, getString(R.string.update_downloading), Toast.LENGTH_LONG).show()
        logDebug("Update download enqueued id=$updateDownloadId url=$downloadUrl")
    }

    private fun installDownloadedApk(downloadId: Long) {
        isDownloadingUpdate = false
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        val success = cursor.use {
            it.moveToFirst() &&
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        if (!success) { logDebug("Update download failed or not found"); return }
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BambuRfidReader_update.apk")
        if (!apkFile.exists()) { logDebug("APK not found: ${apkFile.absolutePath}"); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.update_provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            logDebug("Install intent failed: ${e.message}")
        }
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

    /**
     * 从 block12 提取耗材生产日期，返回 yy-mm-dd 格式，无法解析时返回 null。
     * block12 原始内容为 ASCII 字符串，格式 YYYY_MM_DD_HH_MM。
     */
    private fun extractProductionDate(rawBlocks: List<ByteArray?>): String? {
        val block = rawBlocks.getOrNull(12) ?: return null
        if (block.size < 3) return null
        val trimmed = block.dropLastWhile { it == 0.toByte() || it == 0x20.toByte() }.toByteArray()
        if (trimmed.isEmpty()) return null
        if (!trimmed.all { it in 0x20..0x7E }) return null
        val raw = String(trimmed, Charsets.US_ASCII).trim()
        val parts = raw.split('_')
        if (parts.size < 3) return null
        val year = parts[0]; val month = parts[1]; val day = parts[2]
        if (!listOf(year, month, day).all { s -> s.all(Char::isDigit) }) return null
        if (year.length < 2) return null
        return "${year.takeLast(2)}-${month.padStart(2, '0')}-${day.padStart(2, '0')}"
    }

    /** 严格校验：必须恰好 64 行，每行恰好 32 个十六进制字符（空格会被跳过计数外）。 */
    /**
     * 校验标签合法性：
     * 1. 所有扇区尾块权限位 + 用户数据字节必须为 87878769。
     * 2. 使用 block0 前 4 字节作为 UID 派生密钥，校验每个扇区的 KeyA / KeyB。
     * 尾块布局：KeyA(6B) + AccessBits(3B) + UserByte(1B) + KeyB(6B)
     */
    private fun isValidBambuTag(rawBlocks: List<ByteArray?>): Boolean {
        // 校验 1：权限位和用户数据
        for (sector in 0 until 16) {
            val trailer = rawBlocks.getOrNull(sector * 4 + 3) ?: return false
            if (trailer.size < 16) return false
            if (trailer[6] != 0x87.toByte() ||
                trailer[7] != 0x87.toByte() ||
                trailer[8] != 0x87.toByte() ||
                trailer[9] != 0x69.toByte()
            ) return false
        }

        // 校验 2：使用 UID 派生密钥，逐扇区比对 KeyA 和 KeyB
        val block0 = rawBlocks.getOrNull(0) ?: return false
        if (block0.size < 4) return false
        val uid = block0.copyOfRange(0, 4)
        val expectedKeys = deriveBambuKeys(uid)
        for (sector in 0 until 16) {
            val trailer = rawBlocks[sector * 4 + 3]!!
            val (expectedKeyA, expectedKeyB) = expectedKeys.getOrNull(sector) ?: return false
            val actualKeyA = trailer.copyOfRange(0, 6)
            val actualKeyB = trailer.copyOfRange(10, 16)
            if (!actualKeyA.contentEquals(expectedKeyA)) return false
            if (!actualKeyB.contentEquals(expectedKeyB)) return false
        }
        return true
    }

    private fun parseHexTagFileStrict(content: String): List<ByteArray?>? {
        val lines = content.trim().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.size != 64) return null
        val blocks = MutableList<ByteArray?>(64) { null }
        for ((i, line) in lines.withIndex()) {
            val hex = line.replace(" ", "").uppercase(Locale.US)
            if (hex.length != 32 || !hex.all { c -> c in '0'..'9' || c in 'A'..'F' }) return null
            blocks[i] = hexToBytes(hex)
        }
        return blocks
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

    private fun writeTagFromDump(tag: Tag, item: ShareTagItem, onStatusUpdate: ((String) -> Unit)? = null): String {
        val mifare = MifareClassic.get(tag) ?: return "写入失败：标签不支持 MIFARE Classic"
        val sourceBlocks = item.rawBlocks
        if (sourceBlocks.isEmpty()) {
            return "写入失败：源文件数据为空"
        }

        val ffKey = ByteArray(6) { 0xFF.toByte() }
        val targetSectorCount = minOf(WRITE_SECTOR_COUNT, mifare.sectorCount)

        return try {
            var resumePoint = WriteResumePoint(sector = 0, blockOffset = 0)
            var recoverAttempts = 0

            while (resumePoint.sector < targetSectorCount) {
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

                    for (sector in resumePoint.sector until targetSectorCount) {
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

                        onStatusUpdate?.invoke("正在写入扇区 ${sector + 1}/$targetSectorCount...")

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
                    resumePoint = WriteResumePoint(targetSectorCount, 0)
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
                    if (detected.sector >= targetSectorCount) {
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

    /**
     * CUID改写三阶段流程（集成校验，无需重复贴卡）：
     * Phase 1 — 用穷举密钥将所有扇区 Trailer 重置为全FF（FF*6 | FF078069 | FF*6），
     *           每扇区最多重试5次，写后立即用FF密钥回读权限位校验。
     * Phase 2 — 全部 Trailer 重置完成后，使用FF密钥逐扇区写入源数据，每扇区最多重试5次。
     * Phase 3 — 重连，使用源数据密钥全量回读校验，Trailer 仅比较访问位。
     */
    private fun writeCModifyTag(
        tag: Tag,
        item: ShareTagItem,
        onStatusUpdate: ((String) -> Unit)? = null
    ): String {
        val mifare = MifareClassic.get(tag) ?: return "改写失败：标签不支持 MIFARE Classic"
        val sourceBlocks = item.rawBlocks
        if (sourceBlocks.isEmpty()) return "改写失败：源数据为空"

        val uid = tag.id ?: return "改写失败：无法读取卡 UID"
        val currentKeysA = deriveWriteKeys(uid, WRITE_INFO_A)
        val currentKeysB = deriveWriteKeys(uid, WRITE_INFO_B)

        val targetUidBytes = runCatching { hexToBytes(item.sourceUid) }.getOrNull()?.takeIf { it.size >= 4 }
        val targetKeysA = if (targetUidBytes != null) deriveWriteKeys(targetUidBytes, WRITE_INFO_A) else emptyList()
        val targetKeysB = if (targetUidBytes != null) deriveWriteKeys(targetUidBytes, WRITE_INFO_B) else emptyList()

        val ffKey = ByteArray(6) { 0xFF.toByte() }
        // 目标 Trailer：KeyA=FF*6, Access=FF078069, KeyB=FF*6
        val fullFfTrailer = ByteArray(16).apply {
            for (i in 0..5) this[i] = 0xFF.toByte()
            this[6] = 0xFF.toByte(); this[7] = 0x07.toByte()
            this[8] = 0x80.toByte(); this[9] = 0x69.toByte()
            for (i in 10..15) this[i] = 0xFF.toByte()
        }
        val targetSectorCount = minOf(WRITE_SECTOR_COUNT, mifare.sectorCount)
        val maxRetry = 5

        // 分步 Trailer 还原：Bambu 卡权限位 87878769 只允许 KeyB 写 Trailer，分三步避免卡死。
        // 步骤间不整体重连，直接重认证，节省每扇区 ~200ms。
        // 校验：优先读回 KeyB 字节（FF078069 下可读）；读回 00 或失败则以 FF 认证成功为准。
        fun resetTrailerStepByStep(sector: Int, trailerBlock: Int): Boolean {
            val curKeyA = currentKeysA.getOrNull(sector) ?: return false
            val curKeyB = currentKeysB.getOrNull(sector) ?: return false

            val step1Trailer = ByteArray(16).apply {    // 仅改权限位 → FF078069，密钥保持派生值
                System.arraycopy(curKeyA, 0, this, 0, 6)
                this[6] = 0xFF.toByte(); this[7] = 0x07.toByte()
                this[8] = 0x80.toByte(); this[9] = 0x69.toByte()
                System.arraycopy(curKeyB, 0, this, 10, 6)
            }
            val step2Trailer = ByteArray(16).apply {    // KeyA → FF，保留 curKeyB
                for (i in 0..5) this[i] = 0xFF.toByte()
                this[6] = 0xFF.toByte(); this[7] = 0x07.toByte()
                this[8] = 0x80.toByte(); this[9] = 0x69.toByte()
                System.arraycopy(curKeyB, 0, this, 10, 6)
            }
            // step3 = fullFfTrailer

            // Step 1：必须用派生 KeyB（87878769 权限只允许 KeyB 写 Trailer）
            reconnectMifareClassic(mifare)
            if (!authenticateSectorWithRetry(mifare, sector, emptyList(), listOf(curKeyB))) return false
            if (!writeBlockWithRetry(mifare, trailerBlock, step1Trailer)) return false
            Thread.sleep(15)

            // Step 2：权限位已是 FF078069，KeyA/B 均可写；直接重认证，不重连
            if (!authenticateSectorWithRetry(mifare, sector, listOf(curKeyA, ffKey), listOf(curKeyB, ffKey))) return false
            if (!writeBlockWithRetry(mifare, trailerBlock, step2Trailer)) return false
            Thread.sleep(15)

            // Step 3：KeyA 已是 FF；直接重认证，不重连
            if (!authenticateSectorWithRetry(mifare, sector, listOf(ffKey), listOf(curKeyB, ffKey))) return false
            if (!writeBlockWithRetry(mifare, trailerBlock, fullFfTrailer)) return false
            Thread.sleep(15)

            // 校验：直接重认证，不重连
            if (!authenticateSectorWithRetry(mifare, sector, listOf(ffKey), listOf(ffKey))) return false
            val readBack = readBlockWithRetry(mifare, trailerBlock)
            if (readBack != null && readBack.size >= 16) {
                val accessOk = readBack[6] == 0xFF.toByte() && readBack[7] == 0x07.toByte() &&
                               readBack[8] == 0x80.toByte() && readBack[9] == 0x69.toByte()
                val keyBOk = (10..15).all { readBack[it] == 0xFF.toByte() }
                if (accessOk && keyBOk) return true     // 读回完全确认 ✓
                if (accessOk) return true               // KeyB 读回 00（部分卡屏蔽），以权限位+FF认证为准
            }
            return true // 读取失败但 FF 认证已通过，视为成功
        }

        val retryHint = "请移开标签重新贴上重试，确保标签处于手机 NFC 区域"

        return try {
            mifare.connect()
            Thread.sleep(300)

            // ===== Phase 1: 将所有扇区 Trailer 重置为全FF =====
            onStatusUpdate?.invoke("正在还原 Trailer，请等待...")
            for (sector in 0 until targetSectorCount) {
                onStatusUpdate?.invoke("正在还原 Trailer ${sector + 1}/$targetSectorCount...")
                val trailerBlock = mifare.sectorToBlock(sector) + 3
                var done = false
                for (attempt in 1..maxRetry) {
                    // 优先：分步还原（适合 Bambu 派生密钥卡）
                    if (resetTrailerStepByStep(sector, trailerBlock)) { done = true; break }
                    // 回退：卡已处于 FF 或目标卡密钥状态，直接一步写入
                    reconnectMifareClassic(mifare)
                    val allA = buildList { add(ffKey); targetKeysA.getOrNull(sector)?.let { add(it) } }
                    val allB = buildList { add(ffKey); targetKeysB.getOrNull(sector)?.let { add(it) } }
                    if (authenticateSectorWithRetry(mifare, sector, allA, allB) &&
                        writeBlockWithRetry(mifare, trailerBlock, fullFfTrailer)) {
                        Thread.sleep(15)
                        if (authenticateSectorWithRetry(mifare, sector, listOf(ffKey), listOf(ffKey))) {
                            done = true; break
                        }
                    }
                    Thread.sleep(60L * attempt)
                }
                if (!done) return "扇区 $sector Trailer 还原失败，$retryHint"
            }
            onStatusUpdate?.invoke("Trailer 还原完成，正在写入目标数据...")
            Thread.sleep(100)

            // ===== Phase 2: 使用FF密钥逐扇区写入源数据 =====
            for (sector in 0 until targetSectorCount) {
                onStatusUpdate?.invoke("正在写入数据 ${sector + 1}/$targetSectorCount...")
                var done = false
                for (attempt in 1..maxRetry) {
                    reconnectMifareClassic(mifare)
                    if (!authenticateSectorWithRetry(mifare, sector, listOf(ffKey), listOf(ffKey))) {
                        Thread.sleep(60L * attempt); continue
                    }
                    val startBlock = mifare.sectorToBlock(sector)
                    var blockFailed = false
                    for (offset in 0 until 4) {
                        val blockIndex = startBlock + offset
                        val srcIdx = sector * 4 + offset
                        val blockData = sourceBlocks.getOrNull(srcIdx)
                            ?: return "改写失败：区块 $srcIdx 源数据缺失"
                        if (blockData.size != 16) return "改写失败：区块 $srcIdx 数据长度异常"
                        if (!writeBlockWithRetry(mifare, blockIndex, blockData)) { blockFailed = true; break }
                        Thread.sleep(15)
                    }
                    if (!blockFailed) { done = true; break }
                    Thread.sleep(60L * attempt)
                }
                if (!done) return "扇区 $sector 数据写入失败，$retryHint"
            }

            // ===== Phase 3: 重连，使用源数据密钥全量校验 =====
            onStatusUpdate?.invoke("写入完成，正在校验数据...")
            try { mifare.close() } catch (_: Exception) {}
            Thread.sleep(150)
            mifare.connect()
            Thread.sleep(200)

            for (sector in 0 until targetSectorCount) {
                val trailerData = sourceBlocks.getOrNull(sector * 4 + 3)
                    ?: return "校验失败：扇区 $sector 源 Trailer 缺失"
                if (trailerData.size != 16) return "校验失败：扇区 $sector Trailer 长度异常"
                val srcKeyA = trailerData.copyOfRange(0, 6)
                val srcKeyB = trailerData.copyOfRange(10, 16)
                if (!authenticateSectorWithRetry(mifare, sector, listOf(srcKeyA), listOf(srcKeyB))) {
                    return "改写成功但校验认证失败：扇区 $sector，$retryHint"
                }
                val startBlock = mifare.sectorToBlock(sector)
                for (offset in 0 until 4) {
                    val blockIndex = startBlock + offset
                    val srcIdx = sector * 4 + offset
                    val expected = sourceBlocks.getOrNull(srcIdx) ?: continue
                    val actual = readBlockWithRetry(mifare, blockIndex)
                        ?: return "改写成功但校验读取失败：区块 $blockIndex，$retryHint"
                    val cmpExpected = if (blockIndex % 4 == 3) expected.copyOf().also {
                        for (i in 0..5) it[i] = 0; for (i in 10..15) it[i] = 0
                    } else expected
                    val cmpActual = if (blockIndex % 4 == 3) actual.copyOf().also {
                        for (i in 0..5) it[i] = 0; for (i in 10..15) it[i] = 0
                    } else actual
                    if (!cmpActual.contentEquals(cmpExpected)) {
                        return "改写成功但数据不一致：区块 $blockIndex，$retryHint"
                    }
                }
            }
            onStatusUpdate?.invoke("改写并校验完成！")
            "改写成功：已完成全部区块写入并校验"
        } catch (e: Exception) {
            "改写失败：${e.message.orEmpty()}，$retryHint"
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
    }

    private fun clearFuidAndResetTag(
        tag: Tag,
        onStatusUpdate: ((String) -> Unit)? = null
    ): String {
        val mifare = MifareClassic.get(tag) ?: return "格式化失败：标签不支持 MIFARE Classic"
        val uid = tag.id ?: return "格式化失败：无法读取UID"
        if (uid.isEmpty()) return "格式化失败：UID为空"

        val derivedKeysA = try {
            deriveWriteKeys(uid, WRITE_INFO_A)
        } catch (e: Exception) {
            return "格式化失败：派生KeyA失败 ${e.message.orEmpty()}"
        }
        val derivedKeysB = try {
            deriveWriteKeys(uid, WRITE_INFO_B)
        } catch (e: Exception) {
            return "格式化失败：派生KeyB失败 ${e.message.orEmpty()}"
        }
        if (derivedKeysA.size < WRITE_SECTOR_COUNT || derivedKeysB.size < WRITE_SECTOR_COUNT) {
            return "格式化失败：派生秘钥数量不足"
        }

        val (snapKeysA, snapKeysB) = deriveSnapmakerKeys(uid)
        val crealityKeyA = deriveCrealityKeyA(uid)

        val ffKey = ByteArray(6) { 0xFF.toByte() }
        val zeroBlock = ByteArray(16) { 0x00.toByte() }
        val accessDefault = hexToBytes("FF078069")
        val targetSectorCount = minOf(WRITE_SECTOR_COUNT, mifare.sectorCount)

        fun logStep(message: String) {
            logDebug("格式化标签: $message")
            LogCollector.append(this, "I", "格式化标签: $message")
            appendDebugInfoDialog(message)
        }

        fun fail(message: String): String {
            logStep(message)
            return message
        }

        fun buildTrailer(keyA: ByteArray, access: ByteArray, keyB: ByteArray): ByteArray {
            return ByteArray(16).apply {
                System.arraycopy(keyA, 0, this, 0, 6)
                System.arraycopy(access, 0, this, 6, 4)
                System.arraycopy(keyB, 0, this, 10, 6)
            }
        }

        fun resetTrailerByDerivedKeyBStages(sector: Int): Boolean {
            val trailerBlock = mifare.sectorToBlock(sector) + 3
            val derivedA = derivedKeysA[sector]
            val derivedB = derivedKeysB[sector]
            logStep("扇区$sector.trailer: 使用派生KeyB认证")
            val authByDerivedB = authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = sector,
                keysA = emptyList(),
                keysB = listOf(derivedB)
            )
            if (!authByDerivedB) {
                logStep("扇区$sector.trailer: 派生KeyB认证失败")
                return false
            }
            logStep("扇区$sector.trailer: 派生KeyB认证成功")

            // 按用户要求：直接使用派生 KeyB 授权，一次性写入完整默认 trailer
            if (!writeBlockWithRetry(mifare, trailerBlock, buildTrailer(ffKey, accessDefault, ffKey))) {
                logStep("扇区$sector.trailer: 写入完整默认trailer失败")
                return false
            }
            logStep("扇区$sector.trailer: 写入完整默认trailer成功")

            // 优先以 FF 认证作为成功判据；若失败，则允许派生秘钥继续认证（兼容“只改了权限位”的卡）
            val ffAuthOk = authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = sector,
                keysA = listOf(ffKey),
                keysB = listOf(ffKey)
            )
            logStep("扇区$sector.trailer: FF认证${if (ffAuthOk) "成功" else "失败"}")
            if (ffAuthOk) return true

            val derivedAuthStillOk = authenticateSectorWithRetry(
                mifare = mifare,
                sectorIndex = sector,
                keysA = listOf(derivedA),
                keysB = listOf(derivedB)
            )
            logStep(
                "扇区$sector.trailer: FF失败后派生秘钥复验${if (derivedAuthStillOk) "成功（继续后续清零）" else "失败"}"
            )
            return derivedAuthStillOk
        }

        fun resetTrailerByCrealityDerivedKey(sector: Int): Boolean {
            val trailerBlock = mifare.sectorToBlock(sector) + 3
            // 创想：KeyA=派生，KeyB=FF，访问位=FF078069，可直接用 KeyA 写 Trailer
            reconnectMifareClassic(mifare)
            if (!authenticateSectorWithRetry(mifare, sector, listOf(crealityKeyA), listOf(ffKey))) {
                logStep("扇区$sector: 创想派生 KeyA 认证失败")
                return false
            }
            if (!writeBlockWithRetry(mifare, trailerBlock, buildTrailer(ffKey, accessDefault, ffKey))) {
                logStep("扇区$sector: 创想重置秘钥为 FF 失败")
                return false
            }
            Thread.sleep(15)
            val ffAuthOk = authenticateSectorWithRetry(mifare, sector, listOf(ffKey), listOf(ffKey))
            logStep("扇区$sector: 创想重置后 FF 验证${if (ffAuthOk) "成功" else "失败"}")
            return ffAuthOk
        }

        fun resetTrailerBySnapmakerDerivedKeys(sector: Int): Boolean {
            val trailerBlock = mifare.sectorToBlock(sector) + 3
            val curKeyA = snapKeysA[sector]
            val curKeyB = snapKeysB[sector]

            // 步骤1：仅用派生 KeyB 认证，将权限位改为 FF078069（保留派生秘钥）
            reconnectMifareClassic(mifare)
            if (!authenticateSectorWithRetry(mifare, sector, emptyList(), listOf(curKeyB))) {
                logStep("扇区$sector: 快造派生 KeyB 认证失败")
                return false
            }
            val step1Trailer = ByteArray(16).apply {
                System.arraycopy(curKeyA, 0, this, 0, 6)
                this[6] = 0xFF.toByte(); this[7] = 0x07.toByte()
                this[8] = 0x80.toByte(); this[9] = 0x69.toByte()
                System.arraycopy(curKeyB, 0, this, 10, 6)
            }
            if (!writeBlockWithRetry(mifare, trailerBlock, step1Trailer)) {
                logStep("扇区$sector: 快造修改权限位失败")
                return false
            }
            Thread.sleep(15)

            // 步骤2：权限位已是 FF078069，将 KeyA/B 改为 FF
            if (!authenticateSectorWithRetry(mifare, sector, listOf(curKeyA, ffKey), listOf(curKeyB, ffKey))) {
                logStep("扇区$sector: 快造步骤2认证失败")
                return false
            }
            if (!writeBlockWithRetry(mifare, trailerBlock, buildTrailer(ffKey, accessDefault, ffKey))) {
                logStep("扇区$sector: 快造重置秘钥为 FF 失败")
                return false
            }
            Thread.sleep(15)

            val ffAuthOk = authenticateSectorWithRetry(mifare, sector, listOf(ffKey), listOf(ffKey))
            logStep("扇区$sector: 快造重置后 FF 验证${if (ffAuthOk) "成功" else "失败"}")
            return ffAuthOk
        }

        return try {
            mifare.connect()
            onStatusUpdate?.invoke("正在格式化")
            logStep("开始处理 UID=${uid.toHex().uppercase(Locale.US)}")

            if (mifare.sectorCount < WRITE_SECTOR_COUNT) {
                return fail("格式化失败：标签扇区数量不足 ${mifare.sectorCount}")
            }

            // 记录第0扇区成功的品牌，后续扇区优先使用
            var detectedBrand = "bambu"
            val originalBlock0 = run {
                when {
                    authenticateSectorWithRetry(
                        mifare, 0,
                        keysA = listOf(derivedKeysA[0]),
                        keysB = listOf(derivedKeysB[0])
                    ) -> { logStep("扇区0: 拓竹派生秘钥认证成功"); detectedBrand = "bambu" }
                    authenticateSectorWithRetry(
                        mifare, 0,
                        keysA = listOf(ffKey),
                        keysB = listOf(ffKey)
                    ) -> { logStep("扇区0: FF秘钥认证成功"); detectedBrand = "ff" }
                    authenticateSectorWithRetry(
                        mifare, 0,
                        keysA = listOf(crealityKeyA),
                        keysB = listOf(ffKey)
                    ) -> { logStep("扇区0: 创想派生秘钥认证成功"); detectedBrand = "creality" }
                    authenticateSectorWithRetry(
                        mifare, 0,
                        keysA = listOf(snapKeysA[0]),
                        keysB = listOf(snapKeysB[0])
                    ) -> { logStep("扇区0: 快造派生秘钥认证成功"); detectedBrand = "snapmaker" }
                    else -> return fail("格式化失败：扇区0 拓竹/FF/创想/快造秘钥认证均失败，无法读取块0")
                }
                readBlockWithRetry(mifare, 0) ?: return fail("格式化失败：读取块0失败")
            }
            logStep("检测到卡片品牌：$detectedBrand，后续扇区优先使用此品牌秘钥")
            logStep("块0读取成功（用于最终校验）")

            fun runStep3VerifyByFf(): String? {
                for (sector in 0 until targetSectorCount) {
                    logStep("步骤3/3 扇区$sector: 使用FF秘钥校验")
                    val authOk = authenticateSectorWithRetry(
                        mifare = mifare,
                        sectorIndex = sector,
                        keysA = listOf(ffKey),
                        keysB = listOf(ffKey)
                    )
                    if (!authOk) {
                        return "校验失败：扇区 $sector 使用FF秘钥认证失败"
                    }
                    val startBlock = mifare.sectorToBlock(sector)
                    for (offset in 0 until 4) {
                        val blockIndex = startBlock + offset
                        val actual = readBlockWithRetry(mifare, blockIndex)
                            ?: return "校验失败：区块 $blockIndex 读取失败"
                        if (blockIndex == 0) {
                            if (!actual.contentEquals(originalBlock0)) {
                                return "校验失败：区块0被修改"
                            }
                        } else if (blockIndex % 4 == 3) {
                            // trailer 不参与“清零校验”，本步骤只要求 FF 可认证即可。
                            continue
                        } else if (!actual.all { it == 0.toByte() }) {
                            return "校验失败：区块 $blockIndex 不是全00"
                        }
                    }
                    logStep("扇区$sector: 校验通过")
                }
                return null
            }

            val maxStep3RetryCount = 2
            for (attempt in 0..maxStep3RetryCount) {
                if (attempt > 0) {
                    logStep("步骤3FF校验失败后重试：第${attempt}次重试（最多${maxStep3RetryCount}次）")
                }

                // 第一步：重置所有扇区 trailer 为 FF，优先使用第0扇区检测到的品牌秘钥
                // brandOrder: 检测品牌排第一，其余依次
                val allBrands = listOf("bambu", "ff", "creality", "snapmaker")
                val brandOrder = listOf(detectedBrand) + (allBrands - detectedBrand)

                for (sector in 0 until targetSectorCount) {
                    logStep("步骤1/3 扇区$sector: 开始重置 trailer（优先品牌：$detectedBrand）")
                    var sectorDone = false
                    for (brand in brandOrder) {
                        if (sectorDone) break
                        when (brand) {
                            "bambu" -> {
                                val auth = authenticateSectorWithRetry(
                                    mifare, sector,
                                    keysA = listOf(derivedKeysA[sector]),
                                    keysB = listOf(derivedKeysB[sector])
                                )
                                if (auth) {
                                    logStep("扇区$sector: 拓竹派生秘钥认证成功，重置 trailer")
                                    if (!resetTrailerByDerivedKeyBStages(sector))
                                        return fail("格式化失败：扇区 $sector 拓竹 trailer 重置失败")
                                    logStep("扇区$sector: 拓竹 trailer 重置成功")
                                    sectorDone = true
                                }
                            }
                            "ff" -> {
                                val auth = authenticateSectorWithRetry(
                                    mifare, sector,
                                    keysA = listOf(ffKey),
                                    keysB = listOf(ffKey)
                                )
                                if (auth) {
                                    logStep("扇区$sector: FF认证成功，检查权限位")
                                    val trailerBlock = mifare.sectorToBlock(sector) + 3
                                    val trailerData = readBlockWithRetry(mifare, trailerBlock)
                                    if (trailerData != null) {
                                        val currentAccess = trailerData.copyOfRange(6, 10)
                                        if (!currentAccess.contentEquals(accessDefault)) {
                                            logStep("扇区$sector: 权限位非标准，修正为 FF078069")
                                            val reAuthKeyB = authenticateSectorWithRetry(
                                                mifare, sector,
                                                keysA = emptyList(),
                                                keysB = listOf(ffKey)
                                            )
                                            if (reAuthKeyB && writeBlockWithRetry(mifare, trailerBlock, buildTrailer(ffKey, accessDefault, ffKey))) {
                                                logStep("扇区$sector: 权限位修正成功")
                                            } else {
                                                logStep("扇区$sector: 权限位修正失败，继续")
                                            }
                                        } else {
                                            logStep("扇区$sector: 权限位已是 FF078069")
                                        }
                                    }
                                    sectorDone = true
                                }
                            }
                            "creality" -> {
                                val auth = authenticateSectorWithRetry(
                                    mifare, sector,
                                    keysA = listOf(crealityKeyA),
                                    keysB = listOf(ffKey)
                                )
                                if (auth) {
                                    logStep("扇区$sector: 创想派生秘钥认证成功，重置 trailer")
                                    if (!resetTrailerByCrealityDerivedKey(sector))
                                        return fail("格式化失败：扇区 $sector 创想 trailer 重置失败")
                                    logStep("扇区$sector: 创想 trailer 重置成功")
                                    sectorDone = true
                                }
                            }
                            "snapmaker" -> {
                                val auth = authenticateSectorWithRetry(
                                    mifare, sector,
                                    keysA = listOf(snapKeysA[sector]),
                                    keysB = listOf(snapKeysB[sector])
                                )
                                if (auth) {
                                    logStep("扇区$sector: 快造派生秘钥认证成功，重置 trailer")
                                    if (!resetTrailerBySnapmakerDerivedKeys(sector))
                                        return fail("格式化失败：扇区 $sector 快造 trailer 重置失败")
                                    logStep("扇区$sector: 快造 trailer 重置成功")
                                    sectorDone = true
                                }
                            }
                        }
                    }
                    if (!sectorDone) return fail("格式化失败：扇区 $sector 拓竹/FF/创想/快造 秘钥认证均失败")
                }
                logStep("已重置全部 trailer")

                // 第二步：使用 FF/派生秘钥，将数据区块清零（不清 block0，不清 trailer）
                for (sector in 0 until targetSectorCount) {
                    logStep("步骤2/3 扇区$sector: 使用FF认证并清零区块")
                    val authOk = authenticateSectorWithRetry(
                        mifare = mifare,
                        sectorIndex = sector,
                        keysA = listOf(ffKey, derivedKeysA[sector], crealityKeyA, snapKeysA[sector]),
                        keysB = listOf(ffKey, derivedKeysB[sector], snapKeysB[sector])
                    )
                    if (!authOk) {
                        return fail("格式化失败：扇区 $sector 使用FF/派生秘钥认证失败")
                    }
                    val startBlock = mifare.sectorToBlock(sector)
                    for (offset in 0 until 4) {
                        val blockIndex = startBlock + offset
                        if (blockIndex == 0 || blockIndex % 4 == 3) {
                            continue
                        }
                        if (!writeBlockWithRetry(mifare, blockIndex, zeroBlock)) {
                            return fail("格式化失败：区块 $blockIndex 写零失败")
                        }
                    }
                    logStep("扇区$sector: 区块清零完成")
                }
                logStep("已完成数据区块清零（跳过 block0 和 trailer）")
                onStatusUpdate?.invoke("格式化完成，正在校检")

                val verifyError = runStep3VerifyByFf()
                if (verifyError == null) {
                    logStep("校验通过")
                    return "格式化标签成功：已重置并校验通过"
                }

                if (attempt < maxStep3RetryCount) {
                    logStep("$verifyError，准备从头重试（${attempt + 1}/$maxStep3RetryCount）")
                    continue
                }
                return fail(verifyError)
            }

            fail("格式化失败：步骤3重试结束仍未通过")
        } catch (e: Exception) {
            logDebug("格式化标签失败: ${e.message}\n${Log.getStackTraceString(e)}")
            LogCollector.append(this, "E", "格式化标签失败: ${e.message}")
            appendDebugInfoDialog("异常: ${e.message.orEmpty()}")
            "格式化失败：${e.message.orEmpty()}"
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeNdefDataAndVerify(tag: Tag, request: NdefWriteRequest): String {
        val message = try {
            buildNdefMessage(request)
        } catch (e: Exception) {
            return "NDEF写入失败：生成消息异常 ${e.message.orEmpty()}"
        }
        val expectedBytes = message.toByteArray()
        val mifareClassic = MifareClassic.get(tag)

        return try {
            if (mifareClassic != null) {
                writeNdefByMifareClassicMappingAndVerify(mifareClassic, expectedBytes)
            } else {
                "NDEF写入失败：标签不支持 MIFARE Classic 直写映射"
            }
        } catch (e: Exception) {
            "NDEF写入失败：${e.message.orEmpty()}"
        } finally {
            try {
                mifareClassic?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeNdefByMifareClassicMappingAndVerify(
        mifare: MifareClassic,
        ndefPayload: ByteArray
    ): String {
        val ffKey = ByteArray(6) { 0xFF.toByte() }
        val tlv = buildNdefTlv(ndefPayload)

        return try {
            if (!mifare.isConnected) {
                mifare.connect()
            }
            if (mifare.sectorCount <= 1) {
                return "NDEF写入失败：M1卡扇区数量不足"
            }

            val dataBlocks = ArrayList<Int>()
            for (sector in 1 until mifare.sectorCount) {
                val authOk = authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = sector,
                    keysA = listOf(ffKey),
                    keysB = listOf(ffKey)
                )
                if (!authOk) {
                    return "NDEF写入失败：M1映射认证失败（扇区 $sector，需FF秘钥）"
                }
                val startBlock = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)
                val trailerBlock = startBlock + blockCount - 1
                for (offset in 0 until blockCount) {
                    val blockIndex = startBlock + offset
                    if (blockIndex == trailerBlock) continue
                    dataBlocks.add(blockIndex)
                }
            }

            val capacity = dataBlocks.size * 16
            if (tlv.size > capacity) {
                return "NDEF写入失败：M1映射容量不足（需要${tlv.size}字节，最大${capacity}字节）"
            }

            val usedBlocks = ArrayList<Int>()
            val skippedBlocks = ArrayList<Int>()
            var writeOffset = 0
            for (blockIndex in dataBlocks) {
                if (writeOffset >= tlv.size) break
                val blockData = ByteArray(16) { 0x00.toByte() }
                val copyLen = minOf(16, tlv.size - writeOffset)
                System.arraycopy(tlv, writeOffset, blockData, 0, copyLen)
                if (!writeBlockWithRetry(mifare, blockIndex, blockData)) {
                    skippedBlocks.add(blockIndex)
                    continue
                }
                usedBlocks.add(blockIndex)
                writeOffset += copyLen
            }
            if (writeOffset < tlv.size) {
                val remain = tlv.size - writeOffset
                return "NDEF写入失败：M1映射可写区块不足，剩余 $remain 字节未写入"
            }

            val readBack = ByteArray(tlv.size)
            var readOffset = 0
            for (blockIndex in usedBlocks) {
                if (readOffset >= tlv.size) break
                val block = readBlockWithRetry(mifare, blockIndex)
                    ?: return "NDEF写入失败：M1映射校检读取区块 $blockIndex 失败"
                val copyLen = minOf(16, tlv.size - readOffset)
                System.arraycopy(block, 0, readBack, readOffset, copyLen)
                readOffset += copyLen
            }
            if (!readBack.contentEquals(tlv)) {
                return "NDEF写入失败：M1映射写入后校检不一致"
            }
            if (skippedBlocks.isEmpty()) {
                "NDEF写入成功：已通过 MIFARE Classic 映射写入并校检（FF秘钥）"
            } else {
                "NDEF写入成功：已通过 MIFARE Classic 映射写入并校检（FF秘钥，跳过不可写区块 ${skippedBlocks.joinToString(",")}）"
            }
        } catch (e: Exception) {
            "NDEF写入失败：M1映射异常 ${e.message.orEmpty()}"
        }
    }

    private fun buildNdefTlv(payload: ByteArray): ByteArray {
        if (payload.size <= 0xFE) {
            return ByteArray(payload.size + 3).apply {
                this[0] = 0x03.toByte()
                this[1] = payload.size.toByte()
                System.arraycopy(payload, 0, this, 2, payload.size)
                this[lastIndex] = 0xFE.toByte()
            }
        }
        return ByteArray(payload.size + 5).apply {
            this[0] = 0x03.toByte()
            this[1] = 0xFF.toByte()
            this[2] = ((payload.size shr 8) and 0xFF).toByte()
            this[3] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, this, 4, payload.size)
            this[lastIndex] = 0xFE.toByte()
        }
    }

    private fun buildNdefMessage(request: NdefWriteRequest): NdefMessage {
        val record = when (request.type) {
            NdefWriteType.TEXT -> {
                NdefRecord.createTextRecord("zh", request.textContent.trim())
            }
            NdefWriteType.URL -> {
                val raw = request.url.trim()
                val normalized = if (
                    raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true)
                ) raw else "https://$raw"
                NdefRecord.createUri(Uri.parse(normalized))
            }
            NdefWriteType.PHONE -> {
                val raw = request.phone.trim()
                val normalized = if (raw.startsWith("tel:", ignoreCase = true)) raw else "tel:$raw"
                NdefRecord.createUri(Uri.parse(normalized))
            }
            NdefWriteType.WIFI -> {
                val security = request.wifiSecurity.trim().uppercase(Locale.US).ifBlank { "WPA" }
                val ssid = escapeWifiField(request.wifiSsid.trim())
                val password = escapeWifiField(request.wifiPassword.trim())
                val wifiText = buildString {
                    append("WIFI:")
                    append("T:").append(security).append(';')
                    append("S:").append(ssid).append(';')
                    if (password.isNotEmpty()) {
                        append("P:").append(password).append(';')
                    }
                    append(';')
                }
                NdefRecord.createTextRecord("en", wifiText)
            }
        }
        return NdefMessage(arrayOf(record))
    }

    private fun escapeWifiField(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
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
            val targetSectorCount = minOf(WRITE_SECTOR_COUNT, mifare.sectorCount)
            for (sector in 0 until targetSectorCount) {
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
            val targetSectorCount = minOf(WRITE_SECTOR_COUNT, mifare.sectorCount)
            for (sector in 0 until targetSectorCount) {
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
            WriteResumePoint(targetSectorCount, 0)
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

        val targetSectorCount = minOf(WRITE_SECTOR_COUNT, mifare.sectorCount)
        for (sector in 0 until targetSectorCount) {
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

    // ── 创想三维 Creality RFID ──────────────────────────────────────────────────

    private fun deriveCrealityKeyA(uid: ByteArray): ByteArray {
        val input = ByteArray(16) { uid[it % 4] }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(CREALITY_KEY_DERIVE, "AES"))
        return cipher.doFinal(input).copyOfRange(0, 6)
    }

    private fun encryptCrealityData48(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(CREALITY_KEY_DATA, "AES"))
        return cipher.doFinal(data)
    }

    private fun decryptCrealityData48(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(CREALITY_KEY_DATA, "AES"))
        return cipher.doFinal(data)
    }

    private fun buildCrealityTagBytes(materialId: String, colorHex: String, weight: String, serial: String = "000001"): ByteArray {
        val lengthCode = CREALITY_WEIGHT_TO_LENGTH[weight] ?: "0330"
        val raw = "AB124" +
            "0276" +
            "A2" +
            "1" + materialId.uppercase(Locale.US).padStart(5, '0') +
            "0" + colorHex.uppercase(Locale.US).trimStart('#').padStart(6, '0') +
            lengthCode +
            serial.padStart(6, '0') +
            "00000000000000"
        return raw.padEnd(48, ' ').toByteArray(Charsets.UTF_8)
    }

    private fun parseCrealityTagString(raw: String, uidHex: String = ""): CrealityTagData? {
        if (raw.length < 48) return null
        val serial = raw.substring(28, 34).trim().trimStart('0').ifEmpty { "" }
        val vendorId = raw.substring(5, 9).trim()
        // 尾部14字节可能含生产日期（写入端填充全零时不显示）
        val tailRaw = raw.substring(34).trim().trimEnd('\u0000', ' ')
        val mfDate = if (tailRaw.all { it == '0' }) "" else tailRaw
        return CrealityTagData(
            materialId = raw.substring(12, 17).trim(),
            colorHex = raw.substring(18, 24).trim(),
            weight = CREALITY_LENGTH_TO_WEIGHT[raw.substring(24, 28)] ?: "未知",
            serial = serial,
            vendorId = vendorId,
            batch = "",
            lengthCode = raw.substring(24, 28),
            rawPlaintext = raw,
            uidHex = uidHex,
            mfDate = mfDate
        )
    }

    private fun readCrealityTag(tag: Tag): CrealityTagData? {
        val mifare = MifareClassic.get(tag) ?: return null
        return try {
            if (!mifare.isConnected) mifare.connect()
            Thread.sleep(300)
            val uid = tag.id ?: return null
            val derivedKey = deriveCrealityKeyA(uid)
            val ffKey = ByteArray(6) { 0xFF.toByte() }
            val authenticated = authenticateSectorWithRetry(
                mifare = mifare, sectorIndex = 1,
                keysA = listOf(derivedKey, ffKey),
                keysB = listOf(derivedKey, ffKey)
            )
            if (!authenticated) return null
            val b4 = readBlockWithRetry(mifare, 4) ?: return null
            val b5 = readBlockWithRetry(mifare, 5) ?: return null
            val b6 = readBlockWithRetry(mifare, 6) ?: return null
            val decrypted = decryptCrealityData48(b4 + b5 + b6)
            val uidHex = uid.joinToString("") { "%02X".format(it) }
            parseCrealityTagString(String(decrypted, Charsets.UTF_8), uidHex)
        } catch (e: Exception) {
            logDebug("Creality read failed: ${e.message}")
            null
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
    }

    private fun writeCrealityTag(tag: Tag, pending: CrealityWritePending): String {
        val mifare = MifareClassic.get(tag) ?: return "写入失败：不支持 MIFARE Classic"
        return try {
            if (!mifare.isConnected) mifare.connect()
            Thread.sleep(300)
            val uid = tag.id ?: return "写入失败：无法读取 UID"
            val derivedKey = deriveCrealityKeyA(uid)
            val ffKey = ByteArray(6) { 0xFF.toByte() }
            val authenticated = authenticateSectorWithRetry(
                mifare = mifare, sectorIndex = 1,
                keysA = listOf(derivedKey, ffKey),
                keysB = listOf(derivedKey, ffKey)
            )
            if (!authenticated) return "写入失败：扇区1 认证失败"
            val plaintext = buildCrealityTagBytes(pending.materialId, pending.colorHex, pending.weight)
            val encrypted = encryptCrealityData48(plaintext)
            val b4ok = writeBlockWithRetry(mifare, 4, encrypted.copyOfRange(0, 16))
            val b5ok = writeBlockWithRetry(mifare, 5, encrypted.copyOfRange(16, 32))
            val b6ok = writeBlockWithRetry(mifare, 6, encrypted.copyOfRange(32, 48))
            if (!b4ok || !b5ok || !b6ok) return "写入失败：数据块写入异常"
            // Update trailer: KeyA=derived, access bits=FF078069, KeyB=FF×6
            val trailer = derivedKey +
                byteArrayOf(0xFF.toByte(), 0x07.toByte(), 0x80.toByte(), 0x69.toByte()) +
                ffKey
            writeBlockWithRetry(mifare, 7, trailer)
            "写入成功"
        } catch (e: Exception) {
            "写入失败：${e.message.orEmpty()}"
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
    }

    // ── End Creality ─────────────────────────────────────────────────────────

    // ── 自动品牌检测 ──────────────────────────────────────────────────────────

    /**
     * 通过扇区0秘钥认证判断卡片品牌：
     * 1. 拓竹：UID派生KeyA/B（deriveWriteKeys）
     * 2. 创想：AES派生KeyA（deriveCrealityKeyA），KeyB=FF
     * 3. 快造：HKDF派生KeyA/B（deriveSnapmakerKeys）
     * 4. 空白/未知：FF秘钥
     * 返回检测到的 ReaderBrand，或 null 表示无法判断（FF / 未知）
     */
    private fun detectBrandBySector0(tag: Tag): ReaderBrand? {
        val mifare = MifareClassic.get(tag) ?: return null
        return try {
            mifare.connect()
            Thread.sleep(200)
            val uid = tag.id ?: return null
            val ffKey = ByteArray(6) { 0xFF.toByte() }

            val bambuKeysA = try { deriveWriteKeys(uid, WRITE_INFO_A) } catch (_: Exception) { emptyList() }
            val bambuKeysB = try { deriveWriteKeys(uid, WRITE_INFO_B) } catch (_: Exception) { emptyList() }
            val crealityKey = try { deriveCrealityKeyA(uid) } catch (_: Exception) { null }
            val (snapKeysA, snapKeysB) = try { deriveSnapmakerKeys(uid) } catch (_: Exception) { Pair(emptyList(), emptyList()) }

            when {
                bambuKeysA.isNotEmpty() && bambuKeysB.isNotEmpty() &&
                authenticateSectorWithRetry(mifare, 0, listOf(bambuKeysA[0]), listOf(bambuKeysB[0])) ->
                    ReaderBrand.BAMBU

                crealityKey != null &&
                authenticateSectorWithRetry(mifare, 0, listOf(crealityKey), listOf(ffKey)) ->
                    ReaderBrand.CREALITY

                snapKeysA.isNotEmpty() && snapKeysB.isNotEmpty() &&
                authenticateSectorWithRetry(mifare, 0, listOf(snapKeysA[0]), listOf(snapKeysB[0])) ->
                    ReaderBrand.SNAPMAKER

                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
    }

    // ── 快造 (Snapmaker) RFID ─────────────────────────────────────────────────

    /** HKDF-SHA256 单块派生：Extract (HMAC(salt, ikm)) 然后 Expand 取 length 字节 */
    private fun snapmakerHkdfDerive(ikm: ByteArray, salt: ByteArray, context: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(context)
        mac.update(0x01.toByte())
        return mac.doFinal().copyOf(length)
    }

    /** 为 16 个扇区分别派生 KeyA / KeyB */
    private fun deriveSnapmakerKeys(uid: ByteArray): Pair<List<ByteArray>, List<ByteArray>> {
        val keysA = (0 until 16).map { i ->
            snapmakerHkdfDerive(uid, SNAPMAKER_SALT_A, "key_a_$i".toByteArray(Charsets.US_ASCII), 6)
        }
        val keysB = (0 until 16).map { i ->
            snapmakerHkdfDerive(uid, SNAPMAKER_SALT_B, "key_b_$i".toByteArray(Charsets.US_ASCII), 6)
        }
        return Pair(keysA, keysB)
    }

    /** 读取 Snapmaker 标签，返回解析结果；认证或解析失败返回 null */
    private fun readSnapmakerTag(tag: Tag): SnapmakerTagData? {
        val mifare = MifareClassic.get(tag) ?: return null
        return try {
            if (!mifare.isConnected) mifare.connect()
            Thread.sleep(300)
            val uid = tag.id ?: return null
            val (keysA, keysB) = deriveSnapmakerKeys(uid)

            // 组装 1024 字节缓冲区：16 扇区 × 4 块 × 16 字节（含 trailer 占位）
            val dataBuf = ByteArray(1024)
            var anySuccess = false
            for (sector in 0 until 16) {
                val authenticated = authenticateSectorWithRetry(
                    mifare = mifare,
                    sectorIndex = sector,
                    keysA = listOf(keysA[sector]),
                    keysB = listOf(keysB[sector])
                )
                if (!authenticated) continue
                val firstBlock = mifare.sectorToBlock(sector)
                for (blockInSector in 0 until 4) {
                    val block = readBlockWithRetry(mifare, firstBlock + blockInSector)
                    if (block != null) {
                        block.copyInto(dataBuf, sector * 64 + blockInSector * 16)
                        if (blockInSector < 3) anySuccess = true
                    }
                }
            }
            if (!anySuccess) return null

            // 捕获原始块数据和密钥，供自动共享上传使用（与拓竹格式一致）
            val uidHexSnap = uid.joinToString("") { "%02X".format(it) }
            val rawBlocksSnap: List<ByteArray?> = (0 until 64).map { blockIndex ->
                val sector = blockIndex / 4
                val blockInSector = blockIndex % 4
                if (blockInSector == 3) {
                    // 重建 trailer 块：KeyA(6) + 访问字节(4) + KeyB(6)
                    val trailerOffset = sector * 64 + 48
                    val accessBytes = dataBuf.copyOfRange(trailerOffset + 6, trailerOffset + 10)
                    keysA[sector] + accessBytes + keysB[sector]
                } else {
                    val offset = sector * 64 + blockInSector * 16
                    dataBuf.copyOfRange(offset, offset + 16)
                }
            }
            latestSnapmakerRawData = RawTagReadData(
                uidHex   = uidHexSnap,
                keyA0Hex = keysA[0].joinToString("") { "%02x".format(it) },
                keyB0Hex = keysB[0].joinToString("") { "%02x".format(it) },
                keyA1Hex = keysA[1].joinToString("") { "%02x".format(it) },
                keyB1Hex = keysB[1].joinToString("") { "%02x".format(it) },
                sectorKeys = (0 until 16).map { i ->
                    Pair(keysA[i] as ByteArray?, keysB[i] as ByteArray?)
                },
                rawBlocks = rawBlocksSnap,
                errors    = emptyList()
            )

            parseSnapmakerData(dataBuf, uid)
        } catch (e: Exception) {
            logDebug("Snapmaker read failed: ${e.message}")
            null
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
    }

    /** 将 dataBuf 解析为 SnapmakerTagData（偏移量来自 M1 协议规范） */
    private fun parseSnapmakerData(dataBuf: ByteArray, uid: ByteArray): SnapmakerTagData? {
        if (dataBuf.size != 1024) return null

        fun le16(offset: Int) = ((dataBuf[offset + 1].toInt() and 0xFF) shl 8) or (dataBuf[offset].toInt() and 0xFF)
        fun readRgb(offset: Int) = ((dataBuf[offset].toInt() and 0xFF) shl 16) or
                ((dataBuf[offset + 1].toInt() and 0xFF) shl 8) or
                (dataBuf[offset + 2].toInt() and 0xFF)

        // RSA 密钥版本: sector2 block2 bytes 8-9 → offset 168
        val rsaKeyVer = le16(168)

        // RSA 签名验证（可选，不影响数据展示）
        val isOfficial = tryVerifySnapmakerSignature(dataBuf, rsaKeyVer)

        val vendor = String(dataBuf, 16, 16, Charsets.US_ASCII).trimEnd('\u0000')        // sector0 block1
        val manufacturer = String(dataBuf, 32, 16, Charsets.US_ASCII).trimEnd('\u0000') // sector0 block2

        val mainTypeCode = le16(66)   // sector1 block0 bytes2-3
        val subTypeCode  = le16(68)   // sector1 block0 bytes4-5
        val colorNums    = dataBuf[72].toInt() and 0xFF                                 // sector1 block0 byte8

        val rgb1 = readRgb(80); val rgb2 = readRgb(83); val rgb3 = readRgb(86)
        val rgb4 = readRgb(89); val rgb5 = readRgb(92)

        val diameter    = le16(128)  // sector2 block0 bytes0-1
        val weight      = le16(130)  // sector2 block0 bytes2-3
        val dryingTemp  = le16(144)  // sector2 block1 bytes0-1
        val dryingTime  = le16(146)  // sector2 block1 bytes2-3
        val hotendMax   = le16(148)  // sector2 block1 bytes4-5
        val hotendMin   = le16(150)  // sector2 block1 bytes6-7
        val bedTemp     = le16(154)  // sector2 block1 bytes10-11
        val mfDate = String(dataBuf, 160, 8, Charsets.US_ASCII).trimEnd('\u0000')       // sector2 block2 bytes0-7

        return SnapmakerTagData(
            vendor       = vendor.ifBlank { "-" },
            manufacturer = manufacturer.ifBlank { "-" },
            mainType     = SNAPMAKER_MAIN_TYPE_MAP[mainTypeCode] ?: "Unknown($mainTypeCode)",
            subType      = SNAPMAKER_SUB_TYPE_MAP[subTypeCode]  ?: "Unknown($subTypeCode)",
            colorCount   = colorNums,
            rgb1 = rgb1, rgb2 = rgb2, rgb3 = rgb3, rgb4 = rgb4, rgb5 = rgb5,
            diameter     = diameter,
            weight       = weight,
            dryingTemp   = dryingTemp,
            dryingTime   = dryingTime,
            hotendMaxTemp = hotendMax,
            hotendMinTemp = hotendMin,
            bedTemp      = bedTemp,
            mfDate       = mfDate.ifBlank { "-" },
            isOfficial   = isOfficial,
            uidHex       = uid.joinToString("") { "%02X".format(it) },
            rsaKeyVersion = rsaKeyVer
        )
    }

    /** 将 PKCS#1 PEM 转换为 Java PublicKey（动态构造 SubjectPublicKeyInfo 包装） */
    private fun loadSnapmakerRsaPublicKey(pem: String): java.security.PublicKey? {
        return try {
            val base64 = pem
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replace("\\s+".toRegex(), "")
            val pkcs1 = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)

            fun derLen(n: Int): ByteArray = when {
                n < 128 -> byteArrayOf(n.toByte())
                n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
                else    -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
            }
            val algId = byteArrayOf(
                0x30, 0x0d,
                0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
            )
            val bsContent = byteArrayOf(0x00) + pkcs1
            val bitStr    = byteArrayOf(0x03) + derLen(bsContent.size) + bsContent
            val seqBody   = algId + bitStr
            val spki      = byteArrayOf(0x30) + derLen(seqBody.size) + seqBody

            java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        } catch (e: Exception) {
            logDebug("Snapmaker: RSA key load failed: ${e.message}")
            null
        }
    }

    /** 验证 Snapmaker 标签的 RSA-PKCS1v15-SHA256 签名；验证失败不影响解析 */
    private fun tryVerifySnapmakerSignature(dataBuf: ByteArray, keyVersion: Int): Boolean {
        if (keyVersion < 0 || keyVersion >= SNAPMAKER_RSA_KEYS.size) return false
        val pubKey = loadSnapmakerRsaPublicKey(SNAPMAKER_RSA_KEYS[keyVersion]) ?: return false
        return try {
            // 从 sector10-15 各取前 48 字节（3个数据块）拼成签名，取前 256 字节
            val sigCollected = ByteArray(288)
            for (i in 0 until 6) {
                dataBuf.copyInto(sigCollected, i * 48, (10 + i) * 64, (10 + i) * 64 + 48)
            }
            val sig = java.security.Signature.getInstance("SHA256withRSA")
            sig.initVerify(pubKey)
            sig.update(dataBuf, 0, 640)
            sig.verify(sigCollected.copyOf(256))
        } catch (e: Exception) {
            logDebug("Snapmaker: signature check failed: ${e.message}")
            false
        }
    }

    // ── End Snapmaker ─────────────────────────────────────────────────────────

    private fun authenticateSectorWithRetry(
        mifare: MifareClassic,
        sectorIndex: Int,
        keysA: List<ByteArray?>,
        keysB: List<ByteArray?>
    ): Boolean {
        for (attempt in 0..RW_AUTH_RETRY_COUNT) {
            try {
                if (!ensureMifareClassicConnected(mifare)) {
                    continue
                }
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
                reconnectMifareClassic(mifare)
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
                if (!ensureMifareClassicConnected(mifare)) {
                    continue
                }
                mifare.writeBlock(blockIndex, data)
                return true
            } catch (_: Exception) {
                reconnectMifareClassic(mifare)
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
                if (!ensureMifareClassicConnected(mifare)) {
                    continue
                }
                val raw = mifare.readBlock(blockIndex)
                return when {
                    raw.size == 16 -> raw
                    raw.size > 16 -> raw.copyOf(16)
                    else -> null
                }
            } catch (_: Exception) {
                reconnectMifareClassic(mifare)
            }
        }
        return null
    }

    private fun ensureMifareClassicConnected(mifare: MifareClassic): Boolean {
        return if (mifare.isConnected) {
            true
        } else {
            reconnectMifareClassic(mifare)
        }
    }

    private fun reconnectMifareClassic(mifare: MifareClassic): Boolean {
        return try {
            try {
                mifare.close()
            } catch (_: Exception) {
            }
            Thread.sleep(RW_RECONNECT_DELAY_MS)
            mifare.connect()
            Thread.sleep(RW_RECONNECT_DELAY_MS)
            true
        } catch (_: Exception) {
            false
        }
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
        // 开启自动共享时强制读取全部扇区，确保上传数据完整。
        val rawResult = NfcTagReader.readRaw(
            tag = tag,
            readAllSectors = readAllSectors || autoShareTag,
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

        val extraFields = if (processed.trayUidHex.isNotBlank()) {
            val db = filamentDbHelper?.readableDatabase
            db?.let { filamentDbHelper?.getTrayExtraFields(it, processed.trayUidHex) } ?: Pair("", "")
        } else Pair("", "")

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
            originalMaterial = extraFields.first,
            notes = extraFields.second,
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
                val detailedType = entry.filaDetailedType
                if (detailedType.isNotBlank()) {
                    values.put("fila_detailed_type", detailedType)
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

internal fun syncCrealityMaterialDatabase(context: Context, dbHelper: FilamentDbHelper) {
    val externalDir = context.getExternalFilesDir(null) ?: return
    val externalFile = File(externalDir, CREALITY_MATERIAL_FILE)
    if (!externalFile.exists()) {
        try {
            context.assets.open(CREALITY_MATERIAL_FILE).use { i ->
                externalFile.outputStream().use { o -> i.copyTo(o) }
            }
        } catch (_: IOException) { return }
    }
    if (!externalFile.exists()) return
    val jsonText = try { externalFile.readText(Charsets.UTF_8) } catch (_: IOException) { return }
    val db = dbHelper.writableDatabase
    val fileHash = jsonText.hashCode().toString()
    val storedHash = dbHelper.getMetaValue(db, "creality_material_hash")
    if (storedHash == fileHash) return
    try {
        val materials = JSONObject(jsonText).optJSONArray("materials") ?: return
        db.beginTransaction()
        try {
            db.delete(CREALITY_MATERIAL_TABLE, null, null)
            val values = ContentValues()
            for (i in 0 until materials.length()) {
                val m = materials.getJSONObject(i)
                values.clear()
                values.put("material_id", m.optString("id"))
                values.put("brand", m.optString("brand"))
                values.put("material_type", m.optString("meterialType"))
                values.put("name", m.optString("name"))
                values.put("min_temp", m.optInt("minTemp"))
                values.put("max_temp", m.optInt("maxTemp"))
                values.put("diameter", m.optString("diameter"))
                db.insertWithOnConflict(CREALITY_MATERIAL_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            dbHelper.setMetaValue(db, "creality_material_hash", fileHash)
            db.setTransactionSuccessful()
            logDebug("创想三维耗材数据写入完成: ${materials.length()} 条")
        } finally {
            db.endTransaction()
        }
    } catch (e: Exception) {
        logDebug("同步创想三维耗材数据失败: ${e.message}")
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
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $CREALITY_MATERIAL_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                material_id TEXT NOT NULL UNIQUE,
                brand TEXT,
                material_type TEXT,
                name TEXT,
                min_temp INTEGER,
                max_temp INTEGER,
                diameter TEXT
            )
        """.trimIndent())
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
                original_material TEXT,
                notes TEXT,
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
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $SHARE_TAGS_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_uid TEXT UNIQUE NOT NULL,
                tray_uid TEXT,
                material_type TEXT,
                color_uid TEXT,
                color_name TEXT,
                color_type TEXT,
                color_values TEXT,
                raw_data TEXT,
                copy_count INTEGER NOT NULL DEFAULT 0,
                verified INTEGER NOT NULL DEFAULT 0,
                production_date TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $SNAPMAKER_SHARE_TAGS_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT UNIQUE NOT NULL,
                vendor TEXT,
                manufacturer TEXT,
                main_type INTEGER NOT NULL DEFAULT 0,
                diameter INTEGER NOT NULL DEFAULT 0,
                weight INTEGER NOT NULL DEFAULT 0,
                rgb1 INTEGER NOT NULL DEFAULT 0,
                mf_date TEXT,
                raw_data TEXT,
                copy_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $ANOMALY_UIDS_TABLE (
                uid TEXT PRIMARY KEY NOT NULL,
                report_count INTEGER NOT NULL DEFAULT 1,
                synced_at INTEGER NOT NULL
            )
            """.trimIndent()
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
        if (oldVersion < 14) {
            // 为filament_inventory表添加原始耗材和备注字段
            addTrayColumn(db, "original_material", "TEXT")
            addTrayColumn(db, "notes", "TEXT")
        }
        if (oldVersion < 15) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $SHARE_TAGS_TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_uid TEXT UNIQUE NOT NULL,
                    tray_uid TEXT,
                    material_type TEXT,
                    color_uid TEXT,
                    color_name TEXT,
                    color_type TEXT,
                    color_values TEXT,
                    raw_data TEXT,
                    copy_count INTEGER NOT NULL DEFAULT 0,
                    verified INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 16) {
            try {
                db.execSQL("ALTER TABLE $SHARE_TAGS_TABLE ADD COLUMN raw_data TEXT")
            } catch (_: Exception) { }
        }
        if (oldVersion < 17) {
            try {
                db.execSQL("ALTER TABLE $SHARE_TAGS_TABLE ADD COLUMN production_date TEXT")
            } catch (_: Exception) { }
        }
        if (oldVersion < 18) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $CREALITY_MATERIAL_TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    material_id TEXT NOT NULL UNIQUE,
                    brand TEXT,
                    material_type TEXT,
                    name TEXT,
                    min_temp INTEGER,
                    max_temp INTEGER,
                    diameter TEXT
                )
            """.trimIndent())
        }
        if (oldVersion < 19) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $SNAPMAKER_SHARE_TAGS_TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uid TEXT UNIQUE NOT NULL,
                    vendor TEXT,
                    manufacturer TEXT,
                    main_type INTEGER NOT NULL DEFAULT 0,
                    diameter INTEGER NOT NULL DEFAULT 0,
                    weight INTEGER NOT NULL DEFAULT 0,
                    rgb1 INTEGER NOT NULL DEFAULT 0,
                    mf_date TEXT,
                    raw_data TEXT,
                    copy_count INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
        if (oldVersion < 20) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $ANOMALY_UIDS_TABLE (
                    uid TEXT PRIMARY KEY NOT NULL,
                    report_count INTEGER NOT NULL DEFAULT 1,
                    synced_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
        if (oldVersion < 21) {
            try {
                db.execSQL("ALTER TABLE $ANOMALY_UIDS_TABLE ADD COLUMN report_count INTEGER NOT NULL DEFAULT 1")
            } catch (_: Exception) {}
        }
    }

    private fun addTrayColumn(db: SQLiteDatabase, column: String, type: String) {
        try {
            db.execSQL("ALTER TABLE \"$TRAY_UID_TABLE\" ADD COLUMN $column $type")
        } catch (_: Exception) {
            // Ignore duplicate column errors.
        }
    }

    // --- share_tags 相关方法 ---

    fun insertShareTag(
        db: SQLiteDatabase,
        fileUid: String,
        trayUid: String?,
        materialType: String?,
        colorUid: String?,
        colorName: String?,
        colorType: String?,
        colorValues: String?,
        rawData: String? = null,
        productionDate: String? = null
    ): Long {
        val values = ContentValues()
        values.put("file_uid", fileUid)
        if (!trayUid.isNullOrBlank()) values.put("tray_uid", trayUid)
        if (!materialType.isNullOrBlank()) values.put("material_type", materialType)
        if (!colorUid.isNullOrBlank()) values.put("color_uid", colorUid)
        if (!colorName.isNullOrBlank()) values.put("color_name", colorName)
        if (!colorType.isNullOrBlank()) values.put("color_type", colorType)
        if (!colorValues.isNullOrBlank()) values.put("color_values", colorValues)
        if (!rawData.isNullOrBlank()) values.put("raw_data", rawData)
        if (!productionDate.isNullOrBlank()) values.put("production_date", productionDate)
        return db.insertWithOnConflict(SHARE_TAGS_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateShareTagProductionDate(db: SQLiteDatabase, fileUid: String, productionDate: String) {
        val values = ContentValues()
        values.put("production_date", productionDate)
        db.update(SHARE_TAGS_TABLE, values, "file_uid = ?", arrayOf(fileUid))
    }

    fun getShareTagMetaMap(db: SQLiteDatabase): Map<String, ShareTagDbMeta> {
        val result = mutableMapOf<String, ShareTagDbMeta>()
        val cursor = db.query(
            SHARE_TAGS_TABLE,
            arrayOf("id", "file_uid", "copy_count", "verified"),
            null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val fileUid = it.getString(1) ?: continue
                val copyCount = it.getInt(2)
                val verified = it.getInt(3) != 0
                result[fileUid.uppercase()] = ShareTagDbMeta(id, copyCount, verified)
            }
        }
        return result
    }

    fun getAllShareTagRows(db: SQLiteDatabase): List<ShareTagDbRow> {
        val result = mutableListOf<ShareTagDbRow>()
        val cursor = db.query(
            SHARE_TAGS_TABLE,
            arrayOf("id", "file_uid", "tray_uid", "material_type", "color_uid", "color_name", "color_type", "color_values", "raw_data", "copy_count", "verified", "production_date"),
            null, null, null, null,
            "material_type ASC, color_uid ASC, file_uid ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(ShareTagDbRow(
                    id = it.getLong(0),
                    fileUid = it.getString(1) ?: "",
                    trayUid = it.getString(2),
                    materialType = it.getString(3),
                    colorUid = it.getString(4),
                    colorName = it.getString(5),
                    colorType = it.getString(6),
                    colorValues = it.getString(7),
                    rawData = it.getString(8),
                    copyCount = it.getInt(9),
                    verified = it.getInt(10) != 0,
                    productionDate = it.getString(11)
                ))
            }
        }
        return result
    }

    fun updateShareTagRawData(db: SQLiteDatabase, fileUid: String, rawData: String) {
        val values = ContentValues()
        values.put("raw_data", rawData)
        db.update(SHARE_TAGS_TABLE, values, "file_uid = ?", arrayOf(fileUid))
    }

    fun getExistingShareTrayUids(db: SQLiteDatabase): Set<String> {
        val result = mutableSetOf<String>()
        val cursor = db.query(
            SHARE_TAGS_TABLE,
            arrayOf("tray_uid"),
            "tray_uid IS NOT NULL AND tray_uid != ''",
            null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val uid = it.getString(0)
                if (!uid.isNullOrBlank()) result.add(uid.uppercase())
            }
        }
        return result
    }

    fun incrementShareTagCopyCount(db: SQLiteDatabase, id: Long) {
        db.execSQL("UPDATE $SHARE_TAGS_TABLE SET copy_count = copy_count + 1 WHERE id = ?", arrayOf(id))
    }

    fun setShareTagVerified(db: SQLiteDatabase, id: Long, verified: Boolean) {
        val values = ContentValues()
        values.put("verified", if (verified) 1 else 0)
        db.update(SHARE_TAGS_TABLE, values, "id = ?", arrayOf(id.toString()))
    }

    fun resetShareTagByTrayUid(db: SQLiteDatabase, trayUid: String) {
        if (trayUid.isBlank()) return
        val values = ContentValues()
        values.put("copy_count", 0)
        values.put("verified", 0)
        db.update(SHARE_TAGS_TABLE, values, "tray_uid = ?", arrayOf(trayUid))
    }

    fun deleteShareTagByFileUid(db: SQLiteDatabase, fileUid: String) {
        db.delete(SHARE_TAGS_TABLE, "file_uid = ?", arrayOf(fileUid))
    }

    fun clearShareTagsTable(db: SQLiteDatabase): Int {
        return db.delete(SHARE_TAGS_TABLE, "1", null)
    }

    fun clearSnapmakerShareTagsTable(db: SQLiteDatabase): Int {
        return db.delete(SNAPMAKER_SHARE_TAGS_TABLE, "1", null)
    }

    // --- snapmaker_share_tags 相关方法 ---

    data class SnapmakerShareTagRow(
        val id: Long,
        val uid: String,
        val vendor: String?,
        val manufacturer: String?,
        val mainType: Int,
        val diameter: Int,
        val weight: Int,
        val rgb1: Int,
        val mfDate: String?,
        val rawData: String?,
        val copyCount: Int
    )

    fun insertSnapmakerShareTag(
        db: SQLiteDatabase,
        uid: String,
        vendor: String?,
        manufacturer: String?,
        mainType: Int,
        diameter: Int,
        weight: Int,
        rgb1: Int,
        mfDate: String?,
        rawData: String?
    ): Long {
        val values = ContentValues().apply {
            put("uid", uid)
            if (!vendor.isNullOrBlank()) put("vendor", vendor)
            if (!manufacturer.isNullOrBlank()) put("manufacturer", manufacturer)
            put("main_type", mainType)
            put("diameter", diameter)
            put("weight", weight)
            put("rgb1", rgb1)
            if (!mfDate.isNullOrBlank()) put("mf_date", mfDate)
            if (!rawData.isNullOrBlank()) put("raw_data", rawData)
        }
        return db.insertWithOnConflict(SNAPMAKER_SHARE_TAGS_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getAllSnapmakerShareTagRows(db: SQLiteDatabase): List<SnapmakerShareTagRow> {
        val result = mutableListOf<SnapmakerShareTagRow>()
        val cursor = db.query(
            SNAPMAKER_SHARE_TAGS_TABLE,
            arrayOf("id", "uid", "vendor", "manufacturer", "main_type", "diameter", "weight", "rgb1", "mf_date", "raw_data", "copy_count"),
            null, null, null, null,
            "vendor ASC, uid ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(SnapmakerShareTagRow(
                    id = it.getLong(0),
                    uid = it.getString(1) ?: "",
                    vendor = it.getString(2),
                    manufacturer = it.getString(3),
                    mainType = it.getInt(4),
                    diameter = it.getInt(5),
                    weight = it.getInt(6),
                    rgb1 = it.getInt(7),
                    mfDate = it.getString(8),
                    rawData = it.getString(9),
                    copyCount = it.getInt(10)
                ))
            }
        }
        return result
    }

    fun getAllSnapmakerShareTagUids(db: SQLiteDatabase): List<String> {
        val result = mutableListOf<String>()
        val cursor = db.query(SNAPMAKER_SHARE_TAGS_TABLE, arrayOf("uid"), null, null, null, null, null)
        cursor.use { while (it.moveToNext()) { result.add(it.getString(0) ?: "") } }
        return result
    }

    fun incrementSnapmakerShareTagCopyCount(db: SQLiteDatabase, id: Long) {
        db.execSQL("UPDATE $SNAPMAKER_SHARE_TAGS_TABLE SET copy_count = copy_count + 1 WHERE id = ?", arrayOf(id))
    }

    fun deleteSnapmakerShareTagByUid(db: SQLiteDatabase, uid: String) {
        db.delete(SNAPMAKER_SHARE_TAGS_TABLE, "uid = ?", arrayOf(uid))
    }

    // --- anomaly_uids 相关方法 ---

    fun saveAnomalyUids(db: SQLiteDatabase, uids: Map<String, Int>) {
        db.beginTransaction()
        try {
            db.delete(ANOMALY_UIDS_TABLE, null, null)
            val now = System.currentTimeMillis()
            for ((uid, count) in uids) {
                val cv = ContentValues()
                cv.put("uid", uid.uppercase().trim())
                cv.put("report_count", count)
                cv.put("synced_at", now)
                db.insertWithOnConflict(ANOMALY_UIDS_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAnomalyUids(db: SQLiteDatabase): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val cursor = db.query(ANOMALY_UIDS_TABLE, arrayOf("uid", "report_count"), null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                val uid = it.getString(0)
                val count = it.getInt(1)
                if (!uid.isNullOrBlank()) result[uid] = count
            }
        }
        return result
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

    // ── Creality material queries ──────────────────────────────────────────────

    fun getCrealityBrands(db: SQLiteDatabase): List<String> {
        val result = mutableListOf<String>()
        val cursor = db.query(true, CREALITY_MATERIAL_TABLE, arrayOf("brand"),
            null, null, "brand", null, "brand ASC", null)
        cursor.use { c -> while (c.moveToNext()) { c.getString(0)?.let { result.add(it) } } }
        return result
    }

    fun getCrealityTypes(db: SQLiteDatabase, brand: String): List<String> {
        val result = mutableListOf<String>()
        val cursor = db.query(true, CREALITY_MATERIAL_TABLE, arrayOf("material_type"),
            "brand = ?", arrayOf(brand), "material_type", null, "material_type ASC", null)
        cursor.use { c -> while (c.moveToNext()) { c.getString(0)?.let { result.add(it) } } }
        return result
    }

    fun getCrealityMaterials(db: SQLiteDatabase, brand: String, type: String): List<CrealityMaterial> {
        val result = mutableListOf<CrealityMaterial>()
        val cursor = db.query(CREALITY_MATERIAL_TABLE,
            arrayOf("material_id", "brand", "material_type", "name", "min_temp", "max_temp", "diameter"),
            "brand = ? AND material_type = ?", arrayOf(brand, type), null, null, "name ASC")
        cursor.use { c ->
            while (c.moveToNext()) {
                val mid = c.getString(0) ?: return@use
                result.add(CrealityMaterial(
                    materialId = mid,
                    brand = c.getString(1).orEmpty(),
                    materialType = c.getString(2).orEmpty(),
                    name = c.getString(3).orEmpty(),
                    minTemp = c.getInt(4),
                    maxTemp = c.getInt(5),
                    diameter = c.getString(6).orEmpty()
                ))
            }
        }
        return result
    }

    fun getCrealityMaterialById(db: SQLiteDatabase, materialId: String): CrealityMaterial? {
        val cursor = db.query(CREALITY_MATERIAL_TABLE,
            arrayOf("material_id", "brand", "material_type", "name", "min_temp", "max_temp", "diameter"),
            "material_id = ?", arrayOf(materialId), null, null, null)
        return cursor.use { c ->
            if (c.moveToFirst()) CrealityMaterial(
                materialId = c.getString(0).orEmpty(),
                brand = c.getString(1).orEmpty(),
                materialType = c.getString(2).orEmpty(),
                name = c.getString(3).orEmpty(),
                minTemp = c.getInt(4),
                maxTemp = c.getInt(5),
                diameter = c.getString(6).orEmpty()
            ) else null
        }
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
        // UPDATE first to preserve original_material/notes; INSERT only for new rows.
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
                remaining_grams,
                original_material,
                notes
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
                        remainingGrams = if (!it.isNull(8)) it.getInt(8) else null,
                        originalMaterial = it.getString(9).orEmpty(),
                        notes = it.getString(10).orEmpty()
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
                remaining_grams,
                original_material,
                notes
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
                        remainingGrams = if (!it.isNull(8)) it.getInt(8) else null,
                        originalMaterial = it.getString(9).orEmpty(),
                        notes = it.getString(10).orEmpty()
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

    fun upsertTrayNotes(
        db: SQLiteDatabase,
        trayUid: String,
        originalMaterial: String,
        notes: String
    ) {
        val values = ContentValues()
        values.put("original_material", originalMaterial)
        values.put("notes", notes)
        val updated = db.update(
            TRAY_UID_TABLE,
            values,
            "tray_uid = ?",
            arrayOf(trayUid)
        )
        if (updated == 0) {
            values.put("tray_uid", trayUid)
            values.put("remaining_percent", 100f)
            db.insertWithOnConflict(TRAY_UID_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun getTrayExtraFields(db: SQLiteDatabase, trayUid: String): Pair<String, String> {
        val cursor = db.query(
            TRAY_UID_TABLE,
            arrayOf("original_material", "notes"),
            "tray_uid = ?",
            arrayOf(trayUid),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) {
                Pair(it.getString(0).orEmpty(), it.getString(1).orEmpty())
            } else {
                Pair("", "")
            }
        }
    }

}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02X".format(Locale.US, it) }
