package com.m0h31h31.bamburfidreader.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.m0h31h31.bamburfidreader.NfcUiState
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.FilamentDbHelper
import com.m0h31h31.bamburfidreader.ShareTagItem
import com.m0h31h31.bamburfidreader.ui.screens.InventoryScreen
import com.m0h31h31.bamburfidreader.ui.screens.ReaderScreen
import com.m0h31h31.bamburfidreader.ui.screens.TagScreen
import com.m0h31h31.bamburfidreader.ui.screens.MiscScreen
import com.m0h31h31.bamburfidreader.ui.screens.DataScreen
import com.m0h31h31.bamburfidreader.utils.ConfigManager

private data class TopDestination(
    val route: String,
    @StringRes val labelRes: Int
)

private val topDestinations = listOf(
    TopDestination("reader", R.string.tab_reader),
    TopDestination("inventory", R.string.tab_inventory),
    TopDestination("data", R.string.tab_data),
    TopDestination("tag", R.string.tab_tag),
    TopDestination("misc", R.string.tab_misc)
)

@Composable
fun AppNavigation(
    state: NfcUiState,
    voiceEnabled: Boolean,
    readAllSectors: Boolean,
    saveKeysToFile: Boolean,
    ttsReady: Boolean,
    ttsLanguageReady: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onReadAllSectorsChange: (Boolean) -> Unit,
    onSaveKeysToFileChange: (Boolean) -> Unit,
    onTrayOutbound: (String) -> Unit,
    onRemainingChange: (String, Float, Int?) -> Unit,
    dbHelper: FilamentDbHelper?,
    onBackupDatabase: () -> String,
    onImportDatabase: () -> String,
    onResetDatabase: () -> String,
    miscStatusMessage: String,
    onExportTagPackage: () -> String,
    onSelectImportTagPackage: () -> String,
    navigateToReader: Boolean = false,
    navigateToTag: Boolean = false,
    showRecoveryAction: Boolean = false,
    onAttemptRecovery: () -> Unit,
    shareTagItems: List<ShareTagItem>,
    tagPreselectedFileName: String? = null,
    shareLoading: Boolean,
    shareRefreshStatusMessage: String,
    writeStatusMessage: String,
    writeInProgress: Boolean,
    onTagScreenEnter: () -> Unit,
    onRefreshShareFiles: () -> String,
    onStartWriteTag: (ShareTagItem) -> Unit,
    onCancelWriteTag: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // 支持外部触发跳转到 tag / reader
    if (navigateToTag && currentRoute != "tag") {
        navController.navigate("tag") {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } else if (navigateToReader && currentRoute != "reader") {
        navController.navigate("reader") {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                topDestinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Box(modifier = Modifier.size(0.dp)) },
                        label = { Text(text = stringResource(destination.labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "reader",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("reader") {
                ReaderScreen(
                    state = state,
                    voiceEnabled = voiceEnabled,
                    ttsReady = ttsReady,
                    ttsLanguageReady = ttsLanguageReady,
                    onVoiceEnabledChange = onVoiceEnabledChange,
                    onTrayOutbound = onTrayOutbound,
                    showRecoveryAction = showRecoveryAction,
                    onAttemptRecovery = onAttemptRecovery,
                    onRemainingChange = { trayUid, percent, grams ->
                        onRemainingChange(trayUid, percent, grams)
                    }
                )
            }
            composable("inventory") {
                InventoryScreen(
                    dbHelper = dbHelper
                )
            }
            composable("data") {
                DataScreen(dbHelper = dbHelper)
            }
            composable("tag") {
                LaunchedEffect(shareTagItems.size) {
                    if (shareTagItems.isEmpty()) {
                        onTagScreenEnter()
                    }
                }
                TagScreen(
                    items = shareTagItems,
                    loading = shareLoading,
                    preselectedFileName = tagPreselectedFileName,
                    refreshStatusMessage = shareRefreshStatusMessage,
                    writeStatusMessage = writeStatusMessage,
                    writeInProgress = writeInProgress,
                    onRefresh = onRefreshShareFiles,
                    onStartWrite = onStartWriteTag,
                    onCancelWrite = onCancelWriteTag
                )
            }
            composable("misc") {
                            val context = LocalContext.current
                            val appConfigMessage = ConfigManager.getAppConfigMessage(context)
                            MiscScreen(
                                onBackupDatabase = onBackupDatabase,
                                onImportDatabase = onImportDatabase,
                                onResetDatabase = onResetDatabase,
                                miscStatusMessage = miscStatusMessage,
                                onExportTagPackage = onExportTagPackage,
                                onSelectImportTagPackage = onSelectImportTagPackage,
                                appConfigMessage = appConfigMessage,
                                readAllSectors = readAllSectors,
                                onReadAllSectorsChange = onReadAllSectorsChange,
                                saveKeysToFile = saveKeysToFile,
                                onSaveKeysToFileChange = onSaveKeysToFileChange
                            )
                        }
        }
    }
}
