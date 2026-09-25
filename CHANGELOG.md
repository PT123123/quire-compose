# Changelog

## 0.2.0 — SPEC §四十一: 笔记 and 任务

The organizer, which PLAN called M2 and the first slice shipped without. The
second top-level area, wired to the model `quire-core` already carried.

### The area

- Two drawer rows (笔记 / 任务) lead into it; the open page is not replaced and is
  exactly where it was on the way back (ADR-0013).
- The bar carries the two tabs, the ＋ and the **area's own undo and redo** —
  `core::ORGANIZER_STACK`, a page id no page can hold, so a 撤销 here can never
  reach a page's edits and vice versa.
- The back gesture walks out of a row's form, then out of the area.

### 笔记

- Title, body, tags, pin, and the age on every row.
- Rows are read pinned-first then most-recently-edited, with the body's first line
  as the excerpt; the tag column is a fold of the notes, busiest first.
- A ＋ makes one and puts the caret in its title.

### 任务

- The five smart views 收集箱 / 今天 / 近七天 / 全部 / 已完成, the three sorts
  添加顺序 / 优先级 / 截止日期, and one needle per tab.
- The quick-add line (a task typed into 今天 is due today), the footer's
  显示已完成 switch and 已完成 X / Y, and per-row ⋯ for open / tick / move / delete.
- The detail form: list, priority, deadline (with 今天 and 清除), repeat, tags,
  notes, and a checklist whose ＋ line puts the caret in the new row.
- **平铺**: one column per list on cards, with a **long-press drag** across columns
  to move a task between lists — and the same move in the ⋯ menu, because a
  gesture nobody can discover must not be the only way.
- Lists: create (named up front), rename, recolour and delete, the last filing its
  tasks in the inbox in one undoable step. The Slint shells declare those three
  callbacks and never wire them; this shell does.

### The bridge

- `rust/src/org.rs`: the area's writes — three id watermarks seeded from the
  loaded catalog, both instants stamped shell-side, one command funnel, and a
  no-op edit that is not a step.
- The reply carries the catalog: every note, task and list, unfiltered. The five
  views, the three sorts and the badges are derived in Kotlin, so a filter change
  costs no round trip (ADR-0011).
- 14 more host tests, driving the same session the JVM drives.

### Also

- `OrgModelTest`: 22 JVM tests over the projection rules — the week's boundary, the
  undated-last sort, the dangling-list fold to the inbox, a finished task never
  being overdue, the chip row lighting exactly one half of the selector.
- The device's own day, not UTC, decides 今天 (ADR-0012).
- Fixed while verifying on the device: the sort row's label wrapped to two lines
  and the priority/checklist rows did the same, the subtask box reserved 152 dp in
  a scrolling form, and the board's drag overlay was positioned in px where it
  meant dp.

## 0.1.0 — the first slice

The native Android shell: Kotlin + Jetpack Compose over `quire-core`. Installs
beside the Rust shell (`dev.quire.android`) and reads the same library.

### The bridge

- A new Rust crate, `rust/quire-bridge`, compiles to `libquire_bridge.so` and
  exports four JNI symbols. One JSON request in, one JSON reply out.
- `cargo test` covers the whole protocol on the host — no emulator, no JVM:
  open, page CRUD, block editing, undo/redo, folding, locking, recents, themes,
  and the error paths.
- `cargo ndk` builds the one ABI the app ships (arm64-v8a) and Gradle packages
  the result as an ordinary prebuilt library. Without a Rust toolchain the task
  disables itself and the app says so instead of failing the build.

### Pages

- The page tree with nesting, per-branch folding, favorites and recents, all
  persisted under the keys the other shells read (`sidebar.expanded`, `recents`).
- Create, rename, delete (with the subtree), favorite and lock, with a long press
  on a page row opening its menu.
- The tree opens where the session last was, falling back to the first page in
  tree order.

### The editor

- Paragraphs, headings 1–3, bullets, numbered lists (counted per level), to-dos,
  quotes, code blocks (with their language), callouts, toggles and dividers.
- Enter asks for the next block through the IME's own action button; the caret
  follows the block that was created.
- The ⋮ handle appears on the selected row and opens the block menu — kind,
  move, indent, outdent, insert, delete.
- Undo and redo in the top bar, disabled when the core's own stacks say there is
  nothing to walk.
- Inline marks painted from the core's byte offsets: bold, italic, strike, code,
  links, mentions, dates (ADR-0008).
- Block colours (text and background) come through from the shared model, so a
  page coloured on the desktop reads the same here.

### Shell

- Light and dark in the shared palette, `跟随系统` by default, in Settings rather
  than on the main screen (ADR-0006).
- A startup notice when the library was recovered from a backup, and an error
  surface when it cannot be opened at all.

### Known limitations

- Tables, columns, formulas, tables of contents, link cards, synced mirrors and
  databases render as labelled placeholders: movable and deletable, not editable.
- Images and files show their attachment id, not the picture.
- The notes & tasks organizer, search, and LAN share/sync are not wired yet.
- Backspace on an empty block does not merge it upwards — a soft keyboard's
  backspace is not a key event a Compose text field can observe.
