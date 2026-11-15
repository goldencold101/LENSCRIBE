package com.example.automation

import android.util.Log
import com.example.export.DocumentExporter
import com.example.model.ScanDocument
import com.example.model.WorkflowAutomation
import com.example.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class WorkflowExecutionResult(
    val workflowId: String,
    val delivered: Boolean,
    val statusCode: Int,
    val message: String
)

object WorkflowDispatcher {

    private const val TAG = "WorkflowDispatcher"

    suspend fun dispatchForScan(
        workflow: WorkflowAutomation,
        scan: ScanDocument
    ): WorkflowExecutionResult = withContext(Dispatchers.IO) {
        val syntaxResult = SecurityGuard.validateWebhookEndpointSyntax(workflow.endpointUrl)
        val urlText = syntaxResult.getOrElse { err ->
            return@withContext WorkflowExecutionResult(
                workflowId = workflow.id,
                delivered = false,
                statusCode = 0,
                message = err.message ?: "Invalid https:// webhook endpoint."
            )
        }

        try {
            val targetUrl = URL(urlText)
            if (!SecurityGuard.verifyResolvedHostIsPublic(targetUrl)) {
                return@withContext WorkflowExecutionResult(
                    workflowId = workflow.id,
                    delivered = false,
                    statusCode = 0,
                    message = "Blocked: webhook host resolved to a private or reserved network."
                )
            }

            val payload = when (workflow.destinationType) {
                "accounting_csv" -> {
                    val csvText = String(DocumentExporter.buildCsv(scan.markdown), Charsets.UTF_8)
                    JSONObject().apply {
                        put("event", "lenscribe.accounting_csv")
                        put("title", SecurityGuard.sanitizeSafeText(scan.title))
                        put("pageCount", scan.pageCount)
                        put("csv", csvText)
                    }.toString()
                }
                else -> {
                    JSONObject().apply {
                        put("event", "lenscribe.${workflow.trigger}")
                        put("scanId", scan.id)
                        put("title", SecurityGuard.sanitizeSafeText(scan.title))
                        put("mode", scan.mode)
                        put("preset", scan.preset)
                        put("pageCount", scan.pageCount)
                        put("hasTable", scan.hasTable)
                        put("markdown", SecurityGuard.sanitizeSafeText(scan.markdown))
                        put("lowConfidenceTerms", JSONArray(scan.lowConfidenceTerms))
                    }.toString()
                }
            }

            val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("X-Lenscribe-Event", workflow.trigger)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                WorkflowExecutionResult(workflow.id, true, code, "Delivered (HTTP $code)")
            } else {
                WorkflowExecutionResult(workflow.id, false, code, "Endpoint returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Webhook delivery failed for ${workflow.name}: ${e.message}")
            WorkflowExecutionResult(
                workflowId = workflow.id,
                delivered = false,
                statusCode = 0,
                message = e.localizedMessage ?: "Network delivery failed"
            )
        }
    }
}
