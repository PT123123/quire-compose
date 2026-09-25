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

use crate::org::Organizer;
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

    // ─── SPEC §四十一: notes and tasks ───────────────────────────────────────
    //
    // The area's writes, one arm per thing the UI can do. They are *not* the
    // core's `Command` enum spelled in JSON: a request names a row and the value
    // it now has, and `org.rs` reads the row's previous state out of the catalog
    // it holds — the plan has no store to read from. The high-level shape is what
    // keeps the Kotlin side from having to mirror nine change variants and an
    // ordering rule; it sends intent, and the session decides what that means.
    /// A new note from the quick-capture sheet: its text and the tags its
    /// `#tokens` name, in one command — so one 撤销 puts the whole note away.
    OrgAddNote {
        #[serde(default)]
        body: String,
        #[serde(default)]
        tags: String,
        /// The note this one answers, when it is a comment. Absent for an ordinary
        /// note — `rename = "ref"` because that is the key the Kotlin bridge sends.
        #[serde(default, rename = "ref")]
        ref_note: Option<i64>,
    },
    /// A note's whole text and its tags together — what the editor sheet commits,
    /// for the reason above: one sheet, one step.
    OrgNoteContent {
        note: i64,
        #[serde(default)]
        body: String,
        #[serde(default)]
        tags: String,
    },
    OrgNoteTitle {
        note: i64,
        title: String,
    },
    OrgNoteBody {
        note: i64,
        body: String,
    },
    OrgNotePinned {
        note: i64,
        pinned: bool,
    },
    /// Comma-separated, parsed on this side so one place decides what the input
    /// means.
    OrgNoteTags {
        note: i64,
        tags: String,
    },
    OrgDeleteNote {
        note: i64,
    },

    /// The quick-capture sheet: create, title and tag in one step, and set the
    /// deadline when `due` is given (the 今天 view types a task that is due today).
    OrgQuickAdd {
        list: i64,
        #[serde(default)]
        title: String,
        #[serde(default)]
        tags: String,
        #[serde(default)]
        due: Option<String>,
    },
    OrgTaskTitle {
        task: i64,
        title: String,
    },
    OrgTaskNotes {
        task: i64,
        notes: String,
    },
    OrgTaskDone {
        task: i64,
        done: bool,
    },
    /// The priority menu's slot — the position in `Priority::ALL`.
    OrgTaskPriority {
        task: i64,
        slot: i32,
    },
    /// `YYYY-MM-DD`, or `""` for none.
    OrgTaskDue {
        task: i64,
        due: String,
    },
    /// The repeat menu's slot — the position in `Repeat::ALL`.
    OrgTaskRepeat {
        task: i64,
        slot: i32,
    },
    OrgTaskTags {
        task: i64,
        tags: String,
    },
    /// Move a task to another list (`-1` = the inbox). The board's cross-column
    /// drop and the row menu's 移到 both land here.
    OrgTaskList {
        task: i64,
        list: i64,
    },
    OrgDeleteTask {
        task: i64,
    },

    OrgSubtaskAdd {
        task: i64,
    },
    OrgSubtaskTitle {
        task: i64,
        subtask: i64,
        title: String,
    },
    OrgSubtaskDone {
        task: i64,
        subtask: i64,
        done: bool,
    },
    OrgSubtaskDelete {
        task: i64,
        subtask: i64,
    },

    /// A new list. A blank name becomes 新建清单, as it does on the desktop.
    OrgCreateList {
        #[serde(default)]
        name: String,
    },
    OrgListName {
        list: i64,
        name: String,
    },
    /// The chip's colour dot, by palette slot.
    OrgListColor {
        list: i64,
        slot: i32,
    },
    /// Delete a list and file its tasks in the inbox, in one undoable step.
    OrgDeleteList {
        list: i64,
    },
    /// Walk the area's own stack — never the open page's.
    OrgUndo,
    OrgRedo,

    // ─── LAN sync (`crate::sync`) ───────────────────────────────────────────
    //
    // The page's own verbs, and no more than the page draws. Every one answers
    // with the whole view, because a peer table, a log line or a status word is
    // what the screen is made of — there is no keystroke path here to keep off
    // the bridge.
    /// The 同步 page's state, and the request that starts the engine: opening the
    /// page is what puts this device on the LAN, so nothing listens before it.
    SyncState,
    SyncSetAuto {
        on: bool,
    },
    SyncSetInterval {
        seconds: u64,
    },
    SyncSetName {
        #[serde(default)]
        name: String,
    },
    /// Pair with a device already heard on the wire.
    SyncPair {
        #[serde(default)]
        id: String,
    },
    /// `192.168.1.20` or `192.168.1.20:5878` — the door for a network where the
    /// announcement cannot get through.
    SyncAddPeer {
        #[serde(default)]
        ip: String,
        #[serde(default)]
        port: u16,
    },
    SyncNow {
        #[serde(default)]
        id: String,
    },
    SyncForget {
        #[serde(default)]
        id: String,
    },
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
    pub(crate) repo: Arc<SqliteRepository>,
    pub(crate) persistence: PersistenceService,
    pub(crate) doc: Document,
    pub(crate) hist: History,
    pub(crate) ws: Workspace,
    /// SPEC §四十一's area: the catalog, its id watermarks, and the area's own
    /// undo bookkeeping. Held here rather than in Kotlin because the writes need
    /// it — `Command::Update*` is handed the row *before* and the row *after*, and
    /// only the owner of the catalog has the first of those.
    pub(crate) org: Organizer,
    pub(crate) settings: Settings,
    pub(crate) active: Option<PageId>,
    pub(crate) theme: String,
    pub(crate) recents: Vec<PageId>,
    pub(crate) can_undo: bool,
    pub(crate) can_redo: bool,
    pub(crate) notice: Option<String>,
    /// LAN sync, started the first time the 同步 page is opened. `None` until
    /// then, so a session that never opens that page spawns no threads and
    /// listens on no port (`crate::sync`).
    pub(crate) sync: Option<crate::sync::Sync>,
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

        // SPEC §四十一's catalog, read whole beside the pages. A failure here is
        // not a failure to open: the pages half still works, and the honest answer
        // is an area that draws nothing plus the reason in the notice bar.
        let (org, org_notice) = Organizer::load(&repo);
        if notice.is_none() {
            notice = org_notice;
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
            org,
            settings,
            active,
            theme,
            recents,
            can_undo: false,
            can_redo: false,
            notice,
            // Not started here: the engine spawns four threads and binds a port,
            // and an app that never opens the 同步 page should do neither.
            sync: None,
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

    /// Rebuild everything this session holds in memory from the file.
    ///
    /// Used by LAN sync after a merged snapshot has been written: the rows are in
    /// the database but the document, the page tree, the organizer and the
    /// watermarks are all stale. The undo stacks are dropped on purpose — their
    /// entries name rows the merge may have replaced, and a step that "reverts" a
    /// row nobody asked about is worse than losing the stack.
    pub(crate) fn reload_in_memory(&mut self) -> Result<(), String> {
        let state = self.repo.load().map_err(|e| e.to_string())?;

        let mut doc = Document::new(0);
        let mut by_page: HashMap<PageId, Vec<quire_core::core::Block>> = HashMap::new();
        for block in &state.blocks {
            by_page.entry(block.page).or_default().push(block.clone());
        }
        for (page, blocks) in by_page {
            doc.set_page_blocks(page, blocks);
        }
        self.doc = doc;

        self.ws = Workspace::from_persisted(&state.pages);
        let (settings, meta) = Settings::from_state(&state);
        self.settings = settings;
        self.theme = self.settings.theme().unwrap_or("system").to_string();
        self.recents = parse_recents(meta.get(META_RECENTS), &self.ws);
        self.active = self
            .active
            .filter(|p| self.ws.contains(*p))
            .or_else(|| self.ws.first_root());

        let (org, _org_notice) = Organizer::load(&self.repo);
        self.org = org;

        self.hist = History::default();
        self.can_undo = false;
        self.can_redo = false;
        Ok(())
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
            org_can_undo: self.org.can_undo(),
            org_can_redo: self.org.can_redo(),
            notice: self.notice.clone(),
            page_count: self.ws.len(),
            org: view::org_catalog(self.org.catalog()),
            sync: self.sync_view(),
        }
    }

    fn run(&mut self, request: Request) -> Result<Outcome, String> {
        match request {
            Request::Boot => Ok(Outcome::Full),
            Request::Tick => {
                self.persistence.flush_if_due().map_err(|e| e.to_string())?;
                // LAN sync's progress rides this tick. The engine's threads block
                // until the session answers them, and this is the session's one
                // heartbeat — so a cycle advances without a timer of its own, and
                // an idle app's second still answers with nothing to draw.
                Ok(if self.sync_pump() {
                    Outcome::Full
                } else {
                    Outcome::Quiet
                })
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

            // ─── SPEC §四十一 ────────────────────────────────────────────────
            //
            // Every one of these answers with the whole view. That is not the
            // cheap answer — an organizer edit echoes the catalog back, and a
            // catalog is a few hundred rows — but it is the *correct* one: the row
            // the user just typed into has a new `edited` instant this side
            // stamped, a smart view may have gained or lost the task, and the
            // counts in the chips moved. The Kotlin side debounces the two fields
            // a person types into, which is what keeps that cost off the keystroke
            // path.
            Request::OrgAddNote { body, tags, ref_note } => {
                self.org_op(|org, doc, hist| org.add_note(doc, hist, body, tags, ref_note))
            }
            Request::OrgNoteContent { note, body, tags } => self.org_op(|org, doc, hist| {
                org.set_note_content(doc, hist, note, body, tags)
            }),
            Request::OrgNoteTitle { note, title } => {
                self.org_op(|org, doc, hist| org.note_title(doc, hist, note, title))
            }
            Request::OrgNoteBody { note, body } => {
                self.org_op(|org, doc, hist| org.note_body(doc, hist, note, body))
            }
            Request::OrgNotePinned { note, pinned } => {
                self.org_op(|org, doc, hist| org.note_pinned(doc, hist, note, pinned))
            }
            Request::OrgNoteTags { note, tags } => {
                self.org_op(|org, doc, hist| org.note_tags(doc, hist, note, tags))
            }
            Request::OrgDeleteNote { note } => {
                self.org_op(|org, doc, hist| org.delete_note(doc, hist, note))
            }
            Request::OrgQuickAdd { list, title, tags, due } => self.org_op(|org, doc, hist| {
                org.quick_add(doc, hist, list, title, tags, due)
            }),
            Request::OrgTaskTitle { task, title } => {
                self.org_op(|org, doc, hist| org.task_title(doc, hist, task, title))
            }
            Request::OrgTaskNotes { task, notes } => {
                self.org_op(|org, doc, hist| org.task_notes(doc, hist, task, notes))
            }
            Request::OrgTaskDone { task, done } => {
                self.org_op(|org, doc, hist| org.task_done(doc, hist, task, done))
            }
            Request::OrgTaskPriority { task, slot } => {
                self.org_op(|org, doc, hist| org.task_priority(doc, hist, task, slot))
            }
            Request::OrgTaskDue { task, due } => {
                self.org_op(|org, doc, hist| org.task_due(doc, hist, task, due))
            }
            Request::OrgTaskRepeat { task, slot } => {
                self.org_op(|org, doc, hist| org.task_repeat(doc, hist, task, slot))
            }
            Request::OrgTaskTags { task, tags } => {
                self.org_op(|org, doc, hist| org.task_tags(doc, hist, task, tags))
            }
            Request::OrgTaskList { task, list } => {
                self.org_op(|org, doc, hist| org.task_list(doc, hist, task, list))
            }
            Request::OrgDeleteTask { task } => {
                self.org_op(|org, doc, hist| org.delete_task(doc, hist, task))
            }
            Request::OrgSubtaskAdd { task } => {
                self.org_op(|org, doc, hist| org.subtask_add(doc, hist, task))
            }
            Request::OrgSubtaskTitle { task, subtask, title } => self.org_op(|org, doc, hist| {
                org.subtask_title(doc, hist, task, subtask, title)
            }),
            Request::OrgSubtaskDone { task, subtask, done } => self.org_op(|org, doc, hist| {
                org.subtask_done(doc, hist, task, subtask, done)
            }),
            Request::OrgSubtaskDelete { task, subtask } => self.org_op(|org, doc, hist| {
                org.subtask_delete(doc, hist, task, subtask)
            }),
            Request::OrgCreateList { name } => {
                self.org_op(|org, doc, hist| org.create_list(doc, hist, name))
            }
            Request::OrgListName { list, name } => {
                self.org_op(|org, doc, hist| org.list_name(doc, hist, list, name))
            }
            Request::OrgListColor { list, slot } => {
                self.org_op(|org, doc, hist| org.list_color(doc, hist, list, slot))
            }
            Request::OrgDeleteList { list } => {
                self.org_op(|org, doc, hist| org.delete_list(doc, hist, list))
            }
            Request::OrgUndo => self.walk_org_history(true),
            Request::OrgRedo => self.walk_org_history(false),

            // ─── LAN sync ───────────────────────────────────────────────────
            //
            // `SyncState` is the page being opened, and it is what starts the
            // engine — so a session that never shows that page never listens.
            Request::SyncState => {
                self.sync_ensure()?;
                self.sync_pump();
                Ok(Outcome::Full)
            }
            Request::SyncSetAuto { on } => {
                self.sync_set_auto(on)?;
                Ok(Outcome::Full)
            }
            Request::SyncSetInterval { seconds } => {
                self.sync_set_interval(seconds)?;
                Ok(Outcome::Full)
            }
            Request::SyncSetName { name } => {
                self.sync_set_name(&name)?;
                Ok(Outcome::Full)
            }
            Request::SyncPair { id } => {
                self.sync_pair(&id)?;
                Ok(Outcome::Full)
            }
            Request::SyncAddPeer { ip, port } => {
                self.sync_ensure()?;
                self.sync_add_peer(&ip, port)?;
                Ok(Outcome::Full)
            }
            Request::SyncNow { id } => {
                self.sync_now(&id)?;
                Ok(Outcome::Full)
            }
            Request::SyncForget { id } => {
                self.sync_forget_peer(&id)?;
                Ok(Outcome::Full)
            }
        }
    }

    /// One organizer write: run it, hand the changes it planned to the writer, and
    /// answer with the whole view.
    ///
    /// The three borrows are disjoint fields, which is why this can be one closure
    /// rather than three accessor methods on `Session`.
    fn org_op(
        &mut self,
        op: impl FnOnce(&mut Organizer, &mut Document, &mut History) -> Result<Vec<Change>, String>,
    ) -> Result<Outcome, String> {
        let changes = op(&mut self.org, &mut self.doc, &mut self.hist)?;
        self.record(changes);
        Ok(Outcome::Full)
    }

    /// Undo or redo inside the area — always [`ORGANIZER_STACK`], never the open
    /// page's, so a step here cannot reach a document edit and vice versa.
    fn walk_org_history(&mut self, undo: bool) -> Result<Outcome, String> {
        let changes = if undo {
            self.org.undo(&mut self.doc, &mut self.hist)
        } else {
            self.org.redo(&mut self.doc, &mut self.hist)
        };
        match changes {
            Some(changes) => self.record(changes),
            // Nothing left in that direction: the step must not be remembered as
            // having happened, or the button stays lit over an empty stack.
            None => self.org.set_exhausted(undo),
        }
        Ok(Outcome::Full)
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
    pub(crate) fn apply_now(&self, changes: Vec<Change>) -> Result<(), String> {
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
