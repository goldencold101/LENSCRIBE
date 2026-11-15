package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.example.model.ScanDocument
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LenscribeRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private val runTag = UUID.randomUUID().toString().take(8)
    private val aliceEmail = "alice_$runTag@test.com"
    private val bobEmail = "bob_$runTag@test.com"

    @Test
    fun ensureUserProfile_grantsEarlyAccessPass_and_saveScan_succeedsForOwner() = runBlocking {
        val uid = signInTestUser(aliceEmail)
        val repo = LenscribeRepository(firestore, auth)

        val profileResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("Alice", aliceEmail)
        }
        assertTrue("ensureUserProfile failed: ${profileResult.exceptionOrNull()}", profileResult.isSuccess)
        val profile = profileResult.getOrThrow()
        assertEquals(uid, profile.userId)
        assertEquals("pro", profile.planTier)
        assertEquals("trialing", profile.subscriptionStatus)
        assertEquals(300L, profile.monthlyCreditsLimit)
        assertNotNull(profile.subscriptionExpiresAt)

        val scanResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.saveScan(
                ScanDocument(
                    title = "Receipt ${UUID.randomUUID().toString().take(6)}",
                    markdown = "# Receipt\n\n| Item | Price |\n| --- | --- |\n| Coffee | 4.50 |",
                    mode = "table",
                    preset = "table",
                    pageCount = 1L,
                    hasTable = true,
                    zeroRetention = false,
                    lowConfidenceTerms = listOf("4.50")
                )
            )
        }
        assertTrue("saveScan failed: ${scanResult.exceptionOrNull()}", scanResult.isSuccess)
        val scanId = scanResult.getOrThrow()

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeScans().first { list -> list.any { it.id == scanId } }
        }
        assertTrue(observed.any { it.id == scanId })
    }

    @Test
    fun earlyAccessExpiry_and_secureWorkflows_succeedsAndBlocksEscalation() = runBlocking {
        val uid = signInTestUser(aliceEmail)
        val repo = LenscribeRepository(firestore, auth)
        val profile = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("Alice", aliceEmail).getOrThrow()
        }

        // Consuming credits works
        val consumeRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.consumeScanCredits(profile, 2L)
        }
        assertTrue("consumeScanCredits failed: ${consumeRes.exceptionOrNull()}", consumeRes.isSuccess)

        // Direct client privilege escalation or minting credits MUST fail
        var escalationError: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid)
                    .update(mapOf("paygCredits" to 999L, "planTier" to "team"))
                    .await()
            }
        } catch (e: Exception) {
            escalationError = e.unwrapFirestoreException() ?: (e as? FirebaseFirestoreException)
        }
        assertNotNull("Expected PERMISSION_DENIED on privilege escalation", escalationError)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, escalationError?.code)

        // SSRF private endpoint workflow MUST fail
        val ssrfWorkflow = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createWorkflow(
                name = "SSRF Attempt",
                trigger = "on_table_detected",
                destinationType = "webhook_json",
                endpointUrl = "https://127.0.0.1/steal"
            )
        }
        assertTrue(ssrfWorkflow.isFailure)

        // Valid HTTPS public workflow MUST succeed
        val validWorkflow = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createWorkflow(
                name = "Accounting Sync",
                trigger = "on_table_detected",
                destinationType = "webhook_json",
                endpointUrl = "https://hooks.example.com/lenscribe"
            )
        }
        assertTrue("createWorkflow failed: ${validWorkflow.exceptionOrNull()}", validWorkflow.isSuccess)

        // Expiring pass drops cleanly to Free
        val expireResult = withTimeout(DEFAULT_TIMEOUT_MS) { repo.markSubscriptionExpired() }
        assertTrue("markSubscriptionExpired failed: ${expireResult.exceptionOrNull()}", expireResult.isSuccess)
        val expiredProfile = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeUserProfile().first { it?.subscriptionStatus == "expired" }
        }
        assertEquals("free", expiredProfile?.planTier)
    }

    @Test
    fun crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(aliceEmail)
        val aliceRepo = LenscribeRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.ensureUserProfile("Alice", aliceEmail).getOrThrow()
        }

        signInTestUser(bobEmail)
        var captured: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid).collection("scans").get().await()
            }
        } catch (e: Exception) {
            captured = e.unwrapFirestoreException() ?: (e as? FirebaseFirestoreException)
        }
        assertNotNull(captured)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, captured?.code)
    }

    @Test
    fun unauthenticatedAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(aliceEmail)
        auth.signOut()

        var captured: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid).get().await()
            }
        } catch (e: Exception) {
            captured = e.unwrapFirestoreException() ?: (e as? FirebaseFirestoreException)
        }
        assertNotNull(captured)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, captured?.code)
    }

    @Test
    fun deleteAllUserCloudData_deletesProfileScansAndWorkflows() = runBlocking {
        val uid = signInTestUser(aliceEmail)
        val repo = LenscribeRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.ensureUserProfile("Alice", aliceEmail).getOrThrow()
            repo.saveScan(
                ScanDocument(
                    title = "Delete Test",
                    markdown = "Sample content",
                    mode = "fast",
                    preset = "layout",
                    pageCount = 1L
                )
            ).getOrThrow()
            repo.createWorkflow(
                name = "Test Rule",
                trigger = "on_scan_complete",
                destinationType = "webhook_json",
                endpointUrl = "https://hooks.example.com/test"
            ).getOrThrow()
        }

        val deleteRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.deleteAllUserCloudData() }
        assertTrue("deleteAllUserCloudData failed: ${deleteRes.exceptionOrNull()}", deleteRes.isSuccess)

        val profileSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
            firestore.collection("users").document(uid).get().await()
        }
        val scansSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
            firestore.collection("users").document(uid).collection("scans").get().await()
        }
        assertTrue(!profileSnap.exists())
        assertTrue(scansSnap.isEmpty)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
