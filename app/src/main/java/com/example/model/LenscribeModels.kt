package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class PlanTier(
    val id: String,
    val displayName: String,
    val usdPriceLabel: String,
    val ngnPriceLabel: String,
    val usdAmountMinor: Int,
    val ngnAmountMinor: Int,
    val monthlyScans: Int,
    val maxBatchPages: Int,
    val allowsAllExports: Boolean,
    val allowsCloudHistory: Boolean,
    val allowsWorkflows: Boolean,
    val tagline: String,
) {
    FREE(
        id = "free",
        displayName = "Free",
        usdPriceLabel = "$0",
        ngnPriceLabel = "₦0",
        usdAmountMinor = 0,
        ngnAmountMinor = 0,
        monthlyScans = 10,
        maxBatchPages = 3,
        allowsAllExports = false,
        allowsCloudHistory = true,
        allowsWorkflows = false,
        tagline = "Up to 3 pages per batch with TXT, Markdown & clipboard export."
    ),
    PRO(
        id = "pro",
        displayName = "Pro",
        usdPriceLabel = "$9/mo",
        ngnPriceLabel = "₦13,500/mo",
        usdAmountMinor = 900,
        ngnAmountMinor = 1350000,
        monthlyScans = 300,
        maxBatchPages = 25,
        allowsAllExports = true,
        allowsCloudHistory = true,
        allowsWorkflows = true,
        tagline = "25-page batches, handwriting & table mode, and all 7 export formats."
    ),
    PLUS(
        id = "plus",
        displayName = "Plus",
        usdPriceLabel = "$19/mo",
        ngnPriceLabel = "₦28,500/mo",
        usdAmountMinor = 1900,
        ngnAmountMinor = 2850000,
        monthlyScans = 1500,
        maxBatchPages = 40,
        allowsAllExports = true,
        allowsCloudHistory = true,
        allowsWorkflows = true,
        tagline = "Priority batch processing, accounting CSV templates, and webhook sync."
    ),
    TEAM(
        id = "team",
        displayName = "Team",
        usdPriceLabel = "$39/mo",
        ngnPriceLabel = "₦58,500/mo",
        usdAmountMinor = 3900,
        ngnAmountMinor = 5850000,
        monthlyScans = 5000,
        maxBatchPages = 50,
        allowsAllExports = true,
        allowsCloudHistory = true,
        allowsWorkflows = true,
        tagline = "5 seats, pooled scan credits, and zero-data-retention mode."
    );

    companion object {
        fun fromId(id: String): PlanTier = entries.firstOrNull { it.id == id } ?: FREE
    }
}

enum class PaymentProvider(
    val id: String,
    val displayName: String,
    val regionNote: String,
    val supportsNgn: Boolean,
    val checkoutBaseUrl: String
) {
    PADDLE(
        id = "paddle",
        displayName = "Paddle",
        regionNote = "Global MoR • Cards, Apple Pay, Google Pay (NG payouts supported)",
        supportsNgn = false,
        checkoutBaseUrl = "https://checkout.paddle.com/lenscribe"
    ),
    LEMON_SQUEEZY(
        id = "lemonsqueezy",
        displayName = "Lemon Squeezy",
        regionNote = "Global MoR • Automatic VAT/Sales Tax + Payouts",
        supportsNgn = false,
        checkoutBaseUrl = "https://lenscribe.lemonsqueezy.com/checkout"
    ),
    POLAR(
        id = "polar",
        displayName = "Polar.sh",
        regionNote = "Global MoR • Developer & SaaS Metering",
        supportsNgn = false,
        checkoutBaseUrl = "https://polar.sh/lenscribe/checkout"
    ),
    PAYSTACK(
        id = "paystack",
        displayName = "Paystack",
        regionNote = "Nigeria & Africa • Bank Transfer, USSD & Local Naira Cards",
        supportsNgn = true,
        checkoutBaseUrl = "https://paystack.com/pay/lenscribe"
    );

    companion object {
        fun fromId(id: String): PaymentProvider = entries.firstOrNull { it.id == id } ?: PADDLE
    }
}

enum class ScanMode(
    val id: String,
    val label: String,
    val subtitle: String
) {
    FAST("fast", "Fast Scan", "Clean printed text & screenshots"),
    PRECISION("precision", "Precision & Notes", "Handwriting, low light & angled pages"),
    TABLE("table", "Table & Receipt", "Strict columns for Excel & CSV")
}

enum class SmartPreset(
    val id: String,
    val label: String,
    val instruction: String
) {
    LAYOUT(
        id = "layout",
        label = "Preserve exact layout",
        instruction = "Transcribe every visible word verbatim. Preserve reading order, headings, paragraphs, lists, and spatial layout using clean Markdown."
    ),
    TABLE(
        id = "table",
        label = "Extract as table",
        instruction = "Extract all structured rows, receipts, prices, or tabular records into clean Markdown tables with aligned columns and totals."
    ),
    LINE_BREAKS(
        id = "line_breaks",
        label = "Fix broken line breaks",
        instruction = "Transcribe verbatim while joining mid-sentence wrapped lines from narrow columns into smooth, readable paragraphs."
    ),
    TRANSLATE_EN(
        id = "translate_en",
        label = "Translate to English",
        instruction = "Transcribe the document accurately and translate all non-English content into clear, natural English while preserving structure."
    ),
    CLEAN_PUNCTUATION(
        id = "clean_punctuation",
        label = "Clean punctuation",
        instruction = "Transcribe verbatim, normalizing broken OCR punctuation, stray hyphens, and spacing while keeping every number and name untouched."
    ),
    CODE_MATH(
        id = "code_math",
        label = "Code & Math (LaTeX)",
        instruction = "Preserve source code indentation inside fenced code blocks and render mathematical formulas using clean LaTeX expressions."
    )
}

enum class ExportFormat(
    val ext: String,
    val label: String,
    val mimeType: String,
    val freeTierAllowed: Boolean
) {
    DOCX("docx", "Word (.docx)", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false),
    XLSX("xlsx", "Excel (.xlsx)", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false),
    PPTX("pptx", "Slides (.pptx)", "application/vnd.openxmlformats-officedocument.presentationml.presentation", false),
    PDF("pdf", "PDF (.pdf)", "application/pdf", false),
    CSV("csv", "CSV (.csv)", "text/csv", false),
    MARKDOWN("md", "Markdown (.md)", "text/markdown", true),
    TXT("txt", "Plain Text (.txt)", "text/plain", true),
    PNG_CARD("png", "Share Card (.png)", "image/png", false)
}

enum class CardTheme(val id: String, val label: String) {
    EMERALD("emerald", "Emerald"),
    MIDNIGHT("midnight", "Midnight"),
    PAPER("paper", "Paper")
}

enum class CardAspect(val id: String, val label: String, val width: Int, val height: Int) {
    SQUARE("square", "Square 1:1", 1080, 1080),
    PORTRAIT("portrait", "Portrait 4:5", 1080, 1350),
    WIDE("wide", "Wide 16:9", 1200, 630)
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String? = null,
    val displayName: String = "Workspace User",
    val planTier: String = "free",
    val monthlyCreditsLimit: Long = 10L,
    val monthlyCreditsUsed: Long = 0L,
    val paygCredits: Long = 0L,
    val preferredCurrency: String = "USD",
    val paymentProvider: String = "none",
    val subscriptionStatus: String = "none",
    val subscriptionExpiresAt: Timestamp? = null,
    val zeroRetentionEnabled: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val tier: PlanTier
        get() = PlanTier.fromId(planTier)

    val remainingMonthlyCredits: Long
        get() = (monthlyCreditsLimit - monthlyCreditsUsed).coerceAtLeast(0L)

    val totalRemainingCredits: Long
        get() = remainingMonthlyCredits + paygCredits.coerceAtLeast(0L)

    fun remainingSubscriptionMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val expires = subscriptionExpiresAt?.toDate()?.time ?: return 0L
        return (expires - nowMillis).coerceAtLeast(0L)
    }

    fun isSubscriptionExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expires = subscriptionExpiresAt?.toDate()?.time ?: return false
        return (planTier != "free" || subscriptionStatus == "trialing") && nowMillis >= expires
    }
}

@IgnoreExtraProperties
data class ScanDocument(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "Untitled Scan",
    val markdown: String = "",
    val mode: String = "fast",
    val preset: String = "layout",
    val pageCount: Long = 1L,
    val hasTable: Boolean = false,
    val zeroRetention: Boolean = false,
    val lowConfidenceTerms: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BillingOrder(
    @DocumentId val id: String = "",
    val userId: String = "",
    val planId: String = "pro",
    val provider: String = "paddle",
    val currency: String = "USD",
    val amountMinor: Long = 900L,
    val creditsGranted: Long = 300L,
    val status: String = "completed",
    val reference: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class WorkflowAutomation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val trigger: String = "on_scan_complete",
    val destinationType: String = "webhook_json",
    val endpointUrl: String = "",
    val enabled: Boolean = true,
    val lastStatus: String = "idle",
    val lastTriggeredAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
