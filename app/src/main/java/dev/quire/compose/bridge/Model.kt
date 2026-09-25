package dev.quire.compose.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire shape, decoded.
 *
 * Hand-written from `org.json` rather than a serialization library on purpose:
 * the payloads are flat, this is the only place JSON is touched, and the
 * alternative is a compiler plugin plus a runtime for a hundred lines of
 * mapping. The one subtlety is that every position and length here is a **byte**
 * offset, because that is what `quire-core` stores for a block's inline marks —
 * [dev.quire.compose.ui.marksRange] is where they become UTF-16 spans.
 */

data class MarkRow(
    val start: Int,
    val end: Int,
    val kind: String,
    val url: String,
)

data class PageRow(
    val id: Long,
    val title: String,
    val depth: Int,
    val parent: Long?,
    val favorite: Boolean,
    val expanded: Boolean,
    val hasChildren: Boolean,
    val icon: String,
    val locked: Boolean,
) {
    /** What the row draws when the page has no icon: its title's first character. */
    val initial: String get() = title.firstOrNull()?.toString() ?: ""
}

data class BlockRow(
    val id: Long,
    val kind: String,
    val text: String,
    val checked: Boolean,
    val depth: Int,
    val parent: Long?,
    val folded: Boolean,
    val hasChildren: Boolean,
    val lang: String,
    /** Text colour slot: 0 = the theme's ink, 1..9 = gray..red. */
    val color: Int,
    /** Row background slot, same numbering; 0 = transparent. */
    val background: Int,
    val pageRef: Long?,
    val dbRef: Long?,
    val attachment: Long?,
    val imgPercent: Int,
    val marks: List<MarkRow>,
) {
    val isTextual: Boolean
        get() = kind !in setOf(
            "divider", "image", "file", "table", "table_cell", "columns", "column",
            "toc", "database", "synced", "page", "embed", "math",
        )
}

data class View(
    val pages: List<PageRow>,
    val blocks: List<BlockRow>,
    val activePage: Long?,
    val title: String,
    val icon: String,
    val locked: Boolean,
    val theme: String,
    val recents: List<Long>,
    val favorites: List<Long>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** The same two answers for the organizer's own stack. Two stacks, two pairs. */
    val orgCanUndo: Boolean,
    val orgCanRedo: Boolean,
    val notice: String?,
    val pageCount: Int,
    /**
     * SPEC §四十一's whole catalog. Rows only — the five smart views, the three
     * sorts and the row badges are derived in `ui/OrgModel.kt`, which is what
     * keeps typing in a search box off the bridge.
     */
    val org: OrgCatalog,
)

// ─── the organizer's rows ───────────────────────────────────────────────────
//
// The core's own rows, not a view: `ui/OrgModel.kt` is where they become a
// screen. Every field is here because a screen draws it or a write needs it, and
// the two enums travel as the short stable strings the database stores
// (`Priority::as_str`) so no second vocabulary exists for the same fact.

data class OrgNote(
    val id: Long,
    val title: String,
    val body: String,
    val pinned: Boolean,
    val tags: List<String>,
    val created: Long,
    val edited: Long,
    /**
     * The note this one comments on, or `null`. A **dangling** id is possible and
     * is not an error: the note it named may have been deleted, and the rule is
     * that a ref which resolves to nothing simply paints as an ordinary note.
     */
    val ref: Long?,
)

data class OrgSubtask(
    val id: Long,
    val title: String,
    val done: Boolean,
)

data class OrgTask(
    val id: Long,
    /** `0` is the inbox — a sentinel, not a row. */
    val list: Long,
    val title: String,
    val notes: String,
    /** `none` | `low` | `medium` | `high`. */
    val priority: String,
    /** `YYYY-MM-DD`, or null. A date, not an instant. */
    val due: String?,
    /** `none` | `daily` | `weekdays` | `weekly` | `monthly`. */
    val repeat: String,
    val done: Boolean,
    val completedAt: Long?,
    val tags: List<String>,
    val subtasks: List<OrgSubtask>,
    val created: Long,
    val edited: Long,
    /** Reading order inside the list: the "添加顺序" sort is these two numbers. */
    val ord: Long,
)

data class OrgList(
    val id: Long,
    val name: String,
    /** Palette slot: 0 = the theme default, 1..9 = gray..red. */
    val color: Int,
    val ord: Long,
)

/** One reply's worth of the organizer. */
data class OrgCatalog(
    val notes: List<OrgNote>,
    val tasks: List<OrgTask>,
    /** The stored lists; the inbox has no row and is a chip the UI builds. */
    val lists: List<OrgList>,
) {
    companion object {
        val Empty = OrgCatalog(emptyList(), emptyList(), emptyList())
    }
}

/** What one call answered. */
sealed interface Reply {
    /** The structure changed; redraw from this. */
    data class Updated(val view: View) : Reply

    /** It stuck, and there is nothing new to draw (a keystroke, a tick, a flush). */
    data object Done : Reply

    data class Failed(val message: String) : Reply
}

internal fun parseReply(raw: String?): Reply {
    if (raw == null) {
        return Reply.Failed("桥接层不可用（native 库未加载）")
    }
    val json = try {
        JSONObject(raw)
    } catch (e: Exception) {
        return Reply.Failed("桥接层返回了无法解析的内容: ${e.message}")
    }
    if (!json.optBoolean("ok", false)) {
        return Reply.Failed(json.optString("error").ifEmpty { "未知错误" })
    }
    val view = json.optJSONObject("view") ?: return Reply.Done
    return Reply.Updated(parseView(view))
}

private fun parseView(json: JSONObject): View = View(
    pages = json.getJSONArray("pages").mapObjects(::parsePage),
    blocks = json.getJSONArray("blocks").mapObjects(::parseBlock),
    activePage = json.longOrNull("activePage"),
    title = json.optString("title"),
    icon = json.optString("icon"),
    locked = json.optBoolean("locked"),
    theme = json.optString("theme", "system"),
    recents = json.getJSONArray("recents").longs(),
    favorites = json.getJSONArray("favorites").longs(),
    canUndo = json.optBoolean("canUndo"),
    canRedo = json.optBoolean("canRedo"),
    orgCanUndo = json.optBoolean("orgCanUndo"),
    orgCanRedo = json.optBoolean("orgCanRedo"),
    notice = if (json.isNull("notice")) null else json.optString("notice").ifEmpty { null },
    pageCount = json.optInt("pageCount"),
    org = json.optJSONObject("org")?.let(::parseOrg) ?: OrgCatalog.Empty,
)

private fun parsePage(json: JSONObject) = PageRow(
    id = json.getLong("id"),
    title = json.optString("title"),
    depth = json.optInt("depth"),
    parent = json.longOrNull("parent"),
    favorite = json.optBoolean("favorite"),
    expanded = json.optBoolean("expanded"),
    hasChildren = json.optBoolean("hasChildren"),
    icon = json.optString("icon"),
    locked = json.optBoolean("locked"),
)

private fun parseBlock(json: JSONObject) = BlockRow(
    id = json.getLong("id"),
    kind = json.optString("kind", "paragraph"),
    text = json.optString("text"),
    checked = json.optBoolean("checked"),
    depth = json.optInt("depth"),
    parent = json.longOrNull("parent"),
    folded = json.optBoolean("folded"),
    hasChildren = json.optBoolean("hasChildren"),
    lang = json.optString("lang"),
    color = json.optInt("color"),
    background = json.optInt("background"),
    pageRef = json.longOrNull("pageRef"),
    dbRef = json.longOrNull("dbRef"),
    attachment = json.longOrNull("attachment"),
    imgPercent = json.optInt("imgPercent", 100),
    marks = json.getJSONArray("marks").mapObjects(::parseMark),
)

private fun parseMark(json: JSONObject) = MarkRow(
    start = json.optInt("start"),
    end = json.optInt("end"),
    kind = json.optString("kind"),
    url = json.optString("url"),
)

private fun parseOrg(json: JSONObject) = OrgCatalog(
    notes = json.getJSONArray("notes").mapObjects(::parseNote),
    tasks = json.getJSONArray("tasks").mapObjects(::parseTask),
    lists = json.getJSONArray("lists").mapObjects(::parseList),
)

private fun parseNote(json: JSONObject) = OrgNote(
    id = json.getLong("id"),
    title = json.optString("title"),
    body = json.optString("body"),
    pinned = json.optBoolean("pinned"),
    tags = json.getJSONArray("tags").strings(),
    created = json.optLong("created"),
    edited = json.optLong("edited"),
    ref = json.longOrNull("ref"),
)

private fun parseTask(json: JSONObject) = OrgTask(
    id = json.getLong("id"),
    list = json.optLong("list"),
    title = json.optString("title"),
    notes = json.optString("notes"),
    priority = json.optString("priority", "none"),
    // An absent deadline is no deadline — the empty string would be two spellings
    // of one state.
    due = if (json.isNull("due")) null else json.optString("due").ifEmpty { null },
    repeat = json.optString("repeat", "none"),
    done = json.optBoolean("done"),
    completedAt = json.longOrNull("completedAt"),
    tags = json.getJSONArray("tags").strings(),
    subtasks = json.getJSONArray("subtasks").mapObjects(::parseSubtask),
    created = json.optLong("created"),
    edited = json.optLong("edited"),
    ord = json.optLong("ord"),
)

private fun parseSubtask(json: JSONObject) = OrgSubtask(
    id = json.getLong("id"),
    title = json.optString("title"),
    done = json.optBoolean("done"),
)

private fun parseList(json: JSONObject) = OrgList(
    id = json.getLong("id"),
    name = json.optString("name"),
    color = json.optInt("color"),
    ord = json.optLong("ord"),
)

private fun JSONObject.longOrNull(name: String): Long? =
    if (isNull(name)) null else getLong(name)

private fun <T> JSONArray.mapObjects(parse: (JSONObject) -> T): List<T> =
    (0 until length()).map { parse(getJSONObject(it)) }

private fun JSONArray.longs(): List<Long> = (0 until length()).map { getLong(it) }

private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
