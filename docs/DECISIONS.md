# Decisions

Architecture Decision Records for the Compose shell. Format: decision →
context → consequences. Newest first. Numbering is per repository, so these
numbers have nothing to do with the desktop shell's or the core's.

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
