package dev.quire.compose.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.quire.compose.bridge.SyncState
import org.json.JSONException
import org.json.JSONObject

/**
 * 二维码传输: the same data the LAN round moves, carried by a code instead of by
 * a broadcast.
 *
 * **The code carries the door, not the data.** A workspace is far past what a QR
 * can hold (version 40 is 2953 bytes) and a photo of one would be a second sync
 * protocol to maintain and to get wrong; so the code is `{id, name, ip, port}` and
 * the merge is the engine's ordinary pull-merge-push against the address in it.
 * This is ActivityWatch's WiFi-传输 shape exactly — its code carries the hotspot's
 * SSID and the address behind it, and the bytes go over HTTP — and it is the same
 * trick for the same reason: a link that cannot broadcast can still be dialled.
 *
 * What it buys is the network that filters UDP. Discovery is best-effort by design
 * (there is no manifest permission for it), so a guest Wi-Fi or an AP with client
 * isolation leaves 本网络上的设备 permanently empty with nothing to try; a code
 * the user holds up to the other screen is not filtered by anything.
 *
 * Both directions are one round, not two transfers: the side that scans pulls the
 * peer's snapshot, merges it, and pushes the merged snapshot back, so a single
 * gesture converges both workspaces.
 */
data class QrPayload(
    /** 载荷格式版本. Bumped only when a field changes meaning. */
    val v: Int = VERSION,
    /** 类型标记, so a photo of a shopping list does not parse as a device. */
    val t: String = TYPE,
    /** This device's stable id — the peer book keys on it, and a round refuses a
     *  snapshot whose id is not the one the code claimed. */
    val id: String,
    /** 展示用. */
    val name: String = "",
    /** `ip` or `ip:port`; an absent port is `quire-core`'s own. */
    val address: String,
    /** Convenience field: the address with its scheme, for a reader looking at the
     *  string rather than at the bridge. The bridge parses `address`, not this. */
    val url: String = "http://$address",
) {

    fun toJson(): String = JSONObject()
        .put("v", v)
        .put("t", t)
        .put("id", id)
        .put("name", name)
        .put("addr", address)
        .put("url", url)
        .toString()

    companion object {
        const val VERSION = 1
        const val TYPE = "quire-sync"

        /**
         * The code this device would show, or `null` when it cannot sync at all.
         *
         * `null` rather than a code with an empty address: a scannable square that
         * names no host is the one failure the other side cannot act on.
         */
        fun ofSelf(sync: SyncState): QrPayload? {
            val address = sync.selfAddress.trim()
            // The session spells an address it has not got as `:5878` — the port
            // with no host — so the host is checked separately from the string
            // being non-empty. A code with no host in it scans fine and connects
            // to nothing, which is the worst of both.
            if (sync.selfId.isBlank() || address.substringBefore(':').isBlank()) return null
            return QrPayload(
                id = sync.selfId,
                name = sync.selfName,
                address = address,
            )
        }

        /**
         * Parse what a camera read, or `null` if it is not one of ours.
         *
         * Strict on purpose. Every field checked here is one the round would fail
         * on later, and a failure at the point of scanning can say what was wrong
         * with the code instead of timing out against a device that was never
         * there.
         */
        fun fromJson(text: String?): QrPayload? {
            val trimmed = text?.trim().orEmpty()
            if (!trimmed.startsWith("{")) return null
            return try {
                val json = JSONObject(trimmed)
                val payload = QrPayload(
                    v = json.optInt("v", 0),
                    t = json.optString("t"),
                    id = json.optString("id"),
                    name = json.optString("name"),
                    // `addr` is what this shell writes; `url` is the same value
                    // with a scheme, and is what a human-readable code tends to
                    // carry. Either is accepted — a code is written by three
                    // shells and read by a camera at an angle.
                    address = json.optString("addr").ifBlank { strip_scheme(json.optString("url")) },
                )
                if (payload.t != TYPE) null else if (payload.v != VERSION) null else {
                    if (payload.id.isBlank() || payload.address.isBlank()) null else payload
                }
            } catch (e: JSONException) {
                null
            }
        }

        /** `http://192.168.1.5:5878/sync` → `192.168.1.5:5878`. */
        private fun strip_scheme(url: String): String =
            url.trim().removePrefix("http://").substringBefore('/')
    }
}

/**
 * The code itself, drawn black on white at the size the screen can afford.
 *
 * Always the same two colours regardless of theme: a QR is read by another
 * device's camera, and inverting it for a dark theme buys nothing here and costs
 * a scan that fails on some readers. The quiet zone (margin 1) is what keeps a
 * code readable when it fills the width of a phone screen — the case this one is
 * always in.
 */
fun qrBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val offset = y * w
        for (x in 0 until w) {
            pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
}.getOrNull()

/**
 * 出示: the code, the address in words, and what the other side has to do with it.
 *
 * The address is spelled out under the code because the scan is not the only way
 * to use this: a user on a network where the camera cannot focus can type six
 * characters. It is also the thing to check when a scan does not connect — the
 * answer is almost always that the two devices are not on the same subnet.
 */
@Composable
fun QrShareCard(payload: QrPayload, modifier: Modifier = Modifier) {
    val colors = LocalQuireColors.current
    // 320dp of screen, capped: a code grown past the point where a camera can hold
    // the whole square in focus is worse than a smaller code that scans first try.
    val sizePx = with(LocalDensity.current) {
        (320.dp.toPx()).toInt().coerceIn(320, 1080)
    }
    val bitmap = remember(payload.toJson(), sizePx) { qrBitmap(payload.toJson(), sizePx) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(Radius.lg))
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (bitmap == null) {
            Text("这个二维码生成不出来", style = QuireType.ui, color = colors.danger)
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "同步二维码",
                modifier = Modifier
                    .size(with(LocalDensity.current) { sizePx.toDp() })
                    .clip(RoundedCornerShape(Radius.xs)),
            )
        }
        Text(
            text = payload.address,
            style = QuireType.code,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "让另一台设备在「同步」页点「扫码接收」，对着这个码扫一下。",
            style = QuireType.caption,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = payload.name.ifBlank { "（未命名）" },
                style = QuireType.caption,
                color = colors.textMuted,
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "ID ${payload.id.take(8)}",
                style = QuireType.caption.copy(fontFamily = FontFamily.Monospace),
                color = colors.textMuted,
            )
        }
        Box(modifier = Modifier.height(Spacing.xs))
    }
}
