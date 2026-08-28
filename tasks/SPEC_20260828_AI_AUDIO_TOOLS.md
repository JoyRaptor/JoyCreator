# SPEC — Teach the remaining AI tools about audio clips

**Written:** 2026-08-28 · **For:** an external agent · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first and claim a lane.** Rule 6 (never run gradle) and the
WORKING-TREE HAZARD (`git add` each file as you write it) apply.

**One file: `app/src/main/java/com/fadcam/ui/faditor/ai/AIToolExecutor.java`.** Nothing
else. Two other agents are working in this repo.

---

## 1. The problem, already half-fixed

JoyRaptor was comparing a Vosk transcription against a ground-truth lyric sheet and the
assistant answered:

```
get_transcript -> Error: clip not found: f82f60ff-...
Actual Clips in Project:
 - Clip 0 bd60fa1f-... 3.9s, muted
 - Clip 1 fd275a6f-... ("Blank (auto)") 367.9s, muted
Both clips have captions=false
```

Every statement true, conclusion wrong: the transcript is on the **audio** clip, and the
tools only ever looked at the video spine. `AudioClip` has carried the same transcript
API as `Clip` all along (`getTranscripts`, `getActiveNamedTranscript`) — nothing needed
building, only looking at.

Fixed in `e13b7e7c`, and **you should read that commit before starting**:

- `findAudioClip(proj, id)` and `findActiveTranscript(proj, id)` — resolve a video OR
  audio clip through ONE path. **Reuse these. Do not add a second resolver.**
- `get_transcript` and `correct_transcript` go through them.
- `buildProjectSummary` lists audio clips with ids, offsets, mute state, transcripts.

---

## 2. What is left

Three tools still resolve only against the spine. Each holds this shape:

```java
Clip clip = clipId.isEmpty()
        ? (proj.getTimeline().getClipCount() > 0 ? proj.getTimeline().getClip(0) : null)
        : findClip(proj, clipId);
```

| Tool | Line (approx) |
|---|---|
| `toolAnalyzeNarrativeStructure` | ~812 |
| `toolApplyNarrativeProposal` | ~886 |
| `toolSuggestBrollPlacements` | ~1061 |

They were deliberately left alone rather than search-and-replaced, because **each does
something different with the clip afterwards** and a blind edit across four call sites
is how a subtle bug gets in. Work through them one at a time.

Note the empty-`clipId` default is its own bug: `getClip(0)` on a music project is the
auto-blank black spacer, which has no transcript and never will. `correct_transcript`
now falls back to "the first clip of either kind that actually HAS a transcript" —
follow that precedent.

---

## 3. What to build

For each of the three:

1. Resolve through `findActiveTranscript` when the tool wants a TRANSCRIPT, and through
   `findClip` **or** `findAudioClip` when it wants the clip object.
2. Where the tool then uses spine-only properties (speed, in/out points, segment start),
   decide honestly whether the operation means anything for an audio clip:
   - If it does, map through `AudioClip`'s own offset/in-point — the arithmetic used by
     `drawAudioTranscripts` and the playback follow, so all three agree.
   - If it genuinely does not, return a clear error naming why. **A tool that refuses
     with a reason is better than one that silently acts on the wrong clip.**
3. Fix the empty-`clipId` default as described above.

Then re-read the tool DESCRIPTIONS in the system prompt block near the top of the file
(`19. get_transcript — ...`). If a description says "clip" where it now means "clip or
audio clip", update it. The model can only use what the description tells it exists.

---

## 4. Acceptance

Use JoyRaptor's project: one 4.6s video, one 6:11 mp3 with a transcript, and an auto-blank
spacer between them.

1. `get_transcript` with the audio clip's id returns the song's words. (Already true —
   confirm you have not regressed it.)
2. Each of the three tools, invoked with the audio clip's id, either does something
   sensible or returns an error that names the reason.
3. Each invoked with NO clipId does not land on the auto-blank spacer.
4. `buildProjectSummary` still lists audio clips with their transcripts.

Report the actual tool output for each — not a description of what you expect.

---

## 5. Traps

**5.1 — `getSelectedClip()` returns the WRONG clip silently**, falling back to
`getClip(0)`. On a music project that is the black spacer. Use `clipUnderPlayhead()`.
Do not add a new caller of the former.

**5.2 — The auto-blank is real content to the model.** It is a genuine `Clip` named
"Blank (auto)" holding black, created so overlays and audio past the last video clip
still render. Do not filter it out of listings; do not let it be a default target.

**5.3 — Do not run gradle** (LANES rule 6).

**5.4 — Encoding.** Never `perl -i` without `-CSD`; check `grep -c 'â' <file>` is 0
before committing.

---

## 6. Out of scope

- New tools.
- Changing what any tool DOES for video clips.
- Any file other than `AIToolExecutor.java`.

---

## 7. Reporting

Real line counts from `git diff --numstat`. `build.log`'s last line with its mtime. The
§4 results as literal tool output.
