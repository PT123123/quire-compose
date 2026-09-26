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
import dev.quire.compose.ui.MarkdownText
import dev.quire.compose.ui.ORG_TAB_NOTES
import dev.quire.compose.ui.ORG_TAB_TASKS
import dev.quire.compose.ui.OrgModel
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
    // Which destination, which smart view, which list, which sort, the needle, the
    // two selections, and what the capture sheet is for. All of it is a fact about
    // *the window and the finger*, not about a document — which is why it lives
    // here and not in the library: the desktop keeps it in its `UIState` for the
    // same reason (ADR-0073's rule). Filtering and sorting are then a pure function
    // of this state and the catalog the last reply carried, so a chip press or a
    // keystroke in the search box redraws without a round trip to the backend.
    //
    // It lives on the ViewModel rather than in a `remember` so it survives a
    // rotation: landing back on 今天 with the needle still typed is what a user
    // expects, and a filter that resets itself on a turn of the tablet is a bug
    // nobody would call one. The **draft** is here for the same reason — a capture
    // sheet swiped away mid-sentence must not eat the sentence.

    /**
     * What the floating capture sheet is for. `Closed` is not "no sheet yet" so
     * much as "the sheet is not on screen", which is what makes one piece of state
     * decide the sheet's title, its placeholder, its line count and what ➤ commits.
     */
    sealed interface Compose {
        data object Closed : Compose
        data object NewNote : Compose
        data object NewTask : Compose
        /**
         * A reply to another note. A comment is an ordinary note carrying a
         * reference to the note it comments on, so this variant differs from
         * [NewNote] only in the ref it hands the bridge (ADR-0015).
         */
        data class Comment(val parent: Long) : Compose
    }

    /** 0 笔记（收件箱）· 1 任务. Two destinations, as the drawer lists them. */
    var orgTab by mutableStateOf(ORG_TAB_NOTES)
        private set

    /** Smart view slot: 0 收集箱 · 1 今天 · 2 近七天 · 3 全部 · 4 已完成. */
    var orgView by mutableStateOf(0)
        private set

    /** A stored list's id, or `-1` for "the chip decides". */
    var orgList by mutableStateOf(-1L)
        private set

    /** 0 列表 · 1 平铺（看板）. */
    var orgMode by mutableStateOf(0)
        private set

    /** 0 最新创建 · 1 最新更新 · 2 按内容. */
    var orgNoteSort by mutableStateOf(0)
        private set

    /** 0 默认排序 · 1 最近添加 · 2 反向 · 3 按优先级 · 4 按截止日期. */
    var orgTaskSort by mutableStateOf(0)
        private set

    /** The needle, and whether the bar's 🔍 has revealed the field that holds it. */
    var orgQuery by mutableStateOf("")
        private set

    var orgSearchOpen by mutableStateOf(false)
        private set

    /** Whether the list shows the finished rows the views hide. */
    var orgShowDone by mutableStateOf(false)
        private set

    /** The 笔记 tab's tag filter; `""` is 全部笔记. */
    var orgTag by mutableStateOf("")
        private set

    /** The open task, or `-1` for the list. */
    var orgTaskSel by mutableStateOf(-1L)
        private set

    /**
     * The open note, or `-1` for the list. A note opens on a **page of its own**
     * rather than in the capture sheet (ADR-0015), so this is the note half of
     * what `orgTaskSel` is to 任务.
     */
    var orgNoteSel by mutableStateOf(-1L)
        private set

    /** The capture sheet's state, and the text in it. */
    var orgCompose by mutableStateOf<Compose>(Compose.Closed)
        private set

    var orgDraft by mutableStateOf("")
        private set

    /** The note or task whose ⋯ menu is open. */
    var orgNoteMenu by mutableStateOf<Long?>(null)
        private set

    var orgTaskMenu by mutableStateOf<Long?>(null)
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

    /** Go to 收件箱, with nothing selected and nothing filtered. */
    fun openNotes() = openDestination(ORG_TAB_NOTES)

    /** Go to 任务, on the same terms. */
    fun openTasks() = openDestination(ORG_TAB_TASKS)

    private fun openDestination(tab: Int) {
        orgTab = tab
        orgList = -1
        orgTag = ""
        orgQuery = ""
        orgSearchOpen = false
        orgNoteMenu = null
        orgTaskMenu = null
        orgCompose = Compose.Closed
        orgTaskSel = -1
        orgNoteSel = -1
        // A selection does not cross destinations: the ids a page picked mean
        // nothing to the other one.
        orgSelecting = false
        orgSelection = emptySet()
    }

    fun orgPickView(view: Int) {
        orgView = view.coerceIn(0, 4)
        orgList = -1
    }

    fun orgPickList(list: Long) {
        orgList = list
    }

    fun orgPickMode(mode: Int) {
        orgMode = mode.coerceIn(0, 1)
    }

    fun orgPickNoteSort(sort: Int) {
        orgNoteSort = sort.coerceIn(0, 2)
    }

    fun orgPickTaskSort(sort: Int) {
        orgTaskSort = sort.coerceIn(0, 4)
    }

    fun orgSetQuery(query: String) {
        orgQuery = query
    }

    fun toggleOrgSearch() {
        orgSearchOpen = !orgSearchOpen
        // Closing the field clears the needle with it: a filter nobody can see is
        // the worst of both, and the reference app hides the field the same way.
        if (!orgSearchOpen) orgQuery = ""
    }

    fun closeOrgSearch() {
        orgSearchOpen = false
        orgQuery = ""
    }

    fun orgPickTag(tag: String) {
        orgTag = tag
    }

    /**
     * Step the tag filter up one level: `项目/工作` → `项目` → no filter. The
     * reference app's ↑ on its filter bar, which is also how the top level is
     * reached without a chip for it.
     */
    fun orgTagUp() {
        orgTag = OrgModel.tagParentPath(orgTag).orEmpty()
    }

    fun orgToggleShowDone() {
        orgShowDone = !orgShowDone
    }

    // ─── 多选 ────────────────────────────────────────────────────────────────
    //
    // A selection is a *mode*, the way the reference app models it: the toolbar
    // becomes the selection's own bar while it is on, and leaving it drops what
    // was picked. The ids live here rather than in the page so a rotation keeps
    // them, and they are always ids — never rows — so a row that a filter hides
    // is still selected when the filter comes off.

    /** Whether the page is picking rows rather than showing them. */
    var orgSelecting by mutableStateOf(false)
        private set

    /** The ids picked, in the page that is selecting. */
    var orgSelection by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** A long press on a row: start selecting, with that row already picked. */
    fun orgStartSelecting(id: Long) {
        orgSelecting = true
        orgSelection = setOf(id)
    }

    /** 多选 from the ⋯ menu: the mode, with nothing picked yet. */
    fun orgBeginSelecting() {
        orgSelecting = true
        orgSelection = emptySet()
    }

    fun orgStopSelecting() {
        orgSelecting = false
        orgSelection = emptySet()
    }

    fun orgToggleSelected(id: Long) {
        orgSelection = if (id in orgSelection) orgSelection - id else orgSelection + id
    }

    /** 全选 over what the page is showing — the filter decides, as it does for 复制全部. */
    fun orgSelectAll(ids: Collection<Long>) {
        orgSelection = ids.toSet()
    }

    /**
     * 删除 on a selection: one deferred batch, so the whole selection is one 撤销
     * away and nothing is sent until the window closes (ADR-0015).
     */
    fun orgDeleteSelected(isTask: Boolean) {
        val ids = orgSelection.toSet()
        if (ids.isEmpty()) return
        orgStopSelecting()
        deferDelete(ids, isTask)
    }

    /** 完成 on a selection: each row is ticked on its own, as the reference app's PUTs are. */
    fun orgCompleteSelected(done: Boolean) {
        val ids = orgSelection.toSet()
        orgStopSelecting()
        for (id in ids) act { bridge.orgTaskDone(id, done) }
    }

    /** `-1` closes the detail — the back gesture, and the back chevron. */
    fun orgSelectTask(id: Long) {
        orgTaskSel = id
    }

    fun orgSelectRow(id: Long) {
        orgTaskSel = id
    }

    fun orgOpenTaskMenu(id: Long) {
        orgTaskMenu = id
    }

    fun closeTaskMenu() {
        orgTaskMenu = null
    }

    fun orgOpenNoteMenu(id: Long) {
        orgNoteMenu = id
    }

    fun closeNoteMenu() {
        orgNoteMenu = null
    }

    // ─── the capture sheet ──────────────────────────────────────────────────

    /** The round ＋ on 收件箱: a sheet with an empty field. */
    fun openNoteComposer() {
        orgCompose = Compose.NewNote
        orgDraft = ""
    }

    /**
     * Tapping a card opens the note on a **page of its own** (ADR-0015), the way
     * the reference app edits a note. The capture sheet is for making one thing
     * quickly; a note being read and revised wants the whole screen.
     */
    fun orgSelectNote(id: Long) {
        orgNoteSel = id
        orgNoteMenu = null
    }

    /** The note page's 评论, and a comment row's ＋: the capture overlay, in reply mode. */
    fun openCommentComposer(parent: Long) {
        orgCompose = Compose.Comment(parent)
        orgDraft = ""
    }

    /**
     * The round ＋ on 任务. A tag filter is pre-filled into the field, which is the
     * reference app's own habit: a task added while looking at `#工作` starts with
     * `#工作` in it, so the row lands in the filter it was made in.
     */
    fun openTaskComposer() {
        orgCompose = Compose.NewTask
        orgDraft = if (orgTag.isNotEmpty()) "#$orgTag " else ""
    }

    fun orgCloseComposer() {
        orgCompose = Compose.Closed
    }

    fun orgSetDraft(text: String) {
        orgDraft = text
    }

    fun orgClearDraft() {
        orgDraft = ""
        orgCompose = Compose.Closed
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

    /**
     * ➤ on a new note. The tags are read out of the text — its `#tokens` — and
     * both travel in **one** command, so a note the user typed is one press of 撤销
     * away. The derivation is [MarkdownText]'s, which is where the toolbar's rules
     * live and where they are tested.
     */
    fun orgAddNote(body: String) = act { bridge.orgAddNote(body, MarkdownText.tagString(body)) }

    /**
     * ➤ in reply mode: a note whose `ref` is the note it answers. One command, so
     * a comment and its ref are one press of 撤销 away like any other row.
     */
    fun orgComment(parent: Long, body: String) =
        act { bridge.orgAddComment(parent, body, MarkdownText.tagString(body)) }

    /** ➤ on an open note: the same single command, with the row's own id. */
    fun orgNoteContent(note: Long, body: String) =
        act { bridge.orgNoteContent(note, body, MarkdownText.tagString(body)) }

    /**
     * ➤ on a new task. 今天 is a *date* view, so a task typed into it is due today
     * — the only reading of "today" that survives the next rebuild — and the
     * deadline is decided here, by the view the field was opened from.
     */
    fun orgComposeTask(text: String) = act {
        val line = text.replace(Regex("\\s+"), " ").trim()
        bridge.orgQuickAdd(
            list = orgList,
            title = line,
            tags = MarkdownText.tagString(line),
            due = if (orgView == ORG_VIEW_TODAY) OrgModel.Dates.now().today else null,
        )
    }

    /**
     * A board column's ＋: add straight into *that* column's list rather than into
     * whatever the chips are on, because the board shows every list at once.
     */
    fun orgQuickAddTo(list: Long, title: String) = act {
        bridge.orgQuickAdd(list, title, MarkdownText.tagString(title), null)
    }

    fun orgNoteTitle(note: Long, title: String) = act { bridge.orgNoteTitle(note, title) }

    fun orgNoteBody(note: Long, body: String) = act { bridge.orgNoteBody(note, body) }

    fun orgToggleNotePinned(note: Long, pinned: Boolean) = act { bridge.orgNotePinned(note, pinned) }

    fun orgNoteTags(note: Long, tags: String) = act { bridge.orgNoteTags(note, tags) }

    /**
     * Delete a note — **deferred** (ADR-0015). The row is hidden the moment this
     * is called and the real command only goes out when the 撤销 bar's three
     * seconds run out, so 撤销 has nothing to reverse: it just drops the batch.
     * Nothing reaches the organizer's stack until it commits, which is what keeps
     * an undone delete from leaving a step behind.
     */
    fun orgDeleteNote(note: Long) {
        orgNoteMenu = null
        if (orgNoteSel == note) orgNoteSel = -1
        deferDelete(setOf(note), isTask = false)
    }

    /**
     * 转为待办: the note becomes a task and the note goes — the reference app's own
     * migration, offered from the note's ⋯ and from its page. The title is the
     * note's first line with its markdown stripped ([OrgModel.taskTitle]), the body
     * travels whole as the task's 备注, and the tags come along.
     *
     * It lands in 收集箱, as the reference app's does when no list is in hand. The
     * note's removal is the same **deferred** delete every other delete uses — the
     * row vanishes at once and the bar says what happened, so 撤销 is the way back;
     * nothing is sent until the window closes, which is what keeps a half-done
     * conversion out of the library.
     */
    fun orgConvertToTask(note: Long) {
        val row = view?.org?.notes?.firstOrNull { it.id == note } ?: return
        orgNoteMenu = null
        if (orgNoteSel == note) orgNoteSel = -1
        val title = OrgModel.taskTitle(row)
        val body = row.body
        val known = view?.org?.tasks?.mapTo(HashSet()) { it.id } ?: emptySet()
        viewModelScope.launch {
            val reply = withContext(bridgeDispatcher) {
                runCatching { bridge.orgQuickAdd(0L, title, row.tags.joinToString(", "), null) }
            }
            reply
                .onSuccess { result ->
                    apply(result)
                    val fresh = (result as? Reply.Updated)?.view?.org?.tasks
                        ?.firstOrNull { it.id !in known }
                    // The body is what the note *was*: it is carried over rather than
                    // repeated as the title.
                    if (fresh != null && body.isNotEmpty() && body != title) {
                        act { bridge.orgTaskNotes(fresh.id, body) }
                    }
                }
                .onFailure { error = it.message ?: it.toString() }
        }
        deferDelete(setOf(note), isTask = false, message = "已转为待办")
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

    /** The task half of the same deferred delete; see [orgDeleteNote]. */
    fun orgDeleteTask(task: Long) {
        orgTaskSel = -1
        orgTaskMenu = null
        deferDelete(setOf(task), isTask = true)
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

    // ─── LAN sync ───────────────────────────────────────────────────────────
    //
    // The page's verbs, each one a whole-view reply. `openSync` doubles as the
    // switch that puts this device on the LAN — the session starts the engine the
    // first time it is asked — and the ordinary one-second tick is what keeps a
    // cycle moving, so nothing here needs a timer.

    fun openSync() = act { bridge.syncState() }

    fun syncSetAuto(on: Boolean) = act { bridge.syncSetAuto(on) }

    fun syncSetInterval(seconds: Long) = act { bridge.syncSetInterval(seconds) }

    fun syncSetName(name: String) = act { bridge.syncSetName(name) }

    fun syncAddPeer(ip: String, port: Int = 0) = act { bridge.syncAddPeer(ip, port) }

    fun syncPair(id: String) = act { bridge.syncPair(id) }

    fun syncNow(id: String) = act { bridge.syncNow(id) }

    fun syncForget(id: String) = act { bridge.syncForget(id) }

    // ─── the deferred delete ────────────────────────────────────────────────
    //
    // Delete is optimistic: the row is hidden at once and the real command waits
    // out a 撤销 window (ADR-0015). It is owned *here* rather than by the screen
    // that asked, which is what lets the bar survive leaving the page — the
    // reference app keeps its pending delete the same way. `token` is what makes
    // the three-second timer safe: an undo, or a second delete that replaced the
    // first, leaves the old timer firing against a token that no longer matches.

    /** A delete that has been hidden but not yet sent. */
    data class PendingDelete(
        val token: Long,
        val ids: Set<Long>,
        val isTask: Boolean,
        val message: String,
    )

    var pendingDelete by mutableStateOf<PendingDelete?>(null)
        private set

    private var deleteToken = 0L

    /**
     * Hide the rows and start the window. A second delete commits the first.
     *
     * `message` is what the bar says; 转为待办 passes its own, so the bar that
     * appears when a note becomes a task reads as what happened rather than as a
     * deletion.
     */
    private fun deferDelete(ids: Set<Long>, isTask: Boolean, message: String? = null) {
        pendingDelete?.let { commitPendingDelete(it.token) }
        val token = ++deleteToken
        pendingDelete = PendingDelete(
            token = token,
            ids = ids,
            isTask = isTask,
            message = message ?: if (isTask) "任务已删除" else "笔记已删除",
        )
        viewModelScope.launch {
            delay(DELETE_UNDO_MS)
            commitPendingDelete(token)
        }
    }

    /** The bar's 撤销: the batch is dropped and nothing was ever sent. */
    fun undoPendingDelete() {
        pendingDelete = null
    }

    /** The window closed (or a newer delete pushed this one out): send it. */
    private fun commitPendingDelete(token: Long) {
        val pending = pendingDelete ?: return
        if (pending.token != token) return
        pendingDelete = null
        for (id in pending.ids) {
            if (pending.isTask) act { bridge.orgDeleteTask(id) } else act { bridge.orgDeleteNote(id) }
        }
    }

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

        /** How long a deleted row stays recoverable before the real command goes out. */
        const val DELETE_UNDO_MS = 3_000L
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
