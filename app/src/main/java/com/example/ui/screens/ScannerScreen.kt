package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.imaging.ImagePreprocessor
import com.example.model.PlanTier
import com.example.model.ScanMode
import com.example.model.SmartPreset
import com.example.model.UserProfile
import com.example.ui.components.AnnotatedVerificationText
import com.example.ui.components.CameraDocumentScannerDialog
import com.example.ui.components.CreditBalancePill
import com.example.ui.components.ExportDocumentBottomSheet
import com.example.ui.components.MiniSpreadsheetEditor
import com.example.ui.components.ScanningLaserOverlay
import com.example.ui.components.ZeroRetentionBanner
import com.example.ui.components.copyToClipboard
import com.example.ui.components.pressScale
import com.example.ui.components.smoothCardSize
import com.example.viewmodel.ScanRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScannerScreen(
    profile: UserProfile?,
    queuedPages: List<Bitmap>,
    selectedPageIndex: Int,
    scanMode: ScanMode,
    smartPreset: SmartPreset,
    mergeContinuous: Boolean,
    customNote: String,
    editorTitle: String,
    editorMarkdown: String,
    lowConfidenceTerms: List<String>,
    hasTable: Boolean,
    scanRunState: ScanRunState,
    lastScanDurationSeconds: Double?,
    onAddPages: (List<Bitmap>) -> Unit,
    onSelectPage: (Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onMovePageEarlier: () -> Unit,
    onMovePageLater: () -> Unit,
    onReverseSequence: () -> Unit,
    onClearQueue: () -> Unit,
    onRotatePage: () -> Unit,
    onEnhancePage: () -> Unit,
    onTrimPage: () -> Unit,
    onSendToStudio: () -> Unit,
    onSelectMode: (ScanMode) -> Unit,
    onSelectPreset: (SmartPreset) -> Unit,
    onToggleMerge: (Boolean) -> Unit,
    onCustomNoteChange: (String) -> Unit,
    onRunScan: () -> Unit,
    onEditorChanged: (String, String) -> Unit,
    onTableCellChanged: (Int, Int, String) -> Unit,
    onAddTableRow: () -> Unit,
    onSaveEdits: () -> Unit,
    onNavigateToPlans: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val tier = profile?.tier ?: PlanTier.FREE
    val maxBatch = tier.maxBatchPages

    var showExportSheet by remember { mutableStateOf(false) }
    var showNoteInput by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }
    var activeResultTab by remember { mutableIntStateOf(0) } // 0=Verify, 1=Edit, 2=Table

    LaunchedEffect(lastScanDurationSeconds) {
        if (lastScanDurationSeconds != null) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val bitmaps = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> ImagePreprocessor.decodeScaledBitmap(context, uri) }
                }
                onAddPages(bitmaps)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionDenied = false
            showCameraScanner = true
        } else {
            cameraPermissionDenied = true
            onMessage("Camera permission is needed to scan documents directly.")
        }
    }

    fun openCameraScanner() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            cameraPermissionDenied = false
            showCameraScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (showCameraScanner) {
        val remainingSlots = (maxBatch - queuedPages.size).coerceAtLeast(1)
        CameraDocumentScannerDialog(
            maxRemainingPages = remainingSlots,
            onDismiss = { showCameraScanner = false },
            onPagesCaptured = { bitmaps ->
                showCameraScanner = false
                onAddPages(bitmaps)
            },
            onError = { err -> onMessage(err) }
        )
    }

    if (showExportSheet && editorMarkdown.isNotBlank()) {
        ExportDocumentBottomSheet(
            title = editorTitle.ifBlank { "Lenscribe Document" },
            markdown = editorMarkdown,
            allowsAllExports = tier.allowsAllExports,
            onDismiss = { showExportSheet = false },
            onNavigateToPlans = onNavigateToPlans,
            onMessage = onMessage
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isWide = maxWidth >= 820.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scanner Workspace",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${queuedPages.size}/$maxBatch pages • ${tier.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (lastScanDurationSeconds != null && scanRunState !is ScanRunState.Processing) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Done in ${lastScanDurationSeconds}s",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
                CreditBalancePill(profile = profile, onClick = onNavigateToPlans)
            }

            if (profile?.zeroRetentionEnabled == true) {
                ZeroRetentionBanner(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (isWide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CaptureAndConfigPane(
                        queuedPages = queuedPages,
                        selectedPageIndex = selectedPageIndex,
                        maxBatch = maxBatch,
                        scanMode = scanMode,
                        smartPreset = smartPreset,
                        mergeContinuous = mergeContinuous,
                        customNote = customNote,
                        showNoteInput = showNoteInput,
                        cameraPermissionDenied = cameraPermissionDenied,
                        scanRunState = scanRunState,
                        onPickPhotos = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onLaunchCamera = ::openCameraScanner,
                        onSelectPage = onSelectPage,
                        onRemovePage = onRemovePage,
                        onMovePageEarlier = onMovePageEarlier,
                        onMovePageLater = onMovePageLater,
                        onReverseSequence = onReverseSequence,
                        onClearQueue = onClearQueue,
                        onRotatePage = onRotatePage,
                        onEnhancePage = onEnhancePage,
                        onTrimPage = onTrimPage,
                        onSendToStudio = onSendToStudio,
                        onSelectMode = onSelectMode,
                        onSelectPreset = onSelectPreset,
                        onToggleMerge = onToggleMerge,
                        onToggleNoteInput = { showNoteInput = !showNoteInput },
                        onCustomNoteChange = onCustomNoteChange,
                        onRunScan = onRunScan,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp)
                    )

                    ResultWorkspacePane(
                        selectedBitmap = queuedPages.getOrNull(selectedPageIndex),
                        editorTitle = editorTitle,
                        editorMarkdown = editorMarkdown,
                        lowConfidenceTerms = lowConfidenceTerms,
                        hasTable = hasTable,
                        activeTab = activeResultTab,
                        onSelectTab = { activeResultTab = it },
                        onEditorChanged = onEditorChanged,
                        onTableCellChanged = onTableCellChanged,
                        onAddTableRow = onAddTableRow,
                        onSaveEdits = onSaveEdits,
                        onCopy = {
                            copyToClipboard(context, editorTitle, editorMarkdown)
                            onMessage("Copied to clipboard.")
                        },
                        onOpenExport = { showExportSheet = true },
                        modifier = Modifier
                            .weight(1.1f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CaptureAndConfigPane(
                        queuedPages = queuedPages,
                        selectedPageIndex = selectedPageIndex,
                        maxBatch = maxBatch,
                        scanMode = scanMode,
                        smartPreset = smartPreset,
                        mergeContinuous = mergeContinuous,
                        customNote = customNote,
                        showNoteInput = showNoteInput,
                        cameraPermissionDenied = cameraPermissionDenied,
                        scanRunState = scanRunState,
                        onPickPhotos = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onLaunchCamera = ::openCameraScanner,
                        onSelectPage = onSelectPage,
                        onRemovePage = onRemovePage,
                        onMovePageEarlier = onMovePageEarlier,
                        onMovePageLater = onMovePageLater,
                        onReverseSequence = onReverseSequence,
                        onClearQueue = onClearQueue,
                        onRotatePage = onRotatePage,
                        onEnhancePage = onEnhancePage,
                        onTrimPage = onTrimPage,
                        onSendToStudio = onSendToStudio,
                        onSelectMode = onSelectMode,
                        onSelectPreset = onSelectPreset,
                        onToggleMerge = onToggleMerge,
                        onToggleNoteInput = { showNoteInput = !showNoteInput },
                        onCustomNoteChange = onCustomNoteChange,
                        onRunScan = onRunScan
                    )

                    AnimatedVisibility(
                        visible = editorMarkdown.isNotBlank(),
                        enter = expandVertically(tween(240)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(150))
                    ) {
                        ResultWorkspacePane(
                            selectedBitmap = queuedPages.getOrNull(selectedPageIndex),
                            editorTitle = editorTitle,
                            editorMarkdown = editorMarkdown,
                            lowConfidenceTerms = lowConfidenceTerms,
                            hasTable = hasTable,
                            activeTab = activeResultTab,
                            onSelectTab = { activeResultTab = it },
                            onEditorChanged = onEditorChanged,
                            onTableCellChanged = onTableCellChanged,
                            onAddTableRow = onAddTableRow,
                            onSaveEdits = onSaveEdits,
                            onCopy = {
                                copyToClipboard(context, editorTitle, editorMarkdown)
                                onMessage("Copied to clipboard.")
                            },
                            onOpenExport = { showExportSheet = true }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanProgressTimerBanner(
    processing: ScanRunState.Processing
) {
    var nowMs by remember(processing.startedAtMs) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(processing.startedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(200L)
        }
    }

    val elapsedSeconds = ((nowMs - processing.startedAtMs).coerceAtLeast(0L) / 1000.0)
    val estTotal = processing.estimatedSeconds.coerceAtLeast(1)
    val remainingSec = (estTotal - elapsedSeconds.toInt()).coerceAtLeast(0)
    val progress by animateFloatAsState(
        targetValue = (elapsedSeconds.toFloat() / estTotal.toFloat()).coerceIn(0.05f, 0.96f),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "scan_timer_progress"
    )
    val elapsedFormatted = String.format(java.util.Locale.US, "%.1fs", elapsedSeconds)

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_timer_banner")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Scanning ${processing.pageCount} page(s) • $elapsedFormatted elapsed",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = if (remainingSec > 0) "~${remainingSec}s left" else "Finalizing...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }
    }
}

@Composable
private fun CaptureAndConfigPane(
    queuedPages: List<Bitmap>,
    selectedPageIndex: Int,
    maxBatch: Int,
    scanMode: ScanMode,
    smartPreset: SmartPreset,
    mergeContinuous: Boolean,
    customNote: String,
    showNoteInput: Boolean,
    cameraPermissionDenied: Boolean,
    scanRunState: ScanRunState,
    onPickPhotos: () -> Unit,
    onLaunchCamera: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onMovePageEarlier: () -> Unit,
    onMovePageLater: () -> Unit,
    onReverseSequence: () -> Unit,
    onClearQueue: () -> Unit,
    onRotatePage: () -> Unit,
    onEnhancePage: () -> Unit,
    onTrimPage: () -> Unit,
    onSendToStudio: () -> Unit,
    onSelectMode: (ScanMode) -> Unit,
    onSelectPreset: (SmartPreset) -> Unit,
    onToggleMerge: (Boolean) -> Unit,
    onToggleNoteInput: () -> Unit,
    onCustomNoteChange: (String) -> Unit,
    onRunScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Capture Source Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.smoothCardSize()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickPhotos,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("pick_photos_button")
                    ) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Photos")
                    }
                    Button(
                        onClick = onLaunchCamera,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("capture_camera_button")
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Live Scanner")
                    }
                }

                if (cameraPermissionDenied) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Camera permission declined. Grant camera access or use Add Photos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onLaunchCamera) {
                                Text("Grant")
                            }
                        }
                    }
                }

                if (queuedPages.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DocumentScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "Snap or upload receipts, contracts, or handwritten notes",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Use Live Scanner for rapid multi-page capture or pick up to $maxBatch pages to reorder sequence (#1, #2...) before scanning.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Batch Queue (${queuedPages.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = onClearQueue,
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text("Clear")
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(queuedPages) { index, bmp ->
                            val selected = index == selectedPageIndex
                            val thumbScale by animateFloatAsState(
                                targetValue = if (selected) 1.0f else 0.94f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "thumb_scale_$index"
                            )
                            val borderWidth by animateDpAsState(
                                targetValue = if (selected) 2.dp else 1.dp,
                                animationSpec = tween(160),
                                label = "thumb_border_$index"
                            )
                            val borderColor by animateColorAsState(
                                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                animationSpec = tween(160),
                                label = "thumb_border_color_$index"
                            )
                            val badgeBg by animateColorAsState(
                                targetValue = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                                animationSpec = tween(160),
                                label = "thumb_badge_bg_$index"
                            )
                            Box(
                                modifier = Modifier
                                    .animateItem()
                                    .graphicsLayer {
                                        scaleX = thumbScale
                                        scaleY = thumbScale
                                    }
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .border(
                                        width = borderWidth,
                                        color = borderColor,
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .clickable { onSelectPage(index) }
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Queued page ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    color = badgeBg,
                                    shape = MaterialTheme.shapes.extraSmall,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(3.dp)
                                ) {
                                    Text(
                                        text = "#${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(22.dp)
                                        .clickable { onRemovePage(index) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove page ${index + 1}",
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(3.dp)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = queuedPages.size > 1,
                        enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(160)) + fadeOut(tween(140))
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("page_sequence_bar")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Page Sequence • Selected: Page ${selectedPageIndex + 1} of ${queuedPages.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = onMovePageEarlier,
                                        enabled = selectedPageIndex > 0,
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("move_page_earlier_button")
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Move Earlier")
                                    }
                                    OutlinedButton(
                                        onClick = onMovePageLater,
                                        enabled = selectedPageIndex < queuedPages.lastIndex,
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("move_page_later_button")
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Move Later")
                                    }
                                    OutlinedButton(
                                        onClick = onReverseSequence,
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 48.dp)
                                            .testTag("reverse_sequence_button")
                                    ) {
                                        Icon(
                                            Icons.Filled.SwapVert,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reverse Order")
                                    }
                                }
                            }
                        }
                    }

                    val activeBitmap = queuedPages.getOrNull(selectedPageIndex)
                    if (activeBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                        ) {
                            AnimatedContent(
                                targetState = activeBitmap,
                                transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(140))) },
                                label = "active_page_preview"
                            ) { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Selected page preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (scanRunState is ScanRunState.Processing) {
                                ScanningLaserOverlay()
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onEnhancePage,
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Enhance")
                            }
                            OutlinedButton(
                                onClick = onRotatePage,
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Rotate 90°")
                            }
                            OutlinedButton(
                                onClick = onTrimPage,
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Trim")
                            }
                            OutlinedButton(
                                onClick = onSendToStudio,
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Edit in Studio")
                            }
                        }
                    }
                }
            }
        }

        // Outcome Mode & Presets Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.smoothCardSize()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Scanning Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScanMode.entries.forEach { mode ->
                        val selected = mode == scanMode
                        val modeInteraction = remember { MutableInteractionSource() }
                        val cardBg by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = tween(180),
                            label = "mode_bg_${mode.id}"
                        )
                        val cardBorder by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            animationSpec = tween(180),
                            label = "mode_border_${mode.id}"
                        )
                        val borderW by animateDpAsState(
                            targetValue = if (selected) 1.5.dp else 1.dp,
                            animationSpec = tween(180),
                            label = "mode_border_w_${mode.id}"
                        )
                        Surface(
                            onClick = { onSelectMode(mode) },
                            interactionSource = modeInteraction,
                            shape = MaterialTheme.shapes.medium,
                            color = cardBg,
                            border = BorderStroke(borderW, cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .pressScale(modeInteraction)
                                .defaultMinSize(minHeight = 68.dp)
                                .testTag("scan_mode_${mode.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (queuedPages.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Merge into continuous document",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Off inserts page dividers between sheets",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = mergeContinuous,
                            onCheckedChange = onToggleMerge
                        )
                    }
                }

                Text(
                    text = "Output Format Preset",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmartPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = preset == smartPreset,
                            onClick = { onSelectPreset(preset) },
                            label = { Text(preset.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleNoteInput() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Optional formatting note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = if (showNoteInput) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Toggle custom formatting note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = showNoteInput) {
                    OutlinedTextField(
                        value = customNote,
                        onValueChange = onCustomNoteChange,
                        label = { Text("Formatting preference (e.g. Keep SKU column first)") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (scanRunState is ScanRunState.Processing) {
                    ScanProgressTimerBanner(processing = scanRunState)
                }

                if (scanRunState is ScanRunState.Error) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = scanRunState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                val isRunning = scanRunState is ScanRunState.Processing
                Button(
                    onClick = onRunScan,
                    enabled = queuedPages.isNotEmpty() && !isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("run_scan_button")
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Processing Batch...")
                    } else {
                        Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        val label = if (queuedPages.isEmpty()) "Queue Pages to Scan"
                        else "Scan ${queuedPages.size} Page(s) • ${queuedPages.size} cr"
                        Text(label, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultWorkspacePane(
    selectedBitmap: Bitmap?,
    editorTitle: String,
    editorMarkdown: String,
    lowConfidenceTerms: List<String>,
    hasTable: Boolean,
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    onEditorChanged: (String, String) -> Unit,
    onTableCellChanged: (Int, Int, String) -> Unit,
    onAddTableRow: () -> Unit,
    onSaveEdits: () -> Unit,
    onCopy: () -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wordCount = remember(editorMarkdown) {
        editorMarkdown.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .smoothCardSize()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = editorTitle.ifBlank { "Transcribed Output" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (editorMarkdown.isNotBlank()) {
                        Text(
                            text = "$wordCount words • ${editorMarkdown.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy text")
                    }
                    IconButton(
                        onClick = onSaveEdits,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("save_scan_button")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save edits")
                    }
                }
            }

            if (editorMarkdown.isBlank()) {
                Text(
                    text = "Run a scan or open a document from Library to verify, edit, or export to Word, Excel, PowerPoint, PDF, CSV, or PNG cards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val tabs = listOf("Verify", "Edit", if (hasTable) "Table" else "Grid")
                    tabs.forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = activeTab == idx,
                            onClick = { onSelectTab(idx) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                            icon = {
                                val ic = when (idx) {
                                    0 -> Icons.Filled.Visibility
                                    1 -> Icons.Filled.Edit
                                    else -> Icons.Filled.Tune
                                }
                                Icon(ic, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        ) {
                            Text(label)
                        }
                    }
                }

                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(130))) },
                    label = "result_tab_content"
                ) { tabIdx ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (tabIdx) {
                            0 -> {
                                if (lowConfidenceTerms.isNotEmpty()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Click-to-Verify: ${lowConfidenceTerms.size} faint/handwritten term(s) highlighted in amber.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                                if (selectedBitmap != null) {
                                    Image(
                                        bitmap = selectedBitmap.asImageBitmap(),
                                        contentDescription = "Original scan reference",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(170.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AnnotatedVerificationText(
                                        markdown = editorMarkdown,
                                        lowConfidenceTerms = lowConfidenceTerms,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                            1 -> {
                                OutlinedTextField(
                                    value = editorTitle,
                                    onValueChange = { onEditorChanged(it, editorMarkdown) },
                                    label = { Text("Document Title") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = editorMarkdown,
                                    onValueChange = { onEditorChanged(editorTitle, it) },
                                    label = { Text("Markdown Content") },
                                    minLines = 8,
                                    maxLines = 18,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("markdown_editor_input")
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            val cleaned = editorMarkdown
                                                .lines()
                                                .joinToString("\n") { it.trimEnd() }
                                                .replace(Regex("\n{3,}"), "\n\n")
                                                .trim()
                                            onEditorChanged(editorTitle, cleaned)
                                        }
                                    ) {
                                        Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Clean Spacing")
                                    }
                                }
                            }
                            else -> {
                                MiniSpreadsheetEditor(
                                    markdown = editorMarkdown,
                                    onCellChanged = onTableCellChanged,
                                    onAddRow = onAddTableRow
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onOpenExport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp)
                        .testTag("export_sheet_button")
                ) {
                    Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export (DOCX, XLSX, PPTX, PDF, CSV, Card)")
                }
            }
        }
    }
}
