package dev.quire.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.OrgCatalog

/**
 * The floating capture window: a multi-line field, a markdown toolbar, and a ➤.
 *
 * It is the *only* way a note or a task is made on this shell, which is why it
 * carries the toolbar: the field is the content, and the five keys are how the
 * content is written. Two details are the reference app's and are load-bearing:
 * the send button is a glyph rather than a word so the toolbar has room, and a
 * `#`-token being typed raises the tag suggestions above the field.
 *
 * **It is an overlay, not a `ModalBottomSheet`** (ADR-0015). The sheet's spring —
 * a scrim fade and a two-stage expand — is what a person experiences as "the
 * keyboard is slow", because the IME cannot start until the animation lands. This
 * draws the same content in a plain `Box`, focuses the field on the frame it
 * appears, and lets the IME come up with it. The price is that a swipe-down no
 * longer dismisses it: a tap on the scrim and the back gesture both do, and the
 * draft survives either.
 *
 * Nothing commits until ➤: the window is a draft, and its text lives in the view
 * model, so dismissing it loses nothing.
 */
@Composable
fun CaptureOverlay(vm: QuireViewModel, onDismiss: () -> Unit) {
    val colors = LocalQuireColors.current
    val target = vm.orgCompose
    // Unreachable — the shell only composes this for an open target — but the
    // overlay must not be a place a `Closed` value can reach the write below.
    if (target == QuireViewModel.Compose.Closed) return
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val catalog = vm.view?.org ?: OrgCatalog.Empty

    var field by remember(target) { mutableStateOf(TextFieldValue(vm.orgDraft)) }
    // The draft is the view model's, so a dismissal and a return finds it; every
    // keystroke updates it, which is the same "write early" rule the debounced
    // fields keep.
    LaunchedEffect(field.text) { vm.orgSetDraft(field.text) }
    // The whole point of the overlay: the caret is asked for on the same frame
    // the field exists, so the IME starts with it rather than behind a spring.
    LaunchedEffect(target) { focusRequester.requestFocus() }

    BackHandler { onDismiss() }

    val suggestions = remember(field, catalog) { tagSuggestions(field, catalog) }

    Box(modifier = Modifier.fillMaxSize()) {
        // The scrim is the dismiss target — and it is a sibling *under* the panel
        // rather than a wrapper, so a tap inside the field never reaches it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background.copy(alpha = 0.5f))
                .clickable { onDismiss() },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(colors.background)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = Spacing.sm),
        ) {
            if (suggestions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .padding(horizontal = Spacing.sm),
                ) {
                    for (tag in suggestions) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .clickable { field = applyTagSuggestion(field, tag) }
                                .padding(horizontal = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#$tag", style = QuireType.ui, color = colors.accentText)
                        }
                    }
                }
            }

            BasicTextField(
                value = field,
                onValueChange = { field = it },
                textStyle = QuireType.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                maxLines = when (target) {
                    QuireViewModel.Compose.NewTask -> 4
                    is QuireViewModel.Compose.Comment -> 6
                    else -> 10
                },
                minLines = if (target == QuireViewModel.Compose.NewTask) 1 else 3,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Default,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .focusRequester(focusRequester)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                decorationBox = { inner ->
                    if (field.text.isEmpty()) {
                        Text(
                            text = when (target) {
                                QuireViewModel.Compose.NewTask -> "添加任务…  输入 # 打标签"
                                is QuireViewModel.Compose.Comment -> "回复…  使用 #标签 标记"
                                else -> "记录点什么… 使用 #标签 标记"
                            },
                            style = QuireType.body,
                            color = colors.textMuted,
                        )
                    }
                    inner()
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(end = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarkdownToolbar(
                    field = field,
                    onEdit = { field = it },
                    modifier = Modifier.weight(1f),
                )
                // ➤ and not 发送: the toolbar needs the width, and one glyph says
                // the same thing on any phone width.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable {
                            val text = field.text.trim()
                            if (text.isNotEmpty()) {
                                when (val t = target) {
                                    QuireViewModel.Compose.NewNote -> vm.orgAddNote(text)
                                    QuireViewModel.Compose.NewTask -> vm.orgComposeTask(text)
                                    is QuireViewModel.Compose.Comment -> vm.orgComment(t.parent, text)
                                    // Unreachable: the overlay is not composed for Closed.
                                    QuireViewModel.Compose.Closed -> Unit
                                }
                                vm.orgClearDraft()
                                focus.clearFocus()
                                onDismiss()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (target == QuireViewModel.Compose.NewTask) "添加任务" else "发送",
                        tint = colors.background,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The five keys, in the reference app's order, in a scrolling row. */
@Composable
private fun MarkdownToolbar(
    field: TextFieldValue,
    onEdit: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuireColors.current
    val sel = field.selection

    // Each key is one pure transform (see `MarkdownText`), and the caret moves
    // with the text: a toolbar that left the selection behind would make the
    // second press of 加粗 act on the wrong word.
    fun apply(edit: MarkdownText.Edit) {
        onEdit(TextFieldValue(edit.text, TextRange(edit.start, edit.end)))
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(start = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolKey("#", "井号", 17, true, colors.textMuted) { apply(MarkdownText.insert(field.text, sel.start, sel.end, "#")) }
        ToolKey("B", "加粗", 16, true, colors.textMuted) {
            apply(MarkdownText.toggleWrap(field.text, sel.start, sel.end, "**"))
        }
        ToolKey("/", "斜杠", 18, false, colors.textMuted) { apply(MarkdownText.insert(field.text, sel.start, sel.end, "/")) }
        ToolKey("•", "无序列表", 18, false, colors.textMuted) {
            apply(MarkdownText.toggleBullet(field.text, sel.start, sel.end))
        }
        ToolKey("1.", "有序列表", 15, false, colors.textMuted) {
            apply(MarkdownText.toggleOrdered(field.text, sel.start, sel.end))
        }
    }
}

@Composable
private fun ToolKey(
    label: String,
    description: String,
    size: Int,
    bold: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(Radius.sm))
            // These keys are glyphs, not words — "#", "B", "1." — so the screen
            // reader has only this description to go on.
            .semantics { contentDescription = description }
            .clickable(onClick = onClick)
            .padding(end = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = size.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
            color = tint,
        )
    }
}

/**
 * The tags in the field that the caret is currently *inside* a `#token` of, best
 * first — the reference app's suggestion list.
 *
 * Empty unless the caret sits in a token with no space or second `#` after the
 * last one, which is what makes the list appear while a tag is being typed and
 * disappear once it has been typed.
 */
private fun tagSuggestions(field: TextFieldValue, catalog: OrgCatalog): List<String> {
    val text = field.text
    val caret = field.selection.start.coerceIn(0, text.length)
    val hash = text.lastIndexOf('#', (caret - 1).coerceAtLeast(0))
    if (hash < 0) return emptyList()
    val typed = text.substring(hash + 1, caret)
    if (typed.contains(' ') || typed.contains('#') || typed.contains('\n')) return emptyList()

    val prefix = typed.lowercase()
    val pool = catalog.notes.flatMap { it.tags } + catalog.tasks.flatMap { it.tags }
    return pool.distinct()
        .filter { prefix.isEmpty() || it.lowercase().startsWith(prefix) }
        .take(5)
}

/** Replace the half-typed token with the chosen tag, and leave a space after it. */
private fun applyTagSuggestion(field: TextFieldValue, tag: String): TextFieldValue {
    val text = field.text
    val caret = field.selection.start.coerceIn(0, text.length)
    val hash = text.lastIndexOf('#', (caret - 1).coerceAtLeast(0))
    if (hash < 0) {
        val out = text.substring(0, caret) + "#$tag " + text.substring(caret)
        return TextFieldValue(out, TextRange(caret + tag.length + 2))
    }
    val out = text.substring(0, hash) + "#$tag " + text.substring(caret)
    return TextFieldValue(out, TextRange(hash + tag.length + 2))
}
