package com.example.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.LenscribeAiEngine
import com.example.automation.WorkflowDispatcher
import com.example.backup.BackupRepository
import com.example.backup.BackupScanEntity
import com.example.backup.LocalHistoryRepository
import com.example.backup.ScannedDocumentEntity
import com.example.data.LenscribeRepository
import com.example.export.MarkdownParser
import com.example.imaging.ImagePreprocessor
import com.example.model.ScanDocument
import com.example.model.ScanMode
import com.example.model.SmartPreset
import com.example.model.UserProfile
import com.example.model.WorkflowAutomation
import com.example.security.SecurityGuard
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface ScanRunState {
    data object Idle : ScanRunState
    data class Processing(
        val pageCount: Int,
        val startedAtMs: Long,
        val estimatedSeconds: Int
    ) : ScanRunState
    data class Error(val message: String) : ScanRunState
}

sealed interface ImageStudioState {
    data object Idle : ImageStudioState
    data class Generating(val isEdit: Boolean) : ImageStudioState
    data class Ready(val bitmap: Bitmap, val prompt: String) : ImageStudioState
    data class Error(val message: String) : ImageStudioState
}

class LenscribeViewModel(
    private val repository: LenscribeRepository,
    private val aiEngine: LenscribeAiEngine,
    private val currentUserId: String,
    private val backupRepository: BackupRepository? = null,
    private val localHistoryRepository: LocalHistoryRepository? = null
) : ViewModel() {

    val profileState: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile()
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing profile for $currentUserId", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load workspace profile"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val scansState: StateFlow<UiState<List<ScanDocument>>> = repository.observeScans()
        .map<List<ScanDocument>, UiState<List<ScanDocument>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing scan history", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load scan library"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val workflowsState: StateFlow<UiState<List<WorkflowAutomation>>> = repository.observeWorkflows()
        .map<List<WorkflowAutomation>, UiState<List<WorkflowAutomation>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing workflows", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load automated workflows"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val backupScansState: StateFlow<List<BackupScanEntity>> = (
        backupRepository?.observeBackups(currentUserId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    val localHistoryState: StateFlow<List<ScannedDocumentEntity>> = (
        localHistoryRepository?.observeLocalHistory(currentUserId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    private val _lastBackupAtMs = MutableStateFlow<Long?>(null)
    val lastBackupAtMs: StateFlow<Long?> = _lastBackupAtMs.asStateFlow()

    // -----------------------------------------------------------------------
    // Scanner Queue & Editor State
    // -----------------------------------------------------------------------

    private val _queuedPages = MutableStateFlow<List<Bitmap>>(emptyList())
    val queuedPages: StateFlow<List<Bitmap>> = _queuedPages.asStateFlow()

    private val _selectedPageIndex = MutableStateFlow(0)
    val selectedPageIndex: StateFlow<Int> = _selectedPageIndex.asStateFlow()

    private val _scanMode = MutableStateFlow(ScanMode.FAST)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _smartPreset = MutableStateFlow(SmartPreset.LAYOUT)
    val smartPreset: StateFlow<SmartPreset> = _smartPreset.asStateFlow()

    private val _mergeContinuous = MutableStateFlow(true)
    val mergeContinuous: StateFlow<Boolean> = _mergeContinuous.asStateFlow()

    private val _customNote = MutableStateFlow("")
    val customNote: StateFlow<String> = _customNote.asStateFlow()

    private val _activeScanId = MutableStateFlow<String?>(null)
    val activeScanId: StateFlow<String?> = _activeScanId.asStateFlow()

    private val _editorTitle = MutableStateFlow("")
    val editorTitle: StateFlow<String> = _editorTitle.asStateFlow()

    private val _editorMarkdown = MutableStateFlow("")
    val editorMarkdown: StateFlow<String> = _editorMarkdown.asStateFlow()

    private val _lowConfidenceTerms = MutableStateFlow<List<String>>(emptyList())
    val lowConfidenceTerms: StateFlow<List<String>> = _lowConfidenceTerms.asStateFlow()

    private val _hasTable = MutableStateFlow(false)
    val hasTable: StateFlow<Boolean> = _hasTable.asStateFlow()

    private val _scanRunState = MutableStateFlow<ScanRunState>(ScanRunState.Idle)
    val scanRunState: StateFlow<ScanRunState> = _scanRunState.asStateFlow()

    private val _lastScanDurationSeconds = MutableStateFlow<Double?>(null)
    val lastScanDurationSeconds: StateFlow<Double?> = _lastScanDurationSeconds.asStateFlow()

    // -----------------------------------------------------------------------
    // Studio Image Generation & Edit State (gemini-nano-banana-2.1)
    // -----------------------------------------------------------------------

    private val _studioIsEditMode = MutableStateFlow(false)
    val studioIsEditMode: StateFlow<Boolean> = _studioIsEditMode.asStateFlow()

    private val _studioInputBitmap = MutableStateFlow<Bitmap?>(null)
    val studioInputBitmap: StateFlow<Bitmap?> = _studioInputBitmap.asStateFlow()

    private val _studioPrompt = MutableStateFlow("")
    val studioPrompt: StateFlow<String> = _studioPrompt.asStateFlow()

    private val _studioAspectRatio = MutableStateFlow(AspectRatio.SQUARE_1x1)
    val studioAspectRatio: StateFlow<AspectRatio> = _studioAspectRatio.asStateFlow()

    private val _studioState = MutableStateFlow<ImageStudioState>(ImageStudioState.Idle)
    val studioState: StateFlow<ImageStudioState> = _studioState.asStateFlow()

    // -----------------------------------------------------------------------
    // Status & Early Access Lifecycle
    // -----------------------------------------------------------------------

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        viewModelScope.launch {
            profileState.collect { state ->
                val profile = (state as? UiState.Success)?.data ?: return@collect
                if (profile.isSubscriptionExpired() && profile.subscriptionStatus != "expired") {
                    repository.markSubscriptionExpired().onSuccess {
                        _statusMessage.value = "Your 3-month Early Access Pro pass has ended. Workspace switched to Free tier."
                    }
                }
            }
        }
        if (backupRepository != null) {
            viewModelScope.launch {
                scansState.collect { state ->
                    val scans = (state as? UiState.Success)?.data ?: return@collect
                    if (scans.isNotEmpty()) {
                        val mirrored = backupRepository.mirrorCloudScans(currentUserId, scans)
                        if (mirrored > 0) {
                            _lastBackupAtMs.value = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
    }

    fun ensureProfile(displayName: String, email: String?) {
        viewModelScope.launch {
            repository.ensureUserProfile(displayName, email)
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Unable to initialize workspace profile."
                }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun addPages(bitmaps: List<Bitmap>, maxAllowed: Int) {
        if (bitmaps.isEmpty()) return
        val current = _queuedPages.value
        val availableSlots = (maxAllowed - current.size).coerceAtLeast(0)
        if (availableSlots == 0) {
            _statusMessage.value = "Batch limit ($maxAllowed pages) reached for your current plan."
            return
        }
        val accepted = bitmaps.take(availableSlots)
        _queuedPages.value = current + accepted
        if (current.isEmpty()) {
            _selectedPageIndex.value = 0
        }
        if (accepted.size < bitmaps.size) {
            _statusMessage.value = "Added ${accepted.size} page(s). Upgrade plan for larger batches."
        }
    }

    fun selectPage(index: Int) {
        if (index in _queuedPages.value.indices) {
            _selectedPageIndex.value = index
        }
    }

    fun removePage(index: Int) {
        val current = _queuedPages.value.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        _queuedPages.value = current
        _selectedPageIndex.value = (_selectedPageIndex.value).coerceIn(0, (current.size - 1).coerceAtLeast(0))
    }

    fun clearQueue() {
        _queuedPages.value = emptyList()
        _selectedPageIndex.value = 0
    }

    fun moveSelectedPageEarlier() {
        val idx = _selectedPageIndex.value
        val current = _queuedPages.value.toMutableList()
        if (idx <= 0 || idx >= current.size) return
        val item = current.removeAt(idx)
        current.add(idx - 1, item)
        _queuedPages.value = current
        _selectedPageIndex.value = idx - 1
    }

    fun moveSelectedPageLater() {
        val idx = _selectedPageIndex.value
        val current = _queuedPages.value.toMutableList()
        if (idx < 0 || idx >= current.lastIndex) return
        val item = current.removeAt(idx)
        current.add(idx + 1, item)
        _queuedPages.value = current
        _selectedPageIndex.value = idx + 1
    }

    fun reversePageSequence() {
        val current = _queuedPages.value
        if (current.size <= 1) return
        val oldIdx = _selectedPageIndex.value
        _queuedPages.value = current.reversed()
        _selectedPageIndex.value = (current.lastIndex - oldIdx).coerceIn(0, current.lastIndex)
        _statusMessage.value = "Reversed batch sequence (${current.size} pages)."
    }

    fun rotateSelectedPage() = transformSelectedPage { ImagePreprocessor.rotate90(it) }
    fun enhanceSelectedPage() = transformSelectedPage { ImagePreprocessor.autoEnhanceDocument(it) }
    fun trimSelectedPage() = transformSelectedPage { ImagePreprocessor.trimMargins(it) }

    private fun transformSelectedPage(transform: (Bitmap) -> Bitmap) {
        val idx = _selectedPageIndex.value
        val current = _queuedPages.value
        if (idx !in current.indices) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.Default) { transform(current[idx]) }
            val list = _queuedPages.value.toMutableList()
            if (idx in list.indices) {
                list[idx] = updated
                _queuedPages.value = list
            }
        }
    }

    fun setScanMode(mode: ScanMode) {
        _scanMode.value = mode
        if (mode == ScanMode.TABLE && _smartPreset.value == SmartPreset.LAYOUT) {
            _smartPreset.value = SmartPreset.TABLE
        }
    }

    fun setSmartPreset(preset: SmartPreset) {
        _smartPreset.value = preset
    }

    fun setMergeContinuous(continuous: Boolean) {
        _mergeContinuous.value = continuous
    }

    fun setCustomNote(note: String) {
        _customNote.value = note
    }

    fun updateEditorContent(title: String, markdown: String) {
        _editorTitle.value = title
        _editorMarkdown.value = markdown
        _hasTable.value = MarkdownParser.extractFirstTable(markdown) != null
    }

    fun updateTableCell(rowIdx: Int, colIdx: Int, value: String) {
        val existing = MarkdownParser.extractFirstTable(_editorMarkdown.value)?.toMutableList() ?: return
        if (rowIdx !in existing.indices) return
        val row = existing[rowIdx].toMutableList()
        while (row.size <= colIdx) row.add("")
        row[colIdx] = value
        existing[rowIdx] = row
        _editorMarkdown.value = MarkdownParser.replaceFirstTable(_editorMarkdown.value, existing)
    }

    fun addTableRow() {
        val existing = MarkdownParser.extractFirstTable(_editorMarkdown.value)?.toMutableList()
        if (existing != null && existing.isNotEmpty()) {
            val cols = existing.maxOf { it.size }.coerceAtLeast(1)
            existing.add(List(cols) { "" })
            _editorMarkdown.value = MarkdownParser.replaceFirstTable(_editorMarkdown.value, existing)
        } else {
            val seedTable = "| Item | Value |\n| --- | --- |\n| New entry | 0.00 |"
            _editorMarkdown.value = if (_editorMarkdown.value.isBlank()) {
                seedTable
            } else {
                "${_editorMarkdown.value.trimEnd()}\n\n$seedTable"
            }
            _hasTable.value = true
        }
    }

    fun runBatchScan() {
        val pages = _queuedPages.value
        if (pages.isEmpty()) {
            _statusMessage.value = "Add at least one photo or scan page first."
            return
        }
        val profile = (profileState.value as? UiState.Success)?.data
        if (profile == null) {
            _statusMessage.value = "Workspace profile is still syncing. Please wait a moment."
            return
        }
        val requiredCredits = pages.size.toLong()
        if (profile.totalRemainingCredits < requiredCredits) {
            _scanRunState.value = ScanRunState.Error(
                "You need $requiredCredits scan credit(s), but have ${profile.totalRemainingCredits} remaining. Open Plans & Sync to top up."
            )
            return
        }

        val mode = _scanMode.value
        val preset = _smartPreset.value
        val merge = _mergeContinuous.value
        val note = _customNote.value
        val startedAtMs = System.currentTimeMillis()
        val estPerPageSec = when (mode) {
            ScanMode.FAST -> 5
            ScanMode.PRECISION -> 8
            ScanMode.TABLE -> 7
        }
        val estimatedTotalSec = (4 + pages.size * estPerPageSec).coerceIn(6, 55)

        viewModelScope.launch {
            _lastScanDurationSeconds.value = null
            _scanRunState.value = ScanRunState.Processing(
                pageCount = pages.size,
                startedAtMs = startedAtMs,
                estimatedSeconds = estimatedTotalSec
            )
            val ocrResult = aiEngine.transcribePages(pages, mode, preset, merge, note)
            val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 100.0).toInt() / 10.0
            ocrResult.onSuccess { extracted ->
                _editorTitle.value = extracted.title
                _editorMarkdown.value = extracted.markdown
                _hasTable.value = extracted.hasTable
                _lowConfidenceTerms.value = extracted.lowConfidenceTerms
                _lastScanDurationSeconds.value = elapsedSec
                _scanRunState.value = ScanRunState.Idle

                repository.consumeScanCredits(profile, requiredCredits)

                val scanDoc = ScanDocument(
                    userId = currentUserId,
                    title = SecurityGuard.sanitizeSafeText(extracted.title),
                    markdown = SecurityGuard.sanitizeSafeText(extracted.markdown),
                    mode = mode.id,
                    preset = preset.id,
                    pageCount = pages.size.toLong(),
                    hasTable = extracted.hasTable,
                    zeroRetention = profile.zeroRetentionEnabled,
                    lowConfidenceTerms = extracted.lowConfidenceTerms
                )

                var savedId = ""
                if (!profile.zeroRetentionEnabled) {
                    val cloudSave = repository.saveScan(scanDoc)
                    cloudSave.onSuccess { id ->
                        savedId = id
                        _activeScanId.value = id
                    }
                    backupRepository?.saveFailoverBackup(
                        userId = currentUserId,
                        scan = scanDoc.copy(id = savedId),
                        cloudSynced = cloudSave.isSuccess
                    )?.also {
                        _lastBackupAtMs.value = System.currentTimeMillis()
                    }
                    localHistoryRepository?.saveLocalScan(
                        userId = currentUserId,
                        cloudScanId = savedId,
                        title = scanDoc.title,
                        rawText = scanDoc.markdown,
                        capturedPreview = pages.firstOrNull()
                    )
                    _statusMessage.value = if (cloudSave.isSuccess) {
                        "Done in ${elapsedSec}s (${pages.size} page(s)), saved & backed up."
                    } else {
                        "Done in ${elapsedSec}s. Cloud unavailable; saved to local backup database."
                    }
                } else {
                    _activeScanId.value = null
                    _statusMessage.value = "Done in ${elapsedSec}s. Zero-Retention active: nothing saved to cloud or backup."
                }

                val workflows = (workflowsState.value as? UiState.Success)?.data.orEmpty()
                    .filter { it.enabled && it.endpointUrl.isNotBlank() }
                if (workflows.isNotEmpty() && profile.tier.allowsWorkflows) {
                    val dispatchDoc = scanDoc.copy(id = savedId.ifBlank { "ephemeral" })
                    workflows.forEach { wf ->
                        val res = WorkflowDispatcher.dispatchForScan(wf, dispatchDoc)
                        repository.updateWorkflowStatus(
                            workflowId = wf.id,
                            lastStatus = if (res.delivered) "delivered" else "failed"
                        )
                    }
                }
            }.onFailure { err ->
                _scanRunState.value = ScanRunState.Error(err.localizedMessage ?: "Scan failed.")
            }
        }
    }

    fun saveCurrentEdits() {
        val markdown = SecurityGuard.sanitizeSafeText(_editorMarkdown.value).trim()
        if (markdown.isEmpty()) {
            _statusMessage.value = "Nothing to save yet."
            return
        }
        val profile = (profileState.value as? UiState.Success)?.data
        if (profile?.zeroRetentionEnabled == true) {
            _statusMessage.value = "Zero-Data-Retention is active. Disable it in Pass & Sync to save to cloud."
            return
        }
        val title = SecurityGuard.sanitizeSafeText(_editorTitle.value).trim().ifBlank { "Untitled Scan" }
        val existingId = _activeScanId.value ?: ""
        viewModelScope.launch {
            val doc = ScanDocument(
                id = existingId,
                userId = currentUserId,
                title = title,
                markdown = markdown,
                mode = _scanMode.value.id,
                preset = _smartPreset.value.id,
                pageCount = _queuedPages.value.size.coerceAtLeast(1).toLong(),
                hasTable = _hasTable.value,
                zeroRetention = false,
                lowConfidenceTerms = _lowConfidenceTerms.value
            )
            val saveRes = repository.saveScan(doc)
            saveRes
                .onSuccess { newId ->
                    _activeScanId.value = newId
                    backupRepository?.saveFailoverBackup(
                        userId = currentUserId,
                        scan = doc.copy(id = newId),
                        cloudSynced = true
                    )?.also { _lastBackupAtMs.value = System.currentTimeMillis() }
                    _statusMessage.value = "Saved to cloud & local backup database."
                }
                .onFailure { err ->
                    backupRepository?.saveFailoverBackup(
                        userId = currentUserId,
                        scan = doc,
                        cloudSynced = false
                    )?.also { _lastBackupAtMs.value = System.currentTimeMillis() }
                    _statusMessage.value = "Cloud save failed (${err.localizedMessage ?: "offline"}); stored in local backup database."
                }
        }
    }

    fun openHistoryScan(scan: ScanDocument) {
        _activeScanId.value = scan.id
        _editorTitle.value = scan.title
        _editorMarkdown.value = scan.markdown
        _hasTable.value = scan.hasTable || MarkdownParser.extractFirstTable(scan.markdown) != null
        _lowConfidenceTerms.value = scan.lowConfidenceTerms
        _scanRunState.value = ScanRunState.Idle
    }

    fun openLocalHistoryScan(doc: ScannedDocumentEntity) {
        _activeScanId.value = doc.cloudScanId.ifBlank { null }
        _editorTitle.value = doc.title
        _editorMarkdown.value = doc.rawText
        _hasTable.value = MarkdownParser.extractFirstTable(doc.rawText) != null
        _lowConfidenceTerms.value = emptyList()
        _scanRunState.value = ScanRunState.Idle
        doc.capturedImagePath?.let { path ->
            viewModelScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
                }
                if (bmp != null) {
                    _queuedPages.value = listOf(bmp)
                    _selectedPageIndex.value = 0
                }
            }
        }
    }

    fun deleteLocalHistoryScan(doc: ScannedDocumentEntity) {
        val repo = localHistoryRepository ?: return
        viewModelScope.launch {
            repo.deleteLocalScan(currentUserId, doc)
            _statusMessage.value = "Removed from local device history."
        }
    }

    fun deleteHistoryScan(scanId: String) {
        viewModelScope.launch {
            repository.deleteScan(scanId)
                .onSuccess {
                    if (_activeScanId.value == scanId) _activeScanId.value = null
                    _statusMessage.value = "Scan removed from library."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Could not delete scan."
                }
        }
    }

    // -----------------------------------------------------------------------
    // Image Studio Actions (gemini-nano-banana-2.1)
    // -----------------------------------------------------------------------

    fun setStudioMode(isEdit: Boolean) {
        _studioIsEditMode.value = isEdit
        _studioState.value = ImageStudioState.Idle
    }

    fun setStudioInputBitmap(bitmap: Bitmap?) {
        _studioInputBitmap.value = bitmap
        if (bitmap != null) _studioIsEditMode.value = true
    }

    fun setStudioPrompt(prompt: String) {
        _studioPrompt.value = prompt
    }

    fun setStudioAspectRatio(ratio: AspectRatio) {
        _studioAspectRatio.value = ratio
    }

    fun sendSelectedPageToStudio() {
        val idx = _selectedPageIndex.value
        val page = _queuedPages.value.getOrNull(idx) ?: return
        _studioInputBitmap.value = page
        _studioIsEditMode.value = true
        _statusMessage.value = "Loaded scan page into Image Studio."
    }

    fun sendStudioResultToScanner(maxAllowed: Int) {
        val ready = _studioState.value as? ImageStudioState.Ready ?: return
        addPages(listOf(ready.bitmap), maxAllowed)
        _statusMessage.value = "Studio image added to scanner queue."
    }

    fun runStudioGeneration() {
        val prompt = _studioPrompt.value.trim()
        val isEdit = _studioIsEditMode.value
        val input = _studioInputBitmap.value
        if (isEdit && input == null) {
            _studioState.value = ImageStudioState.Error("Select a photo to edit first.")
            return
        }
        if (prompt.isEmpty()) {
            _studioState.value = ImageStudioState.Error("Enter a clear description for the image.")
            return
        }
        val aspect = _studioAspectRatio.value
        viewModelScope.launch {
            _studioState.value = ImageStudioState.Generating(isEdit)
            val result = if (isEdit && input != null) {
                aiEngine.editStudioImage(input, prompt, aspect)
            } else {
                aiEngine.generateStudioImage(prompt, aspect)
            }
            result.onSuccess { bitmap ->
                _studioState.value = ImageStudioState.Ready(bitmap, prompt)
            }.onFailure { err ->
                _studioState.value = ImageStudioState.Error(err.localizedMessage ?: "Studio generation failed.")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Privacy Preferences, Early Access Expiration & Workflows
    // -----------------------------------------------------------------------

    fun updateZeroRetention(zeroRetention: Boolean) {
        viewModelScope.launch {
            repository.updateWorkspacePreferences(zeroRetention)
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Could not save privacy settings."
                }
        }
    }

    fun handleSubscriptionExpired() {
        viewModelScope.launch {
            repository.markSubscriptionExpired()
                .onSuccess {
                    _statusMessage.value = "Your 3-month Early Access Pro pass ended. Reverted to Free tier."
                }
        }
    }

    fun createWorkflow(name: String, trigger: String, destinationType: String, endpointUrl: String) {
        viewModelScope.launch {
            repository.createWorkflow(name, trigger, destinationType, endpointUrl)
                .onSuccess { _statusMessage.value = "Workflow rule saved." }
                .onFailure { err -> _statusMessage.value = err.localizedMessage ?: "Failed to create workflow." }
        }
    }

    fun toggleWorkflowEnabled(workflow: WorkflowAutomation) {
        viewModelScope.launch {
            repository.updateWorkflowStatus(
                workflowId = workflow.id,
                lastStatus = workflow.lastStatus,
                enabled = !workflow.enabled
            )
        }
    }

    fun testWorkflowNow(workflow: WorkflowAutomation) {
        val sampleOrCurrent = ScanDocument(
            id = _activeScanId.value ?: "test_dispatch",
            userId = currentUserId,
            title = _editorTitle.value.ifBlank { "Lenscribe Webhook Verification" },
            markdown = _editorMarkdown.value.ifBlank {
                "| Item | Amount |\n| --- | --- |\n| Verification Entry | 10.00 |"
            },
            mode = _scanMode.value.id,
            preset = _smartPreset.value.id,
            pageCount = 1L,
            hasTable = true,
            zeroRetention = false
        )
        viewModelScope.launch {
            val result = WorkflowDispatcher.dispatchForScan(workflow, sampleOrCurrent)
            repository.updateWorkflowStatus(
                workflowId = workflow.id,
                lastStatus = if (result.delivered) "delivered" else "failed"
            )
            _statusMessage.value = "${workflow.name}: ${result.message}"
        }
    }

    fun deleteWorkflow(workflowId: String) {
        viewModelScope.launch {
            repository.deleteWorkflow(workflowId)
                .onSuccess { _statusMessage.value = "Workflow deleted." }
        }
    }

    // -----------------------------------------------------------------------
    // Local Room Backup Database Sync, Restore & Portable JSON Snapshot
    // -----------------------------------------------------------------------

    fun syncBackupNow() {
        val repo = backupRepository ?: return
        val cloudScans = (scansState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            val mirrored = repo.mirrorCloudScans(currentUserId, cloudScans)
            _lastBackupAtMs.value = System.currentTimeMillis()
            _statusMessage.value = if (mirrored > 0) {
                "Backed up $mirrored scan(s) to local SQLite database."
            } else {
                "Local backup database is up to date."
            }
        }
    }

    fun restoreMissingFromBackup() {
        val repo = backupRepository ?: return
        val cloudIds = (scansState.value as? UiState.Success)?.data.orEmpty().map { it.id }.toSet()
        viewModelScope.launch {
            repo.restoreMissingToCloud(currentUserId, cloudIds, repository)
                .onSuccess { restored ->
                    _statusMessage.value = if (restored > 0) {
                        "Restored $restored scan(s) from local backup to cloud library."
                    } else {
                        "All backed-up scans are already synced in cloud."
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Could not restore backup."
                }
        }
    }

    suspend fun buildPortableBackupJson(): ByteArray {
        return backupRepository?.exportPortableJson(currentUserId)
            ?: """{"app":"Lenscribe","schemaVersion":1,"scans":[]}""".toByteArray(Charsets.UTF_8)
    }

    fun importPortableBackupJson(jsonBytes: ByteArray) {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            repo.importPortableJson(currentUserId, jsonBytes)
                .onSuccess { count ->
                    _lastBackupAtMs.value = System.currentTimeMillis()
                    _statusMessage.value = "Imported $count scan(s) into backup DB. Tap Restore to Cloud to sync."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Invalid backup JSON file."
                }
        }
    }

    fun clearLocalBackups() {
        val repo = backupRepository ?: return
        viewModelScope.launch {
            repo.clearUserBackups(currentUserId)
            _statusMessage.value = "Local backup database cleared."
        }
    }

    fun deleteWorkspaceAccountAndData(onComplete: () -> Unit) {
        viewModelScope.launch {
            val cloudDelete = repository.deleteAllUserCloudData()
            backupRepository?.clearUserBackups(currentUserId)
            localHistoryRepository?.clearUserLocalHistory(currentUserId)
            clearQueue()
            _editorTitle.value = ""
            _editorMarkdown.value = ""
            _activeScanId.value = null

            if (cloudDelete.isFailure) {
                _statusMessage.value = cloudDelete.exceptionOrNull()?.localizedMessage
                    ?: "Could not delete cloud records. Check your connection and try again."
                return@launch
            }

            try {
                FirebaseAuth.getInstance().currentUser?.delete()?.await()
                _statusMessage.value = "Account and all stored data permanently deleted."
                onComplete()
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                _statusMessage.value = "Cloud and local data deleted. Sign in again if you also want to purge your auth login."
                onComplete()
            } catch (e: Exception) {
                _statusMessage.value = "Cloud and local data deleted."
                onComplete()
            }
        }
    }

    private companion object {
        const val TAG = "LenscribeVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
