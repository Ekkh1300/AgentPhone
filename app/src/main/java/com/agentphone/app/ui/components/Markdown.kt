package com.agentphone.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A dependency-light CommonMark-ish renderer for the subset chat assistants actually emit:
 * headings, paragraphs, lists, quotes, fenced code, tables, inline emphasis/code/links.
 * It is streaming-safe: an unterminated fence is shown as code instead of disappearing.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val colors = MarkdownColors(
        text = MaterialTheme.colorScheme.onSurface,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        codeBorder = MaterialTheme.colorScheme.outlineVariant,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        tableHeader = MaterialTheme.colorScheme.surfaceContainerHigh,
        quote = MaterialTheme.colorScheme.primary,
    )
    Column(
        modifier = modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, colors),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = when (block.level) {
                            1 -> 24.sp
                            2 -> 21.sp
                            3 -> 19.sp
                            else -> 17.sp
                        },
                    ),
                    color = colors.text,
                )
                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, colors),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                is MdBlock.Quote -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .background(colors.quote, RoundedCornerShape(2.dp))
                            .padding(vertical = 2.dp)
                            .size(width = 3.dp, height = 1.dp),
                    )
                    Text(
                        text = inline(block.text, colors),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (block.ordered) "${block.start + index}." else "•",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.muted,
                            )
                            Text(
                                text = inline(item, colors),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is MdBlock.Code -> CodeBlock(block.language, block.code, colors)
                is MdBlock.Table -> MarkdownTable(block, colors)
                MdBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = colors.codeBorder,
                )
            }
        }
        if (isStreaming) {
            Text(
                text = "▍",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 0.dp),
            )
        }
    }
}

@Composable
private fun CodeBlock(language: String?, code: String, colors: MarkdownColors) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.codeBackground)
            .border(1.dp, colors.codeBorder, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.ifBlank { "code" } ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = colors.muted,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Text(
            text = highlightCode(code, language, colors),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, colors: MarkdownColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.codeBorder, RoundedCornerShape(10.dp)),
    ) {
        Row(Modifier.background(colors.tableHeader)) {
            table.headers.forEach { header ->
                Text(
                    text = inline(header, colors),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                    modifier = Modifier
                        .width(170.dp)
                        .border(0.5.dp, colors.codeBorder)
                        .padding(8.dp),
                )
            }
        }
        table.rows.forEach { row ->
            Row {
                row.forEachIndexed { index, cell ->
                    Text(
                        text = inline(cell, colors),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        modifier = Modifier
                            .width(170.dp)
                            .border(0.5.dp, colors.codeBorder)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun inline(text: String, colors: MarkdownColors): AnnotatedString =
    remember(text, colors) { buildInline(text, colors) }

private data class MarkdownColors(
    val text: Color,
    val muted: Color,
    val link: Color,
    val codeBackground: Color,
    val codeBorder: Color,
    val inlineCodeBackground: Color,
    val tableHeader: Color,
    val quote: Color,
)

private fun buildInline(text: String, colors: MarkdownColors): AnnotatedString {
    val builder = AnnotatedString.Builder()

    fun appendRange(source: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false) {
        var index = 0
        fun style(): SpanStyle = SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
        )
        while (index < source.length) {
            when {
                source.startsWith("`", index) -> {
                    val end = source.indexOf('`', index + 1)
                    if (end == -1) {
                        builder.withStyle(style()) { append(source.substring(index)) }
                        return
                    }
                    builder.withStyle(
                        style().merge(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = colors.inlineCodeBackground,
                                fontSize = 13.sp,
                            ),
                        ),
                    ) { append(source.substring(index + 1, end)) }
                    index = end + 1
                }
                source.startsWith("**", index) || source.startsWith("__", index) -> {
                    val marker = source.substring(index, index + 2)
                    val end = source.indexOf(marker, index + 2)
                    if (end == -1) {
                        builder.withStyle(style()) { append(marker) }
                        index += 2
                    } else {
                        appendRange(source.substring(index + 2, end), bold = true, italic = italic, strike = strike)
                        index = end + 2
                    }
                }
                source.startsWith("~~", index) -> {
                    val end = source.indexOf("~~", index + 2)
                    if (end == -1) {
                        builder.withStyle(style()) { append("~~") }
                        index += 2
                    } else {
                        appendRange(source.substring(index + 2, end), bold = bold, italic = italic, strike = true)
                        index = end + 2
                    }
                }
                source[index] == '*' || source[index] == '_' -> {
                    val marker = source[index].toString()
                    val end = source.indexOf(marker, index + 1)
                    if (end == -1) {
                        builder.withStyle(style()) { append(marker) }
                        index++
                    } else {
                        appendRange(source.substring(index + 1, end), bold = bold, italic = true, strike = strike)
                        index = end + 1
                    }
                }
                source[index] == '[' -> {
                    val close = source.indexOf(']', index + 1)
                    val openParen = if (close != -1) source.indexOf('(', close + 1) else -1
                    val closeParen = if (openParen != -1) source.indexOf(')', openParen + 1) else -1
                    if (close != -1 && openParen == close + 1 && closeParen != -1) {
                        val label = source.substring(index + 1, close)
                        val url = source.substring(openParen + 1, closeParen).trim()
                        if (url.startsWith("http://") || url.startsWith("https://") ||
                            url.startsWith("mailto:")
                        ) {
                            builder.withLink(
                                LinkAnnotation.Url(
                                    url = url,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = colors.link,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                    ),
                                ),
                            ) {
                                appendRange(label, bold = bold, italic = italic, strike = strike)
                            }
                        } else {
                            builder.withStyle(style()) { append(label) }
                        }
                        index = closeParen + 1
                    } else {
                        builder.withStyle(style()) { append(source[index]) }
                        index++
                    }
                }
                else -> {
                    val next = source.indexOfFirstFrom(index) {
                        it == '`' || it == '*' || it == '_' || it == '~' || it == '['
                    }.let { if (it == -1) source.length else it }
                    if (next <= index) {
                        builder.withStyle(style()) { append(source[index]) }
                        index++
                    } else {
                        builder.withStyle(style()) { append(source.substring(index, next)) }
                        index = next
                    }
                }
            }
        }
    }
    appendRange(text)
    return builder.toAnnotatedString()
}

private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
    for (index in start until length) if (predicate(this[index])) return index
    return -1
}

private fun highlightCode(code: String, language: String?, colors: MarkdownColors): AnnotatedString {
    val keywords = KEYWORDS[language?.lowercase()] ?: KEYWORDS["default"]!!
    val builder = AnnotatedString.Builder(code)
    val keywordStyle = SpanStyle(color = Color(0xFF7DD3FC), fontWeight = FontWeight.Medium)
    val stringStyle = SpanStyle(color = Color(0xFFFCD34D))
    val commentStyle = SpanStyle(color = colors.muted, fontStyle = FontStyle.Italic)
    val numberStyle = SpanStyle(color = Color(0xFFF0ABFC))
    val keywordSet = keywords
    var index = 0
    while (index < code.length) {
        val ch = code[index]
        when {
            ch == '/' && index + 1 < code.length && code[index + 1] == '/' -> {
                val end = code.indexOf('\n', index).let { if (it == -1) code.length else it }
                builder.addStyle(commentStyle, index, end)
                index = end
            }
            ch == '#' && (language == "python" || language == "py" || language == "sh" || language == "bash") -> {
                val end = code.indexOf('\n', index).let { if (it == -1) code.length else it }
                builder.addStyle(commentStyle, index, end)
                index = end
            }
            ch == '"' || ch == '\'' || ch == '`' -> {
                val quote = ch
                var end = index + 1
                while (end < code.length) {
                    if (code[end] == '\\') {
                        end += 2
                        continue
                    }
                    if (code[end] == quote) {
                        end++
                        break
                    }
                    end++
                }
                builder.addStyle(stringStyle, index, minOf(end, code.length))
                index = minOf(end, code.length)
            }
            ch.isDigit() -> {
                var end = index + 1
                while (end < code.length && (code[end].isDigit() || code[end] == '.')) end++
                builder.addStyle(numberStyle, index, end)
                index = end
            }
            ch.isLetter() || ch == '_' -> {
                var end = index + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                if (code.substring(index, end) in keywordSet) {
                    builder.addStyle(keywordStyle, index, end)
                }
                index = end
            }
            else -> index++
        }
    }
    return builder.toAnnotatedString()
}

private val KEYWORDS: Map<String, Set<String>> = mapOf(
    "kotlin" to setOf(
        "package", "import", "class", "object", "interface", "fun", "val", "var", "if", "else",
        "when", "for", "while", "do", "return", "try", "catch", "finally", "throw", "is", "in",
        "as", "null", "true", "false", "suspend", "override", "private", "public", "internal",
        "data", "sealed", "enum", "companion", "by", "lateinit", "init", "this", "super",
    ),
    "java" to setOf(
        "class", "interface", "enum", "public", "private", "protected", "static", "final", "void",
        "int", "long", "double", "float", "boolean", "char", "if", "else", "for", "while", "return",
        "new", "try", "catch", "finally", "throw", "throws", "extends", "implements", "import",
        "package", "null", "true", "false", "this", "super",
    ),
    "python" to setOf(
        "def", "class", "import", "from", "as", "if", "elif", "else", "for", "while", "return",
        "try", "except", "finally", "raise", "with", "lambda", "None", "True", "False", "and",
        "or", "not", "in", "is", "pass", "break", "continue", "yield", "async", "await",
    ),
    "javascript" to setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "return", "class", "new",
        "import", "export", "from", "async", "await", "try", "catch", "finally", "throw", "null",
        "undefined", "true", "false", "this", "typeof", "instanceof", "of", "in",
    ),
    "json" to setOf("true", "false", "null"),
    "sql" to setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "JOIN",
        "LEFT", "RIGHT", "INNER", "ON", "GROUP", "BY", "ORDER", "LIMIT", "CREATE", "TABLE",
    ),
    "default" to setOf(
        "if", "else", "for", "while", "return", "class", "function", "true", "false", "null",
        "const", "let", "var", "import", "from", "export", "public", "private",
    ),
)

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<String>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

object MarkdownParser {

    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                index++
                continue
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                val language = trimmed.removePrefix(marker).trim().take(24).ifBlank { null }
                val code = StringBuilder()
                index++
                var closed = false
                while (index < lines.size) {
                    if (lines[index].trim().startsWith(marker)) {
                        closed = true
                        index++
                        break
                    }
                    code.appendLine(lines[index])
                    index++
                }
                blocks += MdBlock.Code(language, code.toString().trimEnd())
                if (!closed) break
                continue
            }
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                index++
                continue
            }
            if (Regex("^(\\*\\*\\*|___|---)\\s*$").matches(trimmed)) {
                blocks += MdBlock.Divider
                index++
                continue
            }
            if (trimmed.startsWith(">")) {
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quote += lines[index].trim().removePrefix(">").trimStart()
                    index++
                }
                blocks += MdBlock.Quote(quote.joinToString(" "))
                continue
            }
            if (index + 1 < lines.size && looksLikeTableHeader(trimmed, lines[index + 1].trim())) {
                val headers = parseCells(trimmed)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                    rows += parseCells(lines[index].trim())
                    index++
                }
                blocks += MdBlock.Table(headers, rows)
                continue
            }
            val listMatch = Regex("^\\s*([-*+]|(\\d+)[.)])\\s+(.*)$").find(line)
            if (listMatch != null) {
                val ordered = listMatch.groupValues[2].isNotEmpty()
                val start = listMatch.groupValues[2].toIntOrNull() ?: 1
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val match = Regex("^\\s*([-*+]|(\\d+)[.)])\\s+(.*)$")
                        .find(lines[index]) ?: break
                    items += match.groupValues[3].trim()
                    index++
                }
                blocks += MdBlock.ListBlock(ordered, start, items)
                continue
            }
            val paragraph = mutableListOf<String>()
            while (index < lines.size) {
                val current = lines[index].trim()
                if (current.isEmpty() || current.startsWith("```") || current.startsWith("#") ||
                    current.startsWith(">") || current.startsWith("|") ||
                    Regex("^\\s*([-*+]|(\\d+)[.)])\\s+.*").matches(lines[index])
                ) {
                    break
                }
                paragraph += current
                index++
            }
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraph.joinToString(" "))
            } else {
                index++
            }
        }
        return blocks
    }

    private fun looksLikeTableHeader(header: String, separator: String): Boolean {
        if (!header.contains('|') || !separator.contains('-')) return false
        val cells = parseCells(separator)
        return cells.isNotEmpty() && cells.all { cell ->
            cell.isBlank() || cell.matches(Regex(":?-{2,}:?"))
        }
    }

    private fun parseCells(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }
}
