package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.backup.BackupScanEntity
import com.example.model.PlanTier
import com.example.model.UserProfile
import com.example.model.WorkflowAutomation
import com.example.security.SecurityGuard
import com.example.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BillingAndWorkflowsScreen(
    profile: UserProfile?,
    workflowsState: UiState<List<WorkflowAutomation>>,
    backupScans: List<BackupScanEntity>,
    lastBackupAtMs: Long?,
    isDarkTheme: Boolean,
    screenShieldEnabled: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onToggleScreenShield: (Boolean) -> Unit,
    onToggleZeroRetention: (Boolean) -> Unit,
    onSubscriptionExpired: () -> Unit,
    onSyncBackupNow: () -> Unit,
    onRestoreBackupToCloud: () -> Unit,
    onBuildPortableBackupJson: suspend () -> ByteArray,
    onImportPortableBackupJson: (ByteArray) -> Unit,
    onClearLocalBackups: () -> Unit,
    onCreateWorkflow: (name: String, trigger: String, destinationType: String, endpointUrl: String) -> Unit,
    onToggleWorkflow: (WorkflowAutomation) -> Unit,
    onTestWorkflow: (WorkflowAutomation) -> Unit,
    onDeleteWorkflow: (String) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val securityPosture = remember(context) { SecurityGuard.inspectSecurityPosture(context) }

    var wfName by remember { mutableStateOf("") }
    var wfDestination by remember { mutableStateOf("webhook_json") }
    var wfUrl by remember { mutableStateOf("") }

    val tier = profile?.tier ?: PlanTier.FREE
    val urlValidation = remember(wfUrl) {
        if (wfUrl.isBlank()) null else SecurityGuard.validateWebhookEndpointSyntax(wfUrl)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Workspace Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.displayName ?: "Workspace Account",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = profile?.email ?: "Google Workspace Account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onToggleTheme(!isDarkTheme) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "Toggle light or dark theme"
                        )
                    }
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign out")
                    }
                }
            }

            // 3-Month Complimentary Early Access Pass & Live Countdown Timer
            EarlyAccessStatusAndTimerCard(
                profile = profile,
                tier = tier,
                onSubscriptionExpired = onSubscriptionExpired
            )

            // Early Access Billing Schedule Banner (payments hidden until next year)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Early Access • 3 Months Free Pro",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Paid plans and NGN/USD checkout gateways are paused until next year. Every signed-in workspace automatically unlocks 3 months of Pro (300 scans/month, 25-page batches, all 7 export formats, and HTTPS workflows) at no charge.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Multi-Layer Security & Privacy Defense Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Privacy & Security Hardening",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Zero-Data-Retention Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Zero-Data-Retention Mode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Keep scans strictly in memory; skip cloud storage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = profile?.zeroRetentionEnabled == true,
                            onCheckedChange = onToggleZeroRetention,
                            modifier = Modifier.testTag("zero_retention_switch")
                        )
                    }

                    // Screen Capture Shield (FLAG_SECURE) Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ScreenshotMonitor,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Screen Capture Shield (FLAG_SECURE)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Block screenshots, recorders & streaming previews when ON",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = screenShieldEnabled,
                            onCheckedChange = onToggleScreenShield,
                            modifier = Modifier.testTag("screen_shield_switch")
                        )
                    }

                    // Active Defenses Summary
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Active Multi-Layer Defenses",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DefenseLine("Firebase App Check + Google Sign-In identity lock", securityPosture.appCheckActive)
                            DefenseLine("Zero-trust Firestore quota & anti-escalation rules", true)
                            DefenseLine("Strict TLS-only policy (cleartext HTTP blocked)", securityPosture.tlsOnlyEnforced)
                            DefenseLine("SSRF private-IP / DNS-rebinding webhook guard", securityPosture.ssrfGuardActive)
                            DefenseLine("CSV & Excel formula-injection sanitizer (CWE-1236)", securityPosture.csvFormulaShieldActive)
                            DefenseLine("Anti-tapjacking touch filter & backup exclusion", securityPosture.tapjackingGuardActive)
                            DefenseLine("Zero payment secret keys compiled in APK", securityPosture.noClientPaymentSecrets)
                            if (securityPosture.hasTestKeysOrSu) {
                                Text(
                                    text = "Notice: Device environment reports test-keys or root binary.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            // Local SQLite Backup & Failover Database Card
            LocalBackupDatabaseCard(
                zeroRetentionEnabled = profile?.zeroRetentionEnabled == true,
                backupScans = backupScans,
                lastBackupAtMs = lastBackupAtMs,
                onSyncBackupNow = onSyncBackupNow,
                onRestoreBackupToCloud = onRestoreBackupToCloud,
                onBuildPortableBackupJson = onBuildPortableBackupJson,
                onImportPortableBackupJson = onImportPortableBackupJson,
                onClearLocalBackups = onClearLocalBackups,
                onMessage = onMessage
            )

            // Automated HTTPS Export Workflows
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Automated Export Workflows",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (!tier.allowsWorkflows) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Your Early Access period has ended. Workflows will reopen when subscriptions launch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = wfName,
                            onValueChange = { wfName = it },
                            label = { Text("Workflow Name (e.g. Xero Receipt Ledger)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = wfDestination == "webhook_json",
                                onClick = { wfDestination = "webhook_json" },
                                label = { Text("JSON Webhook") }
                            )
                            FilterChip(
                                selected = wfDestination == "accounting_csv",
                                onClick = { wfDestination = "accounting_csv" },
                                label = { Text("Accounting CSV") }
                            )
                        }
                        OutlinedTextField(
                            value = wfUrl,
                            onValueChange = { wfUrl = it },
                            label = { Text("Public HTTPS Destination URL") },
                            placeholder = { Text("https://hooks.example.com/lenscribe") },
                            isError = urlValidation?.isFailure == true,
                            supportingText = urlValidation?.exceptionOrNull()?.message?.let { msg ->
                                { Text(msg) }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onCreateWorkflow(
                                    wfName.ifBlank { "Scan Sync" },
                                    "on_scan_complete",
                                    wfDestination,
                                    wfUrl
                                )
                                wfName = ""
                                wfUrl = ""
                            },
                            enabled = urlValidation?.isSuccess == true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("add_workflow_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Secure HTTPS Rule")
                        }
                    }

                    val workflows = (workflowsState as? UiState.Success)?.data.orEmpty()
                    workflows.forEach { wf ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = wf.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${wf.destinationType} • Last: ${wf.lastStatus}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onTestWorkflow(wf) },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Test workflow")
                                    }
                                    Switch(
                                        checked = wf.enabled,
                                        onCheckedChange = { onToggleWorkflow(wf) }
                                    )
                                    IconButton(
                                        onClick = { onDeleteWorkflow(wf.id) },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Delete workflow",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Google Play Account Deletion & Privacy Disclosure Card
            AccountPrivacyAndDeletionCard(onDeleteAccount = onDeleteAccount)
        }
    }
}

@Composable
private fun DefenseLine(label: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EarlyAccessStatusAndTimerCard(
    profile: UserProfile?,
    tier: PlanTier,
    onSubscriptionExpired: () -> Unit
) {
    val expiresTimestamp = profile?.subscriptionExpiresAt
    val expiryMillis = expiresTimestamp?.toDate()?.time
    var nowMillis by remember(expiryMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    var triggeredExpiry by remember(expiryMillis) { mutableStateOf(false) }

    LaunchedEffect(expiryMillis, profile?.planTier, profile?.subscriptionStatus) {
        if (expiryMillis != null && (profile?.planTier != "free" || profile.subscriptionStatus == "trialing")) {
            while (true) {
                val current = System.currentTimeMillis()
                nowMillis = current
                if (current >= expiryMillis && !triggeredExpiry) {
                    triggeredExpiry = true
                    onSubscriptionExpired()
                    break
                }
                delay(1000L)
            }
        }
    }

    val expiryDateFmt = remember { SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (profile?.subscriptionStatus == "trialing") {
                            "Early Access Pro • 3 Months Free"
                        } else {
                            "${tier.displayName} Plan"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Monthly quota: ${profile?.remainingMonthlyCredits ?: 0}/${profile?.monthlyCreditsLimit ?: 10} scans remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = if (profile?.subscriptionStatus == "trialing") "FREE PASS" else tier.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            val limit = (profile?.monthlyCreditsLimit ?: 10L).coerceAtLeast(1L)
            val used = (profile?.monthlyCreditsUsed ?: 0L).coerceIn(0L, limit)
            LinearProgressIndicator(
                progress = { ((limit - used).toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            // Live 90-Day Early Access Expiration Countdown
            if (expiryMillis != null && tier != PlanTier.FREE) {
                val remMs = (expiryMillis - nowMillis).coerceAtLeast(0L)
                val totalCycleMs = 90L * 24L * 60L * 60L * 1000L
                val days = remMs / (24L * 60L * 60L * 1000L)
                val hours = (remMs / (60L * 60L * 1000L)) % 24L
                val minutes = (remMs / (60L * 1000L)) % 60L
                val seconds = (remMs / 1000L) % 60L
                val countdownStr = String.format(Locale.US, "%dd %02dh %02dm %02ds", days, hours, minutes, seconds)

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("subscription_countdown_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Free Pro pass ends in",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = countdownStr,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (remMs.toFloat() / totalCycleMs.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                        )
                        Text(
                            text = "Complimentary access valid through ${expiryDateFmt.format(expiresTimestamp.toDate())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (profile?.subscriptionStatus == "expired") {
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Your 3-month complimentary pass has ended. You are on the Free plan (10 scans/month) while paid billing is scheduled for next year.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalBackupDatabaseCard(
    zeroRetentionEnabled: Boolean,
    backupScans: List<BackupScanEntity>,
    lastBackupAtMs: Long?,
    onSyncBackupNow: () -> Unit,
    onRestoreBackupToCloud: () -> Unit,
    onBuildPortableBackupJson: suspend () -> ByteArray,
    onImportPortableBackupJson: (ByteArray) -> Unit,
    onClearLocalBackups: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFmt = remember { SimpleDateFormat("MMM d • HH:mm:ss", Locale.getDefault()) }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    val bytes = onBuildPortableBackupJson()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                            out.flush()
                        } ?: error("Unable to open destination file.")
                    }
                }
                result
                    .onSuccess { onMessage("Exported portable backup JSON (${backupScans.size} scan(s)).") }
                    .onFailure { err -> onMessage(err.localizedMessage ?: "Export failed.") }
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val readRes = withContext(Dispatchers.IO) {
                    runCatching {
                        val maxCap = 2 * 1024 * 1024 + 1024
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                total += read
                                if (total > maxCap) {
                                    throw IllegalArgumentException("Backup file exceeds 2 MB safety limit.")
                                }
                                out.write(buf, 0, read)
                            }
                            out.toByteArray()
                        } ?: throw IllegalStateException("Unable to read selected file.")
                    }
                }
                readRes
                    .onSuccess { bytes -> onImportPortableBackupJson(bytes) }
                    .onFailure { err -> onMessage(err.localizedMessage ?: "Import failed.") }
            }
        }
    }

    val pendingCloudCount = backupScans.count { !it.cloudSynced }
    val minSeq = backupScans.minOfOrNull { it.sequenceNumber }
    val maxSeq = backupScans.maxOfOrNull { it.sequenceNumber }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_backup_database_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Local Backup Database (SQLite)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Offline failover mirror & portable JSON snapshots",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "${backupScans.size} SAVED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = buildString {
                            append("Records: ${backupScans.size}")
                            if (minSeq != null && maxSeq != null) {
                                append(" • Sequence #$minSeq – #$maxSeq")
                            }
                            if (pendingCloudCount > 0) {
                                append(" • $pendingCloudCount pending cloud sync")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (lastBackupAtMs != null) {
                            "Last backup activity: ${dateFmt.format(Date(lastBackupAtMs))}"
                        } else {
                            "Auto-mirrors cloud scans & captures offline failovers"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (zeroRetentionEnabled) {
                        Text(
                            text = "Zero-Retention is ON: new scans are excluded from both cloud and local backup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            if (backupScans.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    backupScans.take(3).forEach { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = MaterialTheme.shapes.extraSmall
                                    ) {
                                        Text(
                                            text = "#${entry.sequenceNumber}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (entry.cloudSynced) "Synced" else "Local Only",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (entry.cloudSynced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSyncBackupNow,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("backup_sync_now_button")
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Backup Now")
                }
                OutlinedButton(
                    onClick = onRestoreBackupToCloud,
                    enabled = backupScans.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("backup_restore_cloud_button")
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restore Cloud")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        exportJsonLauncher.launch("lenscribe-backup-${System.currentTimeMillis() / 1000L}.json")
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("backup_export_json_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export .json")
                }
                OutlinedButton(
                    onClick = {
                        importJsonLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("backup_import_json_button")
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import .json")
                }
            }

            if (backupScans.isNotEmpty()) {
                TextButton(
                    onClick = onClearLocalBackups,
                    modifier = Modifier
                        .align(Alignment.End)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("backup_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Local Backup DB", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AccountPrivacyAndDeletionCard(
    onDeleteAccount: () -> Unit
) {
    var showPrivacySummary by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Account & All Data?") },
            text = {
                Text(
                    "This permanently removes your workspace profile, saved cloud scans, workflow rules, and local SQLite backup database. Export a .json snapshot first if you want to keep a copy."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("privacy_and_deletion_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Policy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Privacy Notice & Account Control",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Lenscribe uses Camera & Photo Picker (no broad storage access) for document capture, Google Sign-In for account sync, and Firebase AI for OCR. Turn on Zero-Data-Retention anytime to skip cloud and local storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(
                onClick = { showPrivacySummary = !showPrivacySummary },
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("toggle_privacy_summary_button")
            ) {
                Text(if (showPrivacySummary) "Hide Data Safety Details" else "View Data Safety Details")
            }

            if (showPrivacySummary) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "• Account Info: Google UID, display name, and email for multi-device sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Captured Images: Sent securely over TLS only when you tap Scan; never shared with third-party ad networks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Storage & Backups: Cloud scans live in private per-user Firestore collections; local backups stay in app-private SQLite.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Deletion Rights: Use Clear Local Backup DB or Delete Account & All Data below to wipe everything immediately.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("delete_account_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Account & All Data", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}


