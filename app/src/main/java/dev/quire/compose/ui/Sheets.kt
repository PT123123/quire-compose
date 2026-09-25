package dev.quire.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.BlockRow
import dev.quire.compose.bridge.PageRow
import dev.quire.compose.bridge.View

/**
 * The block's menu: what it is, and what can be done to it.
 *
 * A bottom sheet rather than a floating popup, because a popup anchored to a row
 * near the bottom of a phone would open off-screen, and because the sheet is the
 * platform's own answer for "a list of verbs about this thing".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockMenuSheet(block: BlockRow, vm: QuireViewModel, onDismiss: () -> Unit) {
    val colors = LocalQuireColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            SheetHeader(BlockKinds.label(block.kind))
            for ((kind, label) in BlockKinds.picker) {
                SheetItem(
                    label = label,
                    selected = kind == block.kind,
                    // The sheet stays open: trying three kinds in a row is one
                    // gesture, and a sheet that closes on every try makes it
                    // three.
                    onClick = { vm.setBlockKind(block.id, kind) },
                )
            }

            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            SheetHeader("排列")
            SheetItem("上移", onClick = { vm.moveBlock(block.id, -1) })
            SheetItem("下移", onClick = { vm.moveBlock(block.id, 1) })
            SheetItem("缩进", onClick = { vm.indentList(block.id) })
            SheetItem("取消缩进", onClick = { vm.outdentList(block.id) })
            SheetItem("在下方插入块", onClick = {
                vm.insertBlockAfter(block.id)
                onDismiss()
            })

            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            SheetItem(
                label = "删除",
                danger = true,
                onClick = {
                    vm.deleteBlock(block.id)
                    onDismiss()
                },
            )
        }
    }
}

/** The page's menu, opened by a long press on its row in the drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageMenuSheet(
    page: PageRow,
    onDismiss: () -> Unit,
    onCreateChild: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    vm: QuireViewModel,
) {
    val colors = LocalQuireColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetHeader(page.title.ifEmpty { "无标题" })
            SheetItem("新建子页面", onClick = onCreateChild)
            SheetItem("重命名", onClick = onRename)
            SheetItem(
                label = if (page.favorite) "取消收藏" else "收藏",
                onClick = { vm.toggleFavorite(page.id) },
            )
            SheetItem(
                label = if (page.locked) "取消只读" else "设为只读",
                onClick = { vm.setPageLocked(page.id, !page.locked) },
            )
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            SheetItem(label = "删除", danger = true, onClick = onDelete)
        }
    }
}

/** Settings: global switches, kept off the main surface on purpose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(view: View, vm: QuireViewModel, onDismiss: () -> Unit) {
    val colors = LocalQuireColors.current
    val themes = listOf(
        "system" to "跟随系统",
        "light" to "浅色",
        "dark" to "深色",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            SheetHeader("外观")
            for ((value, label) in themes) {
                SheetItem(
                    label = label,
                    selected = view.theme == value,
                    onClick = { vm.setTheme(value) },
                )
            }

            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            SheetHeader("当前页面")
            SheetItem(
                label = "只读",
                onClick = { view.activePage?.let { id -> vm.setPageLocked(id, !view.locked) } },
                trailing = {
                    Switch(
                        checked = view.locked,
                        onCheckedChange = { view.activePage?.let { id -> vm.setPageLocked(id, it) } },
                        enabled = view.activePage != null,
                    )
                },
            )

            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            SheetHeader("关于")
            Text(
                text = "Quire 的原生 Android 外壳：Kotlin + Jetpack Compose 画界面，" +
                    "quire-core 管数据。资料库与 Rust 外壳共用同一份 quire.db。",
                style = QuireType.caption,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                text = "${view.pageCount} 个页面 · 主题 ${view.theme}",
                style = QuireType.caption,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
    }
}

/** Rename one page. The keyboard opens with the caret where it matters. */
@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名页面") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                modifier = Modifier.fillMaxWidth().imePadding(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "删除",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalQuireColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = colors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ─── the sheet's own furniture ───────────────────────────────────────────────

@Composable
private fun SheetHeader(label: String) {
    Text(
        text = label,
        style = QuireType.caption,
        color = LocalQuireColors.current.textMuted,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp),
    )
}

/**
 * One line of a sheet. A 52 dp row with a 48 dp-tall tap target inside it — the
 * platform's own minimum, which a 44 dp row would miss on the first try.
 */
@Composable
private fun SheetItem(
    label: String,
    selected: Boolean = false,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .heightIn(min = 52.dp)
            .background(if (selected) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = QuireType.ui,
            color = when {
                danger -> colors.danger
                selected -> colors.accentText
                else -> colors.textPrimary
            },
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        trailing?.invoke()
        Spacer(Modifier.width(4.dp))
    }
}
