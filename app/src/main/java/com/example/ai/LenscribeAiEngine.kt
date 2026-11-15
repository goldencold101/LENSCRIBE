package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.model.ScanMode
import com.example.model.SmartPreset
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class OcrBatchResult(
    val title: String,
    val markdown: String,
    val hasTable: Boolean,
    val lowConfidenceTerms: List<String>
)

class LenscribeAiEngine {

    private val ocrSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Concise 2-6 word document title"),
            "markdown" to Schema.string(description = "Complete verbatim transcribed document in clean Markdown"),
            "hasTable" to Schema.boolean(description = "True if structured rows/tables were extracted"),
            "lowConfidenceTerms" to Schema.array(
                items = Schema.string(),
                description = "Up to 12 short words, prices, or handwritten tokens that look faint, ambiguous, or need quick human verification"
            )
        )
    )

    private val fastOcrModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.0f
                responseMimeType = "application/json"
                responseSchema = ocrSchema
            },
            systemInstruction = content {
                text(
                    "You are a precision document OCR and layout transcription engine. " +
                        "Transcribe visible text verbatim without summarizing, inventing, or altering numbers. " +
                        "Return clean JSON matching the requested schema."
                )
            }
        )
    }

    private val precisionOcrModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.0f
                responseMimeType = "application/json"
                responseSchema = ocrSchema
            },
            systemInstruction = content {
                text(
                    "You are a high-precision document, handwriting, math, code, and table transcription engine. " +
                        "Transcribe visible content verbatim without hallucinating or omitting numbers. " +
                        "Flag any blurred, smudged, or ambiguous handwritten terms in lowConfidenceTerms so the user can verify them quickly."
                )
            }
        )
    }

    suspend fun transcribePages(
        pages: List<Bitmap>,
        mode: ScanMode,
        preset: SmartPreset,
        mergeContinuous: Boolean,
        customNote: String
    ): Result<OcrBatchResult> {
        if (pages.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one image to scan."))
        }
        return try {
            val model = when (mode) {
                ScanMode.FAST -> fastOcrModel
                ScanMode.PRECISION, ScanMode.TABLE -> precisionOcrModel
            }

            val modeDirective = when (mode) {
                ScanMode.FAST -> "Fast printed text capture."
                ScanMode.PRECISION -> "Carefully decipher handwriting, angled paper, low-contrast ink, and complex layouts."
                ScanMode.TABLE -> "Strictly extract all tables, receipts, invoices, or grids into aligned Markdown tables with headers and totals."
            }

            val mergeDirective = if (pages.size > 1) {
                if (mergeContinuous) {
                    "These ${pages.size} images are ordered sequentially from Page 1 to Page ${pages.size}. Transcribe strictly in sequence #1 -> #${pages.size}, merge them into one continuous Markdown document, and omit repeated page headers/footers."
                } else {
                    "These ${pages.size} images are ordered sequentially from Page 1 to Page ${pages.size}. Transcribe strictly in sequence #1 -> #${pages.size} and separate each page with a '---' divider."
                }
            } else {
                "Transcribe this single document page."
            }

            val extraNote = customNote.trim().take(300)
            val promptText = buildString {
                append(modeDirective).append("\n")
                append(preset.instruction).append("\n")
                append(mergeDirective)
                if (extraNote.isNotEmpty()) {
                    append("\nAdditional formatting preference: ").append(extraNote)
                }
            }

            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        text(promptText)
                        pages.forEachIndexed { index, bitmap ->
                            if (pages.size > 1) {
                                text("--- Page ${index + 1} of ${pages.size} (Sequence #${index + 1}) ---")
                            }
                            image(bitmap)
                        }
                    }
                )
            }

            val jsonString = response.text
                ?: throw IllegalStateException("The document engine returned an empty response.")
            val json = JSONObject(jsonString)
            val title = json.optString("title", "Scanned Document").trim().ifBlank { "Scanned Document" }
            val markdown = json.optString("markdown", "").trim()
            if (markdown.isEmpty()) {
                throw IllegalStateException("No readable text was detected in the selected images.")
            }
            val hasTable = json.optBoolean("hasTable", markdown.contains("| ---"))
            val termsArray = json.optJSONArray("lowConfidenceTerms")
            val terms = buildList {
                if (termsArray != null) {
                    for (i in 0 until minOf(termsArray.length(), 15)) {
                        val term = termsArray.optString(i, "").trim()
                        if (term.isNotEmpty()) add(term)
                    }
                }
            }
            Result.success(OcrBatchResult(title, markdown, hasTable, terms))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "OCR transcription timed out", e)
            Result.failure(Exception("Scan timed out after 60 seconds. Try scanning fewer pages at once."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OCR transcription failed", e)
            Result.failure(Exception(e.localizedMessage ?: "Document scan failed."))
        }
    }

    suspend fun generateStudioImage(
        prompt: String,
        aspectRatio: AspectRatio
    ): Result<Bitmap> {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a description to generate an image."))
        }
        return try {
            val config = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
                imageConfig = ImageConfig.builder()
                    .setAspectRatio(aspectRatio)
                    .setImageSize(ImageSize.SIZE_1K)
                    .build()
            }
            val imageModel = Firebase.ai.generativeModel(
                modelName = IMAGE_MODEL_NAME,
                generationConfig = config
            )
            val response = withTimeout(60_000L) {
                imageModel.generateContent(cleanPrompt)
            }
            val imagePart = response.candidates.firstOrNull()
                ?.content?.parts?.filterIsInstance<ImagePart>()
                ?.firstOrNull()
                ?: throw IllegalStateException("No image was returned by the model.")
            Result.success(imagePart.image)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Studio image generation timed out", e)
            Result.failure(Exception("Image generation timed out after 60 seconds. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Studio image generation failed", e)
            Result.failure(Exception(e.localizedMessage ?: "Image generation failed."))
        }
    }

    suspend fun editStudioImage(
        inputBitmap: Bitmap,
        editInstruction: String,
        aspectRatio: AspectRatio
    ): Result<Bitmap> {
        val cleanInstruction = editInstruction.trim()
        if (cleanInstruction.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter an edit instruction for the image."))
        }
        return try {
            val config = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
                imageConfig = ImageConfig.builder()
                    .setAspectRatio(aspectRatio)
                    .setImageSize(ImageSize.SIZE_1K)
                    .build()
            }
            val imageModel = Firebase.ai.generativeModel(
                modelName = IMAGE_MODEL_NAME,
                generationConfig = config
            )
            val response = withTimeout(60_000L) {
                imageModel.generateContent(
                    content {
                        image(inputBitmap)
                        text(cleanInstruction)
                    }
                )
            }
            val imagePart = response.candidates.firstOrNull()
                ?.content?.parts?.filterIsInstance<ImagePart>()
                ?.firstOrNull()
                ?: throw IllegalStateException("No edited image was returned by the model.")
            Result.success(imagePart.image)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Studio image edit timed out", e)
            Result.failure(Exception("Image editing timed out after 60 seconds. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Studio image edit failed", e)
            Result.failure(Exception(e.localizedMessage ?: "Image editing failed."))
        }
    }

    private companion object {
        const val TAG = "LenscribeAiEngine"
        const val IMAGE_MODEL_NAME = "gemini-nano-banana-2.1"
    }
}
