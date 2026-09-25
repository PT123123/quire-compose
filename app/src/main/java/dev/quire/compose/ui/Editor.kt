package dev.quire.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.BlockRow
import dev.quire.compose.bridge.View
import kotlinx.coroutines.delay

/** How long a title burst waits before it becomes one write. */
private const val TITLE_DEBOUNCE_MS = 350L

/**
 * The document: the page's title and its blocks.
 *
 * One `LazyColumn` for the whole page rather than a scrollable box per block: a
 * page can hold thousands of rows, and this is the same decision the Slint shell
 * made (ADR-0003) — only what is on screen costs anything.
 */
@Composable
fun EditorScreen(
    view: View,
    vm: QuireViewModel,
    onBlockMenu: (Long) -> Unit,
) {
    val colors = LocalQuireColors.current
    val listState = rememberLazyListState()
    val numbers = remember(view.blocks) { numberedLabels(view.blocks) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 140.dp),
    ) {
        item(key = "page-header") {
            PageHeader(view = view, vm = vm)
        }
        items(view.blocks, key = { it.id }) { block ->
            BlockRowView(
                row = block,
                numberLabel = numbers[block.id],
                editable = !view.locked,
                pendingFocus = vm.pendingFocus == block.id,
                onFocusConsumed = vm::consumeFocus,
                onTextChange = { vm.setBlockText(block.id, it) },
                onEnter = { vm.insertBlockAfter(block.id) },
                onToggleChecked = { vm.toggleChecked(block.id) },
                onToggleFold = { vm.toggleFold(block.id) },
                onOpenMenu = { onBlockMenu(block.id) },
                onOpenPage = vm::openPage,
            )
        }
        item(key = "append") {
            AppendRow(empty = view.blocks.isEmpty(), enabled = !view.locked, onAppend = vm::appendBlock)
        }
        if (view.locked) {
            item(key = "locked") {
                Text(
                    text = "此页面为只读",
                    style = QuireType.caption,
                    color = colors.textMuted,
                    modifier = Modifier.padding(start = 18.dp, top = 8.dp),
                )
            }
        }
    }
}

/**
 * The page's icon, its title, and the read-only banner.
 *
 * The title is held locally and written on a debounce: every keystroke would
 * otherwise be a transaction (a rename applies immediately — it is page
 * structure, not document content) and would echo the whole page back. 350 ms is
 * roughly one word, so a burst of typing costs one write.
 */
@Composable
private fun PageHeader(view: View, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    val focus = LocalFocusManager.current
    val pageId = view.activePage ?: return

    var title by remember(pageId) { mutableStateOf(view.title) }
    var dirty by remember(pageId) { mutableStateOf(false) }

    LaunchedEffect(pageId, view.title) {
        if (!dirty && view.title != title) title = view.title
    }
    LaunchedEffect(pageId, title, dirty) {
        if (!dirty) return@LaunchedEffect
        delay(TITLE_DEBOUNCE_MS)
        vm.renamePage(pageId, title)
        dirty = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
    ) {
        if (view.icon.isNotEmpty()) {
            Text(view.icon, fontSize = 30.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        BasicTextField(
            value = title,
            onValueChange = {
                title = it
                dirty = true
            },
            enabled = !view.locked,
            textStyle = QuireType.pageTitle.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Sentences,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The row under the document, which is also its empty state.
 *
 * Tapping it appends a paragraph; the whole strip is the target, because on a
 * page whose last block is at the bottom of the screen, "tap somewhere empty to
 * keep writing" is the gesture people already have.
 */
@Composable
private fun AppendRow(empty: Boolean, enabled: Boolean, onAppend: () -> Unit) {
    val colors = LocalQuireColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (empty) 160.dp else 72.dp)
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(Radius.md))
            .clickable(enabled = enabled) { onAppend() },
        contentAlignment = if (empty) Alignment.Center else Alignment.TopStart,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = if (empty) 0.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("＋", style = QuireType.ui, color = colors.textMuted)
            Text(
                text = if (empty) "点这里开始写" else "添加块",
                style = QuireType.ui,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * The number shown beside a numbered row.
 *
 * A run is counted per parent *and* depth, and any other kind of row at the same
 * place ends it — which is what makes two separate lists both start at 1 and a
 * paragraph in the middle of one restart it, without the core storing a number
 * anywhere. The desktop derives it in Rust from the same rows.
 */
private fun numberedLabels(blocks: List<BlockRow>): Map<Long, String> {
    val counters = HashMap<Pair<Long?, Int>, Int>()
    val labels = HashMap<Long, String>()
    for (block in blocks) {
        val key = block.parent to block.depth
        if (block.kind == "numbered") {
            val next = (counters[key] ?: 0) + 1
            counters[key] = next
            labels[block.id] = "$next."
        } else {
            counters.remove(key)
        }
    }
    return labels
}
