package dev.quire.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.View
import kotlinx.coroutines.launch

/**
 * The root: the palette, the startup state, and — once the library is open — the
 * shell.
 *
 * The theme wraps everything including the startup screen, so a library that
 * fails to open still opens in the user's own colours instead of a flash of
 * Material purple.
 */
@Composable
fun QuireApp(vm: QuireViewModel) {
    QuireTheme(theme = vm.view?.theme ?: "system") {
        val colors = LocalQuireColors.current
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            val view = vm.view
            if (view == null) {
                StartupScreen(error = vm.error)
            } else {
                Shell(view = view, vm = vm)
            }
        }
    }
}

/** What the app shows while the library opens, or if it cannot. */
@Composable
private fun StartupScreen(error: String?) {
    val colors = LocalQuireColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error == null) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                Text("正在打开资料库…", style = QuireType.ui, color = colors.textMuted)
            } else {
                Text("资料库打不开", style = QuireType.h3, color = colors.textPrimary)
                Text(error, style = QuireType.caption, color = colors.textMuted)
                Text(
                    text = "如果提示找不到 native 库，说明 APK 里没有 libquire_bridge.so —— " +
                        "先跑一次 `just android-apk`，它会用 cargo-ndk 把桥编译出来。",
                    style = QuireType.caption,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/**
 * Which destination is on screen.
 *
 * Four, because SPEC §四十一's second and third are *separate pages* in the
 * reference app — 收件箱 and 任务 are two drawer entries with two toolbars, not two
 * tabs on one page — and because the document is not destroyed by leaving it: a
 * page opened on the way into 笔记 is still open on the way back (ADR-0013).
 * **同步** joins them as the fourth for the same reason: it is a page with a device
 * list and its own settings in the app this shell is ported from, and it is where
 * this device is put on the LAN.
 */
private enum class Area { Pages, Notes, Tasks, Sync }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(view: View, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val drawerFocus = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    // One id per open sheet/dialog rather than the row object: a reply can
    // replace every row, and a sheet holding a stale copy would show stale
    // values — and act on a row that no longer exists.
    var blockMenuFor by remember { mutableStateOf<Long?>(null) }
    var pageMenuFor by remember { mutableStateOf<Long?>(null) }
    var renameFor by remember { mutableStateOf<Long?>(null) }
    var deleteFor by remember { mutableStateOf<Long?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    // 收件箱 is home (ADR-0015): the app opens there, and it is where 任务 and the
    // document both return to. `openNotes()` runs once so a cold launch lands on a
    // clean inbox rather than on whatever filter a previous window left behind.
    var area by remember { mutableStateOf(Area.Notes) }
    LaunchedEffect(Unit) { vm.openNotes() }

    // The back gesture, in the order a touch user expects: close the capture
    // overlay, leave the open row's form, then leave the destination — and both
    // 任务 and the document go **home** to 收件箱 rather than out of the app. Only
    // 收件箱 itself hands the gesture to the system, which is what makes it the
    // bottom of the stack.
    BackHandler(enabled = area != Area.Notes || vm.orgCompose != QuireViewModel.Compose.Closed) {
        when {
            vm.orgCompose != QuireViewModel.Compose.Closed -> vm.orgCloseComposer()
            area == Area.Tasks && organizerInDetail(vm) -> vm.orgSelectRow(-1)
            area == Area.Tasks -> area = Area.Notes
            area == Area.Pages -> area = Area.Notes
            area == Area.Sync -> area = Area.Notes
            else -> Unit
        }
    }

    // Opening the drawer drops the page's focus and, with it, the IME. The inbox
    // search field and the task-detail fields ask for the caret on their own, so
    // without this a keystroke aimed at the sidebar lands in the page behind it.
    // It lives in `Shell` rather than a page for that reason: every destination,
    // now and later, is covered by the one effect.
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) return@LaunchedEffect
        focusManager.clearFocus(force = true)
        runCatching { drawerFocus.requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = colors.sidebar,
                drawerContentColor = colors.textPrimary,
            ) {
                Sidebar(
                    view = view,
                    vm = vm,
                    onPageMenu = { pageMenuFor = it },
                    // 收件箱 is home, so the page tree is the way *into* the
                    // document: opening a row closes the drawer and switches the
                    // area, which is the gap the old tree left (it called
                    // `openPage` and stayed on 收件箱).
                    onOpenPage = { id ->
                        scope.launch { drawerState.close() }
                        area = Area.Pages
                        vm.openPage(id)
                    },
                    onNewPage = {
                        scope.launch { drawerState.close() }
                        area = Area.Pages
                        vm.createPage(parent = null)
                    },
                    onOpenNotes = {
                        scope.launch { drawerState.close() }
                        vm.openNotes()
                        area = Area.Notes
                    },
                    onOpenTasks = {
                        scope.launch { drawerState.close() }
                        vm.openTasks()
                        area = Area.Tasks
                    },
                    onOpenSync = {
                        scope.launch { drawerState.close() }
                        area = Area.Sync
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        settingsOpen = true
                    },
                    firstRowFocus = drawerFocus,
                )
            }
        },
    ) {
        Scaffold(
            containerColor = colors.background,
            snackbarHost = {
                // Lifted clear of the ＋: a bar over the button that opens capture
                // would hide the one control the inbox is built around.
                SnackbarHost(snackbarHostState, Modifier.padding(bottom = 72.dp))
            },
            topBar = {
                when (area) {
                    Area.Notes -> NotesBar(vm = vm, onOpenDrawer = { scope.launch { drawerState.open() } })
                    Area.Tasks -> TasksBar(vm = vm, onOpenDrawer = { scope.launch { drawerState.open() } })
                    Area.Sync -> SyncBar(vm = vm, onOpenDrawer = { scope.launch { drawerState.open() } })
                    Area.Pages -> TopBar(
                        view = view,
                        vm = vm,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                vm.notice?.let { NoticeBar(it, vm::dismissNotice) }
                vm.error?.let { ErrorBar(it, vm::dismissError) }
                when (area) {
                    Area.Notes -> NotesPage(vm = vm)
                    Area.Tasks -> TasksPage(vm = vm)
                    Area.Sync -> SyncPage(vm = vm)
                    Area.Pages -> EditorScreen(view = view, vm = vm, onBlockMenu = { blockMenuFor = it })
                }
            }
        }
    }

    // The capture overlay is one overlay for both destinations, because it is one
    // gesture: ＋ opens it, ➤ closes it, and a tap on the scrim loses nothing. It
    // is drawn over the whole shell rather than inside a destination so its field
    // can raise the IME on the frame it appears (ADR-0015).
    if (area != Area.Pages && vm.orgCompose != QuireViewModel.Compose.Closed) {
        CaptureOverlay(vm = vm, onDismiss = vm::orgCloseComposer)
    }

    // The delete's 撤销 bar. One bar, shown while a deferred delete is pending and
    // dropped the instant it commits or is undone. `Indefinite` on purpose: the
    // three seconds are the view model's, and two clocks would disagree about the
    // same delete.
    val pendingDelete = vm.pendingDelete
    LaunchedEffect(pendingDelete?.token) {
        if (pendingDelete == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = pendingDelete.message,
            actionLabel = "撤销",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoPendingDelete()
    }
    LaunchedEffect(pendingDelete) {
        if (pendingDelete == null) snackbarHostState.currentSnackbarData?.dismiss()
    }

    val menuBlock = blockMenuFor?.let { id -> view.blocks.firstOrNull { it.id == id } }
    if (menuBlock != null) {
        BlockMenuSheet(block = menuBlock, vm = vm, onDismiss = { blockMenuFor = null })
    } else if (blockMenuFor != null) {
        // The block is gone (deleted here, or undone away): close the sheet.
        LaunchedEffect(blockMenuFor) { blockMenuFor = null }
    }

    val menuPage = pageMenuFor?.let { id -> view.pages.firstOrNull { it.id == id } }
    if (menuPage != null) {
        PageMenuSheet(
            page = menuPage,
            onDismiss = { pageMenuFor = null },
            onCreateChild = {
                vm.createPage(parent = menuPage.id)
                pageMenuFor = null
            },
            onRename = {
                pageMenuFor = null
                renameFor = menuPage.id
            },
            onDelete = {
                pageMenuFor = null
                deleteFor = menuPage.id
            },
            vm = vm,
        )
    } else if (pageMenuFor != null) {
        LaunchedEffect(pageMenuFor) { pageMenuFor = null }
    }

    val renamePage = renameFor?.let { id -> view.pages.firstOrNull { it.id == id } }
    if (renamePage != null) {
        RenameDialog(
            initial = renamePage.title,
            onDismiss = { renameFor = null },
            onConfirm = { title ->
                vm.renamePage(renamePage.id, title)
                renameFor = null
            },
        )
    } else if (renameFor != null) {
        LaunchedEffect(renameFor) { renameFor = null }
    }

    val deletePage = deleteFor?.let { id -> view.pages.firstOrNull { it.id == id } }
    if (deletePage != null) {
        ConfirmDialog(
            title = "删除页面",
            message = "「${deletePage.title.ifEmpty { "无标题" }}」" +
                if (deletePage.hasChildren) "及其全部子页面都会被删除，无法撤销。" else "会被删除，无法撤销。",
            onDismiss = { deleteFor = null },
            onConfirm = {
                vm.deletePage(deletePage.id)
                deleteFor = null
            },
        )
    } else if (deleteFor != null) {
        LaunchedEffect(deleteFor) { deleteFor = null }
    }

    if (settingsOpen) {
        SettingsSheet(view = view, vm = vm, onDismiss = { settingsOpen = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(view: View, vm: QuireViewModel, onOpenDrawer: () -> Unit) {
    val colors = LocalQuireColors.current
    TopAppBar(
        title = {
            Text(
                text = view.title.ifEmpty { "Quire" },
                style = QuireType.ui,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "页面列表", tint = colors.textSecondary)
            }
        },
        actions = {
            // The two buttons are the only keyboard-free way to reach undo on a
            // phone, and `canUndo` is the core's own answer rather than a local
            // guess — see the note on the bridge's reply.
            IconButton(onClick = vm::undo, enabled = view.canUndo) {
                Icon(
                    imageVector = IcUndo,
                    contentDescription = "撤销",
                    tint = if (view.canUndo) colors.textSecondary else colors.textMuted.copy(alpha = 0.35f),
                )
            }
            IconButton(onClick = vm::redo, enabled = view.canRedo) {
                Icon(
                    imageVector = IcRedo,
                    contentDescription = "重做",
                    tint = if (view.canRedo) colors.textSecondary else colors.textMuted.copy(alpha = 0.35f),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.textPrimary,
        ),
    )
}

/**
 * A note from the core, shown once: the library was recovered from a backup, or
 * moved. Dismissing it is the only thing it needs — the alternative is being
 * asked about it again on every reply.
 */
@Composable
private fun NoticeBar(message: String, onDismiss: () -> Unit) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentSoft)
            .padding(start = 16.dp, end = 4.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = QuireType.caption,
            color = colors.accentText,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "知道了", tint = colors.accentText, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.calloutBackground)
            .padding(start = 16.dp, end = 4.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = QuireType.caption, color = colors.danger, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = colors.danger, modifier = Modifier.size(18.dp))
        }
    }
}
