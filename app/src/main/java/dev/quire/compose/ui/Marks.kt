package dev.quire.compose.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import dev.quire.compose.bridge.MarkRow

/**
 * Byte offsets → UTF-16 indices.
 *
 * `quire-core` stores an inline mark's span as **byte** offsets into the block's
 * UTF-8 text (that is what the `marks` table round-trips, and the caret model
 * guarantees they land on char boundaries). Compose measures text in UTF-16 code
 * units. The two agree for ASCII — which is exactly how a bug here stays hidden
 * until the first Chinese character: 你 is three bytes and one unit, so a mark
 * on the third word of a Chinese sentence lands eight characters early if this
 * conversion is skipped, and on this app's first language it would be wrong from
 * the first sentence.
 *
 * An offset past the end (a mark from a longer revision) clamps to the end: a
 * range that has drifted should render too little, never crash.
 */
internal fun utf16Index(text: String, byteOffset: Int): Int {
    if (byteOffset <= 0) return 0
    var bytes = 0
    var units = 0
    var i = 0
    while (i < text.length) {
        if (bytes >= byteOffset) return units
        val codePoint = text.codePointAt(i)
        val chars = Character.charCount(codePoint)
        bytes += utf8Length(codePoint)
        units += chars
        i += chars
    }
    return units
}

private fun utf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/**
 * Paint a block's inline marks.
 *
 * The transformed text is the same length as the input, so [OffsetMapping.Identity]
 * is not an approximation here — it is exact, which is what keeps the caret and
 * the IME's composing region honest while marks are on screen.
 */
class MarksTransformation(
    private val marks: List<MarkRow>,
    private val linkColor: Color,
    private val codeBackground: Color,
    private val muted: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (marks.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val raw = text.text
        val styled = buildAnnotatedString {
            append(raw)
            for (mark in marks) {
                val start = utf16Index(raw, mark.start)
                val end = utf16Index(raw, mark.end)
                if (start >= end) continue
                // An unknown kind is a mark this build cannot draw — a newer
                // shell's, or one whose styling was retired. Painting nothing is
                // right: the text is still the text.
                val style = styleFor(mark.kind) ?: continue
                addStyle(style, start, end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    private fun styleFor(kind: String): SpanStyle? = when (kind) {
        "bold" -> SpanStyle(fontWeight = FontWeight.SemiBold)
        "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
        "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "code" -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        "link" -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        "mention" -> SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)
        "math" -> SpanStyle(fontStyle = FontStyle.Italic, color = muted)
        "date" -> SpanStyle(color = muted, textDecoration = TextDecoration.Underline)
        else -> null
    }
}
