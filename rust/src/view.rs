//! The wire shape: what one reply carries from the core to the Kotlin UI.
//!
//! Two rules keep this honest and cheap. **It is a projection, never a mirror**:
//! a row carries what a screen draws and nothing else, so the payload stays
//! proportional to what is on screen rather than to what the model holds. And
//! **the core's types are never exported**: `quire-core`'s model types have no
//! serde derives (only the sync wire structs do), and adding them would mean
//! changing the shared crate to suit one shell — so the bridge owns the JSON
//! shape and spells the enums as the same short stable strings the database
//! already stores (`BlockKind::as_str`, `MarkKind::as_str`).

use std::collections::{HashMap, HashSet};

use serde::Serialize;

use quire_core::core::organizer::OrganizerCatalog;
use quire_core::core::types::ColorKind;
use quire_core::core::{Block, BlockId, Document, PageId};

use crate::workspace::Workspace;

/// One sidebar row.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PageRow {
    pub id: u64,
    pub title: String,
    pub depth: u32,
    pub parent: Option<u64>,
    pub favorite: bool,
    pub expanded: bool,
    pub has_children: bool,
    pub icon: String,
    /// The page's read-only switch. Carried per row rather than as one flag on
    /// the view because the drawer's menu can be opened for a page that is not
    /// the open one, and that page's switch is the one it has to show.
    pub locked: bool,
}

/// One styled range inside a block's text. Offsets are byte offsets into
/// `BlockRow::text` — exactly what the core stores, so no translation happens
/// on either side of the bridge. (The Kotlin side is where they become UTF-16
/// spans, because that is what a Compose text field measures in.)
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct MarkRow {
    pub start: usize,
    pub end: usize,
    pub kind: String,
    pub url: String,
}

/// One editor row.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BlockRow {
    pub id: u64,
    pub kind: String,
    pub text: String,
    pub checked: bool,
    /// Nesting depth, 0 for a top-level block.
    pub depth: u32,
    pub parent: Option<u64>,
    pub folded: bool,
    /// This block owns children, so its row gets a fold control.
    pub has_children: bool,
    /// The language of a `code` block, `""` for every other kind.
    pub lang: String,
    /// Text colour slot: 0 = the theme's own ink, 1..9 = gray..red.
    pub color: i32,
    /// Row background slot, same numbering. 0 = transparent.
    pub background: i32,
    /// The page a `page` block opens.
    pub page_ref: Option<u64>,
    /// The database a `database` block draws.
    pub db_ref: Option<u64>,
    /// The attachment an `image`/`file` block shows.
    pub attachment: Option<u64>,
    pub img_percent: u16,
    pub marks: Vec<MarkRow>,
}

/// The whole of what the UI redraws, sent with every structural reply.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct View {
    pub pages: Vec<PageRow>,
    pub blocks: Vec<BlockRow>,
    pub active_page: Option<u64>,
    pub title: String,
    pub icon: String,
    pub locked: bool,
    pub theme: String,
    pub recents: Vec<u64>,
    pub favorites: Vec<u64>,
    /// Whether an undo/redo step is believed to exist. Approximate on purpose:
    /// `History` exposes no depth (`push`/`undo`/`redo` and nothing else), so
    /// this is "the last edit-shaped call did something", which is all a
    /// button's enabled state needs — pressing it when it lies is a no-op.
    pub can_undo: bool,
    pub can_redo: bool,
    /// The same two answers for the organizer's own stack. Separate fields
    /// because the two stacks are separate: a step in the area must not light the
    /// editor's button, and neither may a page's edit light the area's.
    pub org_can_undo: bool,
    pub org_can_redo: bool,
    /// A startup notice worth showing once (recovered-from-backup, a moved
    /// library). `None` on an ordinary start.
    pub notice: Option<String>,
    pub page_count: usize,
    /// SPEC §四十一's whole catalog — notes, tasks, lists — for the Compose side
    /// to project. Rows only, no derived view: the five smart views, the three
    /// sorts and the row badges are computed in Kotlin from this, which is what
    /// keeps a filter change off the bridge entirely.
    pub org: OrgCatalog,
}

/// The organizer's catalog as the wire carries it. Not a mirror of the core's
/// types: a list's colour travels as its **palette slot** (an `i32` the UI
/// already knows how to paint, and the same number the write side hands back),
/// and nothing that only the store cares about is sent.
#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct OrgCatalog {
    pub notes: Vec<OrgNoteRow>,
    pub tasks: Vec<OrgTaskRow>,
    /// The stored lists, inbox excluded — the inbox is a sentinel with no row
    /// (`ListId::INBOX`), so a chip for it is a projection the UI builds.
    pub lists: Vec<OrgListRow>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct OrgNoteRow {
    pub id: u64,
    pub title: String,
    pub body: String,
    pub pinned: bool,
    pub tags: Vec<String>,
    pub created: i64,
    pub edited: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct OrgSubtaskRow {
    pub id: u64,
    pub title: String,
    pub done: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct OrgTaskRow {
    pub id: u64,
    /// The list it belongs to. `0` is the inbox — a sentinel, not a row, and the
    /// read side folds a task whose list is gone to the inbox the same way.
    pub list: u64,
    pub title: String,
    pub notes: String,
    /// `none` | `low` | `medium` | `high`, `Priority::as_str`'s own spelling, so
    /// the picker's order and the stored string cannot drift apart.
    pub priority: String,
    /// `YYYY-MM-DD`, or absent. A date and not an instant: "due Friday" is what
    /// the user meant, and a timestamp would make one list read differently in
    /// another zone.
    pub due: Option<String>,
    /// `none` | `daily` | `weekdays` | `weekly` | `monthly`.
    pub repeat: String,
    pub done: bool,
    pub completed_at: Option<i64>,
    pub tags: Vec<String>,
    pub subtasks: Vec<OrgSubtaskRow>,
    pub created: i64,
    pub edited: i64,
    /// Reading order inside the list — a dense key, so the "added order" sort is
    /// a comparison of two numbers.
    pub ord: u64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct OrgListRow {
    pub id: u64,
    pub name: String,
    pub color: i32,
    pub ord: u64,
}

/// The organizer's catalog, projected. One function, no filtering and no
/// sorting: which rows a screen shows is the shell app layer's question, and for
/// this shell that layer is Kotlin.
pub fn org_catalog(org: &OrganizerCatalog) -> OrgCatalog {
    OrgCatalog {
        notes: org
            .notes
            .iter()
            .map(|n| OrgNoteRow {
                id: n.id.0,
                title: n.title.clone(),
                body: n.body.clone(),
                pinned: n.pinned,
                tags: n.tags.clone(),
                created: n.created,
                edited: n.edited,
            })
            .collect(),
        tasks: org
            .tasks
            .iter()
            .map(|t| OrgTaskRow {
                id: t.id.0,
                list: t.list.0,
                title: t.title.clone(),
                notes: t.notes.clone(),
                priority: t.priority.as_str().to_string(),
                due: t.due.clone(),
                repeat: t.repeat.as_str().to_string(),
                done: t.done,
                completed_at: t.completed_at,
                tags: t.tags.clone(),
                subtasks: t
                    .subtasks
                    .iter()
                    .map(|s| OrgSubtaskRow {
                        id: s.id,
                        title: s.title.clone(),
                        done: s.done,
                    })
                    .collect(),
                created: t.created,
                edited: t.edited,
                ord: t.ord.0,
            })
            .collect(),
        lists: org
            .lists
            .iter()
            .map(|l| OrgListRow {
                id: l.id.0,
                name: l.name.clone(),
                // A spelling this build cannot name reads as the theme default
                // rather than failing the projection — the fold the store makes
                // for the same string.
                color: ColorKind::try_from_str(l.color.as_str())
                    .unwrap_or(ColorKind::Default)
                    .slot(),
                ord: l.ord.0,
            })
            .collect(),
    }
}

/// The sidebar's rows: the tree's expanded branches, in order.
pub fn pages(ws: &Workspace) -> Vec<PageRow> {
    ws.tree_rows()
        .into_iter()
        .map(|row| PageRow {
            id: row.page.id.0,
            title: row.page.title,
            depth: row.depth,
            parent: row.page.parent.map(|p| p.0),
            favorite: row.page.favorite,
            expanded: row.page.expanded,
            has_children: row.has_children,
            icon: row.page.icon,
            locked: row.page.locked,
        })
        .collect()
}

/// One page's blocks, in display order.
///
/// The order is a real DFS over `parent` + `order`, not a sort of the flat list:
/// a page's blocks are stored as one `Vec<Block>` per page sorted by `OrderKey`,
/// which interleaves levels — a child's key sits between its parent's and the
/// parent's next sibling's, and only the parent pointers say which is which.
/// Descendants of a folded block are omitted rather than flagged: the row a
/// folded block hides has no height, so sending it would be work the UI throws
/// away (`Document::page_blocks` is what the desktop's projection does too).
pub fn blocks(doc: &Document, page: PageId) -> Vec<BlockRow> {
    let all = doc.page_blocks(page);
    let mut children: HashMap<Option<BlockId>, Vec<&Block>> = HashMap::new();
    for b in all {
        children.entry(b.parent).or_default().push(b);
    }

    let mut out = Vec::new();
    let mut seen: Vec<BlockId> = Vec::new();
    if let Some(roots) = children.get(&None) {
        for root in roots {
            push_row(root, 0, &children, &mut out, &mut seen);
        }
    }
    // A block whose parent is not on this page (a torn write, a hand-edited
    // file) is not reachable from the roots. Showing it at depth 0 beats
    // dropping content the user can see in no other shell — but "not a root"
    // and "orphaned" are different questions: a child of a *folded* row was
    // skipped on purpose, and re-emitting it here would undo the fold.
    let present: HashSet<BlockId> = all.iter().map(|b| b.id).collect();
    for b in all {
        let orphaned = b.parent.map(|p| !present.contains(&p)).unwrap_or(false);
        if orphaned && !seen.contains(&b.id) {
            push_row(b, 0, &children, &mut out, &mut seen);
        }
    }
    out
}

fn push_row(
    block: &Block,
    depth: u32,
    children: &HashMap<Option<BlockId>, Vec<&Block>>,
    out: &mut Vec<BlockRow>,
    seen: &mut Vec<BlockId>,
) {
    if seen.contains(&block.id) {
        return;
    }
    seen.push(block.id);

    let kids = children.get(&Some(block.id));
    let has_children = kids.map(|k| !k.is_empty()).unwrap_or(false);
    out.push(BlockRow {
        id: block.id.0,
        kind: block.kind.as_str().to_string(),
        text: block.text.clone(),
        checked: block.checked,
        depth,
        parent: block.parent.map(|p| p.0),
        folded: block.folded,
        has_children,
        lang: block.lang.as_str().to_string(),
        color: block.color.slot(),
        background: block.background.slot(),
        page_ref: block.page_ref.map(|p| p.0),
        db_ref: block.db_ref.map(|d| d.as_u64()),
        attachment: block.attachment.map(|a| a.0),
        img_percent: block.img_percent,
        marks: block
            .marks
            .iter()
            .map(|m| MarkRow {
                start: m.start,
                end: m.end,
                kind: m.kind.as_str().to_string(),
                url: m.url.clone(),
            })
            .collect(),
    });

    if has_children && !block.folded {
        for child in kids.expect("checked above") {
            push_row(child, depth + 1, children, out, seen);
        }
    }
}

/// The title bar's label: the open page's own title.
///
/// Its own title and not a breadcrumb, because this is a phone: the row that
/// carries the trail would cost a line of a 360 dp screen to say what the
/// drawer already says, and the drawer is one tap away.
pub fn title(ws: &Workspace, page: PageId) -> String {
    ws.title_of(page).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use quire_core::core::{BlockKind, ColorKind, Lang, OrderKey};

    fn block(id: u64, page: u64, parent: Option<u64>, order: u64, text: &str) -> Block {
        Block {
            id: BlockId(id),
            page: PageId(page),
            parent: parent.map(BlockId),
            order: OrderKey(order),
            kind: BlockKind::Paragraph,
            text: text.into(),
            checked: false,
            marks: Vec::new(),
            color: ColorKind::Default,
            background: ColorKind::Default,
            page_ref: None,
            folded: false,
            attachment: None,
            img_percent: 100,
            columns: 0,
            lang: Lang::Plain,
            db_ref: None,
            sync_ref: None,
        }
    }

    #[test]
    fn rows_nest_by_parent_not_by_key() {
        let mut doc = Document::new(0);
        let page = PageId(1);
        // order: root(10) < child(20) < sibling(30) — the keys interleave levels
        let root = block(1, 1, None, 10, "root");
        let child = block(2, 1, Some(1), 20, "child");
        let sibling = block(3, 1, None, 30, "sibling");
        doc.set_page_blocks(page, vec![root, child, sibling]);

        let rows = blocks(&doc, page);
        assert_eq!(
            rows.iter().map(|r| (r.text.as_str(), r.depth)).collect::<Vec<_>>(),
            vec![("root", 0), ("child", 1), ("sibling", 0)]
        );
        assert!(rows[0].has_children);
        assert!(!rows[2].has_children);
    }

    #[test]
    fn folding_omits_the_hidden_rows() {
        let mut doc = Document::new(0);
        let page = PageId(1);
        let mut root = block(1, 1, None, 10, "root");
        root.folded = true;
        doc.set_page_blocks(page, vec![root, block(2, 1, Some(1), 20, "child")]);
        let rows = blocks(&doc, page);
        assert_eq!(rows.len(), 1);
        assert!(rows[0].folded);
        assert!(rows[0].has_children);
    }

    #[test]
    fn an_orphan_block_is_still_a_row() {
        let mut doc = Document::new(0);
        let page = PageId(1);
        doc.set_page_blocks(page, vec![block(2, 1, Some(99), 20, "orphan")]);
        let rows = blocks(&doc, page);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].depth, 0);
    }
}
