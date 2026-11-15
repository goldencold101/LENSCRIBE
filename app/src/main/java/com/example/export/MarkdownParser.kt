package com.example.export

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Numbered(val number: Int, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

object MarkdownParser {
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bulletRegex = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
    private val numberRegex = Regex("""^\s{0,3}(\d{1,9})[.)]\s+(.*)$""")
    private val quoteRegex = Regex("""^\s{0,3}>\s?(.*)$""")
    private val hrRegex = Regex("""^\s{0,3}([-*_])(?:\s*\1){2,}\s*$""")
    private val separatorCellRegex = Regex("""^\s*:?-{3,}:?\s*$""")

    private val imageRegex = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val boldRegex = Regex("""\*\*([^*]+)\*\*|__([^_]+)__""")
    private val italicRegex = Regex("""\*([^*]+)\*|_([^_]+)_""")
    private val codeRegex = Regex("""`([^`]+)`""")
    private val strikeRegex = Regex("""~~([^~]+)~~""")
    private val linkRegex = Regex("""\[([^\]]+)]\([^)]*\)""")

    fun plainText(text: String): String {
        var result = text
        result = imageRegex.replace(result) { "[image: ${it.groupValues[1]}]" }
        result = boldRegex.replace(result) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        result = italicRegex.replace(result) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        result = codeRegex.replace(result) { it.groupValues[1] }
        result = strikeRegex.replace(result) { it.groupValues[1] }
        result = linkRegex.replace(result) { it.groupValues[1] }
        return result
    }

    fun splitRow(line: String): List<String> {
        var row = line.trim()
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|")) row = row.substring(0, row.length - 1)
        return row.split(Regex("""(?<!\\)\|""")).map { cell ->
            cell.trim().replace("\\|", "|")
        }
    }

    private fun isSeparator(line: String): Boolean {
        if (!line.contains("-")) return false
        val cells = splitRow(line)
        return cells.isNotEmpty() && cells.all { separatorCellRegex.matches(it) }
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        return index + 1 < lines.size &&
            lines[index].contains("|") &&
            lines[index + 1].contains("|") &&
            isSeparator(lines[index + 1])
    }

    fun parseBlocks(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val stripped = line.trim()
            if (stripped.isEmpty()) {
                index++
                continue
            }

            if (stripped.startsWith("```")) {
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    codeLines.add(lines[index])
                    index++
                }
                index++
                blocks.add(MarkdownBlock.Code(codeLines.joinToString("\n")))
                continue
            }

            val headingMatch = headingRegex.matchEntire(line)
            if (headingMatch != null) {
                blocks.add(
                    MarkdownBlock.Heading(
                        level = headingMatch.groupValues[1].length,
                        text = headingMatch.groupValues[2]
                    )
                )
                index++
                continue
            }

            if (isTableStart(lines, index)) {
                val rows = mutableListOf(splitRow(lines[index]))
                index += 2
                while (index < lines.size && lines[index].trim().isNotEmpty() && lines[index].contains("|")) {
                    rows.add(splitRow(lines[index]))
                    index++
                }
                blocks.add(MarkdownBlock.Table(rows))
                continue
            }

            if (hrRegex.matches(line)) {
                blocks.add(MarkdownBlock.HorizontalRule)
                index++
                continue
            }

            val quoteMatch = quoteRegex.matchEntire(line)
            if (quoteMatch != null) {
                val quoted = mutableListOf(quoteMatch.groupValues[1])
                index++
                while (index < lines.size) {
                    val nxt = quoteRegex.matchEntire(lines[index]) ?: break
                    quoted.add(nxt.groupValues[1])
                    index++
                }
                blocks.add(MarkdownBlock.Quote(quoted.joinToString("\n")))
                continue
            }

            val bulletMatch = bulletRegex.matchEntire(line)
            if (bulletMatch != null) {
                blocks.add(MarkdownBlock.Bullet(bulletMatch.groupValues[1]))
                index++
                continue
            }

            val numberMatch = numberRegex.matchEntire(line)
            if (numberMatch != null) {
                blocks.add(
                    MarkdownBlock.Numbered(
                        number = numberMatch.groupValues[1].toIntOrNull() ?: 1,
                        text = numberMatch.groupValues[2]
                    )
                )
                index++
                continue
            }

            val paragraph = mutableListOf(stripped)
            index++
            while (index < lines.size) {
                val candidate = lines[index]
                val candidateTrimmed = candidate.trim()
                if (candidateTrimmed.isEmpty() ||
                    headingRegex.matches(candidate) ||
                    candidateTrimmed.startsWith("```") ||
                    quoteRegex.matches(candidate) ||
                    bulletRegex.matches(candidate) ||
                    numberRegex.matches(candidate) ||
                    hrRegex.matches(candidate) ||
                    isTableStart(lines, index)
                ) {
                    break
                }
                paragraph.add(candidateTrimmed)
                index++
            }
            blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n")))
        }

        return blocks
    }

    fun extractFirstTable(markdown: String): List<List<String>>? {
        return parseBlocks(markdown)
            .filterIsInstance<MarkdownBlock.Table>()
            .firstOrNull()
            ?.rows
    }

    fun replaceFirstTable(markdown: String, updatedRows: List<List<String>>): String {
        if (updatedRows.isEmpty()) return markdown
        val colCount = updatedRows.maxOf { it.size }.coerceAtLeast(1)
        val normalized = updatedRows.map { row ->
            List(colCount) { idx -> row.getOrElse(idx) { "" }.replace("|", "\\|").trim() }
        }
        val header = "| " + normalized.first().joinToString(" | ") + " |"
        val separator = "| " + List(colCount) { "---" }.joinToString(" | ") + " |"
        val body = normalized.drop(1).map { row -> "| " + row.joinToString(" | ") + " |" }
        val tableMarkdown = (listOf(header, separator) + body).joinToString("\n")

        val lines = markdown.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            if (isTableStart(lines, i)) {
                var end = i + 2
                while (end < lines.size && lines[end].trim().isNotEmpty() && lines[end].contains("|")) {
                    end++
                }
                val prefix = lines.subList(0, i)
                val suffix = lines.subList(end, lines.size)
                return (prefix + tableMarkdown.split("\n") + suffix).joinToString("\n")
            }
            i++
        }
        return if (markdown.isBlank()) tableMarkdown else "$markdown\n\n$tableMarkdown"
    }
}
