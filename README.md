# tgraph

![Vibe Coded](https://img.shields.io/badge/vibe--coded-yes-blueviolet)
![Built with Claude](https://img.shields.io/badge/built%20with-Claude-d97757)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![License](https://img.shields.io/badge/license-MIT-informational)

An Android app that walks you through troubleshooting steps, one question at a time.

---

## What it actually does

If you work in support, you probably have a few routines in your head. *Is the
router on? No — check the power supply. Yes — what colour is the internet light?*
You follow the same paths over and over, and occasionally you skip a step because
you were rushing.

This app holds those routines for you.

**In the chat**, a bot asks you one question and gives you buttons to answer. You
tap one, and it asks the next appropriate question. You keep going until you reach
an outcome — a fix, or an escalation. Any step can carry a ready-made note you can
copy straight into the ticket.

**In the editor**, you build those routines yourself. You add a question, give it
some answers, and attach a follow-up question to each answer. It draws the whole
thing as a map you can pan and zoom around.

Two things worth knowing:

- **Paths can rejoin.** Four different routes can all end up at "reseat the cable"
  without you writing it out four times.
- **You can loop back.** "Did that fix it? No → go back to step 3" is allowed.

## What it is not

**There is no AI in this app.** The bot is not a language model and does not think.
It reads the flowchart you drew and follows it. It has no internet connection, it
sends nothing anywhere, and it will only ever say things you typed into it yourself.

That is deliberate. When you are on a call with a customer, you want the same
question every time, not a plausible-sounding improvisation. It works offline, it
works on a plane, it works when the vendor's API is down, and it gives the same
answer today and in five years.

---

## Using it

**Graphs** tab — your library of flows. Create one, or import a `.tgraph` file
someone sent you.

**Editing** — the canvas shows your flow as bubbles connected by arrows. Tap a
bubble to open it, or select it to reveal edit, add child, connect to existing, and
delete. Layout is automatic: you pan and zoom, you don't drag nodes around.

Connections that jump a long way, or loop backwards, aren't drawn as arrows — they'd
cross the whole screen. They show as small labels on both ends instead:
`No ↩ check cable` on the node it leaves, `↪ from: is it fixed?` on the node it
arrives at. Tap either to jump between them.

**Chats** tab — start a session, pick a graph, answer questions. Tap any earlier
answer to rewind to that point. Export the transcript when you're done.

Nothing stops you from building a graph with loops, dead ends, or unreachable nodes.
You get warnings, not walls. Undo and redo are in the editor toolbar.

## Sharing routines

Each flow exports as a single `.tgraph` file — a zip containing `graph.json` and an
`assets/` folder for images. Send it to a colleague, they tap it, it opens in their
copy of the app. They can edit it however they like. There is no account, no server,
and nothing syncs behind your back.

The JSON has no coordinates in it, because layout is computed rather than stored —
so you can also write one by hand in a text editor and it will render correctly.
A full worked example is in `graphcore/graph-schema-example.json`.

---

## Install

**Recommended:** install [Obtainium](https://github.com/ImranR98/Obtainium), add
this repository as a source, and it will track releases and update automatically.

**Manual:** grab the APK from the [latest release](../../releases/latest). Android
will ask you to allow installing from an unknown source — that's expected for
anything outside the Play Store. There is no Play Store listing and no plan for one.

## Building it yourself

```
git clone <this repo>
./gradlew assembleDebug
```

Debug builds sign themselves. Release builds expect `KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in the environment, and fall
back to the debug config when those are absent.

Tests are JVM-only, no emulator required:

```
./gradlew test
```

---

## ⚠️ Disclaimer: this is a vibe coded project

A meaningful amount of this was designed and written in conversation with an LLM
(Claude), by someone learning Kotlin on the way. The architecture was argued about,
the trade-offs were real, and the reasoning is documented in `HANDOVER.md` — but
nobody should mistake that for the same thing as years of Android experience.

Practically, this means:

- **It has not been professionally reviewed or audited.** Assume there are bugs in
  places nobody has looked yet.
- It works, on the phones it has been tried on, for the graphs it has been given.
  The edge cases that have been tested are in `app/src/test/`. The ones that haven't
  been tested are everywhere else.
- Some of it is probably more clever than it needed to be, and some of it is
  probably naive in ways that will be obvious to someone who knows better.
- **It is a personal tool, shared in case it is useful.** It is not a supported
  product and there is no guarantee it keeps working.
- **Don't put anything sensitive in it.** Your flows live in a local database and in
  files you export yourself. Nothing is uploaded anywhere, which is the one
  guarantee this project can make with total confidence, because there is no server
  to upload it to. Customer data still doesn't belong here.
- **Use at your own risk.** If it gives you the wrong troubleshooting step, that's
  because someone drew the flowchart wrong — including possibly me.

This notice is here because being open about it seems better than quietly shipping
it and hoping nobody asks. If you find something broken, an issue is welcome. If you
find something architecturally cursed, an issue is *extremely* welcome. If you fork
it, fix it, or rewrite it properly, please do.
