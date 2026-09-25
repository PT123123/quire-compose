package dev.quire.compose.bridge

import org.json.JSONObject

/**
 * The typed face of the bridge: one method per operation, each building the
 * request the Rust side expects.
 *
 * Typed methods rather than a public `send(json)` because the operation names
 * would otherwise be string literals at every call site, and a typo there is a
 * runtime error no compiler sees. The names match `session::Request`'s
 * `serde(rename_all = "camelCase")` spelling exactly.
 *
 * **Threading.** Nothing here is thread-safe, and nothing here should be called
 * from more than one thread at a time: the Rust side holds one session behind a
 * mutex, so calls serialize anyway, but the reply ordering a caller observes
 * would not be its own. `QuireViewModel` confines every call to one dispatcher.
 *
 * @param handle the `long` [Native.create] returned; [close] ends its life.
 */
class Bridge(private val handle: Long) {

    fun open(dataDir: String): Reply = parseReply(Native.open(handle, dataDir))

    fun boot(): Reply = send("boot")

    /** The debounced writer's tick. Call about once a second while in front. */
    fun tick(): Reply = send("tick")

    /** Write everything queued now — the app is going away. */
    fun flush(): Reply = send("flush")

    fun openPage(page: Long): Reply = send("openPage", "page" to page)

    fun createPage(parent: Long?, title: String): Reply =
        send("createPage", "parent" to parent, "title" to title)

    fun renamePage(page: Long, title: String): Reply =
        send("renamePage", "page" to page, "title" to title)

    fun deletePage(page: Long): Reply = send("deletePage", "page" to page)

    fun toggleFavorite(page: Long): Reply = send("toggleFavorite", "page" to page)

    fun toggleExpanded(page: Long): Reply = send("toggleExpanded", "page" to page)

    fun setPageLocked(page: Long, locked: Boolean): Reply =
        send("setPageLocked", "page" to page, "locked" to locked)

    /** `system` | `light` | `dark` — the same three strings the other shells store. */
    fun setTheme(theme: String): Reply = send("setTheme", "theme" to theme)

    /**
     * A keystroke. Answers [Reply.Done]: echoing a page's worth of rows back on
     * every character is the cost this rule exists to avoid.
     */
    fun setBlockText(block: Long, text: String): Reply =
        send("setBlockText", "block" to block, "text" to text)

    fun insertBlockAfter(block: Long, kind: String, text: String = ""): Reply =
        send("insertBlockAfter", "block" to block, "kind" to kind, "text" to text)

    fun appendBlock(kind: String, text: String = ""): Reply =
        send("appendBlock", "kind" to kind, "text" to text)

    fun deleteBlock(block: Long): Reply = send("deleteBlock", "block" to block)

    fun setBlockKind(block: Long, kind: String): Reply =
        send("setBlockKind", "block" to block, "kind" to kind)

    fun toggleChecked(block: Long): Reply = send("toggleChecked", "block" to block)

    fun toggleFold(block: Long): Reply = send("toggleFold", "block" to block)

    fun moveBlock(block: Long, delta: Int): Reply =
        send("moveBlock", "block" to block, "delta" to delta)

    fun moveBlockTo(block: Long, index: Int): Reply =
        send("moveBlockTo", "block" to block, "index" to index)

    fun indentList(block: Long): Reply = send("indentList", "block" to block)

    fun outdentList(block: Long): Reply = send("outdentList", "block" to block)

    fun undo(): Reply = send("undo")

    fun redo(): Reply = send("redo")

    /** Flush and free the native session. After this the handle is dead. */
    fun close() = Native.close(handle)

    private fun send(op: String, vararg fields: Pair<String, Any?>): Reply =
        parseReply(Native.dispatch(handle, request(op, fields)))

    private fun request(op: String, fields: Array<out Pair<String, Any?>>): String {
        val json = JSONObject().put("op", op)
        for ((key, value) in fields) {
            // `put(key, null)` writes a JSON null, which is what an absent
            // parent/parent-ref has to be for the Rust side's Option.
            json.put(key, value ?: JSONObject.NULL)
        }
        return json.toString()
    }
}
