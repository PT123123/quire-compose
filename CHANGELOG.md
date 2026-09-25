# Changelog

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
