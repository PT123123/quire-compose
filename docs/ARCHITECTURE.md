# Architecture

One sentence: **Kotlin and Compose draw, `quire-core` decides, and one JSON
protocol crosses between them.**

```
┌───────────────────────────── app (Kotlin) ─────────────────────────────┐
│ MainActivity        edge-to-edge; the foreground signal the writer needs│
│   QuireApp          theme + startup state + the three destinations      │
│     Sidebar         the page tree, favorites, recents, settings entry    │
│     EditorScreen    the page's title and its block rows                 │
│     NotesPage       SPEC §四十一's 收件箱: note cards, tag chips, cards  │
│     TasksPage       SPEC §四十一's 任务: chips, rows, board, detail      │
│     ComposeSheet    the floating capture window and its toolbar          │
│       MarkdownText  the toolbar's five text operations, and the tag scan │
│       OrgModel      the projections: five views, eight sorts, badges     │
│     Sheets          block menu, page menu, settings, dialogs            │
│   QuireViewModel    the last view, each destination's own filters        │
│     Bridge          typed methods → one JSON request each               │
│     Native          four `external` declarations                        │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │  JNI: request string in, reply string out
┌───────────────────────────────┴──── rust/quire-bridge ─────────────────┐
│ lib.rs              the four exported symbols, panic → reply            │
│   session.rs        Request → Command, or a page Change                 │
│     workspace.rs    the page tree (no core commands exist for pages)    │
│     org.rs          SPEC §四十一's writes: ids, the clock, the funnel   │
│     view.rs         the projection: rows, marks, flags, the catalog     │
│       Document / History / SqliteRepository / PersistenceService        │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │  pinned git rev
                        quire-core (github.com/PT123123/quire-core)
```

## The protocol

Four symbols, resolved by name when Android loads `libquire_bridge.so`:

| symbol | Kotlin | what it does |
|---|---|---|
| `…Native_create` | `Native.create()` | a fresh handle (a boxed, mutex-guarded session) |
| `…Native_open` | `Native.open(handle, dataDir)` | open or create the library; answers with the first view |
| `…Native_dispatch` | `Native.dispatch(handle, request)` | one request in, one reply out |
| `…Native_close` | `Native.close(handle)` | flush, then free |

A request is a JSON object with an `op` field: `{"op":"appendBlock","kind":"todo","text":"买牛奶"}`.
A reply is `{"ok":true,"view":{…}}`, `{"ok":true}` for a keystroke, or
`{"ok":false,"error":"…"}`. The operations are enumerated in `rust/src/session.rs`
(`Request`) and mirrored one-for-one in `app/.../bridge/Bridge.kt`.

**Why JSON over one symbol rather than a symbol per operation.** The alternative
puts half the protocol in Rust signatures and half in Kotlin ones, and every
field touches both. One string each way keeps the whole contract in one file —
where it is covered by `cargo test`, on the host, with no emulator and no JVM.

**Why not UniFFI.** Its Kotlin bindings reach the native side through JNA: a
second marshalling layer and a reflection-based dependency under a UI whose
specification ranks low RAM above maintainability and feature count. UniFFI
would also mean putting `#[uniffi::export]` on the shared crate's model types —
the core's own rule is that nothing under its `src/` may know about a consumer.

**Why a panic is a reply.** A Rust panic unwinding across an FFI boundary is
undefined behaviour and in practice kills the process. Every exported function
catches it and returns an error string, so the worst case is a note the shell can
show rather than an app that disappears.

## Threading and timing

- **One call at a time.** The session is a single workspace behind a mutex; two
  callers would not corrupt it, they would just see replies in an order they did
  not choose, and the UI would settle on whichever landed last. Every call goes
  through `Dispatchers.IO.limitedParallelism(1)` in the view model.
- **One writer clock.** The Rust side owns `quire-core`'s debounced
  `PersistenceService`; the shell owns the clock that drains it — a tick a second
  while the app is in front, a flush when it goes away, a final flush on close.
  Android gives an app no reliable "about to be killed", so the policy is to be
  already written by the time anyone asks.
- **The view model outlives configuration changes** (the Activity declares
  `configChanges`, and the VM is scoped to it regardless), so the session —
  including the undo stacks, which are memory-only — survives a rotation.

## The projection

`rust/src/view.rs` is a projection, never a mirror: a row carries what a screen
draws and nothing else, so the payload is proportional to what is on screen. The
core's types are not exported — they have no serde derives, and adding them would
mean changing the shared crate for one shell — so the bridge owns the JSON shape
and spells enums as the short stable strings the database already stores
(`heading_1`, `bullet`, `todo`).

Block rows are ordered by a real DFS over `parent` + `order`, not by sorting the
flat list: a page's blocks are stored sorted by `OrderKey`, which interleaves
levels (a child's key sits between its parent's and its parent's next sibling's),
and only the parent pointers say which is which. Descendants of a folded block
are omitted, and a block whose parent is not on the page — a torn write — is
shown at depth 0 rather than dropped.

## Two projections, and the split between them

This shell carries **two** projections, and they are deliberately shaped
differently (ADR-0011):

- **The document's is a view.** `view.rs` sends `PageRow`/`BlockRow` — already
  decided: which pages are rows, which blocks are visible, what each mark's
  offsets are. The editor draws what it is handed.
- **The organizer's is a catalog.** `view::org_catalog` sends the *rows* — every
  note, task and list, with no filter, no sort and no derived badge — and
  `ui/OrgModel.kt` derives the five smart views, the eight sorts, the deadline
  labels and the board from them.

The difference is a question of where interaction lives. A block edit is a
document edit: it goes through the core's command funnel, and a reply is the
honest moment to re-project. An organizer *filter* is not an edit at all — which
smart view, which list, which sort, the needle in the search box — so sending each
one across the bridge would put a round trip and a few hundred rows of JSON on
every keystroke, to compute something the shell already has every input for. The
projection rules are in Kotlin for the same reason the Rust shell keeps them in
its own `src/app/state.rs`: they are the *app layer's* rules, and neither
platform's core knows them.

What still crosses for the organizer is every **write**, because a write is what
needs the core: ids allocated once, instants stamped once, the command funnel and
the organizer's own undo stack. `org.rs` holds the catalog for exactly one reason
— `Command::UpdateTask` is handed the row *before* and the row *after*, and only
the owner of the catalog has the first of those.

**A write is one row.** The capture sheet produces one thing — a note the user
typed, with the tags its `#tokens` name — and it travels as **one** command
(`orgAddNote`, `orgQuickAdd`), not a create followed by two updates. Three
commands would be three presses of 撤销 to put one note away, and the first of them
would leave a blank row on the stack that nobody asked for (ADR-0014).

## The organizer's own stack

SPEC §四十一's area gets its own undo with no new mechanism: `core::ORGANIZER_STACK`
is a `PageId` no page can hold (`u64::MAX`, while page ids are allocated from a
`HashMap`'s max + 1), so `History` — already one stack per page — keeps the area's
steps apart from every document's. A 撤销 in the area walks the area's entries and
a 撤销 in the editor walks the open page's, and neither list can contain the
other's steps. The bridge reports the two answers separately (`canUndo`/`canRedo`
and `orgCanUndo`/`orgCanRedo`), because a button lit by the wrong stack is a
button that does nothing.

## What is deliberately not here

- The Rust shell's bench harness, scene renderer and visual-regression sweeps.
  This shell has no headless renderer to sweep; its equivalent is the host-side
  bridge test suite plus the app's unit tests.
- Any feature the README's "What does not work yet" lists. That list is the
  honest boundary of the first slice, and it is expected to shrink.
