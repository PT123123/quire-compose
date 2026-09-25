//! One open workspace, and the requests the Kotlin shell can make of it.
//!
//! The session is the shell's `AppState` in miniature: the loaded `Document`,
//! its `History`, the page tree, the persisted settings, and the debounced
//! `PersistenceService` that the two existing shells also drive. What it is
//! *not* is a second model — every edit goes through `quire_core::core::command`
//! so undo, storage and the merge all keep describing the same event
//! (ADR-0012), and the only writes that bypass a `Command` are the page-tree
//! changes, because the core has no page commands at all (a page is the shell's
//! model; see `workspace.rs`).
//!
//! Two disciplines are worth knowing before reading the match:
//!
//! * **A reply is the whole view, or nothing.** Structural requests answer with
//!   the full projection, so the UI never patches state in two places. A text
//!   edit answers with `{"ok":true}` alone — it fires on every keystroke, and
//!   echoing a page's worth of rows back per character is work nobody reads.
//! * **`History` is per page.** A Ctrl+Z in one document must not reach
//!   another's edits, so every command and every undo names its page.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use quire_core::core::command::{self, Command};
use quire_core::core::{
    Block, BlockId, BlockKind, Change, Document, History, Page, PageFont, PageId, Repository,
};
use quire_core::services::persistence::PersistenceService;
use quire_core::services::settings_store::Settings;
use quire_core::storage::SqliteRepository;

use crate::view::{self, View};
use crate::workspace::{PageRec, Workspace, MAX_RECENTS};

/// `meta`'s key for the recently-opened list. The desktop shell writes the same
/// key with the same comma-separated shape, so a library carried between shells
/// offers the same recents.
const META_RECENTS: &str = "recents";

/// The default page title, in the UI's language. Both existing shells land on
/// the same string, so a page created on a phone reads the same on a desktop.
const UNTITLED: &str = "无标题";

/// One request from Kotlin. `op` is the operation name; the rest are its
/// arguments, all optional where a sensible default exists.
#[derive(Debug, Deserialize)]
#[serde(tag = "op", rename_all = "camelCase")]
pub enum Request {
    /// Redraw everything (the first request after `open`, and after a resume).
    Boot,
    /// The debounced writer's tick. Cheap by design; the shell calls it about
    /// once a second while the app is in front.
    Tick,
    /// Write everything queued now — the app is going to the background, or
    /// closing. Without it a session that is killed loses its last edit.
    Flush,
    OpenPage {
        page: u64,
    },
    CreatePage {
        #[serde(default)]
        parent: Option<u64>,
        #[serde(default)]
        title: String,
    },
    RenamePage {
        page: u64,
        title: String,
    },
    DeletePage {
        page: u64,
    },
    ToggleFavorite {
        page: u64,
    },
    ToggleExpanded {
        page: u64,
    },
    SetPageLocked {
        page: u64,
        locked: bool,
    },
    SetTheme {
        theme: String,
    },
    SetBlockText {
        block: u64,
        text: String,
    },
    InsertBlockAfter {
        block: u64,
        #[serde(default)]
        kind: String,
        #[serde(default)]
        text: String,
    },
    AppendBlock {
        #[serde(default)]
        kind: String,
        #[serde(default)]
        text: String,
    },
    DeleteBlock {
        block: u64,
    },
    SetBlockKind {
        block: u64,
        kind: String,
    },
    ToggleChecked {
        block: u64,
    },
    ToggleFold {
        block: u64,
    },
    MoveBlock {
        block: u64,
        delta: i32,
    },
    MoveBlockTo {
        block: u64,
        index: i32,
    },
    IndentList {
        block: u64,
    },
    OutdentList {
        block: u64,
    },
    Undo,
    Redo,
}

/// Whether a reply carries the full view.
enum Outcome {
    Full,
    Quiet,
}

#[derive(Serialize)]
struct Reply {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    view: Option<View>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

pub struct Session {
    repo: Arc<SqliteRepository>,
    persistence: PersistenceService,
    doc: Document,
    hist: History,
    ws: Workspace,
    settings: Settings,
    active: Option<PageId>,
    theme: String,
    recents: Vec<PageId>,
    can_undo: bool,
    can_redo: bool,
    notice: Option<String>,
}

impl Session {
    /// Open (or create) the library in `data_dir` and load it.
    ///
    /// `data_dir` is handed in rather than discovered: `quire-core`'s placement
    /// rules read `%APPDATA%` on Windows and have no answer on Android, where
    /// the app's private files directory is a fact only the Activity knows. The
    /// directory is the same `Quire` folder a desktop install makes, so the
    /// store sees a per-user directory on both platforms and never has to ask
    /// which one it is on.
    pub fn open(data_dir: &str) -> Result<Session, String> {
        let dir = PathBuf::from(data_dir);
        std::fs::create_dir_all(&dir)
            .map_err(|e| format!("cannot create {}: {e}", dir.display()))?;

        let db_path = dir.join("quire.db");
        let (repo, report) = SqliteRepository::open_at(&db_path, None).map_err(|e| e.to_string())?;
        let state = repo.load().map_err(|e| e.to_string())?;

        // Blocks are stored as one flat `Vec<Block>`; the document wants them
        // grouped per page. `set_page_blocks` also walks the id generator past
        // the highest id it sees, so a freshly allocated block can never collide
        // with a loaded one.
        let mut doc = Document::new(0);
        let mut by_page: HashMap<PageId, Vec<Block>> = HashMap::new();
        for block in &state.blocks {
            by_page.entry(block.page).or_default().push(block.clone());
        }
        for (page, blocks) in by_page {
            doc.set_page_blocks(page, blocks);
        }

        let ws = Workspace::from_persisted(&state.pages);
        let (settings, meta) = Settings::from_state(&state);
        let theme = settings.theme().unwrap_or("system").to_string();
        let recents = parse_recents(meta.get(META_RECENTS), &ws);
        // Open where the user left off, falling back to the first page in tree
        // order: a client that opens on nothing looks broken.
        let active = recents.iter().copied().find(|p| ws.contains(*p)).or_else(|| ws.first_root());

        let mut notice = None;
        if let Some(from) = &report.recovered_from {
            notice = Some(format!("数据库已损坏 — 已从备份恢复（{}）", from.display()));
        } else if report.migrated_from.is_some() {
            notice = Some("资料库已移至用户配置目录".to_string());
        }

        let repo = Arc::new(repo);
        let persistence =
            PersistenceService::with_default_clock(repo.clone()).with_database_snapshots(&repo);

        Ok(Session {
            repo,
            persistence,
            doc,
            hist: History::default(),
            ws,
            settings,
            active,
            theme,
            recents,
            can_undo: false,
            can_redo: false,
            notice,
        })
    }

    /// The reply to `open`: the view, plus a startup notice if there is one.
    pub fn view_reply(&self) -> String {
        self.reply(Outcome::Full)
    }

    /// Write everything queued now. The `close` path, so a process that is
    /// going away does not take the last edit with it.
    pub fn flush(&self) -> Result<(), String> {
        self.persistence.force_flush().map_err(|e| e.to_string())
    }

    /// Handle one request body and answer with one JSON object.
    pub fn dispatch(&mut self, body: &str) -> String {
        let request: Request = match serde_json::from_str(body) {
            Ok(r) => r,
            Err(e) => return error_reply(&format!("bad request: {e}")),
        };
        match self.run(request) {
            Ok(outcome) => self.reply(outcome),
            Err(e) => error_reply(&e),
        }
    }

    fn reply(&self, outcome: Outcome) -> String {
        let reply = Reply {
            ok: true,
            view: match outcome {
                Outcome::Full => Some(self.view()),
                Outcome::Quiet => None,
            },
            error: None,
        };
        serde_json::to_string(&reply).unwrap_or_else(|e| error_reply(&format!("encode failed: {e}")))
    }

    fn view(&self) -> View {
        let active = self.active;
        View {
            pages: view::pages(&self.ws),
            blocks: active.map(|p| view::blocks(&self.doc, p)).unwrap_or_default(),
            active_page: active.map(|p| p.0),
            title: active.map(|p| view::title(&self.ws, p)).unwrap_or_default(),
            icon: active.and_then(|p| self.ws.get(p)).map(|r| r.icon.clone()).unwrap_or_default(),
            locked: active.map(|p| self.ws.is_locked(p)).unwrap_or(false),
            theme: self.theme.clone(),
            recents: self.recents.iter().map(|p| p.0).collect(),
            favorites: self.ws.favorites().into_iter().map(|p| p.0).collect(),
            can_undo: self.can_undo,
            can_redo: self.can_redo,
            notice: self.notice.clone(),
            page_count: self.ws.len(),
        }
    }

    fn run(&mut self, request: Request) -> Result<Outcome, String> {
        match request {
            Request::Boot => Ok(Outcome::Full),
            Request::Tick => {
                self.persistence.flush_if_due().map_err(|e| e.to_string())?;
                Ok(Outcome::Quiet)
            }
            Request::Flush => {
                self.persistence.force_flush().map_err(|e| e.to_string())?;
                Ok(Outcome::Quiet)
            }
            Request::OpenPage { page } => self.open_page(PageId(page)),
            Request::CreatePage { parent, title } => {
                self.create_page(parent.map(PageId), title)
            }
            Request::RenamePage { page, title } => self.rename_page(PageId(page), title),
            Request::DeletePage { page } => self.delete_page(PageId(page)),
            Request::ToggleFavorite { page } => self.toggle_favorite(PageId(page)),
            Request::ToggleExpanded { page } => self.toggle_expanded(PageId(page)),
            Request::SetPageLocked { page, locked } => self.set_locked(PageId(page), locked),
            Request::SetTheme { theme } => self.set_theme(&theme),
            Request::SetBlockText { block, text } => {
                self.block_command(block, |id| Command::ReplaceText { id, text: text.clone() }, true)
            }
            Request::InsertBlockAfter { block, kind, text } => {
                let kind = parse_kind(&kind)?;
                self.block_command(
                    block,
                    move |id| Command::InsertBlockAfter { id, kind, text: text.clone() },
                    false,
                )
            }
            Request::AppendBlock { kind, text } => {
                let kind = parse_kind(&kind)?;
                let page = self.active.ok_or("no page is open")?;
                self.exec(page, Command::AppendBlock { kind, text }, false)
            }
            Request::DeleteBlock { block } => {
                self.block_command(block, |id| Command::DeleteBlock { id }, false)
            }
            Request::SetBlockKind { block, kind } => {
                let kind = parse_kind(&kind)?;
                self.block_command(block, move |id| Command::SetBlockType { id, kind }, false)
            }
            Request::ToggleChecked { block } => {
                self.block_command(block, |id| Command::ToggleTodoChecked { id }, false)
            }
            Request::ToggleFold { block } => {
                self.block_command(block, |id| Command::ToggleFold { id }, false)
            }
            Request::MoveBlock { block, delta } => {
                self.block_command(block, move |id| Command::MoveBlock { id, delta }, false)
            }
            Request::MoveBlockTo { block, index } => {
                self.block_command(block, move |id| Command::MoveBlockTo { id, index }, false)
            }
            Request::IndentList { block } => {
                self.block_command(block, |id| Command::IndentList { id }, false)
            }
            Request::OutdentList { block } => {
                self.block_command(block, |id| Command::OutdentList { id }, false)
            }
            Request::Undo => self.walk_history(true),
            Request::Redo => self.walk_history(false),
        }
    }

    /// Run one command against the page that owns `block`.
    ///
    /// The page is derived from the block rather than passed in: a client that
    /// had to name both would be a client that could name them inconsistently,
    /// and the core already knows which page a block belongs to.
    fn block_command(
        &mut self,
        block: u64,
        make: impl FnOnce(BlockId) -> Command,
        quiet: bool,
    ) -> Result<Outcome, String> {
        let id = BlockId(block);
        let page = self
            .doc
            .block(id)
            .map(|b| b.page)
            .ok_or_else(|| format!("no such block: {block}"))?;
        self.exec(page, make(id), quiet)
    }

    fn exec(&mut self, page: PageId, cmd: Command, quiet: bool) -> Result<Outcome, String> {
        if self.ws.is_locked(page) {
            return Err("this page is read-only".into());
        }
        if let Some(changes) = command::exec(&mut self.doc, &mut self.hist, page, cmd) {
            self.persistence.record(changes);
            self.can_undo = true;
            self.can_redo = false;
        }
        Ok(if quiet { Outcome::Quiet } else { Outcome::Full })
    }

    fn walk_history(&mut self, undo: bool) -> Result<Outcome, String> {
        let page = self.active.ok_or("no page is open")?;
        if self.ws.is_locked(page) {
            return Err("this page is read-only".into());
        }
        let changes = if undo {
            command::undo(&mut self.doc, &mut self.hist, page)
        } else {
            command::redo(&mut self.doc, &mut self.hist, page)
        };
        match changes {
            Some(changes) => {
                self.persistence.record(changes);
                self.can_undo = true;
                self.can_redo = true;
            }
            // Nothing left in that direction: the step must not be remembered as
            // having happened, or the button stays lit over an empty stack.
            None => {
                if undo {
                    self.can_undo = false;
                } else {
                    self.can_redo = false;
                }
            }
        }
        Ok(Outcome::Full)
    }

    // ─── the page tree ──────────────────────────────────────────────────────
    //
    // These are the writes with no `Command` behind them. Each pairs its
    // in-memory edit with the `Change` that persists it and flushes at once:
    // page structure is small, rare and the thing a lost write is most visible
    // in — a page that vanished, a rename that did not stick.

    fn open_page(&mut self, page: PageId) -> Result<Outcome, String> {
        if !self.ws.contains(page) {
            return Err(format!("no such page: {}", page.0));
        }
        self.active = Some(page);
        // Walking into a deep page has to open every branch above it, or the
        // row the user is looking at is not on screen.
        self.ws.expand_ancestors(page);
        self.mark_opened(page);
        self.persist_expanded();
        Ok(Outcome::Full)
    }

    fn create_page(&mut self, parent: Option<PageId>, title: String) -> Result<Outcome, String> {
        if let Some(parent) = parent {
            if !self.ws.contains(parent) {
                return Err(format!("no such page: {}", parent.0));
            }
        }
        let id = self.ws.next_id();
        let title = if title.trim().is_empty() { UNTITLED.to_string() } else { title };
        let order = self.ws.append_order(parent);
        let page = Page {
            id,
            title: title.clone(),
            parent,
            order,
            favorite: false,
            // A new page opens its own branch: a parent whose child is hidden
            // looks like the create failed.
            expanded: true,
            font: PageFont::Default,
            full_width: false,
            small_text: false,
            icon: String::new(),
            cover: None,
            locked: false,
            template: false,
        };

        let mut changes = vec![Change::PageCreated(page)];
        let mut expand_parent = false;
        if let Some(parent) = parent {
            if let Some(rec) = self.ws.get(parent) {
                if !rec.expanded {
                    expand_parent = true;
                }
            }
        }
        if expand_parent {
            if let Some(parent) = parent {
                self.ws.set_expanded(parent, true);
                changes.push(Change::PageExpandedSet { id: parent, expanded: true });
            }
        }
        self.apply_now(changes)?;
        self.ws.insert(PageRec {
            id,
            title,
            parent,
            order,
            favorite: false,
            expanded: true,
            icon: String::new(),
            locked: false,
        });
        self.active = Some(id);
        self.mark_opened(id);
        Ok(Outcome::Full)
    }

    fn rename_page(&mut self, page: PageId, title: String) -> Result<Outcome, String> {
        if !self.ws.contains(page) {
            return Err(format!("no such page: {}", page.0));
        }
        // The stored title is what the row shows, so an empty one is stored as
        // the untitled placeholder rather than as a blank row nobody can click.
        let title = if title.trim().is_empty() { UNTITLED.to_string() } else { title };
        self.ws.set_title(page, &title);
        self.apply_now(vec![Change::PageTitleSet { id: page, title }])?;
        Ok(Outcome::Full)
    }

    fn delete_page(&mut self, page: PageId) -> Result<Outcome, String> {
        if !self.ws.contains(page) {
            return Err(format!("no such page: {}", page.0));
        }
        let gone = self.ws.remove_subtree(page);
        // Storage cascades sub-pages off one `PageDeleted`; the document holds
        // their blocks and has to be told page by page.
        self.apply_now(vec![Change::PageDeleted { id: page }])?;
        for id in &gone {
            self.doc.drop_page(*id);
        }
        self.recents.retain(|p| !gone.contains(p));
        if self.active.map(|a| gone.contains(&a)).unwrap_or(false) {
            self.active = self.ws.first_root();
        }
        self.save_recents();
        Ok(Outcome::Full)
    }

    fn toggle_favorite(&mut self, page: PageId) -> Result<Outcome, String> {
        let value = !self.ws.get(page).ok_or_else(|| format!("no such page: {}", page.0))?.favorite;
        self.ws.set_favorite(page, value);
        // Persisted view state, not content: queued, not flushed (the desktop
        // reads it the same way, and a star is not worth a disk sync).
        self.record(vec![Change::PageFavoriteSet { id: page, favorite: value }]);
        Ok(Outcome::Full)
    }

    fn toggle_expanded(&mut self, page: PageId) -> Result<Outcome, String> {
        let value = !self.ws.get(page).ok_or_else(|| format!("no such page: {}", page.0))?.expanded;
        self.ws.set_expanded(page, value);
        self.record(vec![Change::PageExpandedSet { id: page, expanded: value }]);
        self.persist_expanded();
        Ok(Outcome::Full)
    }

    /// The read-only switch (SPEC §三十八 "lock"). A page property rather than
    /// an edit of the document, which is why it is stored the same way the
    /// favorites star is — and why undoing it is not on any stack (ADR-0044).
    fn set_locked(&mut self, page: PageId, locked: bool) -> Result<Outcome, String> {
        if !self.ws.contains(page) {
            return Err(format!("no such page: {}", page.0));
        }
        self.ws.set_locked(page, locked);
        self.apply_now(vec![Change::PageLockedSet { id: page, locked }])?;
        Ok(Outcome::Full)
    }

    fn set_theme(&mut self, theme: &str) -> Result<Outcome, String> {
        if !matches!(theme, "light" | "dark" | "system") {
            return Err(format!("unknown theme: {theme}"));
        }
        self.settings.set_theme(theme);
        self.theme = theme.to_string();
        self.record(vec![Change::SettingSet {
            key: Settings::KEY_THEME.to_string(),
            value: theme.to_string(),
        }]);
        Ok(Outcome::Full)
    }

    // ─── persistence plumbing ───────────────────────────────────────────────

    /// Apply changes now, in one transaction, instead of queueing them.
    fn apply_now(&self, changes: Vec<Change>) -> Result<(), String> {
        self.repo.apply(&changes).map_err(|e| e.to_string())
    }

    fn record(&self, changes: Vec<Change>) {
        self.persistence.record(changes);
    }

    /// Remember a page as opened: newest first, capped, and written to `meta`.
    fn mark_opened(&mut self, page: PageId) {
        self.recents.retain(|p| *p != page);
        self.recents.insert(0, page);
        self.recents.truncate(MAX_RECENTS);
        self.record(vec![Change::MetaSet {
            key: META_RECENTS.to_string(),
            value: join_ids(&self.recents),
        }]);
    }

    /// The open branches, in the core's own `sidebar.expanded` shape — the key
    /// `Settings::expanded_pages` reads, so a fold made here is folded in the
    /// other shells too.
    fn persist_expanded(&self) {
        let mut settings = Settings::new();
        settings.set_expanded_pages(&self.ws.expanded_ids());
        if let Some(value) = settings.get(Settings::KEY_EXPANDED) {
            self.record(vec![Change::SettingSet {
                key: Settings::KEY_EXPANDED.to_string(),
                value: value.to_string(),
            }]);
        }
    }

    /// Write the recents list immediately (deleting a page rewrites it, and a
    /// stale id there would be a row pointing at nothing next launch).
    fn save_recents(&self) {
        self.record(vec![Change::MetaSet {
            key: META_RECENTS.to_string(),
            value: join_ids(&self.recents),
        }]);
        let _ = self.persistence.force_flush();
    }
}

fn parse_kind(kind: &str) -> Result<BlockKind, String> {
    if kind.is_empty() {
        return Ok(BlockKind::Paragraph);
    }
    BlockKind::try_from_str(kind).ok_or_else(|| format!("unknown block kind: {kind}"))
}

fn join_ids(ids: &[PageId]) -> String {
    ids.iter().map(|p| p.0.to_string()).collect::<Vec<_>>().join(",")
}

/// Parse `meta.recents`. Ids that no longer name a page are dropped here rather
/// than checked at every use — this is the one place the list enters the
/// session.
fn parse_recents(raw: Option<&str>, ws: &Workspace) -> Vec<PageId> {
    raw.unwrap_or("")
        .split(',')
        .filter_map(|part| part.trim().parse::<u64>().ok())
        .map(PageId)
        .filter(|id| ws.contains(*id))
        .take(MAX_RECENTS)
        .collect()
}

pub fn error_reply(message: &str) -> String {
    serde_json::to_string(&Reply {
        ok: false,
        view: None,
        error: Some(message.to_string()),
    })
    .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"bridge failure\"}".to_string())
}
