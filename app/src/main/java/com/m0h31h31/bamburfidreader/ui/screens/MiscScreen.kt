package com.m0h31h31.bamburfidreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
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
    miscStatusMessage: String = "",
    onExportTagPackage: () -> String = { "" },
    onSelectImportTagPackage: () -> String = { "" },
    appConfigMessage: String = "",
    readAllSectors: Boolean = false,
    onReadAllSectorsChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val boostLink =
        "bambulab://bbl/design/model/detail?design_id=2020787&instance_id=2253290&appSharePlatform=copy"
    var message by remember { mutableStateOf("") }
    var showReadAllSectorsDialog by remember { mutableStateOf(false) }
    
    // 处理开关变化
    fun handleReadAllSectorsChange(checked: Boolean) {
        if (checked) {
            // 当用户想要开启时，显示弹窗提醒
            showReadAllSectorsDialog = true
        } else {
            // 关闭时直接执行
            onReadAllSectorsChange(false)
        }
    }
    
    // 处理弹窗确认
    fun confirmReadAllSectors() {
        showReadAllSectorsDialog = false
        onReadAllSectorsChange(true)
    }
    
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
            if (miscStatusMessage.isNotBlank()) {
                Text(
                    text = miscStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            // 添加读取全部扇区的开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(text = "读取全部扇区数据并保存文件")
                Switch(
                    checked = readAllSectors,
                    onCheckedChange = ::handleReadAllSectorsChange
                )
            }
            
            // 读取全部扇区的弹窗提醒
            if (showReadAllSectorsDialog) {
                AlertDialog(
                    onDismissRequest = { showReadAllSectorsDialog = false },
                    title = { Text(text = "读取全部数据提醒") },
                    text = {
                        Text(
                            text = "读取全部数据会影响读取速度，数据会保存在包名下的 rfid_file 文件夹下。确定要开启吗？"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = ::confirmReadAllSectors
                        ) {
                            Text(text = "确定")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showReadAllSectorsDialog = false }
                        ) {
                            Text(text = "取消")
                        }
                    }
                )
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

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
//            Button(
//                onClick = { message = onResetDatabase() },
//                modifier = Modifier.fillMaxWidth()
//            ) {
//                Text(text = "重置数据库")
//            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { message = onExportTagPackage() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "打包标签数据")
                }
                Button(
                    onClick = { message = onSelectImportTagPackage() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "导入标签包")
                }
            }


            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            TextButton(onClick = { uriHandler.openUri(boostLink) }) {
                Text(text = stringResource(R.string.action_boost_open_bambu))
            }
        }
    }
}
