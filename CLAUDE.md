# CLAUDE.md

Working notes for an agent in this repo. This is the single source of truth for
design intent *and* mechanics — it absorbed the old `HANDOVER.md`, which has been
deleted. If you learn something not written here, add it.

`README.md` is **not trusted**. It predates the current structure and is to be
rewritten from scratch once the app exists. Do not cite it, do not patch it.

---

## What this app is

An **Android-only** app for support agents. Two halves:

1. **Chat** — a bot asks a question, the user taps an answer button, the bot
   walks to the next question. Repeat until an endpoint is reached.
2. **Editor** — a zoomable canvas where the user builds the question graph by
   hand.

**This is not an LLM app.** No model, no inference, no API call, no network
dependency of any kind at runtime. The bot is a graph traversal. If you find
yourself reaching for an AI SDK, you have misunderstood the project.

---

## Locked decisions

Argued through and settled. Do not revisit without being asked.

| Decision | Value |
|---|---|
| Platform | Android only, forever. No iOS, no desktop, no KMP. |
| Language / UI | Kotlin + Jetpack Compose, Material 3 |
| Application id | `com.knasiotis.decisionwizard` |
| SDK levels | `minSdk 31`, `targetSdk 36`, `compileSdk 37` |
| Persistence | Room for graph library + chat sessions; graph body stored as a JSON blob column |
| Serialization | `kotlinx.serialization` |
| Node placement | **Auto-layout.** The user pans and zooms. Nodes are never dragged. |
| Coordinates | **Never persisted.** Layout is derived from the graph every time. |
| Long edges | Not drawn. Rendered as reciprocal stub chips. |
| Graph shape | Cycles, reconvergence and dangling branches are all legal. |
| Undo | Snapshot-based, in memory only, not persisted across restarts. |
| Export format | `.dwiz` — plain JSON in v0.2, zip from v0.4, told apart by magic bytes |
| Distribution | GitHub Releases, signed APK, consumed via Obtainium |

### Constraints that are easy to violate by accident

- **Do not add `x` / `y` to the schema.** The file must stay hand-editable and
  portable between two people's installs.
- **Do not add a node `type` or `isTerminal` field.** A node with an empty
  `answers` list *is* an endpoint.
- **Do not block the user from building a weird graph.** Cycles, orphans and
  dangling answers produce warnings on the canvas, never dialogs or refusals.
  Cycle detection exists solely so the depth pass terminates.
- **Do not fake a typing indicator in chat.** Traversal is instant. Do not add
  artificial latency to imitate an LLM.
- **Do not paint node bubbles on a Canvas.** Bubbles are real composables inside
  a custom `Layout`. Only edges are painted, via `drawBehind` on the container.
- **Do not add an Android dependency to `:graphcore`.** See module layout below.

---

## Data model

Kotlin models: `graphcore/.../model/Models.kt`.
Worked example: `samples/graph-schema-example.json`.

```
Graph  { schemaVersion, graphId, name, description, revision, updatedAt,
         rootNodeId, nodes[] }
Node   { id, title, body, snippets[], attachments[], answers[] }
Answer { id, label, targetNodeId? }
```

Points that matter:

- `title` shows on the collapsed bubble; `body` shows when the bubble is opened.
- `answers` is a **list**, not a `yes`/`no` pair. New nodes are seeded with
  `[Yes, No]`, but both are removable and more can be added.
- `targetNodeId: null` means the branch exists with no child yet. Legal state,
  renders as a stub arrow going nowhere.
- `Answer.id` exists so the transcript can record *which edge* was taken. Two
  answers on one node may point at the same child, so the node pair alone is
  ambiguous.
- Nodes are a **list**, not a map. Build `Map<String, Node>` in memory on load
  (`Graph.byId`), never persist it.
- `revision` bumps on every save. On import: same `graphId` + higher `revision`
  → offer to update; otherwise → offer to duplicate. That is the whole sync
  story between two users.

---

## Layout and edge rendering

Implementation: `graphcore/.../layout/GraphLayout.kt` (`object LayoutEngine`).

1. DFS from root, iteratively, marking back-edges. Iterative because cycles are
   legal and recursion would blow the stack.
2. Longest-path depth assignment, excluding back-edges.
3. Two barycenter passes to reduce crossings. Heuristic, not optimal — do not
   spend effort perfecting this until a real 100-node graph proves it matters.
4. Unreachable nodes get parked in a band below the last layer.

Edge classification:

- Forward hop spanning 1..`MAX_DRAWN_SPAN` layers → **`ARROW`**, drawn.
- Anything else (back-edge, cross-edge, long jump) → **`STUB`**, not drawn.
- No target → **`DANGLING`**, short arrow to nowhere.

A `STUB` emits **two** chips, and both are required:

- On the source: `Yes ↩ Restart router`
- On the target: `↪ from: Check cable`

The inbound chip is not decoration. Without it a user can land on a node with no
idea anything points at it, and will restructure or delete it wrongly.

Tapping either chip animates the viewport to the other end and flashes the
target node for ~500ms. Keep a small viewport back-stack so a second tap returns
the user to where they were.

---

## Editor interactions

Each bubble shows its title. Tapping it selects it and opens a bottom sheet with
the actions. Actions are in a sheet rather than mini-buttons pinned to the
bubble, because pinned buttons become unhittable when zoomed out.

- **Edit** — opens the node contents.
- **Add child** — shows the parent's answer labels that have **no target yet**,
  plus "+ new answer". The condition is not free text; it comes from the
  parent's answer list.
- **Connect to existing** — same label picker, then a searchable node list.
- **Delete** — see below.

### Delete

Implementation: `graphcore/.../editor/DeleteOps.kt`. Call `DeleteOps.preview()`
first to decide which options the sheet offers and what counts to display.

| Option | Behaviour |
|---|---|
| Splice | Only when the node has exactly one child. Inbound edges repoint to that child. **Surface this first when available** — it is the common case. |
| Delete only | Inbound answers dangle, children stay, orphans grey out. |
| Delete and purge | Node plus everything that becomes unreachable. Preview the count first. |
| Delete and reparent | Children move to a chosen node, carrying labels as new answers. |

"Remove all children" does *not* mean deleting the subtree — in a DAG a child
may be reachable from elsewhere. **Orphans are computed by re-running
reachability from the root, never by walking descendants.**

Reparenting skips only an *exact* duplicate branch — same label *and* same
target. Two differently-labelled routes to the same node are two real routes;
the label is what the agent reads.

Prefer **undo** over confirmation dialogs. Snackbar with UNDO, not a modal.

---

## Undo / redo

Implementation: `graphcore/.../editor/UndoStack.kt`.

Snapshots hold whole `Graph` objects. Because the models are immutable data
classes, an edited graph shares every unchanged `Node` by reference — a snapshot
is a list of pointers, not a copy. Do not build a diff or command system.

Coalescing rules, which decide whether this feels good or awful:

- Structural edits → `applyStructural()`, one undo step immediately.
- Text editing → `stageDraft()` per keystroke, `commitDraft()` once when the
  sheet closes. The whole typing session collapses to one step.
- Pan and zoom → never committed. Camera position is not a document change.

Each snapshot carries a `description` (for the snackbar) and a `focusNodeId`.
After an undo, pan to `focusNodeId` and flash it — otherwise an off-screen undo
looks like a broken button.

---

## Screens

1. **Chats** — session list, resumable. FAB → graph picker → new chat.
2. **Chat** — `LazyColumn`. Answer options as wrapping `FilterChip`s so 2 and 5
   options both look right. Past answers stay tappable; tapping one **rewinds the
   session** to that question *and takes the tapped branch*, so correcting a wrong
   turn is one gesture. Copy button on snippet bubbles for the whole snippet, plus
   `SelectionContainer` on question and snippet text for partial selection.
   Keep `SelectionContainer` around text only — wrapping the chips would make
   long-press selection fight with tapping them.
3. **Graphs** — library. Create / duplicate / import / export / delete.
4. **Node list** — searchable, per graph, with validation badges. This is a
   maintenance view, not the primary authoring surface.
5. **Node detail** — title, body, snippets, attachments, answer rows. Show
   inbound edges ("reached from: 3 nodes"), tappable.

Bottom navigation: Chats / Graphs.

---

## Import / export

The extension is **`.dwiz`**. It is custom rather than `.json` for one reason:
tap-to-open. Android cannot route a `.json` file to a specific app because too
many apps claim that type. The extension is about association, not content.

**v0.2 writes plain JSON inside `.dwiz`. No zip.** There are no attachments until
v0.4, so a zip would be wrapping a single file for two milestones while breaking
the hand-editable constraint — you cannot open a zip in a text editor, paste it
into a chat, or diff it.

**v0.4 switches the same extension to a zip**, once attachments exist:

```
internet-down.dwiz
├── graph.json
└── assets/
    └── router-leds.jpg
```

The importer distinguishes them by **magic bytes**: a zip always starts with
`PK\x03\x04`, anything else is parsed as bare JSON. One extension covers both
forever, every v0.2 file keeps importing, and there is no migration to write.
**Do not introduce a second extension for the zip.**

Attachments reference relative paths. Images are copied into app storage on
import, never base64-inlined into the JSON.

Use SAF (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`). The intent filter
needs the pattern repeated, because Android's `pathPattern` matcher stops
matching once the filename contains extra dots — and a graph called
`Router v1.2` is enough to trigger it:

```xml
<data android:pathPattern=".*\\.dwiz" />
<data android:pathPattern=".*\\..*\\.dwiz" />
<data android:pathPattern=".*\\..*\\..*\\.dwiz" />
```

---

## Validation

Implementation: `graphcore/.../model/GraphValidator.kt`.

`WARNING` issues render as badges on the canvas and in the node list. They never
block anything. The only `FATAL` codes are duplicate node ids and duplicate
answer ids, which make a file impossible to load unambiguously and so reject the
import.

---

## Repo layout

```
settings.gradle.kts          :graphcore only so far — :app not created yet
gradle/libs.versions.toml    all versions live here, no inline version strings
graphcore/                   pure-JVM module, com.knasiotis.decisionwizard.*
  src/main/kotlin/.../model/    Models.kt, GraphValidator.kt
  src/main/kotlin/.../layout/   GraphLayout.kt
  src/main/kotlin/.../editor/   UndoStack.kt, DeleteOps.kt
  src/main/kotlin/.../chat/     ChatEngine.kt
  src/test/kotlin/.../          Fixtures.kt + 4 test classes, 39 tests
app/                         Android module, Compose UI only
  src/main/kotlin/.../          MainActivity.kt, ui/Theme.kt, ui/ChatScreen.kt
samples/graph-schema-example.json
```

**Traversal lives in `:graphcore`, not in the UI.** `ChatEngine` is pure logic
and fully unit-tested; `ChatScreen` only renders it. Session state
(`ChatState`) holds no graph reference, so it serialises on its own — that is
what makes rotation survival and, later, Room-backed resumable sessions cheap.
Keep new logic on that side of the line.

`samples/` holds the **one canonical copy** of the sample graph. `:graphcore`
picks it up as a test resource via a `srcDir` in its build file; `:app` should
add the same directory to its assets rather than copying the file.

### Why `:graphcore` is a separate module

The core must stay Android-free so it is unit-testable on the JVM. As a separate
pure-Kotlin module that is enforced by the compiler instead of by discipline,
and the tests run in seconds with no emulator or Robolectric. If something needs
the framework, it belongs in `:app`.

---

## Commands

`JAVA_HOME` on this machine points at JDK 24, which AGP does not support. Set it
per-invocation:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

./gradlew :graphcore:test          # 25 tests, the fast inner loop
./gradlew :graphcore:compileKotlin
```

Installed JDKs are 8, 11, 21, 24 — **there is no JDK 17**, despite CI being
planned around temurin 17. Use 21 locally; CI installs its own.

## Toolchain state

- **The Android SDK is installed** at `~/android-sdk` (~650 MB), which is where
  `ANDROID_HOME` already pointed. Platforms `android-37.0` and `android-37.1`,
  `build-tools;37.0.0`, `platform-tools` (so `adb` is available).
- **`:app` builds locally**: `./gradlew :app:assembleDebug` — around 45s cold,
  under a second incremental. Build everything locally before pushing; CI is a
  backstop, not the first check.
- Ignore `/usr/lib/android-sdk` — it holds only `build-tools/34.0.0` and a
  license file, and is not what `ANDROID_HOME` refers to.
- Gradle on `PATH` is **4.4.1** and useless. Always use `./gradlew`.
- The wrapper jar is committed, fetched from the `v9.7.1` tag of gradle/gradle.

Versions pinned in `gradle/libs.versions.toml`: Gradle 9.7.1, AGP 9.3.1,
Kotlin 2.4.10, kotlinx-serialization 1.11.0, JUnit 6.1.3.

---

## CI

Two workflows in `.github/workflows/`.

| Workflow | Trigger | Does |
|---|---|---|
| `test.yml` | push to main, PRs (docs ignored) | `:graphcore:test` **and** `:app:assembleDebug`, uploading the debug APK |
| `release.yml` | tag `v*` only | signed APK, attached to a GitHub Release |

**The repo is public, so Actions minutes are free and unmetered.** The lean setup
below is for speed and for safety if the repo ever goes private, not for cost.
If it does go private, the rules that matter are: `ubuntu-latest` only (macOS is
10x, Windows 2x), tag-only release builds, and `cancel-in-progress` on the test
workflow.

Do not add `--no-daemon` to CI Gradle calls — it defeats `setup-gradle`'s
caching. (Locally it is fine and used.)

### The release contract

Implemented in `app/build.gradle.kts`. `release.yml` supplies `-PversionName`
and `-PversionCode` plus four keystore environment variables; the build reads
them all through `providers.*` rather than `System.getenv` / `findProperty`,
because the root `gradle.properties` enables the configuration cache and direct
reads at configuration time invalidate it.

A local release build with no `KEYSTORE_PATH` is simply unsigned rather than a
configuration error.

Keep the release asset named `decision-wizard-<tag>.apk`. Obtainium matches on a
stable asset name; changing it breaks update detection on installed devices.

### Keystore — done and proven

The four secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`) are set, and a `workflow_dispatch` rehearsal on 2026-08-23
produced a verified signed APK with no tag and no release.

Key: RSA 4096, alias `decision-wizard`, valid to Jan 2054.
Fingerprint `SHA256: 03:1C:7B:71:87:0A:E3:D0:B7:23:74:74:53:F9:0D:54:B8:7E:22:CD:41:73:13:F6:45:9D:0D:91:80:D7:BF:8D`.
**Every future release must show this fingerprint.** A different one means the
wrong key is in the secrets, and anything signed with it cannot update an
installed app.

The keystore lives outside the repo in `~/decision-wizard-signing/` and is backed
up. It is unrecoverable: lose it and no future build can update an installed
app, because Android refuses an update signed by a different key. The only
remedy is uninstall and reinstall, which destroys the user's graphs and history.

Re-run the rehearsal any time the secrets change, rather than finding out on a
tag.

---

## Gotchas learned the hard way

**Serialization defaults.** `GraphJson` sets `encodeDefaults = false` to keep
exports small and hand-editable. That silently drops any field equal to its
default — which killed `schemaVersion` and `revision`, the two fields the entire
import/update story keys off. Both now carry
`@EncodeDefault(EncodeDefault.Mode.ALWAYS)`. **Any new field that must always
appear in the file needs the same annotation.**

**Which edge becomes a stub is traversal-order dependent.** A cycle has to be
broken somewhere, but *which* edge the DFS marks as a back-edge depends on visit
order. In the sample, the cycle `n-cable → n-restart → n-recheck → n-cable`
breaks at `e-9`, not at `e-14` as you might expect. Not a bug, but it means an
edit elsewhere in the graph can make a stub chip jump to the other side of a
cycle. Never write a test that hardcodes which edge is the stub — assert that
the cycle is broken *somewhere* and that stubs are reciprocal.

**AGP 9 provides Kotlin itself.** Applying `org.jetbrains.kotlin.android` in
`:app` is a **hard error**, not a warning — there is deliberately no
`kotlin-android` alias in the version catalog. `:graphcore` is a plain JVM module
and still uses `kotlin-jvm` normally. The Compose compiler plugin applies on top
of AGP's built-in Kotlin without issue.

**KSP is versioned independently of Kotlin** since KSP 2.3.0. Older guidance says
to match `<kotlin>-<ksp>` (e.g. `2.2.21-2.0.5`); that scheme is gone, and there is
no `2.4.10-*` build to hunt for. KSP and the Room Gradle plugin both apply
cleanly on top of AGP 9's built-in Kotlin.

**Room schemas are committed** under `app/schemas/` and the database
deliberately has **no `fallbackToDestructiveMigration`**, so a missing migration
fails loudly rather than silently wiping the user's own hand-authored graphs.

**But do not invest in a v0.2 → v0.3 migration.** Nobody will be running v0.2 in
anger before v0.3 lands, so if the schema changes, bump the version and reinstall
rather than writing migration code for data that does not exist. The strictness
above is for v0.3 onwards, once there is real work in the database.

**SDK platform packages now carry a minor version.** `platforms;android-37` does
not exist — the packages are `platforms;android-37.0` and `platforms;android-37.1`.
`sdkmanager` reports a bare `android-37` as "Failed to find package", which reads
like the platform is unavailable when it is only named differently. This is also
why the AAR metadata error suggested "at least 37, for example 37.1".

**`compileSdk` is dictated by the dependencies, not chosen.** AndroidX artifacts
declare a minimum consumer `compileSdk` in their AAR metadata, and the build
fails at `checkDebugAarMetadata` — before any Kotlin is compiled — if it is too
low. Compose 1.12, core-ktx 1.19 and lifecycle 2.11 all demand 37. When bumping
any AndroidX version, expect to bump `compileSdk` with it. It is safe to do so:
`compileSdk` only governs which APIs can be *called*, independently of
`targetSdk`, which is what opts the app into new runtime behaviour.

**Android sourceset `srcDir` is deprecated in AGP 9.** Use
`assets.directories.add(...)`, and note it takes a **String path, not a File**.

**`nodeHeightOf` must only ever receive real node ids.** It is backed by measured
composables in the editor, so a synthetic id would throw or silently return a
wrong height. `LayoutEngineTest.strictHeights` throws on an unknown id
specifically to catch this.

---

## Testing

`Fixtures.kt` loads `samples/graph-schema-example.json` and offers small builders
(`graph`, `node`, `answer`) so structural tests don't depend on the sample's
shape. The sample **already contains a deliberate cycle**
(`n-cable → n-restart → n-recheck → n-cable`) — no separate cycle fixture needed.

Prefer asserting invariants over exact outputs. The layout is a heuristic; tests
that pin down barycenter ordering will break for no good reason.

---

## Build order

**Versions name end-user functionality.** A milestone that does not change what
the other person can do does not get a tag. Infrastructure working is not a
feature, so the release pipeline is proven with the `workflow_dispatch` signing
rehearsal instead of by burning a version number.

- **v0.1 — internal, never tagged. Done.** Bundled sample graph, chat and
  traversal only. Following one hard-coded flow is not worth releasing. Its
  purpose was to get Gradle, CI and the app skeleton working while the app was
  too small to hide problems. Lives on `main`; try it via the debug APK that
  `test.yml` attaches to every run.
- **v0.2.0 — the first actual release.** Room, the graph library, and `.dwiz`
  import/export. This is the first build worth handing to someone else: they can
  hold several flows, import one you sent them, and send one back. Authoring is
  by hand-edited JSON until v0.3, which is exactly why the format stays
  hand-editable.
- **v0.3.0** — the canvas editor: layout, stub chips, undo/redo, delete ops.
  Authoring moves onto the device.
- **v0.4.0** — attachments and transcript export.

## v0.2 plan

The first tagged release. Someone can hold several flows, import one you sent,
chat over any of them, and resume where they left off. Authoring stays
hand-edited JSON until v0.3 — which is why the format must stay hand-editable.

v0.1 has effectively no architecture: one activity, one composable, state in
`rememberSaveable`. v0.2 adds persistence, navigation, background work and a
settings store. **That jump is the real risk, not the features.**

### Decisions

| Question | Decision |
|---|---|
| Bundled example graph | **Dropped.** A fresh install has an empty library; the empty state carries first-run. Keep `samples/` as the `:graphcore` test fixture — only remove it from `:app` assets. |
| Chat sessions persisted? | **Yes**, in Room. |
| App launch behaviour | User preference: **resume last-opened session**, or **start a new chat**. Falls back to the Chats list when there is nothing to resume. Stored in DataStore, set from an overflow menu on Chats. |
| Deleting a graph | **Cascades to its sessions.** The confirmation states the count ("3 chats will also be deleted"). A session stores only the answer path, so it cannot render without its graph. |
| Node list / node detail screens | **Cut from v0.2.** Surface validation on the graph itself instead. Those screens exist to support the v0.3 editor. |

### Schema

Graph body stays a JSON blob per the locked decision, with metadata
denormalised into columns so the library list renders without parsing every
graph.

```
graphs    graphId PK, name, description, revision, updatedAt, rootNodeId, body TEXT
sessions  sessionId PK, graphId FK -> CASCADE, graphRevision, stateJson,
          startedAt, lastOpenedAt
```

`sessions.graphRevision` records what the session ran against. When a graph is
later updated, `ChatEngine.turns()` already skips nodes that no longer exist, so
old sessions degrade instead of crashing. Export the Room schema from the first
version so later migrations have a baseline.

### Split

- **`:graphcore`** — `.dwiz` read/write, the import decision (same `graphId` +
  higher `revision` → update, else duplicate), duplication that mints fresh ids
  so an import cannot collide, FATAL validation gating. All unit-tested.
- **`:app`** — Room, DataStore, SAF, navigation, screens.

### Order

1. `:graphcore` — dwiz I/O, import decisions, duplication, tests.
2. Room — schema, DAOs, repository.
3. Navigation shell + Graphs screen + import. Drop the bundled example here.
4. Export + `.dwiz` intent filter.
5. Session persistence + Chats screen + launch preference.
6. Empty states, validation surfacing, device test, tag `v0.2.0`.

## Current state

**v0.1 is done and verified on a real device.** Gradle scaffold, `:graphcore`
green at 39 tests, four bugs found and fixed (see git history), chat, traversal,
rewind, snippet copy, text selection, the bundled sample graph, and both CI
workflows passing. Manually confirmed on hardware: launch, clipboard copy,
walking the cycle, rotation, rewind, chip wrapping, endpoints, text selection,
dynamic colour, and that the app requests no permissions.

Not done, in the order agreed:

1. **v0.2.0** — Room, the graph library screen, and `.dwiz` import/export. The
   first tagged release, and the next real work.
2. **Consider raising `targetSdk` to 37** as part of v0.2's device testing, not
   before. It is held at 36 only out of caution; nothing needs it, there is no
   Play Store deadline behind it since distribution is Obtainium, and bumping it
   opts into API 37 runtime behaviour that would need re-checking on hardware.
   Cheaper to fold that into a test pass you are doing anyway.
4. **Wire `GraphEditor.graph` to `mutableStateOf`** so recomposition fires. It is
   currently a plain `var` and will not trigger a redraw. Needed for v0.3, but
   easy to forget because it compiles fine.
5. Rewrite `README.md` from scratch.

Note for whoever picks up the first release: a signed APK **cannot be installed
over the debug build** — different signing keys, so Android refuses the upgrade.
Uninstall the debug build first. This applies to the `workflow_dispatch` signing
rehearsal too, not just to `v0.2.0`.

### Open questions

None currently.
