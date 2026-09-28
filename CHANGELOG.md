# Changelog

## Unreleased — 笔记与任务按 唯一 ID 合并，revision 说了算

The organizer's half of the sync, ported to the reference app's rule (core ADR-0004,
this shell's ADR-0026, the desktop's ADR-0125). `notes` and `tasks` now merge by their
**唯一 ID** and settle two copies of one row by the newer **revision**; both devices
must be updated together, because `SNAPSHOT_VERSION` moves to 4.

- **A note or a task is keyed by its uuid, not by its integer row id.** Two devices
  that each filed a note under row 1 hold two notes — the integer is a per-device
  watermark and the uuid is the name — so the merge no longer renumbers the organizer,
  and a reply's `ref_note` follows its parent to the id it actually landed under here
- **A conflict is settled once, by the revision** (`"{millis:013}-{device}"`, compared
  as a string): the newer copy stands, on both ends, in the round that finds it. The
  old rule kept this device's copy *and wrote it into its shadow*, so the next round
  read the other device's row as a one-sided edit and settled the same argument again,
  the other way
- **Every organizer write here stamps that revision**, and it is stamped in the one
  funnel every write already passes through (`apply` / `apply_all`) rather than at the
  dozen call sites that build the rows. `Organizer` therefore holds this device's id,
  read once when the session opens; the `before` half of an update is deliberately left
  alone, so one 撤销 puts a row back looking as old as it was instead of newer
- **A bin and a restore are writes like any other**, which is why the revision is a
  field of its own: `edited` deliberately does not move when a row is binned, so a bin
  and its restore would otherwise carry the same stamp with nothing to order them
- **A purge still stays purged**: it is the one removal with no row to carry it, so the
  per-peer shadow remains what tells "this device never had the row" from "this device
  emptied it out of 回收站"
- New tests: `the_funnel_stamps_the_written_row_and_never_the_before_half`; the core's
  `the_uuid_is_the_key_not_the_integer_id`,
  `an_organizer_edit_lands_and_the_newer_revision_wins_a_double_edit`,
  `a_bin_travels_as_a_write_of_the_row_it_belongs_to` and the v30 migration step

## Unreleased — 同步：一次带表格的往返不再关掉本机同步

Two desktop-paired bugs, both about what this shell is allowed to *refuse*
(ADR-0024, ADR-0025; the desktop's halves are its ADR-0122 and ADR-0123).

### 对端有表格，本机照样同步

- **A `database` block no longer vetoes a round.** The gate scanned this device's
  blocks for a table and answered `Some`, and `Some` is the answer that keeps
  `Engine::start` from starting anything: no worker thread, no listening socket, no
  announcements, no pairing. One successful sync with a desktop that had a table
  anywhere in its library was therefore enough to switch this device's sync off for
  good — with a line telling the user to sync with the desktop they had just synced
  with. A table's *block* is a placeholder this shell already draws (▦ 数据库); its
  *rows* were never in that round's danger, because this build exports none and the
  desktop answers a phone's silence about databases with its own copy
- **The veto keeps one clause: this device's own attachment rows.** There is no door
  through which an attachment row can enter — inbound ones are dropped below, and
  `Job::AttachmentBytes` answers empty — so the only library that can grow one is a
  desktop's file copied onto the phone, and `replace_all` deliberately leaves the
  attachments table alone. Offering a peer a row with no file behind it is the case
  the veto was ever about
- **An inbound snapshot is trimmed at this door instead of refused.** A desktop
  library with a table or a picture in it used to answer 409, so its pages, notes and
  tasks went unsynced along with the tables. The rows are cleared off a clone of the
  inbound snapshot — which is what keeps them out of the shadow this device agrees to,
  and the reason an accepted attachment row would have been worse than a refusal — and
  the log says how many were dropped
- **An inbound push from a device that is not paired is refused.** The core's server
  marks whoever POSTs as `paired: true` on the strength of the snapshot naming itself,
  so the request cannot vouch for itself and this shell is the only place that can ask
- **The automatic cycle only dials a peer that announced in the last minute**, the same
  window the 在线 dot uses: a silent device is a device whose server stopped too, and
  the round that follows only times out

### 同步页说的还是那两件事

- The 同步 page's foot line now names what does not cross (**a table's contents, an
  attachment's bytes**) and where to edit a table (**the desktop**), rather than
  announcing that a library with either refuses to sync at all — which was the old
  rule, and no longer the gate's question
- New tests: `a_peers_table_does_not_switch_this_devices_sync_off` (the block travels,
  the rows do not, the page still offers a round), and the rewritten
  `a_library_this_build_cannot_carry_is_refused_rather_than_half_synced`, which now
  pins the single clause that remains


## Unreleased — 在系统浏览器中打开地址

The shell gains an OS-open path (ADR-0023), the Compose half of the desktop's
`open_link`.

### 链接卡片可以打开了

- **Tapping an embed card opens its address outside the app.** A 链接卡片 row is a
  placeholder with no text field, so the tap is free: it hands the address to an
  `Intent.ACTION_VIEW`, which is the browser's or the mail app's to render —
  exactly what the desktop shell's card does, and the only action an embed has.
- **The address passes two gates first.** A bare domain (`example.com/a`) gets an
  `https://` scheme, and only `http` / `https` / `mailto` addresses reach the
  system — the same allow-list `quire-core::embed::is_openable` keeps, so a
  `file:` or `javascript:` address a document carries cannot act on the device.
- **Not yet: a link inside a paragraph.** Compose 1.6 has no link annotation for a
  text field, and intercepting taps there would fight the caret and the IME, so
  inline link marks stay styled but inert for now (ADR-0023).
- New test: `LinksTest` pins the scheme rule and the allow-list against the same
  cases the core's own `embed` test uses.

## Unreleased — 筛选预填与 打开笔记页自动弹出输入框

Two alignments against the reference app's inbox, both about the tag filter
(ADR-0022; the desktop shell has the same pair, its ADR-0117).

### The composer inherits the filter

- **Tapping ＋ on 收件箱 with a tag filter on opens the field with `#当前标签 `
  already in it** — the reference's own habit, and one 任务's ＋ has had all along.
  The two buttons now share one `composerSeed`, which is also the reference's
  priority order: a draft already in hand wins over the preset, so a sheet
  dismissed mid-sentence still loses nothing.
- **The pre-filled token is not in the way of the caret any more.** The overlay
  seeds its `TextFieldValue` with the caret after the text; it started at 0, which
  would put the first keystroke in front of the preset (so 任务's pre-fill was
  already writing rows the tag never reached).
- **➤ merges the filter into the new task**: the `#tokens` in the line plus the
  include path, deduplicated — `(tags + listOfNotNull(currentTag)).distinct()`,
  the reference's own line. Editing or deleting the preset therefore no longer
  means the task leaves the filter it was made in. The board column's ＋ gets the
  same tags.

### 打开笔记页时自动弹出输入框

- **A new row in Settings, under 笔记**: with it on (the default) arriving at
  收件箱 — the drawer row, or a cold start that lands there — opens the capture
  overlay with the caret in it, ready to type. Off means the list opens on its own.
- It is the **library's** row, not the device's: `notes.auto_input` round-trips
  through the bridge (`setAutoInput` → `Request::SetAutoInput`) and is read back as
  `View.autoInput`, so the desktop shell reads the same value out of the same
  `quire.db`. An absent row means on — the shipped default.
- 任务 deliberately has no such switch: the reference sets 收件箱's inbox up this
  way and leaves its todo page alone.
- New test: `auto_input_defaults_on_and_the_switch_is_the_library_s`.

## Unreleased — AW's theme catalog

The desktop shell adopted ActivityWatch's theme table and this shell follows, so
the two agree about what a theme *is* (ADR-0021). Both shells read the same
`theme` row out of the same `quire.db`, so the ids — not the colours — are the
contract.

### Twelve palettes, chosen by name

- `ui/Theme.kt` gains `ThemePalette` + `ThemeCatalog`: `aw-qtui/src/theme.h`'s
  `kThemes[]`, field for field — 暗夜蓝 midnight (default) / 石墨灰 graphite /
  紫罗兰 violet / 森林绿 emerald / 琥珀暖 amber / 海洋青 ocean / 珊瑚红 rose /
  明亮 light, plus the four gradient themes 翡翠绿 jade / 深空蓝 deepblue /
  暮光紫 twilight / 荣艳红 crimson.
- `QuireColors` is no longer two instances but *derived* from one catalog row
  (`colorsFor`), following the desktop's `ui/Colors.slint` derivation token for
  token so the two shells cannot drift.

### The window is a ramp

- The four gradient themes paint a vertical ramp behind everything (`pageBrush` in
  `QuireApp`), with the `Scaffold` and the page bodies transparent so it is one
  surface rather than one per pane; the nine flat themes set `grad2 == bg`, so one
  rule draws both kinds. The top bars keep `colors.background`, which is where the
  desktop's title bar sits too.

### Settings

- 外观 is a swatch grid instead of three text rows: each card paints its own ramp
  in its own ink, so the grid is a preview rather than a legend — AW's own Android
  picker construction.
- **跟随系统** survives, narrowed: it is no longer the default and no longer a
  palette of its own — one card that resolves to `midnight` or `light`.
- A library with no `theme` row now opens in `midnight` (the desktop's default)
  rather than following the system.

### Also

- The bridge's `set_theme` validates against the catalog instead of
  `light|dark|system`, and the pre-catalog spelling `dark` is rewritten to
  `midnight` rather than rejected — a library left dark by the previous build does
  not snap to the default. New test:
  `the_pre_catalog_theme_spelling_still_resolves`.

## Unreleased — 回收站

The gap this README has named since the organizer landed ("a real 回收站 needs soft
delete in `quire-core` — a column, the store, the merge"), done on all three repos
together (ADR-0019, core ADR-0003). The pinned rev moves, and **snapshot version
2 → 3**, so this shell and the desktop ship together.

### A delete is a stamp

- The 🗑 and the selection bar's 删除 now write `deletedAt` on the row instead of
  removing it, so a delete is reversible twice over: the 撤销 bar for three seconds,
  and the bin for as long as the user wants. Only 彻底删除 — and 清空回收站 — remove
  a row.
- The bin is a **mode of the page**, not a third destination: the overflow's
  回收站/离开回收站 flips it, the title says which one is showing, and what the bin
  lists is the half the user is standing on.

### The bin

- Each binned card's or row's ⋯ carries its own two verbs — **恢复** (back where it
  was) and **彻底删除** — and the overflow's 清空回收站 purges the whole half as
  **one** space on the undo stack.
- The **search box applies to the bin** and the smart views, the list chips and the
  tag row do not: all four are questions about a *list*. Picking any of them closes
  the bin, and 多选 leaves with it — a pick is a set of rows on a list.
- **`edited` does not move** when a row is binned; the content did not change.
- 指令's `delete` now means *bin* — the same verb the 🗑 is — and a new `restore`
  action is its mirror.

### Also

- `OrgModel` grew `binNotes` / `binTasks` / `binCounts`, and every list, board,
  footer, chip and tag projection moved to the live half of the catalog. A binned
  reply leaves its thread, and a binned task leaves its list's chip count — the
  count of a thing nobody can see is a count that disagrees with the list.
- `OrgModelTest` +3 (65 JVM tests), the bridge's `session_test` +1.
- **Core rev `4899857 → fbfdaca`** and **snapshot version 3**: a peer that could not
  see the tombstone would read a binned row as an ordinary remote edit and
  resurrect it, so an older build refuses the sync instead.

## Unreleased — the AI round trip and the filter's other half

Three things the desktop shell had and this one did not — all shell-side: no
`quire-core` change and no rev bump (ADR-0019).

### 反向筛选

- The tag filter has **two halves**: the include path (tapping a chip) and the hidden
  set (each chip's **⊖**). Hiding `项目` hides its subtree and leaves `项目2` alone —
  the same segment boundary, with the answer turned around.
- The filter bar spells the hidden paths out under the breadcrumb, and its ✕ clears
  **both** halves; 清除过滤 clears the needle with them.
- **任务 has a tag row now.** It was the notes' page only, and the tasks' half folds
  the *tasks'* own tags — so a row answering about notes while tasks are on screen can
  no longer happen.

### 复制 carries a 唯一 ID

- Every 复制 — the selection bar's, 全部's, and a single row's — writes each row with
  `ID: <uuid>` (a note: its body or title, then the id; a task: title, 备注, then the
  id), or `local:<id>` for a row an older peer sent without a uuid.
- **任务 gains the copy verb it never had**: 复制 in the selection bar, 复制全部 in the
  overflow, and 复制 in a row's ⋯.

### 指令

- The overflow's **指令…** opens a paste box: a `{"operations":[…]}` batch applied in
  order as **one** 撤销 step. 复制示例 puts the desktop's own editable template on the
  clipboard, so the batch's shape is discoverable without a manual.
- An action a half cannot take is refused by name and counted (`指令完成：成功 N / 失败
  M`), and the line is shown even when a second batch repeats the last — the notice bar
  is no longer write-once.

### Also

- `OrgModelTest` grew six tests: the note and task copy texts with their uuids, the
  tasks' own tag row, the exclude half and its `项目2` boundary, and the 排除 line.
- The dead `orgNoteTitle` / `orgNoteBody` / `orgNoteTags` view-model wrappers are gone;
  a note's write path is `orgNoteContent` — its text and tags in one step — which is
  what the UI has always used.

## 0.6.0 — 层级标签, 转为待办, 多选

The rest of what the reference app's 收件箱 and 任务 offer that this shell did not
(ADR-0018). All three are shell-side: no `quire-core` change and no rev bump, so
the library this reads is the same one the other two shells read.

### 标签 are paths

- A tag may be `项目/工作/ActivityWatch`. The filter is a **segment-boundary
  prefix** — `项目` keeps `项目` and `项目/工作` and drops `项目2`, which a plain
  prefix test would keep.
- The chips row shows **one level**: the direct children of the path being filtered,
  each carrying how many notes tapping it would leave on screen. `项目` → `工作` →
  `ActivityWatch` is three taps.
- A filter bar under the row carries the breadcrumb, **↑ 返回上级** one level, and
  **✕ 清除**. A tag's card and detail line read as `#项目 / 工作`.

### 转为待办

- A note's ⋯ turns it into a 收集箱 task and removes the note, with no confirmation
  dialog: the title is the note's own title or its first line with the markdown
  that opens it stripped (50 characters), the body travels whole as 备注, and the
  tags come along.
- The removal is the existing **deferred** delete, so the bar appears saying
  已转为待办 and one 撤销 puts the whole conversion back. The two writes are two
  steps on the organizer's stack, as the reference app's two requests also are.

### 多选

- A **long press** on a row starts 多选 with that row picked — a finger cannot
  hover, and a gesture nobody can discover must not be the only way — and the ⋯
  menu's 多选 starts it empty.
- The toolbar becomes the selection's own bar: 已选 N 项 · **全选** · the verb · ✕.
  收件箱's verb is 删除 (with 复制 above it in the bar); 任务's is 完成 and 删除.
- 全选 covers what the page is *showing*: the filter decides, and a row inside its
  撤销 window is not on screen and is not in it.
- The picked ids live in the view model, so a rotation keeps them and a filter that
  hides a picked row does not unpick it. The board is not selectable — it is a
  "what is left" view with no room for a selection bar.

### Also

- The merge's **conflicts** are no longer dropped on the floor: when both sides
  edited the same row and the local copy won, a `冲突：…` line naming the row goes
  into the 同步 page's 最近记录 — the reference app's 冲突 list, narrowed to the one
  surface this protocol has. A Rust test pins it.
- `OrgModelTest` grew seven tests: the subtree filter and its `项目2` boundary, the
  one-level chip row and its once-per-prefix count, the breadcrumb walk, and the
  title 转为待办 gives a note.

## 0.5.1 — the workshop deploy, and the local copy of a release

`just deploy-workshop` (ADR-0017): bump the patch, build the signed release APK,
commit and push the bump, then copy the APK to
`C:\workshop\quire-compose-<version>\quire-compose-<version>.apk` — the user's own
drop zone, which holds one folder per release of several applications
(`aura-1.2.6`, `aw-qtui-0.1.36`, `quire-desktop-0.1.4`).

- The **local** door a release leaves by, beside `just release-publish`, which is
  the door Obtainium installs from. Each advances the version, so they are two
  independent acts rather than two halves of one.
- The APK is verified **signed** (`apksigner verify --print-certs`) before it is
  copied. The keystore lives in the user's own directory, outside this repository,
  and without it the build yields an *unsigned* release APK — which installs
  nowhere, and the workshop is where one gets picked up from.
- The folder name carries the shell (`quire-compose-`, not a bare `quire-`): the
  drop zone holds several applications and this family has two Android shells in
  it. The file inside keeps the name the GitHub release uses, so the local copy and
  the published asset are the same name over the same bytes.

## 0.5.0 — 同步: the shell's half of the LAN protocol, and a page to drive it

`quire-core` has shipped the whole sync protocol since the beginning of this
project — the HTTP server, UDP discovery, the version-2 snapshot and the
three-way merge — and no shell here wired it. This slice wires it, and adds the
two halves the core leaves to a shell (ADR-0016).

### A 同步 destination

- A fourth drawer row, and a page of its own: a discovery banner, 本机 address and
  id, **已配对的设备** (在线/离线, 上次同步, 立即同步, 忘记), **已发现的设备**
  (发起配对), 按地址添加 for a network where the announcement cannot get through,
  the interval presets (10 秒 / 1 分 / 5 分 / 30 分 / 仅手动), 本机别名, and the last
  dozen log lines.
- **Opening the page is what puts this device on the LAN.** The engine — four
  threads and a listening socket — starts on the first request, so an app that
  never shows 同步 still listens on nothing.

### The two halves the core leaves out

- `rust/src/sync.rs`: the snapshot is read out of the workspace (flush, then the
  file), and a merged snapshot is written back — `replace_all` for the document
  half with `meta`/`settings` handed back untouched, and row-level `Change`s for
  the organizer, which is not part of a `PersistedState`. The session then
  rebuilds what it holds in memory and drops the undo stacks.
- The engine's jobs are answered on the existing one-second tick, so no second
  timer exists; a step that cannot be answered drops its reply channel, so a peer
  fails loudly rather than being handed an empty workspace.
- Identity, the peer book, the log and the per-peer shadows are the same
  `sync.*` `settings` rows the desktop and Slint shells already use, so a library
  carried between shells keeps its pairings.

### What it deliberately will not do

- **A library with a database or an attachment refuses to sync**, and says why on
  the page. This shell models neither, and a snapshot that dropped them would make
  a peer's merge read the absence as a deletion — so the engine is not started and
  an inbound snapshot carrying either is refused. Losing a user's databases to a
  phone is not a bug worth shipping.
- The read-only LAN **share** (port 5877) is still not wired, and neither are the
  reference app's pairing codes, conflict lists, per-device statistics or
  cloud/backup/WiFi-transfer siblings — `quire-core` has none of those.

### Also

- `SyncModelTest` pins the bridge's `sync` block key by key: it is a contract
  between two languages that no compiler checks. Real `org.json` is on the test
  classpath for it (the framework's is a stub under a JVM test).
- Two Rust tests: the gate, and an export → merge → apply → re-export round trip
  that asserts a peer's change lands, that a comment arrives carrying its ref, and
  that the `settings` rows survive the bulk replace.

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
