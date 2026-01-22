package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m0h31h31.bamburfidreader.R

@Preview
@Composable
fun MiscScreen(
    onBackupDatabase: () -> String = { "" },
    onImportDatabase: () -> String = { "" },
    onResetDatabase: () -> String = { "" },
    appConfigMessage: String = "",
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val boostLink =
        "bambulab://bbl/design/model/detail?design_id=2020787&instance_id=2253290&appSharePlatform=copy"
    var message by remember { mutableStateOf("") }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.tab_misc),
                style = MaterialTheme.typography.titleLarge
            )
            if (appConfigMessage.isNotBlank()) {
                Text(
                    text = appConfigMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { uriHandler.openUri(boostLink) }) {
                Text(text = stringResource(R.string.action_boost_open_bambu))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { message = onBackupDatabase() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.action_backup_db))
                }
                Button(
                    onClick = { message = onImportDatabase() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.action_import_db))
                }
            }
            Button(
                onClick = { message = onResetDatabase() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "重置数据库")
            }
        }
    }
}
