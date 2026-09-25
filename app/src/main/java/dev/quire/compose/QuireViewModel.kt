package dev.quire.compose

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.quire.compose.bridge.Bridge
import dev.quire.compose.bridge.Native
import dev.quire.compose.bridge.OrgCatalog
import dev.quire.compose.bridge.Reply
import dev.quire.compose.bridge.View
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shell's one state holder: the last view the bridge sent, and every
 * operation the UI can ask for.
 *
 * **One call at a time.** The native session is a single workspace behind a
 * mutex, so a second caller would not corrupt it — it would just observe
 * replies in an order it did not choose, and the UI would settle on whichever
 * landed last. Every call therefore goes through one single-threaded dispatcher:
 * the ordering the UI sees is the ordering it asked for.
 *
 * **One writer.** The Rust side owns the debounced queue; this class owns the
 * clock that drains it (a tick a second while the app is in front, a flush when
 * it goes away, a final flush on close). Android gives an app no reliable
 * "about to die" callback, so the honest design is "write early and often".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuireViewModel(application: Application) : AndroidViewModel(application) {

    private val bridge = Bridge(Native.create())
    private val bridgeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The last full view, or null until the library has opened. */
    var view by mutableStateOf<View?>(null)
        private set

    /** The last failure, shown as a bar; cleared by the next successful call. */
    var error by mutableStateOf<String?>(null)
        private set

    /** A one-shot startup notice (a recovered backup), dismissed by the user. */
    var notice by mutableStateOf<String?>(null)
        private set
    private var noticeTaken = false

    /**
     * The block a reply just created, waiting for the caret.
     *
     * The new row's id is not knowable before its reply — the core allocates it —
     * so the UI cannot ask for it, it has to be told. The id is found by
     * difference (what is in the new page that was not in the old one), which is
     * exact rather than the usual "the highest id, probably" guess.
     */
    var pendingFocus by mutableStateOf<Long?>(null)
        private set

    fun consumeFocus() {
        pendingFocus = null
    }

    private var foreground = false
    private var closed = false

    init {
        viewModelScope.launch {
            // `internalDataPath` on the Rust side was `<files>/Quire`, the same
            // folder name a desktop install makes, so a library can be carried
            // between the two shells without the store noticing which it is on.
            val dataDir = File(getApplication<Application>().filesDir, "Quire").absolutePath
            apply(withContext(bridgeDispatcher) { bridge.open(dataDir) })
        }
        // The writer's clock. Ticking while backgrounded would keep a wakeup
        // every second for a queue that is already empty, so the loop runs but
        // the work does not.
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                if (foreground) withContext(bridgeDispatcher) { bridge.tick() }
            }
        }
    }

    /** The app came to the front (tick) or went away (write now). */
    fun setForeground(front: Boolean) {
        if (front == foreground) return
        foreground = front
        if (!front) flush()
    }

    fun dismissNotice() {
        notice = null
    }

    fun dismissError() {
        error = null
    }

    // ─── pages ──────────────────────────────────────────────────────────────

    fun openPage(page: Long) = act { bridge.openPage(page) }

    fun createPage(parent: Long?, title: String = "") = act { bridge.createPage(parent, title) }

    fun renamePage(page: Long, title: String) = act { bridge.renamePage(page, title) }

    fun deletePage(page: Long) = act { bridge.deletePage(page) }

    fun toggleFavorite(page: Long) = act { bridge.toggleFavorite(page) }

    fun toggleExpanded(page: Long) = act { bridge.toggleExpanded(page) }

    fun setPageLocked(page: Long, locked: Boolean) = act { bridge.setPageLocked(page, locked) }

    fun setTheme(theme: String) = act { bridge.setTheme(theme) }

    // ─── blocks ─────────────────────────────────────────────────────────────

    /** A keystroke. Deliberately `Reply.Done`: nothing comes back to redraw. */
    fun setBlockText(block: Long, text: String) = act { bridge.setBlockText(block, text) }

    fun insertBlockAfter(block: Long, kind: String = KIND_PARAGRAPH, text: String = "") =
        actCreatingBlock { bridge.insertBlockAfter(block, kind, text) }

    fun appendBlock(kind: String = KIND_PARAGRAPH, text: String = "") =
        actCreatingBlock { bridge.appendBlock(kind, text) }

    fun deleteBlock(block: Long) = act { bridge.deleteBlock(block) }

    fun setBlockKind(block: Long, kind: String) = act { bridge.setBlockKind(block, kind) }

    fun toggleChecked(block: Long) = act { bridge.toggleChecked(block) }

    fun toggleFold(block: Long) = act { bridge.toggleFold(block) }

    fun moveBlock(block: Long, delta: Int) = act { bridge.moveBlock(block, delta) }

    fun indentList(block: Long) = act { bridge.indentList(block) }

    fun outdentList(block: Long) = act { bridge.outdentList(block) }

    fun undo() = act { bridge.undo() }

    fun redo() = act { bridge.redo() }

    // ─── SPEC §四十一: the organizer's own view state ───────────────────────
    //
    // Which tab, which smart view, which list, which sort, the needle and the two
    // selections. All of it is a fact about *the window and the finger*, not about
    // a document — which is why it lives here and not in the library: the desktop
    // keeps it in its `UIState` for the same reason (ADR-0073's rule). Filtering
    // and sorting are then a pure function of this state and the catalog the last
    // reply carried, so a chip press or a keystroke in the search box redraws
    // without a round trip to the backend.
    //
    // It lives on the ViewModel rather than in a `remember` so it survives a
    // rotation: landing back on 今天 with the needle still typed is what a user
    // expects, and a filter that resets itself on a turn of the tablet is a bug
    // nobody would call one.

    /** 0 笔记 · 1 任务. The Slint shell's slots, kept so both shells say 笔记 first. */
    var orgTab by mutableStateOf(0)
        private set

    /** Smart view slot: 0 收集箱 · 1 今天 · 2 近七天 · 3 全部 · 4 已完成. */
    var orgView by mutableStateOf(0)
        private set

    /** A stored list's id, or `-1` for "the chip decides". */
    var orgList by mutableStateOf(-1L)
        private set

    /** 0 添加顺序 · 1 优先级 · 2 截止日期. */
    var orgSort by mutableStateOf(0)
        private set

    /** 0 列表 · 1 平铺（看板）. */
    var orgMode by mutableStateOf(0)
        private set

    /** One needle per tab, and only the tab that is showing reads it. */
    var orgQuery by mutableStateOf("")
        private set

    /** Whether the list shows the finished rows the views hide. */
    var orgShowDone by mutableStateOf(false)
        private set

    /** The 笔记 tab's tag filter; `""` is 全部笔记. */
    var orgTag by mutableStateOf("")
        private set

    /** The open note, or `-1` for the list. */
    var orgNoteSel by mutableStateOf(-1L)
        private set

    /** The open task, or `-1` for the list. */
    var orgTaskSel by mutableStateOf(-1L)
        private set

    /**
     * A row a create just made, waiting for the caret.
     *
     * The new row's id is not knowable before its reply — the Rust side allocates
     * it — so the UI cannot ask for it, it has to be told. Found by difference
     * against the catalog the previous reply carried, which is exact rather than
     * the usual "the highest id, probably" guess; the same trick
     * [pendingFocus] uses for blocks.
     */
    var orgFocus by mutableStateOf<Long?>(null)
        private set

    fun consumeOrgFocus() {
        orgFocus = null
    }

    /** Go to the area, on one tab, with nothing selected. */
    fun openOrganizer(tab: Int) {
        orgTab = tab.coerceIn(0, 1)
        orgList = -1
        orgTag = ""
        orgQuery = ""
        orgNoteSel = -1
        orgTaskSel = -1
    }

    fun orgPickView(view: Int) {
        orgView = view.coerceIn(0, 4)
        orgList = -1
    }

    fun orgPickList(list: Long) {
        orgList = list
    }

    fun orgPickSort(sort: Int) {
        orgSort = sort.coerceIn(0, 2)
    }

    fun orgPickMode(mode: Int) {
        orgMode = mode.coerceIn(0, 1)
    }

    fun orgSetQuery(query: String) {
        orgQuery = query
    }

    fun orgPickTag(tag: String) {
        orgTag = tag
    }

    fun orgToggleShowDone() {
        orgShowDone = !orgShowDone
    }

    /** `-1` closes the detail — the back gesture, and the back chevron. */
    fun orgSelectNote(id: Long) {
        orgNoteSel = id
    }

    fun orgSelectTask(id: Long) {
        orgTaskSel = id
    }

    fun orgSelectRow(id: Long) {
        if (orgTab == 0) orgNoteSel = id else orgTaskSel = id
    }

    // ─── the organizer's writes ─────────────────────────────────────────────

    /**
     * A create, plus "open what it made": the ＋ makes a row nobody has to name
     * first, so the row's own form had better be the next thing on screen. The
     * new id is found by difference, exactly as a new block's is.
     */
    private fun orgCreate(
        idsOf: (OrgCatalog) -> List<Long>,
        action: () -> Reply,
        select: (Long) -> Unit,
    ) {
        val known = view?.org?.let(idsOf)?.toHashSet() ?: emptySet()
        viewModelScope.launch {
            val reply = withContext(bridgeDispatcher) { runCatching(action) }
            reply
                .onSuccess { result ->
                    apply(result)
                    if (result is Reply.Updated) {
                        val fresh = idsOf(result.view.org).firstOrNull { it !in known }
                        if (fresh != null) {
                            select(fresh)
                            orgFocus = fresh
                        }
                    }
                }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    fun orgCreateNote() = orgCreate(
        idsOf = { org -> org.notes.map { it.id } },
        action = { bridge.orgCreateNote() },
    ) { orgNoteSel = it }

    /** A new task, in the list the chips are on — the inbox when none is. */
    fun orgCreateTask() = orgCreate(
        idsOf = { org -> org.tasks.map { it.id } },
        action = { bridge.orgCreateTask(orgList) },
    ) { orgTaskSel = it }

    /**
     * The quick-add line. 今天 is a *date* view, so a task typed into it is due
     * today — the only reading of "today" that survives the next rebuild — and
     * the deadline is decided here, by the view the line was typed into.
     */
    fun orgQuickAdd(title: String, today: String) =
        act { bridge.orgQuickAdd(orgList, title, if (orgView == ORG_VIEW_TODAY) today else null) }

    /**
     * A board column's ＋: add straight into *that* column's list rather than into
     * whatever the chips are on, because the board shows every list at once.
     */
    fun orgQuickAddTo(list: Long, title: String) = act { bridge.orgQuickAdd(list, title) }

    fun orgNoteTitle(note: Long, title: String) = act { bridge.orgNoteTitle(note, title) }

    fun orgNoteBody(note: Long, body: String) = act { bridge.orgNoteBody(note, body) }

    fun orgToggleNotePinned(note: Long, pinned: Boolean) = act { bridge.orgNotePinned(note, pinned) }

    fun orgNoteTags(note: Long, tags: String) = act { bridge.orgNoteTags(note, tags) }

    fun orgDeleteNote(note: Long) {
        orgNoteSel = -1
        act { bridge.orgDeleteNote(note) }
    }

    fun orgTaskTitle(task: Long, title: String) = act { bridge.orgTaskTitle(task, title) }

    fun orgTaskNotes(task: Long, notes: String) = act { bridge.orgTaskNotes(task, notes) }

    fun orgToggleTaskDone(task: Long, done: Boolean) = act { bridge.orgTaskDone(task, done) }

    fun orgTaskPriority(task: Long, slot: Int) = act { bridge.orgTaskPriority(task, slot) }

    /** ISO, or `""` to clear. A value the core cannot read is refused there. */
    fun orgTaskDue(task: Long, due: String) = act { bridge.orgTaskDue(task, due) }

    fun orgTaskRepeat(task: Long, slot: Int) = act { bridge.orgTaskRepeat(task, slot) }

    fun orgTaskTags(task: Long, tags: String) = act { bridge.orgTaskTags(task, tags) }

    /** The row menu's 移到, and the board's cross-column drop. */
    fun orgMoveTask(task: Long, list: Long) = act { bridge.orgTaskList(task, list) }

    fun orgDeleteTask(task: Long) {
        orgTaskSel = -1
        act { bridge.orgDeleteTask(task) }
    }

    fun orgSubtaskAdd(task: Long) = act { bridge.orgSubtaskAdd(task) }

    fun orgSubtaskTitle(task: Long, subtask: Long, title: String) =
        act { bridge.orgSubtaskTitle(task, subtask, title) }

    fun orgSubtaskDone(task: Long, subtask: Long, done: Boolean) =
        act { bridge.orgSubtaskDone(task, subtask, done) }

    fun orgSubtaskDelete(task: Long, subtask: Long) =
        act { bridge.orgSubtaskDelete(task, subtask) }

    /** A new list, and the chip it makes becomes the one that is lit. */
    fun orgCreateList(name: String = "") = orgCreate(
        idsOf = { org -> org.lists.map { it.id } },
        action = { bridge.orgCreateList(name) },
    ) { orgList = it }

    fun orgListName(list: Long, name: String) = act { bridge.orgListName(list, name) }

    fun orgListColor(list: Long, slot: Int) = act { bridge.orgListColor(list, slot) }

    /**
     * Delete a list. Its tasks are not lost — the command files them in the inbox
     * in the same undoable step, which is why the confirm dialog says so.
     */
    fun orgDeleteList(list: Long) {
        if (orgList == list) orgList = -1
        act { bridge.orgDeleteList(list) }
    }

    /** The area's own stack. A step here can never reach a page's edits. */
    fun orgUndo() = act { bridge.orgUndo() }

    fun orgRedo() = act { bridge.orgRedo() }

    // ─── the plumbing ───────────────────────────────────────────────────────

    /**
     * Run one bridge call off the main thread, then land its reply on it.
     *
     * The failure path keeps the last good view: a rejected keystroke on a page
     * that just became read-only must not blank the document behind an error.
     */
    private fun act(action: () -> Reply) {
        viewModelScope.launch {
            val reply = withContext(bridgeDispatcher) { runCatching(action) }
            reply
                .onSuccess(::apply)
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    /** `act`, plus: put the caret in the block this reply created. */
    private fun actCreatingBlock(action: () -> Reply) {
        val known = view?.blocks?.mapTo(HashSet()) { it.id } ?: emptySet()
        viewModelScope.launch {
            val reply = withContext(bridgeDispatcher) { runCatching(action) }
            reply
                .onSuccess { result ->
                    apply(result)
                    if (result is Reply.Updated) {
                        result.view.blocks.firstOrNull { it.id !in known }?.let { pendingFocus = it.id }
                    }
                }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    private fun apply(reply: Reply) {
        when (reply) {
            is Reply.Updated -> {
                view = reply.view
                error = null
                if (!noticeTaken && reply.view.notice != null) {
                    notice = reply.view.notice
                    noticeTaken = true
                }
            }
            Reply.Done -> error = null
            is Reply.Failed -> error = reply.message
        }
    }

    private fun flush() {
        viewModelScope.launch { withContext(bridgeDispatcher) { bridge.flush() } }
    }

    /**
     * Write, then free. Blocking on purpose: the handle must not be released
     * while a call is still in flight, and the queue at this moment is the last
     * edit of the session — the one thing losing is unforgivable. It costs one
     * SQLite write on the way out of the app.
     */
    override fun onCleared() {
        if (!closed) {
            closed = true
            runBlocking(bridgeDispatcher) {
                runCatching { bridge.flush() }
                bridge.close()
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}

/** The short stable string `quire-core` stores a paragraph as. */
const val KIND_PARAGRAPH = "paragraph"

/**
 * SPEC §四十一's smart views, in the order their chips are drawn. Slots and not
 * names: the same five numbers the Slint shell's `UIState.org-view` carries, so
 * the two Android shells ask the same question with the same word.
 */
const val ORG_VIEW_INBOX = 0
const val ORG_VIEW_TODAY = 1
const val ORG_VIEW_WEEK = 2
const val ORG_VIEW_ALL = 3
const val ORG_VIEW_DONE = 4

/** The inbox's sentinel list id: it has no row, so the UI spells it once. */
const val ORG_INBOX = -1L
