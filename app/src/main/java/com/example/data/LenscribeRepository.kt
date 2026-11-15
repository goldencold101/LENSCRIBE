package com.example.data

import android.content.Context
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.example.model.BillingOrder
import com.example.model.PlanTier
import com.example.model.ScanDocument
import com.example.model.UserProfile
import com.example.model.WorkflowAutomation
import com.example.security.SecurityGuard
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

class LenscribeRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun ensureUserProfile(displayName: String, email: String?): Result<UserProfile> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val snap = docRef.get().await()
        if (snap.exists()) {
            val existing = checkNotNull(
                snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            )
            if (existing.planTier == PlanTier.FREE.id && existing.subscriptionStatus == "none") {
                val earlyAccessExpiry = Timestamp(Date(System.currentTimeMillis() + NINETY_DAYS_MILLIS))
                docRef.update(
                    mapOf(
                        "planTier" to PlanTier.PRO.id,
                        "monthlyCreditsLimit" to PlanTier.PRO.monthlyScans.toLong(),
                        "monthlyCreditsUsed" to 0L,
                        "subscriptionStatus" to "trialing",
                        "subscriptionExpiresAt" to earlyAccessExpiry,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                val upgraded = docRef.get().await()
                return@runCatching upgraded.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: existing
            }
            return@runCatching existing
        }
        val safeName = SecurityGuard.sanitizeSafeText(displayName).trim().ifBlank { "Workspace User" }.take(100)
        val earlyAccessExpiry = Timestamp(Date(System.currentTimeMillis() + NINETY_DAYS_MILLIS))
        val payload = buildMap<String, Any> {
            put("userId", uid)
            if (!email.isNullOrBlank()) {
                put("email", SecurityGuard.sanitizeSafeText(email).trim().take(254))
            }
            put("displayName", safeName)
            put("planTier", PlanTier.PRO.id)
            put("monthlyCreditsLimit", PlanTier.PRO.monthlyScans.toLong())
            put("monthlyCreditsUsed", 0L)
            put("paygCredits", 0L)
            put("preferredCurrency", "USD")
            put("paymentProvider", "none")
            put("subscriptionStatus", "trialing")
            put("subscriptionExpiresAt", earlyAccessExpiry)
            put("zeroRetentionEnabled", false)
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
        }
        docRef.set(payload).await()
        val created = docRef.get().await()
        created.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?: UserProfile(
                id = uid,
                userId = uid,
                displayName = safeName,
                email = email,
                planTier = PlanTier.PRO.id,
                monthlyCreditsLimit = PlanTier.PRO.monthlyScans.toLong(),
                subscriptionStatus = "trialing",
                subscriptionExpiresAt = earlyAccessExpiry
            )
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.WRITE, "users/${auth.currentUser?.uid}")
    }

    fun observeUserProfile(): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun updateWorkspacePreferences(
        zeroRetentionEnabled: Boolean
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        docRef.update(
            mapOf(
                "zeroRetentionEnabled" to zeroRetentionEnabled,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.UPDATE, "users/${auth.currentUser?.uid}")
    }

    suspend fun consumeScanCredits(profile: UserProfile, creditsToConsume: Long = 1L): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val monthlyLeft = (profile.monthlyCreditsLimit - profile.monthlyCreditsUsed).coerceAtLeast(0L)
        val fromMonthly = minOf(monthlyLeft, creditsToConsume)
        val fromPayg = (creditsToConsume - fromMonthly).coerceAtMost(profile.paygCredits)
        val newMonthlyUsed = (profile.monthlyCreditsUsed + fromMonthly).coerceAtMost(100000L)
        val newPayg = (profile.paygCredits - fromPayg).coerceAtLeast(0L)

        docRef.update(
            mapOf(
                "monthlyCreditsUsed" to newMonthlyUsed,
                "paygCredits" to newPayg,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.UPDATE, "users/${auth.currentUser?.uid}")
    }

    suspend fun markSubscriptionExpired(): Result<Unit> = runCatching {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        userRef.update(
            mapOf(
                "planTier" to PlanTier.FREE.id,
                "monthlyCreditsLimit" to PlanTier.FREE.monthlyScans.toLong(),
                "monthlyCreditsUsed" to 0L,
                "subscriptionStatus" to "expired",
                "subscriptionExpiresAt" to null,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Unit
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.UPDATE, "users/${auth.currentUser?.uid}")
    }

    fun observeOrders(): Flow<List<BillingOrder>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/billing_orders"
        emitAll(
            db.collection("users").document(uid).collection("billing_orders")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        BillingOrder::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveScan(scan: ScanDocument): Result<String> = runCatching {
        val uid = requireUserId()
        val scanId = scan.id.ifBlank {
            "scan_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        }
        val docRef = db.collection("users").document(uid).collection("scans").document(scanId)
        val safeTitle = scan.title.trim().ifBlank { "Untitled Scan" }.take(120)
        val safeMarkdown = scan.markdown.ifBlank { "(Empty scan)" }.take(100000)
        val safeTerms = scan.lowConfidenceTerms.take(30)

        if (scan.id.isBlank()) {
            val payload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "markdown" to safeMarkdown,
                "mode" to scan.mode,
                "preset" to scan.preset,
                "pageCount" to scan.pageCount.coerceIn(1L, 50L),
                "hasTable" to scan.hasTable,
                "zeroRetention" to scan.zeroRetention,
                "lowConfidenceTerms" to safeTerms,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } else {
            docRef.update(
                mapOf(
                    "title" to safeTitle,
                    "markdown" to safeMarkdown,
                    "mode" to scan.mode,
                    "preset" to scan.preset,
                    "pageCount" to scan.pageCount.coerceIn(1L, 50L),
                    "hasTable" to scan.hasTable,
                    "zeroRetention" to scan.zeroRetention,
                    "lowConfidenceTerms" to safeTerms,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        scanId
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.WRITE, "users/${auth.currentUser?.uid}/scans")
    }

    suspend fun deleteScan(scanId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("scans").document(scanId)
        docRef.delete().await()
        Unit
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.DELETE, "users/${auth.currentUser?.uid}/scans/$scanId")
    }

    fun observeScans(): Flow<List<ScanDocument>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/scans"
        emitAll(
            db.collection("users").document(uid).collection("scans")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ScanDocument::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createWorkflow(
        name: String,
        trigger: String,
        destinationType: String,
        endpointUrl: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val validatedUrl = SecurityGuard.validateWebhookEndpointSyntax(endpointUrl).getOrThrow()
        val workflowId = "wf_${UUID.randomUUID().toString().replace("-", "").take(18)}"
        val docRef = db.collection("users").document(uid).collection("workflows").document(workflowId)
        val payload = mapOf(
            "userId" to uid,
            "name" to SecurityGuard.sanitizeSafeText(name).trim().ifBlank { "Automation Rule" }.take(80),
            "trigger" to trigger,
            "destinationType" to destinationType,
            "endpointUrl" to validatedUrl,
            "enabled" to true,
            "lastStatus" to "idle",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(payload).await()
        workflowId
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.CREATE, "users/${auth.currentUser?.uid}/workflows")
    }

    suspend fun updateWorkflowStatus(
        workflowId: String,
        lastStatus: String,
        enabled: Boolean? = null
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("workflows").document(workflowId)
        val updates = buildMap<String, Any> {
            put("lastStatus", lastStatus)
            put("lastTriggeredAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
            if (enabled != null) {
                put("enabled", enabled)
            }
        }
        docRef.update(updates).await()
        Unit
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.UPDATE, "users/${auth.currentUser?.uid}/workflows/$workflowId")
    }

    suspend fun deleteWorkflow(workflowId: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("workflows").document(workflowId)
        docRef.delete().await()
        Unit
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.DELETE, "users/${auth.currentUser?.uid}/workflows/$workflowId")
    }

    fun observeWorkflows(): Flow<List<WorkflowAutomation>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/workflows"
        emitAll(
            db.collection("users").document(uid).collection("workflows")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        WorkflowAutomation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun deleteAllUserCloudData(): Result<Unit> = runCatching {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val scansSnap = userRef.collection("scans").get().await()
        val workflowsSnap = userRef.collection("workflows").get().await()

        val allRefs = scansSnap.documents.map { it.reference } +
            workflowsSnap.documents.map { it.reference } +
            listOf(userRef)

        // Commit in chunks safely below Firestore's 500-op batch limit
        allRefs.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { ref -> batch.delete(ref) }
            batch.commit().await()
        }
        Unit
    }.onFailure { error ->
        if (error is CancellationException) throw error
        handleFirestoreError(error, OperationType.DELETE, "users/${auth.currentUser?.uid}")
    }

    private companion object {
        const val NINETY_DAYS_MILLIS = 90L * 24L * 60L * 60L * 1000L
    }
}
