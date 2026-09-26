package dev.quire.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.OrgCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收件箱's second screen: one note, and the notes that answer it.
 *
 * A note opens **here** rather than in the capture overlay (ADR-0015) — the
 * reference app edits a note on a page of its own, and the overlay is for making
 * one thing quickly, not for holding a document open. The page carries the note's
 * body, its tags, its replies, and a 详细信息 sheet; the ⋯ in the row menu is what
 * gets you here.
 *
 * **A comment is a note.** There is no second type: a reply is an ordinary note
 * carrying `ref`, and everything on this page — the `↩` preview, the comments list
 * — is a projection of the one catalog. That is what keeps a comment from being
 * able to drift out of the notes list.
 */

/**
 * One note as a card: the text, its tags on an accent line, its age, and a ⋯.
 *
 * The card is a *surface* rather than a divider-separated row — the reference app
 * draws every note as a rounded plate — and the ⋯ is always drawn for the reason
 * this whole shell keeps repeating: a finger cannot hover, so an affordance only a
 * mouse reveals is an affordance a phone does not have.
 *
 * When the note is a reply, a muted `↩ <parent's first line>` row sits under the
 * text and taps through to what it answers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteCardView(
    row: OrgModel.NoteRow,
    preview: (Long) -> String?,
    onClick: () -> Unit,
    onParent: (Long) -> Unit,
    onMenu: () -> Unit,
    /** 多选: the page is picking notes, so a tap toggles rather than opens. */
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
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected || row.selected) colors.surfaceSelected else colors.card)
            .border(
                1.dp,
                if (selected || row.selected) colors.accent else colors.cardBorder,
                RoundedCornerShape(12.dp),
            )
            .then(gesture)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = row.content.ifEmpty { "（空白笔记）" },
                style = QuireType.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = colors.textPrimary,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            // Only drawn when the ref resolves: a dangling one paints as an
            // ordinary note rather than as a broken link.
            row.ref?.let { ref -> ParentPreviewRow(ref = ref, preview = preview, onOpen = onParent) }
            if (row.tags.isNotEmpty()) {
                Text(
                    text = row.tags.joinToString("  ") { OrgModel.tagLabel(it) },
                    style = QuireType.caption.copy(fontSize = 13.sp),
                    color = colors.accentText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.whenText,
                style = QuireType.caption,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        if (selecting) {
            OrgCheck(checked = selected, size = 22.dp, onToggle = { onSelect?.invoke() })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "已置顶",
                        tint = colors.accentText,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(34.dp)) {
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
}

/**
 * The `↩` line a reply carries: what it answers, and the way to it.
 *
 * The whole row is the target — a phone width has no room for a link inside a
 * line of text — and its content description says so, because `↩` is a glyph a
 * screen reader cannot read.
 */
@Composable
private fun ParentPreviewRow(ref: Long, preview: (Long) -> String?, onOpen: (Long) -> Unit) {
    val colors = LocalQuireColors.current
    val text = preview(ref) ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceSelected)
            .clickable { onOpen(ref) }
            .padding(horizontal = Spacing.sm, vertical = 5.dp)
            .semantics { contentDescription = "查看被引用的笔记" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("↩", style = QuireType.caption, color = colors.accentText)
        Text(
            text = text,
            style = QuireType.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The note's own page: its header, its body, its replies, and the way out. */
@Composable
fun NoteDetailPage(row: OrgModel.NoteRow, catalog: OrgCatalog, vm: QuireViewModel) {
    val colors = LocalQuireColors.current
    var detailsOpen by remember { mutableStateOf(false) }
    val comments = remember(catalog, row.id) { OrgModel.comments(catalog, row.id) }
    val preview: (Long) -> String? = { id -> OrgModel.parentPreview(catalog, id) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            IconButton(onClick = { vm.orgSelectNote(-1) }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textSecondary,
                )
            }
            Text(
                text = if (row.ref != null) "评论" else "笔记",
                style = QuireType.h3,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.orgToggleNotePinned(row.id, !row.pinned) }) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (row.pinned) "取消置顶" else "置顶",
                    tint = if (row.pinned) colors.accentText else colors.textMuted,
                )
            }
            IconButton(onClick = { detailsOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = colors.textSecondary)
            }
        }

        // A reply says what it answers, here as on the card.
        row.ref?.let { ref ->
            ParentPreviewRow(ref = ref, preview = preview, onOpen = { vm.orgSelectNote(it) })
        }

        // The body is the note, and it commits on the same 300 ms settle the
        // task form's fields use — with its tags, so one edit is one 撤销
        // (ADR-0014).
        OrgTextField(
            rowKey = "note-body-${row.id}",
            value = row.content,
            placeholder = "写点什么…  使用 #标签 标记",
            multiline = true,
            minHeight = 160.dp,
            textStyle = QuireType.body,
            onCommit = { vm.orgNoteContent(row.id, it) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (row.tags.isNotEmpty()) {
            Text(
                text = row.tags.joinToString("  ") { OrgModel.tagLabel(it) },
                style = QuireType.caption,
                color = colors.accentText,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        NoteCommentsSection(parent = row.id, comments = comments, vm = vm)
    }

    if (detailsOpen) {
        NoteDetailsSheet(row = row, catalog = catalog, onDismiss = { detailsOpen = false })
    }
}

/**
 * The replies on a note's page.
 *
 * A projection, like everything else here: a comment being an ordinary note means
 * this list can never disagree with what is in 收件箱. Each row opens the comment
 * on its own page; the 🗑 deletes it through the same 撤销 window every other
 * delete uses.
 */
@Composable
private fun NoteCommentsSection(
    parent: Long,
    comments: List<OrgModel.NoteRow>,
    vm: QuireViewModel,
) {
    val colors = LocalQuireColors.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("评论 ${comments.size}", style = QuireType.caption, color = colors.textMuted)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.openCommentComposer(parent) }) {
                Text("＋ 评论", color = colors.accentText)
            }
        }
        if (comments.isEmpty()) {
            Text("还没有评论", style = QuireType.caption, color = colors.textMuted)
        }
        for (comment in comments) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(colors.card)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(Radius.sm))
                    .clickable { vm.orgSelectNote(comment.id) }
                    .padding(start = Spacing.sm, end = 4.dp, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = comment.content.ifEmpty { "（空白评论）" },
                        style = QuireType.ui,
                        color = colors.textPrimary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(comment.whenText, style = QuireType.caption, color = colors.textMuted)
                }
                IconButton(onClick = { vm.orgDeleteNote(comment.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除评论",
                        tint = colors.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 详细信息: what the core actually holds about a note.
 *
 * The reference app's 历史 / 恢复版本 has **no counterpart here** — `quire-core`
 * keeps one copy of a note and no revisions, so there is no version to list. The
 * sheet says that out loud rather than inventing an empty history list: 撤销 can
 * walk an edit back, but there is nothing dated to restore to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailsSheet(row: OrgModel.NoteRow, catalog: OrgCatalog, onDismiss: () -> Unit) {
    val colors = LocalQuireColors.current
    val note = catalog.notes.firstOrNull { it.id == row.id }
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
            OrgSheetHeader(if (row.ref != null) "评论详细信息" else "详细信息")
            DetailRow("编号", row.id.toString())
            DetailRow("创建", instant(note?.created))
            DetailRow("修改", instant(note?.edited))
            DetailRow("标签", if (row.tags.isEmpty()) "无" else row.tags.joinToString(" ") { OrgModel.tagLabel(it) })
            DetailRow("长度", "${(note?.body?.ifEmpty { note.title } ?: "").length} 字")
            DetailRow("置顶", if (row.pinned) "是" else "否")
            DetailRow("引用", row.ref?.toString() ?: "无")
            DetailRow("评论", OrgModel.commentCount(catalog, row.id).toString())
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
            Text(
                text = "没有历史版本：资料库只保存当前这一份，撤销可以回退编辑，但没有按时间恢复的版本。",
                style = QuireType.caption,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = QuireType.caption, color = colors.textMuted, modifier = Modifier.width(56.dp))
        Text(
            text = value,
            style = QuireType.ui,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Unix seconds as a local `YYYY-MM-DD HH:mm`, or a dash when there is nothing. */
private fun instant(epochSeconds: Long?): String {
    if (epochSeconds == null) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1_000L))
}
