# HANDOVER.md

Briefing for an AI coding agent working in this repository. Read this before
touching any code.

---

## What this app is

An **Android-only** app for support agents. It has two halves:

1. **Chat** — a bot asks a question, the user taps an answer button, the bot
   walks to the next question. Repeat until an endpoint is reached.
2. **Editor** — a zoomable canvas where the user builds the question graph by
   hand.

**This is not an LLM app.** There is no model, no inference, no API call, no
network dependency of any kind at runtime. The bot is a graph traversal. If you
find yourself reaching for an AI SDK, you have misunderstood the project.

---

## Locked decisions

These were argued through and settled. Do not revisit them without being asked.

| Decision | Value |
|---|---|
| Platform | Android only, forever. No iOS, no desktop, no KMP. |
| Language / UI | Kotlin + Jetpack Compose, Material 3 |
| Persistence | Room for graph library + chat sessions; graph body stored as a JSON blob column |
| Serialization | `kotlinx.serialization` |
| Node placement | **Auto-layout.** The user pans and zooms. Nodes are never dragged. |
| Coordinates | **Never persisted.** Layout is derived from the graph every time. |
| Long edges | Not drawn. Rendered as reciprocal stub chips. |
| Graph shape | Cycles, reconvergence and dangling branches are all legal. |
| Undo | Snapshot-based, in memory only, not persisted across restarts. |
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

---

## Data model

Full worked example: `graphcore/graph-schema-example.json`.
Kotlin models: `graphcore/Models.kt`.

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

Implementation: `graphcore/GraphLayout.kt`.

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

Implementation: `graphcore/DeleteOps.kt`. Call `DeleteOps.preview()` first to
decide which options the sheet offers and what counts to display.

| Option | Behaviour |
|---|---|
| Splice | Only when the node has exactly one child. Inbound edges repoint to that child. **Surface this first when available** — it is the common case. |
| Delete only | Inbound answers dangle, children stay, orphans grey out. |
| Delete and purge | Node plus everything that becomes unreachable. Preview the count first. |
| Delete and reparent | Children move to a chosen node, carrying labels as new answers. |

"Remove all children" does *not* mean deleting the subtree — in a DAG a child
may be reachable from elsewhere. Orphans are computed by re-running reachability
from the root, never by walking descendants.

Prefer **undo** over confirmation dialogs. Snackbar with UNDO, not a modal.

---

## Undo / redo

Implementation: `graphcore/UndoStack.kt`.

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
   options both look right. Past answers stay visible but disabled; tapping one
   **rewinds the session** to that point. Copy button on snippet bubbles.
3. **Graphs** — library. Create / duplicate / import / export / delete.
4. **Node list** — searchable, per graph, with validation badges. This is a
   maintenance view, not the primary authoring surface.
5. **Node detail** — title, body, snippets, attachments, answer rows. Show
   inbound edges ("reached from: 3 nodes"), tappable.

Bottom navigation: Chats / Graphs.

---

## Import / export

Export is a **zip** with a custom extension so Android can associate it:

```
internet-down.tgraph
├── graph.json
└── assets/
    └── router-leds.jpg
```

Attachments reference relative paths. Images are copied into app storage on
import, never base64-inlined into the JSON.

Use SAF (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`). Register an intent
filter so tapping a `.tgraph` file opens the app.

---

## Validation

Implementation: `graphcore/GraphValidator.kt`.

`WARNING` issues render as badges on the canvas and in the node list. They never
block anything. The only `FATAL` codes are duplicate node ids and duplicate
answer ids, which make a file impossible to load unambiguously and so reject the
import.

---

## Build order

Ship each stage as a real signed APK before starting the next one.

- **v0.1** — bundle `graph-schema-example.json` as an asset. Chat screen and
  traversal only. No editor, no Room. Purpose: get Gradle, signing, secrets and
  the release pipeline working while the app is too small to hide problems.
- **v0.2** — Room, graph library, import/export.
- **v0.3** — the canvas editor, layout, stub chips, undo/redo, delete ops.
- **v0.4** — snippets, attachments, transcript export.

---

## Current state

`graphcore/` holds pure-Kotlin, UI-free core logic that is written but **not yet
wired into an Android module**: models, validator, layout engine, undo stack,
delete ops. Package is `com.example.tgraph.*` — rename to the real application
id when the module is created.

Nothing in `graphcore/` depends on the Android framework. Keep it that way so it
stays unit-testable on the JVM.

### Next tasks

- Create the Android module and move `graphcore/` into it.
- Wire `GraphEditor.graph` to `mutableStateOf` so recomposition fires.
- JVM unit tests over the layout engine and delete ops, using the example JSON
  as a fixture. Include a graph with a deliberate cycle.
- GitHub Actions: tag push → `setup-java` 17 temurin → Gradle → decode
  `KEYSTORE_B64` secret → `assembleRelease` → attach APK to the release.
  `versionCode` from `github.run_number`, `versionName` from the tag.
