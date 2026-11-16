package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.export.DocumentExporter
import com.example.imaging.ImagePreprocessor
import com.example.ui.components.StudioPulsePlaceholder
import com.example.ui.components.smoothCardSize
import com.example.viewmodel.ImageStudioState
import com.google.firebase.ai.type.AspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudioScreen(
    isEditMode: Boolean,
    inputBitmap: Bitmap?,
    prompt: String,
    aspectRatio: AspectRatio,
    studioState: ImageStudioState,
    onSetEditMode: (Boolean) -> Unit,
    onSetInputBitmap: (Bitmap?) -> Unit,
    onPromptChange: (String) -> Unit,
    onAspectRatioChange: (AspectRatio) -> Unit,
    onRunGeneration: () -> Unit,
    onSendResultToScanner: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    ImagePreprocessor.decodeScaledBitmap(context, uri)
                }
                if (bmp != null) {
                    onSetInputBitmap(bmp)
                }
            }
        }
    }

    var pendingSaveBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val savePngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bmp = pendingSaveBitmap ?: return@rememberLauncherForActivityResult
        pendingSaveBitmap = null
        if (uri != null) {
            scope.launch {
                runCatching {
                    val bytes = withContext(Dispatchers.Default) {
                        DocumentExporter.bitmapToPngBytes(bmp)
                    }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                        } ?: error("Cannot write destination PNG.")
                    }
                }.onSuccess {
                    onMessage("Saved studio PNG to device.")
                }.onFailure { err ->
                    onMessage(err.localizedMessage ?: "Failed to save image.")
                }
            }
        }
    }

    val aspectChoices = remember {
        listOf(
            AspectRatio.SQUARE_1x1 to "1:1",
            AspectRatio.LANDSCAPE_4x3 to "4:3",
            AspectRatio.LANDSCAPE_16x9 to "16:9",
            AspectRatio.PORTRAIT_3x4 to "3:4",
            AspectRatio.PORTRAIT_9x16 to "9:16"
        )
    }

    val starterPrompts = remember(isEditMode) {
        if (isEditMode) {
            listOf(
                "Clean up shadows and sharpen printed document text on white paper",
                "Convert whiteboard photo into crisp minimal high-contrast diagram",
                "Remove cluttered desk background and straighten page perspective"
            )
        } else {
            listOf(
                "Minimalist dark emerald presentation header banner for Q4 report",
                "Clean editorial spot illustration of an architectural ledger and fountain pen",
                "Isometric studio diagram of organized invoice folders on slate background"
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Create & Edit Images",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Generate presentation covers, visual cards, or clean up and restyle photos for your documents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isEditMode,
                    onClick = { onSetEditMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("studio_mode_create")
                ) {
                    Text("Create Image")
                }
                SegmentedButton(
                    selected = isEditMode,
                    onClick = { onSetEditMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("studio_mode_edit")
                ) {
                    Text("Edit Photo")
                }
            }

            AnimatedVisibility(
                visible = isEditMode,
                enter = expandVertically(tween(220)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(140))
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .smoothCardSize()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Source Photo",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedButton(
                                onClick = {
                                    pickPhotoLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (inputBitmap == null) "Choose Photo" else "Change")
                            }
                        }

                        if (inputBitmap != null) {
                            Image(
                                bitmap = inputBitmap.asImageBitmap(),
                                contentDescription = "Source photo to edit",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        } else {
                            Text(
                                text = "Pick a photo from your library or send any queued page from the Scanner tab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .smoothCardSize()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Aspect Ratio",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        aspectChoices.forEach { (ratio, label) ->
                            FilterChip(
                                selected = aspectRatio == ratio,
                                onClick = { onAspectRatioChange(ratio) },
                                label = { Text(label) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        label = {
                            Text(
                                if (isEditMode) "Describe how to edit this photo"
                                else "Describe the image you want to create"
                            )
                        },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_prompt_input")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        starterPrompts.forEach { sample ->
                            FilterChip(
                                selected = false,
                                onClick = { onPromptChange(sample) },
                                label = { Text(sample.take(36) + if (sample.length > 36) "..." else "") }
                            )
                        }
                    }

                    val isBusy = studioState is ImageStudioState.Generating
                    Button(
                        onClick = onRunGeneration,
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .testTag("studio_generate_button")
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Synthesizing 1K image (typically 20–45s)...")
                        } else {
                            Icon(
                                imageVector = if (isEditMode) Icons.Filled.AutoFixHigh else Icons.Filled.Brush,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isEditMode) "Apply Image Edit" else "Generate Image")
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = studioState,
                transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(150))) },
                label = "studio_result_state"
            ) { currentStudio ->
                when (currentStudio) {
                    is ImageStudioState.Idle -> Unit
                    is ImageStudioState.Generating -> {
                        StudioPulsePlaceholder()
                    }
                    is ImageStudioState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = currentStudio.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                    is ImageStudioState.Ready -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    bitmap = currentStudio.bitmap.asImageBitmap(),
                                    contentDescription = "Studio generated result",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            pendingSaveBitmap = currentStudio.bitmap
                                            savePngLauncher.launch("lenscribe-studio-${System.currentTimeMillis()}.png")
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 48.dp)
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Save PNG")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            DocumentExporter.shareBitmap(context, currentStudio.bitmap)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 48.dp)
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Share")
                                    }
                                }
                                Button(
                                    onClick = onSendResultToScanner,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Icon(Icons.Filled.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Send to Scanner Queue")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
