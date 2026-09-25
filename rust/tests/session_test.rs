//! The bridge's contract, driven the way the JVM drives it: one JSON request
//! in, one JSON reply out.
//!
//! These run on the host (`cargo test`), with no emulator and no JVM, because
//! that is the whole point of the request/reply shape — the protocol is
//! testable where it is written. What they cannot cover is the JNI plumbing
//! itself (symbol names, `System.loadLibrary`), which the Android build and a
//! real launch verify.

use std::path::PathBuf;

use serde_json::Value;

use quire_bridge::Session;

/// A library in a directory of its own.
struct Harness {
    dir: PathBuf,
    session: Session,
}

impl Harness {
    fn new(name: &str) -> Harness {
        let dir = std::env::temp_dir().join(format!(
            "quire-compose-{}-{name}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        let session = Session::open(dir.to_str().expect("utf-8 temp path"))
            .expect("an empty library opens");
        Harness { dir, session }
    }

    /// Reopen the same directory — the round trip that proves a write landed.
    fn reopen(&mut self) {
        self.session = Session::open(self.dir.to_str().expect("utf-8 temp path"))
            .expect("a library written by this process reopens");
    }

    fn send(&mut self, body: &str) -> Value {
        let raw = self.session.dispatch(body);
        serde_json::from_str(&raw)
            .unwrap_or_else(|e| panic!("reply is not JSON ({e}): {raw}"))
    }

    /// A request that must succeed, returning its view.
    fn ok(&mut self, body: &str) -> Value {
        let reply = self.send(body);
        assert_eq!(reply["ok"], Value::Bool(true), "request failed: {body} -> {reply}");
        reply["view"].clone()
    }

    fn err(&mut self, body: &str) -> String {
        let reply = self.send(body);
        assert_eq!(reply["ok"], Value::Bool(false), "expected a failure: {body} -> {reply}");
        reply["error"].as_str().unwrap_or_default().to_string()
    }

    fn view(&mut self) -> Value {
        self.ok(r#"{"op":"boot"}"#)
    }

    /// The first block row of the open page.
    fn first_block(&mut self) -> u64 {
        self.view()["blocks"][0]["id"].as_u64().expect("a block row")
    }

    /// SPEC §四十一's catalog, as the last reply carried it.
    fn org(&mut self) -> Value {
        self.view()["org"].clone()
    }

    /// The one note in the catalog — every test here makes exactly one.
    fn note(&mut self) -> Value {
        let notes = self.org()["notes"].clone();
        let notes = notes.as_array().expect("a notes array");
        assert_eq!(notes.len(), 1, "expected exactly one note: {notes:?}");
        notes[0].clone()
    }

    fn task(&mut self) -> Value {
        let tasks = self.org()["tasks"].clone();
        let tasks = tasks.as_array().expect("a tasks array");
        assert_eq!(tasks.len(), 1, "expected exactly one task: {tasks:?}");
        tasks[0].clone()
    }

    fn list(&mut self) -> Value {
        let lists = self.org()["lists"].clone();
        let lists = lists.as_array().expect("a lists array");
        assert_eq!(lists.len(), 1, "expected exactly one list: {lists:?}");
        lists[0].clone()
    }
}

impl Drop for Harness {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

#[test]
fn a_page_and_its_text_survive_a_restart() {
    let mut h = Harness::new("round-trip");
    assert!(h.view()["pages"].as_array().unwrap().is_empty());

    let view = h.ok(r#"{"op":"createPage","title":"笔记"}"#);
    let page = view["activePage"].as_u64().unwrap();
    assert_eq!(view["pages"][0]["title"], "笔记");
    assert_eq!(view["pages"][0]["depth"], 0);

    h.ok(r#"{"op":"appendBlock","kind":"paragraph","text":""}"#);
    let block = h.first_block();
    h.send(&format!(r#"{{"op":"setBlockText","block":{block},"text":"你好，世界"}}"#));
    h.ok(r#"{"op":"flush"}"#);

    h.reopen();
    let view = h.view();
    assert_eq!(view["activePage"].as_u64(), Some(page));
    assert_eq!(view["pages"][0]["title"], "笔记");
    assert_eq!(view["blocks"][0]["text"], "你好，世界");
}

#[test]
fn a_text_edit_answers_without_echoing_the_page() {
    let mut h = Harness::new("quiet");
    h.ok(r#"{"op":"createPage","title":"n"}"#);
    h.ok(r#"{"op":"appendBlock","kind":"paragraph","text":"a"}"#);
    let block = h.first_block();

    // Two hundred rows per character is the cost this rule exists to avoid.
    let reply = h.send(&format!(r#"{{"op":"setBlockText","block":{block},"text":"ab"}}"#));
    assert_eq!(reply["ok"], Value::Bool(true));
    assert!(reply.get("view").is_none(), "a text edit must not carry a view");
}

#[test]
fn edits_are_undoable_one_step_at_a_time() {
    let mut h = Harness::new("undo");
    h.ok(r#"{"op":"createPage","title":"doc"}"#);
    h.ok(r#"{"op":"appendBlock","kind":"paragraph","text":"first"}"#);
    let view = h.ok(r#"{"op":"appendBlock","kind":"heading_1","text":"second"}"#);
    assert_eq!(view["blocks"].as_array().unwrap().len(), 2);

    let view = h.ok(r#"{"op":"undo"}"#);
    assert_eq!(view["blocks"].as_array().unwrap().len(), 1);
    assert_eq!(view["blocks"][0]["text"], "first");

    let view = h.ok(r#"{"op":"redo"}"#);
    assert_eq!(view["blocks"].as_array().unwrap().len(), 2);
    assert_eq!(view["blocks"][1]["kind"], "heading_1");
}

#[test]
fn nesting_and_folding_control_which_rows_come_back() {
    let mut h = Harness::new("fold");
    h.ok(r#"{"op":"createPage","title":"doc"}"#);
    h.ok(r#"{"op":"appendBlock","kind":"bullet","text":"parent"}"#);
    let parent = h.first_block();
    let view = h.ok(&format!(
        r#"{{"op":"insertBlockAfter","block":{parent},"kind":"bullet","text":"child"}}"#
    ));
    let child = view["blocks"][1]["id"].as_u64().unwrap();

    // The list command is what turns a sibling into a child.
    let view = h.ok(&format!(r#"{{"op":"indentList","block":{child}}}"#));
    assert_eq!(view["blocks"][1]["depth"], 1);
    assert_eq!(view["blocks"][0]["hasChildren"], Value::Bool(true));

    let view = h.ok(&format!(r#"{{"op":"toggleFold","block":{parent}}}"#));
    assert_eq!(view["blocks"].as_array().unwrap().len(), 1, "a folded row hides its child");
    assert_eq!(view["blocks"][0]["folded"], Value::Bool(true));

    let view = h.ok(&format!(r#"{{"op":"toggleFold","block":{parent}}}"#));
    assert_eq!(view["blocks"].as_array().unwrap().len(), 2);
}

#[test]
fn a_deleted_page_takes_its_subtree_with_it() {
    let mut h = Harness::new("delete");
    let root = h.ok(r#"{"op":"createPage","title":"root"}"#)["activePage"]
        .as_u64()
        .unwrap();
    h.ok(&format!(r#"{{"op":"createPage","parent":{root},"title":"child"}}"#));
    let view = h.view();
    // The parent had to open for its new child to be a row at all.
    assert_eq!(view["pages"].as_array().unwrap().len(), 2);
    assert_eq!(view["pages"][1]["depth"], 1);

    let view = h.ok(&format!(r#"{{"op":"deletePage","page":{root}}}"#));
    assert!(view["pages"].as_array().unwrap().is_empty());
    assert_eq!(view["activePage"], Value::Null);

    h.reopen();
    assert!(h.view()["pages"].as_array().unwrap().is_empty());
}

#[test]
fn the_last_page_opened_is_the_one_that_reopens() {
    let mut h = Harness::new("recents");
    let first = h.ok(r#"{"op":"createPage","title":"one"}"#)["activePage"].as_u64().unwrap();
    let second = h.ok(r#"{"op":"createPage","title":"two"}"#)["activePage"]
        .as_u64()
        .unwrap();
    let view = h.ok(&format!(r#"{{"op":"openPage","page":{first}}}"#));
    assert_eq!(view["recents"][0].as_u64(), Some(first));
    h.ok(r#"{"op":"flush"}"#);

    h.reopen();
    let view = h.view();
    assert_eq!(view["activePage"].as_u64(), Some(first));
    assert_eq!(view["recents"][1].as_u64(), Some(second));
}

#[test]
fn favorites_and_the_theme_are_persisted() {
    let mut h = Harness::new("settings");
    let page = h.ok(r#"{"op":"createPage","title":"starred"}"#)["activePage"]
        .as_u64()
        .unwrap();
    h.ok(&format!(r#"{{"op":"toggleFavorite","page":{page}}}"#));
    let view = h.ok(r#"{"op":"setTheme","theme":"dark"}"#);
    assert_eq!(view["theme"], "dark");
    h.ok(r#"{"op":"flush"}"#);

    h.reopen();
    let view = h.view();
    assert_eq!(view["theme"], "dark");
    assert_eq!(view["favorites"][0].as_u64(), Some(page));
}

#[test]
fn a_rename_sticks_and_a_blank_one_becomes_untitled() {
    let mut h = Harness::new("rename");
    let page = h.ok(r#"{"op":"createPage","title":"before"}"#)["activePage"]
        .as_u64()
        .unwrap();
    let view = h.ok(&format!(r#"{{"op":"renamePage","page":{page},"title":"after"}}"#));
    assert_eq!(view["pages"][0]["title"], "after");

    let view = h.ok(&format!(r#"{{"op":"renamePage","page":{page},"title":"  "}}"#));
    assert_eq!(view["pages"][0]["title"], "无标题");
}

#[test]
fn bad_requests_come_back_as_errors_not_panics() {
    let mut h = Harness::new("errors");
    assert!(h.err("not json at all").contains("bad request"));
    assert!(h.err(r#"{"op":"nonsense"}"#).contains("bad request"));
    assert!(h.err(r#"{"op":"openPage","page":999}"#).contains("no such page"));
    assert!(h.err(r#"{"op":"deleteBlock","block":404}"#).contains("no such block"));
    assert!(h.err(r#"{"op":"setBlockKind","block":1,"kind":"banana"}"#)
        .contains("unknown block kind"));
    assert!(h.err(r#"{"op":"setTheme","theme":"neon"}"#).contains("unknown theme"));
    // A tick with nothing queued is not an error: the ticker runs constantly.
    assert_eq!(h.send(r#"{"op":"tick"}"#)["ok"], Value::Bool(true));
}

#[test]
fn a_locked_page_refuses_block_edits() {
    let mut h = Harness::new("locked");
    let page = h.ok(r#"{"op":"createPage","title":"locked"}"#)["activePage"]
        .as_u64()
        .unwrap();
    h.ok(r#"{"op":"appendBlock","kind":"paragraph","text":"body"}"#);
    let block = h.first_block();
    assert!(!h.view()["locked"].as_bool().unwrap());

    // No screen sets the lock yet (the page menu is a later slice), so the
    // switch is flipped the way any other page property is.
    h.ok(&format!(r#"{{"op":"setPageLocked","page":{page},"locked":true}}"#));
    assert!(h.view()["locked"].as_bool().unwrap());
    let message = h.err(&format!(r#"{{"op":"setBlockText","block":{block},"text":"no"}}"#));
    assert!(message.contains("read-only"), "{message}");

    // …and the stored text is untouched by the refused edit.
    assert_eq!(h.view()["blocks"][0]["text"], "body");
}

// ─── SPEC §四十一: the organizer ─────────────────────────────────────────────

#[test]
fn a_note_and_a_task_and_a_list_all_survive_a_restart() {
    let mut h = Harness::new("org-round-trip");

    let note = h.ok(r#"{"op":"orgCreateNote"}"#)["org"]["notes"][0]["id"]
        .as_u64()
        .expect("the new note is a row");
    h.ok(&format!(r#"{{"op":"orgNoteTitle","note":{note},"title":"想法"}}"#));
    h.ok(&format!(r#"{{"op":"orgNoteBody","note":{note},"body":"第一行\n第二行"}}"#));
    h.ok(&format!(r#"{{"op":"orgNoteTags","note":{note},"tags":"idea, 灵感"}}"#));
    h.ok(&format!(r#"{{"op":"orgNotePinned","note":{note},"pinned":true}}"#));

    let list = h.ok(r#"{"op":"orgCreateList","name":"工作"}"#)["org"]["lists"][0]["id"]
        .as_u64()
        .expect("the new list is a row");
    let task = h.ok(&format!(r#"{{"op":"orgCreateTask","list":{list}}}"#))["org"]["tasks"][0]
        ["id"]
        .as_u64()
        .expect("the new task is a row");

    h.ok(&format!(r#"{{"op":"orgTaskTitle","task":{task},"title":"写桥接层"}}"#));
    h.ok(&format!(r#"{{"op":"orgTaskPriority","task":{task},"slot":3}}"#));
    h.ok(&format!(r#"{{"op":"orgTaskDue","task":{task},"due":"2026-09-30"}}"#));
    h.ok(&format!(r#"{{"op":"orgTaskRepeat","task":{task},"slot":2}}"#));
    h.ok(&format!(r#"{{"op":"orgTaskTags","task":{task},"tags":"quire, 安卓"}}"#));
    h.ok(&format!(r#"{{"op":"orgTaskNotes","task":{task},"notes":"一次一层"}}"#));
    h.ok(&format!(r#"{{"op":"orgTaskDone","task":{task},"done":true}}"#));
    h.ok(&format!(r#"{{"op":"orgTaskDone","task":{task},"done":false}}"#));
    h.ok(&format!(r#"{{"op":"orgSubtaskAdd","task":{task}}}"#));
    let subtask = h.task()["subtasks"][0]["id"].as_u64().expect("a subtask row");
    h.ok(&format!(
        r#"{{"op":"orgSubtaskTitle","task":{task},"subtask":{subtask},"title":"先写测试"}}"#
    ));
    h.ok(&format!(
        r#"{{"op":"orgSubtaskDone","task":{task},"subtask":{subtask},"done":true}}"#
    ));
    h.ok(r#"{"op":"flush"}"#);

    h.reopen();

    let note = h.note();
    assert_eq!(note["title"], "想法");
    assert_eq!(note["body"], "第一行\n第二行");
    assert_eq!(note["tags"][0], "idea");
    assert_eq!(note["tags"][1], "灵感");
    assert_eq!(note["pinned"], Value::Bool(true));
    assert!(note["edited"].as_i64().unwrap() > 0, "the shell stamped the instant");

    // The list's colour travels as a palette slot, and the slot a new list draws
    // is never `Default` (0) — a dot with no colour is not a dot.
    let list = h.list();
    assert_eq!(list["name"], "工作");
    assert_ne!(list["color"].as_i64(), Some(0));

    let task = h.task();
    assert_eq!(task["title"], "写桥接层");
    assert_eq!(task["priority"], "high");
    assert_eq!(task["due"], "2026-09-30");
    assert_eq!(task["repeat"], "weekdays");
    assert_eq!(task["notes"], "一次一层");
    assert_eq!(task["done"], Value::Bool(false));
    assert_eq!(task["tags"][0], "quire");
    assert_eq!(task["subtasks"][0]["title"], "先写测试");
    assert_eq!(task["subtasks"][0]["done"], Value::Bool(true));
    // Unticking clears the instant: the two fields cannot disagree about the
    // same fact.
    assert_eq!(task["completedAt"], Value::Null);
}

#[test]
fn the_area_has_its_own_undo_and_the_page_has_its_own() {
    let mut h = Harness::new("org-undo-split");

    // A page with one block, and a note.
    h.ok(r#"{"op":"createPage","title":"doc"}"#);
    h.ok(r#"{"op":"appendBlock","kind":"paragraph","text":"page text"}"#);
    let note = h.ok(r#"{"op":"orgCreateNote"}"#)["org"]["notes"][0]["id"]
        .as_u64()
        .unwrap();
    h.ok(&format!(r#"{{"op":"orgNoteTitle","note":{note},"title":"area text"}}"#));

    // Undo inside the area: the note's title goes back, the page is untouched.
    let view = h.ok(r#"{"op":"orgUndo"}"#);
    assert_eq!(view["org"]["notes"][0]["title"], "");
    assert_eq!(view["blocks"][0]["text"], "page text");

    // Undo in the editor: the block goes, the note is still the blank one the
    // area's own undo left behind — a step here cannot reach an area edit.
    let view = h.ok(r#"{"op":"undo"}"#);
    assert_eq!(view["blocks"].as_array().unwrap().len(), 0);
    assert_eq!(view["org"]["notes"][0]["title"], "");

    // Redo puts the title back, and only the title.
    let view = h.ok(r#"{"op":"orgRedo"}"#);
    assert_eq!(view["org"]["notes"][0]["title"], "area text");
    assert_eq!(view["blocks"].as_array().unwrap().len(), 0);
}

#[test]
fn an_empty_stack_stops_claiming_a_step() {
    let mut h = Harness::new("org-undo-empty");
    let view = h.view();
    assert_eq!(view["orgCanUndo"], Value::Bool(false));
    assert_eq!(view["orgCanRedo"], Value::Bool(false));

    h.ok(r#"{"op":"orgCreateNote"}"#);
    let view = h.view();
    assert_eq!(view["orgCanUndo"], Value::Bool(true));
    assert_eq!(view["orgCanRedo"], Value::Bool(false));

    // One step back. The flags are optimistic — "the last step did something" —
    // which is all a button needs, and the *second* press is what discovers the
    // stack is empty.
    let view = h.ok(r#"{"op":"orgUndo"}"#);
    assert_eq!(view["orgCanUndo"], Value::Bool(true));
    assert_eq!(view["orgCanRedo"], Value::Bool(true));

    // Nothing left to walk: the button must stop claiming a step rather than
    // staying lit over an empty stack, and only that direction is cleared.
    let view = h.ok(r#"{"op":"orgUndo"}"#);
    assert_eq!(view["orgCanUndo"], Value::Bool(false));
    assert_eq!(view["orgCanRedo"], Value::Bool(true));

    // The same rule the other way: one redo succeeds, the second has nothing to
    // walk and clears 重做 alone.
    let view = h.ok(r#"{"op":"orgRedo"}"#);
    assert_eq!(view["orgCanUndo"], Value::Bool(true));
    assert_eq!(view["orgCanRedo"], Value::Bool(true));

    let view = h.ok(r#"{"op":"orgRedo"}"#);
    assert_eq!(view["orgCanUndo"], Value::Bool(true));
    assert_eq!(view["orgCanRedo"], Value::Bool(false));
}

#[test]
fn deleting_a_list_files_its_tasks_in_the_inbox_and_undo_brings_both_back() {
    let mut h = Harness::new("org-delete-list");

    let list = h.ok(r#"{"op":"orgCreateList","name":"工作"}"#)["org"]["lists"][0]["id"]
        .as_u64()
        .unwrap();
    for title in ["a", "b"] {
        let id = h.ok(&format!(r#"{{"op":"orgCreateTask","list":{list}}}"#))["org"]["tasks"]
            [0]["id"]
            .as_u64()
            .unwrap();
        h.ok(&format!(r#"{{"op":"orgTaskTitle","task":{id},"title":"{title}"}}"#));
    }

    let view = h.ok(&format!(r#"{{"op":"orgDeleteList","list":{list}}}"#));
    assert!(view["org"]["lists"].as_array().unwrap().is_empty());
    let tasks = view["org"]["tasks"].as_array().unwrap();
    assert_eq!(tasks.len(), 2, "delete my list must not be a way to lose tasks");
    // The inbox is the sentinel row 0, and a task that lands there reads as the
    // inbox's — which is what the UI folds a missing list to as well.
    assert!(tasks.iter().all(|t| t["list"] == Value::from(0)));

    // One undo brings the list back AND its tasks: the batch is one step.
    let view = h.ok(r#"{"op":"orgUndo"}"#);
    assert_eq!(view["org"]["lists"][0]["name"], "工作");
    let tasks = view["org"]["tasks"].as_array().unwrap();
    assert!(tasks.iter().all(|t| t["list"].as_u64() == Some(list)));
}

#[test]
fn a_quick_add_is_one_step_and_carries_the_deadline_it_was_typed_into() {
    let mut h = Harness::new("org-quick-add");

    // A blank line is not a row and not a step.
    let view = h.ok(r#"{"op":"orgQuickAdd","list":-1,"title":"   "}"#);
    assert!(view["org"]["tasks"].as_array().unwrap().is_empty());
    assert_eq!(view["orgCanUndo"], Value::Bool(false), "a no-op must not be a step");

    // Typed into 今天, the task is due today — the only reading of "today" that
    // survives the next rebuild.
    let view = h.ok(r#"{"op":"orgQuickAdd","list":-1,"title":"买菜","due":"2026-09-25"}"#);
    let task = &view["org"]["tasks"][0];
    assert_eq!(task["title"], "买菜");
    assert_eq!(task["due"], "2026-09-25");
    assert_eq!(task["list"], Value::from(0));

    // One step: the create and the deadline and the title were one command, so
    // there is no blank row for the stack to hold.
    let view = h.ok(r#"{"op":"orgUndo"}"#);
    assert!(view["org"]["tasks"].as_array().unwrap().is_empty());
}

#[test]
fn a_subtask_draws_on_the_task_counter_and_never_collides() {
    let mut h = Harness::new("org-subtask-ids");

    let task = h.ok(r#"{"op":"orgCreateTask","list":-1}"#)["org"]["tasks"][0]["id"]
        .as_u64()
        .unwrap();
    h.ok(&format!(r#"{{"op":"orgSubtaskAdd","task":{task}}}"#));
    h.ok(&format!(r#"{{"op":"orgSubtaskAdd","task":{task}}}"#));
    let subtasks = h.org()["tasks"][0]["subtasks"].clone();
    let subtasks = subtasks.as_array().unwrap();
    assert_eq!(subtasks.len(), 2);
    assert_ne!(subtasks[0]["id"], subtasks[1]["id"]);
    let last_subtask = subtasks[1]["id"].as_u64().unwrap();

    // A second task's id must clear the subtask ids already handed out, or a new
    // checklist line would collide with one inside the first task.
    let second = h.ok(r#"{"op":"orgCreateTask","list":-1}"#)["org"]["tasks"][1]["id"]
        .as_u64()
        .unwrap();
    assert!(second > last_subtask);

    h.ok(&format!(
        r#"{{"op":"orgSubtaskDelete","task":{task},"subtask":{}}}"#,
        subtasks[0]["id"]
    ));
    let remaining = h.org()["tasks"][0]["subtasks"].clone();
    assert_eq!(remaining.as_array().unwrap().len(), 1);
    assert_eq!(h.org()["tasks"].as_array().unwrap().len(), 2, "the other task stayed");
}

#[test]
fn the_task_menu_operations_each_land_and_each_undo() {
    let mut h = Harness::new("org-task-fields");

    let list = h.ok(r#"{"op":"orgCreateList","name":""}"#)["org"]["lists"][0]["id"]
        .as_u64()
        .unwrap();
    // A blank name becomes the placeholder rather than an unnamed chip.
    assert_eq!(h.list()["name"], "新建清单");

    let task = h.ok(r#"{"op":"orgCreateTask","list":-1}"#)["org"]["tasks"][0]["id"]
        .as_u64()
        .unwrap();

    // Move it into the stored list, then back to the inbox (`-1`).
    let view = h.ok(&format!(r#"{{"op":"orgTaskList","task":{task},"list":{list}}}"#));
    assert_eq!(view["org"]["tasks"][0]["list"].as_u64(), Some(list));
    let view = h.ok(&format!(r#"{{"op":"orgTaskList","task":{task},"list":-1}}"#));
    assert_eq!(view["org"]["tasks"][0]["list"], Value::from(0));

    // The colour picker hands over a palette slot and the wire hands back the
    // same number.
    let view = h.ok(&format!(r#"{{"op":"orgListColor","list":{list},"slot":9}}"#));
    assert_eq!(view["org"]["lists"][0]["color"], Value::from(9));
    h.ok(&format!(r#"{{"op":"orgListName","list":{list},"name":"改名了"}}"#));
    assert_eq!(h.list()["name"], "改名了");
}

#[test]
fn an_edit_that_changes_nothing_is_not_a_step() {
    let mut h = Harness::new("org-noop");

    let note = h.ok(r#"{"op":"orgCreateNote"}"#)["org"]["notes"][0]["id"]
        .as_u64()
        .unwrap();
    h.ok(&format!(r#"{{"op":"orgNoteTitle","note":{note},"title":"same"}}"#));
    let edited = h.note()["edited"].as_i64().unwrap();

    // Clicking into a title and out of it again is a real, frequent no-op: it
    // must not restamp `edited`, or the row would end up newer than the edit
    // that made it.
    let view = h.ok(&format!(r#"{{"op":"orgNoteTitle","note":{note},"title":"same"}}"#));
    assert_eq!(view["org"]["notes"][0]["edited"].as_i64(), Some(edited));
}

#[test]
fn a_bad_id_or_a_bad_date_is_an_error_not_a_silent_write() {
    let mut h = Harness::new("org-errors");

    assert!(h.err(r#"{"op":"orgNoteTitle","note":404,"title":"x"}"#).contains("no such note"));
    assert!(h.err(r#"{"op":"orgTaskDone","task":404,"done":true}"#).contains("no such task"));
    assert!(h.err(r#"{"op":"orgDeleteList","list":404}"#).contains("no such list"));
    assert!(h
        .err(r#"{"op":"orgSubtaskAdd","task":404}"#)
        .contains("no such task"));

    let task = h.ok(r#"{"op":"orgCreateTask","list":-1}"#)["org"]["tasks"][0]["id"]
        .as_u64()
        .unwrap();
    // The deadline column holds `core::date`'s one format or nothing: a string no
    // comparison can read is refused rather than stored.
    let message = h.err(&format!(r#"{{"op":"orgTaskDue","task":{task},"due":"2026/09/25"}}"#));
    assert!(message.contains("不是一个日期"), "{message}");
    assert_eq!(h.task()["due"], Value::Null);

    // …and an empty string is "no deadline", not a bad one.
    h.ok(&format!(r#"{{"op":"orgTaskDue","task":{task},"due":"2026-09-25"}}"#));
    let view = h.ok(&format!(r#"{{"op":"orgTaskDue","task":{task},"due":"  "}}"#));
    assert_eq!(view["org"]["tasks"][0]["due"], Value::Null);
}

/// The area must not be reachable by a page's lock: it is a second top-level
/// area, not a page, and a read-only page has nothing to do with whether a note
/// can be written.
#[test]
fn a_locked_page_does_not_lock_the_organizer() {
    let mut h = Harness::new("org-lock");

    let page = h.ok(r#"{"op":"createPage","title":"locked"}"#)["activePage"]
        .as_u64()
        .unwrap();
    h.ok(&format!(r#"{{"op":"setPageLocked","page":{page},"locked":true}}"#));

    let note = h.ok(r#"{"op":"orgCreateNote"}"#)["org"]["notes"][0]["id"]
        .as_u64()
        .unwrap();
    h.ok(&format!(r#"{{"op":"orgNoteTitle","note":{note},"title":"写着呢"}}"#));
    assert_eq!(h.note()["title"], "写着呢");
}

