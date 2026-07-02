# Plan: Transcript-as-Source Windowing (the AI-editing substrate)

**Status:** Designed + fully traced (2026-06-20). NOT yet implemented — deliberately deferred to a
device-interactive session because the risk is concentrated in scattered UI call sites that need
visual verification, and a subtle error degrades a core feature. This doc is implementation-ready.

## The problem (HANDOFF §3.20 "known limitation")
Today, splitting a clip **bakes** the transcript: `Timeline.splitAt` → `partitionAfterSplit` →
`partitionWords` (and `EditScriptApplier.splitClip` → `partitionTranscripts`) **delete** the words on
the wrong side of the split. So a clip's transcript only carries words within its current trim, and
**lengthening a clip's trim cannot recover words** that went to the other half. The correct mental
model (the user's, and the right long-term design): the transcript belongs to the **SOURCE**; each
clip is a **window** `[inPointMs, outPointMs]` over it; cutting moves the bounds, lengthening reveals
more, splitting never destroys words. This is the substrate that lets the AI make large *non-destructive*
waves of cut/crop/reorder edits without ever breaking the transcript or losing context.

## Key discovery — most consumers are ALREADY windowing-safe
Traced every consumer of transcript words. The change is far smaller than it looks:

- ✅ **`EditorTimelineView` (the hard one) ALREADY windows.** `drawSegmentTranscript` (~line 1763)
  positions each word by **source time** (`rect.left + (word.startMs - inPointMs) * pxPerMs`) and
  **already skips out-of-window words** (`if (word.startMs < inPointMs || word.endMs > outPointMs)
  continue;`). Keeping the full transcript on a clip is invisible to the timeline — it draws only the
  in-window words at the right place. **No change needed.**
- ✅ **`syncRemovedSpansFromTranscript` (strikes→cuts) already clamps** struck spans to `[inMs,outMs]`
  (FaditorEditorActivity ~6605). It needs ONE added guard: `if (w.startMs < inMs || w.endMs > outMs)
  continue;` at the top of its word loop (~6603) so a wholly-out-of-window struck word can't produce an
  inverted/spurious span. (One line.)
- ✅ **Captions** (`CaptionExportRenderer`, `CaptionOverlayView`) render the word at the *current*
  playback time — inherently in-window. **No change needed.**
- ⚠️ **`TranscriptPanelView` is the ONLY display consumer that does NOT window** — it wraps ALL words
  sequentially (parallel arrays indexed like `transcript.words`), so given a full transcript it shows
  out-of-window words. **This is the sole reason the baking was added** (the split comment: "Without
  this both halves kept the FULL transcript, so the second half's words showed up shifted/misaligned").
- ⚠️ **AI** (`AIToolExecutor.toolAnalyzeNarrativeStructure` / `toolSuggestBrollPlacements`) iterates
  `nt.transcript.words` filtering only `struck`. After the rework it should also window to the clip's
  `[in,out]` so the AI sees exactly the clip's words (and offers boundaries inside the trim).

## Implementation (do WITH a device for visual verify)
1. **Add `Transcript.windowed(long inMs, long outMs)`** returning a new `Transcript` whose `words` list
   holds the **SAME `TranscriptWord` references** that satisfy `startMs >= inMs && endMs <= outMs`
   (strict containment, matching the timeline). Shared references mean strikes made in the windowed
   view propagate to the source words — so no separate write-back path is needed.
2. **Make split non-destructive:**
   - `Timeline.partitionAfterSplit`: keep the `removedSpans` clamping (`clampSpans`), DROP the two
     `partitionWords(...)` calls. Both halves keep the full transcript (each via the `Clip` deep-copy).
   - `EditScriptApplier.splitClip`: same — drop the two `partitionTranscripts(...)` calls (keep
     `clampSpans` for removed spans). (Leave the `partitionTranscripts`/`partitionWords` methods or
     delete if now unused.)
3. **Feed the panel a windowed view.** At each `transcriptView.setTranscript(currentTranscript)` call
   site (FaditorEditorActivity ~3997, 6158, 6248, 6267, 6515, 6538) pass
   `currentTranscript.windowed(clip.getInPointMs(), clip.getOutPointMs())` for the bound clip. **This is
   the risky surgery** — each site must use the correct clip's in/out (watch the version-switch and
   AI-generate sites where the clip may differ). Consider a single private helper
   `bindTranscriptPanel(Clip clip)` that all sites route through, to make it one correct place.
   - `currentTranscript` (the field) stays the FULL transcript so `syncRemovedSpansFromTranscript`,
     `strikeFillers`, and `search` keep working on the source; only the *panel's view* is windowed.
4. **Window the AI reads.** In the two read-only tools, skip words outside the clip's `[in,out]`.
5. **One-line guard** in `syncRemovedSpansFromTranscript` (item above).

## Verify (device)
- Generate a transcript on a clip, open the panel → only the clip's words show.
- Split the clip → each half's panel + timeline show only their own words (no shift/dupes).
- **Lengthen a split half's trim → previously-hidden words REAPPEAR** (the payoff; impossible today).
- Strike a word in the panel → it cuts in preview/timeline; un-strike restores. Filler-clean still works.
- Run `analyze_narrative_structure` on a split half → boundaries stay inside its trim.

## Why deferred from the autonomous pass
The timeline/captions/strikes paths are safe, but step 3 (scattered panel call sites, each needing the
right clip's in/out) is exactly the kind of change where a blind mistake silently corrupts a core,
user-facing feature — which would undermine the very goal (AI editing that never breaks transcripts).
Worth ~30 min with the device in hand; the analysis above makes it mechanical.
