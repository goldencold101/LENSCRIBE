package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.imaging.ImagePreprocessor
import com.example.ui.theme.EmeraldMint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor

private const val CAMERA_TAG = "LenscribeCameraX"

@Composable
fun CameraDocumentScannerDialog(
    maxRemainingPages: Int,
    onDismiss: () -> Unit,
    onPagesCaptured: (List<Bitmap>) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val capturedPages = remember { mutableStateListOf<Bitmap>() }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var autoEnhance by remember { mutableStateOf(true) }
    var isCapturing by remember { mutableStateOf(false) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    boundProvider = provider
                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e(CAMERA_TAG, "Failed to bind CameraX use cases", e)
                    onError(e.localizedMessage ?: "Unable to start camera preview.")
                }
            },
            mainExecutor
        )

        onDispose {
            runCatching { boundProvider?.unbindAll() }
        }
    }

    fun cycleFlash() {
        val next = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        flashMode = next
        imageCapture.flashMode = next
    }

    fun capturePage() {
        if (isCapturing) return
        if (capturedPages.size >= maxRemainingPages) {
            onError("Batch limit reached ($maxRemainingPages more page(s) allowed).")
            return
        }
        isCapturing = true
        val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
        val photoFile = File(capturesDir, "cx_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        val executor = ContextCompat.getMainExecutor(context)

        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val decoded = withContext(Dispatchers.IO) {
                            val raw = ImagePreprocessor.decodeScaledBitmap(context, Uri.fromFile(photoFile))
                            if (raw != null && autoEnhance) {
                                ImagePreprocessor.autoEnhanceDocument(raw)
                            } else {
                                raw
                            }
                        }
                        isCapturing = false
                        if (decoded != null) {
                            capturedPages.add(decoded)
                        } else {
                            onError("Captured photo could not be decoded.")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(CAMERA_TAG, "Capture failed", exception)
                    isCapturing = false
                    onError(exception.localizedMessage ?: "Camera capture failed.")
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Precision emerald document viewfinder corner reticle
            Canvas(modifier = Modifier.fillMaxSize()) {
                val padX = size.width * 0.08f
                val padY = size.height * 0.17f
                val right = size.width - padX
                val bottom = size.height - padY
                val bracket = size.minDimension * 0.08f
                val stroke = 6f

                // Dim outside framing guides
                drawLine(EmeraldMint, Offset(padX, padY), Offset(padX + bracket, padY), stroke, StrokeCap.Round)
                drawLine(EmeraldMint, Offset(padX, padY), Offset(padX, padY + bracket), stroke, StrokeCap.Round)

                drawLine(EmeraldMint, Offset(right, padY), Offset(right - bracket, padY), stroke, StrokeCap.Round)
                drawLine(EmeraldMint, Offset(right, padY), Offset(right, padY + bracket), stroke, StrokeCap.Round)

                drawLine(EmeraldMint, Offset(padX, bottom), Offset(padX + bracket, bottom), stroke, StrokeCap.Round)
                drawLine(EmeraldMint, Offset(padX, bottom), Offset(padX, bottom - bracket), stroke, StrokeCap.Round)

                drawLine(EmeraldMint, Offset(right, bottom), Offset(right - bracket, bottom), stroke, StrokeCap.Round)
                drawLine(EmeraldMint, Offset(right, bottom), Offset(right, bottom - bracket), stroke, StrokeCap.Round)
            }

            // Top Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = CircleShape
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close scanner camera", tint = Color.White)
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, EmeraldMint.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Align page inside frame • ${capturedPages.size} snapped",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = if (autoEnhance) EmeraldMint.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.65f),
                        shape = CircleShape
                    ) {
                        IconButton(
                            onClick = { autoEnhance = !autoEnhance },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Toggle auto contrast enhancement",
                                tint = if (autoEnhance) EmeraldMint else Color.White
                            )
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = CircleShape
                    ) {
                        IconButton(
                            onClick = ::cycleFlash,
                            modifier = Modifier.size(48.dp)
                        ) {
                            val icon = when (flashMode) {
                                ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                                ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                                else -> Icons.Filled.FlashOff
                            }
                            Icon(icon, contentDescription = "Toggle camera flash", tint = Color.White)
                        }
                    }
                }
            }

            // Bottom Shutter Controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(104.dp)) {
                        Text(
                            text = if (autoEnhance) "Auto-Enhance ON" else "Raw Capture",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (autoEnhance) EmeraldMint else Color.LightGray
                        )
                    }

                    // Shutter Button
                    Surface(
                        onClick = ::capturePage,
                        enabled = !isCapturing,
                        shape = CircleShape,
                        color = Color.White,
                        border = BorderStroke(4.dp, EmeraldMint),
                        modifier = Modifier
                            .size(74.dp)
                            .testTag("camerax_shutter_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isCapturing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = Color.Black,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = "Snap document page",
                                    tint = Color.Black,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.width(104.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Button(
                            onClick = {
                                if (capturedPages.isNotEmpty()) {
                                    onPagesCaptured(capturedPages.toList())
                                } else {
                                    onDismiss()
                                }
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .semantics(mergeDescendants = true) {}
                                .testTag("camerax_done_button")
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (capturedPages.isEmpty()) "Close" else "Use (${capturedPages.size})",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
