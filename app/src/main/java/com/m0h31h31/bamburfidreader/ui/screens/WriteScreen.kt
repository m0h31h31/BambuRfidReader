package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.ui.components.NeuButton
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import com.m0h31h31.bamburfidreader.ui.components.NeuTextField
import com.m0h31h31.bamburfidreader.ui.components.neuBackground

enum class NdefWriteType {
    TEXT,
    URL,
    PHONE,
    WIFI
}

data class NdefWriteRequest(
    val type: NdefWriteType,
    val textContent: String = "",
    val url: String = "",
    val phone: String = "",
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val wifiSecurity: String = "WPA"
)

@Preview
@Composable
fun WriteScreen(
    statusMessage: String = "",
    onStartNdefWrite: (NdefWriteRequest) -> String = { "" }
) {
    var selectedType by remember { mutableStateOf(NdefWriteType.TEXT.name) }
    var textContent by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiSecurity by remember { mutableStateOf("WPA") }
    var pageMessage by remember { mutableStateOf("") }

    val currentType = NdefWriteType.valueOf(selectedType)

    fun buildRequest(): NdefWriteRequest {
        return NdefWriteRequest(
            type = currentType,
            textContent = textContent,
            url = url,
            phone = phone,
            wifiSsid = wifiSsid,
            wifiPassword = wifiPassword,
            wifiSecurity = wifiSecurity
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize().neuBackground(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NeuPanel(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.write_intro),
                    modifier = Modifier,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = stringResource(R.string.write_type),
                style = MaterialTheme.typography.titleMedium
            )

            NdefWriteType.values().forEach { type ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = currentType == type,
                        onClick = { selectedType = type.name }
                    )
                    Text(
                        text = when (type) {
                            NdefWriteType.TEXT -> stringResource(R.string.write_type_text)
                            NdefWriteType.URL -> stringResource(R.string.write_type_url)
                            NdefWriteType.PHONE -> stringResource(R.string.write_type_phone)
                            NdefWriteType.WIFI -> "WiFi"
                        }
                    )
                }
            }

            when (currentType) {
                NdefWriteType.TEXT -> {
                    NeuTextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.write_text_label)
                    )
                    Text(
                        text = stringResource(R.string.write_text_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                NdefWriteType.URL -> {
                    NeuTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.write_url_label)
                    )
                    Text(
                        text = stringResource(R.string.write_url_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                NdefWriteType.PHONE -> {
                    NeuTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.write_phone_label)
                    )
                    Text(
                        text = stringResource(R.string.write_phone_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                NdefWriteType.WIFI -> {
                    NeuTextField(
                        value = wifiSsid,
                        onValueChange = { wifiSsid = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.write_wifi_ssid)
                    )
                    NeuTextField(
                        value = wifiPassword,
                        onValueChange = { wifiPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.write_wifi_password)
                    )
                    NeuTextField(
                        value = wifiSecurity,
                        onValueChange = { wifiSecurity = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.write_wifi_security)
                    )
                    Text(
                        text = stringResource(R.string.write_wifi_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            NeuButton(
                text = stringResource(R.string.write_start),
                onClick = { pageMessage = onStartNdefWrite(buildRequest()) },
                modifier = Modifier.fillMaxWidth()
            )

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (pageMessage.isNotBlank()) {
                Text(
                    text = pageMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
