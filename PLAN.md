# PLAN — living status tracker (update after every slice)

## M0 · The repository and the bridge — ✅

- [x] New repository, `dev.quire.compose` as the application id so both Android
      shells install side by side (ADR-0005)
- [x] `quire-core` pinned as a git dependency at the rev both existing shells
      carry, with `net.git-fetch-with-cli` (cargo's own libgit2 401s on that repo)
- [x] `rust/quire-bridge`: a cdylib + rlib exporting four JNI symbols, JSON in and
      JSON out, every export panic-guarded (ADR-0001, docs/ARCHITECTURE.md)
- [x] The bridge's own workspace: the page tree, because the core has no page
      commands (ADR-0002)
- [x] 19 host-side tests driving the same `Session` the JVM drives

## M1 · The Compose app — ✅

- [x] Gradle 8.9 / AGP 8.5.2 / Kotlin 1.9.22 / Compose BOM 2024.06.00, the
      versions the other Android project on this machine already builds with
- [x] `cargo ndk` → `jniLibs` wiring, degrading to "no library" rather than a
      broken build when Rust is absent
- [x] The palette and type scale ported from the Slint shell's tokens, light and
      dark, with the block swatch table (ADR-0006)
- [x] The app shell: drawer, top bar with undo/redo, the editor, the sheets
- [x] The page tree: nesting, folding, favorites, recents, page menu, rename and
      delete dialogs
- [x] The block editor: twelve kinds, the ⋮ handle on selection, the block menu,
      inline marks through the byte→UTF-16 conversion (ADR-0008)
- [x] Block colours carried through from the shared model
- [x] Settings: theme, the current page's read-only switch, about
- [x] The keystroke rule: text edits answer without echoing the page (ADR-0003)
- [x] `MarksTest` — the conversion that can be wrong without looking wrong
- [x] Build gates: `just bridge`, `just check`, `just android-apk`
- [x] Release automation: one version property, patch bumped on publish, APK
      attached to a GitHub release for Obtainium (ADR-0009)

## M2 · The organizer — ✅

SPEC §四十一, and the slice PLAN named before M1 was even finished. Rebuilt once
(0.3.0) after the first cut turned out to be the Slint shell's shape rather than
the reference app's.

- [x] Two drawer rows into **two separate pages** — 收件箱 and 任务, each with its
      own toolbar and state, with the document left exactly where it was
      (ADR-0013)
- [x] `rust/src/org.rs`: the area's writes on `core::ORGANIZER_STACK` — its own
      undo and redo, never the open page's
- [x] The reply carries the catalog; `ui/OrgModel.kt` derives the five smart views,
      the five task sorts, the three note sorts, the deadline labels, the chips and
      the board (ADR-0011)
- [x] 收件箱: **note cards** — the text, its tags on an accent line, its age, a ⚑ and
      a ⋯ — a tag chip row, a hideable search field, and 排序（最新创建 / 最新更新 /
      按内容）
- [x] **Capture floats**: the round ＋ opens a bottom sheet with a multi-line field,
      a markdown toolbar (`#`, `B`, `/`, `•`, `1.`), a ➤ send, tag suggestions, and
      a draft that survives a swipe-away (ADR-0014)
- [x] A note is one command carrying its text and its `#tokens` as tags; a task is
      one carrying its title, its tags and (in 今天) its deadline
- [x] 任务: 收集箱 / 今天 / 近七天 / 全部 / 已完成, five sorts behind ⇅, one needle per
      page, glass rows with the list dot / priority / deadline / checklist / tags,
      and a 4 dp progress bar with 已完成 X / Y
- [x] The task detail: 清单 / 优先级 / 截止 (with 今天 and 清除) / 重复 / 标签 / 备注 /
      子任务, and 删除
- [x] 平铺: one column per list, a long-press drag between columns, and the same
      move in the row's ⋯
- [x] Lists: create, rename, recolour, delete — the three writes the Slint shells
      declared and never wired
- [x] 37 bridge tests and 42 JVM tests (`OrgModelTest`, `MarkdownTextTest`)
- [x] Verified on the device: both pages, the toolbars, the capture sheet and its
      toolbar, a note sent with a `#tag` and the card it made, the task chips and
      footer. The final pass (the tasks bar and footer fixes) was cut short by the
      device's lock screen.

## M2.5 · The activitywatch alignment pass — ✅

The four things the reference app did and this shell did not, plus 引用. Core and
shell together: `quire-core` gained a field first and was pushed, and this shell
pins the rev that carries it (ADR-0015, core ADR-0001).

- [x] 收件箱 is **home**: the app opens there, 任务 and 文档 both return there, and
      the drawer's page tree is the way *into* the document (it closes the drawer
      and switches destination now, which it never did)
- [x] Opening the drawer **drops the page's focus and the IME**, in `Shell`, so it
      covers every destination and stays true for pages added later
- [x] Capture is a **non-animated overlay** (`ui/CaptureOverlay.kt`) instead of a
      `ModalBottomSheet`: the spring was the wait before the keyboard
- [x] Delete is **deferred behind a 撤销 bar** — notes and tasks, no confirm dialog,
      one bar at a time, owned by the view model so it survives leaving the page
- [x] A note opens on **its own page**, with 详细信息 and a 评论 section
- [x] **评论 / 引用**: a comment is a note with `ref_note`; the card draws `↩`, the
      parent lists its replies, tapping the preview jumps to the parent
- [x] `quire-core`: `Note.ref_note`, migration 27, the store's read/write, the wire
      row, and a `note_map` in the merge's renumber pass — with two merge tests and
      a migration test
- [x] Not done, and named: a persistent 回收站, 笔记历史 / 恢复版本, and a note body
      that renders its markdown. (转为待办 and 多选 were on this list and are now in
      M2.9.)

## M2.9 · 层级标签, 转为待办, 多选 — ✅

The rest of the reference app's 收件箱 and 任务 screens, all of it shell-side: no
`quire-core` change and no rev bump (ADR-0018).

- [x] A tag is a **path**: the filter is a segment-boundary prefix (`项目` keeps
      `项目/工作`, drops `项目2`), the chips row shows one level with subtree counts
      counted once per note per prefix, and a filter bar carries the breadcrumb,
      ↑ 返回上级 and ✕ 清除
- [x] 转为待办: a note's ⋯ turns it into a 收集箱 task (title rule, body → 备注, tags
      carried) and removes the note through the **deferred** delete, whose bar says
      已转为待办 — one 撤销 puts the whole conversion back
- [x] 多选: a mode keyed by ids in the view model, entered by a long press or the ⋯
      menu, with 全选 over what the filter shows; 收件箱's verbs are 复制 / 删除 and
      任务's are 完成 / 删除, and back leaves the mode before it leaves the page
- [x] `OrgModelTest` +7: the subtree boundary, the one-level row and its count, the
      breadcrumb walk, and the title 转为待办 gives a note

## M2.75 · 同步 — ✅ (the read-only share still open)

LAN sync over `quire-core`'s own protocol, which it has shipped unwired since M0.
`PLAN`'s M6 named both halves; this is the sync half, and the share half is left
where it was (ADR-0016).

- [x] A fourth destination, `ui/Sync.kt`: the discovery banner, 本机 address and
      id, 已配对 / 已发现 device lists with 在线·离线 and 上次同步, 立即同步 /
      忘记 / 发起配对, 按地址添加, the interval presets, 本机别名, the log tail
- [x] `rust/src/sync.rs`: the two halves the core leaves to a shell — the snapshot
      out of the workspace, and a merged snapshot back in (`replace_all` for the
      document, row `Change`s for the organizer, then reload in memory)
- [x] The peer book, this device's identity, the log and the per-peer shadows are
      the desktop shell's `sync.*` `settings` keys, so a carried library keeps them
- [x] **The gate**: a library with a database or an attachment refuses to sync, and
      the engine is never started — a partial snapshot would make a peer's merge
      read the absence as a deletion
- [x] The engine's jobs ride the existing one-second tick; Identity answered on the
      first `syncState`, so nothing listens until the page is opened
- [x] Two Rust tests (the gate; export → merge → apply → re-export) and
      `SyncModelTest` pinning the wire keys
- [x] The merge's **conflicts** reach the page: a conflict the merge settled in this
      device's favour writes a `冲突：…` line into 最近记录, so a row the two devices
      disagree about is explainable (ADR-0018's tail)
- [ ] The read-only LAN share (port 5877), a 冲突 list of its own, per-device
      statistics, pairing codes, and the reference app's cloud / backup /
      WiFi-transfer pages

## M2.95 · 反向筛选, 复制 with a 唯一 ID, 指令 — ✅

The three the desktop shell had and this one did not, all shell-side: no
`quire-core` change and no rev bump (ADR-0019).

- [x] 反向筛选: `orgExcluded` in the view model, a **⊖** on every tag chip, the hidden
      paths spelled out under the breadcrumb, and ✕ clearing both halves
- [x] A tag row on **both** tabs: `OrgModel.tagChips` takes an `isTask` flag, so the
      任务 half folds the tasks' own tags instead of the notes'
- [x] 复制 carries a **唯一 ID**: `noteCopyText` / `taskCopyText` shape the rows the
      desktop's do, and the tasks gain 复制 / 复制全部 / a row's 复制
- [x] 指令: a paste dialog behind each overflow, applying `{"operations":[…]}` in
      order as one 撤销 step, with the desktop's template behind 复制示例
- [x] The notice bar adopts a line on *change* rather than once, so 指令's count can
      never be swallowed by a startup notice
- [x] `OrgModelTest` +6 (59 JVM tests total), and the dead `orgNote*` wrappers removed

## M2.97 · 回收站 — ✅

The gap this repository's README has carried since M2 ("a real 回收站 needs soft
delete in `quire-core` — a column, the store, the merge"), done on all three repos
together (ADR-0020, core ADR-0003). Core lands first and this shell pins the rev.

- [x] A delete is a **stamp**: the 🗑 and the selection bar's 删除 write `deletedAt`
      (the bridge's `orgDeleteNote` / `orgDeleteTask` now route to `trash_note` /
      `trash_task`), so a delete is reversible twice over — the 撤销 bar, and the bin
- [x] 回收站 as a **mode of the page**: the overflow's 回收站/离开回收站 flips
      `orgBin`, the title says which one is showing, and each binned card's ⋯ carries
      **恢复** and **彻底删除**; the overflow's 清空回收站 purges the whole half as
      **one** space on the undo stack
- [x] Every list-shaped projection moved to the live half of the catalog, and
      `OrgModel` grew `binNotes` / `binTasks` / `binCounts`; a binned reply leaves its
      thread and a binned task leaves its list's chip count
- [x] The search box applies to the bin and the smart views / list chips / tag row do
      not (picking one closes it); 多选 leaves with it
- [x] 指令's `delete` means *bin* and a new `restore` action is its mirror
- [x] `OrgModelTest` +3 (65 JVM tests), the bridge's `session_test` +1, and the rev
      `4899857 → fbfdaca` with **snapshot version 2 → 3**

## M2.98 · AW's theme catalog — ✅

Twelve palettes instead of two (`ADR-0021`). `ui/Theme.kt` gains `ThemePalette` +
`ThemeCatalog` — `aw-qtui/src/theme.h`'s `kThemes[]`, field for field, the same
twelve the desktop shell ports into `ui/Colors.slint` — and `QuireColors` becomes
*derived* from one row (`colorsFor`), following the desktop's derivation token for
token so the two shells cannot drift. Four rows carry a page ramp; the other nine
set `grad2 == bg`, so `pageBrush`'s single `Brush.verticalGradient` draws both
kinds. The window is painted on it (`QuireApp`'s `Box`, `Scaffold` transparent) and
the top bars keep `colors.background`, which is where the desktop puts it too.

Settings' 外观 stops being three text rows (跟随系统/浅色/深色) and becomes a swatch
grid: `ThemeSwatch` paints each palette's own ramp in its own ink, so the grid is a
preview rather than a legend — AW's own Android picker construction.

The stored value is an **id both shells know**, because they read the same `theme`
row out of the same `quire.db`: the bridge's `set_theme` validates against the
catalog plus `system` instead of `light|dark|system`, and `dark` — what the
previous shell wrote — is rewritten to `midnight` rather than rejected. A library
with no `theme` row now opens in `midnight` (the desktop's default) rather than
following the system; 跟随系统 is still there, one card over, and resolves to
`midnight`/`light`.

**Verified**: `cargo test --manifest-path rust/Cargo.toml` 44 green (18 bridge + 26
session, incl. the new `the_pre_catalog_theme_spelling_still_resolves`), and
`just check` green end to end — `:app:testDebugUnitTest :app:assembleDebug`.

**Not verified (honest)**: the grid on a real device (no emulator here — the same
gap every UI slice in this shell carries); and the twelve palettes' callout /
mention tints are legible by construction (`lerp` against the theme's own `bg`),
not by measurement — this shell has no contrast probe.

## Next (not started)

Slices in the order they are worth doing, each one a vertical cut the way M1 and
M2 were:

- **M3 · Databases**: a table view first, then the other layouts. The largest
  single piece of the desktop shell, and the one that most needs the phone's own
  gestures rather than a port.
- **M4 · Search**: the page palette and the in-page find bar, over the core's
  FTS5 index. (The organizer's own two needles are not that search.)
- **M5 · Attachments**: import through the system picker (SAF), thumbnails, and
  image blocks that show a picture.
- **M6 · LAN share**: the read-only Markdown share on 5877 — the half of the LAN
  work `M2.75` did not take. **Sync** itself is done (`quire-core`'s protocol,
  wired in `rust/src/sync.rs` with the 同步 page on top).
- **The organizer's tail**: rolling a completed repeating task forward, a note body
  that renders more than plain text, and a board that respects the tag filter
  (多层标签 and 多选 are done — `M2.9` / `M2.95`).

## Verification notes

- `cargo test` in `rust/` is the bridge's gate and runs in seconds.
- `:app:testDebugUnitTest` covers the three things in Kotlin that can be wrong
  without looking wrong: the byte/UTF-16 mark conversion (`MarksTest`), the
  organizer's projection rules (`OrgModelTest`), and the compose toolbar's text
  operations (`MarkdownTextTest`). Everything else in the UI is verified by
  building, installing and using it on the device — the honest state of a shell
  with no headless renderer (the Rust shell's answer was 140 swept scenes; this one
  would need an instrumented test suite, and that is not these slices).
- The release APK is signed by the key `keystore.properties` names, which lives
  outside every repository (ADR-0009's sibling note in `app/build.gradle.kts`).
