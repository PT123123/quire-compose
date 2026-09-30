package dev.quire.compose.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 同步 block on the wire, which is a contract between two languages that no
 * compiler checks: `rust/src/sync.rs` writes these keys and this side reads them.
 * A renamed field would otherwise surface as a page that draws nothing.
 *
 * The two derived helpers are here too, because they are what the page actually
 * asks: which rows are peers, which are merely discovered, and that this device is
 * never one of the latter.
 */
class SyncModelTest {

    private val body = """
        {
          "ok": true,
          "view": {
            "pages": [], "blocks": [], "recents": [], "favorites": [],
            "org": { "notes": [], "tasks": [], "lists": [] },
            "sync": {
              "running": true,
              "selfId": "android-7f3a",
              "selfName": "手机",
              "selfAddress": "192.168.1.5:5878",
              "auto": true,
              "interval": 300,
              "port": 5878,
              "discoveryPort": 5879,
              "busy": false,
              "status": "已与「笔记本」同步",
              "rows": [
                { "id": "android-7f3a", "name": "手机", "kind": "android",
                  "address": "192.168.1.5:5878", "paired": true, "online": true,
                  "lastSync": "", "selfDevice": true },
                { "id": "windows-11", "name": "笔记本", "kind": "windows",
                  "address": "192.168.1.9:5878", "paired": true, "online": true,
                  "lastSync": "2026-09-25 21:00", "selfDevice": false },
                { "id": "windows-12", "name": "台机", "kind": "windows",
                  "address": "192.168.1.11:5878", "paired": false, "online": false,
                  "lastSync": "", "selfDevice": false }
              ],
              "log": [
                { "at": "2026-09-25 21:00", "peer": "笔记本", "ok": true,
                  "message": "同步完成：12 个页面" },
                { "at": "2026-09-25 20:59", "peer": "笔记本", "ok": false,
                  "message": "连接被拒绝" }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun the_sync_block_parses_into_the_state_the_page_draws() {
        val reply = parseReply(body)
        val view = (reply as Reply.Updated).view
        val sync = view.sync

        assertTrue(sync.running)
        assertNull("no gate is the ordinary case", sync.unsyncable)
        assertEquals("手机", sync.selfName)
        assertEquals(300L, sync.interval)
        assertTrue(sync.auto)
        assertFalse(sync.busy)
        assertEquals("已与「笔记本」同步", sync.status)
        assertEquals(5878, sync.port)
        assertEquals(5879, sync.discoveryPort)
        assertEquals(2, sync.log.size)
        assertFalse(sync.log[1].ok)
        assertEquals("连接被拒绝", sync.log[1].message)
    }

    @Test
    fun the_rows_are_one_list_of_devices_and_this_one_excluded() {
        val view = (parseReply(body) as Reply.Updated).view
        val sync = view.sync

        // ADR-0030: "paired" and "merely discovered" stopped being two states, so
        // there is one list. The two rows below are a device that has synced and
        // one that is merely heard — the flag that used to separate them is still
        // on the wire (`paired`), because a peer on an older build sends it, but
        // nothing in this shell's UI reads it any more.
        assertEquals(2, sync.devices.size)
        assertEquals("windows-11", sync.devices[0].id)
        assertEquals("2026-09-25 21:00", sync.devices[0].lastSync)
        assertTrue(sync.devices[0].online)
        assertEquals("windows-12", sync.devices[1].id)
        assertFalse(sync.devices[1].online)

        // This device is excluded: it is drawn first and separately, above the
        // list, and it has no 立即同步 verb because it cannot sync with itself.
        assertEquals(3, sync.rows.size)
        assertTrue(sync.rows[0].selfDevice)
        assertTrue(sync.devices.none { it.selfDevice })
    }

    @Test
    fun a_view_without_the_sync_block_still_opens() {
        // A shell talking to a bridge that predates 同步: the page draws its
        // "nothing is running yet" state rather than failing to open the library.
        val bare = """{"ok":true,"view":{"pages":[],"blocks":[],"recents":[],
            "favorites":[],"org":{"notes":[],"tasks":[],"lists":[]}}}"""
        val view = (parseReply(bare) as Reply.Updated).view
        assertFalse(view.sync.running)
        assertTrue(view.sync.rows.isEmpty())
        assertEquals(60L, view.sync.interval)
    }

    /**
     * ADR-0032's two new fields survive the crossing, and the aggressive half
     * arrives as a *different number* from the stored one.
     *
     * The distinction is the whole feature: `interval` is the chip the user
     * pressed and `effectiveInterval` is what the timer does. Parsing one field
     * into both would leave the page drawing 「30 分」 over a 10-second timer
     * while every assertion in the suite passed — so both are read here, and
     * required to differ.
     */
    @Test
    fun the_cadence_in_force_is_not_the_one_that_was_chosen() {
        val wifi = """
            {"ok":true,"view":{"pages":[],"blocks":[],"recents":[],"favorites":[],
             "org":{"notes":[],"tasks":[],"lists":[]},
             "sync":{"running":true,"selfId":"a","selfName":"手机","selfAddress":"",
                     "auto":true,"interval":1800,"effectiveInterval":10,"onWifi":true,
                     "port":5878,"discoveryPort":5879,"busy":false,"status":"",
                     "rows":[],"log":[]}}}
        """.trimIndent()
        val sync = (parseReply(wifi) as Reply.Updated).view.sync

        assertEquals(1800L, sync.interval)
        assertEquals(10L, sync.effectiveInterval)
        assertTrue(sync.onWifi)
    }

    /**
     * A bridge that predates ADR-0032 sends neither field, and the missing
     * `effectiveInterval` must not become an invented number.
     *
     * The honest default for "no transport was ever reported" is the stored
     * interval — the relaxed one — because a session that has not been told it is
     * on Wi-Fi is not evidence that it is. Defaulting `effectiveInterval` to 10
     * instead would put every un-updated reply into a cadence the device never
     * agreed to, which is the direction that costs battery.
     */
    @Test
    fun an_older_bridge_reports_no_transport_and_no_aggression() {
        val view = (parseReply(body) as Reply.Updated).view
        assertFalse(view.sync.onWifi)
        assertEquals(
            view.sync.interval, view.sync.effectiveInterval,
        )
    }
}
