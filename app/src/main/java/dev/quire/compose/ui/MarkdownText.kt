package dev.quire.compose.ui

/**
 * What the compose sheet's toolbar does to the text, and how a note's tags are
 * read out of it.
 *
 * Pure functions over `(text, selection)`, deliberately: the toolbar's five
 * buttons are the kind of thing that is *nearly* right in a way nobody notices
 * (a heading cycle that eats the list marker, a bold toggle that doubles a
 * marker), so they are testable on the JVM with no Compose and no device — the
 * same reason `MarksTest` exists. The reference app keeps the same five
 * operations in `MarkdownTextActions.kt`, and the rules below are its rules.
 *
 * The `#` and `/` keys insert **literal characters** rather than markdown: they
 * are how a tag and a hierarchical tag's segments are typed.
 */
object MarkdownText {

    /** An edit's result: the new text and where the caret (or selection) lands. */
    data class Edit(val text: String, val start: Int, val end: Int)

    private val heading = Regex("^(#{1,6})\\s+")
    private val bullet = Regex("^[-*+]\\s+")
    private val ordered = Regex("^\\d+[.)]\\s+")
    private val anyPrefix = Regex("^(#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)")

    /** A literal character at the caret (or over the selection). */
    fun insert(text: String, start: Int, end: Int, what: String): Edit {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        val out = text.substring(0, from) + what + text.substring(to)
        return Edit(out, from + what.length, from + what.length)
    }

    /**
     * 加粗/斜体: wrap the selection in `marker`, or unwrap it when it is already
     * wrapped. With no selection an empty pair is inserted and the caret goes
     * between the two halves, which is what makes the button usable with a
     * keyboard that has no shift-selection habit.
     */
    fun toggleWrap(text: String, start: Int, end: Int, marker: String): Edit {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)

        if (from == to) {
            val out = text.substring(0, from) + marker + marker + text.substring(from)
            return Edit(out, from + marker.length, from + marker.length)
        }

        val selection = text.substring(from, to)
        // The markers are already inside the selection.
        if (selection.length >= marker.length * 2 &&
            selection.startsWith(marker) &&
            selection.endsWith(marker)
        ) {
            val inner = selection.substring(marker.length, selection.length - marker.length)
            val out = text.substring(0, from) + inner + text.substring(to)
            return Edit(out, from, from + inner.length)
        }
        // The markers sit just outside it.
        val before = text.substring((from - marker.length).coerceAtLeast(0), from)
        val after = text.substring(to, (to + marker.length).coerceAtMost(text.length))
        if (before == marker && after == marker) {
            val out = text.substring(0, from - marker.length) + selection +
                text.substring(to + marker.length)
            return Edit(out, from - marker.length, to - marker.length)
        }

        val out = text.substring(0, from) + marker + selection + marker + text.substring(to)
        return Edit(out, from + marker.length, to + marker.length)
    }

    /**
     * 标题: each line in the selection cycles 无 → H1 → H2 → H3 → 无, and the cycle
     * replaces any list prefix it finds — a line cannot be a heading and a bullet
     * at once, and stacking the two is what a naive implementation does.
     */
    fun cycleHeading(text: String, start: Int, end: Int): Edit =
        applyToLines(text, start, end) { lines ->
            lines.map { line ->
                val level = heading.find(line)?.groupValues?.get(1)?.length ?: 0
                val rest = anyPrefix.replaceFirst(line, "")
                when {
                    level == 0 -> "# $rest"
                    level >= 3 -> rest
                    else -> "#".repeat(level + 1) + " $rest"
                }
            }
        }

    /**
     * 无序列表: every non-blank line already a bullet means the press takes them
     * all off; otherwise they all go on. One button, two directions, decided by
     * what is already there.
     */
    fun toggleBullet(text: String, start: Int, end: Int): Edit =
        applyToLines(text, start, end) { lines ->
            val nonBlank = lines.filter { it.isNotBlank() }
            if (nonBlank.isNotEmpty() && nonBlank.all { bullet.containsMatchIn(it) }) {
                lines.map { bullet.replaceFirst(it, "") }
            } else {
                lines.map { if (it.isBlank()) it else "- " + anyPrefix.replaceFirst(it, "") }
            }
        }

    /** 有序列表: the same two directions, and the numbering is recomputed. */
    fun toggleOrdered(text: String, start: Int, end: Int): Edit =
        applyToLines(text, start, end) { lines ->
            val nonBlank = lines.filter { it.isNotBlank() }
            if (nonBlank.isNotEmpty() && nonBlank.all { ordered.containsMatchIn(it) }) {
                lines.map { ordered.replaceFirst(it, "") }
            } else {
                var n = 0
                lines.map { line ->
                    if (line.isBlank()) {
                        line
                    } else {
                        n++
                        "$n. " + anyPrefix.replaceFirst(line, "")
                    }
                }
            }
        }

    /**
     * The `#tag` tokens a note's text names, in first-seen order and without the
     * `#`.
     *
     * A token runs to whitespace, and the separators a tag may *not* contain are
     * the ones the text itself uses to lay out words. A bare `#` (or `#` followed
     * by punctuation only) is dropped: "issue #3" is not a tag named "3", because
     * the moment it became one a number would be a label.
     */
    fun tagTokens(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in text.split(' ', '\n', '\t', '，', ',', '、')) {
            if (!token.startsWith("#") || token.length < 2) continue
            val name = token.drop(1).trimEnd('.', '。', '!', '！', '?', '？', ')', '）', ':', '：', ';', '；')
            if (name.isEmpty() || name.all { it.isDigit() }) continue
            out += name
        }
        return out.toList()
    }

    /** The same tokens as the comma-separated string the bridge's tag input takes. */
    fun tagString(text: String): String = tagTokens(text).joinToString(", ")

    /**
     * Apply a line-wise transform to every line the selection covers (or to the
     * caret's own line), and keep the selection sensible afterwards.
     */
    private fun applyToLines(
        text: String,
        start: Int,
        end: Int,
        transform: (List<String>) -> List<String>,
    ): Edit {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        val hadSelection = from != to

        // `lastIndexOf('\n', -1)` is -1 — the caret at the very start belongs to
        // the first line, not to a line that ends at index 0.
        val regionStart = (text.lastIndexOf('\n', from - 1) + 1).coerceAtLeast(0)
        val regionEnd = text.indexOf('\n', to).let { if (it == -1) text.length else it }
        val region = text.substring(regionStart, regionEnd)
        val rewritten = transform(region.split('\n')).joinToString("\n")
        if (rewritten == region) return Edit(text, from, to)

        val out = text.substring(0, regionStart) + rewritten + text.substring(regionEnd)
        return if (hadSelection) {
            Edit(out, regionStart, regionStart + rewritten.length)
        } else {
            Edit(out, regionStart + rewritten.length, regionStart + rewritten.length)
        }
    }
}
