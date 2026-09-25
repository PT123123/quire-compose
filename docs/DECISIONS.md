# Decisions

Architecture Decision Records for the Compose shell. Format: decision →
context → consequences. Newest first. Numbering is per repository, so these
numbers have nothing to do with the desktop shell's or the core's.

## ADR-0017 · The local delivery is a workshop deploy, and it advances the version

Decision: `scripts/deploy-workshop.ps1` (`just deploy-workshop`) bumps
`quire.version`'s patch, builds the signed release APK, commits and pushes that
bump, and copies the APK to
`C:\workshop\quire-compose-<version>\quire-compose-<version>.apk`. The GitHub
publish (`just release-publish`) is unchanged and remains the delivery Obtainium
follows. The two are independent acts, and each one advances the version.

Why a second delivery at all. The workshop is the user's own drop zone: one folder
per release, named `<name>-<version>` (`aura-1.2.6`, `aw-qtui-0.1.36`,
`quire-desktop-0.1.4`), and the desktop shell has deployed into it since its
ADR-0105. Until now the Android shells could only publish, which left the build on
this machine and the build a release names identical by luck — and the APK a phone
is installed from is too useful to live only in
`app\build\outputs\apk\release\`.

Why the deploy bumps, rather than archiving whatever version is current. It is the
desktop deploy's reason in this repository's vocabulary: the folder is named after
the version, so a deploy that did not bump would be a second, different build
silently overwriting the previous release's folder, and the drop zone could no
longer say what was built when. The bump is also the only thing that gets
committed, which is what lets the folder keep saying what it was built from.
Accepted with it: deploying and then publishing moves the version twice, exactly
as it does on the desktop side.

The file inside keeps the release's own name — `quire-compose-<version>.apk` — so
the local copy and the published asset are the same file name over the same bytes,
and either one still identifies its release after being copied out to a phone. The
folder name carries the shell because the drop zone holds several applications and
this family has two Android shells in it (this one and `quire-droid`); a bare
`quire-<version>` would be a name to guess at, which is the argument the desktop
shell's folder name already makes.

Consequences:

- The copy is the **last** step. The bump, the build, the signature check and the
  push all happen before it, so a build or a push that failed leaves no folder
  behind claiming a version that never landed.
- The APK is verified signed before the copy (`apksigner verify --print-certs` —
  the check ADR-0009's publish makes). A missing keystore, which lives in the
  user's own directory and outside this repository, yields an *unsigned* release
  APK that installs nowhere, and the workshop is where one gets picked up from.
- No check against the APK's own `versionName`, unlike the desktop deploy's look at
  the exe's version resource: `gradle.properties` is a configuration input Gradle
  re-reads on every configure, and the script reads the bumped value back before
  building, so the folder's name and the APK's versionName cannot disagree. The
  failure that would be *silent* is the unsigned one, and that is the one checked.
- `quire-droid`, the Slint Android shell, has no workshop deploy and this ADR does
  not give it one — that is a decision for that repository.

## ADR-0016 · 同步 is a destination, and a library this build cannot carry refuses to sync at all

Decision: LAN sync gets a **page** — a fourth drawer row, `ui/Sync.kt` — over
`quire-core`'s own protocol (HTTP 5878, UDP discovery 5879, the version-2
snapshot, the three-way merge). The shell supplies the two halves the core leaves
out (`sync_export` / `sync_apply_remote`, `rust/src/sync.rs`), the peer book and
the log are the same `settings` rows the other shells use, and the engine is
started **by opening the page** — nothing listens until 同步 has been shown.

The page carries what this protocol actually has: a discovery banner, this
device's address and id, **已配对的设备** with 在线/离线, 上次同步 and 立即同步 /
忘记, **已发现的设备** with 发起配对, 按地址添加 for a network where the
announcement cannot get through, the interval presets (10 秒 / 1 分 / 5 分 / 30 分 /
仅手动), 本机别名, and the last dozen log lines. It is the reference app's own page
narrowed to this protocol: that app's 同步 hub also has pairing codes, per-device
statistics, conflict lists, a permission/keep-alive page, D1 cloud sync, cloud
backup and a WiFi-transfer mode, and `quire-core` has none of them.

**The one decision that matters: a library with a database or an attachment
cannot be synced by this build, and it must refuse rather than sync partially.**
This shell's UI does not model either (databases render as a placeholder, images
as an id), and the core exposes no public bulk write for the database layer — so
a snapshot built here would carry `databases: []` / `attachments: []` for a
library that has them. A merge reads *absence* as a deletion: the peer's shadow
would say those rows were agreed present, this device would say they are gone,
and the next merge would delete the user's databases on both machines. So the
gate is checked **before the engine starts** — no threads, no listening socket,
no announcement, the reason on screen — and an inbound snapshot that carries
either is refused. `SyncView.unsyncable` is the reason, and the page draws it.

Why the write path is `replace_all` plus row changes rather than the desktop's
incremental diff: the merged snapshot *is* a whole workspace, and this session
holds a `PersistedState`'s worth of it, so the document half goes back with one
`replace_all` — with `meta` and `settings` read out first and handed straight
back, because that call deletes those two tables and the peer book, this device's
id and the per-peer shadows all live in `settings`. The organizer is *not* part of
a `PersistedState` (a bulk replace leaves it alone on purpose), so its rows go
back as the same `Change`s every organizer write uses, from a row-level diff.
Afterwards the session rebuilds what it holds in memory and **drops both undo
stacks**: their entries name rows the merge may have replaced.

Consequences:

- The engine's jobs are answered on the ordinary one-second tick. The core's
  threads block until the session replies, and the tick is the session's own
  heartbeat, so no second timer exists and an idle app's tick still answers with
  nothing to draw. A job that cannot be answered — an export of a library that
  fails the gate — is answered by **dropping the reply channel**, so the peer's
  pull fails loudly instead of receiving an empty workspace, which its merge
  would read as "this device deleted everything".
- The merge's fresh ids are seeded from `max(local, remote) + 1` per collection
  rather than reserved from the session, and the reload walks every in-memory
  watermark past what it loaded — so nothing minted next can collide.
- `INTERNET` is the only manifest permission (it was already there, reserved for
  this). UDP broadcast reception is not guaranteed on every Android device, which
  is why 按地址添加 exists; there is no multicast lock and no keep-alive page.
- Not here, and named so they are not mistaken for oversights: the read-only LAN
  **share** (port 5877, `quire-core`'s other LAN module), conflict lists,
  per-device statistics, pairing codes, and the cloud/backup/WiFi-transfer
  siblings the reference app has. The desktop shell's `settings` rows mean a
  library carried between shells keeps its peer book, its identity and its
  shadows.

## ADR-0015 · 收件箱 as home, an instant capture, undo-able deletes, and 引用

Decision: four changes to the organizer, each one a thing the reference app does
and this shell did not.

**收件箱 is home.** The app opens on 收件箱, and the back gesture walks *down* into
it: close the capture overlay, then leave a row's form, then leave the destination
— and both 任务 and the document go **home to 收件箱** rather than out of the app.
Only 收件箱 itself hands the gesture to the system, which is what makes it the
bottom of the stack. The drawer's page tree is therefore the way *into* the
document, and that had to be fixed rather than assumed: `PageTreeRow.onOpen` called
`openPage` and never switched the destination or closed the drawer, so tapping a
page changed what was open behind a 收件箱 that stayed on screen.

**Delete is deferred behind a 撤销 bar.** A delete hides its row the moment it is
asked for and shows a bar with a 撤销 action; the real command goes out only when
the bar's three seconds expire, and 撤销 drops a batch that was never sent. Notes
and tasks both, from the row's ⋯ and from the task detail's 删除任务. The
confirmation dialog is **gone** — the bar is what it was standing in for, and one
fewer tap on every delete is the point. The pending delete lives on the view model
and not in the screen that asked, so the bar survives leaving the page; a second
delete commits the first, so there is only ever one bar.

**Capture is an overlay, not a `ModalBottomSheet`.** Same content — the multi-line
field, the markdown toolbar, the ➤, the tag suggestions — in a plain `Box`: a scrim
that dismisses, a bottom panel, and the caret asked for on the same frame the field
appears.

**A note opens on a page of its own**, with its body, its tags, a 详细信息 sheet and
its replies. And 引用: a comment is an ordinary note carrying `ref_note` — the note
it answers — drawn as a muted `↩ <parent's first line>` under the text and listed
in the parent's 评论 section. `core::organizer` gained the field (quire-core
ADR-0001), the bridge carries it, and every read of it is a projection of the one
catalog: `OrgModel.comments` is `notes.filter { it.ref == id }`, so a comment can
never drift out of the notes list.

Why: the reference app. Its delete hides the row and shows a 3 s 撤销 bar (a
persistent bin is not what it does, and not what was asked for here); its capture
window is a floating panel that is on screen the instant the ＋ is tapped; its
note opens on a separate screen; and its 引用 is a note previewed under the note
that cited it. The overlay is also a straight latency fix: the sheet's spring — a
scrim fade plus a two-stage expand — was what a person experiences as "the
keyboard is slow", because the IME cannot start until the animation lands.

Consequences, including what is deliberately *not* here:

- The 撤销 bar shows the same row for notes and tasks and its three seconds are the
  view model's alone (`SnackbarDuration.Indefinite`, one clock). If the app is
  killed inside the window the delete simply never happens and the row is still
  there — the safe direction, and the reason there is no journal to recover.
- **No persistent 回收站.** It is not a shell feature: it needs soft delete in
  `quire-core` (a column, the store, and the merge), a settings page, and
  restore/purge. The deferred delete is the part that does not, which is why it is
  the part that shipped.
- The overlay loses swipe-to-dismiss; tapping the scrim and the back gesture both
  dismiss it, and the draft survives either. If that reads worse than the latency
  it removes, it is one composable to change back.
- A **dangling ref** is tolerated end to end: deleting a parent leaves its comments
  pointing at an id that names nothing, and the card paints as an ordinary note
  rather than as a broken link — the same rule `blocks.page_ref` keeps. The merge
  remaps a renumbered parent and passes an unresolvable ref through unchanged.
- 详细信息 lists what the core actually holds. The reference's 历史 / 恢复版本 has
  **no counterpart** for a note, so the sheet says so instead of showing an empty
  history.
- The comment is visible to the desktop shell as soon as it reads the same
  `quire.db`; the desktop's projection does not paint the ref yet, which is the
  "ordinary note" answer and therefore incomplete rather than wrong. Its
  `docs/SPEC.md` §四十一 and its own ADR belong to that repository and wait for its
  next core bump.
- Not in this pass, and named so they are not mistaken for oversights: 转为待办,
  multi-select (选择 / 多选), note history, and rolling a completed repeating task
  forward.



Decision: SPEC §四十一's notes and tasks are **two separate pages**, each reached
from its own drawer row, each with its own toolbar and its own state. There is no
tab strip between them and no shared bar. The document is not replaced — leaving
and returning finds it exactly as it was. Inside 任务, the system's back gesture
walks out of a row's form and then out of the page. A stored list's rename /
recolour / delete are behind a **long press on its chip**.

Why: this is what the reference app does. 收件箱 is a page with 搜索 / 排序 / 多选
in its toolbar and a 标签 column; 任务 is another page with 搜索, 新建清单 and its sort
menu, and its own 收集箱 / 今天 / 最近 7 天 chips. They are two screens in the
drawer, not two tabs on one — so a shell that puts one strip over both is a shell
that has to decide, on every control, which half it belongs to. Two destinations
also means the state each one keeps (which view, which list, which needle) cannot
be confused for the other's, and the back gesture has an unambiguous meaning in
each.

The first version of this shell put 笔记 / 任务 chips in one bar over one screen.
That was the wrong shape, and it read as one: a *tab* is a control for switching
between views of the same thing, which these are not.

The long press is the same vocabulary the sidebar uses for a page, and it is here
rather than a chip-adjacent button because a chip row on a phone is already tight.
It closes a real gap: the Slint shells declare `org-list-name-set`,
`org-list-color-set` and `org-list-deleted` and never invoke them, so a list can be
created there and never renamed. The three writes exist in the core and in
`org.rs`; this is the shell that wires them.

Consequences: `NotesBar` and `TasksBar` are separate composables — different
titles, different actions, different menus — and `openNotes()` / `openTasks()`
reset the destination's own state on the way in. 列表/平铺 lives in 任务's overflow
menu rather than the bar: the desktop's copy puts it in the card's header, and on a
phone the bar has no room for it beside 搜索, 排序 and the undo pair (a squeezed
title is the price of trying — it read as "任"). A list's task count in the delete
confirmation is the catalog's own, because the command moves them in the same
undoable step.

## ADR-0014 · A note is one field, and capture is a floating sheet

Decision: a note on this shell is **one blob of text**, created and edited in a
**floating capture sheet** — a bottom sheet carrying a multi-line field, a markdown
toolbar (`#`, `B`, `/`, `•`, `1.`), a ➤ send button and nothing else. There is no
cancel button. The field's `#tokens` become the note's tags, and the core's `title`
field is left empty.

Why: again the reference app. Its capture window is a floating sheet whose bottom
row is exactly that toolbar plus a send glyph, and its note model is a single text
field — `NoteCard` renders the content, then the tags, then the age, with no title
anywhere. A note with a title *and* a body would be this shell inventing a second
model and then showing both on a screen that has room for one.

Three details are load-bearing rather than decorative. `#`, `B` and the two list
keys are text operations with rules that are easy to get *nearly* right (a heading
cycle that eats a list marker, a bold toggle that doubles a mark), so they live in
`MarkdownText` as pure functions with their own unit tests. ➤ is one glyph so the
toolbar has the width. And nothing commits until ➤: the sheet is a draft, and its
text lives in the view model, so a swipe-away loses nothing.

Consequences: the core's `title` stays empty for a note made here, and the
desktop's list shows it as 无标题 above the body's first line. Deriving a title from
the first line would paint that line twice on the desktop — worse than a
placeholder. Notes and tasks are created with **one** command
(`orgAddNote` / `orgQuickAdd`, both carrying the text and its tags), because the
sheet produces one thing and a create-then-fill would leave a blank row on the undo
stack and cost three presses of 撤销 to put away. A note the desktop made with a
title and no body still reads here: the card falls back to the title.

**Not ported from the reference app**: multi-select (选择 / 多选) with its bulk
pin, tag and delete. It is a mode with its own toolbar, its own selection model and
its own undo story, and it is listed in the README's "what does not work yet"
rather than half-built.

## ADR-0012 · The organizer's 今天 is the device's own day, not UTC

Decision: `ui/OrgModel.kt` takes "today" from the device's local calendar
(`Calendar.getInstance()` plus a day offset), not from `now / 86_400`.

Why: the Rust shell's `OrgDates::now` divides unix seconds by 86 400, which is the
**UTC** day. East of Greenwich that is wrong for a large part of every day: at
01:00 in UTC+8 it calls yesterday "today", so a task due today is filed under
近七天 and its badge says yesterday's date. The row's badge and the 今天 filter are
the one place in this app where the user's own clock is the authority — they typed
the date against the calendar on their wall.

Consequences: this is a **deliberate difference from the Rust shell**, not an
accident, and the two shells can disagree about which task is "today" while
standing in the same time zone east or west of UTC. The comparison itself is
unchanged — ISO strings compare as dates because the format is fixed-width. Still
one clock read per redraw (`OrgModel.Dates.now()` is remembered against the
catalog), so the filter and every badge in one frame cannot straddle midnight.
`java.time` is deliberately not used: minSdk is 24 and desugaring is not enabled.

## ADR-0011 · The organizer crosses as a catalog; the document crosses as a view

Decision: the organizer's rows go over the bridge whole — `view::org_catalog`
sends every note, task and list with no filtering, sorting or derived fields — and
`ui/OrgModel.kt` derives the five smart views, the three sorts, the deadline
labels, the chips and the board. The document keeps the opposite shape: `view.rs`
sends rows that are already decided.

Why: the two have different interaction profiles. Every document edit *is* a
command through the core's funnel, so a reply is the natural re-projection point,
and a keystroke is answered with `{"ok":true}` and no rows at all. The organizer's
*filters* — tab, view, list, sort, needle, show-done — are not edits: they are
facts about the window (`UIState` on the desktop, `ADR-0073`'s rule), and sending
each one over the bridge would put a round trip and a full catalog of JSON on
every keystroke of the search box to compute something the shell already has all
of the inputs for. The Rust shell agrees and keeps the same rules in its own
`src/app/state.rs`; no version of `quire-core` knows what 今天 means.

Consequences: the projection rules exist twice in the repository's *family* — once
in Rust for the Slint shells, once in Kotlin here — and a rule changed in one is a
rule changed in the other. The mitigation is the same one the mark offsets have:
`OrgModelTest` asserts the rules that can be wrong without looking wrong (the
week's boundary, the undated-last sort, the inbox fold for a dangling list, a
finished task never being overdue), with the assertions the Rust shell makes.

Every organizer **write** still crosses, and answers with the whole view. That is
not the cheap answer — an edit echoes the catalog back — but it is the correct
one: the row's `edited` instant is stamped on the Rust side, a task may have
changed which views it belongs to, and the chip counts moved. The two fields a
person types into are debounced for 300 ms on the Kotlin side, which is what keeps
that off the keystroke path.

## ADR-0010 · One signing identity, named in the build, used for release and debug alike

Decision: releases are signed with `C:\Users\ted\keystores\debug.keystore` — the
keystore in the user's own directory — and that same key signs debug builds. The
path, alias and passwords are named in `app/build.gradle.kts` as the default
(`keystore.properties`, gitignored, can override them). The keystore *file* stays
outside every repository.

Why: the name `debug.keystore` is Android tooling's default name for a key
generated locally the first time anything was debugged, and it is misleading here
— the user promoted that key to the app's permanent identity, so it is what the
*release* must be signed with. The name describes where the file came from; the
role is the app's signature.

Naming it in the build rather than in a gitignored file is a correction of this
repository's first arrangement. The signing values are the Android tooling's
documented defaults for that key (`androiddebugkey`, password `android`) and are
public knowledge, and the sibling Rust shell already commits them in its
`Cargo.toml`'s `[package.metadata.android.signing.release]`. Hiding them only made
a clean clone unable to publish while protecting nothing.

Extending it to debug builds prevents a specific, silent failure: AGP's default is
to sign debug builds with its own generated `~/.android/debug.keystore`. On this
machine that file happens to hold the same key, so everything looked right — but
the day it is regenerated (a wiped profile, another machine), every `just install`
would put an app on the tablet that the next released APK cannot install over,
and the only fix would be an uninstall, discovered long after the cause.

Consequences: both APKs carry the certificate
`e0bb843a9192a9579727be09083540a277b15bac967d49cf26a8f30d750a6400`, verified with
`apksigner verify --print-certs`, so a debug install and a released install
replace each other freely. v1 (JAR) signing is off — minSdk 24 is exactly where v2
begins — and v2/v3 are on. `scripts/release-publish.ps1` verifies the signature of
the APK it is about to publish instead of trusting the build's exit code, because
a missing keystore yields an *unsigned* release that would otherwise ship.

## ADR-0009 · One version, in gradle.properties, with versionCode derived

Decision: the app's version is written once, as `quire.version` in
`gradle.properties`; `app/build.gradle.kts` derives both `versionName` and
`versionCode` from it, and `scripts/release-publish.ps1` bumps the patch on
every publish.

Why: Android refuses an update whose `versionCode` does not advance, and
Obtainium — which is how this app is delivered to the one device it runs on —
sees an update only when the release carries a new one. Two files to edit for one
release is one file to forget, and the failure mode of forgetting is silent: no
update, no error. The Rust shell derives its version from `Cargo.toml` by the
same reasoning (`cargo-apk` reads `[package].version`); this is that decision in
Gradle's vocabulary, where the single source has to be declared explicitly.

Consequences: `versionCode` is `major*10000 + minor*100 + patch`, so the minor
and patch fields are two digits wide each. `quire.version` must be
`MAJOR.MINOR.PATCH`; the build fails loudly at configuration time otherwise.

## ADR-0008 · Marks are painted from byte offsets, with an identity offset mapping

Decision: a block's inline marks arrive as the byte offsets the core stores, and
are converted to UTF-16 indices in Kotlin (`ui/Marks.kt`) before being applied as
`SpanStyle`s. The visual transformation returns the transformed text with
`OffsetMapping.Identity`.

Why: the two offsets disagree exactly where this app lives. `quire-core` stores
a mark's span in bytes into UTF-8 — that is what the `marks` table round-trips —
while Compose measures text in UTF-16 code units. For ASCII they coincide, which
is how a bug here stays invisible until the first Chinese character: 你 is three
bytes and one unit, so a mark on the third word of a Chinese sentence lands eight
characters early.

The identity mapping is not an approximation but a fact of this transformation:
it adds styling and never inserts or removes a character, so every index means
the same thing before and after. That is what keeps the caret, the selection and
the IME's composing region honest while marks are on screen.

Consequences: mark conversion is a per-block linear walk, run only when the marks
change (`remember(row.marks)`). The conversion has its own unit test, because it
is the one piece of this shell that can be wrong without looking wrong.

## ADR-0007 · The chrome's icons are drawn, not imported

Decision: `material-icons-extended` is not a dependency. The two icons it would
have supplied — undo and redo — are original vector paths in `ui/Icons.kt`;
everything else comes from `material-icons-core`, which Material 3 already
brings.

Why: the extended set is several megabytes of vector paths to ship two glyphs,
in a product whose own specification ranks low RAM above maintainability and
feature count. The Slint shell made the same call for the same reason and draws
its own icons too.

Consequences: a new icon is a few lines of `ImageVector` rather than a
dependency bump. Both icons are stroke-only and unbaked, so `Icon(tint = …)`
colours them with the theme.

## ADR-0006 · Settings live in the drawer, not on the main surface

Decision: the theme switch, the read-only switch for the current page, and
anything global live behind one row at the bottom of the drawer. The main surface
is the document and the page tree.

Why: this is the rule the desktop shell already follows, and a phone is stricter
about it — a control on the main surface costs a row of every screen to serve an
errand nobody runs twice a day. The theme in particular is a global setting that
had no business being a button next to a page's title.

Consequences: `跟随系统` is the default, so the app tracks the device unless the
user says otherwise, which is the platform's own convention.

## ADR-0005 · A separate applicationId, over a shared library

Decision: this app is `dev.quire.compose`; the Rust shell is `dev.quire.android`.
Both open the same database — `files/Quire/quire.db`, the same folder name a
desktop install makes — and read and write the same settings and meta keys.

Why: two application ids, two signatures, two launcher entries, which is what
makes this shell developable without endangering a working app on the only device
it runs on. And one library rather than two because the alternative is a
migration between shells for data both of them already understand: the core
stores pages, blocks, notes and tasks, and neither shell owns any of it.

Consequences: the two shells can hold the same library open in turn, and the
second one to open sees the first one's committed work — but not its uncommitted
work, and not its undo stack. A library is shared between shells the way it is
shared between two devices: at rest.

## ADR-0004 · A block's handle follows selection, never hover

Decision: the block menu's door is a ⋮ that appears in the row's gutter once the
row is focused. A long press opens the menu only on rows that are not text
fields.

Why: a finger cannot hover, so every desktop affordance that appears on hover
needs a selection-driven equivalent — this is the rule the Slint shell learned on
the same hardware. The obvious candidate, a long press, is not available: inside
a text field a long press is how the platform selects text, and a control that
competes with the platform's own gesture loses. So: tap a block, the handle
appears, tap the handle.

Placeholder rows (a table, a database, a picture) are not text fields, so they
keep the long press, which is the gesture touch actually expects for "the rest of
this row's verbs".

Consequences: one 30 dp gutter column carries either the block's marker or the
handle, never both — on a 360 dp screen, two columns of chrome cost more than the
distinction is worth.

## ADR-0003 · A reply carries the whole view — except a keystroke

Decision: every structural request answers with the entire projection (page rows,
the open page's block rows, the flags). The one exception is `setBlockText`,
which answers `{"ok":true}` and nothing else.

Why: full replies mean the UI never patches state in two places, which is the
class of bug that takes a week to find. But a text edit fires on every character,
and echoing a page's worth of rows back per character is work nobody reads — on a
page with ten thousand blocks it is the difference between typing and not typing.
The client already has the text it just sent, so silence is the correct answer
there and only there.

Consequences: the rule is written down in both `session.rs` and `Bridge.kt`,
because it is the one place where a reply's shape depends on the request, and a
future operation that fires per keystroke has to join that list deliberately
rather than by accident.

## ADR-0002 · The page tree lives in the shell, again

Decision: `rust/src/workspace.rs` is a second implementation of the page tree —
title, parent, order, favourite, expanded, icon, locked — with its own create,
rename, delete, subtree and ordering logic. The core's `Command` enum has no page
arms; page writes go straight to storage as `Change`s.

Why: this is not a new decision, it is the existing one arriving here. `quire-core`
owns the document, the database layer and the organizer, and deliberately no page
tree: page operations are the shell's model, which is why the desktop and the Rust
Android shell each carry one already. This repository is the third consumer of the
same rule, not a place to change it — and changing it (moving the tree into the
core) would be a change to the shared crate to suit one shell, in the opposite
direction from where the split was drawn.

Consequences: this version is deliberately smaller than the other two — no bench
pages, no templates, no covers, no search blob. What it does keep is the *format*
of the persisted state, so the three shells agree about the library even though
three copies of the code now decide how to walk it.

## ADR-0001 · JSON over four JNI symbols, not UniFFI and not a symbol per operation

Decision: the Kotlin shell talks to Rust through exactly four exported symbols —
create, open, dispatch, close — and `dispatch` carries one JSON request and
returns one JSON reply. There is no generated binding layer.

Why: the core has no FFI at all (no `uniffi`, no `jni`, no `extern "C"`), because
its rule is that nothing under its `src/` may know about a consumer — so whatever
crosses the boundary is this repository's to invent, and the two obvious
alternatives each cost something real.

*A symbol per operation* puts half the protocol in Rust function signatures and
half in Kotlin ones, and every field, every rename and every new operation
touches both sides and neither compiler sees the other. One string each way keeps
the whole contract in `session.rs`, where it is covered by `cargo test` on the
host, with no emulator and no JVM.

*UniFFI* is the industry answer for this shape and was seriously considered. Two
things ruled it out here. Its Kotlin bindings reach the native side through JNA:
a second marshalling layer, a reflection-based dependency, and a runtime cost
under a UI whose specification ranks low RAM above maintainability and feature
count. And its `#[uniffi::export]` derives would have to go on the shared crate's
model types — the `Block` and `Command` enums — which is exactly the direction the
core's rule forbids.

Consequences: the request and reply shapes are hand-written on both sides, and
the Kotlin half parses with `org.json` rather than a serialization library (flat
payloads, one file, no compiler plugin). A payload that grows beyond what
`org.json` handles pleasantly is the signal to revisit this, not a reason to
pre-decide it. The one operation whose reply shape differs from the others —
`setBlockText`, which answers with nothing — is called out in ADR-0003.

