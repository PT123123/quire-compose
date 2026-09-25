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
    fun the_rows_split_into_peers_and_merely_discovered_devices() {
        val view = (parseReply(body) as Reply.Updated).view
        val sync = view.sync

        // This device is neither: it is drawn first and separately, and it has no
        // pairing verb because there is nothing to pair with itself.
        assertEquals(1, sync.peers.size)
        assertEquals("windows-11", sync.peers[0].id)
        assertEquals("2026-09-25 21:00", sync.peers[0].lastSync)
        assertTrue(sync.peers[0].online)

        assertEquals(1, sync.discovered.size)
        assertEquals("windows-12", sync.discovered[0].id)
        assertFalse(sync.discovered[0].online)
        assertEquals(3, sync.rows.size)
        assertTrue(sync.rows[0].selfDevice)
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
}
