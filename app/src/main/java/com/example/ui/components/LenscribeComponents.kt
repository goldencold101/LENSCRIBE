package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.export.DocumentExporter
import com.example.export.MarkdownParser
import com.example.model.CardAspect
import com.example.model.CardTheme
import com.example.model.ExportFormat
import com.example.model.UserProfile
import com.example.security.SecurityGuard
import com.example.ui.theme.VerifyAmber
import com.example.ui.theme.VerifyAmberBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun CreditBalancePill(
    profile: UserProfile?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val credits = profile?.totalRemainingCredits ?: 0L
    val planName = profile?.tier?.displayName ?: "Free"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .testTag("credit_balance_pill")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$planName • $credits cr",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ZeroRetentionBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = "Zero-data-retention active",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Zero-Data-Retention active: documents stay in volatile memory only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun AnnotatedVerificationText(
    markdown: String,
    lowConfidenceTerms: List<String>,
    modifier: Modifier = Modifier
) {
    val annotated = remember(markdown, lowConfidenceTerms) {
        buildAnnotatedString {
            append(markdown)
            lowConfidenceTerms
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()
                .forEach { term ->
                    var searchFrom = 0
                    while (searchFrom < markdown.length) {
                        val idx = markdown.indexOf(term, startIndex = searchFrom, ignoreCase = true)
                        if (idx < 0) break
                        addStyle(
                            style = SpanStyle(
                                background = VerifyAmberBg,
                                color = VerifyAmber,
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline
                            ),
                            start = idx,
                            end = idx + term.length
                        )
                        searchFrom = idx + term.length
                    }
                }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

@Composable
fun MiniSpreadsheetEditor(
    markdown: String,
    onCellChanged: (rowIdx: Int, colIdx: Int, value: String) -> Unit,
    onAddRow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(markdown) { MarkdownParser.extractFirstTable(markdown).orEmpty() }
    val scrollH = rememberScrollState()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (rows.isEmpty()) "No table detected yet" else "Editable Spreadsheet (${rows.size} rows)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onAddRow,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("add_table_row_button")
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (rows.isEmpty()) "Insert Table" else "Add Row")
            }
        }

        if (rows.isNotEmpty()) {
            val cols = rows.maxOf { it.size }.coerceAtLeast(1)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollH)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .clip(MaterialTheme.shapes.small)
            ) {
                rows.forEachIndexed { rIdx, row ->
                    val isHeader = rIdx == 0
                    Row(
                        modifier = Modifier.background(
                            if (isHeader) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else if (rIdx % 2 == 1) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        for (cIdx in 0 until cols) {
                            val cellValue = row.getOrElse(cIdx) { "" }
                            Box(
                                modifier = Modifier
                                    .width(132.dp)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = cellValue,
                                    onValueChange = { updated -> onCellChanged(rIdx, cIdx, updated) },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = if (isHeader) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDocumentBottomSheet(
    title: String,
    markdown: String,
    allowsAllExports: Boolean,
    onDismiss: () -> Unit,
    onNavigateToPlans: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedFormat by remember { mutableStateOf(if (allowsAllExports) ExportFormat.DOCX else ExportFormat.MARKDOWN) }
    var cardTheme by remember { mutableStateOf(CardTheme.EMERALD) }
    var cardAspect by remember { mutableStateOf(CardAspect.SQUARE) }

    val cardPreviewBitmap: Bitmap? = remember(selectedFormat, markdown, title, cardTheme, cardAspect) {
        if (selectedFormat == ExportFormat.PNG_CARD) {
            DocumentExporter.buildTextCardBitmap(markdown, title, cardTheme, cardAspect)
        } else {
            null
        }
    }

    var pendingSaveFormat by remember { mutableStateOf<ExportFormat?>(null) }
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val fmt = pendingSaveFormat ?: return@rememberLauncherForActivityResult
        pendingSaveFormat = null
        if (uri != null) {
            scope.launch {
                runCatching {
                    val bytes = withContext(Dispatchers.Default) {
                        DocumentExporter.exportBytes(fmt, markdown, title, cardTheme, cardAspect)
                    }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                        } ?: error("Unable to open destination file.")
                    }
                }.onSuccess {
                    onMessage("Saved ${fmt.label} to device.")
                }.onFailure { err ->
                    onMessage(err.localizedMessage ?: "Failed to save file.")
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Export Document",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Choose any format or shareable text card",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, title, markdown)
                        onMessage("Copied Markdown to clipboard.")
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy")
                }
            }

            // Format grid (2 columns)
            val formats = ExportFormat.entries
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { fmt ->
                            val unlocked = fmt.freeTierAllowed || allowsAllExports
                            val selected = selectedFormat == fmt
                            Surface(
                                onClick = {
                                    if (unlocked) {
                                        selectedFormat = fmt
                                    } else {
                                        onDismiss()
                                        onNavigateToPlans()
                                    }
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 52.dp)
                                    .testTag("export_format_${fmt.ext}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = formatIcon(fmt),
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = fmt.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!unlocked) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = "Pro plan required",
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    } else if (selected) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedFormat == ExportFormat.PNG_CARD) {
                Text(
                    text = "Share Card Theme & Aspect",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = cardTheme == theme,
                            onClick = { cardTheme = theme },
                            label = { Text(theme.label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardAspect.entries.forEach { aspect ->
                        FilterChip(
                            selected = cardAspect == aspect,
                            onClick = { cardAspect = aspect },
                            label = { Text(aspect.label) }
                        )
                    }
                }
                if (cardPreviewBitmap != null) {
                    val ratio = cardAspect.width.toFloat() / cardAspect.height.toFloat()
                    Image(
                        bitmap = cardPreviewBitmap.asImageBitmap(),
                        contentDescription = "Share card preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                            .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                            .align(Alignment.CenterHorizontally)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        pendingSaveFormat = selectedFormat
                        val base = SecurityGuard.sanitizeFilenameBase(title, "lenscribe")
                        saveDocumentLauncher.launch("$base.${selectedFormat.ext}")
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 50.dp)
                        .testTag("export_save_device_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save File")
                }

                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.Default) {
                                DocumentExporter.writeAndShare(
                                    context = context,
                                    format = selectedFormat,
                                    markdown = markdown,
                                    title = title,
                                    cardTheme = cardTheme,
                                    cardAspect = cardAspect
                                )
                            }
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 50.dp)
                        .testTag("export_share_button")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

private fun formatIcon(fmt: ExportFormat): ImageVector = when (fmt) {
    ExportFormat.DOCX -> Icons.Filled.Description
    ExportFormat.XLSX -> Icons.Filled.TableChart
    ExportFormat.PPTX -> Icons.Filled.Slideshow
    ExportFormat.PDF -> Icons.Filled.PictureAsPdf
    ExportFormat.CSV -> Icons.Filled.GridOn
    ExportFormat.MARKDOWN -> Icons.Filled.Description
    ExportFormat.TXT -> Icons.Filled.Description
    ExportFormat.PNG_CARD -> Icons.Filled.Image
}
