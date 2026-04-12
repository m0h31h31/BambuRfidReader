package com.m0h31h31.bamburfidreader.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.m0h31h31.bamburfidreader.NfcUiState
import com.m0h31h31.bamburfidreader.R
import com.m0h31h31.bamburfidreader.FilamentDbHelper
import com.m0h31h31.bamburfidreader.ShareTagItem
import com.m0h31h31.bamburfidreader.ui.components.NeuPanel
import com.m0h31h31.bamburfidreader.ui.components.neuBackground
import com.m0h31h31.bamburfidreader.ui.screens.InventoryScreen
import com.m0h31h31.bamburfidreader.ui.screens.ReaderScreen
import com.m0h31h31.bamburfidreader.ui.screens.TagScreen
import com.m0h31h31.bamburfidreader.ui.screens.MiscScreen
import com.m0h31h31.bamburfidreader.ui.screens.DataScreen
import com.m0h31h31.bamburfidreader.ui.screens.NdefWriteRequest
import com.m0h31h31.bamburfidreader.ui.screens.WriteScreen
import com.m0h31h31.bamburfidreader.ui.screens.CrealityScreen
import com.m0h31h31.bamburfidreader.ui.screens.SnapmakerTagScreen
import com.m0h31h31.bamburfidreader.SnapmakerShareTagItem
import com.m0h31h31.bamburfidreader.CrealityMaterial
import com.m0h31h31.bamburfidreader.CrealityTagData
import com.m0h31h31.bamburfidreader.ReaderBrand
import com.m0h31h31.bamburfidreader.SnapmakerTagData
import com.m0h31h31.bamburfidreader.ui.theme.AppUiStyle
import com.m0h31h31.bamburfidreader.ui.theme.ColorPalette
import com.m0h31h31.bamburfidreader.ui.theme.LocalAppUiStyle
import com.m0h31h31.bamburfidreader.ui.theme.ThemeMode
import com.m0h31h31.bamburfidreader.utils.ConfigManager
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem

private data class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val iconRes: Int
)

private val topDestinations = listOf(
    TopDestination("reader", R.string.tab_reader, R.drawable.shibie),
    TopDestination("inventory", R.string.tab_inventory, R.drawable.kucun),
    TopDestination("data", R.string.tab_data, R.drawable.shuju),
    TopDestination("tag", R.string.tab_tag, R.drawable.bambu),
    TopDestination("snapmaker", R.string.tab_snapmaker, R.drawable.snapmaker),
    TopDestination("creality", R.string.tab_creality, R.drawable.chuangxiang),
    TopDestination("misc", R.string.tab_misc, R.drawable.zaxiang)
)

@Composable
fun AppNavigation(
    state: NfcUiState,
    voiceEnabled: Boolean,
    uiStyle: AppUiStyle,
    readAllSectors: Boolean,
    saveKeysToFile: Boolean,
    formatTagDebugEnabled: Boolean,
    forceOverwriteImport: Boolean,
    ttsReady: Boolean,
    ttsLanguageReady: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onUiStyleChange: (AppUiStyle) -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    colorPalette: ColorPalette = ColorPalette.OCEAN,
    onColorPaletteChange: (ColorPalette) -> Unit = {},
    onReadAllSectorsChange: (Boolean) -> Unit,
    onSaveKeysToFileChange: (Boolean) -> Unit,
    onFormatTagDebugEnabledChange: (Boolean) -> Unit,
    onForceOverwriteImportChange: (Boolean) -> Unit,
    bambuTagEnabled: Boolean = true,
    onBambuTagEnabledChange: (Boolean) -> Unit = {},
    crealityEnabled: Boolean = false,
    onCrealityEnabledChange: (Boolean) -> Unit = {},
    crealityTagData: CrealityTagData? = null,
    crealityStatusMessage: String = "",
    crealityWriteInProgress: Boolean = false,
    onCrealityPrepareWrite: (String, String, String) -> Unit = { _, _, _ -> },
    onCrealityCancelWrite: () -> Unit = {},
    onCrealityClearTagData: () -> Unit = {},
    inventoryEnabled: Boolean = false,
    onInventoryEnabledChange: (Boolean) -> Unit = {},
    autoDetectBrand: Boolean = false,
    onAutoDetectBrandChange: (Boolean) -> Unit = {},
    autoShareTag: Boolean = true,
    onAutoShareTagChange: (Boolean) -> Unit = {},
    onCheckDownloadPermission: suspend () -> String? = { null },
    onDownloadTagPackage: suspend (brand: String, onProgress: (Int) -> Unit, onImportStatus: (String) -> Unit) -> String = { _, _, _ -> "" },
    hideCopiedTags: Boolean = true,
    onHideCopiedTagsChange: (Boolean) -> Unit = {},
    dualTagMode: Boolean = false,
    onDualTagModeChange: (Boolean) -> Unit = {},
    tagViewMode: String = "list",
    onTagViewModeChange: (String) -> Unit = {},
    onNotesChange: (String, String, String) -> Unit = { _, _, _ -> },
    onTrayOutbound: (String) -> Unit,
    onRemainingChange: (String, Float, Int?) -> Unit,
    dbHelper: FilamentDbHelper?,
    onBackupDatabase: () -> String,
    onImportDatabase: () -> String,
    onClearFuid: () -> String,
    onCancelClearFuid: () -> String,
    onClearSelfTags: () -> String,
    onClearShareTags: () -> String = { "" },
    onEnqueueCuidTest: () -> String = { "" },
    onCancelCuidTest: () -> String = { "" },
    cuidTestInProgress: Boolean = false,
    onResetDatabase: () -> String,
    selfTagCount: Int,
    miscStatusMessage: String,
    onExportTagPackage: () -> String,
    onSelectImportTagPackage: () -> String,
    onSelectImportSnapmakerTagPackage: () -> String = { "" },
    snapmakerTagEnabled: Boolean = false,
    onSnapmakerTagEnabledChange: (Boolean) -> Unit = {},
    snapmakerShareTagItems: List<SnapmakerShareTagItem> = emptyList(),
    snapmakerShareLoading: Boolean = false,
    snapmakerWriteStatusMessage: String = "",
    snapmakerWriteInProgress: Boolean = false,
    onSnapmakerTagScreenEnter: () -> Unit = {},
    onStartWriteSnapmakerTag: (SnapmakerShareTagItem) -> Unit = {},
    onDeleteSnapmakerTagItem: (SnapmakerShareTagItem) -> String = { "" },
    onCancelSnapmakerWrite: () -> Unit = {},
    navigateToReader: Boolean = false,
    navigateToTag: Boolean = false,
    navigateToMisc: Boolean = false,
    scrollToNotice: Boolean = false,
    onScrollToNoticeDone: () -> Unit = {},
    showRecoveryAction: Boolean = false,
    onAttemptRecovery: () -> Unit,
    shareTagItems: List<ShareTagItem>,
    tagPreselectedFileName: String? = null,
    shareLoading: Boolean,
    writeStatusMessage: String,
    writeToolStatusMessage: String,
    writeInProgress: Boolean,
    formatInProgress: Boolean,
    onTagScreenEnter: () -> Unit,
    onStartWriteTag: (ShareTagItem) -> Unit,
    onDeleteTagItem: (ShareTagItem) -> String,
    onCancelWriteTag: () -> Unit,
    onStartCModifyTag: (ShareTagItem) -> Unit = {},
    cModifyInProgress: Boolean = false,
    cModifyRecoveryInfo: com.m0h31h31.bamburfidreader.CModifyRecoveryInfo? = null,
    onDismissCModifyRecovery: () -> Unit = {},
    onStartNdefWrite: (NdefWriteRequest) -> String,
    onActiveRouteChange: (String) -> Unit = {},
    readerBrand: ReaderBrand = ReaderBrand.BAMBU,
    onReaderBrandChange: (ReaderBrand) -> Unit = {},
    readerCrealityTagData: CrealityTagData? = null,
    readerCrealityMaterial: CrealityMaterial? = null,
    readerSnapmakerTagData: SnapmakerTagData? = null,
    readerBrandStatus: String = "",
    anomalyUids: Map<String, Int> = emptyMap(),
    onReportAnomaly: (cardUid: String) -> Unit = { _ -> },
    pendingUpdateInfo: com.m0h31h31.bamburfidreader.utils.UpdateInfo? = null,
    isDownloadingUpdate: Boolean = false,
    onStartUpdate: (com.m0h31h31.bamburfidreader.utils.UpdateInfo) -> Unit = {},
    onDismissUpdate: () -> Unit = {},
) {
    val resolvedUiStyle = LocalAppUiStyle.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(currentRoute) {
        onActiveRouteChange(currentRoute ?: "reader")
    }
    val visibleDestinations = remember(inventoryEnabled, crealityEnabled, snapmakerTagEnabled, bambuTagEnabled) {
        topDestinations.filter { dest ->
            when (dest.route) {
                "inventory", "data" -> inventoryEnabled
                "tag" -> bambuTagEnabled
                "creality" -> crealityEnabled
                "snapmaker" -> snapmakerTagEnabled
                else -> true
            }
        }
    }

    LaunchedEffect(inventoryEnabled) {
        if (!inventoryEnabled && (currentRoute == "inventory" || currentRoute == "data")) {
            navController.navigate("reader") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(bambuTagEnabled) {
        if (!bambuTagEnabled && currentRoute == "tag") {
            navController.navigate("reader") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(crealityEnabled) {
        if (!crealityEnabled && currentRoute == "creality") {
            navController.navigate("reader") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(snapmakerTagEnabled) {
        if (!snapmakerTagEnabled && currentRoute == "snapmaker") {
            navController.navigate("reader") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(currentRoute, snapmakerWriteInProgress) {
        if (currentRoute != "snapmaker" && snapmakerWriteInProgress) {
            onCancelSnapmakerWrite()
        }
    }
    LaunchedEffect(currentRoute, writeInProgress) {
        if (currentRoute != "tag" && writeInProgress) {
            onCancelWriteTag()
        }
    }
    LaunchedEffect(currentRoute, cModifyInProgress) {
        if (currentRoute != "tag" && cModifyInProgress) {
            onCancelWriteTag()
        }
    }
    LaunchedEffect(currentRoute, formatInProgress) {
        if (currentRoute != "misc" && formatInProgress) {
            onCancelClearFuid()
        }
    }
    LaunchedEffect(currentRoute, cuidTestInProgress) {
        if (currentRoute != "misc" && cuidTestInProgress) {
            onCancelCuidTest()
        }
    }
    
    // 支持外部触发跳转到 tag / reader / misc
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
    } else if (navigateToMisc && currentRoute != "misc") {
        navController.navigate("misc") {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .neuBackground(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
            // 更新下载进度横幅
            AnimatedVisibility(
                visible = isDownloadingUpdate,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.update_downloading),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    }
                }
            }
            if (resolvedUiStyle == AppUiStyle.MIUIX) {
                key(inventoryEnabled, bambuTagEnabled, crealityEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    MiuixNavigationBar {
                        visibleDestinations.forEach { destination ->
                            val selected = currentRoute == destination.route
                            val onNavigate = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            val label = stringResource(destination.labelRes)
                            val icon = ImageVector.vectorResource(destination.iconRes)
                            MiuixNavigationBarItem(
                                selected = selected,
                                onClick = onNavigate,
                                icon = icon,
                                label = label
                            )
                        }
                    }
                }
                } // key
            } else {
                key(inventoryEnabled, bambuTagEnabled, crealityEnabled) {
                NeuPanel(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        visibleDestinations.forEach { destination ->
                            val selected = currentRoute == destination.route
                            val onNavigate = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            val label = stringResource(destination.labelRes)
                            val icon = ImageVector.vectorResource(destination.iconRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = onNavigate,
                                icon = {
                                    androidx.compose.material3.Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                label = { Text(text = label) },
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
                } // key
            }
            } // Column
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
                    },
                    onNotesChange = onNotesChange,
                    readerBrand = readerBrand,
                    onBrandChange = onReaderBrandChange,
                    readerCrealityTagData = readerCrealityTagData,
                    readerCrealityMaterial = readerCrealityMaterial,
                    readerSnapmakerTagData = readerSnapmakerTagData,
                    readerBrandStatus = readerBrandStatus,
                    onReportAnomaly = onReportAnomaly
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
                    writeStatusMessage = writeStatusMessage,
                    writeInProgress = writeInProgress,
                    hideCopiedTags = hideCopiedTags,
                    dualTagMode = dualTagMode,
                    tagViewMode = tagViewMode,
                    onStartWrite = onStartWriteTag,
                    onDelete = onDeleteTagItem,
                    onCancelWrite = onCancelWriteTag,
                    onStartCModify = onStartCModifyTag,
                    cModifyInProgress = cModifyInProgress,
                    cModifyRecoveryInfo = cModifyRecoveryInfo,
                    onDismissCModifyRecovery = onDismissCModifyRecovery,
                    onRefresh = onTagScreenEnter,
                    anomalyUids = anomalyUids
                )
            }
            composable("snapmaker") {
                LaunchedEffect(snapmakerShareTagItems.size) {
                    if (snapmakerShareTagItems.isEmpty()) onSnapmakerTagScreenEnter()
                }
                SnapmakerTagScreen(
                    items = snapmakerShareTagItems,
                    loading = snapmakerShareLoading,
                    writeStatusMessage = snapmakerWriteStatusMessage,
                    writeInProgress = snapmakerWriteInProgress,
                    tagViewMode = tagViewMode,
                    onStartWrite = onStartWriteSnapmakerTag,
                    onDelete = onDeleteSnapmakerTagItem,
                    onCancelWrite = onCancelSnapmakerWrite,
                    onRefresh = onSnapmakerTagScreenEnter
                )
            }
            composable("creality") {
                CrealityScreen(
                    dbHelper = dbHelper,
                    crealityTagData = crealityTagData,
                    crealityStatusMessage = crealityStatusMessage,
                    writeInProgress = crealityWriteInProgress,
                    onPrepareWrite = onCrealityPrepareWrite,
                    onCancelWrite = onCrealityCancelWrite,
                    onClearTagData = onCrealityClearTagData
                )
            }
            composable("misc") {
                            val context = LocalContext.current
                            val appConfigMessage = ConfigManager.getAppConfigMessage(context)
                            val appConfigAdMessage = ConfigManager.getAppConfigAdMessage(context)
                            val appConfigBoostLink = ConfigManager.getAppConfigBoostLink(context)
                            val appConfigLogoLinks = ConfigManager.getAppConfigLogoLinks(context)
                            MiscScreen(
                                bambuTagEnabled = bambuTagEnabled,
                                onBambuTagEnabledChange = onBambuTagEnabledChange,
                                crealityEnabled = crealityEnabled,
                                onCrealityEnabledChange = onCrealityEnabledChange,
                                inventoryEnabled = inventoryEnabled,
                                onInventoryEnabledChange = onInventoryEnabledChange,
                                autoDetectBrand = autoDetectBrand,
                                onAutoDetectBrandChange = onAutoDetectBrandChange,
                                autoShareTag = autoShareTag,
                                onAutoShareTagChange = onAutoShareTagChange,
                                onCheckDownloadPermission = onCheckDownloadPermission,
                                onDownloadTagPackage = onDownloadTagPackage,
                                hideCopiedTags = hideCopiedTags,
                                onHideCopiedTagsChange = onHideCopiedTagsChange,
                                dualTagMode = dualTagMode,
                                onDualTagModeChange = onDualTagModeChange,
                                tagViewMode = tagViewMode,
                                onTagViewModeChange = onTagViewModeChange,
                                onBackupDatabase = onBackupDatabase,
                                onImportDatabase = onImportDatabase,
                                onClearFuid = onClearFuid,
                                onCancelClearFuid = onCancelClearFuid,
                                onClearSelfTags = onClearSelfTags,
                                onClearShareTags = onClearShareTags,
                                onEnqueueCuidTest = onEnqueueCuidTest,
                                onCancelCuidTest = onCancelCuidTest,
                                cuidTestInProgress = cuidTestInProgress,
                                onResetDatabase = onResetDatabase,
                                selfTagCount = selfTagCount,
                                miscStatusMessage = miscStatusMessage,
                                onExportTagPackage = onExportTagPackage,
                                onSelectImportTagPackage = onSelectImportTagPackage,
                            onSelectImportSnapmakerTagPackage = onSelectImportSnapmakerTagPackage,
                            snapmakerTagEnabled = snapmakerTagEnabled,
                            onSnapmakerTagEnabledChange = onSnapmakerTagEnabledChange,
                                appConfigMessage = appConfigMessage,
                                appConfigAdMessage = appConfigAdMessage,
                                boostLink = appConfigBoostLink,
                                logoLinks = appConfigLogoLinks,
                                uiStyle = uiStyle,
                                onUiStyleChange = onUiStyleChange,
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                colorPalette = colorPalette,
                                onColorPaletteChange = onColorPaletteChange,
                                readAllSectors = readAllSectors,
                                onReadAllSectorsChange = onReadAllSectorsChange,
                                saveKeysToFile = saveKeysToFile,
                                onSaveKeysToFileChange = onSaveKeysToFileChange,
                                formatTagDebugEnabled = formatTagDebugEnabled,
                                onFormatTagDebugEnabledChange = onFormatTagDebugEnabledChange,
                                forceOverwriteImport = forceOverwriteImport,
                                onForceOverwriteImportChange = onForceOverwriteImportChange,
                                formatInProgress = formatInProgress,
                                scrollToNotice = scrollToNotice,
                                onScrollToNoticeDone = onScrollToNoticeDone,
                                pendingUpdateInfo = pendingUpdateInfo,
                                isDownloadingUpdate = isDownloadingUpdate,
                                onStartUpdate = onStartUpdate,
                                onDismissUpdate = onDismissUpdate
                            )
                        }
        }
    }
}
