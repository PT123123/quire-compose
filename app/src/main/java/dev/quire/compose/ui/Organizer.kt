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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.OrgCatalog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * SPEC §四十一's second top-level area: 笔记 and 任务.
 *
 * The Slint shell draws the desktop's three-column card; a phone has no width to
 * split, so this copy — like the Rust Android shell's — is a **single pane**: the
 * list fills the screen, and tapping a row flips to that row's own form, with the
 * back chevron and the system's back gesture returning. Every decision the
 * desktop's copy made is here: the header with its count and the 列表/平铺 switch,
 * the quick-add line, the footer with the 显示已完成 switch and 已完成 X / Y, the
 * board, and the same detail form.
 *
 * What is deliberately absent, because this is a touch surface: every hover-only
 * affordance, and every keyboard hint. A row is *tapped*, so nothing a hover could
 * reveal is missing — which is why a row's ⋯ is always drawn rather than faded in,
 * and why its target is a finger wide. A long press is the touch vocabulary for
 * "the rest of this row's verbs", the same one the sidebar uses for a page.
 *
 * The projection rules are in [OrgModel]; this file is layout and gesture only.
 */
private const val ORG_DRAFT_MS = 300L

/** The palette's one green Material's scheme does not carry: 低 priority. */
private val OrgSuccess = Color(0xFF3FB950)

/** Whether the area is showing one row's own form rather than the list. */
fun organizerInDetail(vm: QuireViewModel): Boolean =
    if (vm.orgTab == 0) vm.orgNoteSel >= 0 else vm.orgTaskSel >= 0

/** The area's bar: back (in detail) · the two tabs · undo/redo · ＋. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizerBar(vm: QuireViewModel, onOpenDrawer: () -> Unit) {
    val colors = LocalQuireColors.current
    val inDetail = organizerInDetail(vm)
    val canUndo = vm.view?.orgCanUndo == true
    val canRedo = vm.view?.orgCanRedo == true
    val dim = colors.textMuted.copy(alpha = 0.35f)

    TopAppBar(
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                // The tab that is showing stays lit even in a row's own form: the
                // tabs still work there, and an area with neither lit leaves the
                // user guessing which half of the area they are in.
                OrgChip(
                    label = "笔记",
                    selected = vm.orgTab == 0,
                    onClick = { vm.openOrganizer(0) },
                )
                OrgChip(
                    label = "任务",
                    selected = vm.orgTab == 1,
                    onClick = { vm.openOrganizer(1) },
                )
            }
        },
        navigationIcon = {
            if (inDetail) {
                IconButton(onClick = { vm.orgSelectRow(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表", tint = colors.textSecondary)
                }
            } else {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "页面列表", tint = colors.textSecondary)
                }
            }
        },
        actions = {
            // The area's own stack, and the core's own answer about whether a step
            // exists: a step here can never reach a page's edits, and neither may a
            // page's edit light these.
            IconButton(onClick = vm::orgUndo, enabled = canUndo) {
                Icon(IcUndo, contentDescription = "撤销", tint = if (canUndo) colors.textSecondary else dim)
            }
            IconButton(onClick = vm::orgRedo, enabled = canRedo) {
                Icon(IcRedo, contentDescription = "重做", tint = if (canRedo) colors.textSecondary else dim)
            }
            IconButton(onClick = { if (vm.orgTab == 0) vm.orgCreateNote() else vm.orgCreateTask() }) {
                Icon(Icons.Default.Add, contentDescription = "新建", tint = colors.textSecondary)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.textPrimary,
        ),
    )
}

/** The area: either the list or one row's own form. */
@Composable
fun OrganizerScreen(vm: QuireViewModel) {
    val catalog = vm.view?.org ?: OrgCatalog.Empty
    // One clock read for the whole redraw, so 今天's filter and a row's badge
    // cannot straddle midnight and disagree. Re-read whenever the catalog moves,
    // which is every reply.
    val dates = remember(catalog) { OrgModel.Dates.now() }

    if (organizerInDetail(vm)) {
        DetailPane(catalog = catalog, dates = dates, vm = vm)
    } else {
        ListPane(catalog = catalog, dates = dates, vm = vm)
    }
}

// ─── the list ───────────────────────────────────────────────────────────────

@Composable
private fun ListPane(catalog: OrgCatalog, dates: OrgModel.Dates, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val notesTab = vm.orgTab == 0
    val board = !notesTab && vm.orgMode == 1
    val (title, countLabel) = remember(catalog, vm.orgTab, vm.orgView, vm.orgList, vm.orgQuery, vm.orgMode, vm.orgTag) {
        OrgModel.header(
            catalog = catalog,
            tab = vm.orgTab,
            view = vm.orgView,
            list = vm.orgList,
            query = vm.orgQuery,
            mode = vm.orgMode,
            tag = vm.orgTag,
            dates = dates,
        )
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // The header: what is showing, how many, and the two ways of looking at it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.md, top = Spacing.sm)
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = title,
                style = QuireType.ui.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(countLabel, style = QuireType.caption, color = colors.textMuted, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (!notesTab) {
                OrgSegmented(labels = listOf("列表", "平铺"), selected = vm.orgMode, onPick = vm::orgPickMode)
            }
        }

        // One needle per tab: the tab that is not showing does not read it.
        OrgSearchField(
            value = vm.orgQuery,
            placeholder = if (notesTab) "搜索笔记" else "搜索任务",
            onValueChange = vm::orgSetQuery,
        )

        if (notesTab) {
            NoteTagChips(catalog = catalog, selected = vm.orgTag, onPick = vm::orgPickTag)
            NoteListPane(catalog = catalog, vm = vm)
        } else {
            SmartChips(catalog = catalog, dates = dates, vm = vm)
            ListChips(catalog = catalog, vm = vm)
            SortChips(vm = vm)
            if (board) {
                BoardPane(catalog = catalog, dates = dates, vm = vm)
            } else {
                TaskListPane(catalog = catalog, dates = dates, vm = vm)
            }
        }
    }
}

/** 全部笔记, then the tags the notes carry. */
@Composable
private fun NoteTagChips(catalog: OrgCatalog, selected: String, onPick: (String) -> Unit) {
    val chips = remember(catalog) { OrgModel.tagChips(catalog) }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        contentPadding = PaddingValues(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all-notes") {
            OrgChip(label = "全部笔记", selected = selected.isEmpty(), onClick = { onPick("") })
        }
        items(chips, key = { "tag-${it.name}" }) { chip ->
            OrgChip(
                label = "${chip.name} ${chip.count}",
                selected = selected == chip.name,
                onClick = { onPick(chip.name) },
            )
        }
    }
}

/** The five smart views, with their counts. */
@Composable
private fun SmartChips(catalog: OrgCatalog, dates: OrgModel.Dates, vm: QuireViewModel) {
    val counts = remember(catalog, vm.orgQuery) { OrgModel.smartCounts(catalog, vm.orgQuery, dates) }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        contentPadding = PaddingValues(horizontal = Spacing.md),
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
        contentPadding = PaddingValues(horizontal = Spacing.md),
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

@Composable
private fun SortChips(vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text("排序", style = QuireType.caption, color = colors.textMuted, modifier = Modifier.widthIn(min = 44.dp))
        for ((index, name) in OrgModel.sortNames.withIndex()) {
            OrgChip(label = name, small = true, selected = vm.orgSort == index, onClick = { vm.orgPickSort(index) })
        }
    }
}

/** The 笔记 rows. */
@Composable
private fun NoteListPane(catalog: OrgCatalog, vm: QuireViewModel) {
    val rows = remember(catalog, vm.orgQuery, vm.orgTag, vm.orgNoteSel) {
        OrgModel.notes(catalog, vm.orgQuery, vm.orgTag, vm.orgNoteSel)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        items(rows, key = { it.id }) { row ->
            NoteRowView(row = row, onClick = { vm.orgSelectNote(row.id) })
        }
        if (rows.isEmpty()) {
            item(key = "empty") { OrgEmpty(text = "还没有笔记", hint = "点右上角的 ＋ 写一条") }
        }
    }
}

/** The 任务 rows: the quick-add line, the list, and the footer. */
@Composable
private fun TaskListPane(catalog: OrgCatalog, dates: OrgModel.Dates, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val rows = remember(catalog, vm.orgView, vm.orgList, vm.orgQuery, vm.orgSort, vm.orgShowDone, vm.orgTaskSel) {
        OrgModel.tasks(
            catalog = catalog,
            view = vm.orgView,
            list = vm.orgList,
            query = vm.orgQuery,
            sort = vm.orgSort,
            selected = vm.orgTaskSel,
            showDone = vm.orgShowDone,
            dates = dates,
        )
    }
    val (done, total) = remember(catalog) { OrgModel.progress(catalog) }
    val scope = remember(catalog, vm.orgView, vm.orgList) { OrgModel.scopeName(catalog, vm.orgView, vm.orgList) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var quickAdd by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        OrgQuickAdd(
            value = quickAdd,
            placeholder = "添加任务到「$scope」",
            onValueChange = { quickAdd = it },
            onSubmit = {
                if (quickAdd.isNotBlank()) {
                    vm.orgQuickAdd(quickAdd, dates.today)
                    quickAdd = ""
                }
            },
        )

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = Spacing.sm)) {
            items(rows, key = { it.id }) { row ->
                TaskRowView(
                    row = row,
                    today = dates.today,
                    onToggle = { vm.orgToggleTaskDone(row.id, !row.done) },
                    onClick = { vm.orgSelectTask(row.id) },
                    onMenu = { menuFor = row.id },
                )
            }
            if (rows.isEmpty()) {
                item(key = "empty") { OrgEmpty(text = "这里没有任务", hint = "在上面那一行写一条") }
            }
        }

        // The footer: the switch that reveals the finished ones, and the area's
        // progress over the whole catalog rather than over the filtered view.
        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OrgChip(
                label = (if (vm.orgShowDone) "隐藏已完成 (" else "显示已完成 (") + "$done)",
                selected = vm.orgShowDone,
                onClick = vm::orgToggleShowDone,
            )
            Spacer(Modifier.weight(1f))
            Text("已完成 $done / $total", style = QuireType.caption, color = colors.textMuted)
        }
    }

    val menuRow = menuFor?.let { id -> catalog.tasks.firstOrNull { it.id == id } }
    if (menuRow != null) {
        OrgTaskMenuSheet(
            row = OrgModel.taskRow(catalog, menuRow, dates, selected = false),
            catalog = catalog,
            onDismiss = { menuFor = null },
            vm = vm,
        )
    } else if (menuFor != null) {
        LaunchedEffect(menuFor) { menuFor = null }
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
private fun BoardPane(catalog: OrgCatalog, dates: OrgModel.Dates, vm: QuireViewModel) {
    val columns = remember(catalog, vm.orgQuery, vm.orgSort) {
        OrgModel.board(catalog, vm.orgQuery, vm.orgSort, dates)
    }
    // Column boxes in *root* coordinates — the same space the finger is tracked
    // in, so a card in a horizontally scrolled board still finds its column.
    val bounds = remember { mutableMapOf<Long, Rect>() }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<Drag?>(null) }
    var hovered by remember { mutableStateOf<Long?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { boardOrigin = it.positionInRoot() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md),
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
                    onCardMenu = { menuFor = it },
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

    val menuCard = menuFor?.let { id -> catalog.tasks.firstOrNull { it.id == id } }
    if (menuCard != null) {
        OrgTaskMenuSheet(
            row = OrgModel.taskRow(catalog, menuCard, dates, selected = false),
            catalog = catalog,
            onDismiss = { menuFor = null },
            vm = vm,
        )
    } else if (menuFor != null) {
        LaunchedEffect(menuFor) { menuFor = null }
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
            .background(if (hovered) colors.accentSoft else colors.surfaceHover.copy(alpha = 0.3f))
            .border(
                width = 1.dp,
                color = if (hovered) colors.accent else colors.border,
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
                    .size(9.dp)
                    .clip(RoundedCornerShape(3.dp))
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

// ─── the detail: one row's own form ─────────────────────────────────────────

@Composable
private fun DetailPane(catalog: OrgCatalog, dates: OrgModel.Dates, vm: QuireViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(bottom = 48.dp),
    ) {
        if (vm.orgTab == 0) {
            val row = OrgModel.noteDetail(catalog, vm.orgNoteSel)
            if (row != null) NoteDetail(row = row, vm = vm) else DetailGone(vm)
        } else {
            val row = OrgModel.taskDetail(catalog, vm.orgTaskSel, dates)
            if (row != null) TaskDetail(row = row, catalog = catalog, dates = dates, vm = vm) else DetailGone(vm)
        }
    }
}

/** The row a reply took away (deleted here, or undone away): go back to the list. */
@Composable
private fun DetailGone(vm: QuireViewModel) {
    LaunchedEffect(Unit) { vm.orgSelectRow(-1) }
}

@Composable
private fun NoteDetail(row: OrgModel.NoteRow, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val focus = LocalFocusManager.current
    val titleFocus = remember { FocusRequester() }

    // A note the ＋ just made: the caret belongs in its title, not in a list of
    // one untitled row.
    LaunchedEffect(row.id) {
        if (vm.orgFocus == row.id) {
            titleFocus.requestFocus()
            vm.consumeOrgFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OrgTextField(
                rowKey = "note-title-${row.id}",
                value = row.title,
                placeholder = "标题",
                textStyle = QuireType.h3,
                focusRequester = titleFocus,
                onCommit = { vm.orgNoteTitle(row.id, it) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.orgToggleNotePinned(row.id, !row.pinned) }) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (row.pinned) "取消置顶" else "置顶",
                    tint = if (row.pinned) colors.accentText else colors.textMuted,
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
                rowKey = "note-tags-${row.id}",
                value = row.tags,
                placeholder = "用逗号分隔",
                onCommit = { vm.orgNoteTags(row.id, it) },
                modifier = Modifier.weight(1f),
            )
        }

        Text(row.whenText, style = QuireType.caption, color = colors.textMuted)

        OrgTextField(
            rowKey = "note-body-${row.id}",
            value = row.body,
            placeholder = "正文",
            multiline = true,
            minHeight = 280.dp,
            onCommit = { vm.orgNoteBody(row.id, it) },
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(
            onClick = {
                focus.clearFocus()
                vm.orgDeleteNote(row.id)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("删除笔记", color = colors.danger)
        }
    }
}

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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp),
        ) {
            OrgSheetHeader(row.title.ifEmpty { "无标题" })
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
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            OrgSheetItem("删除任务", danger = true) {
                onDismiss()
                vm.orgDeleteTask(row.id)
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
private fun OrgSheetHeader(label: String) {
    Text(
        text = label,
        style = QuireType.caption,
        color = LocalQuireColors.current.textMuted,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun OrgSheetItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
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
        Text(label, style = QuireType.ui, color = if (danger) colors.danger else colors.textPrimary)
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

// ─── the small pieces ───────────────────────────────────────────────────────

/**
 * One tappable chip: a tab, a smart view, a sort, a list, a priority.
 * `selected` is the one lit state the whole area uses.
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .height(if (small) 30.dp else 34.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (selected) colors.surfaceSelected else colors.background)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = if (colorSlot != null && colorSlot > 0) Spacing.sm else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (colorSlot != null && colorSlot > 0) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(blockTextColor(colorSlot, colors.isDark)),
            )
        }
        Text(
            text = label,
            style = if (small) QuireType.caption else QuireType.ui,
            color = when {
                selected -> colors.textPrimary
                accent -> colors.accentText
                else -> colors.textSecondary
            },
            maxLines = 1,
        )
    }
}

/** The 列表/平铺 switch: two cells in one bordered box. */
@Composable
private fun OrgSegmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .widthIn(min = 104.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm)),
    ) {
        for ((index, label) in labels.withIndex()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected == index) colors.surfaceSelected else colors.background)
                    .clickable { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = QuireType.caption,
                    color = if (selected == index) colors.textPrimary else colors.textMuted,
                )
            }
        }
    }
}

/** The checkbox in front of a task and a checklist line. */
@Composable
private fun OrgCheck(checked: Boolean, round: Boolean = false, size: Dp = 18.dp, onToggle: () -> Unit) {
    val colors = LocalQuireColors.current
    val shape = RoundedCornerShape(if (round) size / 2 else Radius.sm)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (checked) colors.accent else colors.background)
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
            else -> colors.textMuted
        },
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (overdue) colors.danger.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (overdue) colors.danger else colors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        maxLines = 1,
    )
}

/** One note as the list paints it: three lines, pinned first. */
@Composable
private fun NoteRowView(row: OrgModel.NoteRow, onClick: () -> Unit) {
    val colors = LocalQuireColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = 1.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (row.selected) colors.surfaceSelected else colors.background)
            .clickable(onClick = onClick)
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (row.pinned) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "已置顶",
                    tint = colors.accentText,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = row.title.ifEmpty { "无标题" },
                style = QuireType.ui.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = row.excerpt,
            style = QuireType.caption,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OrgMeta(tags = row.tags, whenText = row.whenText)
    }
}

/** A row's tags and its age, on one muted line. */
@Composable
private fun OrgMeta(tags: String, whenText: String) {
    if (tags.isEmpty() && whenText.isEmpty()) return
    Text(
        text = when {
            tags.isNotEmpty() && whenText.isNotEmpty() -> "$tags · $whenText"
            tags.isNotEmpty() -> tags
            else -> whenText
        },
        style = QuireType.caption,
        color = LocalQuireColors.current.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One task as the list paints it. A finger needs the two lines to fit. */
@Composable
private fun TaskRowView(
    row: OrgModel.TaskRow,
    today: String,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = Spacing.sm, vertical = 1.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (row.selected) colors.surfaceSelected else colors.background)
            .clickable(onClick = onClick)
            .padding(start = Spacing.sm, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OrgCheck(checked = row.done, round = true, size = 20.dp, onToggle = onToggle)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = row.title.ifEmpty { "无标题" },
                style = QuireType.ui,
                color = if (row.done) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The pills are only drawn when there is something to put in them: a
            // list that is not there yet must not cost a line of every row.
            if (row.list > 0 || row.subtasksTotal > 0) {
                Row(
                    modifier = Modifier.height(19.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (row.list > 0) {
                        OrgPill(text = row.listName, tint = blockTextColor(row.listColor, colors.isDark))
                    }
                    if (row.subtasksTotal > 0) {
                        Text(
                            "☑ ${row.subtasksDone}/${row.subtasksTotal}",
                            style = QuireType.caption,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
        OrgPrio(row.priority)
        OrgDue(label = row.dueLabel, overdue = row.overdue, today = row.due == today)
        // Always drawn, never faded in: a finger cannot hover, so a ⋯ that only a
        // mouse reveals would be a menu no phone can open.
        IconButton(onClick = onMenu, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = colors.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** A tag (or a list's name) as a pill. */
@Composable
private fun OrgPill(text: String, tint: Color) {
    Text(
        text = text,
        style = QuireType.caption,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(LocalQuireColors.current.surfaceSelected)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        maxLines = 1,
    )
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
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in tags) OrgPill(text = tag, tint = colors.textSecondary)
            }
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

/** The bordered search line. */
@Composable
private fun OrgSearchField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val colors = LocalQuireColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The quick-add line and a board column's ＋: committed on the IME's own button. */
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
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .border(1.dp, colors.border, RoundedCornerShape(Radius.sm))
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
            // The one input in the area that is *not* a debounced draft: a created
            // row is a step, and half a row is not.
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
private fun OrgTextField(
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(text, style = QuireType.ui, color = colors.textMuted)
        Text(hint, style = QuireType.caption, color = colors.textMuted.copy(alpha = 0.8f))
    }
}
