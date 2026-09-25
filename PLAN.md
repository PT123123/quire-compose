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

SPEC §四十一, and the slice PLAN named before M1 was even finished.

- [x] Two drawer rows (笔记 / 任务) into a second top-level area, with the document
      left exactly where it was (ADR-0013)
- [x] `rust/src/org.rs`: the area's writes on `core::ORGANIZER_STACK` — its own
      undo and redo, never the open page's
- [x] The reply carries the catalog; `ui/OrgModel.kt` derives the five smart views,
      the three sorts, the deadline labels, the chips and the board (ADR-0011)
- [x] 笔记: title, body, tags, pin, excerpt, age, tag column, and a ＋ that puts the
      caret in the new note's title
- [x] 任务: 收集箱 / 今天 / 近七天 / 全部 / 已完成, the three sorts, one needle per
      tab, the quick-add line, the footer's 显示已完成 switch and 已完成 X / Y
- [x] The detail form: 清单 / 优先级 / 截止 (with 今天 and 清除) / 重复 / 标签 /
      备注 / 子任务, and 删除
- [x] 平铺: one column per list, a long-press drag between columns, and the same
      move in the row's ⋯
- [x] Lists: create, rename, recolour, delete — the three writes the Slint shells
      declared and never wired, and the delete files its tasks in the inbox in one
      step
- [x] 14 more bridge tests and 22 JVM projection tests (`OrgModelTest`)
- [x] Verified on the device: the drawer, both tabs, the quick-add, the detail
      form, the board, a drag between columns, the row menu and the list dialog

## Next (not started)

Slices in the order they are worth doing, each one a vertical cut the way M1 and
M2 were:

- **M3 · Databases**: a table view first, then the other layouts. The largest
  single piece of the desktop shell, and the one that most needs the phone's own
  gestures rather than a port.
- **M4 · Search**: the page palette and the in-page find bar, over the core's
  FTS5 index.
- **M5 · Attachments**: import through the system picker (SAF), thumbnails, and
  image blocks that show a picture.
- **M6 · LAN share and sync**: `quire-core` ships both; neither is wired.
- **The organizer's tail**: rolling a completed repeating task forward (the core
  stores the rule and shows it; v1 does not fake the roll), and a note body that
  renders more than plain text if a second content format is ever wanted.

## Verification notes

- `cargo test` in `rust/` is the bridge's gate and runs in seconds.
- `:app:testDebugUnitTest` covers the two things in Kotlin that can be wrong
  without looking wrong: the byte/UTF-16 mark conversion (`MarksTest`) and the
  organizer's projection rules (`OrgModelTest`). Everything else in the UI is
  verified by building, installing and using it on the device — the honest state
  of a shell with no headless renderer (the Rust shell's answer was 140 swept
  scenes; this one would need an instrumented test suite, and that is not these
  slices).
- The release APK is signed by the key `keystore.properties` names, which lives
  outside every repository (ADR-0009's sibling note in `app/build.gradle.kts`).
