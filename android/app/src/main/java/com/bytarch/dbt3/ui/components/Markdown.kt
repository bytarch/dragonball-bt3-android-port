package com.bytarch.dbt3.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bytarch.dbt3.ui.theme.Dbz

/**
 * A small renderer for the release notes (CHANGELOG-vX.Y.Z.md): headings, bullets (nested), quotes, **bold**,
 * `code`, [links](...) as their text. Images are left out (the notes are read in a dialog). Not a full Markdown.
 */
@Composable
fun MarkdownNotes(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseNotes(text) }
    val code = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Heading -> {
                    val style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        styled(b.text, code, link), style = style, fontWeight = FontWeight.Bold,
                        color = if (b.level <= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = if (b.level <= 2) 10.dp else 4.dp),
                    )
                }
                is Block.Bullet -> Row(Modifier.padding(start = (b.depth * 14).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 8.dp).size(5.dp).background(Dbz.SaiyanGold, CircleShape))
                    Text(styled(b.text, code, link), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                is Block.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Dbz.KiBlue))
                    Text(
                        styled(b.text, code, link), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                }
                is Block.Para -> Text(styled(b.text, code, link), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val depth: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Para(val text: String) : Block
}

private fun parseNotes(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Para(para.toString().trim())
        para.clear()
    }
    for (raw in text.replace("\r", "").lines()) {
        val line = raw.trimEnd()
        val t = line.trim()
        when {
            t.isEmpty() -> flush()
            t.startsWith("![") -> flush() // an image: not shown here
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 3)
                out += Block.Heading(level, t.dropWhile { it == '#' }.trim())
            }
            t.startsWith("- ") || t.startsWith("* ") -> {
                flush()
                out += Block.Bullet((line.length - line.trimStart().length) / 2, t.drop(2).trim())
            }
            t.startsWith(">") -> {
                flush()
                out += Block.Quote(t.removePrefix(">").trim())
            }
            t == "---" || t == "***" -> flush()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
    }
    flush()
    return out
}

private fun styled(s: String, codeBg: androidx.compose.ui.graphics.Color, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val end = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                val end = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            s[i] == '[' && s.indexOf("](", i) > 0 && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                val mid = s.indexOf("](", i)
                val end = s.indexOf(')', mid)
                withStyle(SpanStyle(color = linkColor)) { append(s.substring(i + 1, mid)) }
                i = end + 1
            }
            else -> {
                append(s[i])
                i++
            }
        }
    }
}
