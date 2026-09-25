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
