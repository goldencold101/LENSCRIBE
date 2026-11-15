package com.example.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.model.CardAspect
import com.example.model.CardTheme
import com.example.model.ExportFormat
import com.example.security.SecurityGuard
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocumentExporter {

    private const val XML_DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val X_MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"

    fun exportBytes(
        format: ExportFormat,
        markdown: String,
        title: String,
        cardTheme: CardTheme = CardTheme.EMERALD,
        cardAspect: CardAspect = CardAspect.SQUARE
    ): ByteArray {
        val safeTitle = title.ifBlank { "Lenscribe Transcription" }
        return when (format) {
            ExportFormat.DOCX -> buildDocx(markdown, safeTitle)
            ExportFormat.XLSX -> buildXlsx(markdown, safeTitle)
            ExportFormat.PPTX -> buildPptx(markdown, safeTitle)
            ExportFormat.PDF -> buildPdf(markdown, safeTitle)
            ExportFormat.CSV -> buildCsv(markdown)
            ExportFormat.TXT -> buildTxt(markdown)
            ExportFormat.MARKDOWN -> markdown.toByteArray(Charsets.UTF_8)
            ExportFormat.PNG_CARD -> buildTextCardPng(markdown, safeTitle, cardTheme, cardAspect)
        }
    }

    fun writeAndShare(
        context: Context,
        format: ExportFormat,
        markdown: String,
        title: String,
        cardTheme: CardTheme = CardTheme.EMERALD,
        cardAspect: CardAspect = CardAspect.SQUARE
    ) {
        val bytes = exportBytes(format, markdown, title, cardTheme, cardAspect)
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val sanitized = SecurityGuard.sanitizeFilenameBase(title, "lenscribe-export")
        val outFile = File(exportsDir, "$sanitized.${format.ext}")
        outFile.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "$title.${format.ext}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Export ${format.label}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun shareBitmap(context: Context, bitmap: Bitmap, fileNameBase: String = "lenscribe-studio") {
        val bytes = bitmapToPngBytes(bitmap)
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val sanitized = SecurityGuard.sanitizeFilenameBase(fileNameBase, "lenscribe-studio").take(40)
        val outFile = File(exportsDir, "$sanitized.png")
        outFile.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Image").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    // -----------------------------------------------------------------------
    // Plain Text & CSV
    // -----------------------------------------------------------------------

    fun buildTxt(markdown: String): ByteArray {
        val sections = mutableListOf<String>()
        for (block in MarkdownParser.parseBlocks(markdown)) {
            when (block) {
                is MarkdownBlock.Heading -> sections.add(MarkdownParser.plainText(block.text).trim())
                is MarkdownBlock.Paragraph -> sections.add(MarkdownParser.plainText(block.text))
                is MarkdownBlock.Quote -> sections.add("> " + MarkdownParser.plainText(block.text).replace("\n", "\n> "))
                is MarkdownBlock.Bullet -> sections.add("- " + MarkdownParser.plainText(block.text))
                is MarkdownBlock.Numbered -> sections.add("${block.number}. ${MarkdownParser.plainText(block.text)}")
                is MarkdownBlock.Code -> sections.add(block.text.lines().joinToString("\n") { "    $it" })
                is MarkdownBlock.Table -> {
                    val rows = block.rows
                    if (rows.isNotEmpty()) {
                        val cols = rows.maxOf { it.size }
                        val widths = (0 until cols).map { c ->
                            rows.maxOf { r -> MarkdownParser.plainText(r.getOrElse(c) { "" }).length }.coerceAtLeast(3)
                        }
                        val lines = rows.map { r ->
                            (0 until cols).joinToString(" | ") { c ->
                                MarkdownParser.plainText(r.getOrElse(c) { "" }).padEnd(widths[c])
                            }.trimEnd()
                        }
                        val sep = widths.joinToString("-+-") { "-".repeat(it) }
                        sections.add((listOf(lines.first(), sep) + lines.drop(1)).joinToString("\n"))
                    }
                }
                is MarkdownBlock.HorizontalRule -> sections.add("-".repeat(48))
            }
        }
        return (sections.filter { it.isNotBlank() }.joinToString("\n\n") + "\n").toByteArray(Charsets.UTF_8)
    }

    fun buildCsv(markdown: String): ByteArray {
        val blocks = MarkdownParser.parseBlocks(markdown)
        val tables = blocks.filterIsInstance<MarkdownBlock.Table>()
        val rows = mutableListOf<List<String>>()
        if (tables.isNotEmpty()) {
            tables.forEachIndexed { idx, table ->
                if (idx > 0) rows.add(emptyList())
                table.rows.forEach { row ->
                    rows.add(row.map { MarkdownParser.plainText(it) })
                }
            }
        } else {
            for (block in blocks) {
                when (block) {
                    is MarkdownBlock.Heading -> rows.add(listOf(MarkdownParser.plainText(block.text).trim()))
                    is MarkdownBlock.Paragraph -> rows.add(listOf(MarkdownParser.plainText(block.text).replace("\n", " ")))
                    is MarkdownBlock.Quote -> rows.add(listOf("> " + MarkdownParser.plainText(block.text).replace("\n", " ")))
                    is MarkdownBlock.Bullet -> rows.add(listOf("• " + MarkdownParser.plainText(block.text)))
                    is MarkdownBlock.Numbered -> rows.add(listOf("${block.number}. ${MarkdownParser.plainText(block.text)}"))
                    is MarkdownBlock.Code -> block.text.lines().forEach { rows.add(listOf(it)) }
                    is MarkdownBlock.HorizontalRule -> rows.add(listOf(""))
                    is MarkdownBlock.Table -> Unit
                }
            }
        }
        val sb = StringBuilder()
        rows.forEach { row ->
            sb.append(
                row.joinToString(",") { rawCell ->
                    val cell = SecurityGuard.sanitizeCsvCell(rawCell)
                    val needsQuote = cell.contains(',') || cell.contains('"') || cell.contains('\n')
                    if (needsQuote) "\"${cell.replace("\"", "\"\"")}\"" else cell
                }
            ).append("\r\n")
        }
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sb.toString().toByteArray(Charsets.UTF_8)
    }

    // -----------------------------------------------------------------------
    // PDF (Android PdfDocument)
    // -----------------------------------------------------------------------

    fun buildPdf(markdown: String, title: String): ByteArray {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val contentWidth = (pageWidth - margin * 2).toInt()
        val bottomLimit = pageHeight - 54f

        val pdf = PdfDocument()
        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = margin

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 130, 145)
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }

        fun finishAndStartNewPage() {
            canvas.drawText("Lenscribe  •  Page $pageNumber", pageWidth / 2f, pageHeight - 24f, footerPaint)
            pdf.finishPage(page)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = margin
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > bottomLimit) {
                finishAndStartNewPage()
            }
        }

        fun drawWrappedText(
            text: String,
            size: Float,
            color: Int,
            bold: Boolean = false,
            mono: Boolean = false,
            italic: Boolean = false,
            leftOffset: Float = 0f,
            extraGap: Float = 8f
        ) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = size
                this.color = color
                typeface = when {
                    mono -> Typeface.MONOSPACE
                    bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    else -> Typeface.DEFAULT
                }
            }
            val width = (contentWidth - leftOffset.toInt()).coerceAtLeast(100)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.18f)
                .build()
            ensureSpace(layout.height.toFloat() + extraGap)
            canvas.save()
            canvas.translate(margin + leftOffset, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + extraGap
        }

        drawWrappedText(title, 20f, Color.rgb(15, 118, 110), bold = true, extraGap = 14f)

        for (block in MarkdownParser.parseBlocks(markdown)) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    val sz = when (block.level) {
                        1 -> 16f
                        2 -> 14f
                        else -> 12f
                    }
                    drawWrappedText(MarkdownParser.plainText(block.text), sz, Color.rgb(15, 118, 110), bold = true, extraGap = 10f)
                }
                is MarkdownBlock.Paragraph -> {
                    drawWrappedText(MarkdownParser.plainText(block.text), 11f, Color.rgb(30, 41, 59), extraGap = 8f)
                }
                is MarkdownBlock.Bullet -> {
                    drawWrappedText("•  " + MarkdownParser.plainText(block.text), 11f, Color.rgb(30, 41, 59), leftOffset = 10f, extraGap = 5f)
                }
                is MarkdownBlock.Numbered -> {
                    drawWrappedText("${block.number}.  " + MarkdownParser.plainText(block.text), 11f, Color.rgb(30, 41, 59), leftOffset = 10f, extraGap = 5f)
                }
                is MarkdownBlock.Quote -> {
                    drawWrappedText("“${MarkdownParser.plainText(block.text)}”", 10.5f, Color.rgb(71, 85, 105), italic = true, leftOffset = 14f, extraGap = 8f)
                }
                is MarkdownBlock.Code -> {
                    drawWrappedText(block.text, 9.5f, Color.rgb(15, 23, 42), mono = true, leftOffset = 8f, extraGap = 8f)
                }
                is MarkdownBlock.HorizontalRule -> {
                    ensureSpace(14f)
                    val linePaint = Paint().apply {
                        color = Color.rgb(203, 213, 225)
                        strokeWidth = 1f
                    }
                    canvas.drawLine(margin, y + 4f, pageWidth - margin, y + 4f, linePaint)
                    y += 14f
                }
                is MarkdownBlock.Table -> {
                    val rows = block.rows
                    if (rows.isEmpty()) continue
                    val cols = rows.maxOf { it.size }.coerceAtLeast(1)
                    val colW = contentWidth.toFloat() / cols
                    val cellPad = 5f
                    val borderPaint = Paint().apply {
                        color = Color.rgb(203, 213, 225)
                        style = Paint.Style.STROKE
                        strokeWidth = 0.8f
                    }
                    val headerFill = Paint().apply {
                        color = Color.rgb(209, 250, 229)
                        style = Paint.Style.FILL
                    }
                    rows.forEachIndexed { rowIdx, row ->
                        val isHeader = rowIdx == 0
                        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = 9.5f
                            color = if (isHeader) Color.rgb(6, 78, 59) else Color.rgb(30, 41, 59)
                            typeface = if (isHeader) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                        }
                        val layouts = (0 until cols).map { c ->
                            val cellText = MarkdownParser.plainText(row.getOrElse(c) { "" })
                            StaticLayout.Builder.obtain(
                                cellText,
                                0,
                                cellText.length,
                                tp,
                                (colW - cellPad * 2).toInt().coerceAtLeast(24)
                            ).build()
                        }
                        val rowH = (layouts.maxOfOrNull { it.height } ?: 14) + cellPad * 2
                        ensureSpace(rowH)
                        if (isHeader) {
                            canvas.drawRect(margin, y, margin + contentWidth, y + rowH, headerFill)
                        }
                        layouts.forEachIndexed { c, layout ->
                            val cx = margin + c * colW
                            canvas.drawRect(cx, y, cx + colW, y + rowH, borderPaint)
                            canvas.save()
                            canvas.translate(cx + cellPad, y + cellPad)
                            layout.draw(canvas)
                            canvas.restore()
                        }
                        y += rowH
                    }
                    y += 10f
                }
            }
        }

        canvas.drawText("Lenscribe  •  Page $pageNumber", pageWidth / 2f, pageHeight - 24f, footerPaint)
        pdf.finishPage(page)

        val out = ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------
    // DOCX (OpenXML Word Document)
    // -----------------------------------------------------------------------

    fun buildDocx(markdown: String, title: String): ByteArray {
        val body = StringBuilder()
        body.append(docxParagraph(docxRun(title, bold = true), style = "Title"))

        for (block in MarkdownParser.parseBlocks(markdown)) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = "Heading${block.level.coerceIn(1, 6)}"
                    body.append(docxParagraph(docxRun(MarkdownParser.plainText(block.text)), style = style))
                }
                is MarkdownBlock.Paragraph -> {
                    body.append(docxParagraph(docxRun(MarkdownParser.plainText(block.text))))
                }
                is MarkdownBlock.Bullet -> {
                    body.append(docxParagraph(docxRun("•  " + MarkdownParser.plainText(block.text)), indent = 360))
                }
                is MarkdownBlock.Numbered -> {
                    body.append(docxParagraph(docxRun("${block.number}.  " + MarkdownParser.plainText(block.text)), indent = 360))
                }
                is MarkdownBlock.Quote -> {
                    body.append(docxParagraph(docxRun(MarkdownParser.plainText(block.text)), style = "Quote"))
                }
                is MarkdownBlock.Code -> {
                    block.text.lines().ifEmpty { listOf(" ") }.forEach { line ->
                        body.append(docxParagraph(docxRun(line.ifEmpty { " " }, code = true), style = "Code"))
                    }
                }
                is MarkdownBlock.HorizontalRule -> {
                    body.append(docxParagraph("", borderBottom = true))
                }
                is MarkdownBlock.Table -> {
                    body.append(docxTable(block.rows))
                    body.append("<w:p/>")
                }
            }
        }

        val documentXml = """$XML_DECL
<w:document xmlns:w="$W_NS" xmlns:r="$R_NS"><w:body>
$body
<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>
</w:body></w:document>"""

        val stylesXml = """$XML_DECL
<w:styles xmlns:w="$W_NS">
  <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
  <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:color w:val="0B3B2E"/><w:sz w:val="48"/></w:rPr></w:style>
  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:color w:val="0F766E"/><w:sz w:val="36"/></w:rPr></w:style>
  <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:color w:val="0F766E"/><w:sz w:val="30"/></w:rPr></w:style>
  <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:color w:val="334155"/><w:sz w:val="26"/></w:rPr></w:style>
  <w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="420"/></w:pPr><w:rPr><w:i/><w:color w:val="475569"/></w:rPr></w:style>
  <w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="19"/></w:rPr></w:style>
</w:styles>"""

        val contentTypes = """$XML_DECL
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>"""

        val rootRels = """$XML_DECL
<Relationships xmlns="$PKG_REL_NS">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

        val docRels = """$XML_DECL
<Relationships xmlns="$PKG_REL_NS">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

        return zipEntries(
            mapOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to rootRels,
                "word/document.xml" to documentXml,
                "word/styles.xml" to stylesXml,
                "word/_rels/document.xml.rels" to docRels,
                "docProps/core.xml" to corePropsXml(title)
            )
        )
    }

    private fun docxRun(text: String, bold: Boolean = false, code: Boolean = false): String {
        val props = buildString {
            if (bold) append("<w:b/>")
            if (code) append("""<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="19"/>""")
        }
        val rpr = if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else ""
        return """<w:r>$rpr<w:t xml:space="preserve">${xmlEscape(text)}</w:t></w:r>"""
    }

    private fun docxParagraph(
        runs: String,
        style: String? = null,
        indent: Int? = null,
        borderBottom: Boolean = false
    ): String {
        val props = buildString {
            if (style != null) append("""<w:pStyle w:val="$style"/>""")
            if (borderBottom) append("""<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="CBD5E1"/></w:pBdr>""")
            if (indent != null) append("""<w:ind w:left="$indent"/>""")
        }
        val ppr = if (props.isNotEmpty()) "<w:pPr>$props</w:pPr>" else ""
        return "<w:p>$ppr$runs</w:p>"
    }

    private fun docxTable(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val grid = (0 until cols).joinToString("") { """<w:gridCol w:w="1800"/>""" }
        val borders = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
            """<w:$it w:val="single" w:sz="4" w:space="0" w:color="CBD5E1"/>"""
        }
        val trs = rows.mapIndexed { rIdx, row ->
            val cells = (0 until cols).joinToString("") { c ->
                val text = MarkdownParser.plainText(row.getOrElse(c) { "" })
                val shade = if (rIdx == 0) """<w:shd w:val="clear" w:color="auto" w:fill="E8F5F1"/>""" else ""
                val run = if (text.isNotEmpty()) docxRun(text, bold = rIdx == 0) else ""
                """<w:tc><w:tcPr><w:tcW w:w="1800" w:type="dxa"/>$shade</w:tcPr>${docxParagraph(run)}</w:tc>"""
            }
            "<w:tr>$cells</w:tr>"
        }.joinToString("")
        return """<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblBorders>$borders</w:tblBorders></w:tblPr><w:tblGrid>$grid</w:tblGrid>$trs</w:tbl>"""
    }

    // -----------------------------------------------------------------------
    // XLSX (OpenXML Spreadsheet)
    // -----------------------------------------------------------------------

    fun buildXlsx(markdown: String, title: String): ByteArray {
        val transcript = mutableListOf<List<Pair<String, Int>>>()
        val tables = mutableListOf<List<List<String>>>()
        transcript.add(listOf(title to 1))

        for (block in MarkdownParser.parseBlocks(markdown)) {
            when (block) {
                is MarkdownBlock.Heading -> transcript.add(listOf(MarkdownParser.plainText(block.text).trim() to 2))
                is MarkdownBlock.Paragraph -> transcript.add(listOf(MarkdownParser.plainText(block.text).replace("\n", " ") to 6))
                is MarkdownBlock.Quote -> transcript.add(listOf(("> " + MarkdownParser.plainText(block.text).replace("\n", " ")) to 6))
                is MarkdownBlock.Bullet -> transcript.add(listOf(("•  " + MarkdownParser.plainText(block.text)) to 6))
                is MarkdownBlock.Numbered -> transcript.add(listOf("${block.number}.  ${MarkdownParser.plainText(block.text)}" to 6))
                is MarkdownBlock.Code -> block.text.lines().forEach { transcript.add(listOf(it to 3)) }
                is MarkdownBlock.Table -> if (block.rows.isNotEmpty()) tables.add(block.rows)
                is MarkdownBlock.HorizontalRule -> transcript.add(emptyList())
            }
        }

        val sheets = mutableListOf<Pair<String, String>>()
        sheets.add("Transcript" to xlsxSheetXml(transcript.ifEmpty { listOf(listOf("" to 6)) }, listOf(100f), freezeHeader = false))

        tables.forEachIndexed { idx, rows ->
            val cols = rows.maxOf { it.size }.coerceAtLeast(1)
            val styled = rows.mapIndexed { rIdx, row ->
                (0 until cols).map { c ->
                    MarkdownParser.plainText(row.getOrElse(c) { "" }) to (if (rIdx == 0) 4 else 5)
                }
            }
            val widths = (0 until cols).map { c ->
                val longest = rows.maxOf { r -> MarkdownParser.plainText(r.getOrElse(c) { "" }).length }
                (longest * 1.1f + 3f).coerceIn(12f, 52f)
            }
            sheets.add("Table ${idx + 1}" to xlsxSheetXml(styled, widths, freezeHeader = true))
        }

        val stylesXml = """$XML_DECL
<styleSheet xmlns="$X_MAIN_NS">
  <fonts count="5">
    <font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>
    <font><b/><sz val="16"/><color rgb="FF0F766E"/><name val="Calibri"/></font>
    <font><b/><sz val="12"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
    <font><sz val="10"/><color rgb="FF334155"/><name val="Consolas"/></font>
    <font><b/><sz val="11"/><color rgb="FF064E3B"/><name val="Calibri"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD1FAE5"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border><left style="thin"><color rgb="FFCBD5E1"/></left><right style="thin"><color rgb="FFCBD5E1"/></right><top style="thin"><color rgb="FFCBD5E1"/></top><bottom style="thin"><color rgb="FFCBD5E1"/></bottom></border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="7">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="4" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
  </cellXfs>
</styleSheet>"""

        val contentTypes = buildString {
            append(XML_DECL)
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            sheets.indices.forEach { i ->
                append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            }
            append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
            append("</Types>")
        }

        val workbookXml = buildString {
            append(XML_DECL)
            append("""<workbook xmlns="$X_MAIN_NS" xmlns:r="$R_NS"><sheets>""")
            sheets.forEachIndexed { i, (name, _) ->
                append("""<sheet name="${xmlEscape(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            append("</sheets></workbook>")
        }

        val workbookRels = buildString {
            append(XML_DECL)
            append("""<Relationships xmlns="$PKG_REL_NS">""")
            sheets.indices.forEach { i ->
                append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
            }
            append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("</Relationships>")
        }

        val rootRels = """$XML_DECL
<Relationships xmlns="$PKG_REL_NS">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

        val entries = mutableMapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rootRels,
            "xl/workbook.xml" to workbookXml,
            "xl/_rels/workbook.xml.rels" to workbookRels,
            "xl/styles.xml" to stylesXml,
            "docProps/core.xml" to corePropsXml(title)
        )
        sheets.forEachIndexed { i, (_, xml) ->
            entries["xl/worksheets/sheet${i + 1}.xml"] = xml
        }
        return zipEntries(entries)
    }

    private fun xlsxColumnName(index: Int): String {
        var i = index + 1
        val sb = StringBuilder()
        while (i > 0) {
            val rem = (i - 1) % 26
            sb.insert(0, ('A'.code + rem).toChar())
            i = (i - 1) / 26
        }
        return sb.toString()
    }

    private fun xlsxSheetXml(
        rows: List<List<Pair<String, Int>>>,
        widths: List<Float>,
        freezeHeader: Boolean
    ): String {
        val rowXml = rows.mapIndexed { rIdx, cells ->
            val rNum = rIdx + 1
            if (cells.isEmpty()) {
                """<row r="$rNum"/>"""
            } else {
                val cellXml = cells.mapIndexed { cIdx, (text, style) ->
                    val ref = "${xlsxColumnName(cIdx)}$rNum"
                    val safeCell = SecurityGuard.sanitizeCsvCell(text).take(32767)
                    """<c r="$ref" t="inlineStr" s="$style"><is><t xml:space="preserve">${xmlEscape(safeCell)}</t></is></c>"""
                }.joinToString("")
                """<row r="$rNum">$cellXml</row>"""
            }
        }.joinToString("")

        val cols = widths.mapIndexed { i, w ->
            """<col min="${i + 1}" max="${i + 1}" width="${String.format(Locale.US, "%.1f", w)}" customWidth="1"/>"""
        }.joinToString("")
        val pane = if (freezeHeader) """<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""" else ""
        return """$XML_DECL
<worksheet xmlns="$X_MAIN_NS" xmlns:r="$R_NS">
  <sheetViews><sheetView workbookViewId="0">$pane</sheetView></sheetViews>
  <cols>$cols</cols>
  <sheetData>$rowXml</sheetData>
</worksheet>"""
    }

    // -----------------------------------------------------------------------
    // PPTX (OpenXML Presentation)
    // -----------------------------------------------------------------------

    fun buildPptx(markdown: String, title: String): ByteArray {
        val blocks = MarkdownParser.parseBlocks(markdown)
        val slides = mutableListOf<String>()

        // Slide 1: Title Slide
        slides.add(
            pptxSlideXml(
                listOf(
                    pptxTextBox(2, "Title", 731520, 2286000, 10728960, 1371600, listOf(pptxParagraph(title, size = 4000, bold = true, color = "0F766E"))),
                    pptxTextBox(3, "Subtitle", 731520, 3840480, 10728960, 914400, listOf(pptxParagraph("Transcribed with Lenscribe", size = 1800, color = "475569")))
                )
            )
        )

        var currentTitle = title
        val bufferParagraphs = mutableListOf<String>()

        fun flushContentSlide() {
            if (bufferParagraphs.isEmpty()) return
            slides.add(
                pptxSlideXml(
                    listOf(
                        pptxTextBox(2, "Title", 731520, 457200, 10728960, 762000, listOf(pptxParagraph(currentTitle, size = 2600, bold = true, color = "0F766E"))),
                        pptxTextBox(3, "Content", 731520, 1371600, 10728960, 5029200, bufferParagraphs.toList())
                    )
                )
            )
            bufferParagraphs.clear()
        }

        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    if (block.level <= 2) {
                        flushContentSlide()
                        currentTitle = MarkdownParser.plainText(block.text).ifBlank { title }
                    } else {
                        bufferParagraphs.add(pptxParagraph(MarkdownParser.plainText(block.text), size = 1900, bold = true, color = "334155"))
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    bufferParagraphs.add(pptxParagraph(MarkdownParser.plainText(block.text).replace("\n", " ")))
                }
                is MarkdownBlock.Bullet -> {
                    bufferParagraphs.add(pptxParagraph("•  " + MarkdownParser.plainText(block.text)))
                }
                is MarkdownBlock.Numbered -> {
                    bufferParagraphs.add(pptxParagraph("${block.number}.  " + MarkdownParser.plainText(block.text)))
                }
                is MarkdownBlock.Quote -> {
                    bufferParagraphs.add(pptxParagraph("“${MarkdownParser.plainText(block.text)}”", italic = true, color = "475569"))
                }
                is MarkdownBlock.Code -> {
                    block.text.lines().forEach { line ->
                        bufferParagraphs.add(pptxParagraph(line.ifEmpty { " " }, size = 1400, mono = true))
                    }
                }
                is MarkdownBlock.Table -> {
                    flushContentSlide()
                    block.rows.forEach { row ->
                        bufferParagraphs.add(pptxParagraph(row.joinToString("   |   ") { MarkdownParser.plainText(it) }, size = 1500))
                    }
                    flushContentSlide()
                }
                is MarkdownBlock.HorizontalRule -> flushContentSlide()
            }
            if (bufferParagraphs.size >= 10) {
                flushContentSlide()
            }
        }
        flushContentSlide()

        val slideCount = slides.size
        val contentTypes = buildString {
            append(XML_DECL)
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
            append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
            append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
            (1..slideCount).forEach { i ->
                append("""<Override PartName="/ppt/slides/slide$i.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
            }
            append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
            append("</Types>")
        }

        val presentationXml = buildString {
            append("""$XML_DECL<p:presentation xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS">""")
            append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
            append("<p:sldIdLst>")
            (1..slideCount).forEach { i ->
                append("""<p:sldId id="${255 + i}" r:id="rId${i + 1}"/>""")
            }
            append("""</p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""")
        }

        val presRels = buildString {
            append("""$XML_DECL<Relationships xmlns="$PKG_REL_NS">""")
            append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
            (1..slideCount).forEach { i ->
                append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide$i.xml"/>""")
            }
            append("</Relationships>")
        }

        val masterXml = """$XML_DECL<p:sldMaster xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>"""
        val masterRels = """$XML_DECL<Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
        val layoutXml = """$XML_DECL<p:sldLayout xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS" type="blank"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld></p:sldLayout>"""
        val layoutRels = """$XML_DECL<Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
        val slideRels = """$XML_DECL<Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
        val rootRels = """$XML_DECL<Relationships xmlns="$PKG_REL_NS"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

        val entries = mutableMapOf(
            "[Content_Types].xml" to contentTypes,
            "_rels/.rels" to rootRels,
            "ppt/presentation.xml" to presentationXml,
            "ppt/_rels/presentation.xml.rels" to presRels,
            "ppt/slideMasters/slideMaster1.xml" to masterXml,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to masterRels,
            "ppt/slideLayouts/slideLayout1.xml" to layoutXml,
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to layoutRels,
            "docProps/core.xml" to corePropsXml(title)
        )
        slides.forEachIndexed { idx, xml ->
            val n = idx + 1
            entries["ppt/slides/slide$n.xml"] = xml
            entries["ppt/slides/_rels/slide$n.xml.rels"] = slideRels
        }
        return zipEntries(entries)
    }

    private fun pptxParagraph(
        text: String,
        size: Int = 1800,
        bold: Boolean = false,
        italic: Boolean = false,
        color: String = "1E293B",
        mono: Boolean = false
    ): String {
        val face = if (mono) "Consolas" else "Calibri"
        val b = if (bold) """ b="1"""" else ""
        val it = if (italic) """ i="1"""" else ""
        return """<a:p><a:r><a:rPr lang="en-US" sz="$size"$b$it dirty="0"><a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:latin typeface="$face"/></a:rPr><a:t xml:space="preserve">${xmlEscape(text)}</a:t></a:r></a:p>"""
    }

    private fun pptxTextBox(id: Int, name: String, x: Int, y: Int, cx: Int, cy: Int, paragraphs: List<String>): String {
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" anchor="t"><a:normAutofit/></a:bodyPr><a:lstStyle/>${paragraphs.joinToString("")}</p:txBody></p:sp>"""
    }

    private fun pptxSlideXml(shapes: List<String>): String {
        return """$XML_DECL<p:sld xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>${shapes.joinToString("")}</p:spTree></p:cSld></p:sld>"""
    }

    // -----------------------------------------------------------------------
    // PNG Share Card (matching image_card.py aesthetics)
    // -----------------------------------------------------------------------

    fun buildTextCardPng(
        markdown: String,
        title: String,
        theme: CardTheme = CardTheme.EMERALD,
        aspect: CardAspect = CardAspect.SQUARE
    ): ByteArray {
        val bitmap = buildTextCardBitmap(markdown, title, theme, aspect)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun buildTextCardBitmap(
        markdown: String,
        title: String,
        theme: CardTheme = CardTheme.EMERALD,
        aspect: CardAspect = CardAspect.SQUARE
    ): Bitmap {
        val w = aspect.width
        val h = aspect.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val (topColor, bottomColor, glowColor, gridColor, inkColor, mutedColor, accentColor, labelColor, borderColor) = when (theme) {
            CardTheme.EMERALD -> CardColors(
                top = Color.rgb(9, 26, 22),
                bottom = Color.rgb(3, 7, 9),
                glow = Color.argb(62, 16, 185, 129),
                grid = Color.argb(12, 255, 255, 255),
                ink = Color.rgb(236, 253, 245),
                muted = Color.rgb(148, 163, 184),
                accent = Color.rgb(52, 211, 153),
                label = Color.rgb(110, 231, 183),
                border = Color.argb(24, 255, 255, 255)
            )
            CardTheme.MIDNIGHT -> CardColors(
                top = Color.rgb(11, 20, 38),
                bottom = Color.rgb(4, 6, 10),
                glow = Color.argb(58, 14, 165, 233),
                grid = Color.argb(12, 255, 255, 255),
                ink = Color.rgb(226, 232, 240),
                muted = Color.rgb(148, 163, 184),
                accent = Color.rgb(56, 189, 248),
                label = Color.rgb(125, 211, 252),
                border = Color.argb(24, 255, 255, 255)
            )
            CardTheme.PAPER -> CardColors(
                top = Color.rgb(250, 248, 242),
                bottom = Color.rgb(240, 234, 223),
                glow = Color.argb(40, 52, 211, 153),
                grid = Color.argb(10, 15, 23, 42),
                ink = Color.rgb(22, 34, 29),
                muted = Color.rgb(88, 102, 95),
                accent = Color.rgb(5, 150, 105),
                label = Color.rgb(4, 120, 87),
                border = Color.argb(24, 22, 34, 29)
            )
        }

        // Background gradient
        val bgPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), topColor, bottomColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Subtle radial glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            maskFilter = BlurMaskFilter(120f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(w * 0.85f, h * 0.08f, w * 0.35f, glowPaint)
        canvas.drawCircle(w * 0.12f, h * 0.88f, w * 0.3f, glowPaint)

        // Grid lines
        val gridPaint = Paint().apply {
            color = gridColor
            strokeWidth = 1f
        }
        for (gx in 84 until w step 84) {
            canvas.drawLine(gx.toFloat(), 0f, gx.toFloat(), h.toFloat(), gridPaint)
        }
        for (gy in 84 until h step 84) {
            canvas.drawLine(0f, gy.toFloat(), w.toFloat(), gy.toFloat(), gridPaint)
        }

        // Frame border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(RectF(26f, 26f, w - 26f, h - 26f), 30f, 30f, borderPaint)

        val wide = aspect == CardAspect.WIDE
        val side = if (wide) 72f else 88f
        val top = if (wide) 54f else 72f
        val contentWidth = (w - side * 2).toInt()
        val footerY = h - (if (wide) 46f else 60f)

        // Eyebrow badge
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawCircle(side + 6f, top + 10f, 6f, dotPaint)

        val eyebrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 18f
            letterSpacing = 0.18f
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("LENSCRIBE", side + 24f, top + 16f, eyebrowPaint)
        eyebrowPaint.color = mutedColor
        canvas.drawText(" / TEXT CARD", side + 168f, top + 16f, eyebrowPaint)

        var cursor = top + 56f
        if (title.isNotBlank()) {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkColor
                textSize = if (wide) 40f else 48f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, contentWidth)
                .setMaxLines(2)
                .build()
            canvas.save()
            canvas.translate(side, cursor)
            titleLayout.draw(canvas)
            canvas.restore()
            cursor += titleLayout.height + 22f
        }

        canvas.drawRoundRect(RectF(side, cursor, side + 64f, cursor + 5f), 2.5f, 2.5f, dotPaint)
        val bodyTop = cursor + 34f
        val maxBodyHeight = (footerY - bodyTop - 40f).toInt().coerceAtLeast(100)

        val cleanText = String(buildTxt(markdown), Charsets.UTF_8).trim()
        var bodySize = if (wide) 24f else 28f
        var bodyLayout: StaticLayout
        while (true) {
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkColor
                textSize = bodySize
                typeface = Typeface.DEFAULT
            }
            bodyLayout = StaticLayout.Builder.obtain(cleanText, 0, cleanText.length, bodyPaint, contentWidth)
                .setLineSpacing(4f, 1.15f)
                .build()
            if (bodyLayout.height <= maxBodyHeight || bodySize <= 16f) break
            bodySize -= 2f
        }
        canvas.save()
        canvas.clipRect(side, bodyTop, side + contentWidth, bodyTop + maxBodyHeight)
        canvas.translate(side, bodyTop)
        bodyLayout.draw(canvas)
        canvas.restore()

        // Footer
        canvas.drawLine(side, footerY - 24f, w - side, footerY - 24f, borderPaint)
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 15f
            letterSpacing = 0.14f
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("LENSCRIBE", side, footerY + 4f, footerPaint)
        footerPaint.color = mutedColor
        footerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("DOCUMENT EXPORT • PRECISION OCR", w - side, footerY + 4f, footerPaint)

        return bitmap
    }

    private data class CardColors(
        val top: Int,
        val bottom: Int,
        val glow: Int,
        val grid: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val label: Int,
        val border: Int
    )

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun corePropsXml(title: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val stamp = fmt.format(Date())
        return """$XML_DECL
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>${xmlEscape(title)}</dc:title>
  <dc:creator>Lenscribe</dc:creator>
  <dcterms:created xsi:type="dcterms:W3CDTF">$stamp</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">$stamp</dcterms:modified>
</cp:coreProperties>"""
    }

    private fun xmlEscape(text: String): String {
        return text
            .replace(Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F]"""), "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun zipEntries(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
