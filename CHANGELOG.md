# Changelog

## 0.4.0 — 收件箱 as home, an instant capture, undo-able deletes, and 引用

The alignment pass against the reference app (ADR-0015): four things this shell
already did, done the way the app it was ported from does them.

### 收件箱 is home

- The app opens on 收件箱, and the back gesture returns there from 任务 and from a
  document page rather than leaving the app. Only 收件箱 hands the gesture to the
  system.
- A page is reached from the drawer's tree, which now closes the drawer and
  switches to the document — before this, tapping a page changed what was open
  behind a 收件箱 that stayed on screen.
- Opening the drawer drops the open page's focus, and the IME with it, on every
  destination.

### Capture is instant

- The ＋ opens a **non-animated overlay** instead of a `ModalBottomSheet`. The
  sheet's spring — a scrim fade and a two-stage expand — was what the keyboard had
  to wait behind; the field now asks for the caret on the frame it appears.
- Tap the scrim or press back to dismiss. The draft still survives either, and the
  swipe-down is gone with the sheet.

### Delete is undo-able

- Deleting a note or a task hides the row at once and shows a **撤销** bar for three
  seconds; the row is really deleted only when it expires, so 撤销 drops work that
  was never sent. Both row menus and 删除任务 go through it.
- The delete confirmation dialog is gone — the bar is what it was standing in for.
- **No persistent 回收站 yet**: that needs soft delete in `quire-core`, which is a
  cross-repo slice of its own (ADR-0015).

### Notes get a page, and comments

- Tapping a note opens it on a page of its own: its body (committing body and tags
  together, as before), its tags, its replies, and a **详细信息** sheet listing id,
  created, edited, tags, length, pinned, ref and comment count. The reference's
  历史 / 恢复版本 has no counterpart in the core, and the sheet says so.
- **评论 / 引用**: a comment is an ordinary note carrying a reference to the note it
  answers. A reply's card draws a muted `↩ <parent's first line>` that jumps to the
  parent (clearing the filter first if the parent is hidden by it), and the parent's
  page has a 评论 section listing them. It is all a projection of the one catalog.
- `quire-core` gained `notes.ref_note` — its ADR-0001, migration 27 — and this
  shell pins the rev that carries it. A dangling ref is tolerated end to end.

### Also

- The bridge's `session_test` gained the comment's ref round trip; the core gained a
  migration test and two merge tests (a comment following a renumbered parent, and
  an orphan ref surviving untouched).

## 0.3.0 — the organizer, rebuilt to look like the app it was ported from

The shape of the 笔记 / 任务 slice was wrong: it was the Slint shell's, not the
reference app's. Three things were put right (ADR-0013, ADR-0014).

### 收件箱 and 任务 are two pages, not two tabs

- The 笔记 / 任务 chips are gone. Each page has its own drawer row, its own toolbar
  and its own state; the drawer is the only thing between them.
- 收件箱's toolbar: 搜索 · 排序（最新创建 / 最新更新 / 按内容）· 撤销 / 重做 · ⋯（清除过滤,
  复制全部）.
- 任务's toolbar: the same three, with its own five-item sort menu and ⋯（列表 /
  平铺 / 新建清单）.
- 搜索 is a toggle, as in the reference app: the field appears under the bar and
  clearing it clears the filter.
- The back gesture walks out of a row's form, then out of the page.

### Capture floats

- **The round ＋ opens a bottom sheet**, not a row in the list: a multi-line field,
  a markdown toolbar (`#`, `B`, `/`, `•`, `1.`), a round ➤ and no cancel button —
  a swipe down is one.
- The toolbar's rules are `MarkdownText`'s, pure functions with their own tests:
  a heading cycle that replaces a list marker instead of stacking on it, a bold
  toggle that unwraps what it wrapped, and a `#`-scan that does not call "issue
  #3" a tag named 3.
- A `#token` being typed raises the tag suggestions above the field.
- The draft lives in the view model: a sheet swiped away mid-sentence loses
  nothing.
- A note is created with its text **and** the tags its `#tokens` name in one
  command (`orgAddNote`), and a task in one too (`orgQuickAdd` now carries tags) —
  so one 撤销 puts the whole row away, with no half-built note in between.

### A note is a card; a task is a glass row

- Note cards: the text (up to eight lines), its tags on an accent line, its age
  bottom-right, a ⚑ when pinned and a ⋯ in the corner. A note the desktop made
  with a title and no body falls back to the title.
- Task rows: title, then a meta line carrying the list's colour dot, the priority
  glyph, the deadline badge, the checklist count, and the tags pushed right.
- Both sit on a translucent "glass" plate (`QuireColors.card`), which is what the
  reference app draws its rows on.
- The footer is a 4 dp progress bar and 已完成 X / Y, not a chip row.
- Four new sort slots for tasks — 最近添加 and 反向 alongside 默认排序 — matching the
  reference app's own menu.

### Also

- `MarkdownTextTest`: 15 JVM tests over the toolbar's rules and the tag scan.
- `OrgModelTest` grew the new sorts and the note card's fallback.
- Fixed on the device: the notes tab's own bar clipped a squeezed title to "任"
  (the view switch moved into the overflow and the title got a floor), and the
  progress footer sat under the ＋.

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
