package com.example.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

object ImagePreprocessor {

    private const val MAX_SIDE_PX = 2048

    fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        while (max(rawWidth / sampleSize, rawHeight / sampleSize) > MAX_SIDE_PX * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        return downscaleIfNeeded(decoded)
    }

    fun downscaleIfNeeded(bitmap: Bitmap, maxSide: Int = MAX_SIDE_PX): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / largest.toFloat()
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    fun rotate90(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Auto-contrast + luminance normalization for skewed, low-light phone document photos.
     */
    fun autoEnhanceDocument(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val contrast = 1.24f
        val brightness = -18f
        val matrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Trim outer 6% edge clutter on phone camera captures for cleaner OCR framing.
     */
    fun trimMargins(source: Bitmap, insetFraction: Float = 0.06f): Bitmap {
        val safeFraction = insetFraction.coerceIn(0.01f, 0.25f)
        val dx = (source.width * safeFraction).toInt()
        val dy = (source.height * safeFraction).toInt()
        val cropW = (source.width - dx * 2).coerceAtLeast(min(100, source.width))
        val cropH = (source.height - dy * 2).coerceAtLeast(min(100, source.height))
        return Bitmap.createBitmap(source, dx, dy, cropW, cropH)
    }
}
