# Decision Wizard 🧙

An offline Android app for support agents. It turns a troubleshooting flowchart
into a chat: the bot asks a question, you tap an answer, it asks the next one —
and at the end it hands you the exact wording to paste into the ticket.

> [!TIP]
> **🤖 This is a vibe coded project.** It was built conversationally with an AI
> assistant, start to finish. It works and it is tested, but it was not designed
> on a whiteboard first — expect the shape of it to reflect that.

## The problem

Troubleshooting scripts live in a wiki page nobody reads, a PDF nobody can
search, or someone's head. Two agents handling the same fault do different
things, write different ticket notes, and escalate with different information.

Decision Wizard makes the script something you *walk through* rather than
something you *remember*:

- **One question at a time.** No scrolling a document looking for where you are.
- **The wording comes with it.** Each step can carry ready-made text — a ticket
  note, an escalation summary, a closing comment — with a copy button.
- **The chat is a record.** What was asked and answered is kept exactly as it
  happened, so it can be exported into the ticket. Editing the flow afterwards
  never rewrites history.
- **Flows are files.** Build one, send it to a colleague, they import it. No
  server, no account, no sync service.

**No network. No telemetry. No permissions.** Check the manifest — the app asks
for nothing.

## Download 📦

Grab the latest signed APK from the
[**Releases**](https://github.com/knasiotis/decision-wizard/releases) page.

Updates work well with [Obtainium](https://github.com/ImranR98/Obtainium) —
point it at this repository and it will track releases for you.

Requires Android 12 or newer.

## Features ✨

**Chat**
- Walk a flow one question at a time, with wrapping answer buttons
- Copy a whole snippet, or select part of it
- Tap any earlier answer to rewind and take a different branch
- Chats are named, resumable, and survive their graph being deleted (read-only)
- Export a chat as plain text for the ticket
- Optional automatic clean-up of old chats

**Editor**
- Zoomable canvas, laid out automatically — nodes are never dragged
- Add questions, add resolutions, connect to existing questions
- Four ways to delete: splice, delete only, delete and clean up, or reparent
- Undo and redo, with a message saying what changed and where
- Long links are shown as chips on both ends; tap one to jump to the other

**Library**
- Several flows side by side; create, copy, import, export, delete
- Import decides for you: a newer revision offers an update, anything else
  offers a copy
- Back up every flow to a `.zip`, and restore from one

## Flows are hand-editable 📝

A `.dwiz` file is plain JSON. Open it in any text editor:

```json
{
  "schemaVersion": 1,
  "graphId": "0f9c2a6e-4b31-4d8a-9f2e-77c1a0e5b310",
  "name": "Internet down",
  "revision": 7,
  "rootNodeId": "n-power",
  "nodes": [
    {
      "id": "n-power",
      "title": "Is the router powered on?",
      "body": "Ask the customer to look at the power LED on the front panel.",
      "answers": [
        { "id": "e-1", "label": "Yes", "targetNodeId": "n-lights" },
        { "id": "e-2", "label": "No",  "targetNodeId": "n-plug" }
      ]
    }
  ]
}
```

A worked example lives in [`samples/`](samples/). Cycles, reconvergence and
half-built branches are all legal — the app warns, it does not refuse.

## Screenshots 📸

Not included yet. Worth adding, in this order:

1. **A chat mid-flow** — a question, its answer buttons, and a snippet with the
   copy button. This is the one that explains the app.
2. **The canvas** — a flow with a branch and a cycle, showing the stub chips.
3. **The node sheet** — the actions available on a question.
4. **The library** — a few flows side by side.

## Building 🔨

```bash
./gradlew :graphcore:test        # core logic, no Android needed
./gradlew :app:assembleDebug
```

`:graphcore` is a pure-Kotlin module holding the graph model, layout engine,
traversal, validation and file format — all unit-tested on the JVM. `:app` is
Compose and Room on top of it.

Releases are built and signed by GitHub Actions on a version tag.

## Licence ⚖️

**GNU General Public License v3.0** — see [LICENSE](LICENSE).

Icons are traced from [Lucide](https://lucide.dev) (ISC); see
[NOTICE.md](NOTICE.md).
