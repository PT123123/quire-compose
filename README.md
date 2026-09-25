# Quire Compose

The **native Android shell** for Quire — Kotlin + Jetpack Compose drawing the
interface, [`quire-core`](https://github.com/PT123123/quire-core) holding the
document model, the SQLite store and the services behind it.

It is the third repository of one workspace, and the second Android shell:

| repository | what it is |
|---|---|
| `quire-core` | the model, the store, the windowless services — pinned here as a git dependency |
| [`quire`](https://github.com/PT123123/quire) | the desktop shell (Rust + Slint) |
| [`quire-droid`](https://github.com/PT123123/quire-droid) | the Android shell as it was: Rust + Slint, one `NativeActivity` |
| **`quire-compose`** (this one) | the Android shell in the platform's own idiom |

## Why this exists

The Slint shell draws every pixel itself, which is how the desktop and the phone
ended up running one set of components. It also means the phone never gets any of
Android's own behaviour for free: text selection handles, the IME's own
composition UI, the platform's touch targets, back-gesture and lifecycle
conventions. This repository takes the other trade: the UI is written twice, and
in exchange the Android side is an Android app.

**Both are installable side by side.** The application id here is
`dev.quire.compose`; the Rust shell is `dev.quire.android`. Two ids, two
signatures, two icons-in-the-launcher — which is also what makes this shell safe
to develop without touching a working app.

**One library, not two.** Both shells open `files/Quire/quire.db`, the same file
name a desktop install makes under `%APPDATA%`, so the database — pages, blocks,
notes, tasks — is shared rather than migrated. Undo stacks and the open-page
setting are per-session, but the recents list and the sidebar's open branches are
stored under the keys the other shells already read.

## The shape of the thing

```
app/                    the Compose app (Kotlin)
  src/main/java/dev/quire/compose/
    MainActivity.kt     the only Activity: edge-to-edge, one lifecycle signal
    QuireViewModel.kt   the session's owner: one call at a time, one writer clock
    bridge/             the typed face of the Rust bridge (JSON in, JSON out)
    ui/                 the palette, the icons, and the screens
      OrgModel.kt       SPEC §四十一's projections: views, sorts, badges, board
      MarkdownText.kt   the capture toolbar's text operations and the tag scan
      Organizer.kt      the 收件箱 and 任务 pages, the cards and the rows
      Notes.kt          a note's own page, 详细信息, and its replies
      CaptureOverlay.kt the instant capture panel both destinations share
      Sync.kt           同步: the device list, the pairing verbs and the timer
  src/test/             the unit tests that need no device
rust/                   the bridge crate: cdylib + rlib, JNI, `cargo test` on the host
  src/lib.rs            the four exported symbols
  src/session.rs        every operation the shell can ask for
  src/workspace.rs      the page tree (the core has no page commands)
  src/org.rs            the organizer's writes, ids, clock and undo stack
  src/sync.rs           LAN sync's shell half: the snapshot out, the merge back in
  src/view.rs           the projection the UI redraws
```

`app/build.gradle.kts` runs `cargo ndk` for one ABI and hands the resulting
`libquire_bridge.so` to AGP as an ordinary prebuilt library. Nothing else about
the build is unusual.

## Build

Needs: JDK 17, the Android SDK, the NDK, Rust with the `aarch64-linux-android`
target, and `cargo-ndk`.

```sh
just bridge           # the Rust bridge's tests, on the host (seconds)
just check            # bridge tests + app unit tests + a debug APK
just install          # debug APK onto the connected device, data preserved
just android-apk      # the signed release APK
just release-publish  # bump the patch, build, push, publish (Obtainium's source)
just deploy-workshop  # bump the patch, build, deploy to C:\workshop (the local copy)
```

Machine-local files: `local.properties` (the SDK path, gitignored).

**Signing.** Releases are signed with the keystore in the user's own directory,
`C:\Users\ted\keystores\debug.keystore` — despite the name, it is this app's
permanent identity and not a throwaway, which is why the *release* uses it. Debug
builds carry the same key, so `just install` and a published APK replace each
other on the device instead of colliding (ADR-0010). The path and the key's
default credentials are named in `app/build.gradle.kts`; the keystore file itself
never enters the repository, and a `keystore.properties` (gitignored) overrides
the defaults on a machine that keeps its identity somewhere else.

## What works today

Two slices, end to end: **the document** and **the organizer**.

**The page tree and the block editor.**

- Open/create a library; the page tree with nesting, favorites, recents and
  per-branch folding; create, rename, delete (subtree and all), favorite and
  lock a page; long-press a page row for its menu.
- The block editor: paragraphs, headings 1–3, bullets, numbered lists, to-dos,
  quotes, code blocks, callouts, toggles and dividers — editing text, Enter for
  the next block, the ⋮ handle for the block menu (kind, move, indent, delete),
  undo and redo, and inline marks (bold, italic, strike, code, links) painted
  from the core's own byte offsets.

**SPEC §四十一's 收件箱 and 任务**, reached from the drawer as two separate pages.
**收件箱 is home**: the app opens there, and the back gesture returns there from
either of the other two destinations rather than leaving the app.

- **收件箱**: notes as cards — the text, its tags on an accent line, its age —
  with a tag chip row, a hideable search field, and 排序（最新创建 / 最新更新 / 按内容）.
- **Capture is instant.** The round ＋ opens a non-animated overlay — a multi-line
  field, a markdown toolbar (`#`, `B`, `/`, `•`, `1.`), a ➤ send and tag
  suggestions — with the caret asked for on the frame it appears, so the keyboard
  comes up with it. A note's `#tokens` become its tags, and one 撤销 puts the whole
  note away.
- **A note opens on a page of its own**: its body, its tags, a 详细信息 sheet (id,
  created, edited, tags, length, pinned, ref, comment count) and its replies.
  **评论 / 引用**: replying to a note writes a note carrying a reference to it — the
  card draws a `↩` preview of what it answers, the parent's page lists its comments,
  and tapping the preview jumps to the parent.
- **Delete is undo-able.** Deleting a note or a task hides the row at once and shows
  a three-second **撤销** bar; the row is really deleted when the bar expires, so
  撤销 drops work that was never sent. There is no confirmation dialog.
- **任务**: the five smart views (收集箱 / 今天 / 近七天 / 全部 / 已完成), five sorts
  behind ⇅, per-page search, glass rows carrying the list's dot, the priority, the
  deadline and the tags, and a 4 dp progress bar with 已完成 X / Y.
- The task detail form: list, priority, deadline, repeat, tags, notes and a
  checklist.
- **平铺**: one column per list on cards, with a long-press drag to move a task
  between lists — and the same move in the row's ⋯.
- Lists: create, rename, recolour, delete (its tasks move to the inbox in one
  undoable step).
- **Its own undo and redo.** The organizer has a stack of its own
  (`core::ORGANIZER_STACK`), so a 撤销 in 收件箱 can never reach a page's edits.
- Light and dark themes in the shell's own palette, `跟随系统` by default, in
  Settings rather than on the main screen.
- Everything above is persisted through `quire-core`'s change stream, debounced
  the way the other shells debounce it.

**同步**, a fourth page: LAN sync over `quire-core`'s own protocol (HTTP 5878, UDP
discovery 5879, snapshot v2, a three-way merge against a per-peer shadow).

- The device list: this device's alias and address, **已配对的设备** with 在线/离线
  and 上次同步, **已发现的设备** with 发起配对, and 按地址添加 for a network where
  the announcement cannot get through.
- 立即同步 and 忘记 per device; 10 秒 / 1 分 / 5 分 / 30 分 / 仅手动 for the interval;
  the last dozen log lines.
- **Opening the page is what puts this device on the LAN** — the engine's threads
  and its socket start with the first request, so nothing listens otherwise. The
  engine's work is answered on the ordinary one-second tick.
- The peer book, this device's identity and the per-peer shadows are the same
  `sync.*` `settings` rows the desktop shell uses, so a library carried between
  shells keeps its pairings.

## What does not work yet

Said plainly, because a shell that pretends is worse than one that is small:

- **Tables, columns, formulas, tables of contents, link cards, synced mirrors
  and databases** render as a labelled placeholder row that can be moved or
  deleted but not edited. The desktop's `DatabaseView` is the largest single
  component it has, and it has not been ported.
- **Images and files** show their attachment id, not the picture.
- **Search** is the organizer's own two needles and nothing else — no page
  palette and no in-page find bar.
- **A library with a database or an attachment cannot be synced at all.** This
  shell models neither, and a snapshot that dropped them would make a peer's merge
  read the absence as a deletion — so 同步 refuses, says why, and starts nothing
  (ADR-0016).
- **The read-only LAN share** (port 5877, `quire-core`'s other LAN module) is still
  not wired, and neither are the reference app's pairing codes, per-device
  statistics, conflict lists, or its cloud / backup / WiFi-transfer siblings —
  `quire-core` has none of those.
- **Backspace on an empty block** does not merge it into the one above: a soft
  keyboard's backspace is not a key event a Compose text field can see. Deleting
  an empty block goes through the block menu. (The organizer's fields do not need
  it: a note is one plain-text field.)
- **Multi-select** in 收件箱 / 任务 (选择 / 多选, with bulk pin, tag and delete) is not
  here. The reference app has it and it is a mode with its own toolbar, selection
  model and undo story — half of one would be worse than none (ADR-0014).
- **No persistent 回收站.** The deferred delete buys three seconds, not a bin: a
  real one needs soft delete in `quire-core` (a column, the store, the merge), a
  settings page, and restore/purge — a cross-repo slice of its own (ADR-0015).
- **A note has no history / 恢复版本.** The core keeps one copy of a note, so 撤销
  walks an edit back but 详细信息 has no versions to list, and says so.
- **A repeating task does not roll forward** when it is completed. The rule is
  stored and shown; v1 deliberately does not fake the roll by rewriting the
  deadline, which would be a derived write with no undo of its own.
- **A note's text is plain text**, not rendered markdown: the toolbar writes the
  markers, and the card shows them as written. The task half is where the format
  work went.

## Development notes

- The bridge's protocol is testable without a device: `just bridge` drives the
  same `Session` the JVM drives, over the same JSON.
- Two things in Kotlin have unit tests, both of them things that can be wrong
  without looking wrong: the mark offsets, which are bytes in the database and
  UTF-16 indices in Compose (`MarksTest.kt`), and the organizer's projection
  rules — the week's boundary, the undated-last sort, the dangling-list fold to
  the inbox, the chip row lighting exactly one half of the selector
  (`OrgModelTest.kt`).
- A third is the capture toolbar's text operations (`MarkdownTextTest.kt`): a
  heading cycle that eats a list marker, a bold toggle that doubles a mark, and a
  `#`-scan that calls "issue #3" a tag are all *nearly* right in a way nobody
  notices until the notes are full of stray asterisks.
- `just android-lib` builds only the `.so`; the Rust side's loop is `cargo test`
  in `rust/`.
- Version lives in one place, `gradle.properties`' `quire.version`;
  `versionCode` is derived from it so an update can never be refused for
  standing still.
