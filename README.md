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
  src/test/             the unit tests that need no device
rust/                   the bridge crate: cdylib + rlib, JNI, `cargo test` on the host
  src/lib.rs            the four exported symbols
  src/session.rs        every operation the shell can ask for
  src/workspace.rs      the page tree (the core has no page commands)
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
```

Machine-local files, both gitignored: `local.properties` (the SDK path) and
`keystore.properties` (the signing identity — an identity, not a build artifact,
so it lives outside every repository).

## What works today

The first slice, end to end: **the page tree and the block editor.**

- Open/create a library; the page tree with nesting, favorites, recents and
  per-branch folding; create, rename, delete (subtree and all), favorite and
  lock a page; long-press a page row for its menu.
- The block editor: paragraphs, headings 1–3, bullets, numbered lists, to-dos,
  quotes, code blocks, callouts, toggles and dividers — editing text, Enter for
  the next block, the ⋮ handle for the block menu (kind, move, indent, delete),
  undo and redo, and inline marks (bold, italic, strike, code, links) painted
  from the core's own byte offsets.
- Light and dark themes in the shell's own palette, `跟随系统` by default, in
  Settings rather than on the main screen.
- Everything above is persisted through `quire-core`'s change stream, debounced
  the way the other shells debounce it.

## What does not work yet

Said plainly, because a shell that pretends is worse than one that is small:

- **Tables, columns, formulas, tables of contents, link cards, synced mirrors
  and databases** render as a labelled placeholder row that can be moved or
  deleted but not edited. The desktop's `DatabaseView` is the largest single
  component it has, and it has not been ported.
- **Images and files** show their attachment id, not the picture.
- **The notes & tasks organizer** (SPEC §四十一) is not here at all.
- **Search** (the palette or the find bar) is not here.
- **LAN share and sync** are not wired, though `quire-core` ships both.
- **Backspace on an empty block** does not merge it into the one above: a soft
  keyboard's backspace is not a key event a Compose text field can see. Deleting
  an empty block goes through the block menu.

## Development notes

- The bridge's protocol is testable without a device: `just bridge` drives the
  same `Session` the JVM drives, over the same JSON. The mark offsets, which are
  bytes in the database and UTF-16 indices in Compose, have their own unit test
  (`app/src/test/.../MarksTest.kt`).
- `just android-lib` builds only the `.so`; the Rust side's loop is `cargo test`
  in `rust/`.
- Version lives in one place, `gradle.properties`' `quire.version`;
  `versionCode` is derived from it so an update can never be refused for
  standing still.
