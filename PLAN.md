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

## Next (not started)

Slices in the order they are worth doing, each one a vertical cut the way M1 was:

- **M2 · The organizer** (SPEC §四十一): notes and tasks, with the core's own
  `ORGANIZER_STACK` undo. The Rust shell mirrored it from the desktop; this shell
  has the model in the core already.
- **M3 · Databases**: a table view first, then the other layouts. The largest
  single piece of the desktop shell, and the one that most needs the phone's own
  gestures rather than a port.
- **M4 · Search**: the page palette and the in-page find bar, over the core's
  FTS5 index.
- **M5 · Attachments**: import through the system picker (SAF), thumbnails, and
  image blocks that show a picture.
- **M6 · LAN share and sync**: `quire-core` ships both; neither is wired.

## Verification notes

- `cargo test` in `rust/` is the bridge's gate and runs in seconds.
- `:app:testDebugUnitTest` covers the mark conversion; everything else in the UI
  is verified by building and using it, which is the honest state of a shell with
  no headless renderer (the Rust shell's answer was 140 swept scenes; this one
  would need an instrumented test suite, and that is not this slice).
- The release APK is signed by the key `keystore.properties` names, which lives
  outside every repository (ADR-0009's sibling note in `app/build.gradle.kts`).
