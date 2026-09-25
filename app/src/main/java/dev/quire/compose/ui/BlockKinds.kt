package dev.quire.compose.ui

import androidx.compose.ui.text.TextStyle

/**
 * What each `quire-core` block kind looks like as a row, and what the picker
 * calls it.
 *
 * The kind strings are the ones `BlockKind::as_str` stores (`heading_1`, not
 * `heading1`), because they travel to the Rust side and back through the
 * database — a display name invented here would be a second vocabulary for one
 * fact.
 */
object BlockKinds {

    /** The picker's order: what a phone actually reaches for, first. */
    val picker: List<Pair<String, String>> = listOf(
        "paragraph" to "正文",
        "heading_1" to "标题 1",
        "heading_2" to "标题 2",
        "heading_3" to "标题 3",
        "bullet" to "项目符号",
        "numbered" to "编号列表",
        "todo" to "待办事项",
        "quote" to "引用",
        "code" to "代码块",
        "callout" to "提示框",
        "toggle" to "折叠列表",
        "divider" to "分隔线",
    )

    /**
     * Amount of indent a block's `depth` is worth. The desktop nests by 24 px;
     * a finger needs a little more to read as nesting, and this is the one
     * number that decides how deep a list can go before a phone runs out of
     * width.
     */
    val indentPerDepth = 18

    private val markers = mapOf(
        "bullet" to "•",
        "quote" to "❝",
        "callout" to "💡",
        "page" to "📄",
        "link" to "🔗",
        "image" to "🖼",
        "file" to "📎",
        "table" to "▦",
        "columns" to "▥",
        "column" to "▥",
        "table_cell" to "▫",
        "math" to "∑",
        "toc" to "≡",
        "embed" to "🔗",
        "synced" to "⧉",
        "database" to "▦",
    )

    private val labels = mapOf(
        "table" to "表格",
        "columns" to "分栏",
        "column" to "分栏",
        "table_cell" to "单元格",
        "math" to "公式",
        "toc" to "目录",
        "embed" to "链接卡片",
        "synced" to "同步块",
        "database" to "数据库",
        "image" to "图片",
        "file" to "文件",
        "page" to "子页面",
        "link" to "页面链接",
    )

    /** Kinds this slice draws as a labelled, read-only row rather than natively. */
    fun isPlaceholder(kind: String): Boolean = kind in labels

    fun label(kind: String): String =
        picker.firstOrNull { it.first == kind }?.second ?: labels[kind] ?: kind

    fun marker(kind: String): String = markers[kind] ?: ""

    fun textStyle(kind: String): TextStyle = when (kind) {
        "heading_1" -> QuireType.h1
        "heading_2" -> QuireType.h2
        "heading_3" -> QuireType.h3
        "code" -> QuireType.code
        "quote" -> QuireType.body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        else -> QuireType.body
    }

    /** The kinds a todo row may be turned into without losing its text. */
    fun isCheckable(kind: String): Boolean = kind == "todo"
}
