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

    // ─── SPEC §四十一: notes and tasks ──────────────────────────────────────
    //
    // Every one of these answers with the whole view, because an organizer edit
    // moves things the UI cannot predict: the row's `edited` instant (stamped on
    // the Rust side), which smart views the row belongs to now, and the counts in
    // the chips. The Kotlin side debounces the two fields a person types into, so
    // a keystroke does not pay for that.

    /**
     * A new note, from the capture overlay: its text, its tags, and — when it is a
     * reply — the note it answers, in one command.
     *
     * One command and not three because the overlay produces one thing — a note the
     * user typed — and a create-then-fill would leave a blank row on the undo stack
     * and cost three presses of 撤销 to put away.
     */
    fun orgAddNote(body: String, tags: String, ref: Long? = null): Reply =
        send("orgAddNote", "body" to body, "tags" to tags, "ref" to ref)

    /** A reply: the same command as a new note, carrying the ref it answers. */
    fun orgAddComment(parent: Long, body: String, tags: String): Reply =
        send("orgAddNote", "body" to body, "tags" to tags, "ref" to parent)

    /** An open note's text and its tags, together, for the same reason. */
    fun orgNoteContent(note: Long, body: String, tags: String): Reply =
        send("orgNoteContent", "note" to note, "body" to body, "tags" to tags)

    fun orgNoteTitle(note: Long, title: String): Reply =
        send("orgNoteTitle", "note" to note, "title" to title)

    fun orgNoteBody(note: Long, body: String): Reply =
        send("orgNoteBody", "note" to note, "body" to body)

    fun orgNotePinned(note: Long, pinned: Boolean): Reply =
        send("orgNotePinned", "note" to note, "pinned" to pinned)

    /** Comma-separated; the split is decided on the Rust side, once. */
    fun orgNoteTags(note: Long, tags: String): Reply =
        send("orgNoteTags", "note" to note, "tags" to tags)

    fun orgDeleteNote(note: Long): Reply = send("orgDeleteNote", "note" to note)

    /**
     * The capture sheet, and a board column's ＋: create with the title and the
     * tags it was given, in one step. `due` is the ISO date the field was opened
     * into when that was 今天, and null otherwise.
     */
    fun orgQuickAdd(list: Long, title: String, tags: String, due: String? = null): Reply =
        send("orgQuickAdd", "list" to list, "title" to title, "tags" to tags, "due" to due)

    fun orgTaskTitle(task: Long, title: String): Reply =
        send("orgTaskTitle", "task" to task, "title" to title)

    fun orgTaskNotes(task: Long, notes: String): Reply =
        send("orgTaskNotes", "task" to task, "notes" to notes)

    fun orgTaskDone(task: Long, done: Boolean): Reply =
        send("orgTaskDone", "task" to task, "done" to done)

    /** The priority menu's slot — the position in `Priority::ALL` (无·低·中·高). */
    fun orgTaskPriority(task: Long, slot: Int): Reply =
        send("orgTaskPriority", "task" to task, "slot" to slot)

    /** `YYYY-MM-DD`, or `""` to clear it. */
    fun orgTaskDue(task: Long, due: String): Reply =
        send("orgTaskDue", "task" to task, "due" to due)

    /** The repeat menu's slot — the position in `Repeat::ALL`. */
    fun orgTaskRepeat(task: Long, slot: Int): Reply =
        send("orgTaskRepeat", "task" to task, "slot" to slot)

    fun orgTaskTags(task: Long, tags: String): Reply =
        send("orgTaskTags", "task" to task, "tags" to tags)

    /** Move a task to another list; `-1` is the inbox. */
    fun orgTaskList(task: Long, list: Long): Reply =
        send("orgTaskList", "task" to task, "list" to list)

    fun orgDeleteTask(task: Long): Reply = send("orgDeleteTask", "task" to task)

    fun orgSubtaskAdd(task: Long): Reply = send("orgSubtaskAdd", "task" to task)

    fun orgSubtaskTitle(task: Long, subtask: Long, title: String): Reply =
        send("orgSubtaskTitle", "task" to task, "subtask" to subtask, "title" to title)

    fun orgSubtaskDone(task: Long, subtask: Long, done: Boolean): Reply =
        send("orgSubtaskDone", "task" to task, "subtask" to subtask, "done" to done)

    fun orgSubtaskDelete(task: Long, subtask: Long): Reply =
        send("orgSubtaskDelete", "task" to task, "subtask" to subtask)

    fun orgCreateList(name: String): Reply = send("orgCreateList", "name" to name)

    fun orgListName(list: Long, name: String): Reply =
        send("orgListName", "list" to list, "name" to name)

    /** The chip's colour dot, by palette slot. */
    fun orgListColor(list: Long, slot: Int): Reply =
        send("orgListColor", "list" to list, "slot" to slot)

    fun orgDeleteList(list: Long): Reply = send("orgDeleteList", "list" to list)

    /** The area's own stack — never the open page's. */
    fun orgUndo(): Reply = send("orgUndo")

    fun orgRedo(): Reply = send("orgRedo")

    // ─── LAN sync ───────────────────────────────────────────────────────────
    //
    // `syncState` is also the switch: the session starts the engine the first time
    // it is asked, so a shell that never opens the 同步 page spawns no threads and
    // binds no port. Everything else is that page's verbs, and every one answers
    // with the whole view — a peer table and a log line are what the screen is
    // made of, so there is no quiet path to keep.

    fun syncState(): Reply = send("syncState")

    fun syncSetAuto(on: Boolean): Reply = send("syncSetAuto", "on" to on)

    /** Seconds, floored by the bridge at 15. */
    fun syncSetInterval(seconds: Long): Reply = send("syncSetInterval", "seconds" to seconds)

    fun syncSetName(name: String): Reply = send("syncSetName", "name" to name)

    /** `192.168.1.20` or `192.168.1.20:5878` — the by-hand door onto the LAN. */
    fun syncAddPeer(ip: String, port: Int): Reply =
        send("syncAddPeer", "ip" to ip, "port" to port)

    fun syncPair(id: String): Reply = send("syncPair", "id" to id)

    fun syncNow(id: String): Reply = send("syncNow", "id" to id)

    fun syncForget(id: String): Reply = send("syncForget", "id" to id)

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
