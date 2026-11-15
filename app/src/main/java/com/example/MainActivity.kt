package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai.LenscribeAiEngine
import com.example.auth.authStateFlow
import com.example.auth.signOutUser
import com.example.backup.BackupRepository
import com.example.backup.LenscribeBackupDatabase
import com.example.backup.LocalHistoryRepository
import com.example.data.LenscribeRepository
import com.example.firebase.configureAppCheck
import com.example.model.PlanTier
import com.example.security.SecurityGuard
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BillingAndWorkflowsScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.LenscribeTheme
import com.example.viewmodel.LenscribeViewModel
import com.example.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.io.File

enum class LenscribeTab(
    val routeId: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    SCAN("scan", R.string.nav_scan, Icons.Filled.DocumentScanner, Icons.Outlined.DocumentScanner),
    LIBRARY("library", R.string.nav_library, Icons.Filled.FolderCopy, Icons.Outlined.FolderCopy),
    STUDIO("studio", R.string.nav_studio, Icons.Filled.Brush, Icons.Outlined.Brush),
    PLANS("plans", R.string.nav_plans, Icons.Filled.CreditCard, Icons.Outlined.CreditCard)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App Check MUST run before enableEdgeToEdge, setContent, or ViewModels
        configureAppCheck(this, intent)
        SecurityGuard.purgeExportCache(this)
        window?.decorView?.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        setContent {
            var isDarkTheme by rememberSaveable { mutableStateOf(true) }
            var screenShieldEnabled by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(screenShieldEnabled) {
                if (screenShieldEnabled) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            LenscribeTheme(darkTheme = isDarkTheme) {
                AppNavigation(
                    isDarkTheme = isDarkTheme,
                    screenShieldEnabled = screenShieldEnabled,
                    onToggleTheme = { isDarkTheme = it },
                    onToggleScreenShield = { screenShieldEnabled = it }
                )
            }
        }
    }
}

@Composable
fun AppNavigation(
    isDarkTheme: Boolean,
    screenShieldEnabled: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onToggleScreenShield: (Boolean) -> Unit
) {
    val auth = Firebase.auth
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            LenscribeWorkspace(
                currentUserId = user.uid,
                displayName = user.displayName ?: "Workspace User",
                email = user.email,
                isDarkTheme = isDarkTheme,
                screenShieldEnabled = screenShieldEnabled,
                onToggleTheme = onToggleTheme,
                onToggleScreenShield = onToggleScreenShield
            )
        }
    }
}

@Composable
private fun LenscribeWorkspace(
    currentUserId: String,
    displayName: String,
    email: String?,
    isDarkTheme: Boolean,
    screenShieldEnabled: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onToggleScreenShield: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: LenscribeViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val backupDb = LenscribeBackupDatabase.getInstance(app)
                val scanImageDir = File(app.filesDir, "scan_images")
                LenscribeViewModel(
                    repository = LenscribeRepository(db),
                    aiEngine = LenscribeAiEngine(),
                    currentUserId = currentUserId,
                    backupRepository = BackupRepository(backupDb.backupScanDao()),
                    localHistoryRepository = LocalHistoryRepository(backupDb.scannedDocumentDao(), scanImageDir)
                )
            }
        }
    )

    LaunchedEffect(currentUserId) {
        viewModel.ensureProfile(displayName, email)
    }

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val scansState by viewModel.scansState.collectAsStateWithLifecycle()
    val workflowsState by viewModel.workflowsState.collectAsStateWithLifecycle()
    val backupScans by viewModel.backupScansState.collectAsStateWithLifecycle()
    val localHistoryScans by viewModel.localHistoryState.collectAsStateWithLifecycle()
    val lastBackupAtMs by viewModel.lastBackupAtMs.collectAsStateWithLifecycle()

    val queuedPages by viewModel.queuedPages.collectAsStateWithLifecycle()
    val selectedPageIndex by viewModel.selectedPageIndex.collectAsStateWithLifecycle()
    val scanMode by viewModel.scanMode.collectAsStateWithLifecycle()
    val smartPreset by viewModel.smartPreset.collectAsStateWithLifecycle()
    val mergeContinuous by viewModel.mergeContinuous.collectAsStateWithLifecycle()
    val customNote by viewModel.customNote.collectAsStateWithLifecycle()
    val editorTitle by viewModel.editorTitle.collectAsStateWithLifecycle()
    val editorMarkdown by viewModel.editorMarkdown.collectAsStateWithLifecycle()
    val lowConfidenceTerms by viewModel.lowConfidenceTerms.collectAsStateWithLifecycle()
    val hasTable by viewModel.hasTable.collectAsStateWithLifecycle()
    val scanRunState by viewModel.scanRunState.collectAsStateWithLifecycle()
    val lastScanDurationSeconds by viewModel.lastScanDurationSeconds.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val studioIsEditMode by viewModel.studioIsEditMode.collectAsStateWithLifecycle()
    val studioInputBitmap by viewModel.studioInputBitmap.collectAsStateWithLifecycle()
    val studioPrompt by viewModel.studioPrompt.collectAsStateWithLifecycle()
    val studioAspectRatio by viewModel.studioAspectRatio.collectAsStateWithLifecycle()
    val studioState by viewModel.studioState.collectAsStateWithLifecycle()

    val profile = (profileState as? UiState.Success)?.data
    val maxBatchPages = profile?.tier?.maxBatchPages ?: PlanTier.FREE.maxBatchPages

    var currentTab by rememberSaveable { mutableStateOf(LenscribeTab.SCAN) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    BackHandler(enabled = currentTab != LenscribeTab.SCAN) {
        currentTab = LenscribeTab.SCAN
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        LenscribeTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("nav_${tab.routeId}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        LenscribeTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("rail_${tab.routeId}")
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (currentTab) {
                        LenscribeTab.SCAN -> {
                            ScannerScreen(
                                profile = profile,
                                queuedPages = queuedPages,
                                selectedPageIndex = selectedPageIndex,
                                scanMode = scanMode,
                                smartPreset = smartPreset,
                                mergeContinuous = mergeContinuous,
                                customNote = customNote,
                                editorTitle = editorTitle,
                                editorMarkdown = editorMarkdown,
                                lowConfidenceTerms = lowConfidenceTerms,
                                hasTable = hasTable,
                                scanRunState = scanRunState,
                                lastScanDurationSeconds = lastScanDurationSeconds,
                                onAddPages = { pages -> viewModel.addPages(pages, maxBatchPages) },
                                onSelectPage = viewModel::selectPage,
                                onRemovePage = viewModel::removePage,
                                onMovePageEarlier = viewModel::moveSelectedPageEarlier,
                                onMovePageLater = viewModel::moveSelectedPageLater,
                                onReverseSequence = viewModel::reversePageSequence,
                                onClearQueue = viewModel::clearQueue,
                                onRotatePage = viewModel::rotateSelectedPage,
                                onEnhancePage = viewModel::enhanceSelectedPage,
                                onTrimPage = viewModel::trimSelectedPage,
                                onSendToStudio = {
                                    viewModel.sendSelectedPageToStudio()
                                    currentTab = LenscribeTab.STUDIO
                                },
                                onSelectMode = viewModel::setScanMode,
                                onSelectPreset = viewModel::setSmartPreset,
                                onToggleMerge = viewModel::setMergeContinuous,
                                onCustomNoteChange = viewModel::setCustomNote,
                                onRunScan = viewModel::runBatchScan,
                                onEditorChanged = viewModel::updateEditorContent,
                                onTableCellChanged = viewModel::updateTableCell,
                                onAddTableRow = viewModel::addTableRow,
                                onSaveEdits = viewModel::saveCurrentEdits,
                                onNavigateToPlans = { currentTab = LenscribeTab.PLANS },
                                onMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                        LenscribeTab.LIBRARY -> {
                            HistoryScreen(
                                profile = profile,
                                scansState = scansState,
                                localHistoryScans = localHistoryScans,
                                onOpenScan = { scan ->
                                    viewModel.openHistoryScan(scan)
                                    currentTab = LenscribeTab.SCAN
                                },
                                onDeleteScan = viewModel::deleteHistoryScan,
                                onOpenLocalScan = { localDoc ->
                                    viewModel.openLocalHistoryScan(localDoc)
                                    currentTab = LenscribeTab.SCAN
                                },
                                onDeleteLocalScan = viewModel::deleteLocalHistoryScan,
                                onNavigateToPlans = { currentTab = LenscribeTab.PLANS },
                                onMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                        LenscribeTab.STUDIO -> {
                            StudioScreen(
                                isEditMode = studioIsEditMode,
                                inputBitmap = studioInputBitmap,
                                prompt = studioPrompt,
                                aspectRatio = studioAspectRatio,
                                studioState = studioState,
                                onSetEditMode = viewModel::setStudioMode,
                                onSetInputBitmap = viewModel::setStudioInputBitmap,
                                onPromptChange = viewModel::setStudioPrompt,
                                onAspectRatioChange = viewModel::setStudioAspectRatio,
                                onRunGeneration = viewModel::runStudioGeneration,
                                onSendResultToScanner = {
                                    viewModel.sendStudioResultToScanner(maxBatchPages)
                                    currentTab = LenscribeTab.SCAN
                                },
                                onMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                        LenscribeTab.PLANS -> {
                            BillingAndWorkflowsScreen(
                                profile = profile,
                                workflowsState = workflowsState,
                                backupScans = backupScans,
                                lastBackupAtMs = lastBackupAtMs,
                                isDarkTheme = isDarkTheme,
                                screenShieldEnabled = screenShieldEnabled,
                                onToggleTheme = onToggleTheme,
                                onToggleScreenShield = onToggleScreenShield,
                                onToggleZeroRetention = viewModel::updateZeroRetention,
                                onSubscriptionExpired = viewModel::handleSubscriptionExpired,
                                onSyncBackupNow = viewModel::syncBackupNow,
                                onRestoreBackupToCloud = viewModel::restoreMissingFromBackup,
                                onBuildPortableBackupJson = viewModel::buildPortableBackupJson,
                                onImportPortableBackupJson = viewModel::importPortableBackupJson,
                                onClearLocalBackups = viewModel::clearLocalBackups,
                                onCreateWorkflow = viewModel::createWorkflow,
                                onToggleWorkflow = viewModel::toggleWorkflowEnabled,
                                onTestWorkflow = viewModel::testWorkflowNow,
                                onDeleteWorkflow = viewModel::deleteWorkflow,
                                onSignOut = {
                                    signOutUser(
                                        credentialManager = credentialManager,
                                        scope = scope
                                    )
                                },
                                onDeleteAccount = {
                                    viewModel.deleteWorkspaceAccountAndData {
                                        signOutUser(
                                            credentialManager = credentialManager,
                                            scope = scope
                                        )
                                    }
                                },
                                onMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
