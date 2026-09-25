package dev.quire.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.quire.compose.QuireViewModel
import dev.quire.compose.bridge.PageRow
import dev.quire.compose.bridge.View

/**
 * The drawer: the page tree, the favorites, and the way into settings.
 *
 * **Settings live here rather than on the main surface.** The theme switch, the
 * read-only switch and the rest are global settings, and a global setting on a
 * phone's main screen is a control that costs a row of every page to serve an
 * errand nobody runs twice a day. It is one row at the bottom of the drawer, in
 * the same place the desktop shell keeps it.
 */
@Composable
fun Sidebar(
    view: View,
    vm: QuireViewModel,
    onPageMenu: (Long) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = LocalQuireColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.sidebar)
            // The drawer slides over the system bars, so its own content has to
            // keep clear of them.
            .padding(top = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Quire", style = QuireType.h2.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
            Text(
                text = if (view.pageCount == 1) "1 个页面" else "${view.pageCount} 个页面",
                style = QuireType.caption,
                color = colors.textMuted,
            )
        }

        // SPEC §四十一: the way into the other two pages, in the same place the
        // reference app keeps them — two rows under the header, above the tree.
        // They are *destinations*, which is why they are separate rows and not two
        // tabs on one screen: 收件箱 and 任务 have their own toolbars and their own
        // state, and the drawer is the only thing between them (ADR-0013).
        SidebarAction(label = "收件箱", icon = Icons.Default.Edit, onClick = onOpenNotes)
        SidebarAction(label = "任务", icon = Icons.Default.CheckCircle, onClick = onOpenTasks)
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 4.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            item(key = "new-page") {
                SidebarAction(
                    label = "新建页面",
                    onClick = { vm.createPage(parent = null) },
                )
            }

            val favorites = view.pages.filter { it.favorite }
            if (favorites.isNotEmpty()) {
                item(key = "favorites-header") { SectionHeader("收藏") }
                items(favorites.size, key = { "fav-${favorites[it].id}" }) { index ->
                    PageTreeRow(
                        page = favorites[index],
                        selected = favorites[index].id == view.activePage,
                        onOpen = { vm.openPage(favorites[index].id) },
                        onToggleExpanded = { vm.toggleExpanded(favorites[index].id) },
                        onMenu = { onPageMenu(favorites[index].id) },
                    )
                }
            }

            item(key = "pages-header") { SectionHeader("页面") }
            items(view.pages.size, key = { "page-${view.pages[it].id}" }) { index ->
                val page = view.pages[index]
                PageTreeRow(
                    page = page,
                    selected = page.id == view.activePage,
                    onOpen = { vm.openPage(page.id) },
                    onToggleExpanded = { vm.toggleExpanded(page.id) },
                    onMenu = { onPageMenu(page.id) },
                )
            }

            val recents = recentRows(view)
            if (recents.isNotEmpty()) {
                item(key = "recents-header") { SectionHeader("最近") }
                items(recents.size, key = { "recent-${recents[it].first}" }) { index ->
                    val (id, title) = recents[index]
                    SidebarAction(label = title, onClick = { vm.openPage(id) })
                }
            }
        }

        HorizontalDivider(color = colors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.sidebar)
                .combinedClickableCompat(onClick = onOpenSettings)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = colors.textSecondary)
            Text("设置", style = QuireType.ui, color = colors.textPrimary)
        }
    }
}

/** One row of the tree: fold control, icon-or-initial, title. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTreeRow(
    page: PageRow,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleExpanded: () -> Unit,
    onMenu: () -> Unit,
) {
    val colors = LocalQuireColors.current
    val background = if (selected) colors.surfaceSelected else colors.sidebar

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(background)
            // A long press is the touch vocabulary for "the rest of this row's
            // verbs" — there is no such thing as a right click here.
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(start = (6 + page.depth * 14).dp, end = 4.dp)
            .heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            if (page.hasChildren) {
                IconButton(onClick = onToggleExpanded, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = if (page.expanded) "折叠" else "展开",
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp).rotate(if (page.expanded) 0f else -90f),
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = page.icon.ifEmpty { page.initial },
            style = QuireType.ui,
            color = colors.textSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = page.title.ifEmpty { "无标题" },
            style = QuireType.ui,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SidebarAction(label: String, icon: ImageVector = Icons.Default.Add, onClick: () -> Unit) {
    val colors = LocalQuireColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .combinedClickableCompat(onClick)
            .padding(horizontal = 10.dp)
            .heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Text(label, style = QuireType.ui, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = LocalQuireColors.current
    Text(
        text = label,
        style = QuireType.caption,
        color = colors.textMuted,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** The recents list, resolved against the rows we have titles for. */
private fun recentRows(view: View): List<Pair<Long, String>> {
    val titles = view.pages.associate { it.id to it.title }
    return view.recents
        .filter { it != view.activePage }
        .mapNotNull { id -> titles[id]?.let { id to (it.ifEmpty { "无标题" }) } }
}

/**
 * `combinedClickable` without the opt-in at every call site. A long press is
 * the whole point: on touch it is the only "secondary click" there is, and this
 * shell uses it wherever a text field is not in the way.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
