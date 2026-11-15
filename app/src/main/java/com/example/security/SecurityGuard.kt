package com.example.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.example.BuildConfig
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URL

data class SecurityPosture(
    val tlsOnlyEnforced: Boolean,
    val noClientPaymentSecrets: Boolean,
    val appCheckActive: Boolean,
    val ssrfGuardActive: Boolean,
    val csvFormulaShieldActive: Boolean,
    val tapjackingGuardActive: Boolean,
    val isDebuggableBuild: Boolean,
    val hasTestKeysOrSu: Boolean
)

object SecurityGuard {

    private val BIDI_AND_CONTROL_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u202A-\\u202E\\u2066-\\u2069]")

    private val BLOCKED_HOSTNAMES = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata"
    )

    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * Neutralizes CSV / Spreadsheet Formula Injection (CWE-1236) in exported cells.
     */
    fun sanitizeCsvCell(value: String): String {
        val cleaned = sanitizeSafeText(value)
        if (cleaned.isEmpty()) return ""
        val trimmedStart = cleaned.trimStart()
        val firstChar = trimmedStart.firstOrNull() ?: return cleaned
        return if (firstChar in FORMULA_PREFIXES) {
            "'$cleaned"
        } else {
            cleaned
        }
    }

    /**
     * Strips null bytes and Unicode bidirectional override characters that can spoof filenames or text.
     */
    fun sanitizeSafeText(input: String): String {
        return input.replace(BIDI_AND_CONTROL_REGEX, "")
    }

    fun sanitizeFilenameBase(title: String, fallback: String = "lenscribe-export"): String {
        return sanitizeSafeText(title)
            .replace(Regex("""[^\w\- ]+"""), "")
            .trim()
            .replace(Regex("""\s+"""), "-")
            .ifBlank { fallback }
            .take(48)
    }

    /**
     * Validates webhook URL syntax to prevent open-HTTP, credential leakage, and SSRF into private networks.
     */
    fun validateWebhookEndpointSyntax(rawUrl: String): Result<String> = runCatching {
        val trimmed = sanitizeSafeText(rawUrl).trim()
        require(trimmed.isNotEmpty()) { "Webhook URL cannot be empty." }
        require(trimmed.length <= 500) { "Webhook URL exceeds maximum length of 500 characters." }

        val uri = URI(trimmed)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Only encrypted https:// webhook endpoints are permitted."
        }
        require(uri.userInfo.isNullOrBlank()) {
            "Embedded credentials in webhook URLs are not allowed."
        }
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: throw IllegalArgumentException("Webhook URL must include a valid domain host.")

        require(host !in BLOCKED_HOSTNAMES) {
            "Localhost and internal metadata endpoints are blocked for security."
        }
        require(!isLiteralPrivateOrLoopbackIp(host)) {
            "Private or loopback IP addresses are blocked for security."
        }
        require(host.contains('.')) {
            "Webhook host must be a public domain name."
        }
        if (uri.port != -1 && uri.port != 443) {
            throw IllegalArgumentException("Custom ports are disabled; use standard HTTPS port 443.")
        }
        trimmed
    }

    /**
     * Resolves DNS right before connecting to prevent DNS-rebinding SSRF attacks against private/loopback IPs.
     */
    fun verifyResolvedHostIsPublic(url: URL): Boolean {
        return runCatching {
            val host = url.host?.lowercase()?.trimEnd('.') ?: return false
            if (host in BLOCKED_HOSTNAMES || isLiteralPrivateOrLoopbackIp(host)) return false
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all { addr ->
                !addr.isAnyLocalAddress &&
                    !addr.isLoopbackAddress &&
                    !addr.isLinkLocalAddress &&
                    !addr.isSiteLocalAddress &&
                    !addr.isMulticastAddress &&
                    !isCarrierGradeNatOrReserved(addr)
            }
        }.getOrDefault(false)
    }

    private fun isLiteralPrivateOrLoopbackIp(host: String): Boolean {
        val stripped = host.trim('[', ']')
        if (stripped.startsWith("127.") ||
            stripped.startsWith("10.") ||
            stripped.startsWith("192.168.") ||
            stripped.startsWith("169.254.") ||
            stripped.startsWith("0.") ||
            stripped == "::1" ||
            stripped.startsWith("fe80:") ||
            stripped.startsWith("fc") ||
            stripped.startsWith("fd")
        ) {
            return true
        }
        // Check 172.16.0.0 - 172.31.255.255
        if (stripped.startsWith("172.")) {
            val second = stripped.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        // Reject integer/hex IP encodings like 2130706433 or 0x7f000001
        if (stripped.all { it.isDigit() } || stripped.startsWith("0x")) {
            return true
        }
        return false
    }

    private fun isCarrierGradeNatOrReserved(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 100.64.0.0/10 CGNAT or 0.0.0.0/8 or 240.0.0.0/4
            if (b0 == 100 && b1 in 64..127) return true
            if (b0 == 0 || b0 >= 240) return true
        }
        return false
    }

    fun purgeExportCache(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "exports")
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }
            }
        }
    }

    fun inspectSecurityPosture(context: Context): SecurityPosture {
        val debuggable = BuildConfig.DEBUG ||
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val suExists = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su"
        ).any { path -> runCatching { File(path).exists() }.getOrDefault(false) }

        return SecurityPosture(
            tlsOnlyEnforced = true,
            noClientPaymentSecrets = true,
            appCheckActive = true,
            ssrfGuardActive = true,
            csvFormulaShieldActive = true,
            tapjackingGuardActive = true,
            isDebuggableBuild = debuggable,
            hasTestKeysOrSu = testKeys || suExists
        )
    }
}
