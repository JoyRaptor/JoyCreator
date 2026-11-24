# SPEC — Choosing a transcript source, without a dialog in the way

**Written:** 2026-08-28 · **For:** an external agent · **Owner:** JoyRaptor.

Claim a lane in `tasks/LANES.md` **named after this spec**. Rule 6 (never run gradle) and
the WORKING-TREE HAZARD apply — and **stage by explicit path, never `git add -u <dir>`**,
which has already swept other agents' work into the wrong commit today.

⚠️ **`FaditorEditorActivity.java` is shared.** Check LANES before your first edit. Your
work is confined to the transcript panel; other lanes hold the caption drawer and the
PiP/layout regions of the same file.

---

## 1. Where this stands

Fixed already in `81779e91` — read it first. `resolveTranscriptTarget()` used to pick its
target from what was SELECTED, so on a music project (where the selection is usually the
auto-blank black spacer) the panel looked for a transcript on a clip that can never have
one, found none, and threw up the "Select a transcript" chooser **every time**. JoyRaptor:

> "I don't want it popping up every single time I'm trying to nudge things around."

It now resolves: selected audio that HAS a transcript → whatever audio is live under the
playhead → a spine clip under the playhead → any transcript in the project → and only
then the chooser.

**So the pop-up is gone.** What is missing is the way back IN.

---

## 2. What to build

JoyRaptor's own design, and it is the right one:

> "if it's selected and it doesn't have a transcript, you can just say load source and,
> oh, you didn't mean this one? don't show me this again, option box that they can tick
> with some extra words that say something to the effect of, you can always add a source
> by doing X, Y, or Z so that they can know where to look in the future"

Three parts. Build all three; they are one feature.

### 2.1 A header that says what you are reading, and switches

The transcript panel header currently shows a truncated `Tr…`. Make it name the source —
e.g. **`Audio · import`** or **`Clip 2 · vosk`** — and make tapping it open the existing
chooser (`showTranscriptSourceChooser`, the "Select a transcript" dialog around
`FaditorEditorActivity:31483`).

This is the always-available route, and it also answers a question the panel never
answered: *whose words am I looking at?*

### 2.2 A `+ Source` chip on the version row

The version row already exists (`Balanced / Fast timing / Best wording / Import`) and is
about *which transcript*. Add `+ Source` at its **start**, not its end.

**Put it first.** The Import chip in the caption font row was appended last and sat off
the right edge behind other controls — present, wired, and undiscoverable until it was
moved (`90d77721`). Do not repeat that.

### 2.3 The one-time offer, with a way to silence it

When the user opens the transcript panel on a clip that has **no** transcript anywhere in
the project (the only case that still reaches the chooser):

- Offer to load/generate a source for the selected clip, as now.
- Add a **"Don't show this again"** checkbox.
- Add one line of body text naming where to find it later: the header (§2.1) and the
  `+ Source` chip (§2.2). Word it as instruction, not apology.
- Persist the tick in the same `SharedPreferences` the editor already uses
  (`getSharedPreferences("faditor_ui", MODE_PRIVATE)` — see `PREF_TIMELINE_BAND_DP`).
- When silenced, open the panel in its empty state with the two affordances visible
  rather than showing nothing.

### 2.4 Long-press the Transcript tool

Tap opens what is live (already true). **Long-press opens the chooser.** A shortcut for
people who know, never the only route.

---

## 3. Acceptance

Use JoyRaptor's project: 4.6s video, 6:11 mp3 with a transcript, auto-blank spacer.

1. Opening the panel with nothing selected still goes straight to the song's words, no
   dialog. (Already true — confirm you have not regressed `81779e91`.)
2. The header names the source, and tapping it opens the chooser.
3. `+ Source` is visible without scrolling the version row.
4. On a project with no transcript at all, the offer appears with a working
   "don't show again"; ticking it and reopening does not show it; the header and chip
   still work.
5. Long-press on the Transcript tool opens the chooser from anywhere.

Report with screenshots (`adb exec-out screencap -p > x.png`) — the Note 20 IS connected
(`adb devices` shows `<note20-serial>`), regardless of what an older LANES note says.

---

## 4. Traps

- **4.1** `getSelectedClip()` silently returns `getClip(0)` when nothing is selected — the
  auto-blank spacer on a music project. Use `clipUnderPlayhead()`.
- **4.2** Audio clips carry transcripts too (`AudioClip.getTranscripts()`), and
  `transcriptIsForAudio` / `transcriptAudioIndex` are how the panel tracks that. Several
  bugs today came from a path that handled only spine clips; check both.
- **4.3** Never `perl -i` without `-CSD`; `grep -c 'â' <file>` must be 0 before commit.
- **4.4** Do not run gradle; read `build.log` for a `BUILD SUCCESSFUL` newer than your
  last edit.

---

## 5. Out of scope

Changing how transcripts are generated. The transcript panel's editing gestures. Anything
in the caption drawer.

---

## 6. Reporting

Real `git diff --numstat`. `build.log`'s last line with mtime. §3 results with the
screenshots. Say plainly what needs JoyRaptor's eye.
