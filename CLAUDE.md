# CLAUDE.md

Working notes for an agent in this repo. This is the single source of truth for
design intent *and* mechanics — it absorbed the old `HANDOVER.md`, which has been
deleted. If you learn something not written here, add it.

`README.md` was rewritten for v0.5 and is current. It is the outward-facing
description — what the app is for and how to get it — while this file holds the
design reasoning. Keep them apart: decisions and rationale belong here.

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
- **An answer with no target is not a warning.** It is a documented legal state
  and the normal condition of every question the moment it is created. Reporting
  it made a brand-new node look broken and buried the warnings that mean
  something. Warnings left are: orphaned nodes, targets that no longer exist, a
  missing root, and attachments with nothing attached.
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

## A chat is a record, not a view of the graph

**Answered turns store their own wording.** `Answered` carries the question,
detail, snippets and the options as they were offered at the moment the answer
was taken. Editing or deleting a graph afterwards must never rewrite what was
said — a chat is a record of a conversation that happened, and a script rewrite
does not change a recording of the performance.

**The question waiting for an answer is a record too.** It was already asked, so
editing it in the graph must not rewrite it on screen. It is captured the moment
it is reached.

**The graph is read at exactly one moment: when an answer is tapped**, to find
where that answer leads and to capture the question it leads to. From then on
that question is history. `turns()` therefore takes no graph at all — if
rendering needs the graph, the model has drifted back.

That is what makes a chat outlive its graph, and why sessions need no snapshot
of the graph body — only its name, for the header.

---

## Layout and edge rendering

Implementation: `graphcore/.../layout/GraphLayout.kt` (`object LayoutEngine`).

1. DFS from root, iteratively, marking back-edges. Iterative because cycles are
   legal and recursion would blow the stack.
2. Longest-path depth assignment, excluding back-edges.
3. Two barycenter passes to reduce crossings. Heuristic, not optimal — do not
   spend effort perfecting this until a real 100-node graph proves it matters.
4. Unreachable nodes get parked in a band below the last layer.

Node heights are **measured, not assumed.** `LayoutEngine.layout` takes a
`nodeHeightOf` callback and the editor backs it with `onSizeChanged` on every
bubble, feeding the numbers into a `mutableStateMapOf` that the layout is
recomputed from. It settles after one extra frame because a bubble's width is
fixed, so its height cannot depend on where it was put. Without this the engine
assumes 64dp for everything, a tall bubble overlaps the layer below it, and its
edges start inside it.

That is why **the layout is built in `EditorScreen`, not in `EditorViewModel`** —
only a composable can measure a composable. It is still derived from the graph
every time and still never persisted.

Edge classification:

- Forward hop spanning 1..`MAX_DRAWN_SPAN` layers → **`ARROW`**, drawn.
- Anything else (back-edge, cross-edge, long jump) → **`STUB`**, not drawn.
- No target → **`DANGLING`**, short arrow to nowhere.

**Drawn edges are right-angled, and the route is computed in `:graphcore`.**
`RenderEdge.route` is a polyline from the bottom of the source to the top of the
target; the UI only converts it to pixels. A straight line across two layers ran
behind whatever stood in the layer between, which on screen reads as an edge into
that node rather than past it.

- One layer down → dog-leg through the empty band between the two layers. The
  band, not the midpoint of the two nodes: the source may be the short one in its
  layer, and a run level with a taller neighbour would cut through it.
- Across a layer → down a **corridor**, meaning the middle of a gap between two
  of that layer's nodes or just outside the row, picked nearest to the straight
  line. Nothing has to move to make room; `NODE_H_GAP` means the gaps are
  already there.
- `midpointOf(route)` puts the answer label half way along **by length**. The
  centre of the bounding box falls off the line the moment a route turns.

`LayoutEngineTest` asserts no segment of any route runs over any node. Keep that
test honest rather than pinning down which corridor is chosen — like the
barycenter order, that is a heuristic.

A `STUB` emits **two** chips, and both are required:

- On the source: `Yes ↩ Restart router`
- On the target: `↪ from: Check cable`

The inbound chip is not decoration. Without it a user can land on a node with no
idea anything points at it, and will restructure or delete it wrongly.

Tapping either chip animates the viewport to the other end and flashes the
target node for ~500ms. Keep a small viewport back-stack so a second tap returns
the user to where they were.

**The jump is clamped to keep the canvas on screen.** Centring a node on a graph
that already fits the viewport shoves the rest of it off to one side, and the
further out you are zoomed the further it goes — it looks like the jump landed
somewhere empty. A canvas that fits is centred instead; one that does not is held
so its edges cannot come inside the viewport. Panning by hand stays unclamped:
the user knows where they put it.

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

Bottom navigation: Chats / Graphs / Settings.

**Chats own chat-starting; Graphs own graphs.** New chats begin from the FAB on
the Chats tab, which picks the graph to run on. A graph card is deliberately
**not** clickable — tapping one used to start a chat, which made the Graphs tab
read as a second, read-only chat list. That card becomes the way into the canvas
editor in v0.3.

**Deleting a chat deletes only the chat**, and its dialog says the graph is kept.
Deleting a *graph* is what asks about chats, because a session stores only the
answers taken and cannot be rendered without its graph. The two are easy to
conflate: a chat card is named after its graph, so it carries a "Chat" label and
its button reads "Delete chat".

**Settings is a top-level destination, not an overflow menu**, and is grouped by
the area each setting affects (Chats, Graphs, …). There is little in it today;
the grouping exists so the next setting does not force another reshuffle.

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

### Backups

A whole-library backup is a **plain `.zip` holding one `.dwiz` per graph**, not
another custom extension. The point of a backup is that it stays useful without
this app: it opens on a computer, and a single graph can be pulled out of it and
sent on unchanged.

Restore resolves every graph silently rather than asking about them one at a
time: a newer revision updates in place, anything else is left alone. **Restoring
your own backup onto a live library must be idempotent** — a restore that
duplicated everything would be worse than useless.

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

`samples/` holds two graphs, with different jobs. `:graphcore` picks the whole
directory up as a test resource via a `srcDir` in its build file. **Neither is
bundled into the APK** — a fresh install starts empty, by decision.

- `graph-schema-example.json` — the **one canonical copy** of the schema example
  and the `Fixtures.kt` test fixture. Tests load it by name, so it must keep that
  name and its deliberate cycle.
- `pc-wont-turn-on.dwiz` — the demo graph the F-Droid screenshots are taken of,
  described under F-Droid below. `LayoutEngineTest` loads it by name too, so CI
  needs it present and it cannot be renamed or moved.

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

### Test builds during development

Each build handed over for testing gets its own patch tag — `v0.2.1`, `v0.2.2`,
… — so it installs over the previous one instead of sitting alongside it. Bump
`appVersionName` in `gradle.properties` and tag the commit to match; `release.yml`
refuses a tag that disagrees with it.

These are signed with the release key, so **the first one cannot install over a
debug build** — different signing key, and Android refuses the upgrade. Uninstall
the debug build once; release-to-release upgrades are then clean.

### The release contract

Implemented in `app/build.gradle.kts`.

**The version is in the repo, not in CI.** `gradle.properties` holds
`appVersionName`, and `versionCodeOf()` derives `versionCode` from it as
`major*10000 + minor*100 + patch` — `0.6.0` is `600`. `release.yml` supplies only
the four keystore environment variables and *checks* the tag against
`appVersionName` rather than passing a version in.

This is not a style preference. F-Droid builds a tag with a plain
`gradle assembleRelease` and passes no `-P` flags at all, so the old scheme —
`versionName` from the tag, `versionCode` from `github.run_number` — produced
`dev` / `1` for anyone who was not GitHub Actions, and `run_number` was a number
that existed nowhere in the repo and nobody else could reproduce. **Do not move
the version back onto the command line.**

Patch is capped at 99 by the arithmetic; the build fails loudly rather than
silently wrapping. `0.5.5` shipped as versionCode 33 under the old scheme and
`0.6.0` is 600, so the jump is forward and upgrades are unaffected.

Values are read through `providers.*` rather than `System.getenv` /
`findProperty`, because the root `gradle.properties` enables the configuration
cache and direct reads at configuration time invalidate it.

A local release build with no `KEYSTORE_PATH` is simply unsigned rather than a
configuration error.

Keep the release asset named `decision-wizard-<tag>.apk`. Obtainium matches on a
stable asset name; changing it breaks update detection on installed devices.

### Release notes are written, not generated

`release.yml` publishes `release-notes/<tag>.md` with `--notes-file`, and only
falls back to `--generate-notes` (with a warning) when that file is missing. A
generated list of commit subjects says what was touched, not what changed for the
person installing it.

The format is fixed: a `## Features` section and a `## Fixes` section, plain
one-line bullets, each ending with **the commit id in brackets**. Omit a section
that has nothing in it. Write the file before tagging — the ids have to exist
already, so the notes commit is the last one before the tag.

This is separate from `fastlane/…/changelogs/<versionCode>.txt` on purpose. Both
describe the same release, but the fastlane file is what F-Droid users read and
carries no commit ids.

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

## F-Droid

The app is being submitted to F-Droid. It meets the inclusion policy already and
always did — GPL-3.0, every dependency FOSS (AndroidX, Kotlin, Room, all Apache
2.0), no GMS, no Firebase, no analytics, no ads, no network code of any kind, no
embedded keys. Nothing about the app had to change to qualify. What had to change
was how it is *built*.

### What lives where

| Thing | Where | Read by |
|---|---|---|
| Summary, description, changelogs, icon | `fastlane/metadata/android/en-US/` | F-Droid, from this repo |
| Build recipe | `fdroid/com.knasiotis.decisionwizard.yml` | nothing — it is a copy to paste into fdroiddata |
| Version | `gradle.properties` | everything |
| GitHub release notes | `release-notes/<tag>.md` | `release.yml`, at publish time |

`fdroid/…yml` does nothing where it sits. It gets copied into a fork of
`gitlab.com/fdroid/fdroiddata` as `metadata/com.knasiotis.decisionwizard.yml`, on
a branch named after the application id, and submitted as a merge request. It is
kept here so its `versionName` / `versionCode` cannot drift away from
`gradle.properties` unnoticed.

**Cutting a release touches four files, and nothing enforces that they agree:**

1. `gradle.properties` — `appVersionName`, the one everything else derives from
2. `fdroid/com.knasiotis.decisionwizard.yml` — `versionName`, `versionCode`,
   `commit`, `CurrentVersion`, `CurrentVersionCode`
3. `fastlane/…/changelogs/<versionCode>.txt` — named after the **code**, for
   F-Droid users
4. `release-notes/<tag>.md` — named after the **tag**, for GitHub

Only the tag-versus-`gradle.properties` disagreement is caught automatically, by
`release.yml`. The other three fail quietly: a wrong recipe builds the wrong
commit, and a misnamed notes or changelog file just goes unread.

Summary and description are deliberately *not* in the recipe. F-Droid prefers
fastlane files in the app repo, so they live in one place instead of two.
Changelog files are named after the **versionCode**, not the version name —
`changelogs/600.txt`, not `0.6.0.txt`.

### Things that were checked, so they need not be checked again

- **The committed `gradle-wrapper.jar` is fine.** F-Droid's scanner rejects
  prebuilt binaries, but `scanner.py` explicitly calls `removeproblem` on
  `gradle-wrapper.jar`, `gradlew`, `gradlew.bat` and
  `gradle-daemon-jvm.properties`. No `scandelete` or `scanignore` is needed.
- **Gradle 9.7.1 is supported.** This one is easy to get wrong: the hardcoded
  version list inside `gradlew-fdroid` stops at 9.2.0, which looks like a hard
  ceiling. It is only a fallback. The real list is fetched at build time from
  `gitlab.com/fdroid/gradle-transparency-log/checksums.json`, which carries
  9.7.1. Check the transparency log, not the script.
- **AGP 9.3.1 is fine**, and its Gradle floor of 9.5.0 is satisfied. That check
  is a fallback anyway — `gradlew-fdroid` reads `distributionUrl` from
  `gradle-wrapper.properties` first and only consults the AGP mapping if no
  wrapper is found.
- **`compileSdk 37` is fine.** The buildserver preinstalls platforms only up to
  `android-33`, which looks alarming, but SDK downloading is not disabled and AGP
  fetches the missing platform itself.

### Screenshots are the remaining gap

`fastlane/metadata/android/en-US/images/phoneScreenshots/` is empty and F-Droid
wants it filled. They cannot be produced from this machine — there is no emulator
and no system image installed, only `platform-tools`. They have to come off a
real device, via `adb exec-out screencap -p > shot.png`.

Two shots, in this order, as listed in `README.md`: **`01-chat.png`** — a chat
mid-flow, showing a question, its answer chips and a snippet with the copy
button — and **`02-canvas.png`** — the editor, showing a branch and the stub
chips on the cycle. The chat one goes first because F-Droid's carousel leads with
it. A node-sheet shot and a library shot were considered and dropped: they show
chrome rather than the idea.

Filenames are `sorted()` by F-Droid, so keep the numeric prefix and zero-pad if a
third is ever added.

**`samples/pc-wont-turn-on.dwiz` exists to be the graph in those shots.** It is a
desk-side "PC won't turn on" flow — 20 nodes, 8 resolutions, 16 snippets — and it
is shaped for the canvas shot rather than only for realism:

- It validates with **zero** warnings, so no badges clutter the canvas.
- Of 26 edges, exactly **one** is a stub: the cycle
  `n-ram → n-recheck-internal → n-ram`. That is one reciprocal chip pair to
  photograph, instead of ten scattered ones. Depths were chosen to keep every
  other edge inside `MAX_DRAWN_SPAN`, so **re-pointing one answer can silently
  turn a drawn arrow into a stub.** `LayoutEngineTest."the demo graph stays
  shaped for its screenshots"` guards both the zero warnings and the single
  stub, and also asserts no edge route runs over a node — so a break shows up as
  a failing test rather than as a bad screenshot.
- The root and `n-display` have three answers each; the rest have two, which is
  what makes the wrapping answer chips look right.
- Reconvergence is real: several paths land on `n-resolved-boots` and
  `n-escalate-hw`.

Get it onto the device with `adb push samples/pc-wont-turn-on.dwiz /sdcard/Download/`,
then tap it — the `.dwiz` intent filter opens it in the app.

### Screenshots must be inside the tag

Worth knowing before planning the submission: F-Droid does **not** read fastlane
metadata from the default branch. `insert_localized_app_metadata()` scans the
**build checkout**, which is the tree at the recipe's `commit:` — the tag. So
screenshots added to `main` after a tag do not attach to that build; they first
appear in whatever version is tagged after them.

That is a chicken-and-egg only if you let it be one. Screenshots do not care how
the APK is signed, so they can be taken off a debug build at any time. **Take
them before tagging** rather than after.

Consequences for the submission:

- v0.5.6 is being tagged without screenshots. Its F-Droid listing will have none.
- The fdroiddata merge request should point `commit:` at **the first tag that
  contains the screenshots**, not necessarily at v0.5.6.
- The alternative, if a listing is wanted sooner, is putting the images directly
  in the fdroiddata repo under `metadata/com.knasiotis.decisionwizard/en-US/`,
  which takes precedence over the app repo. Prefer keeping them here.

### Signing, and why the F-Droid build is not the Obtainium build

F-Droid signs with **its own key**, so an F-Droid install cannot upgrade an
existing Obtainium install and vice versa — same refusal as debug-over-release.
Anyone switching has to uninstall first and loses their graphs unless they back
up.

The fix, if it ever matters, is a **reproducible build**: F-Droid rebuilds the
tag, confirms it matches the APK from `release.yml` byte for byte, and publishes
the one signed with *our* key. Not required for acceptance, and not worth doing
until the app is accepted and building there reliably.

### The one permission

The APK declares `com.knasiotis.decisionwizard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
which F-Droid will display. It is injected by AndroidX, it is signature-level and
scoped to this app's own id, and it grants nothing to anybody. `AndroidManifest.xml`
still declares no permissions and the app still touches no network. Do not try to
remove it — it comes from `androidx.core` registering non-exported receivers.
Note that `README.md` says "the app asks for nothing", which is true of the
manifest but not literally true of the built APK's permission list.


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

**Never use `@Insert(onConflict = REPLACE)` on a table other rows depend on.**
SQLite implements REPLACE as DELETE plus INSERT, and that DELETE fires
`ON DELETE CASCADE`. Saving a graph therefore destroyed every chat that ran on
it — a rename looked like it deleted the user's chats. Use **`@Upsert`**, which
compiles to `ON CONFLICT DO UPDATE` and touches no other table. Check the
generated SQL when in doubt:

```bash
find app/build -name 'GraphDao_Impl.kt' | xargs grep -n 'INSERT\|UPDATE'
```

**Never edit code with blind string replacement.** A `sed`/`str.replace` whose
pattern no longer matches does nothing and reports success. This shipped a build
where the "New chat" button did not exist: the pattern had been broken by an
earlier reformat, so the picker dialog and its state compiled while nothing could
open them. Use an edit tool that errors when the pattern is absent.

**Grep the APK for ASCII-only fragments.** `strings` ends a run at the first
non-ASCII byte, so a literal containing an em dash, an ellipsis or an accent
never appears whole and a grep for it returns nothing — which reads exactly like
the code being missing. Interpolated strings are split at the `$` too. Search for
a plain ASCII substring instead.

**Verify the user-visible strings in the built APK, not just that a class
exists.** Grepping the dex for `GraphPickerDialog` "confirmed" that same broken
build — the dialog was present and unreachable. The check that would have caught
it is whether `"New chat"` appears:

```bash
unzip -qo app/build/outputs/apk/debug/app-debug.apk 'classes*.dex'
strings -a classes*.dex | grep -cF "New chat"
```

**`BUILD SUCCESSFUL in 1s` after a real change deserves suspicion.** Check the
APK's mtime before believing an incremental build did anything.

**KSP is versioned independently of Kotlin** since KSP 2.3.0. Older guidance says
to match `<kotlin>-<ksp>` (e.g. `2.2.21-2.0.5`); that scheme is gone, and there is
no `2.4.10-*` build to hunt for. KSP and the Room Gradle plugin both apply
cleanly on top of AGP 9's built-in Kotlin.

**Migrations are mandatory from version 5 onwards.** There is no destructive
fallback: a missing migration fails loudly on launch rather than quietly deleting
hand-authored graphs that cannot be recovered. Migrations live in
`data/Migrations.kt`.

**Two things count as a schema change, and the second catches people out.** A
column, table or index is the obvious one. The other is **the shape of anything
inside a blob column** — `graphs.body` and `sessions.stateJson` are JSON, and
Room cannot see inside them, so it keeps rows this build cannot parse. A build
shipped that crashed on launch for exactly that reason. Prefer changes old data
still parses under (a new field with a default) over ones needing a rewrite;
where a rewrite is unavoidable, bump the version and migrate the rows.

`ChatState` carries a `CHAT_STATE_VERSION` marker, always written, so a stored
chat from a newer build is *detectable* rather than merely unparseable. Loading
refuses a newer version instead of half-reading it, and refuses an unreadable row
instead of throwing.

**Room schemas are committed** under `app/schemas/` and the database
deliberately has **no `fallbackToDestructiveMigration`**, so a missing migration
fails loudly rather than silently wiping the user's own hand-authored graphs.

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
- **v0.4.0** — transcript export. Now cheap: a chat already stores its own
  wording, so exporting one is formatting `Answered`, with no graph to consult
  and nothing to reconcile.

- **v0.5.0 — done.** Snippets can be written rather than only read, resolutions,
  transcript export, graph copy, clickable stub chips, and undo that says what it
  did and takes you there.
- **v0.5.6 — canvas fixes plus the F-Droid packaging, untagged.** Right-angled
  edge routes so a hop across a layer stops running behind the node in between,
  measured bubble heights behind it, a stub-chip jump that keeps the canvas on
  screen, and a focus flash that no longer sticks. Bug fixes, so a patch tag
  rather than a minor one — nothing new is possible that was not possible before.
  The F-Droid packaging rides along on it rather than taking a number of its own.

- **v0.6.0 — the search bar, not started, and reserved for it alone.** Fixes and
  packaging do not go on this tag; it names the one thing a user gets out of it.
  A search bar over the **Chats** list and the **Graphs** list. Both grow past a screenful quickly and there is currently no
  way to find anything in either.

  The pattern already exists three times over — the new-chat graph picker, the
  connect-to-existing target list, and move-children-to — all substring,
  case-insensitive, trimmed, with an explicit "nothing matches" rather than an
  empty list. Follow it rather than inventing a fourth behaviour. Searching chats
  should match the chat's own name *and* its graph's name, since a chat named
  "Tuesday callout" is often looked for by the flow it ran on.

- **The F-Droid packaging rode along with v0.5.6**, exactly as the rule above
  says it should: it carries no user-facing change, so it took no number of its
  own. `appVersionName=0.5.6`, `changelogs/506.txt`, and the recipe all agree on
  `0.5.6` / `506`. It was originally written against `0.6.0`; that number went
  back to search.

**Attachments are deferred**, deliberately, and are no longer part of v0.4. They
are the one feature that forces `.dwiz` to become a zip, and nothing so far has
needed them. `DwizCodec` already sniffs the zip magic bytes, so the door stays
open — picking them up later costs nothing that doing them now would save.

## v0.2 — done

Room library, `.dwiz` import/export, tap-to-open, persistent chat sessions,
launch preference, a Settings destination, zip backups and chat retention.
Shipped as test builds `v0.2.1` … `v0.2.4` on the `v0.2` branch.

### Decisions made during v0.2

| Question | Decision |
|---|---|
| Bundled example graph | **Dropped.** A fresh install starts empty; the empty state carries first-run. `samples/` lives on as the `:graphcore` test fixture. |
| Chat sessions | Persisted in Room, **from the moment the chat is created**. Naming a chat and starting it is a deliberate act, so it belongs in the list straight away — an empty chat the user asked for is not clutter. |
| App launch | DataStore preference: resume last-opened session, or go to Graphs to start a new chat. A tapped `.dwiz` always wins over it. |
| Deleting a **graph** | **Chats survive as read-only records.** Reversed in v0.3: the cascade and the foreign key are gone. A chat is history; deleting the flow it ran on should not erase what was answered. |
| Deleting a **chat** | Deletes only the chat, and says so. A chat card is named after its graph, so it carries a "Chat" label and its button reads "Delete chat". |
| Starting a chat | The **New chat** button on Chats, with a searchable graph picker. Graph cards are deliberately not clickable. |
| Node list / node detail | Cut. They exist to support the v0.3 editor. |
| Chat retention | Never (default), 7, 30, or a custom 1–3650 days. Measured from *last opened*, not started. Keeping is the default — silently deleting history never is. |

### Schema (version 1)

```
graphs    graphId PK, name, description, revision, updatedAt, rootNodeId,
          body TEXT, savedAt
sessions  sessionId PK, graphId FK -> CASCADE, graphRevision, stateJson,
          startedAt, lastOpenedAt
```

`sessions.graphRevision` records what the session ran against. When the graph is
later updated, `ChatEngine.turns()` skips nodes that no longer exist, so an old
session degrades rather than crashing — and the Chats card says the graph has
changed.

---

## v0.3 plan

The canvas editor: authoring moves onto the device. Plus one thing carried over
from v0.2 feedback.

### Scope

1. **Chat titles.** Name a chat before it starts, so the Chats list is not a
   column of identical graph names. Adds `sessions.title` — schema version 2.
2. **The canvas** — `LayoutEngine` rendered as real composables in a custom
   `Layout`, pan and zoom, stub chips, validation badges.
3. **Node editing** — edit contents, add child, connect to existing.
4. **Delete ops UI** over the existing `DeleteOps`.
5. **Undo/redo** over the existing `UndoStack`.

Most of the hard logic already exists in `:graphcore` and is tested. v0.3 is
mostly the UI over it — plus the one thing below that is a real bug waiting.

### Known trap

**`GraphEditor.graph` is a plain `var` and will not trigger recomposition.** It
compiles fine and simply never redraws, so it looks like a broken editor rather
than a state bug.

Do **not** fix this by making it `mutableStateOf` — that would put
`androidx.compose.runtime` into `:graphcore` and break the rule that keeps the
module framework-free and JVM-testable. Instead the editor ViewModel owns the
`GraphEditor` and republishes a `StateFlow` after every operation, so
recomposition is driven from `:app` and the core stays pure.

---

## Current state

**Everything through v0.5 is done, merged and released.** `main` carries the
work and the tags; the last published build is `v0.5.5` (versionCode 33).
`:graphcore` is green at **103** tests. `README.md` has been rewritten and is
current.

**`main` is five commits past `v0.5.5`** — four canvas fixes plus the release
notes change — **and the F-Droid packaging is committed on top of them. All of
it is v0.5.6.** Nothing is tagged or pushed yet.

The packaging took no number of its own: `appVersionName=0.5.6` gives
versionCode **506**, and `changelogs/506.txt` and `fdroid/…yml` agree with it.
`release.yml` now refuses a tag that disagrees with `appVersionName`, so v0.5.6
is the only tag this tree can publish.

**v0.6.0 is search and is being built in a separate session**, so do not assume
the tree is yours and do not take that number.

Also outstanding:

1. **Tag v0.5.6** — push `main`, then the tag. That is the whole release.
2. **Screenshots**, which must land *inside* a tag to reach F-Droid — see
   "Screenshots must be inside the tag" above. Take them off a debug build,
   commit them, and tag.
3. **The fdroiddata merge request**, pointing `commit:` at the first tag that
   has the screenshots.
4. **v0.6** — search, in the other session. See the build order above.
5. **Consider raising `targetSdk` to 37** during a device test pass. Nothing
   needs it; there is no Play Store deadline since distribution is Obtainium.

Note: a signed APK **cannot be installed over a debug build** — different keys,
so Android refuses the upgrade. Uninstall the debug build first. Release-to-
release upgrades are clean.

### Open questions

None currently.
