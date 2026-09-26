package dev.quire.compose.ui

import dev.quire.compose.ORG_VIEW_ALL
import dev.quire.compose.ORG_VIEW_DONE
import dev.quire.compose.ORG_VIEW_INBOX
import dev.quire.compose.ORG_VIEW_TODAY
import dev.quire.compose.ORG_VIEW_WEEK
import dev.quire.compose.bridge.OrgCatalog
import dev.quire.compose.bridge.OrgList
import dev.quire.compose.bridge.OrgNote
import dev.quire.compose.bridge.OrgSubtask
import dev.quire.compose.bridge.OrgTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §四十一's projections, which are the part of the area that can be wrong
 * without looking wrong: a task filed under the wrong day reads as a correct row
 * in the wrong place, and a sort that is *nearly* right reads as a sort that is
 * right. Every rule here has a twin in the Rust shell's `src/app/state.rs`, and
 * these assertions are the same ones that file makes.
 */
class OrgModelTest {

    private val dates = OrgModel.Dates(today = "2026-09-25", tomorrow = "2026-09-26", week = "2026-10-02")

    private fun task(
        id: Long,
        list: Long = 0,
        due: String? = null,
        done: Boolean = false,
        priority: String = "none",
        ord: Long = id,
        title: String = "task $id",
        tags: List<String> = emptyList(),
        subtasks: List<OrgSubtask> = emptyList(),
    ) = OrgTask(
        id = id,
        list = list,
        title = title,
        notes = "",
        priority = priority,
        due = due,
        repeat = "none",
        done = done,
        completedAt = null,
        tags = tags,
        subtasks = subtasks,
        created = 0,
        edited = 0,
        ord = ord,
    )

    private fun note(
        id: Long,
        pinned: Boolean = false,
        edited: Long = 0,
        title: String = "note $id",
        body: String = "",
        tags: List<String> = emptyList(),
        /** The note it comments on, when it is a reply. */
        ref: Long? = null,
    ) = OrgNote(
        id = id,
        title = title,
        body = body,
        pinned = pinned,
        tags = tags,
        created = 0,
        edited = edited,
        ref = ref,
    )

    private val oneList = listOf(OrgList(id = 5, name = "工作", color = 6, ord = 1))

    // ─── the five smart views ───────────────────────────────────────────────

    @Test
    fun the_inbox_is_where_the_list_is() {
        val catalog = OrgCatalog(emptyList(), listOf(task(1), task(2, list = 5)), oneList)
        assertEquals(listOf(1L), ids(OrgModel.tasks(catalog, ORG_VIEW_INBOX, -1, "", 0, -1, false, dates)))
    }

    @Test
    fun a_task_whose_list_is_gone_reads_as_the_inboxs() {
        // What a half-arrived merge leaves behind: the list's deletion landed and
        // the task naming it stayed local. It must file under 收集箱 rather than
        // vanish from every view.
        val catalog = OrgCatalog(emptyList(), listOf(task(1, list = 99)), oneList)
        assertEquals(listOf(1L), ids(OrgModel.tasks(catalog, ORG_VIEW_INBOX, -1, "", 0, -1, false, dates)))
        assertEquals("收集箱", OrgModel.taskRow(catalog, catalog.tasks[0], dates, false).listName)
    }

    @Test
    fun today_is_one_exact_day_and_the_week_is_the_rest_of_it() {
        val catalog = OrgCatalog(
            emptyList(),
            listOf(
                task(1, due = "2026-09-24"), // yesterday
                task(2, due = "2026-09-25"), // today
                task(3, due = "2026-09-26"), // tomorrow
                task(4, due = "2026-10-02"), // the last day inside
                task(5, due = "2026-10-03"), // outside
                task(6), // undated
            ),
            emptyList(),
        )
        assertEquals(listOf(2L), ids(OrgModel.tasks(catalog, ORG_VIEW_TODAY, -1, "", 0, -1, false, dates)))
        // The week is the next seven days and *not* today: two views that both
        // claimed today would make a task appear twice in a row of chips.
        assertEquals(listOf(3L, 4L), ids(OrgModel.tasks(catalog, ORG_VIEW_WEEK, -1, "", 0, -1, false, dates)))
        // 全部 is everything, undated included.
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L),
            ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 0, -1, false, dates)),
        )
    }

    @Test
    fun done_is_one_question_asked_once() {
        val catalog = OrgCatalog(emptyList(), listOf(task(1), task(2, done = true)), emptyList())
        // The four open views never answer with a finished task…
        for (view in listOf(ORG_VIEW_INBOX, ORG_VIEW_ALL)) {
            assertEquals(listOf(1L), ids(OrgModel.tasks(catalog, view, -1, "", 0, -1, false, dates)))
        }
        // …unless the footer's switch says otherwise, which is why `done` is not
        // part of a view's predicate: a view that filtered it could not turn it
        // back on.
        assertEquals(
            listOf(1L, 2L),
            ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 0, -1, showDone = true, dates)),
        )
        // 已完成 is the one view whose answer IS the finished ones, whatever the
        // switch says.
        for (showDone in listOf(false, true)) {
            assertEquals(listOf(2L), ids(OrgModel.tasks(catalog, ORG_VIEW_DONE, -1, "", 0, -1, showDone, dates)))
        }
    }

    @Test
    fun the_smart_counts_are_open_rows_and_the_finished_ones() {
        val catalog = OrgCatalog(
            emptyList(),
            listOf(
                task(1, due = "2026-09-25"),
                task(2, due = "2026-09-25"),
                task(3, due = "2026-09-27"),
                task(4, done = true, due = "2026-09-25"),
            ),
            emptyList(),
        )
        // 收集箱 3 open · 今天 2 · 近七天 1 · 全部 3 · 已完成 1
        assertEquals(listOf(3, 2, 1, 3, 1), OrgModel.smartCounts(catalog, "", dates))
        // The needle moves the counts with it: the chip and the list it opens are
        // the same question. "task 3" is the one due inside the week and not today.
        assertEquals(listOf(1, 0, 1, 1, 0), OrgModel.smartCounts(catalog, "task 3", dates))
    }

    // ─── the three sorts ────────────────────────────────────────────────────

    @Test
    fun the_added_order_is_the_areas_own_reading_order() {
        val catalog = OrgCatalog.Empty.copy(
            tasks = listOf(task(1, ord = 30), task(2, ord = 10), task(3, ord = 20)),
        )
        assertEquals(listOf(2L, 3L, 1L), ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 0, -1, false, dates)))
    }

    @Test
    fun the_priority_sort_breaks_ties_by_deadline_and_then_by_order() {
        val catalog = OrgCatalog.Empty.copy(
            tasks = listOf(
                task(1, priority = "low", ord = 10),
                task(2, priority = "high", due = "2026-09-30", ord = 20),
                task(3, priority = "high", due = "2026-09-26", ord = 30),
                task(4, priority = "high", due = "2026-09-26", ord = 40),
                task(5, priority = "medium", ord = 50),
            ),
        )
        // High first, and inside high the earlier day wins; two the same day fall
        // back to where they were added. Slot 3 is 按优先级 in the toolbar's menu.
        assertEquals(
            listOf(3L, 4L, 2L, 5L, 1L),
            ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 3, -1, false, dates)),
        )
    }

    @Test
    fun the_deadline_sort_puts_the_undated_last() {
        val catalog = OrgCatalog.Empty.copy(
            tasks = listOf(
                task(1),
                task(2, due = "2026-09-30"),
                task(3, due = "2026-09-26"),
                task(4),
            ),
        )
        // Slot 4 is 按截止日期 in the menu the reference app's toolbar opens.
        assertEquals(
            listOf(3L, 2L, 1L, 4L),
            ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 4, -1, false, dates)),
        )
    }

    @Test
    fun the_reverse_sort_is_the_default_ones_mirror() {
        val catalog = OrgCatalog.Empty.copy(
            tasks = listOf(task(1, ord = 30), task(2, ord = 10), task(3, ord = 20)),
        )
        val forward = ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 0, -1, false, dates))
        val backward = ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 2, -1, false, dates))
        assertEquals(listOf(2L, 3L, 1L), forward)
        assertEquals(forward.reversed(), backward)
    }

    @Test
    fun the_recent_sort_is_about_when_a_task_was_made_not_where_it_sits() {
        // A task dragged to the middle of a list keeps its `ord`, so 最近添加 and
        // 默认排序 answer different questions.
        val catalog = OrgCatalog.Empty.copy(
            tasks = listOf(
                task(1, ord = 10).copy(created = 100),
                task(2, ord = 30).copy(created = 300),
                task(3, ord = 20).copy(created = 200),
            ),
        )
        // 最近添加: newest first.
        assertEquals(
            listOf(2L, 3L, 1L),
            ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 1, -1, false, dates)),
        )
        // 默认排序: the placement order, which here is the other way round.
        assertEquals(
            listOf(1L, 3L, 2L),
            ids(OrgModel.tasks(catalog, ORG_VIEW_ALL, -1, "", 0, -1, false, dates)),
        )
    }

    @Test
    fun the_board_sorts_its_cards_the_way_the_list_sort_its_rows() {
        // One catalog, two screens, one answer about "which is next".
        val catalog = OrgCatalog.Empty.copy(
            tasks = listOf(
                task(1, priority = "low"),
                task(2, priority = "high"),
                task(3, priority = "medium"),
            ),
        )
        val column = OrgModel.board(catalog, "", sort = 3, dates = dates).first()
        assertEquals(listOf(2L, 3L, 1L), column.cards.map { it.id })
    }

    // ─── notes ──────────────────────────────────────────────────────────────

    @Test
    fun the_note_sorts_are_the_three_the_menu_lists() {
        val catalog = OrgCatalog(
            listOf(
                note(1, edited = 100, title = "banana").copy(created = 500),
                note(2, pinned = true, edited = 50, title = "cherry").copy(created = 100),
                note(3, edited = 900, title = "apple").copy(created = 300),
            ),
            emptyList(),
            emptyList(),
        )

        // 最新创建 is the default: newest first, and the pin above all of it — the
        // flag is the user saying "this one first", and a sort that overrode it
        // would make the pin a label rather than an instruction.
        assertEquals(listOf(2L, 1L, 3L), OrgModel.notes(catalog, "", "", 0, -1).map { it.id })
        // 最新更新: the pinned one, then by when each was last written.
        assertEquals(listOf(2L, 3L, 1L), OrgModel.notes(catalog, "", "", 1, -1).map { it.id })
        // 按内容: alphabetically by the text, which is what a tag-less note list
        // is read by when the user is looking for a *word*.
        assertEquals(listOf(2L, 3L, 1L), OrgModel.notes(catalog, "", "", 2, -1).map { it.id })
    }

    @Test
    fun a_note_card_shows_its_text_and_falls_back_to_the_title() {
        // The reference app's note is one blob of text, so the card is the body.
        val body = OrgCatalog.Empty.copy(notes = listOf(note(1, body = "第一行\n第二行")))
        assertEquals("第一行\n第二行", OrgModel.notes(body, "", "", 0, -1)[0].content)

        // A note the desktop made with a title and no body still reads here: a card
        // showing nothing is worse than one showing the only thing the row has.
        val titled = OrgCatalog.Empty.copy(notes = listOf(note(1, title = "会议")))
        assertEquals("会议", OrgModel.notes(titled, "", "", 0, -1)[0].content)
    }

    @Test
    fun a_reply_is_a_note_carrying_a_ref_and_the_parent_lists_it() {
        // A comment is an ordinary note with a ref, so the comments list is a
        // filter of the one catalog — there is no second store to disagree with.
        val catalog = OrgCatalog.Empty.copy(
            notes = listOf(
                note(1, body = "the parent"),
                note(2, body = "first reply", ref = 1).copy(created = 10),
                note(3, body = "second reply", ref = 1).copy(created = 20),
                note(4, body = "unrelated"),
            ),
        )

        // Newest first, the way a thread is read.
        assertEquals(listOf(3L, 2L), OrgModel.comments(catalog, 1).map { it.id })
        assertEquals(2, OrgModel.commentCount(catalog, 1))
        assertTrue(OrgModel.comments(catalog, 4).isEmpty())
        // The ref reaches the row the card draws, and an ordinary note has none —
        // which is the whole of what tells a comment from a note.
        val rows = OrgModel.notes(catalog, "", "", 0, -1)
        assertEquals(1L, rows.first { it.id == 2L }.ref)
        assertNull(rows.first { it.id == 1L }.ref)
    }

    @Test
    fun a_reply_preview_is_the_parents_first_non_empty_line() {
        val catalog = OrgCatalog.Empty.copy(
            notes = listOf(note(1, body = "\n  the first real line  \nsecond"), note(2, ref = 1)),
        )
        assertEquals("the first real line", OrgModel.parentPreview(catalog, 1))
    }

    @Test
    fun a_dangling_ref_resolves_to_no_preview_rather_than_to_a_broken_one() {
        // The parent was deleted: the comment is still the user's writing and
        // stays a comment, it just has nothing to preview. Never a crash, and
        // never a preview borrowed from whatever holds the id now.
        val catalog = OrgCatalog.Empty.copy(notes = listOf(note(2, body = "orphan reply", ref = 404)))
        assertNull(OrgModel.parentPreview(catalog, 404))
        // The comment is still a note in the list: a dangling ref is a paint
        // difference, not a row that disappears.
        assertEquals(2L, OrgModel.notes(catalog, "", "", 0, -1).first().id)
        assertEquals(404L, OrgModel.notes(catalog, "", "", 0, -1).first().ref)
    }

    @Test
    fun a_note_is_found_by_its_title_body_or_tags() {
        val catalog = OrgCatalog(
            listOf(
                note(1, title = "会议"),
                note(2, body = "买了牛奶"),
                note(3, tags = listOf("idea")),
                note(4),
            ),
            emptyList(),
            emptyList(),
        )
        assertEquals(listOf(1L), OrgModel.notes(catalog, "会议", "", 0, -1).map { it.id })
        assertEquals(listOf(2L), OrgModel.notes(catalog, "牛奶", "", 0, -1).map { it.id })
        assertEquals(listOf(3L), OrgModel.notes(catalog, "idea", "", 0, -1).map { it.id })
        // The tag filter is a separate question from the needle, and 全部笔记 is
        // the empty tag rather than a tag nobody has.
        assertEquals(listOf(3L), OrgModel.notes(catalog, "", "idea", 0, -1).map { it.id })
        assertEquals(4, OrgModel.notes(catalog, "", "", 0, -1).size)
    }

    @Test
    fun the_tag_column_counts_the_notes_and_puts_the_busiest_first() {
        val catalog = OrgCatalog(
            listOf(
                note(1, tags = listOf("work", "idea")),
                note(2, tags = listOf("work")),
                note(3, tags = listOf("zeta")),
            ),
            emptyList(),
            emptyList(),
        )
        assertEquals(
            listOf("work" to 2, "idea" to 1, "zeta" to 1),
            OrgModel.tagChips(catalog).map { it.name to it.count },
        )
    }

    // ─── a tag is a path ────────────────────────────────────────────────────

    @Test
    fun a_tag_filter_keeps_a_whole_subtree_and_stops_at_a_segment() {
        val catalog = OrgCatalog(
            listOf(
                note(1, tags = listOf("项目/工作")),
                note(2, tags = listOf("项目/生活")),
                note(3, tags = listOf("项目")),
                note(4, tags = listOf("项目2")),
                note(5, tags = listOf("别的")),
            ),
            emptyList(),
            emptyList(),
        )
        // `项目` is a subtree: the tag itself and everything under it. `项目2` is a
        // different tag that only *looks* nested, and a plain prefix test keeps it.
        assertEquals(listOf(1L, 2L, 3L), OrgModel.notes(catalog, "", "项目", 0, -1).map { it.id }.sorted())
        assertEquals(listOf(1L), OrgModel.notes(catalog, "", "项目/工作", 0, -1).map { it.id })
        assertEquals(listOf(4L), OrgModel.notes(catalog, "", "项目2", 0, -1).map { it.id })
        assertEquals(5, OrgModel.notes(catalog, "", "", 0, -1).size)
    }

    @Test
    fun the_tag_row_shows_one_level_and_counts_a_note_once_per_prefix() {
        val catalog = OrgCatalog(
            listOf(
                // Two tags under `项目` — still one note under `项目`.
                note(1, tags = listOf("项目/工作", "项目/生活")),
                note(2, tags = listOf("项目/工作")),
                note(3, tags = listOf("idea")),
            ),
            emptyList(),
            emptyList(),
        )
        assertEquals(
            listOf("项目" to 2, "idea" to 1),
            OrgModel.tagChips(catalog).map { it.name to it.count },
        )
        // One level down the chips are the *next* segment, still carrying the path,
        // and the count is again what the tap would leave on screen.
        assertEquals(
            listOf("项目/工作" to 2, "项目/生活" to 1),
            OrgModel.tagChips(catalog, "项目").map { it.name to it.count },
        )
    }

    @Test
    fun a_hierarchical_tag_reads_as_a_breadcrumb_and_walks_up_one_level() {
        assertEquals(listOf("项目", "工作"), OrgModel.tagSegments("项目 / 工作"))
        assertEquals("项目/工作", OrgModel.tagParentPath("项目/工作/xx"))
        assertNull(OrgModel.tagParentPath("项目"))
        assertEquals("项目 / 工作", OrgModel.tagBreadcrumb("项目/工作"))
        assertEquals("#项目 / 工作", OrgModel.tagLabel("项目/工作"))
    }

    @Test
    fun a_note_turned_into_a_task_gives_its_first_line_a_title() {
        // The note's own title wins when it has one — a desktop-made note.
        assertEquals("会议", OrgModel.taskTitle(note(1, title = "会议", body = "# 别的")))
        // Otherwise the first non-empty line, with the markdown that opens it taken
        // off: a title of "##" is not a title.
        assertEquals("买了牛奶", OrgModel.taskTitle(note(2, title = "", body = "\n  买了牛奶\n还有鸡蛋")))
        assertEquals("会议纪要", OrgModel.taskTitle(note(3, title = "", body = "# 会议纪要\n细节")))
        assertEquals("第一件事", OrgModel.taskTitle(note(4, title = "", body = "- 第一件事")))
        assertEquals("第二步", OrgModel.taskTitle(note(5, title = "", body = "2. 第二步")))
        assertEquals("", OrgModel.taskTitle(note(6, title = "", body = "   \n  ")))
    }

    // ─── the deadlines ──────────────────────────────────────────────────────

    @Test
    fun a_deadline_reads_relative_only_where_that_is_what_the_user_means() {
        assertEquals("今天", OrgModel.dueLabel("2026-09-25", dates))
        assertEquals("明天", OrgModel.dueLabel("2026-09-26", dates))
        // Same year: the year is noise. Another year: it is the whole point.
        assertEquals("09-30", OrgModel.dueLabel("2026-09-30", dates))
        assertEquals("2027-01-04", OrgModel.dueLabel("2027-01-04", dates))
        assertEquals("", OrgModel.dueLabel(null, dates))
    }

    @Test
    fun a_badge_is_a_state_and_not_part_of_the_label() {
        // An overdue task still says which day it was due…
        assertEquals("09-24", OrgModel.dueLabel("2026-09-24", dates))
        assertTrue(OrgModel.overdue("2026-09-24", dates))
        // …today is not overdue, and neither is the future.
        assertFalse(OrgModel.overdue("2026-09-25", dates))
        assertFalse(OrgModel.overdue("2026-09-26", dates))
        assertFalse(OrgModel.overdue(null, dates))
        // A finished task is never painted as overdue, however late it was: the
        // row and the badge would otherwise contradict each other.
        val catalog = OrgCatalog.Empty.copy(tasks = listOf(task(1, due = "2026-09-01", done = true)))
        val row = OrgModel.taskRow(catalog, catalog.tasks[0], dates, false)
        assertTrue(row.done)
        assertFalse(row.overdue)
    }

    // ─── the chips and the header ───────────────────────────────────────────

    @Test
    fun the_chip_row_lights_exactly_one_half_of_the_selector() {
        val catalog = OrgCatalog.Empty.copy(lists = oneList, tasks = listOf(task(1), task(2, list = 5)))
        // No stored list picked: the inbox's chip is the lit one, and it counts
        // what reads as the inbox's.
        val smart = OrgModel.listChips(catalog, ORG_VIEW_INBOX, -1)
        assertTrue(smart[0].smart)
        assertTrue(smart[0].selected)
        assertEquals(1, smart[0].count)
        assertEquals("收集箱", smart[0].name)
        assertFalse(smart[1].selected)
        assertEquals(1, smart[1].count)

        // A stored list picked: that chip is the lit one instead, and the smart
        // half goes quiet — otherwise two chips would read as the current one.
        val picked = OrgModel.listChips(catalog, ORG_VIEW_INBOX, 5)
        assertFalse(picked[0].selected)
        assertTrue(picked[1].selected)
    }

    @Test
    fun the_header_names_the_half_of_the_selector_that_is_showing() {
        val catalog = OrgCatalog.Empty.copy(lists = oneList, tasks = listOf(task(1), task(2, list = 5)))
        // The chip decides: the view's own word.
        assertEquals("收集箱", OrgModel.scopeName(catalog, ORG_VIEW_INBOX, -1))
        assertEquals("今天", OrgModel.scopeName(catalog, ORG_VIEW_TODAY, -1))
        // A stored list decides: its name, so one selector with two halves reads
        // back as whichever half is showing.
        assertEquals("工作", OrgModel.scopeName(catalog, ORG_VIEW_INBOX, 5))
    }

    @Test
    fun the_board_has_a_column_per_list_with_the_inbox_first() {
        val catalog = OrgCatalog.Empty.copy(
            lists = oneList,
            tasks = listOf(task(1), task(2, list = 5), task(3, list = 5, done = true)),
        )
        val columns = OrgModel.board(catalog, "", sort = 0, dates = dates)
        assertEquals(listOf(-1L, 5L), columns.map { it.id })
        assertEquals("收集箱", columns[0].name)
        assertEquals("工作", columns[1].name)
        // A board is a "what is left" view: the finished ones have a view of their
        // own, and they are not on a card.
        assertEquals(listOf(1L), columns[0].cards.map { it.id })
        assertEquals(listOf(2L), columns[1].cards.map { it.id })
        assertEquals(1, columns[1].count)
    }

    @Test
    fun progress_is_over_the_whole_area_and_not_over_the_filtered_view() {
        val catalog = OrgCatalog.Empty.copy(tasks = listOf(task(1), task(2, done = true), task(3)))
        // A progress line that moved when the user typed a search would be
        // answering a different question from the one it looks like it answers.
        assertEquals(1 to 3, OrgModel.progress(catalog))
    }

    @Test
    fun a_row_that_is_not_there_is_not_a_detail() {
        val catalog = OrgCatalog.Empty.copy(notes = listOf(note(1)), tasks = listOf(task(1)))
        assertNull(OrgModel.noteDetail(catalog, 404))
        assertNull(OrgModel.taskDetail(catalog, 404, dates))
        assertEquals(1L, OrgModel.noteDetail(catalog, 1)?.id)
        assertEquals(1L, OrgModel.taskDetail(catalog, 1, dates)?.id)
    }

    // ─── the vocabulary ─────────────────────────────────────────────────────

    @Test
    fun the_stored_spellings_map_to_the_picker_s_own_slots() {
        // The slots are the positions in the label lists, and those are what the
        // write side hands back — so a round trip through the bridge cannot drift.
        assertEquals(listOf(0, 1, 2, 3), listOf("none", "low", "medium", "high").map(OrgModel::prioritySlot))
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            listOf("none", "daily", "weekdays", "weekly", "monthly").map(OrgModel::repeatSlot),
        )
        assertEquals(4, OrgModel.priorityNames.size)
        assertEquals(5, OrgModel.repeatNames.size)
        // A level a future build writes folds to 无 / 不重复 rather than crashing a
        // picker: a task without a level is a task.
        assertEquals(0, OrgModel.prioritySlot("urgent"))
        assertEquals(0, OrgModel.repeatSlot("yearly"))
    }

    @Test
    fun the_five_views_and_their_names_stay_in_step() {
        assertEquals(listOf("收集箱", "今天", "近七天", "全部", "已完成"), OrgModel.smartNames)
        // The header's slot lookup and the chip row's order are the same list.
        assertEquals("已完成", OrgModel.smartNames[ORG_VIEW_DONE])
        assertEquals("全部", OrgModel.smartNames[ORG_VIEW_ALL])
    }

    private fun ids(rows: List<OrgModel.TaskRow>): List<Long> = rows.map { it.id }
}
