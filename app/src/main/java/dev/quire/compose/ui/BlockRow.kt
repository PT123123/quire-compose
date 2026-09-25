package dev.quire.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.quire.compose.bridge.BlockRow

/**
 * One editor row.
 *
 * Two things here are shaped by the platform rather than by taste:
 *
 * * **The handle follows selection, never hover.** A finger cannot hover, so the
 *   block menu's door is the ⋮ that appears in the gutter once the row is
 *   focused — tap a block, the handle shows up, tap the handle. A long press
 *   cannot be it: inside a text field a long press is how you select text, and a
 *   control that competes with the platform's own gesture loses.
 * * **Enter is the IME action, not a newline.** The field is multiline (a
 *   paragraph has to be able to wrap on a 360 dp screen) but its IME action is
 *   "next", so the keyboard's return key asks for the next block, which is what
 *   a block editor is. A hardware keyboard still inserts a real newline, because
 *   that is what a hardware keyboard means.
 */
@Composable
fun BlockRowView(
    row: BlockRow,
    numberLabel: String?,
    editable: Boolean,
    pendingFocus: Boolean,
    onFocusConsumed: () -> Unit,
    onTextChange: (String) -> Unit,
    onEnter: () -> Unit,
    onToggleChecked: () -> Unit,
    onToggleFold: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenPage: (Long) -> Unit,
) {
    val colors = LocalQuireColors.current
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(pendingFocus) {
        if (pendingFocus && editable) {
            focusRequester.requestFocus()
            onFocusConsumed()
        }
    }

    val indent = (row.depth * BlockKinds.indentPerDepth).dp
    val textStyle = BlockKinds.textStyle(row.kind).copy(
        color = blockTextColor(row.color, colors.isDark),
        textDecoration = if (row.checked) TextDecoration.LineThrough else null,
    )
    val rowBackground = blockBackgroundColor(row.background, colors.isDark)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .padding(start = 6.dp + indent, end = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Gutter(
            row = row,
            numberLabel = numberLabel,
            showHandle = editable && (focused || !row.isTextual),
            onOpenMenu = onOpenMenu,
            onToggleChecked = onToggleChecked,
            onToggleFold = onToggleFold,
        )

        if (row.kind == "divider") {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp).weight(1f),
                color = colors.divider,
            )
            return@Row
        }

        if (BlockKinds.isPlaceholder(row.kind)) {
            PlaceholderBody(
                row = row,
                modifier = Modifier.weight(1f),
                onOpenPage = onOpenPage,
                onOpenMenu = onOpenMenu,
            )
            return@Row
        }

        var text by remember(row.id) { mutableStateOf(row.text) }
        // The model can move under a row without this row being typed into —
        // undo, a redo, another shell on the same library. Re-syncing on a
        // difference is what keeps those honest, and it does not fight typing:
        // a keystroke is already the stored value by the time a view comes back.
        LaunchedEffect(row.id, row.text) {
            if (row.text != text) text = row.text
        }

        Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
            if (row.kind == "code" && row.lang.isNotEmpty()) {
                Text(row.lang, style = QuireType.caption, color = colors.textMuted)
            }
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onTextChange(it)
                },
                enabled = editable,
                textStyle = textStyle,
                cursorBrush = SolidColor(colors.accent),
                maxLines = 24,
                visualTransformation = remember(row.marks, colors) {
                    MarksTransformation(
                        marks = row.marks,
                        linkColor = colors.accent,
                        codeBackground = colors.codeBackground,
                        muted = colors.textMuted,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                keyboardActions = KeyboardActions(onNext = { onEnter() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

/**
 * The strip left of the text: the block's marker, or the handle once the row is
 * selected. One column rather than two, because on a phone the honest budget for
 * chrome is about 30 dp and a marker plus a handle plus a marker's own gap eats
 * 60.
 */
@Composable
private fun Gutter(
    row: BlockRow,
    numberLabel: String?,
    showHandle: Boolean,
    onOpenMenu: () -> Unit,
    onToggleChecked: () -> Unit,
    onToggleFold: () -> Unit,
) {
    val colors = LocalQuireColors.current
    Box(
        modifier = Modifier.width(30.dp).heightIn(min = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            showHandle -> IconButton(onClick = onOpenMenu, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "块菜单", tint = colors.textMuted)
            }

            row.kind == "todo" -> Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(Radius.sm)).clickable(onClick = onToggleChecked),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(19.dp)
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(if (row.checked) colors.accent else Color.Transparent)
                        .border(
                            width = 1.5.dp,
                            color = if (row.checked) colors.accent else colors.borderStrong,
                            shape = RoundedCornerShape(Radius.xs),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.checked) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
            }

            row.kind == "toggle" && row.hasChildren -> IconButton(
                onClick = onToggleFold,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (row.folded) "展开" else "折叠",
                    tint = colors.textSecondary,
                    modifier = Modifier.rotate(if (row.folded) -90f else 0f),
                )
            }

            numberLabel != null -> Text(numberLabel, style = QuireType.body, color = colors.textSecondary)

            else -> {
                val marker = BlockKinds.marker(row.kind)
                if (marker.isNotEmpty()) {
                    Text(marker, style = QuireType.body, color = colors.textSecondary)
                }
            }
        }
    }
}

/**
 * Rows this slice cannot edit yet — a table, a database, a picture — shown as
 * what they are rather than as an empty line.
 *
 * A `page` block is the exception: it is a door, so it opens the page it points
 * at. Everything else says its kind in the UI's language and leaves the content
 * to the shell that can draw it; showing the block's own text where there is one
 * (a formula's LaTeX source, a card's address) beats hiding it behind the label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceholderBody(
    row: BlockRow,
    modifier: Modifier,
    onOpenPage: (Long) -> Unit,
    onOpenMenu: () -> Unit,
) {
    val colors = LocalQuireColors.current
    val target = row.pageRef ?: row.dbRef ?: row.attachment
    val label = if (target != null) "${BlockKinds.label(row.kind)} #$target" else BlockKinds.label(row.kind)
    val opens = row.pageRef != null

    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceHover)
            // A long press is free here — there is no text field to steal it —
            // so a placeholder row can offer its menu the way touch expects.
            .combinedClickable(
                onClick = { row.pageRef?.let(onOpenPage) },
                onLongClick = onOpenMenu,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (opens) "$label  ›" else label,
            style = QuireType.ui,
            color = if (opens) colors.accentText else colors.textSecondary,
        )
        if (row.text.isNotBlank() && row.kind != "page") {
            Text(row.text, style = QuireType.caption, color = colors.textMuted)
        }
    }
}
