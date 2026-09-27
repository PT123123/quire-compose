package dev.quire.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two string rules between a stored address and the system (ADR-0023).
 *
 * They mirror `quire-core::embed`'s `with_scheme` / `is_openable`, and they are
 * worth a test for the same reason the core gives them one: the allow-list is
 * what keeps an address out of an `ACTION_VIEW` that would *act* on it, and a
 * rule that is nearly right is invisible until a document carries the wrong
 * thing.
 */
class LinksTest {

    @Test
    fun a_bare_domain_gets_the_scheme_the_dialog_would_give_it() {
        assertEquals("https://example.com/a", withScheme("example.com/a"))
        assertEquals("https://example.com", withScheme("https://example.com"))
        assertEquals("http://example.com", withScheme("http://example.com"))
        assertEquals("mailto:a@b.c", withScheme("mailto:a@b.c"))
        assertEquals("", withScheme("  "))
    }

    @Test
    fun only_an_address_a_browser_understands_leaves_the_app() {
        assertTrue(isOpenable("https://example.com"))
        assertTrue(isOpenable("HTTP://EXAMPLE.COM/x"))
        assertTrue(isOpenable("mailto:a@b.c"))
        // the shapes an Intent would act on rather than open: a local path, a
        // script url, an installed protocol
        assertFalse(isOpenable("file:///data/local/notes.md"))
        assertFalse(isOpenable("javascript:alert(1)"))
        assertFalse(isOpenable("C:\\Windows\\System32\\calc.exe"))
        assertFalse(isOpenable(""))
        // a bare domain is not openable until withScheme has given it a scheme —
        // which is the order openUrl applies the two in
        assertFalse(isOpenable("example.com/a"))
    }
}
