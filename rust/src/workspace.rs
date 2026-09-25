//! The page tree.
//!
//! `quire-core` deliberately owns no page-tree model: its `Command` enum has
//! block, database and organizer arms and not one page arm, because the tree is
//! the *shell's* model — the persisted `Vec<Page>` arrives through `Change`s and
//! whoever builds a window decides how to order, walk and present it. Every
//! shell therefore carries one, and this is the Compose shell's: small on
//! purpose (title / parent / order / favorite / expanded / icon / locked), with
//! the desktop's templates, covers, recents and search blobs left out until a
//! screen actually asks for them.

use std::collections::HashMap;

use quire_core::core::{OrderKey, Page, PageId};

/// How many recently opened pages the sidebar remembers in `meta.recents`.
/// The desktop's cap is the same number, so a library carried across shells
/// shows the same list (settings_store has no key for this; `meta` does).
pub const MAX_RECENTS: usize = 6;

/// One page, as this shell needs it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PageRec {
    pub id: PageId,
    pub title: String,
    pub parent: Option<PageId>,
    pub order: OrderKey,
    pub favorite: bool,
    pub expanded: bool,
    pub icon: String,
    pub locked: bool,
}

/// A page plus what the sidebar row needs to know about it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TreeRow {
    pub page: PageRec,
    pub depth: u32,
    pub has_children: bool,
}

#[derive(Debug, Default)]
pub struct Workspace {
    pages: HashMap<PageId, PageRec>,
}

impl Workspace {
    /// Build from persisted rows. Template pages are skipped: a template is a
    /// body to copy *from*, and SPEC §三十八 keeps it out of every list a page
    /// appears in — the sidebar, the palette, the search results.
    pub fn from_persisted(pages: &[Page]) -> Self {
        let mut ws = Workspace::default();
        for p in pages.iter().filter(|p| !p.template) {
            ws.pages.insert(
                p.id,
                PageRec {
                    id: p.id,
                    title: p.title.clone(),
                    parent: p.parent,
                    order: p.order,
                    favorite: p.favorite,
                    expanded: p.expanded,
                    icon: p.icon.clone(),
                    locked: p.locked,
                },
            );
        }
        ws
    }

    pub fn get(&self, id: PageId) -> Option<&PageRec> {
        self.pages.get(&id)
    }

    pub fn contains(&self, id: PageId) -> bool {
        self.pages.contains_key(&id)
    }

    pub fn len(&self) -> usize {
        self.pages.len()
    }

    pub fn is_locked(&self, id: PageId) -> bool {
        self.pages.get(&id).map(|p| p.locked).unwrap_or(false)
    }

    pub fn title_of(&self, id: PageId) -> &str {
        self.pages.get(&id).map(|p| p.title.as_str()).unwrap_or("")
    }

    /// The next free page id. Ids are allocated from `max + 1` over a hash map,
    /// exactly as both existing shells do — which is also why nothing above
    /// `i32::MAX` is a legal page id (`ORGANIZER_STACK` in the core depends on
    /// that being true).
    pub fn next_id(&self) -> PageId {
        PageId(self.pages.keys().map(|p| p.0).max().unwrap_or(0) + 1)
    }

    /// A parent's children, in display order. `None` is the root level.
    pub fn children_of(&self, parent: Option<PageId>) -> Vec<PageId> {
        let mut rows: Vec<&PageRec> =
            self.pages.values().filter(|p| p.parent == parent).collect();
        rows.sort_by_key(|p| p.order);
        rows.into_iter().map(|p| p.id).collect()
    }

    pub fn has_children(&self, id: PageId) -> bool {
        self.pages.values().any(|p| p.parent == Some(id))
    }

    /// Where a page appended to `parent` lands. A gap that has run out is not
    /// worth a renumber here: leaving one stride of headroom costs nothing and
    /// keeps the tree's order keys monotonic.
    pub fn append_order(&self, parent: Option<PageId>) -> OrderKey {
        match self.children_of(parent).last() {
            Some(last) => {
                let key = self.pages[&last].order;
                OrderKey::between(Some(key), None)
                    .unwrap_or(OrderKey(key.0.saturating_add(OrderKey::STRIDE)))
            }
            None => OrderKey::FIRST,
        }
    }

    pub fn insert(&mut self, rec: PageRec) {
        self.pages.insert(rec.id, rec);
    }

    pub fn set_title(&mut self, id: PageId, title: &str) -> Option<String> {
        let p = self.pages.get_mut(&id)?;
        let old = std::mem::replace(&mut p.title, title.to_string());
        Some(old)
    }

    pub fn set_favorite(&mut self, id: PageId, value: bool) -> Option<bool> {
        let p = self.pages.get_mut(&id)?;
        Some(std::mem::replace(&mut p.favorite, value))
    }

    pub fn set_expanded(&mut self, id: PageId, value: bool) -> Option<bool> {
        let p = self.pages.get_mut(&id)?;
        Some(std::mem::replace(&mut p.expanded, value))
    }

    pub fn set_locked(&mut self, id: PageId, value: bool) -> Option<bool> {
        let p = self.pages.get_mut(&id)?;
        Some(std::mem::replace(&mut p.locked, value))
    }

    /// Remove a page and everything under it, returning the ids that went.
    pub fn remove_subtree(&mut self, id: PageId) -> Vec<PageId> {
        let gone = self.subtree(id);
        for id in &gone {
            self.pages.remove(id);
        }
        gone
    }

    /// `id` first, then its descendants in DFS order.
    pub fn subtree(&self, id: PageId) -> Vec<PageId> {
        let mut out = Vec::new();
        if !self.pages.contains_key(&id) {
            return out;
        }
        out.push(id);
        let mut i = 0;
        while i < out.len() {
            let current = out[i];
            out.extend(self.children_of(Some(current)));
            i += 1;
        }
        out
    }

    /// Root-first chain of ancestors, `id` excluded.
    pub fn ancestors(&self, id: PageId) -> Vec<PageId> {
        let mut chain = Vec::new();
        let mut cursor = self.pages.get(&id).and_then(|p| p.parent);
        // A corrupt library can hold a parent cycle; the guard is cheaper than
        // an unbounded walk and the honest answer for a cycle is "stop here".
        while let Some(parent) = cursor {
            if chain.contains(&parent) {
                break;
            }
            chain.push(parent);
            cursor = self.pages.get(&parent).and_then(|p| p.parent);
        }
        chain.reverse();
        chain
    }

    /// Where a page belongs when expanded — its ancestors' branches open.
    pub fn expand_ancestors(&mut self, id: PageId) {
        for ancestor in self.ancestors(id) {
            if let Some(p) = self.pages.get_mut(&ancestor) {
                p.expanded = true;
            }
        }
    }

    pub fn first_root(&self) -> Option<PageId> {
        self.children_of(None).into_iter().next()
    }

    /// Every expanded-branch row, in sidebar order.
    pub fn tree_rows(&self) -> Vec<TreeRow> {
        let mut out = Vec::new();
        for root in self.children_of(None) {
            self.walk(root, 0, &mut out);
        }
        out
    }

    fn walk(&self, id: PageId, depth: u32, out: &mut Vec<TreeRow>) {
        let Some(page) = self.pages.get(&id) else {
            return;
        };
        let has_children = self.has_children(id);
        out.push(TreeRow { page: page.clone(), depth, has_children });
        if has_children && page.expanded {
            for child in self.children_of(Some(id)) {
                self.walk(child, depth + 1, out);
            }
        }
    }

    /// Every page whose branch is open — what `sidebar.expanded` persists.
    pub fn expanded_ids(&self) -> Vec<PageId> {
        let mut ids: Vec<PageId> = self
            .pages
            .values()
            .filter(|p| p.expanded)
            .map(|p| p.id)
            .collect();
        ids.sort_by_key(|p| p.0);
        ids
    }

    pub fn favorites(&self) -> Vec<PageId> {
        let mut rows: Vec<&PageRec> = self.pages.values().filter(|p| p.favorite).collect();
        rows.sort_by_key(|p| (p.order, p.id.0));
        rows.into_iter().map(|p| p.id).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use quire_core::core::PageFont;

    fn page(id: u64, title: &str, parent: Option<u64>, order: u64) -> Page {
        Page {
            id: PageId(id),
            title: title.into(),
            parent: parent.map(PageId),
            order: OrderKey(order),
            favorite: false,
            expanded: false,
            font: PageFont::Default,
            full_width: false,
            small_text: false,
            icon: String::new(),
            cover: None,
            locked: false,
            template: false,
        }
    }

    fn ws() -> Workspace {
        Workspace::from_persisted(&[
            page(1, "root", None, OrderKey::FIRST.0),
            page(2, "child", Some(1), OrderKey::FIRST.0),
            page(3, "other", None, OrderKey::FIRST.0 + 10),
        ])
    }

    #[test]
    fn ids_do_not_collide_with_allocated_ones() {
        assert_eq!(ws().next_id(), PageId(4));
    }

    #[test]
    fn a_folded_branch_hides_its_children() {
        let mut w = ws();
        // page 1 owns a child, but nothing is expanded: the child is not a row
        let rows = w.tree_rows();
        assert_eq!(rows.len(), 2);
        assert!(rows[0].has_children);
        assert_eq!(rows[0].page.title, "root");
        assert_eq!(rows[1].page.title, "other");

        w.set_expanded(PageId(1), true);
        let rows = w.tree_rows();
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[1].page.title, "child");
        assert_eq!(rows[1].depth, 1);
        assert_eq!(rows[2].depth, 0);
    }

    #[test]
    fn delete_takes_the_whole_subtree() {
        let mut w = ws();
        let mut gone = w.remove_subtree(PageId(1));
        gone.sort_by_key(|p| p.0);
        assert_eq!(gone, vec![PageId(1), PageId(2)]);
        assert_eq!(w.len(), 1);
        assert_eq!(w.first_root(), Some(PageId(3)));
    }

    #[test]
    fn templates_never_reach_the_tree() {
        let mut t = page(9, "template", None, OrderKey::FIRST.0);
        t.template = true;
        let w = Workspace::from_persisted(&[t, page(1, "real", None, OrderKey::FIRST.0)]);
        assert_eq!(w.len(), 1);
        assert_eq!(w.first_root(), Some(PageId(1)));
    }

    #[test]
    fn a_parent_cycle_is_survivable() {
        let w = Workspace::from_persisted(&[
            page(1, "a", Some(2), OrderKey::FIRST.0),
            page(2, "b", Some(1), OrderKey::FIRST.0),
        ]);
        assert_eq!(w.ancestors(PageId(1)).len(), 2);
    }

    #[test]
    fn appends_keep_climbing() {
        let mut w = ws();
        let first = w.append_order(None);
        w.insert(PageRec {
            id: w.next_id(),
            title: "new".into(),
            parent: None,
            order: first,
            favorite: false,
            expanded: false,
            icon: String::new(),
            locked: false,
        });
        assert_eq!(w.children_of(None).len(), 3);
        assert!(w.append_order(None) > first);
    }
}
