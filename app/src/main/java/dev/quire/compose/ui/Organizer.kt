package dev.quire.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.OrgCatalog
import dev.quire.compose.bridge.SyncState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * SPEC §四十一's second and third destinations: **收件箱** (notes) and **任务**.
 *
 * Two pages, not two tabs on one page. The reference app navigates to them
 * separately from its drawer and each carries its own toolbar — 收件箱 has 搜索 /
 * 排序 / 多选, 任务 has 搜索 plus 新建清单 and the sort menu — so this shell gives
 * each its own bar and its own screen, and the drawer's two rows are the only way
 * between them (ADR-0013).
 *
 * Three shapes are the reference app's and are worth naming, because they are
 * what this page was rewired for:
 *
 * * **A note is a card.** One blob of text (up to eight lines), its tags on an
 *   accent line, its age bottom-right, and a ⋯ in the header — not a title, an
 *   excerpt and a metadata line.
 * * **A task is a glass row.** Title, then a meta line carrying the list's dot,
 *   the priority flag, the deadline badge, the checklist count, and the tags
 *   right-aligned.
 * * **Capture floats.** Nothing is typed into the list itself: a round ＋ at the
 *   bottom right opens a bottom sheet that carries a multi-line field, a markdown
 *   toolbar, and a ➤ send — and no cancel button, because a swipe down is one.
 *
 * The projection rules are in [OrgModel] and the toolbar's text operations in
 * [MarkdownText]; this file is layout and gesture only.
 */
private const val ORG_DRAFT_MS = 300L

/**
 * 指令's editable templates, the desktop's own (`NOTE_COMMAND_EXAMPLE` /
 * `TASK_COMMAND_EXAMPLE`) — kept verbatim so one prompt works against either
 * shell and against the reference server the JSON came from. 复制示例 puts one on
 * the clipboard, so the batch's shape is discoverable without a manual.
 */
private const val NOTE_COMMAND_EXAMPLE = """{
  "operations": [
    {"action": "create", "title": "新笔记", "content": "正文 #项目/工作", "tags": ["项目/工作"]},
    {"action": "update", "uuid": "在此填笔记ID", "title": "改后标题", "content": "改后的正文", "tags": ["项目"]},
    {"action": "add_tags", "uuid": "在此填笔记ID", "tags": ["重要", "待办"]},
    {"action": "remove_tags", "uuid": "在此填笔记ID", "tags": ["待办"]},
    {"action": "set_tags", "uuid": "在此填笔记ID", "tags": ["项目/工作", "重要"]},
    {"action": "comment", "uuid": "在此填笔记ID", "content": "给这条笔记加一条评论"},
    {"action": "delete", "uuid": "在此填笔记ID"}
  ]
}"""

private const val TASK_COMMAND_EXAMPLE = """{
  "operations": [
    {"action": "create", "title": "新任务", "content": "备注", "tags": ["项目/工作"], "priority": 2, "due_date": "2026-10-01T00:00:00Z", "list_id": 0},
    {"action": "update", "uuid": "在此填任务ID", "title": "改后标题", "content": "改后备注"},
    {"action": "add_tags", "uuid": "在此填任务ID", "tags": ["重要"]},
    {"action": "remove_tags", "uuid": "在此填任务ID", "tags": ["重要"]},
    {"action": "set_tags", "uuid": "在此填任务ID", "tags": ["项目/工作", "重要"]},
    {"action": "set_completed", "uuid": "在此填任务ID", "completed": true},
    {"action": "move", "uuid": "在此填任务ID", "list_name": "工作"},
    {"action": "set_priority", "uuid": "在此填任务ID", "priority": 3},
    {"action": "set_due", "uuid": "在此填任务ID", "due_date": "2026-10-01T00:00:00Z"},
    {"action": "set_due", "uuid": "在此填任务ID", "clear_due": true},
    {"action": "add_subtask", "uuid": "在此填任务ID", "title": "子任务 1"},
    {"action": "set_subtask", "uuid": "在此填任务ID", "subtask_id": 1, "completed": true},
    {"action": "remove_subtask", "uuid": "在此填任务ID", "subtask_id": 1},
    {"action": "comment", "uuid": "在此填任务ID", "content": "追加到任务备注的评论"},
    {"action": "delete", "uuid": "在此填任务ID"}
  ]
}"""

/** The palette's one green Material's scheme does not carry: 低 priority. */
private val OrgSuccess = Color(0xFF3FB950)

/** The area's destinations, in the order the drawer lists them. */
const val ORG_TAB_NOTES = 0
const val ORG_TAB_TASKS = 1

/** Whether the tasks page is showing one row's own form rather than the list. */
fun organizerInDetail(vm: QuireViewModel): Boolean =
    vm.orgTab == ORG_TAB_TASKS && vm.orgTaskSel >= 0

// ─── the two bars ───────────────────────────────────────────────────────────

/**
 * 收件箱's toolbar: the drawer, the title, and the three things the reference app
 * puts there — a search toggle, the sort menu, and the overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesBar(vm: QuireViewModel, onOpenDrawer: () -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var commandsOpen by remember { mutableStateOf(false) }
    val catalog = vm.view?.org ?: OrgCatalog.Empty
    if (vm.orgSelecting) {
        // 全选 covers what the page is *showing*: the filter is what the user is
        // looking at, and a 全选 that reached past it would be a lie about the rows
        // it lit. A row inside its 撤销 window is not on screen, so it is not in it.
        val clipboard = LocalClipboardManager.current
        val shown = remember(catalog, vm.orgQuery, vm.orgTag, vm.orgExcluded, vm.orgNoteSort, vm.pendingDelete) {
            val hidden = vm.pendingDelete?.takeIf { !it.isTask }?.ids ?: emptySet()
            OrgModel.notes(catalog, vm.orgQuery, vm.orgTag, vm.orgNoteSort, -1, vm.orgExcluded)
                .filter { it.id !in hidden }
                .map { it.id }
        }
        OrgSelectionBar(
            count = vm.orgSelection.size,
            allChosen = shown.isNotEmpty() && vm.orgSelection.containsAll(shown),
            onSelectAll = { vm.orgSelectAll(shown) },
            onClose = vm::orgStopSelecting,
        ) {
            OrgSelectionVerb("复制") {
                // The picked notes, in the order the page draws them, each with its
                // 唯一 ID on a line of its own — the text an AI reads and answers
                // with 指令, naming the rows by the id a sync cannot renumber.
                val ids = OrgModel.notes(catalog, vm.orgQuery, vm.orgTag, vm.orgNoteSort, -1, vm.orgExcluded)
                    .filter { it.id in vm.orgSelection }
                    .map { it.id }
                clipboard.setText(AnnotatedString(OrgModel.noteCopyText(catalog, ids)))
            }
            OrgSelectionVerb("删除", danger = true) { vm.orgDeleteSelected(isTask = false) }
        }
        return
    }
    OrgBar(
        // The bin is the same page turned over, so the title follows the flag
        // rather than the destination: "收件箱" over a list of deleted notes would
        // be the window lying about itself.
        title = if (vm.orgBin) "回收站" else "收件箱",
        canUndo = vm.view?.orgCanUndo == true,
        canRedo = vm.view?.orgCanRedo == true,
        onOpenDrawer = onOpenDrawer,
        onUndo = vm::orgUndo,
        onRedo = vm::orgRedo,
        onSearch = vm::toggleOrgSearch,
        onSort = { sortOpen = true },
        onMenu = { menuOpen = true },
        extra = { OrgRefreshAction(vm) },
    )
    if (sortOpen) {
        OrgSortSheet(
            names = OrgModel.noteSortNames,
            selected = vm.orgNoteSort,
            onPick = { vm.orgPickNoteSort(it); sortOpen = false },
            onDismiss = { sortOpen = false },
        )
    }
    if (menuOpen) {
        OrgNotesMenuSheet(
            vm = vm,
            onDismiss = { menuOpen = false },
            onCommands = { menuOpen = false; commandsOpen = true },
        )
    }
    if (commandsOpen) {
        OrgCommandDialog(isTask = false, vm = vm, onDismiss = { commandsOpen = false })
    }
}

/** 任务's toolbar: the same three, and everything else behind the overflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksBar(vm: QuireViewModel, onOpenDrawer: () -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var commandsOpen by remember { mutableStateOf(false) }
    val catalog = vm.view?.org ?: OrgCatalog.Empty
    if (vm.orgSelecting) {
        val dates = remember(catalog) { OrgModel.Dates.now() }
        val clipboard = LocalClipboardManager.current
        val shown = remember(
            catalog, vm.orgView, vm.orgList, vm.orgQuery, vm.orgTaskSort, vm.orgShowDone,
            vm.orgTag, vm.orgExcluded, vm.pendingDelete, dates,
        ) {
            val hidden = vm.pendingDelete?.takeIf { it.isTask }?.ids ?: emptySet()
            OrgModel.tasks(
                catalog = catalog,
                view = vm.orgView,
                list = vm.orgList,
                query = vm.orgQuery,
                sort = vm.orgTaskSort,
                selected = -1,
                showDone = vm.orgShowDone,
                dates = dates,
                tag = vm.orgTag,
                exclude = vm.orgExcluded,
            ).filter { it.id !in hidden }.map { it.id }
        }
        OrgSelectionBar(
            count = vm.orgSelection.size,
            allChosen = shown.isNotEmpty() && vm.orgSelection.containsAll(shown),
            onSelectAll = { vm.orgSelectAll(shown) },
            onClose = vm::orgStopSelecting,
        ) {
            OrgSelectionVerb("复制") {
                // The tasks' half of 复制: a title, its 备注, then the 唯一 ID — the
                // same shape the notes' half sends, and the desktop's own.
                val ids = OrgModel.tasks(
                    catalog = catalog,
                    view = vm.orgView,
                    list = vm.orgList,
                    query = vm.orgQuery,
                    sort = vm.orgTaskSort,
                    selected = -1,
                    showDone = vm.orgShowDone,
                    dates = dates,
                    tag = vm.orgTag,
                    exclude = vm.orgExcluded,
                ).filter { it.id in vm.orgSelection }.map { it.id }
                clipboard.setText(AnnotatedString(OrgModel.taskCopyText(catalog, ids)))
            }
            OrgSelectionVerb("完成") { vm.orgCompleteSelected(true) }
            OrgSelectionVerb("删除", danger = true) { vm.orgDeleteSelected(isTask = true) }
        }
        return
    }
    OrgBar(
        // See [NotesBar]: the bin is the page turned over, and the title says so.
        title = if (vm.orgBin) "回收站" else "任务",
        canUndo = vm.view?.orgCanUndo == true,
        canRedo = vm.view?.orgCanRedo == true,
        onOpenDrawer = onOpenDrawer,
        onUndo = vm::orgUndo,
        onRedo = vm::orgRedo,
        onSearch = vm::toggleOrgSearch,
        onSort = { sortOpen = true },
        onMenu = { menuOpen = true },
        extra = { OrgRefreshAction(vm) },
    )
    if (sortOpen) {
        OrgSortSheet(
            names = OrgModel.taskSortNames,
            selected = vm.orgTaskSort,
            onPick = { vm.orgPickTaskSort(it); sortOpen = false },
            onDismiss = { sortOpen = false },
        )
    }
    if (menuOpen) {
        OrgTasksMenuSheet(
            vm = vm,
            onDismiss = { menuOpen = false },
            onCommands = { menuOpen = false; commandsOpen = true },
        )
    }
    if (commandsOpen) {
        OrgCommandDialog(isTask = true, vm = vm, onDismiss = { commandsOpen = false })
    }
}

/**
 * 刷新: a round with every paired device, right now.
 *
 * It is on the bar rather than only on the 同步 page because that is where a
 * note is read *and* written — a row that arrived from another device while you
 * were looking at the list is the moment the button is worth pressing, and
 * making that a trip to another page is how a stale list stays stale.
 *
 * It is `vm.syncNowAll` and not `vm.syncNow(somePeer)` because there is no one
 * peer the user has in mind: the press means "a note of mine is not here", and
 * which of the devices has it is the question they did not ask. The desktop's
 * 刷新 is the same round against the same rule.
 *
 * Drawn only with a paired peer to dial ([SyncState.peers] is paired, excluding
 * this device) — a button whose only possible answer is "还没有配对任何设备" is
 * a button in the way, and the 同步 page is where pairing happens. A round in
 * flight turns the glyph into a spinner: the same treatment the 同步 page's own
 * bar gives, so "it is working" looks the same in both places.
 */
@Composable
private fun OrgRefreshAction(vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val sync = vm.view?.sync ?: SyncState.Empty
    if (sync.peers.isEmpty()) return
    IconButton(onClick = vm::syncNowAll, enabled = !sync.busy) {
        if (sync.busy) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "刷新并立即同步",
                tint = colors.textSecondary,
            )
        }
    }
}

/** The furniture both bars share: nav, title, search, sort, undo/redo, overflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onOpenDrawer: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSearch: () -> Unit,
    onSort: () -> Unit,
    onMenu: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = LocalQuireColors.current
    val dim = colors.textMuted.copy(alpha = 0.35f)
    TopAppBar(
        title = {
            Text(
                text = title,
                style = QuireType.ui.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                // A floor, because five action buttons leave the title slot very
                // little on a 360 dp screen and a squeezed title reads as "任".
                modifier = Modifier.widthIn(min = 56.dp),
            )
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "页面列表", tint = colors.textSecondary)
            }
        },
        actions = {
            extra?.invoke()
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索", tint = colors.textSecondary)
            }
            IconButton(onClick = onSort) {
                Icon(IcSort, contentDescription = "排序", tint = colors.textSecondary)
            }
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(IcUndo, contentDescription = "撤销", tint = if (canUndo) colors.textSecondary else dim)
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(IcRedo, contentDescription = "重做", tint = if (canRedo) colors.textSecondary else dim)
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = colors.textSecondary)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.textPrimary,
        ),
    )
}

/**
 * The bar 多选 puts in place of the toolbar: how many are picked, 全选, the verb
 * for what is picked, and ✕ to leave the mode.
 *
 * The reference app's own shape — a selection is a *mode*, and the bar becomes the
 * selection's rather than the toolbar growing a checkbox. The verb is a word
 * rather than a glyph because 复制 and 完成 have no icon in the shell's own set, and
 * a guessed glyph is worse than a word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgSelectionBar(
    count: Int,
    allChosen: Boolean,
    onSelectAll: () -> Unit,
    onClose: () -> Unit,
    verbs: @Composable RowScope.() -> Unit,
) {
    val colors = LocalQuireColors.current
    TopAppBar(
        title = {
            Text(
                text = "已选 $count 项",
                style = QuireType.ui.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "退出多选", tint = colors.textSecondary)
            }
        },
        actions = {
            TextButton(onClick = onSelectAll) {
                Text(if (allChosen) "取消全选" else "全选", color = colors.accentText)
            }
            verbs()
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.textPrimary,
        ),
    )
}

/** One verb of a selection bar: a word, because the shell has no glyph for it. */
@Composable
private fun OrgSelectionVerb(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalQuireColors.current
    TextButton(onClick = onClick) {
        Text(label, color = if (danger) colors.danger else colors.accentText)
    }
}

// ─── 收件箱 ─────────────────────────────────────────────────────────────────

@Composable
fun NotesPage(vm: QuireViewModel) {
    val catalog = vm.view?.org ?: OrgCatalog.Empty

    // A note opens on a page of its own (ADR-0015), the same shape 任务 gives a
    // row's form: the destination's bar stays above and the page fills what is
    // left. It is `orgNoteSel` and not local state, so a turn of the tablet lands
    // back on the note rather than on the list.
    if (vm.orgNoteSel >= 0) {
        val note = OrgModel.noteDetail(catalog, vm.orgNoteSel)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 48.dp),
        ) {
            if (note != null) {
                NoteDetailPage(row = note, catalog = catalog, vm = vm)
            } else {
                // The row is gone — deleted here, or undone away: leave the page.
                LaunchedEffect(Unit) { vm.orgSelectNote(-1) }
            }
        }
        return
    }

    // A delete in its 撤销 window is hidden on the frame it is made. The row is
    // still in the catalog — the command has not been sent — so the projection is
    // filtered, which is what makes the vanish instant and the undo free.
    val hidden = vm.pendingDelete?.takeIf { !it.isTask }?.ids ?: emptySet()
    val rows = remember(catalog, vm.orgQuery, vm.orgTag, vm.orgExcluded, vm.orgNoteSort, hidden, vm.orgBin) {
        if (vm.orgBin) {
            // 回收站 (core ADR-0003): the other half of the same catalog. The
            // search box applies to it and the tag row does not — the tag row's
            // counts are the live notes', so a filter over them would narrow by a
            // number drawn from somewhere else.
            OrgModel.binNotes(catalog, vm.orgQuery)
        } else {
            OrgModel.notes(catalog, vm.orgQuery, vm.orgTag, vm.orgNoteSort, selected = -1, exclude = vm.orgExcluded)
                .filter { it.id !in hidden }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            if (vm.orgSearchOpen) {
                OrgSearchField(
                    value = vm.orgQuery,
                    placeholder = "搜索笔记…",
                    onValueChange = vm::orgSetQuery,
                    onClose = vm::closeOrgSearch,
                )
            }
            // The tag row is a filter over the *live* notes and the bin is neither:
            // its counts would be drawn from a collection the list behind it is not
            // showing, so it is not drawn while the bin is open.
            if (!vm.orgBin) {
                OrgTagChips(
                    catalog = catalog,
                    selected = vm.orgTag,
                    excluded = vm.orgExcluded,
                    isTask = false,
                    allLabel = "全部笔记",
                    onPick = vm::orgPickTag,
                    onExclude = vm::orgToggleTagExcluded,
                )
                TagFilterBar(
                    path = vm.orgTag,
                    excluded = vm.orgExcluded,
                    onUp = vm::orgTagUp,
                    onClear = vm::orgClearFilters,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    NoteCardView(
                        row = row,
                        preview = { id -> OrgModel.parentPreview(catalog, id) },
                        onClick = { vm.orgSelectNote(row.id) },
                        onParent = { parent ->
                            // The reference app clears its filter before jumping:
                            // a parent the current search hides must not jump to a
                            // card that is not on screen.
                            vm.orgSetQuery("")
                            vm.orgPickTag("")
                            vm.orgSelectNote(parent)
                        },
                        onMenu = { vm.orgOpenNoteMenu(row.id) },
                        selecting = vm.orgSelecting,
                        selected = row.id in vm.orgSelection,
                        onSelect = { vm.orgToggleSelected(row.id) },
                        // 多选 is a set of rows on a list, and the bin is not one:
                        // its two verbs are on each card's own ⋯.
                        onLongSelect = if (vm.orgBin) null else { { vm.orgStartSelecting(row.id) } },
                    )
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        OrgEmpty(
                            text = if (vm.orgBin) {
                                if (vm.orgQuery.isNotEmpty()) "没有匹配的笔记" else "回收站是空的"
                            } else if (vm.orgQuery.isNotEmpty() || vm.orgTag.isNotEmpty() ||
                                vm.orgExcluded.isNotEmpty()
                            ) {
                                "没有匹配的笔记"
                            } else {
                                "还没有笔记"
                            },
                            hint = if (vm.orgBin) {
                                "删除的笔记会先放到这里"
                            } else {
                                "点右下角 ＋ 新建一条"
                            },
                        )
                    }
                }
            }
        }
        OrgFab(
            modifier = Modifier.align(Alignment.BottomEnd),
            label = "新建笔记",
            onClick = vm::openNoteComposer,
        )
    }

    val menuRow = vm.orgNoteMenu?.let { id -> catalog.notes.firstOrNull { it.id == id } }
    if (menuRow != null) {
        OrgNoteMenuSheet(
            row = OrgModel.noteRow(menuRow, selected = false),
            onDismiss = vm::closeNoteMenu,
            vm = vm,
        )
    } else if (vm.orgNoteMenu != null) {
        LaunchedEffect(vm.orgNoteMenu) { vm.closeNoteMenu() }
    }
}

/**
 * A tab's tag row: the level the filter is on, one chip per child tag.
 *
 * At the top level the chips are the tags themselves; one level down they are the
 * *next segment* of everything under the path, so `项目` → `工作` → `ActivityWatch`
 * is three taps — the reference app's own 层级标签 walk. A chip's number is how
 * many rows tapping it would leave on screen.
 *
 * Each chip also carries a **⊖** — the filter's other half (反向筛选). Tapping it
 * *hides* that path and its subtree and leaves the include half alone, so one path
 * can be kept while another is hidden. `isTask` decides whose tags are folded:
 * a row answering about notes while tasks were on screen would filter rows that are
 * not there.
 *
 * `allLabel` is the "no filter" chip, drawn only where the tab has no smarter row
 * of its own to say it — 收件箱's 全部笔记. 任务's smart chips *are* that row.
 */
@Composable
private fun OrgTagChips(
    catalog: OrgCatalog,
    selected: String,
    excluded: Set<String>,
    isTask: Boolean,
    allLabel: String?,
    onPick: (String) -> Unit,
    onExclude: (String) -> Unit,
) {
    val chips = remember(catalog, selected, isTask) { OrgModel.tagChips(catalog, selected, isTask) }
    if (chips.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        contentPadding = PaddingValues(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allLabel != null && selected.isEmpty()) {
            item(key = "all-notes") {
                OrgChip(label = allLabel, selected = true, onClick = { onPick("") })
            }
        }
        // The child's own segment rather than the whole path: the path is the filter
        // bar's line, and a chip that repeated it would be the same words twice.
        items(chips, key = { "tag-${it.name}" }) { chip ->
            OrgChip(
                label = "#${OrgModel.tagSegments(chip.name).last()} ${chip.count}",
                // A chip paints its own half: a path in the hidden set reads struck
                // so the row says whether it is keeping or hiding its subtree.
                excluded = chip.name in excluded,
                onExcludeToggle = { onExclude(chip.name) },
                onClick = { onPick(chip.name) },
            )
        }
    }
}

/**
 * The tag filter bar: where the filter is, one level up, and out — the *whole*
 * filter, so it draws whenever either half is set.
 *
 * The reference app's own bar. ↑ is drawn only when there is a level above — a
 * button whose only answer is "you are already there" is a button that does
 * nothing — and ✕ clears **both halves**, the include path and the hidden set. The
 * hidden paths are spelled out under the path, because a filter the user cannot
 * see is a filter they cannot undo.
 */
@Composable
private fun TagFilterBar(path: String, excluded: Set<String>, onUp: () -> Unit, onClear: () -> Unit) {
    if (path.isEmpty() && excluded.isEmpty()) return
    val colors = LocalQuireColors.current
    val parent = OrgModel.tagParentPath(path)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp, enabled = parent != null, modifier = Modifier.size(36.dp)) {
                if (parent != null) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "返回上级标签",
                        tint = colors.accentText,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = if (path.isEmpty()) "标签" else OrgModel.tagBreadcrumb(path),
                style = QuireType.caption.copy(fontSize = 13.sp),
                color = if (path.isEmpty()) colors.textMuted else colors.accentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "清除筛选",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (excluded.isNotEmpty()) {
            Text(
                text = OrgModel.excludedLabel(excluded),
                style = QuireType.caption.copy(fontSize = 13.sp),
                color = colors.danger,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 4.dp),
            )
        }
    }
}

// ─── 任务 ───────────────────────────────────────────────────────────────────

@Composable
fun TasksPage(vm: QuireViewModel) {
    val catalog = vm.view?.org ?: OrgCatalog.Empty
    val dates = remember(catalog) { OrgModel.Dates.now() }
    // The task half of the 撤销 window's filter; see [NotesPage].
    val hidden = vm.pendingDelete?.takeIf { it.isTask }?.ids ?: emptySet()

    if (organizerInDetail(vm)) {
        val row = OrgModel.taskDetail(catalog, vm.orgTaskSel, dates)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 48.dp),
        ) {
            if (row != null) TaskDetail(row = row, catalog = catalog, dates = dates, vm = vm)
            else LaunchedEffect(Unit) { vm.orgSelectRow(-1) }
        }
        return
    }

    val rows = remember(
        catalog, vm.orgView, vm.orgList, vm.orgQuery, vm.orgTaskSort, vm.orgShowDone, vm.orgTaskSel,
        vm.orgTag, vm.orgExcluded, hidden, vm.orgBin, dates,
    ) {
        if (vm.orgBin) {
            // 回收站 (core ADR-0003): the tasks' half of the bin. The needle
            // applies; the smart views, the lists and the tag filter do not, because
            // all four are questions about a *list*.
            OrgModel.binTasks(catalog, dates, vm.orgQuery, vm.orgTaskSel)
        } else {
            OrgModel.tasks(
                catalog = catalog,
                view = vm.orgView,
                list = vm.orgList,
                query = vm.orgQuery,
                sort = vm.orgTaskSort,
                selected = vm.orgTaskSel,
                showDone = vm.orgShowDone,
                dates = dates,
                tag = vm.orgTag,
                exclude = vm.orgExcluded,
            ).filter { it.id !in hidden }
        }
    }
    val (done, total) = remember(catalog) { OrgModel.progress(catalog) }
    val board = vm.orgMode == 1

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            if (vm.orgSearchOpen) {
                OrgSearchField(
                    value = vm.orgQuery,
                    placeholder = "搜索任务…",
                    onValueChange = vm::orgSetQuery,
                    onClose = vm::closeOrgSearch,
                )
            }
            // The smart views, the lists and the tag row are all questions about a
            // *list*, and a bin is not one: they are not drawn while it is open.
            if (!vm.orgBin) {
                SmartChips(catalog = catalog, dates = dates, vm = vm)
                ListChips(catalog = catalog, vm = vm)
                // The tasks' own tag row: the same question the notes' page asks, of
                // the other list, with the same include / exclude halves. It is not
                // drawn on the board either, which files by list and has no tag column.
                if (!board) {
                    OrgTagChips(
                        catalog = catalog,
                        selected = vm.orgTag,
                        excluded = vm.orgExcluded,
                        isTask = true,
                        allLabel = null,
                        onPick = vm::orgPickTag,
                        onExclude = vm::orgToggleTagExcluded,
                    )
                    TagFilterBar(
                        path = vm.orgTag,
                        excluded = vm.orgExcluded,
                        onUp = vm::orgTagUp,
                        onClear = vm::orgClearFilters,
                    )
                }
            }

            if (board && !vm.orgBin) {
                BoardPane(catalog = catalog, dates = dates, vm = vm, hidden = hidden)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        TaskRowView(
                            row = row,
                            today = dates.today,
                            onToggle = { vm.orgToggleTaskDone(row.id, !row.done) },
                            onClick = { vm.orgSelectTask(row.id) },
                            onMenu = { vm.orgOpenTaskMenu(row.id) },
                            selecting = vm.orgSelecting,
                            selected = row.id in vm.orgSelection,
                            onSelect = { vm.orgToggleSelected(row.id) },
                            // The board is a "what is left" view and has no room for a
                            // selection bar; 多选 is the list's — and the bin's rows
                            // carry their own two verbs instead.
                            onLongSelect = if (board || vm.orgBin) null else { { vm.orgStartSelecting(row.id) } },
                        )
                    }
                    if (rows.isEmpty()) {
                        item(key = "empty") {
                            OrgEmpty(
                                text = if (vm.orgBin) {
                                    if (vm.orgQuery.isNotEmpty()) "没有匹配的任务" else "回收站是空的"
                                } else if (vm.orgQuery.isNotEmpty() || vm.orgTag.isNotEmpty() ||
                                    vm.orgExcluded.isNotEmpty()
                                ) {
                                    "没有匹配的任务"
                                } else {
                                    "暂无任务"
                                },
                                hint = if (vm.orgBin) "删除的任务会先放到这里" else "点右下角 ＋ 添加任务",
                            )
                        }
                    }
                }
                TaskProgressFooter(
                    done = done,
                    total = total,
                    showDone = vm.orgShowDone,
                    onToggleShowDone = vm::orgToggleShowDone,
                )
            }
        }
        OrgFab(
            modifier = Modifier.align(Alignment.BottomEnd),
            label = "添加任务",
            onClick = vm::openTaskComposer,
        )
    }

    val menuRow = vm.orgTaskMenu?.let { id -> catalog.tasks.firstOrNull { it.id == id } }
    if (menuRow != null) {
        OrgTaskMenuSheet(
            row = OrgModel.taskRow(catalog, menuRow, dates, selected = false),
            catalog = catalog,
            onDismiss = vm::closeTaskMenu,
            vm = vm,
        )
    }
}

/** The five smart views, with their counts. */
@Composable
private fun SmartChips(catalog: OrgCatalog, dates: OrgModel.Dates, vm: QuireViewModel) {
    val counts = remember(catalog, vm.orgQuery) { OrgModel.smartCounts(catalog, vm.orgQuery, dates) }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        contentPadding = PaddingValues(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(OrgModel.smartNames) { index, name ->
            OrgChip(
                label = "$name ${counts.getOrElse(index) { 0 }}",
                selected = vm.orgList < 0 && vm.orgView == index,
                onClick = { vm.orgPickView(index) },
            )
        }
    }
}

/**
 * The inbox first, then the stored lists, then "＋ 新建清单".
 *
 * A long press on a stored list is its own verbs — rename, recolour, delete —
 * which is the touch idiom the sidebar already uses for a page. The Slint shell
 * declares those three callbacks and never wires them, so a list can be created
 * there and never renamed; this shell wires them (ADR-0013).
 */
@Composable
private fun ListChips(catalog: OrgCatalog, vm: QuireViewModel) {
    val chips = remember(catalog, vm.orgView, vm.orgList) {
        OrgModel.listChips(catalog, vm.orgView, vm.orgList)
    }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyRow(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        contentPadding = PaddingValues(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(chips, key = { "list-${it.id}" }) { chip ->
            OrgChip(
                label = if (chip.count > 0) "${chip.name} ${chip.count}" else chip.name,
                selected = if (vm.orgList >= 0) vm.orgList == chip.id else chip.smart,
                colorSlot = if (chip.smart) null else chip.color,
                onLongClick = if (chip.smart) null else ({ menuFor = chip.id }),
                onClick = { vm.orgPickList(chip.id) },
            )
        }
        item(key = "new-list") {
            // Named first, then created: a list called 新建清单 that has to be
            // renamed by a long press is a chore with no hint attached.
            OrgChip(label = "＋ 新建清单", accent = true, onClick = { creating = true })
        }
    }

    if (creating) {
        OrgTextDialog(
            title = "新建清单",
            initial = "",
            placeholder = "清单名字",
            onDismiss = { creating = false },
            onConfirm = { name ->
                vm.orgCreateList(name)
                creating = false
            },
        )
    }

    val menuChip = menuFor?.let { id -> chips.firstOrNull { it.id == id } }
    if (menuChip != null) {
        OrgListMenuSheet(chip = menuChip, onDismiss = { menuFor = null }, vm = vm)
    } else if (menuFor != null) {
        LaunchedEffect(menuFor) { menuFor = null }
    }
}

/**
 * 已完成 X / Y as a bar and a line, taken from the reference app's own footer: a
 * 4 dp track with the accent for the finished share, and the switch that reveals
 * the finished rows beside it.
 *
 * It counts the **whole area** rather than the filtered view: a progress line that
 * moved when the user typed a search would be answering a different question from
 * the one it looks like it answers.
 */
@Composable
private fun TaskProgressFooter(done: Int, total: Int, showDone: Boolean, onToggleShowDone: () -> Unit) {
    val colors = LocalQuireColors.current
    val share = if (total == 0) 0f else done.toFloat() / total
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = Spacing.sm)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.border),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已完成 $done / $total",
                style = QuireType.caption,
                color = colors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            OrgChip(
                label = if (showDone) "隐藏已完成 ($done)" else "显示已完成 ($done)",
                small = true,
                selected = showDone,
                onClick = onToggleShowDone,
            )
            // The ＋ floats over this corner, so the footer stops short of it: a
            // switch a finger cannot reach is worse than one line further left.
            Spacer(Modifier.width(88.dp))
        }
    }
}

/** The ＋ at the bottom right: what opens the capture sheet. */
@Composable
private fun OrgFab(modifier: Modifier, label: String, onClick: () -> Unit) {
    val colors = LocalQuireColors.current
    Box(
        modifier = modifier
            .padding(16.dp)
            .size(56.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = label,
            tint = colors.background,
            modifier = Modifier.size(26.dp),
        )
    }
}

// ─── the board ──────────────────────────────────────────────────────────────

/** One card being dragged: which row, its title, and where the finger is. */
private data class Drag(val id: Long, val title: String, val pointer: Offset)

/**
 * 平铺: one column per list, the open tasks on cards.
 *
 * A card is dragged onto another column to move it there, and that gesture
 * *replaces* the desktop's drag-and-drop rather than imitating it: on a finger a
 * long press picks the card up — a plain drag would be a scroll, and a slow slide
 * must not be read as a move — and while it is held the column under the finger
 * lights up. The card's ⋯ offers the same move as a menu, because a gesture
 * nobody can discover must not be the only way to do it.
 */
@Composable
private fun BoardPane(catalog: OrgCatalog, dates: OrgModel.Dates, vm: QuireViewModel, hidden: Set<Long>) {
    val columns = remember(catalog, vm.orgQuery, vm.orgTaskSort, hidden) {
        OrgModel.board(catalog, vm.orgQuery, vm.orgTaskSort, dates)
            .map { column ->
                // A card in its 撤销 window is hidden here too, and the column's
                // count moves with it: a header counting a card nobody can see is
                // the same bug as the card itself.
                val cards = column.cards.filter { it.id !in hidden }
                column.copy(cards = cards, count = cards.size)
            }
    }
    // Column boxes in *root* coordinates — the same space the finger is tracked
    // in, so a card in a horizontally scrolled board still finds its column.
    val bounds = remember { mutableMapOf<Long, Rect>() }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<Drag?>(null) }
    var hovered by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { boardOrigin = it.positionInRoot() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            for (column in columns) {
                BoardColumnView(
                    column = column,
                    today = dates.today,
                    hovered = hovered == column.id,
                    onBounds = { bounds[column.id] = it },
                    onCardToggle = { vm.orgToggleTaskDone(it, true) },
                    onCardOpen = { vm.orgSelectTask(it) },
                    onCardMenu = { vm.orgOpenTaskMenu(it) },
                    onCardMove = { id, pointer ->
                        dragging = Drag(id, columns.titleOf(id), pointer)
                        hovered = columnAt(bounds, pointer)
                    },
                    onCardDrop = {
                        val card = dragging?.id
                        val target = hovered
                        dragging = null
                        hovered = null
                        if (card != null && target != null) vm.orgMoveTask(card, target)
                    },
                    onAdd = { title -> vm.orgQuickAddTo(column.id, title) },
                )
            }
            Spacer(Modifier.width(Spacing.lg))
        }

        // The card under the finger, drawn above every column rather than inside
        // one, so it can cross the gaps between them. `Modifier.offset` speaks
        // *pixels*, so the two nudges that centre it on the finger are converted
        // from dp once, outside the lambda.
        val density = LocalDensity.current
        val halfCard = with(density) { 88.dp.toPx() }
        val grab = with(density) { 26.dp.toPx() }
        dragging?.let { drag ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (drag.pointer.x - boardOrigin.x - halfCard).roundToInt(),
                            (drag.pointer.y - boardOrigin.y - grab).roundToInt(),
                        )
                    }
                    .width(176.dp),
            ) {
                OrgCard(title = drag.title, tags = emptyList(), priority = 0, dueLabel = "", overdue = false)
            }
        }
    }
}

/** The column whose box holds the point, or null when it is between two. */
private fun columnAt(bounds: Map<Long, Rect>, point: Offset): Long? =
    bounds.entries.firstOrNull { (_, rect) -> rect.contains(point) }?.key

/** The title of the card a drag is carrying, looked up from the columns. */
private fun List<OrgModel.Column>.titleOf(card: Long): String =
    firstNotNullOfOrNull { column -> column.cards.firstOrNull { it.id == card }?.title } ?: ""

@Composable
private fun BoardColumnView(
    column: OrgModel.Column,
    today: String,
    hovered: Boolean,
    onBounds: (Rect) -> Unit,
    onCardToggle: (Long) -> Unit,
    onCardOpen: (Long) -> Unit,
    onCardMenu: (Long) -> Unit,
    onCardMove: (Long, Offset) -> Unit,
    onCardDrop: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val colors = LocalQuireColors.current
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .width(230.dp)
            .fillMaxHeight()
            .onGloballyPositioned { coords ->
                onBounds(
                    Rect(
                        coords.positionInRoot(),
                        Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                    ),
                )
            }
            .clip(RoundedCornerShape(Radius.lg))
            .background(if (hovered) colors.accentSoft else colors.card)
            .border(
                width = 1.dp,
                color = if (hovered) colors.accent else colors.cardBorder,
                shape = RoundedCornerShape(Radius.lg),
            )
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(blockTextColor(column.color, colors.isDark)),
            )
            Text(
                text = column.name,
                style = QuireType.ui.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${column.count}", style = QuireType.caption, color = colors.textMuted)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(column.cards, key = { it.id }) { card ->
                var origin by remember(card.id) { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { origin = it.positionInRoot() }
                        .pointerInput(card.id) {
                            // `change.position` is local to the card, so the card's
                            // own root origin turns it back into the space the
                            // columns are measured in. `dragAmount` is deliberately
                            // *not* added: `position` is already absolute, and adding
                            // the delta per event would make the card run away from
                            // the finger.
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onCardMove(card.id, origin + it) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    onCardMove(card.id, origin + change.position)
                                },
                                onDragEnd = { onCardDrop() },
                                onDragCancel = { onCardDrop() },
                            )
                        },
                ) {
                    OrgCard(
                        title = card.title,
                        tags = card.tags,
                        priority = card.priority,
                        dueLabel = card.dueLabel,
                        overdue = card.overdue,
                        today = card.due == today,
                        done = card.done,
                        onToggle = { onCardToggle(card.id) },
                        onClick = { onCardOpen(card.id) },
                        onMenu = { onCardMenu(card.id) },
                    )
                }
            }
        }

        OrgQuickAdd(
            value = draft,
            placeholder = "＋ 添加任务",
            onValueChange = { draft = it },
            onSubmit = {
                if (draft.isNotBlank()) {
                    onAdd(draft)
                    draft = ""
                }
            },
        )
    }
}

// ─── the task detail ────────────────────────────────────────────────────────

@Composable
private fun TaskDetail(
    row: OrgModel.TaskRow,
    catalog: OrgCatalog,
    dates: OrgModel.Dates,
    vm: QuireViewModel,
) {
    val colors = LocalQuireColors.current
    val focus = LocalFocusManager.current
    val titleFocus = remember { FocusRequester() }
    var dueDraft by remember("due-${row.id}") { mutableStateOf(row.due) }

    LaunchedEffect(row.id) {
        if (vm.orgFocus == row.id) {
            titleFocus.requestFocus()
            vm.consumeOrgFocus()
        }
    }
    // A deadline moved by the 今天 / 清除 buttons has to move the draft too, or the
    // field would still be showing the day the row no longer has.
    LaunchedEffect(row.id, row.due) { if (dueDraft != row.due) dueDraft = row.due }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OrgCheck(
                checked = row.done,
                round = true,
                size = 22.dp,
                onToggle = { vm.orgToggleTaskDone(row.id, !row.done) },
            )
            OrgTextField(
                rowKey = "task-title-${row.id}",
                value = row.title,
                placeholder = "任务",
                textStyle = QuireType.h3,
                focusRequester = titleFocus,
                onCommit = { vm.orgTaskTitle(row.id, it) },
                modifier = Modifier.weight(1f),
            )
        }

        OrgChoiceRow("清单") {
            for (chip in OrgModel.listChips(catalog, vm.orgView, vm.orgList)) {
                OrgChip(
                    label = chip.name,
                    colorSlot = if (chip.smart) null else chip.color,
                    small = true,
                    selected = if (chip.smart) row.list == 0L else row.list == chip.id,
                    onClick = { vm.orgMoveTask(row.id, chip.id) },
                )
            }
        }

        OrgChoiceRow("优先级") {
            for ((index, name) in OrgModel.priorityNames.withIndex()) {
                OrgChip(
                    label = name,
                    small = true,
                    selected = row.priority == index,
                    onClick = { vm.orgTaskPriority(row.id, index) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OrgFieldLabel("截止", width = OrgLabelWidth)
            // The one field in the form that shares its row with buttons, so it
            // takes what is left rather than a fixed width: at a large text size a
            // fixed 126 dp would leave the 今天 chip off the edge of the screen.
            OrgTextField(
                rowKey = "task-due-${row.id}",
                value = dueDraft,
                placeholder = "YYYY-MM-DD",
                onValueChange = { dueDraft = it },
                onCommit = { vm.orgTaskDue(row.id, it) },
                modifier = Modifier.weight(1f),
            )
            OrgChip(
                label = "今天",
                small = true,
                selected = row.due == dates.today,
                onClick = { vm.orgTaskDue(row.id, if (row.due == dates.today) "" else dates.today) },
            )
            if (row.due.isNotEmpty()) {
                OrgChip(label = "清除", small = true, onClick = { vm.orgTaskDue(row.id, "") })
            }
            if (row.overdue) {
                Text("已逾期", style = QuireType.caption, color = colors.danger)
            }
        }

        OrgChoiceRow("重复") {
            for ((index, name) in OrgModel.repeatNames.withIndex()) {
                OrgChip(
                    label = name,
                    small = true,
                    selected = row.repeat == index,
                    onClick = { vm.orgTaskRepeat(row.id, index) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OrgFieldLabel("标签", width = OrgLabelWidth)
            OrgTextField(
                rowKey = "task-tags-${row.id}",
                value = row.tags.joinToString(", "),
                placeholder = "用逗号分隔",
                onCommit = { vm.orgTaskTags(row.id, it) },
                modifier = Modifier.weight(1f),
            )
        }

        Text("备注", style = QuireType.caption, color = colors.textMuted)
        OrgTextField(
            rowKey = "task-notes-${row.id}",
            value = row.notes,
            placeholder = "备注",
            multiline = true,
            minHeight = 120.dp,
            onCommit = { vm.orgTaskNotes(row.id, it) },
            modifier = Modifier.fillMaxWidth(),
        )

        SubtaskBox(row = row, vm = vm)

        TextButton(
            onClick = {
                focus.clearFocus()
                vm.orgDeleteTask(row.id)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("删除任务", color = colors.danger)
        }
    }
}

/** The checklist: its own box, with the ＋ line inside it. */
@Composable
private fun SubtaskBox(row: OrgModel.TaskRow, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val focus = LocalFocusManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("子任务", style = QuireType.caption, color = colors.textMuted)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The box hugs its content rather than reserving the desktop's
                // 152 dp: this form scrolls, so a fixed floor would leave the ＋
                // line floating in the middle of an empty box. The floor is what
                // keeps an empty one reading as a box rather than a stray row.
                .heightIn(min = 96.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
                .padding(Spacing.xs),
        ) {
            // A new line's field takes the caret: the ＋ makes a row nobody has
            // typed into yet, and asking for a second tap to start writing would be
            // the whole reason to add it.
            val lastId = row.subtasks.lastOrNull()?.id
            val newFocus = remember { FocusRequester() }
            var seen by remember(row.id) { mutableStateOf(row.subtasks.size) }
            LaunchedEffect(row.id, row.subtasks.size) {
                if (row.subtasks.size > seen) newFocus.requestFocus()
                seen = row.subtasks.size
            }

            for (sub in row.subtasks) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OrgCheck(
                        checked = sub.done,
                        size = 18.dp,
                        onToggle = { vm.orgSubtaskDone(row.id, sub.id, !sub.done) },
                    )
                    OrgTextField(
                        rowKey = "sub-${row.id}-${sub.id}",
                        value = sub.title,
                        placeholder = "子任务",
                        onCommit = { vm.orgSubtaskTitle(row.id, sub.id, it) },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (sub.id == lastId) Modifier.focusRequester(newFocus) else Modifier),
                    )
                    IconButton(onClick = { vm.orgSubtaskDelete(row.id, sub.id) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除子任务",
                            tint = colors.textMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OrgChip(
                    label = "＋ 添加子任务",
                    accent = true,
                    small = true,
                    onClick = {
                        focus.clearFocus()
                        vm.orgSubtaskAdd(row.id)
                    },
                )
                Spacer(Modifier.weight(1f))
                Text("${row.subtasksDone}/${row.subtasksTotal}", style = QuireType.caption, color = colors.textMuted)
            }
        }
    }
}

// ─── the sheets ─────────────────────────────────────────────────────────────

/** A sort menu: one choice, from wherever the bar's ⇅ button was pressed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgSortSheet(
    names: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuireColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            OrgSheetHeader("排序")
            for ((index, name) in names.withIndex()) {
                OrgSheetItem(name, selected = index == selected) { onPick(index) }
            }
        }
    }
}

/** 收件箱's overflow: the whole list, onto the clipboard, and the 指令 door. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgNotesMenuSheet(vm: QuireViewModel, onDismiss: () -> Unit, onCommands: () -> Unit) {
    val colors = LocalQuireColors.current
    val clipboard = LocalClipboardManager.current
    val catalog = vm.view?.org ?: OrgCatalog.Empty
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (vm.orgBin) {
                // The bin's own verb, and nothing else: 多选 is a set of rows on a
                // list, 复制 hands rows to an AI and 指令 edits them — none of the
                // three is a question about a bin.
                OrgSheetItem("清空回收站", danger = true) {
                    onDismiss()
                    vm.orgEmptyBin()
                }
            } else {
                OrgSheetItem("多选") {
                    onDismiss()
                    vm.orgBeginSelecting()
                }
                OrgSheetItem("清除过滤") {
                    vm.orgClearFilters()
                    vm.orgSetQuery("")
                }
                OrgSheetItem("复制全部") {
                    // Every note the filter is showing, each with its 唯一 ID on a line
                    // of its own — the text 指令's batch names the rows by.
                    val shown = OrgModel.notes(catalog, vm.orgQuery, vm.orgTag, vm.orgNoteSort, -1, vm.orgExcluded)
                    clipboard.setText(AnnotatedString(OrgModel.noteCopyText(catalog, shown.map { it.id })))
                    onDismiss()
                }
                OrgSheetItem("指令…") { onCommands() }
            }
            OrgSheetItem(if (vm.orgBin) "离开回收站" else "回收站") {
                onDismiss()
                vm.orgToggleBin()
            }
        }
    }
}

/** 任务's overflow: the view switch, the list verb that is not a chip, and 指令. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgTasksMenuSheet(vm: QuireViewModel, onDismiss: () -> Unit, onCommands: () -> Unit) {
    val colors = LocalQuireColors.current
    val clipboard = LocalClipboardManager.current
    val catalog = vm.view?.org ?: OrgCatalog.Empty
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        OrgTextDialog(
            title = "新建清单",
            initial = "",
            placeholder = "清单名字",
            onDismiss = { creating = false },
            onConfirm = { name ->
                vm.orgCreateList(name)
                creating = false
                onDismiss()
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (vm.orgBin) {
                // The bin's own verb; see [OrgNotesMenuSheet] for why nothing else
                // is offered while it is open.
                OrgSheetItem("清空回收站", danger = true) {
                    onDismiss()
                    vm.orgEmptyBin()
                }
            } else {
                OrgSheetHeader("视图")
                // The desktop's copy puts 列表/平铺 in the card's header; on a phone the
                // toolbar has no room for it beside 搜索, 排序 and the undo pair, and a
                // squeezed title is the price of trying.
                OrgSheetItem("列表", selected = vm.orgMode == 0) {
                    vm.orgPickMode(0)
                    onDismiss()
                }
                OrgSheetItem("平铺", selected = vm.orgMode == 1) {
                    vm.orgPickMode(1)
                    onDismiss()
                }
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
                OrgSheetItem("多选") {
                    onDismiss()
                    vm.orgBeginSelecting()
                }
                OrgSheetItem("复制全部") {
                    // Every task the filter is showing, each with its 唯一 ID — the
                    // tasks' half of what 复制全部 hands an AI.
                    val shown = OrgModel.tasks(
                        catalog = catalog,
                        view = vm.orgView,
                        list = vm.orgList,
                        query = vm.orgQuery,
                        sort = vm.orgTaskSort,
                        selected = -1,
                        showDone = vm.orgShowDone,
                        dates = OrgModel.Dates.now(),
                        tag = vm.orgTag,
                        exclude = vm.orgExcluded,
                    )
                    clipboard.setText(AnnotatedString(OrgModel.taskCopyText(catalog, shown.map { it.id })))
                    onDismiss()
                }
                OrgSheetItem("新建清单") { creating = true }
                OrgSheetItem("指令…") { onCommands() }
            }
            OrgSheetItem(if (vm.orgBin) "离开回收站" else "回收站") {
                onDismiss()
                vm.orgToggleBin()
            }
        }
    }
}

/**
 * A note's ⋯: pin it, open it, reply to it, copy it, or delete it.
 *
 * 删除 no longer asks first: it hides the row at once and the 撤销 bar is the way
 * back (ADR-0015). That is the reference app's own idiom, costs one fewer tap on
 * every delete, and is what the confirmation dialog was standing in for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgNoteMenuSheet(row: OrgModel.NoteRow, onDismiss: () -> Unit, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            OrgSheetHeader(row.content.lineSequence().first().take(24).ifEmpty { "空白笔记" })
            if (row.binned) {
                // 回收站's own two verbs (core ADR-0003), and nothing else: 置顶,
                // 评论 and 转为待办 are all writes to a row on a list, and this one
                // is not on a list.
                OrgSheetItem("恢复") {
                    onDismiss()
                    vm.orgRestore(row.id)
                }
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
                OrgSheetItem("彻底删除", danger = true) {
                    onDismiss()
                    vm.orgPurge(row.id)
                }
            } else {
            OrgSheetItem(if (row.pinned) "取消置顶" else "置顶") {
                vm.orgToggleNotePinned(row.id, !row.pinned)
                onDismiss()
            }
            OrgSheetItem("打开") {
                onDismiss()
                vm.orgSelectNote(row.id)
            }
            OrgSheetItem("评论") {
                onDismiss()
                vm.openCommentComposer(row.id)
            }
            OrgSheetItem("复制内容") {
                // The one note, with its 唯一 ID on a line of its own — the smallest
                // unit 复制 hands an AI, and the name a 指令 batch can act on.
                val catalog = vm.view?.org ?: OrgCatalog.Empty
                clipboard.setText(AnnotatedString(OrgModel.noteCopyText(catalog, listOf(row.id))))
                onDismiss()
            }
            // The reference app's own migration, and its no-confirm rule: the note
            // becomes a task and the note goes, with the 撤销 bar as the way back.
            OrgSheetItem("转为待办") {
                onDismiss()
                vm.orgConvertToTask(row.id)
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            OrgSheetItem("删除", danger = true) {
                onDismiss()
                vm.orgDeleteNote(row.id)
            }
            }
        }
    }
}

/** A task row's ⋯: open it, tick it, move it, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgTaskMenuSheet(
    row: OrgModel.TaskRow,
    catalog: OrgCatalog,
    onDismiss: () -> Unit,
    vm: QuireViewModel,
) {
    val colors = LocalQuireColors.current
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp),
        ) {
            OrgSheetHeader(row.title.ifEmpty { "无标题" })
            if (row.binned) {
                // The bin's own two verbs; see [OrgNoteMenuSheet].
                OrgSheetItem("恢复") {
                    onDismiss()
                    vm.orgRestore(row.id)
                }
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
                OrgSheetItem("彻底删除", danger = true) {
                    onDismiss()
                    vm.orgPurge(row.id)
                }
            } else {
            OrgSheetItem("打开详情") {
                onDismiss()
                vm.orgSelectTask(row.id)
            }
            OrgSheetItem(if (row.done) "标记为未完成" else "标记为已完成") {
                vm.orgToggleTaskDone(row.id, !row.done)
            }
            // Only the lists it could actually go to: a move to the list it is
            // already in would be a row that does nothing.
            for (chip in OrgModel.listChips(catalog, vm.orgView, vm.orgList)) {
                if (chip.id == row.list) continue
                OrgSheetItem(if (chip.smart) "移到收集箱" else "移到「${chip.name}」") {
                    vm.orgMoveTask(row.id, chip.id)
                }
            }
            OrgSheetItem("复制") {
                // A title, its 备注, then the 唯一 ID — one task's worth of what the
                // selection bar's 复制 hands an AI.
                clipboard.setText(AnnotatedString(OrgModel.taskCopyText(catalog, listOf(row.id))))
                onDismiss()
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            OrgSheetItem("删除任务", danger = true) {
                onDismiss()
                vm.orgDeleteTask(row.id)
            }
            }
        }
    }
}

/** A stored list's long press: rename it, recolour it, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrgListMenuSheet(chip: OrgModel.ListChip, onDismiss: () -> Unit, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (renaming) {
        OrgTextDialog(
            title = "重命名清单",
            initial = chip.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                vm.orgListName(chip.id, name)
                renaming = false
                onDismiss()
            },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "删除清单",
            message = if (chip.count > 0) {
                "「${chip.name}」里的 ${chip.count} 项任务会移进收集箱，不会丢。"
            } else {
                "「${chip.name}」是空的，删掉它不会有影响。"
            },
            confirmLabel = "删除清单",
            onDismiss = { deleting = false },
            onConfirm = {
                vm.orgDeleteList(chip.id)
                deleting = false
                onDismiss()
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp),
        ) {
            OrgSheetHeader(chip.name)
            OrgSheetItem("重命名") { renaming = true }
            OrgSheetHeader("颜色")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                for (slot in OrgModel.listColorSlots) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(blockTextColor(slot, colors.isDark))
                            .clickable {
                                vm.orgListColor(chip.id, slot)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (slot == chip.color) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "当前颜色",
                                // The page's own ink, not a fixed white: a white tick
                                // would vanish on the yellow dot.
                                tint = colors.background,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            OrgSheetItem("删除清单", danger = true) { deleting = true }
        }
    }
}

@Composable
internal fun OrgSheetHeader(label: String) {
    Text(
        text = label,
        style = QuireType.caption,
        color = LocalQuireColors.current.textMuted,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
internal fun OrgSheetItem(label: String, selected: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = QuireType.ui,
            color = when {
                danger -> colors.danger
                selected -> colors.accentText
                else -> colors.textPrimary
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** One text field in a dialog. */
@Composable
private fun OrgTextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String = "",
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                modifier = Modifier.fillMaxWidth().imePadding(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 指令 (SPEC §四十一): paste one batch of AI instructions and apply it.
 *
 * The other half of 复制: the clipboard carries the rows with their 唯一 IDs, the AI
 * answers with `{"operations":[…]}` naming those ids, and this applies the batch —
 * in order, as **one** undo step, so one 撤销 takes the whole batch back. `isTask`
 * names the half: a task action pasted on the notes half is refused action by
 * action and *counted*, never silently skipped, and the count comes back in the
 * notice bar.
 *
 * 复制示例 puts an editable template on the clipboard, so the batch's shape is
 * discoverable without a manual. A payload the Rust side cannot read at all (bad
 * JSON, no `operations`) fails the whole call and lands in the error bar instead.
 */
@Composable
private fun OrgCommandDialog(isTask: Boolean, vm: QuireViewModel, onDismiss: () -> Unit) {
    val colors = LocalQuireColors.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isTask) "任务指令" else "笔记指令") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("{\"operations\":[…]}") },
                    minLines = 5,
                    maxLines = 10,
                    textStyle = QuireType.caption,
                    modifier = Modifier.fillMaxWidth().imePadding(),
                )
                Text(
                    text = "粘贴 AI 返回的 operations。整批作为一步撤销。",
                    style = QuireType.caption,
                    color = colors.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.orgRunCommands(isTask, text)
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) { Text("应用") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(if (isTask) TASK_COMMAND_EXAMPLE else NOTE_COMMAND_EXAMPLE),
                        )
                    },
                ) { Text("复制示例") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

// ─── the small pieces ───────────────────────────────────────────────────────

/**
 * One tappable chip: a smart view, a sort, a list, a priority, a tag. `selected`
 * is the one lit state the whole area uses.
 *
 * `onExcludeToggle` draws a **⊖** at the chip's trailing edge — the tag row's
 * other half (反向筛选), where the chip's tap keeps a subtree and the ⊖ hides it.
 * A finger cannot hover, so the control is drawn for every chip rather than
 * revealed on one, and `excluded` strikes the label so a hidden path reads as
 * hidden.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrgChip(
    label: String,
    selected: Boolean = false,
    accent: Boolean = false,
    small: Boolean = false,
    /** `>= 1` draws a palette dot before the label; `null` for none. */
    colorSlot: Int? = null,
    /** The chip's path is in the hidden set: the label is struck and the ⊖ is lit. */
    excluded: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** The ⊖'s own tap; `null` for a chip with no hidden half. */
    onExcludeToggle: (() -> Unit)? = null,
) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .height(if (small) 30.dp else 34.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (selected) colors.surfaceSelected else Color.Transparent)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(
                start = if (colorSlot != null && colorSlot > 0) Spacing.sm else 10.dp,
                end = when {
                    onExcludeToggle != null -> 4.dp
                    colorSlot != null && colorSlot > 0 -> Spacing.sm
                    else -> 10.dp
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (colorSlot != null && colorSlot > 0) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(blockTextColor(colorSlot, colors.isDark)),
            )
        }
        Text(
            text = label,
            style = if (small) QuireType.caption else QuireType.ui,
            color = when {
                excluded -> colors.danger
                selected -> colors.textPrimary
                accent -> colors.accentText
                else -> colors.textSecondary
            },
            maxLines = 1,
            textDecoration = if (excluded) TextDecoration.LineThrough else null,
        )
        if (onExcludeToggle != null) {
            // A circled bar rather than the reference's `⊘`: the same idea in a
            // shape Compose draws without a rotated glyph.
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(if (excluded) colors.danger.copy(alpha = 0.12f) else Color.Transparent)
                    .border(1.dp, if (excluded) colors.danger else colors.borderStrong, CircleShape)
                    .clickable(onClick = onExcludeToggle),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 9.dp, height = 1.5.dp)
                        .background(if (excluded) colors.danger else colors.textMuted),
                )
            }
        }
    }
}

/** The checkbox in front of a task and a checklist line. */
@Composable
internal fun OrgCheck(checked: Boolean, round: Boolean = false, size: Dp = 18.dp, onToggle: () -> Unit) {
    val colors = LocalQuireColors.current
    val shape = RoundedCornerShape(if (round) size / 2 else Radius.sm)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (checked) colors.accent else Color.Transparent)
            .border(1.5.dp, if (checked) colors.accent else colors.borderStrong, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = colors.background,
                modifier = Modifier.size(size - 6.dp),
            )
        }
    }
}

/** A priority as the glyph the desktop uses: ▲ ◆ ▼. A word per row is a column of noise. */
@Composable
private fun OrgPrio(slot: Int) {
    if (slot <= 0) return
    val colors = LocalQuireColors.current
    Text(
        text = priorityGlyph(slot),
        style = QuireType.caption.copy(fontWeight = FontWeight.Bold),
        color = when (slot) {
            3 -> colors.danger
            2 -> colors.accentText
            else -> OrgSuccess
        },
    )
}

/** A deadline as a badge: boxed, so a date in a row of dates reads as one thing. */
@Composable
private fun OrgDue(label: String, overdue: Boolean, today: Boolean) {
    if (label.isEmpty()) return
    val colors = LocalQuireColors.current
    Text(
        text = label,
        style = QuireType.caption,
        color = when {
            overdue -> colors.danger
            today -> colors.accentText
            else -> colors.textSecondary
        },
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (overdue) colors.danger.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (overdue) colors.danger.copy(alpha = 0.45f) else colors.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        maxLines = 1,
    )
}

/**
 * One task as the list paints it: a glass row — title, then a meta line carrying
 * the list's dot, the priority, the deadline, the checklist count, and the tags
 * pushed to the right edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRowView(
    row: OrgModel.TaskRow,
    today: String,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    /** 多选: the page is picking rows, so a tap toggles rather than opens. */
    selecting: Boolean = false,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
    /** A long press *starts* 多选 — a finger cannot hover, so this is the way in. */
    onLongSelect: (() -> Unit)? = null,
) {
    val colors = LocalQuireColors.current
    val gesture = when {
        selecting && onSelect != null -> Modifier.combinedClickable(onClick = onSelect)
        !selecting && onLongSelect != null ->
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongSelect)
        else -> Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected || row.selected) colors.surfaceSelected else colors.card)
            .border(
                1.dp,
                if (selected || row.selected) colors.accent else colors.cardBorder,
                RoundedCornerShape(12.dp),
            )
            .then(gesture)
            .padding(start = Spacing.sm, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // In 多选 the box in front is the *selection*, not the task's own state: a
        // tap that both picked a row and ticked it would be two verbs in one tap.
        if (selecting) {
            OrgCheck(checked = selected, size = 20.dp, onToggle = { onSelect?.invoke() })
        } else {
            OrgCheck(checked = row.done, round = true, size = 20.dp, onToggle = onToggle)
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = row.title.ifEmpty { "无标题" },
                style = QuireType.body.copy(fontSize = 15.sp),
                color = if (row.done) colors.textMuted else colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The meta line is only drawn when there is something to put in it: a
            // task with no list, no priority, no deadline and no checklist must not
            // cost a line of every row.
            if (row.list > 0 || row.priority > 0 || row.dueLabel.isNotEmpty() ||
                row.subtasksTotal > 0 || row.tags.isNotEmpty()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (row.list > 0) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(blockTextColor(row.listColor, colors.isDark)),
                        )
                    }
                    OrgPrio(row.priority)
                    OrgDue(label = row.dueLabel, overdue = row.overdue, today = row.due == today)
                    if (row.subtasksTotal > 0) {
                        Text(
                            "☑ ${row.subtasksDone}/${row.subtasksTotal}",
                            style = QuireType.caption,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (row.tags.isNotEmpty()) {
                        Text(
                            text = row.tags.joinToString(" ") { "#$it" },
                            style = QuireType.caption,
                            color = colors.accentText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                    }
                }
            }
        }
        if (!selecting) {
            IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** One card of the board. */
@Composable
private fun OrgCard(
    title: String,
    tags: List<String>,
    priority: Int,
    dueLabel: String,
    overdue: Boolean,
    today: Boolean = false,
    done: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
) {
    val colors = LocalQuireColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (onToggle != null) OrgCheck(checked = done, size = 18.dp, onToggle = onToggle)
            Text(
                text = title.ifEmpty { "无标题" },
                style = QuireType.ui,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            OrgPrio(priority)
            if (onMenu != null) {
                IconButton(onClick = onMenu, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString(" ") { "#$it" },
                style = QuireType.caption,
                color = colors.accentText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (dueLabel.isNotEmpty()) {
            OrgDue(label = dueLabel, overdue = overdue, today = today)
        }
    }
}

/**
 * A field's label, to the left of its control.
 *
 * The width is a *minimum* and the text never wraps: at the device's own font
 * scale a three-character label is wider than any fixed slot that still fits a
 * two-character one, and a label broken over two lines ("优先 / 级") reads as a
 * layout that fell apart. Letting the column grow keeps the controls aligned among
 * themselves, which is what the alignment is for.
 */
@Composable
private fun OrgFieldLabel(name: String, width: Dp) {
    Text(
        text = name,
        style = QuireType.caption,
        color = LocalQuireColors.current.textMuted,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.widthIn(min = width),
    )
}

/** The label column's minimum: what 优先级 needs at the system's own text size. */
private val OrgLabelWidth = 56.dp

/** A labelled row of choice chips, boxed the way the desktop's are: a page of loose chips is a page of floating words. */
@Composable
private fun OrgChoiceRow(label: String, content: @Composable () -> Unit) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OrgFieldLabel(label, width = OrgLabelWidth)
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.sm))
                .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 3.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

/** The search line, revealed by the bar's 🔍 — as the reference app reveals it. */
@Composable
private fun OrgSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalQuireColors.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.card)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = QuireType.ui, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = QuireType.ui.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "关闭搜索", tint = colors.textMuted)
        }
    }
}

/** A board column's ＋: committed on the IME's own button. */
@Composable
private fun OrgQuickAdd(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = LocalQuireColors.current
    val focus = LocalFocusManager.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.card)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = QuireType.ui, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = QuireType.ui.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Sentences,
            ),
            keyboardActions = KeyboardActions(onDone = {
                onSubmit()
                focus.clearFocus()
            }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A field in a form: bare, the way the desktop's `OrgInput` is — an idle field on a
 * touch screen has no border because there is no hover to hint at one.
 *
 * **The write is debounced.** Every one of these commits on a 300 ms settle, which
 * is what keeps a keystroke off the bridge: an organizer edit answers with the
 * whole catalog, and echoing a few hundred rows per character is work nobody
 * reads. `rowKey` is the row's identity, so a reply that replaces the row's fields
 * does not throw the draft away — only moving to another row does.
 */
@Composable
internal fun OrgTextField(
    rowKey: String,
    value: String,
    placeholder: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
    minHeight: Dp = 40.dp,
    textStyle: TextStyle = QuireType.ui,
    focusRequester: FocusRequester? = null,
    onValueChange: ((String) -> Unit)? = null,
) {
    val colors = LocalQuireColors.current
    var draft by remember(rowKey) { mutableStateOf(value) }

    // The row's stored value moved under us — an undo, a merge, another shell:
    // take it, but never clobber what is being typed.
    LaunchedEffect(rowKey, value) { if (value != draft) draft = value }
    LaunchedEffect(rowKey, draft) {
        if (draft == value) return@LaunchedEffect
        delay(ORG_DRAFT_MS)
        onCommit(draft)
    }

    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(Radius.sm))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (draft.isEmpty()) {
            Text(
                text = placeholder,
                style = textStyle,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = if (multiline) 8.dp else 0.dp),
            )
        }
        BasicTextField(
            value = draft,
            onValueChange = {
                draft = it
                onValueChange?.invoke(it)
            },
            textStyle = textStyle.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            maxLines = if (multiline) 12 else 1,
            minLines = if (multiline) 4 else 1,
            keyboardOptions = KeyboardOptions(
                imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                capitalization = KeyboardCapitalization.Sentences,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = if (multiline) 8.dp else 0.dp),
        )
    }
}

/** The list's empty state: what is missing, and the one gesture that fills it. */
@Composable
private fun OrgEmpty(text: String, hint: String) {
    val colors = LocalQuireColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(text, style = QuireType.ui, color = colors.textMuted)
        Text(hint, style = QuireType.caption, color = colors.textMuted.copy(alpha = 0.8f))
    }
}
