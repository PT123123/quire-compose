//! SPEC §四十一's second top-level area: notes and tasks.
//!
//! The session's half of the organizer, and deliberately a *thin* half: the
//! catalog is loaded whole and handed to the Kotlin side as rows, and Kotlin
//! derives the five smart views, the three sorts and the row badges from it. That
//! is the split the core's own comment describes ("the organizer is a few hundred
//! rows a user typed, its 'views' are computed in memory from the whole set"),
//! and it is where each half belongs: this module owns the *writes* — ids, the
//! clock, the command funnel and the area's own undo stack — while the projection
//! rules live in the shell's app layer, which for this shell is Kotlin. The Rust
//! shell's app layer does the same job in Rust; nobody's core does it.
//!
//! Four rules travel with the writes, all of them the Rust shell's and stated
//! once there (`src/app/state.rs`), repeated here because they are what makes the
//! area behave rather than what makes it compile:
//!
//! * **One funnel.** Every write goes through `quire_core::core::command::exec`
//!   on [`ORGANIZER_STACK`], so undo and redo walk the area's own entries and a
//!   step here can never reach a document edit.
//! * **Instants are stamped here**, as unix seconds written into the row before
//!   the command is planned — so undoing a delete restores the *same* birthday
//!   rather than inventing a new one. `quire-core` has no clock on purpose.
//! * **A row that did not move is not a step.** `edit_note`/`edit_task`/
//!   `edit_list` return nothing when the edit changed nothing, and a refused edit
//!   must not restamp `edited` — otherwise clicking into a title and out again
//!   would leave the row newer than the edit that made it.
//! * **The caller records.** Every operation hands back the `Change`s it planned,
//!   and the session feeds them to the persistence service; nothing in here knows
//!   the store exists.
//!
//! Ids come from three watermarks seeded from the loaded catalog, the same
//! `max + 1` rule the page workspace uses. A subtask draws on the *task*
//! counter: its id is scoped to the task that holds it and the two are never
//! cross-referenced (`core::organizer::Subtask`).

use quire_core::core::command::{self, Command};
use quire_core::core::organizer::{
    ListId, Note, NoteId, OrganizerCatalog, Priority, Repeat, Subtask, Task, TaskId, TaskList,
};
use quire_core::core::types::ColorKind;
use quire_core::core::{
    is_iso_date, Change, Document, History, OrderKey, ORGANIZER_STACK,
};
use quire_core::storage::SqliteRepository;

/// The colours a new list draws from, in rotation: the document editor's own
/// closed palette minus `Default` (a dot with no colour is not a dot). The order
/// is the Rust shell's, kept here so a list created on the phone lands on the
/// same colour a list created on the desktop would.
const LIST_COLORS: [ColorKind; 6] = [
    ColorKind::Blue,
    ColorKind::Green,
    ColorKind::Orange,
    ColorKind::Purple,
    ColorKind::Pink,
    ColorKind::Yellow,
];

/// The name a list gets when the user creates one without typing anything.
const UNTITLED_LIST: &str = "新建清单";

/// The organizer's catalog, its id watermarks, and its undo bookkeeping.
#[derive(Debug)]
pub struct Organizer {
    catalog: OrganizerCatalog,
    next_note: u64,
    next_task: u64,
    next_list: u64,
    can_undo: bool,
    can_redo: bool,
}

impl Organizer {
    /// Read the whole catalog, or hand back a reason it could not be read.
    ///
    /// A library whose organizer half will not open is a library the *pages* half
    /// still opens: the caller keeps an empty catalog and shows the reason in the
    /// notice bar, which is the honest answer for a corrupt `tasks` table
    /// (`state.rs` makes the same call on the Rust side).
    pub fn load(repo: &SqliteRepository) -> (Organizer, Option<String>) {
        match repo.load_organizer() {
            Ok(catalog) => (Organizer::from_catalog(catalog), None),
            Err(e) => (
                Organizer::from_catalog(OrganizerCatalog::default()),
                Some(format!("笔记与任务读不出来 — 这一块先空着（{e}）")),
            ),
        }
    }

    /// Seed the three watermarks from what is already stored: `max + 1`, with a
    /// floor of 1 so the first row of a fresh area is row 1 and not row 0 (the
    /// inbox's sentinel — `ListId::INBOX`).
    fn from_catalog(catalog: OrganizerCatalog) -> Organizer {
        let next_note = catalog.notes.iter().map(|n| n.id.0).max().unwrap_or(0) + 1;
        // A subtask's id draws on the task counter, so the floor has to clear
        // every subtask already stored or a new checklist line would collide with
        // one inside another task.
        let next_task = catalog
            .tasks
            .iter()
            .flat_map(|t| {
                t.subtasks
                    .iter()
                    .map(|s| s.id)
                    .chain(std::iter::once(t.id.0))
            })
            .max()
            .unwrap_or(0)
            + 1;
        let next_list = catalog.lists.iter().map(|l| l.id.0).max().unwrap_or(0) + 1;
        Organizer {
            catalog,
            next_note,
            next_task,
            next_list,
            can_undo: false,
            can_redo: false,
        }
    }

    pub fn catalog(&self) -> &OrganizerCatalog {
        &self.catalog
    }

    pub fn can_undo(&self) -> bool {
        self.can_undo
    }

    pub fn can_redo(&self) -> bool {
        self.can_redo
    }

    // ─── the funnel ─────────────────────────────────────────────────────────

    /// Plan, apply and fold one organizer command on the area's own stack.
    /// `None` is the plan's own refusal — a no-op edit, a row that is not there —
    /// and a refusal must not be remembered as having happened.
    fn apply(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        cmd: Command,
    ) -> Option<Vec<Change>> {
        let changes = command::exec(doc, hist, ORGANIZER_STACK, cmd)?;
        self.absorb(&changes);
        self.can_undo = true;
        self.can_redo = false;
        Some(changes)
    }

    pub fn undo(&mut self, doc: &mut Document, hist: &mut History) -> Option<Vec<Change>> {
        let changes = command::undo(doc, hist, ORGANIZER_STACK)?;
        self.absorb(&changes);
        self.can_undo = true;
        self.can_redo = true;
        Some(changes)
    }

    pub fn redo(&mut self, doc: &mut Document, hist: &mut History) -> Option<Vec<Change>> {
        let changes = command::redo(doc, hist, ORGANIZER_STACK)?;
        self.absorb(&changes);
        self.can_undo = true;
        self.can_redo = true;
        Some(changes)
    }

    /// The stack ran out in one direction. The button must stop claiming a step
    /// exists, or it stays lit over an empty stack — the same rule the editor's
    /// history walk keeps.
    pub fn set_exhausted(&mut self, undo: bool) {
        if undo {
            self.can_undo = false;
        } else {
            self.can_redo = false;
        }
    }

    /// Fold a change list into the in-memory catalog — the one reader of the nine
    /// organizer variants, and idempotent by construction, which is what lets
    /// undo, redo and a merge all arrive through here. (`*Added` upserts because
    /// a redo arrives as an add.)
    fn absorb(&mut self, changes: &[Change]) {
        for change in changes {
            match change {
                Change::NoteAdded(note) => {
                    if self.catalog.note(note.id).is_none() {
                        self.catalog.notes.push(note.clone());
                    }
                }
                Change::NoteUpdated(note) => {
                    if let Some(row) = self.catalog.notes.iter_mut().find(|n| n.id == note.id) {
                        *row = note.clone();
                    }
                }
                Change::NoteDeleted { id } => self.catalog.notes.retain(|n| n.id != *id),
                Change::TaskListAdded(list) => {
                    if self.catalog.list(list.id).is_none() {
                        self.catalog.lists.push(list.clone());
                    }
                }
                Change::TaskListUpdated(list) => {
                    if let Some(row) = self.catalog.lists.iter_mut().find(|l| l.id == list.id) {
                        *row = list.clone();
                    }
                }
                // The list goes and its tasks stay, naming an id nothing holds —
                // which is why the *read* side folds a missing list to the inbox.
                // The command that deletes a list moves its tasks in the same
                // batch, so this is the half-arrived case.
                Change::TaskListDeleted { id } => self.catalog.lists.retain(|l| l.id != *id),
                Change::TaskAdded(task) => {
                    if self.catalog.task(task.id).is_none() {
                        self.catalog.tasks.push(task.clone());
                    }
                }
                Change::TaskUpdated(task) => {
                    if let Some(row) = self.catalog.tasks.iter_mut().find(|t| t.id == task.id) {
                        *row = task.clone();
                    }
                }
                Change::TaskDeleted { id } => self.catalog.tasks.retain(|t| t.id != *id),
                // Blocks, pages, settings, §三十九's layer: none of them is in
                // this catalog.
                _ => {}
            }
        }
    }

    /// A row by the id the UI handed back. An id that names nothing is a bug
    /// worth refusing loudly rather than swallowing as a no-op.
    fn note_of(&self, id: i64) -> Option<Note> {
        self.catalog.note(NoteId(id.max(0) as u64)).cloned()
    }

    fn task_of(&self, id: i64) -> Option<Task> {
        self.catalog.task(TaskId(id.max(0) as u64)).cloned()
    }

    fn list_of(&self, id: i64) -> Option<TaskList> {
        self.catalog.list(ListId(id.max(0) as u64)).cloned()
    }

    // ─── notes ──────────────────────────────────────────────────────────────

    /// A new note with its text and its tags already in it.
    ///
    /// This is the phone's one-field note: the quick-capture sheet asks for one
    /// blob of text and derives the tags from its `#tokens`, so creating the row
    /// and filling it have to be **one command** — a blank row nobody asked for is
    /// not a step the undo stack should hold, and "undo my note" must put it away
    /// in one press.
    ///
    /// The core's `title` stays empty: this shell's note *is* its text (the
    /// reference app's is too), and a derived title would show the first line
    /// twice on the desktop, whose list paints a title and an excerpt.
    pub fn add_note(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        body: String,
        tags: String,
    ) -> Result<Vec<Change>, String> {
        let id = self.next_note;
        let now = now_secs();
        let note = Note {
            id: NoteId(id),
            title: String::new(),
            body,
            pinned: false,
            tags: parse_tags(&tags),
            created: now,
            edited: now,
        };
        let changes = self
            .apply(doc, hist, Command::CreateNote { note })
            .ok_or("新建笔记没有落下来")?;
        self.next_note = id + 1;
        Ok(changes)
    }

    fn edit_note(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        edit: impl FnOnce(&mut Note),
    ) -> Result<Vec<Change>, String> {
        let before = self.note_of(id).ok_or_else(|| format!("no such note: {id}"))?;
        let mut after = before.clone();
        edit(&mut after);
        if after == before {
            // Nothing moved: no step, and `edited` stays where it was.
            return Ok(Vec::new());
        }
        after.edited = now_secs();
        Ok(self
            .apply(
                doc,
                hist,
                Command::UpdateNote {
                    id: NoteId(id.max(0) as u64),
                    before,
                    after,
                },
            )
            .unwrap_or_default())
    }

    /// A note's whole text and its tags, in one step — what the editor sheet
    /// commits. The two travel together because one sheet produced both: a body
    /// command plus a tags command would be two presses of 撤销 for one edit.
    pub fn set_note_content(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        body: String,
        tags: String,
    ) -> Result<Vec<Change>, String> {
        let tags = parse_tags(&tags);
        self.edit_note(doc, hist, id, move |n| {
            n.body = body;
            n.tags = tags;
        })
    }

    pub fn note_title(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        title: String,
    ) -> Result<Vec<Change>, String> {
        self.edit_note(doc, hist, id, |n| n.title = title)
    }

    pub fn note_body(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        body: String,
    ) -> Result<Vec<Change>, String> {
        self.edit_note(doc, hist, id, |n| n.body = body)
    }

    pub fn note_pinned(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        pinned: bool,
    ) -> Result<Vec<Change>, String> {
        self.edit_note(doc, hist, id, |n| n.pinned = pinned)
    }

    pub fn note_tags(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        tags: String,
    ) -> Result<Vec<Change>, String> {
        let tags = parse_tags(&tags);
        self.edit_note(doc, hist, id, |n| n.tags = tags)
    }

    /// Delete a note. One step, undone by the area's own undo: the command
    /// carries the whole row, so the revert writes back its title, body, pin and
    /// tags — which is why the shell says so out loud in the notice rather than
    /// leaving a delete with no visible way back.
    pub fn delete_note(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
    ) -> Result<Vec<Change>, String> {
        let note = self.note_of(id).ok_or_else(|| format!("no such note: {id}"))?;
        self.apply(doc, hist, Command::DeleteNote { note })
            .ok_or_else(|| "这条笔记没有删掉".to_string())
    }

    // ─── tasks ──────────────────────────────────────────────────────────────

    /// Where a task appended to `list` sits: one key after the last task already
    /// there, the same "one key past the end" rule the sidebar uses.
    fn append_task_order(&self, list: ListId) -> Option<OrderKey> {
        let last = self.catalog.tasks_in(list).map(|t| t.ord).max();
        OrderKey::between(last, None)
    }

    /// A new task, already titled, tagged and (when the line it came from was
    /// 今天) already due. One `CreateTask` rather than a create plus updates, so a
    /// quick-add is **one** undo step and a blank row nobody asked for never
    /// exists to be held by the stack.
    fn new_task(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        list: i64,
        title: String,
        tags: Vec<String>,
        due: Option<String>,
    ) -> Result<Vec<Change>, String> {
        let list = if list >= 0 {
            ListId(list as u64)
        } else {
            ListId::INBOX
        };
        let id = self.next_task;
        let now = now_secs();
        let ord = self
            .append_task_order(list)
            .ok_or("清单里的顺序键用完了")?;
        let task = Task {
            id: TaskId(id),
            list,
            title,
            notes: String::new(),
            priority: Priority::None,
            due,
            repeat: Repeat::None,
            done: false,
            completed_at: None,
            tags,
            subtasks: Vec::new(),
            created: now,
            edited: now,
            ord,
        };
        let changes = self
            .apply(doc, hist, Command::CreateTask { task })
            .ok_or("新建任务没有落下来")?;
        self.next_task = id + 1;
        Ok(changes)
    }

    /// The quick-capture sheet and a board column's ＋: one write, and the row it
    /// makes is already titled and tagged. `due` is `Some` when the task was typed
    /// into 今天, because 今天 is a *date* view — a task typed into it is due today,
    /// which is the only reading of "today" that survives the next rebuild. A blank
    /// line is not a row and not a step.
    pub fn quick_add(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        list: i64,
        title: String,
        tags: String,
        due: Option<String>,
    ) -> Result<Vec<Change>, String> {
        let title = title.trim().to_string();
        let tags = parse_tags(&tags);
        if title.is_empty() && tags.is_empty() {
            return Ok(Vec::new());
        }
        let due = match due {
            Some(due) if !due.trim().is_empty() => Some(check_date(&due)?),
            _ => None,
        };
        self.new_task(doc, hist, list, title, tags, due)
    }

    fn edit_task(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        edit: impl FnOnce(&mut Task),
    ) -> Result<Vec<Change>, String> {
        let before = self.task_of(id).ok_or_else(|| format!("no such task: {id}"))?;
        let mut after = before.clone();
        edit(&mut after);
        if after == before {
            return Ok(Vec::new());
        }
        after.edited = now_secs();
        Ok(self
            .apply(
                doc,
                hist,
                Command::UpdateTask {
                    id: TaskId(id.max(0) as u64),
                    before,
                    after,
                },
            )
            .unwrap_or_default())
    }

    pub fn task_title(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        title: String,
    ) -> Result<Vec<Change>, String> {
        self.edit_task(doc, hist, id, |t| t.title = title)
    }

    pub fn task_notes(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        notes: String,
    ) -> Result<Vec<Change>, String> {
        self.edit_task(doc, hist, id, |t| t.notes = notes)
    }

    /// Tick a task. `completed_at` moves with the flag and is cleared on untick,
    /// so the two fields cannot disagree about the same fact.
    pub fn task_done(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        done: bool,
    ) -> Result<Vec<Change>, String> {
        let now = now_secs();
        self.edit_task(doc, hist, id, |t| {
            t.done = done;
            t.completed_at = done.then_some(now);
        })
    }

    pub fn task_priority(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        slot: i32,
    ) -> Result<Vec<Change>, String> {
        let priority = Priority::from_slot(slot);
        self.edit_task(doc, hist, id, |t| t.priority = priority)
    }

    /// A deadline. The shape is `core::date`'s one format and it is checked here:
    /// a picker that wrote anything else would put a string in the column that no
    /// comparison could make sense of. An empty string clears it.
    pub fn task_due(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        due: String,
    ) -> Result<Vec<Change>, String> {
        let due = due.trim().to_string();
        let due = if due.is_empty() {
            None
        } else {
            Some(check_date(&due)?)
        };
        self.edit_task(doc, hist, id, |t| t.due = due)
    }

    pub fn task_repeat(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        slot: i32,
    ) -> Result<Vec<Change>, String> {
        let repeat = Repeat::from_slot(slot);
        self.edit_task(doc, hist, id, |t| t.repeat = repeat)
    }

    pub fn task_tags(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        tags: String,
    ) -> Result<Vec<Change>, String> {
        let tags = parse_tags(&tags);
        self.edit_task(doc, hist, id, |t| t.tags = tags)
    }

    /// Move a task to another list (`list < 0` = the inbox). The same write
    /// `DeleteTaskList` plans for the tasks it rescues, which is why it is one
    /// `TaskUpdated` and needs no command of its own — and why the board's
    /// cross-column drop is this one call.
    pub fn task_list(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        list: i64,
    ) -> Result<Vec<Change>, String> {
        let list = if list >= 0 {
            ListId(list as u64)
        } else {
            ListId::INBOX
        };
        self.edit_task(doc, hist, id, |t| t.list = list)
    }

    pub fn delete_task(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
    ) -> Result<Vec<Change>, String> {
        let task = self.task_of(id).ok_or_else(|| format!("no such task: {id}"))?;
        self.apply(doc, hist, Command::DeleteTask { task })
            .ok_or_else(|| "这条任务没有删掉".to_string())
    }

    // ─── the checklist ──────────────────────────────────────────────────────

    pub fn subtask_add(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        task: i64,
    ) -> Result<Vec<Change>, String> {
        if self.task_of(task).is_none() {
            return Err(format!("no such task: {task}"));
        }
        // The counter moves only if the push landed: `edit_task` refuses an edit
        // that changed nothing, and a refused edit must not spend an id.
        let id = self.next_task;
        let changes = self.edit_task(doc, hist, task, |t| {
            t.subtasks.push(Subtask {
                id,
                title: String::new(),
                done: false,
            })
        })?;
        if !changes.is_empty() {
            self.next_task = id + 1;
        }
        Ok(changes)
    }

    pub fn subtask_title(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        task: i64,
        subtask: i64,
        title: String,
    ) -> Result<Vec<Change>, String> {
        let subtask = subtask.max(0) as u64;
        self.edit_task(doc, hist, task, move |t| {
            if let Some(row) = t.subtasks.iter_mut().find(|s| s.id == subtask) {
                row.title = title;
            }
        })
    }

    pub fn subtask_done(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        task: i64,
        subtask: i64,
        done: bool,
    ) -> Result<Vec<Change>, String> {
        let subtask = subtask.max(0) as u64;
        self.edit_task(doc, hist, task, move |t| {
            if let Some(row) = t.subtasks.iter_mut().find(|s| s.id == subtask) {
                row.done = done;
            }
        })
    }

    pub fn subtask_delete(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        task: i64,
        subtask: i64,
    ) -> Result<Vec<Change>, String> {
        let subtask = subtask.max(0) as u64;
        self.edit_task(doc, hist, task, move |t| {
            t.subtasks.retain(|s| s.id != subtask)
        })
    }

    // ─── lists ──────────────────────────────────────────────────────────────

    pub fn create_list(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        name: String,
    ) -> Result<Vec<Change>, String> {
        let id = self.next_list;
        let ord = OrderKey::between(self.catalog.lists.iter().map(|l| l.ord).max(), None)
            .ok_or("清单的顺序键用完了")?;
        let name = if name.trim().is_empty() {
            UNTITLED_LIST.to_string()
        } else {
            name
        };
        let list = TaskList {
            id: ListId(id),
            name,
            // A new list takes the next colour of the closed palette rather than
            // a random one: two lists in a row must not be the same colour, or the
            // dots stop telling them apart.
            color: LIST_COLORS[(id as usize) % LIST_COLORS.len()],
            ord,
        };
        let changes = self
            .apply(doc, hist, Command::CreateTaskList { list })
            .ok_or("新建清单没有落下来")?;
        self.next_list = id + 1;
        Ok(changes)
    }

    fn edit_list(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        edit: impl FnOnce(&mut TaskList),
    ) -> Result<Vec<Change>, String> {
        let before = self.list_of(id).ok_or_else(|| format!("no such list: {id}"))?;
        let mut after = before.clone();
        edit(&mut after);
        if after == before {
            return Ok(Vec::new());
        }
        Ok(self
            .apply(
                doc,
                hist,
                Command::UpdateTaskList {
                    id: ListId(id.max(0) as u64),
                    before,
                    after,
                },
            )
            .unwrap_or_default())
    }

    pub fn list_name(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        name: String,
    ) -> Result<Vec<Change>, String> {
        self.edit_list(doc, hist, id, |l| l.name = name)
    }

    pub fn list_color(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
        slot: i32,
    ) -> Result<Vec<Change>, String> {
        let color = ColorKind::from_slot(slot).unwrap_or(ColorKind::Default);
        self.edit_list(doc, hist, id, |l| l.color = color)
    }

    /// Delete a list and file its tasks in the inbox **in the same step** — the
    /// one command that had to exist separately from "delete a row". One undo
    /// brings the list and its tasks back together.
    pub fn delete_list(
        &mut self,
        doc: &mut Document,
        hist: &mut History,
        id: i64,
    ) -> Result<Vec<Change>, String> {
        let list = self.list_of(id).ok_or_else(|| format!("no such list: {id}"))?;
        let now = now_secs();
        let moved: Vec<(Task, Task)> = self
            .catalog
            .tasks_in(list.id)
            .map(|t| {
                let mut after = t.clone();
                after.list = ListId::INBOX;
                after.edited = now;
                (t.clone(), after)
            })
            .collect();
        self.apply(doc, hist, Command::DeleteTaskList { list, moved })
            .ok_or_else(|| "这个清单没有删掉".to_string())
    }
}

/// A date as `core::date` spells it, or a refusal. One check, so the deadline
/// column can never hold a string no comparison understands.
fn check_date(value: &str) -> Result<String, String> {
    let value = value.trim();
    if is_iso_date(value) {
        Ok(value.to_string())
    } else {
        Err(format!("不是一个日期: {value}"))
    }
}

/// A comma-separated tag input as the list a row stores: trimmed, empties
/// dropped, duplicates dropped in first-seen order. One place decides what the
/// input means, so the row a typed string produces and the string it renders back
/// cannot disagree.
///
/// The separators are the ones a Chinese or English keyboard produces for "a list
/// of things" — the two commas and the enumeration mark. **Not** a space: a tag
/// is allowed to have one ("project atlas"), and splitting on spaces would file
/// it as two tags nobody can find again.
fn parse_tags(input: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for tag in input.split([',', '，', '、', '\n', '\t']) {
        let tag = tag.trim();
        if tag.is_empty() || out.iter().any(|t| t == tag) {
            continue;
        }
        out.push(tag.to_string());
    }
    out
}

/// Now, as unix seconds. The core has no clock (`core::organizer` says so in its
/// header), so the shell stamps `created`/`edited` and the instant then rides
/// inside the `Change` — which is what makes an undone delete restore the same
/// birthday instead of inventing a new one.
pub fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_tag_input_is_split_trimmed_and_deduplicated() {
        assert_eq!(parse_tags(""), Vec::<String>::new());
        assert_eq!(parse_tags("   "), Vec::<String>::new());
        assert_eq!(parse_tags("work"), vec!["work"]);
        assert_eq!(parse_tags(" work , urgent "), vec!["work", "urgent"]);
        // The three separators a Chinese or English keyboard produces, and the
        // duplicates a second one drops.
        assert_eq!(parse_tags("a，b、c\nd"), vec!["a", "b", "c", "d"]);
        assert_eq!(parse_tags("a, a, b"), vec!["a", "b"]);
        // A space is NOT a separator: a tag is allowed to have one.
        assert_eq!(parse_tags("project atlas"), vec!["project atlas"]);
    }

    #[test]
    fn a_date_is_checked_before_it_reaches_the_column() {
        assert_eq!(check_date("2026-09-25"), Ok("2026-09-25".to_string()));
        assert_eq!(check_date("  2026-09-25  "), Ok("2026-09-25".to_string()));
        // The shapes a picker must never be allowed to write: a comparison of
        // two of these would sort by nothing, and the row's own badge would have
        // no day to name.
        assert!(check_date("2026-9-25").is_err());
        assert!(check_date("25/09/2026").is_err());
        assert!(check_date("今天").is_err());
        assert!(check_date("").is_err());
    }

    #[test]
    fn the_watermarks_clear_every_id_already_stored() {
        let organizer = Organizer::from_catalog(OrganizerCatalog {
            notes: vec![Note {
                id: NoteId(7),
                title: String::new(),
                body: String::new(),
                pinned: false,
                tags: Vec::new(),
                created: 0,
                edited: 0,
            }],
            lists: vec![TaskList {
                id: ListId(3),
                name: "Work".into(),
                color: ColorKind::Blue,
                ord: OrderKey::FIRST,
            }],
            tasks: vec![Task {
                id: TaskId(20),
                list: ListId(3),
                title: String::new(),
                notes: String::new(),
                priority: Priority::None,
                due: None,
                repeat: Repeat::None,
                done: false,
                completed_at: None,
                tags: Vec::new(),
                // A subtask's id is scanned too: it draws on the task counter, so
                // a watermark that missed one would hand out a colliding id.
                subtasks: vec![Subtask {
                    id: 99,
                    title: String::new(),
                    done: false,
                }],
                created: 0,
                edited: 0,
                ord: OrderKey::FIRST,
            }],
        });
        assert_eq!(organizer.next_note, 8);
        assert_eq!(organizer.next_task, 100);
        assert_eq!(organizer.next_list, 4);
    }

    #[test]
    fn a_fresh_area_starts_at_row_one_not_row_zero() {
        // Row 0 is the inbox sentinel, and an id there would be a row nobody can
        // ever hold.
        let organizer = Organizer::from_catalog(OrganizerCatalog::default());
        assert_eq!(organizer.next_note, 1);
        assert_eq!(organizer.next_task, 1);
        assert_eq!(organizer.next_list, 1);
    }

    #[test]
    fn a_new_list_draws_from_the_closed_palette_and_never_the_default() {
        // Two lists in a row must not be the same colour, and `Default` — the
        // theme's own ink — is not in the rotation at all.
        for id in 1..=24u64 {
            let color = LIST_COLORS[(id as usize) % LIST_COLORS.len()];
            assert_ne!(color, ColorKind::Default);
            assert_ne!(color, LIST_COLORS[((id + 1) % LIST_COLORS.len() as u64) as usize]);
        }
    }
}
