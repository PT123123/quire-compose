package dev.quire.compose.ui

import dev.quire.compose.ORG_VIEW_ALL
import dev.quire.compose.ORG_VIEW_DONE
import dev.quire.compose.ORG_VIEW_INBOX
import dev.quire.compose.ORG_VIEW_TODAY
import dev.quire.compose.ORG_VIEW_WEEK
import dev.quire.compose.bridge.OrgCatalog
import dev.quire.compose.bridge.OrgNote
import dev.quire.compose.bridge.OrgTask
import java.util.Calendar
import java.util.Locale

/**
 * SPEC §四十一's projections: the catalog's rows as the screens draw them.
 *
 * This is the shell's *app layer*, and it is Kotlin because that is what this
 * shell's app layer is. The Rust shell keeps the same rules in
 * `src/app/state.rs` and reaches the same answers; the desktop keeps them in its
 * own copy. Nobody's `quire-core` does it — the core stores rows, and what a
 * smart view *means* is a question about a window.
 *
 * The rules are the Rust shell's, kept deliberately identical so one library
 * reads the same on both platforms:
 *
 * * The five smart views (收集箱 / 今天 / 近七天 / 全部 / 已完成) are predicates over
 *   the whole catalog, not five sets of data. `done` is deliberately **not** part
 *   of 今天 or 近七天 — whether a finished task is painted is one question, asked
 *   once by the list's footer switch, and folding it into the views would mean the
 *   switch could not turn it back on.
 * * A task whose list is gone reads as the inbox's. `tasks.list = 0` is the inbox
 *   by construction, and a dangling id is what a half-arrived merge leaves behind;
 *   one comparison answers both and neither task is lost.
 * * A deadline is a *date*, so the row's badge and the 今天 filter compare ISO
 *   strings, which compare correctly precisely because the format is fixed-width.
 *
 * **The day comes from the device's own clock, not from UTC.** The Rust shell takes
 * `now / 86_400` as days, which is the UTC day — so east of Greenwich, at 01:00,
 * it calls yesterday "today". A row's 今天 badge has to agree with the clock the
 * user just looked at, so this reads the local calendar instead (ADR-0012).
 */
object OrgModel {

    /** The five smart views, in the order their chips are drawn. */
    val smartNames: List<String> = listOf("收集箱", "今天", "近七天", "全部", "已完成")

    /** `Priority::ALL`'s labels, in its order: the slot is the index. */
    val priorityNames: List<String> = listOf("无", "低", "中", "高")

    /** `Repeat::ALL`'s labels, in its order: the slot is the index. */
    val repeatNames: List<String> = listOf("不重复", "每天", "工作日", "每周", "每月")

    /**
     * The 笔记 tab's three sorts, in the order its menu lists them. The reference
     * app's own three: 最新创建 / 最新更新 / 按内容.
     */
    val noteSortNames: List<String> = listOf("最新创建", "最新更新", "按内容")

    /**
     * The 任务 tab's five sorts, again the reference app's own list. `反向` is not
     * a replacement for 默认排序 but its mirror, which is why the two are separate
     * slots rather than one slot with a flag.
     */
    val taskSortNames: List<String> =
        listOf("默认排序", "最近添加", "反向", "按优先级", "按截止日期")

    /** The stored spellings, in slot order — `Priority::as_str`'s own list. */
    private val priorityKeys = listOf("none", "low", "medium", "high")
    private val repeatKeys = listOf("none", "daily", "weekdays", "weekly", "monthly")

    /** The colours a list's dot may draw from, minus `Default`. */
    val listColorSlots: List<Int> = listOf(6, 5, 3, 7, 8, 4)

    /**
     * The one clock read behind every "today" in a redraw.
     *
     * Taken once and passed down, because a rebuild that read the clock twice
     * could straddle midnight and file a task under one day while painting it as
     * another.
     */
    data class Dates(val today: String, val tomorrow: String, val week: String) {
        companion object {
            fun now(): Dates = Dates(
                today = localDay(0),
                tomorrow = localDay(1),
                week = localDay(7),
            )
        }
    }

    // ─── the rows a screen draws ────────────────────────────────────────────

    /**
     * One note as its card paints it.
     *
     * There is no title: the reference app's note *is* one blob of text, and this
     * card is that blob, its tags and its age. A note the desktop made with a
     * title and no body still reads here — the title is what the card falls back
     * to, because a card with nothing in it is worse than one showing the only
     * thing the row has.
     */
    data class NoteRow(
        val id: Long,
        val content: String,
        val tags: List<String>,
        val whenText: String,
        val pinned: Boolean,
        val selected: Boolean,
        /**
         * The note this one comments on, or `null` for an ordinary note. A
         * comment is modelled as a note with a ref rather than a type of its own,
         * so every projection here keeps working on one shape (ADR-0015).
         */
        val ref: Long? = null,
    )

    /** One task as the 任务 list paints it. */
    data class TaskRow(
        val id: Long,
        val title: String,
        val done: Boolean,
        /** 0 无 · 1 低 · 2 中 · 3 高. */
        val priority: Int,
        /** The stored ISO date, or `""`. */
        val due: String,
        /** What the badge says (今天 / 明天 / 09-30), or `""`. */
        val dueLabel: String,
        /** Past its day and not finished. The badge is a *state*, not a label. */
        val overdue: Boolean,
        val list: Long,
        val listName: String,
        val listColor: Int,
        val tags: List<String>,
        val notes: String,
        val repeat: Int,
        val subtasks: List<SubtaskRow>,
        val subtasksDone: Int,
        val subtasksTotal: Int,
        val whenText: String,
        val selected: Boolean,
    )

    data class SubtaskRow(val id: Long, val title: String, val done: Boolean)

    /** One chip in the list's chip row. */
    data class ListChip(
        val id: Long,
        val name: String,
        val color: Int,
        val count: Int,
        val selected: Boolean,
        /** The inbox: a projection, because it has no stored row. */
        val smart: Boolean,
    )

    data class TagChip(val name: String, val count: Int)

    /** One card of the board. */
    data class Card(
        val id: Long,
        val title: String,
        val tags: List<String>,
        val priority: Int,
        val due: String,
        val dueLabel: String,
        val overdue: Boolean,
        val done: Boolean,
    )

    /** One column of the board: the inbox first, then the stored lists. */
    data class Column(
        val id: Long,
        val name: String,
        val color: Int,
        val count: Int,
        val cards: List<Card>,
    )

    // ─── notes ──────────────────────────────────────────────────────────────

    fun notes(
        catalog: OrgCatalog,
        query: String,
        tag: String,
        sort: Int,
        selected: Long,
    ): List<NoteRow> {
        val needle = query.trim().lowercase()
        val rows = catalog.notes
            .filter { noteMatches(it, needle) }
            // The empty path is no filter, and it has to be said out loud: `any` over
            // a note that carries no tags at all is false, so a tagless note would be
            // filtered out of 全部笔记 by a filter that is not there.
            .filter { note -> tag.isEmpty() || note.tags.any { tagMatches(it, tag) } }
        return sortNotes(rows, sort)
            .map { noteRow(it, it.id == selected) }
    }

    fun noteDetail(catalog: OrgCatalog, selected: Long): NoteRow? =
        catalog.notes.firstOrNull { it.id == selected }?.let { noteRow(it, selected = true) }

    fun noteRow(note: OrgNote, selected: Boolean) = NoteRow(
        id = note.id,
        // The card is the note's text; a note with a title and no body falls back
        // to the title rather than to an empty card.
        content = note.body.ifEmpty { note.title },
        tags = note.tags,
        whenText = ageText(note.edited),
        pinned = note.pinned,
        selected = selected,
        ref = note.ref,
    )

    /**
     * A note's replies: the notes whose `ref` names it, newest first.
     *
     * A projection of the catalog and nothing more — a comment is an ordinary note
     * carrying a ref, so this needs no second store and no second bridge call. It
     * is why the comments list can never disagree with the notes list.
     */
    fun comments(catalog: OrgCatalog, noteId: Long): List<NoteRow> =
        catalog.notes
            .filter { it.ref == noteId }
            .sortedWith(compareByDescending<OrgNote> { it.created }.thenByDescending { it.id })
            .map { noteRow(it, selected = false) }

    /** How many replies a note has, for the 详细信息 sheet. */
    fun commentCount(catalog: OrgCatalog, noteId: Long): Int =
        catalog.notes.count { it.ref == noteId }

    /**
     * What a `↩` preview says: the parent's first non-empty line, trimmed to a
     * readable length. `null` when the parent is gone.
     *
     * A **dangling** ref is tolerated rather than scrubbed — the comment is the
     * user's writing and must not be deleted because the thing it answers was. A
     * ref to a note that is not here simply has no preview, the same way a
     * dangling `page_ref` paints as an ordinary block.
     */
    fun parentPreview(catalog: OrgCatalog, ref: Long): String? =
        catalog.notes.firstOrNull { it.id == ref }?.let { parent ->
            // `isNotBlank` and then `trim`: a line of spaces is not a line, and the
            // trim cannot land on empty afterwards.
            parent.body.ifEmpty { parent.title }
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(100)
        }

    /**
     * The three note sorts, with the pin above all of them: the flag is the user
     * saying "this one first", and a sort that overrode it would make the pin a
     * label rather than an instruction.
     */
    private fun sortNotes(notes: List<OrgNote>, sort: Int): List<OrgNote> =
        notes.sortedWith(
            compareByDescending<OrgNote> { it.pinned }
                .then(
                    when (sort) {
                        1 -> compareByDescending<OrgNote> { it.edited }.thenByDescending { it.id }
                        2 -> compareBy<OrgNote> { it.body.ifEmpty { it.title }.lowercase() }
                            .thenByDescending { it.id }
                        // 最新创建, the default: what the reference app opens on.
                        else -> compareByDescending<OrgNote> { it.created }.thenByDescending { it.id }
                    },
                ),
        )

    /**
     * The 笔记 tab's tag row: the direct children of the filter `path` — the top
     * level when nothing is filtered — each with the number of notes at or under
     * it, most used first.
     *
     * A tag is a **path** (`项目/工作/ActivityWatch`), and the row walks it the way
     * the reference app's does: 项目 → 工作 → ActivityWatch is three taps, and the
     * filter bar's ↑ is the way back. The count is the *subtree* count rather than
     * the number of notes carrying the tag literally, because a chip has to say how
     * many notes tapping it would leave on screen.
     */
    fun tagChips(catalog: OrgCatalog, path: String = ""): List<TagChip> =
        tagCounts(catalog).entries
            .filter { (full, _) -> tagParentPath(full).orEmpty() == path }
            .map { TagChip(it.key, it.value) }
            .sortedWith(compareByDescending<TagChip> { it.count }.thenBy { it.name })

    /**
     * Every tag prefix the notes carry, and how many notes sit at or under it.
     *
     * A note counts **once** per prefix however many of its tags pass through it:
     * two tags under `项目` are still one note under `项目`, and counting it twice
     * would make the chip a lie.
     */
    private fun tagCounts(catalog: OrgCatalog): LinkedHashMap<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (note in catalog.notes) {
            val prefixes = LinkedHashSet<String>()
            for (tag in note.tags) {
                val segments = tagSegments(tag)
                for (end in 1..segments.size) prefixes += segments.subList(0, end).joinToString("/")
            }
            for (prefix in prefixes) counts[prefix] = (counts[prefix] ?: 0) + 1
        }
        return counts
    }

    // ─── a tag is a path ────────────────────────────────────────────────────
    //
    // The reference app's 层级标签: a tag may be `项目/工作/ActivityWatch`, and every
    // screen that shows or filters one has to agree on what its segments are. The
    // rules are the reference app's (`tagSegments` / `tagParentPath` /
    // `formatTagBreadcrumb`), kept here so both organizer pages, the capture
    // overlay and the tests ask the same question.

    /** A tag as its segments: `项目/工作` → [项目, 工作]. Empty segments are dropped. */
    fun tagSegments(tag: String): List<String> =
        tag.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    /** The parent path of a hierarchical tag; `null` for a single-segment one. */
    fun tagParentPath(tag: String): String? {
        val segments = tagSegments(tag)
        return if (segments.size <= 1) null else segments.dropLast(1).joinToString("/")
    }

    /** A tag's breadcrumb: `项目/工作/xx` → `项目 / 工作 / xx`. */
    fun tagBreadcrumb(tag: String): String = tagSegments(tag).joinToString(" / ")

    /**
     * A tag as a card or a chip paints it: `#项目 / 工作` — the `#` only on the front,
     * because that is what the user typed and what the text's own `#token` is.
     */
    fun tagLabel(tag: String): String = "#" + tagBreadcrumb(tag)

    /**
     * Whether a tag sits at or under a filter path, on **segment boundaries**: `项目`
     * keeps `项目` and `项目/工作` and drops `项目2`, which a plain prefix test would
     * keep. An empty path is no filter and keeps everything.
     */
    fun tagMatches(candidate: String, path: String): Boolean =
        path.isEmpty() || candidate == path || candidate.startsWith("$path/")

    /**
     * A note's title when it is turned into a task (转为待办): the note's own title if
     * it has one, else its first non-empty line with the markdown that opens it
     * stripped, cut to a length a task row can show. The reference app's own rule —
     * a title that is a heading marker is not a title.
     */
    fun taskTitle(note: OrgNote): String {
        val title = note.title.trim()
        if (title.isNotEmpty()) return title.take(50)
        return note.body.lineSequence()
            .map(::stripLead)
            .firstOrNull { it.isNotEmpty() }
            ?.take(50)
            .orEmpty()
    }

    /** A line with the markdown that opens it taken off: `## 会议` → `会议`. */
    private fun stripLead(line: String): String =
        line.trim()
            .replace(Regex("^(?:[#>\\-*+]\\s*)+"), "")
            .replace(Regex("^\\d+[.)、]\\s*"), "")
            .trim()

    // ─── tasks ──────────────────────────────────────────────────────────────

    /**
     * The list, filtered and sorted. One selector with two halves: a stored list
     * id, or a smart view slot (`list < 0` means "the chip decides").
     */
    fun tasks(
        catalog: OrgCatalog,
        view: Int,
        list: Long,
        query: String,
        sort: Int,
        selected: Long,
        showDone: Boolean,
        dates: Dates,
    ): List<TaskRow> {
        val needle = query.trim().lowercase()
        // 已完成 is the one view whose answer *is* the finished ones; everywhere
        // else they are hidden until the footer's switch says otherwise.
        val keepDone = showDone || view == ORG_VIEW_DONE
        val rows = catalog.tasks.filter { task ->
            val bucket = if (list >= 0) task.list == list else inSmartView(catalog, task, view, dates)
            val finished = if (view == ORG_VIEW_DONE) task.done else !task.done || keepDone
            bucket && finished && taskMatches(task, needle)
        }
        return sortTasks(rows, sort)
            .map { taskRow(catalog, it, dates, it.id == selected) }
    }

    fun taskDetail(catalog: OrgCatalog, selected: Long, dates: Dates): TaskRow? =
        catalog.tasks.firstOrNull { it.id == selected }
            ?.let { taskRow(catalog, it, dates, selected = true) }

    fun taskRow(catalog: OrgCatalog, task: OrgTask, dates: Dates, selected: Boolean): TaskRow {
        // The row's list, folded the way the inbox view folds it: a task whose list
        // is gone files under 收集箱 rather than disappearing from every view,
        // because it is still in the file and the user can still move it.
        val list = catalog.lists.firstOrNull { it.id == task.list }
        return TaskRow(
            id = task.id,
            title = task.title,
            done = task.done,
            priority = prioritySlot(task.priority),
            due = task.due ?: "",
            dueLabel = dueLabel(task.due, dates),
            // The badge is a *state*, not part of the date's label: an overdue task
            // still has to say which day it was due.
            overdue = !task.done && overdue(task.due, dates),
            list = task.list,
            listName = list?.name ?: "收集箱",
            listColor = list?.color ?: 0,
            tags = task.tags,
            notes = task.notes,
            repeat = repeatSlot(task.repeat),
            subtasks = task.subtasks.map { SubtaskRow(it.id, it.title, it.done) },
            subtasksDone = task.subtasks.count { it.done },
            subtasksTotal = task.subtasks.size,
            whenText = ageText(task.edited),
            selected = selected,
        )
    }

    /**
     * The chip row: the inbox first, then the stored lists in their own order.
     *
     * The inbox's chip is the *view* half of the selector, which is why it is
     * built here rather than stored: `ListId(0)` is a sentinel with no row, so a
     * chip for it could only ever be a projection.
     */
    fun listChips(catalog: OrgCatalog, view: Int, list: Long): List<ListChip> {
        val inbox = ListChip(
            id = -1,
            name = "收集箱",
            color = 0,
            // The inbox's count is every task that *reads* as the inbox's, which
            // includes a task whose list a merge left dangling.
            count = catalog.tasks.count { task -> catalog.lists.none { it.id == task.list } },
            selected = list < 0 && view == ORG_VIEW_INBOX,
            smart = true,
        )
        val rows = catalog.lists
            .sortedBy { it.ord }
            .map { stored ->
                ListChip(
                    id = stored.id,
                    name = stored.name,
                    color = stored.color,
                    count = catalog.tasks.count { it.list == stored.id },
                    selected = list >= 0 && stored.id == list,
                    smart = false,
                )
            }
        return listOf(inbox) + rows
    }

    /**
     * The five numbers the smart chips carry, in their own slot order — so the row
     * that says 今天 and the list that *is* 今天 cannot disagree. Open tasks for the
     * four views a task can be open in, and the finished ones for 已完成, which is
     * the one whose answer is the other half of the same count.
     */
    fun smartCounts(catalog: OrgCatalog, query: String, dates: Dates): List<Int> {
        val needle = query.trim().lowercase()
        return (0..4).map { slot ->
            catalog.tasks.count { task ->
                task.done == (slot == ORG_VIEW_DONE) &&
                    inSmartView(catalog, task, slot, dates) &&
                    taskMatches(task, needle)
            }
        }
    }

    /**
     * The board: the same catalog filed under the lists instead of filtered by
     * one. Open tasks only — a board is a "what is left" view, and the finished
     * ones have a view of their own.
     */
    fun board(catalog: OrgCatalog, query: String, sort: Int, dates: Dates): List<Column> {
        val needle = query.trim().lowercase()
        val heads = mutableListOf(Triple(-1L, "收集箱", 0))
        for (stored in catalog.lists.sortedBy { it.ord }) {
            heads += Triple(stored.id, stored.name, stored.color)
        }
        return heads.map { (id, name, color) ->
            val cards = sortTasks(
                catalog.tasks.filter { task ->
                    val bucket = if (id < 0) {
                        catalog.lists.none { it.id == task.list }
                    } else {
                        task.list == id
                    }
                    bucket && !task.done && taskMatches(task, needle)
                },
                sort,
            ).map { task ->
                Card(
                    id = task.id,
                    title = task.title,
                    tags = task.tags,
                    priority = prioritySlot(task.priority),
                    due = task.due ?: "",
                    dueLabel = dueLabel(task.due, dates),
                    overdue = overdue(task.due, dates),
                    done = task.done,
                )
            }
            Column(id = id, name = name, color = color, count = cards.size, cards = cards)
        }
    }

    /**
     * The name of the thing the chips are on: the stored list when one is
     * selected, the smart view's own word otherwise. One selector with two halves
     * has to read back as whichever half is showing — the quick-add line says it,
     * and so does the list's header.
     */
    fun scopeName(catalog: OrgCatalog, view: Int, list: Long): String =
        catalog.lists.firstOrNull { list >= 0 && it.id == list }?.name
            ?: smartNames.getOrElse(view) { smartNames[ORG_VIEW_INBOX] }

    /**
     * The list header's two words: the *list* when one is selected and the view's
     * own name otherwise.
     */
    fun header(
        catalog: OrgCatalog,
        tab: Int,
        view: Int,
        list: Long,
        query: String,
        mode: Int,
        tag: String,
        dates: Dates,
    ): Pair<String, String> {
        if (tab == 0) {
            // The header is the *tab's* header: the notes count notes, and a board
            // counts lists — one line reading "2 项待办" over a list of notes would
            // be the window describing something it is not showing.
            val notes = notes(catalog, query, tag, sort = 0, selected = -1)
            return (if (tag.isEmpty()) "全部笔记" else tagBreadcrumb(tag)) to "${notes.size} 条笔记"
        }
        if (mode == 1) {
            val columns = board(catalog, query, sort = 0, dates = dates)
            val open = columns.sumOf { it.count }
            return "全部清单" to "${columns.size} 个清单 · $open 项待办"
        }
        val needle = query.trim().lowercase()
        val title = catalog.lists.firstOrNull { list >= 0 && it.id == list }?.name
            ?: smartNames.getOrElse(view) { smartNames[ORG_VIEW_INBOX] }
        val count = catalog.tasks.count { task ->
            val bucket = if (list >= 0) task.list == list else inSmartView(catalog, task, view, dates)
            bucket && task.done == (view == ORG_VIEW_DONE) && taskMatches(task, needle)
        }
        return title to "$count 项待办"
    }

    /**
     * 已完成 X / Y over the whole area, not over the filtered view: a progress line
     * that moved when the user typed a search would be answering a different
     * question from the one it looks like it answers.
     */
    fun progress(catalog: OrgCatalog): Pair<Int, Int> =
        catalog.tasks.count { it.done } to catalog.tasks.size

    // ─── the shared vocabulary ──────────────────────────────────────────────

    /** A stored priority string as its menu slot. An unknown one is 无. */
    fun prioritySlot(priority: String): Int = priorityKeys.indexOf(priority).coerceAtLeast(0)

    /** A stored repeat string as its menu slot. An unknown one is 不重复. */
    fun repeatSlot(repeat: String): Int = repeatKeys.indexOf(repeat).coerceAtLeast(0)

    /**
     * Whether a task belongs to one of the five smart views. The catalog is here
     * for one thing: the inbox — a task is in it when its `list` names no stored
     * list.
     */
    fun inSmartView(catalog: OrgCatalog, task: OrgTask, view: Int, dates: Dates): Boolean {
        val due = task.due
        return when (view) {
            ORG_VIEW_INBOX -> catalog.lists.none { it.id == task.list }
            ORG_VIEW_TODAY -> due == dates.today
            ORG_VIEW_WEEK -> due != null && due > dates.today && due <= dates.week
            ORG_VIEW_DONE -> task.done
            // 全部, and any slot a future build writes: everything. A view this
            // build does not know must show the rows rather than none of them.
            else -> true
        }
    }

    /**
     * The five sorts, in one place because the list and the board share them: a
     * column's cards and the list's rows have to answer "which is next"
     * identically, or a task would sit in a different position depending on which
     * view asked.
     */
    private fun sortTasks(tasks: List<OrgTask>, sort: Int): List<OrgTask> = when (sort) {
        // 按优先级, and then the earlier deadline, and then where it was added.
        3 -> tasks.sortedWith(
            compareByDescending<OrgTask> { prioritySlot(it.priority) }
                .thenBy { it.due ?: "" }
                .thenBy { it.ord },
        )
        // 按截止日期: undated last, then by day, then by where it was added.
        4 -> tasks.sortedWith(
            compareBy<OrgTask> { it.due == null }
                .thenBy { it.due ?: "" }
                .thenBy { it.ord },
        )
        // 最近添加: newest first, which is not the same question as 默认排序 —
        // that one is the order the tasks were *placed* in, and a task dragged
        // into the middle of a list keeps its place under it.
        1 -> tasks.sortedWith(
            compareByDescending<OrgTask> { it.created }.thenBy { it.ord },
        )
        // 反向: 默认排序's mirror, which is why it is a slot of its own.
        2 -> tasks.sortedWith(
            compareByDescending<OrgTask> { it.list }.thenByDescending { it.ord },
        )
        // 默认排序, and any slot a future build writes: `ord` is this area's own
        // reading order, and every sort above is a question asked *of* it rather
        // than a replacement for it.
        else -> tasks.sortedWith(compareBy({ it.list }, { it.ord }))
    }

    private fun noteMatches(note: OrgNote, needle: String): Boolean =
        needle.isEmpty() ||
            note.title.lowercase().contains(needle) ||
            note.body.lowercase().contains(needle) ||
            note.tags.any { it.lowercase().contains(needle) }

    private fun taskMatches(task: OrgTask, needle: String): Boolean =
        needle.isEmpty() ||
            task.title.lowercase().contains(needle) ||
            task.notes.lowercase().contains(needle) ||
            task.tags.any { it.lowercase().contains(needle) } ||
            task.subtasks.any { it.title.lowercase().contains(needle) }

    /**
     * A deadline as the row paints it. Short and relative where that is what the
     * user means (今天 / 明天), the stored day where it is not — and 逾期 is the
     * *badge*, not a string, so an overdue task still says which day it was due.
     */
    fun dueLabel(due: String?, dates: Dates): String {
        if (due == null || due.isEmpty()) return ""
        if (due == dates.today) return "今天"
        if (due == dates.tomorrow) return "明天"
        // Same year: the year is noise. Another year: it is the whole point.
        return if (due.length >= 10 && due.substring(0, 4) == dates.today.substring(0, 4)) {
            due.substring(5)
        } else {
            due
        }
    }

    fun overdue(due: String?, dates: Dates): Boolean =
        due != null && due.isNotEmpty() && due < dates.today

    /**
     * How long ago something happened, in the coarsest unit that still says
     * something. Ages and not clock times: a time would be either UTC, which is
     * wrong to the reader, or a zone conversion nothing in this app keeps.
     */
    fun ageText(epochSeconds: Long): String {
        val age = nowSeconds() - epochSeconds
        return when {
            age < 60 -> "刚刚"
            age < 3_600 -> "${age / 60} 分钟前"
            age < 86_400 -> "${age / 3_600} 小时前"
            age < 86_400L * 30 -> "${age / 86_400} 天前"
            else -> dayOf(epochSeconds)
        }
    }

    /** The ISO date `offset` days from today, on the device's own calendar. */
    private fun localDay(offset: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_MONTH, offset)
        return formatIso(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** The ISO date an instant falls on, for an age too old to be counted in days. */
    private fun dayOf(epochSeconds: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = epochSeconds * 1_000L
        return formatIso(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    // `Locale.ROOT` and not the default: some locales shape `%d`'s digits, and a
    // deadline the Rust side cannot parse as `YYYY-MM-DD` would be refused.
    private fun formatIso(year: Int, month: Int, day: Int): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1_000L

    /** The day the quick-add line files a task under when it is typed into 今天. */
    fun today(): String = Dates.now().today
}

/** The priority glyphs, in slot order: none draws nothing. */
fun priorityGlyph(slot: Int): String = when (slot) {
    3 -> "▲"
    2 -> "◆"
    else -> "▼"
}
