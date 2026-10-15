package dev.quire.compose.ui

import dev.quire.compose.bridge.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 二维码载荷: what one shell writes and another reads, with no compiler in
 * between and no wire protocol underneath to catch a mismatch.
 *
 * The round it starts is the ordinary one, so the code's whole job is to name a
 * device correctly. Every rejection below is a case where it would not: a photo
 * of something else entirely, a code from a shape this build does not speak, and
 * a code whose address is missing — which scans cleanly and then connects to
 * nothing, the one failure the other side cannot diagnose.
 */
class QrPayloadTest {

    private fun sync(
        id: String = "android-7f3a",
        name: String = "手机",
        address: String = "192.168.1.5:5878",
    ) = SyncState.Empty.copy(
        running = true,
        selfId = id,
        selfName = name,
        selfAddress = address,
    )

    @Test
    fun a_code_this_device_would_show_reads_back_as_itself() {
        val payload = QrPayload.ofSelf(sync())!!
        val back = QrPayload.fromJson(payload.toJson())

        assertNotNull(back)
        assertEquals("android-7f3a", back!!.id)
        assertEquals("手机", back.name)
        assertEquals("192.168.1.5:5878", back.address)
        assertEquals(QrPayload.TYPE, back.t)
        assertEquals(QrPayload.VERSION, back.v)
    }

    /** A name with emoji in it is the ordinary case on Android, not an edge. */
    @Test
    fun a_code_survives_a_name_that_is_not_ascii() {
        val payload = QrPayload.ofSelf(sync(name = "小明的手机 📱"))!!
        assertEquals("小明的手机 📱", QrPayload.fromJson(payload.toJson())!!.name)
    }

    /**
     * `url` is what a human-readable code tends to carry and `addr` is what this
     * shell writes; either must dial the same device, because a code is written
     * by three shells and read by a camera at an angle.
     */
    @Test
    fun a_code_carrying_only_a_url_still_names_an_address() {
        val back = QrPayload.fromJson(
            """{"v":1,"t":"quire-sync","id":"desk-1","name":"桌面","url":"http://192.168.1.9:5878/sync"}"""
        )
        assertEquals("192.168.1.9:5878", back!!.address)
    }

    @Test
    fun a_code_that_is_not_ours_is_refused_rather_than_guessed_at() {
        // A Wi-Fi code from another app, and a shopping list: both are JSON, and
        // both would otherwise dial whatever they happened to contain.
        assertNull(QrPayload.fromJson("""{"v":1,"t":"aw-wifi-transfer","ssid":"x","ip":"1.2.3.4"}"""))
        assertNull(QrPayload.fromJson("""{"name":"咖啡豆","price":"18"}"""))
        assertNull(QrPayload.fromJson("not json at all"))
        assertNull(QrPayload.fromJson(null))
        assertNull(QrPayload.fromJson(""))
    }

    @Test
    fun a_future_format_is_refused_rather_than_half_understood() {
        assertNull(
            QrPayload.fromJson("""{"v":99,"t":"quire-sync","id":"d","addr":"1.2.3.4:5878"}""")
        )
    }

    @Test
    fun a_code_with_no_device_or_no_address_cannot_be_offered() {
        assertNull(QrPayload.fromJson("""{"v":1,"t":"quire-sync","addr":"192.168.1.5:5878"}"""))
        assertNull(QrPayload.fromJson("""{"v":1,"t":"quire-sync","id":"desk-1"}"""))

        // And the same two gaps on this side: the session spells a device with no
        // route `:5878` — a port with no host — and that must not become a code,
        // because it scans perfectly and reaches nothing.
        assertNull(QrPayload.ofSelf(sync(id = "")))
        assertNull(QrPayload.ofSelf(sync(address = ":5878")))
    }
}
