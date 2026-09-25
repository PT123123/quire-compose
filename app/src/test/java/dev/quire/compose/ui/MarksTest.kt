package dev.quire.compose.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The byte/UTF-16 conversion, which is the one piece of this shell that can be
 * wrong without looking wrong until someone types Chinese.
 */
class MarksTest {

    @Test
    fun ascii_offsets_are_their_own_answer() {
        val text = "hello world"
        assertEquals(0, utf16Index(text, 0))
        assertEquals(6, utf16Index(text, 6))
        assertEquals(11, utf16Index(text, 11))
    }

    @Test
    fun chinese_characters_cost_three_bytes_and_one_unit() {
        val text = "你好世界"
        // 你 = 3 bytes, 1 unit: the third character starts at byte 6, unit 2
        assertEquals(0, utf16Index(text, 0))
        assertEquals(1, utf16Index(text, 3))
        assertEquals(2, utf16Index(text, 6))
        assertEquals(4, utf16Index(text, 12))
    }

    @Test
    fun a_mixed_run_counts_both_alphabets() {
        val text = "a你b"
        assertEquals(1, utf16Index(text, 1))
        assertEquals(2, utf16Index(text, 4))
        assertEquals(3, utf16Index(text, 5))
    }

    @Test
    fun an_emoji_is_four_bytes_and_two_units() {
        // The page icons are emoji, so this is not a hypothetical: 🙂 is 4 bytes
        // and a surrogate pair, and a mark after it would land one unit short
        // without the surrogate-pair count.
        val text = "🙂x"
        assertEquals(2, utf16Index(text, 4))
        assertEquals(3, utf16Index(text, 5))
    }

    @Test
    fun an_offset_past_the_end_clamps_instead_of_throwing() {
        assertEquals(5, utf16Index("hello", 99))
        assertEquals(0, utf16Index("", 4))
        assertEquals(0, utf16Index("hello", -3))
    }
}
