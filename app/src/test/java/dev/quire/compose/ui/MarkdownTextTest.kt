package dev.quire.compose.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compose sheet's toolbar, which is the kind of code that is *nearly* right
 * in a way nobody notices until a note is full of stray asterisks: a heading
 * cycle that eats the list marker, a bold toggle that doubles a marker, a tag
 * scan that calls "issue #3" a tag named 3.
 *
 * The reference app keeps the same five operations; these are their rules.
 */
class MarkdownTextTest {

    private fun edit(text: String, start: Int = text.length, end: Int = start) =
        MarkdownText.Edit(text, start, end)

    // ─── the two literal keys ───────────────────────────────────────────────

    @Test
    fun the_hash_and_slash_keys_insert_a_literal_character() {
        // They are not markdown: they are how a tag and a hierarchical tag's
        // segments are typed.
        val hash = MarkdownText.insert("ab", 1, 1, "#")
        assertEquals("a#b", hash.text)
        assertEquals(2, hash.start)

        val slash = MarkdownText.insert("项目工作", 2, 2, "/")
        assertEquals("项目/工作", slash.text)
        assertEquals(3, slash.start)
    }

    @Test
    fun insert_replaces_a_selection_and_puts_the_caret_after_it() {
        val e = MarkdownText.insert("hello", 1, 4, "i")
        assertEquals("hio", e.text)
        assertEquals(2, e.start)
        assertEquals(2, e.end)
    }

    // ─── bold ───────────────────────────────────────────────────────────────

    @Test
    fun bold_wraps_the_selection_and_unwraps_it_again() {
        val wrapped = MarkdownText.toggleWrap("hello world", 0, 5, "**")
        assertEquals("**hello** world", wrapped.text)
        // The word stays selected, so the same press can take it off.
        assertEquals(2, wrapped.start)
        assertEquals(7, wrapped.end)

        val unwrapped = MarkdownText.toggleWrap(wrapped.text, wrapped.start, wrapped.end, "**")
        assertEquals("hello world", unwrapped.text)
        assertEquals(0, unwrapped.start)
        assertEquals(5, unwrapped.end)
    }

    @Test
    fun bold_with_no_selection_leaves_the_caret_inside_the_pair() {
        val e = MarkdownText.toggleWrap("ab", 1, 1, "**")
        assertEquals("a****b", e.text)
        assertEquals(3, e.start)
        assertEquals(3, e.end)
    }

    @Test
    fun bold_unwraps_markers_the_selection_already_contains() {
        val e = MarkdownText.toggleWrap("**hello**", 0, 9, "**")
        assertEquals("hello", e.text)
        assertEquals(0, e.start)
        assertEquals(5, e.end)
    }

    @Test
    fun bold_does_not_unwrap_a_selection_that_merely_touches_a_marker() {
        // "**a** b**" selected from 0 to 7 is "**a** b" — it ends with " b", not
        // with the marker, so it is wrapped rather than mistaken for one already
        // wrapped pair (the naive "starts and ends with it" test eats the wrong
        // pair and leaves "a** b" behind).
        val e = MarkdownText.toggleWrap("**a** b**", 0, 7, "**")
        assertEquals("****a** b****", e.text)
        assertEquals(2, e.start)
        assertEquals(9, e.end)
    }

    // ─── the three line keys ────────────────────────────────────────────────

    @Test
    fun a_heading_cycles_and_never_stacks_with_a_list_marker() {
        var e = MarkdownText.cycleHeading("title", 0, 0)
        assertEquals("# title", e.text)
        e = MarkdownText.cycleHeading(e.text, 0, 0)
        assertEquals("## title", e.text)
        e = MarkdownText.cycleHeading(e.text, 0, 0)
        assertEquals("### title", e.text)
        // Past H3 it goes back to plain text rather than to H4 — three levels is
        // what a phone screen has room to say.
        e = MarkdownText.cycleHeading(e.text, 0, 0)
        assertEquals("title", e.text)

        // A bullet becomes a heading, and the bullet marker goes: a line cannot be
        // both, and stacking them is what a naive implementation does.
        e = MarkdownText.cycleHeading("- item", 0, 0)
        assertEquals("# item", e.text)
        e = MarkdownText.cycleHeading("1. item", 0, 0)
        assertEquals("# item", e.text)
    }

    @Test
    fun the_bullet_key_turns_a_run_on_and_off() {
        val on = MarkdownText.toggleBullet("a\nb\n\nc", 0, 7)
        assertEquals("- a\n- b\n\n- c", on.text)
        // Blank lines are left alone, so a gap in the list stays a gap.
        assertTrue(on.text.contains("\n\n"))

        val off = MarkdownText.toggleBullet(on.text, 0, on.text.length)
        assertEquals("a\nb\n\nc", off.text)
    }

    @Test
    fun the_bullet_key_replaces_an_ordered_prefix_rather_than_stacking() {
        val e = MarkdownText.toggleBullet("1. a", 0, 0)
        assertEquals("- a", e.text)
    }

    @Test
    fun the_ordered_key_numbers_the_run_and_un_numbers_it() {
        val on = MarkdownText.toggleOrdered("a\nb\nc", 0, 5)
        assertEquals("1. a\n2. b\n3. c", on.text)

        val off = MarkdownText.toggleOrdered(on.text, 0, on.text.length)
        assertEquals("a\nb\nc", off.text)
    }

    @Test
    fun a_line_key_touches_only_the_lines_the_selection_covers() {
        val text = "one\ntwo\nthree"
        // The caret is inside "two" only.
        val e = MarkdownText.toggleBullet(text, 5, 5)
        assertEquals("one\n- two\nthree", e.text)
    }

    // ─── the tags a note's text names ───────────────────────────────────────

    @Test
    fun the_tag_scan_takes_first_seen_order_and_drops_duplicates() {
        val tags = MarkdownText.tagTokens("会议 #工作 记录 #idea 再说 #工作")
        assertEquals(listOf("工作", "idea"), tags)
        assertEquals("工作, idea", MarkdownText.tagString("会议 #工作 记录 #idea 再说 #工作"))
    }

    @Test
    fun a_bare_hash_and_a_bare_number_are_not_tags() {
        // "issue #3" must not become a label called 3: a number is not a tag.
        assertEquals(emptyList<String>(), MarkdownText.tagTokens("issue #3"))
        assertEquals(emptyList<String>(), MarkdownText.tagTokens("# alone"))
        assertEquals(emptyList<String>(), MarkdownText.tagTokens("no tags here"))
        assertEquals(listOf("a"), MarkdownText.tagTokens("#a # #3"))
    }

    @Test
    fun a_tag_ends_at_the_whitespace_or_the_punctuation_after_it() {
        assertEquals(listOf("work"), MarkdownText.tagTokens("done #work."))
        assertEquals(listOf("工作"), MarkdownText.tagTokens("好了，#工作。"))
        // A hierarchical tag keeps its segments: `/` is part of the name.
        assertEquals(listOf("项目/工作"), MarkdownText.tagTokens("#项目/工作 记一下"))
    }

    @Test
    fun an_edit_that_changes_nothing_returns_the_text_untouched() {
        // Pressing 无序列表 twice in a row is two transforms, and the second one
        // must be a real inverse — not an approximation that leaves a marker.
        val once = MarkdownText.toggleBullet("x", 0, 1)
        val twice = MarkdownText.toggleBullet(once.text, 0, once.text.length)
        assertEquals("x", twice.text)
        assertEquals(edit("x", 0, 1), twice)
    }
}
