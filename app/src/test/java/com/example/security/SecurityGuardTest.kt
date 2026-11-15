package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityGuardTest {

    @Test
    fun sanitizeCsvCell_neutralizesFormulaInjectionPayloads() {
        assertEquals("'=cmd|' /C calc'!A0", SecurityGuard.sanitizeCsvCell("=cmd|' /C calc'!A0"))
        assertEquals("'+SUM(1,2)", SecurityGuard.sanitizeCsvCell("+SUM(1,2)"))
        assertEquals("'-2+3", SecurityGuard.sanitizeCsvCell("-2+3"))
        assertEquals("'@SUM(1+1)*cmd|' /C calc'!A0", SecurityGuard.sanitizeCsvCell("@SUM(1+1)*cmd|' /C calc'!A0"))
        assertEquals("Normal Receipt Total 10.50", SecurityGuard.sanitizeCsvCell("Normal Receipt Total 10.50"))
    }

    @Test
    fun sanitizeFilenameBase_stripsBidiOverrideAndTraversalChars() {
        val malicious = "../../etc/passwd\u202Egpj.exe"
        val safe = SecurityGuard.sanitizeFilenameBase(malicious)
        assertTrue(!safe.contains("/"))
        assertTrue(!safe.contains(".."))
        assertTrue(!safe.contains("\u202E"))
    }

    @Test
    fun validateWebhookEndpointSyntax_blocksHttpCredentialsAndSsrfTargets() {
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("http://hooks.example.com/hook").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://user:pass@hooks.example.com/hook").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://localhost/admin").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://127.0.0.1/hook").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://169.254.169.254/latest/meta-data").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://10.0.0.5/internal").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://172.16.0.12/internal").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://192.168.1.1/router").isFailure)
        assertTrue(SecurityGuard.validateWebhookEndpointSyntax("https://hooks.example.com:8443/custom").isFailure)

        val valid = SecurityGuard.validateWebhookEndpointSyntax("https://hooks.example.com/lenscribe")
        assertTrue(valid.isSuccess)
        assertEquals("https://hooks.example.com/lenscribe", valid.getOrNull())
    }
}
