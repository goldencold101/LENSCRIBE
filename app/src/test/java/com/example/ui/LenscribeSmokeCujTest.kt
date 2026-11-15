package com.example.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.backup.BackupScanEntity
import com.example.model.PlanTier
import com.example.model.ScanMode
import com.example.model.SmartPreset
import com.example.model.UserProfile
import com.example.ui.screens.BillingAndWorkflowsScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.theme.LenscribeTheme
import com.example.viewmodel.ScanRunState
import com.example.viewmodel.UiState
import com.google.firebase.Timestamp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LenscribeSmokeCujTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scannerScreen_multiPageSequencingControlsWork() {
        val page1 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val page2 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        var moveLaterClicked = false
        var reverseClicked = false

        composeRule.setContent {
            LenscribeTheme(darkTheme = true) {
                ScannerScreen(
                    profile = UserProfile(
                        id = "u1",
                        userId = "u1",
                        planTier = PlanTier.PRO.id,
                        monthlyCreditsLimit = 300L,
                        subscriptionStatus = "trialing",
                        subscriptionExpiresAt = Timestamp(Date(System.currentTimeMillis() + 86_400_000L * 89L))
                    ),
                    queuedPages = listOf(page1, page2),
                    selectedPageIndex = 0,
                    scanMode = ScanMode.FAST,
                    smartPreset = SmartPreset.LAYOUT,
                    mergeContinuous = true,
                    customNote = "",
                    editorTitle = "",
                    editorMarkdown = "",
                    lowConfidenceTerms = emptyList(),
                    hasTable = false,
                    scanRunState = ScanRunState.Idle,
                    lastScanDurationSeconds = null,
                    onAddPages = {},
                    onSelectPage = {},
                    onRemovePage = {},
                    onMovePageEarlier = {},
                    onMovePageLater = { moveLaterClicked = true },
                    onReverseSequence = { reverseClicked = true },
                    onClearQueue = {},
                    onRotatePage = {},
                    onEnhancePage = {},
                    onTrimPage = {},
                    onSendToStudio = {},
                    onSelectMode = {},
                    onSelectPreset = {},
                    onToggleMerge = {},
                    onCustomNoteChange = {},
                    onRunScan = {},
                    onEditorChanged = { _, _ -> },
                    onTableCellChanged = { _, _, _ -> },
                    onAddTableRow = {},
                    onSaveEdits = {},
                    onNavigateToPlans = {},
                    onMessage = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("#1").assertIsDisplayed()
        composeRule.onNodeWithText("#2").assertIsDisplayed()
        composeRule.onNodeWithTag("page_sequence_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("move_page_later_button").performClick()
        composeRule.onNodeWithTag("reverse_sequence_button").performClick()
        composeRule.waitForIdle()

        assertTrue(moveLaterClicked)
        assertTrue(reverseClicked)
    }

    @Test
    fun billingScreen_showsCountdownBackupDbAndAccountDeletionDialog() {
        var deletedConfirmed = false
        var syncTriggered = false
        val sampleBackup = BackupScanEntity(
            id = "scan_1",
            userId = "u1",
            sequenceNumber = 1L,
            title = "Invoice #2041",
            markdown = "| Item | Price |\n| --- | --- |\n| Coffee | 4.50 |",
            mode = "table",
            preset = "table",
            pageCount = 1L,
            hasTable = true,
            lowConfidenceTermsJson = "[]",
            updatedAtMillis = System.currentTimeMillis(),
            backedUpAtMillis = System.currentTimeMillis(),
            cloudSynced = true
        )

        composeRule.setContent {
            LenscribeTheme(darkTheme = true) {
                BillingAndWorkflowsScreen(
                    profile = UserProfile(
                        id = "u1",
                        userId = "u1",
                        displayName = "Samuel",
                        email = "samuel@example.com",
                        planTier = PlanTier.PRO.id,
                        monthlyCreditsLimit = 300L,
                        subscriptionStatus = "trialing",
                        subscriptionExpiresAt = Timestamp(Date(System.currentTimeMillis() + 86_400_000L * 89L))
                    ),
                    workflowsState = UiState.Success(emptyList()),
                    backupScans = listOf(sampleBackup),
                    lastBackupAtMs = System.currentTimeMillis(),
                    isDarkTheme = true,
                    screenShieldEnabled = false,
                    onToggleTheme = {},
                    onToggleScreenShield = {},
                    onToggleZeroRetention = {},
                    onSubscriptionExpired = {},
                    onSyncBackupNow = { syncTriggered = true },
                    onRestoreBackupToCloud = {},
                    onBuildPortableBackupJson = { ByteArray(0) },
                    onImportPortableBackupJson = {},
                    onClearLocalBackups = {},
                    onCreateWorkflow = { _, _, _, _ -> },
                    onToggleWorkflow = {},
                    onTestWorkflow = {},
                    onDeleteWorkflow = {},
                    onSignOut = {},
                    onDeleteAccount = { deletedConfirmed = true },
                    onMessage = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("subscription_countdown_card").assertIsDisplayed()
        composeRule.onNodeWithTag("backup_sync_now_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(syncTriggered)

        composeRule.onNodeWithTag("delete_account_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_delete_account_button").performClick()
        composeRule.waitForIdle()
        assertTrue(deletedConfirmed)
    }
}
