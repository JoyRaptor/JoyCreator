# SPEC: Transcript sharing — one source of truth per source

**Status 2026-07-26:** migration + in-memory sharing BUILT (see "Landed"); the panel half and
several follow-ups are OPEN. Written from a live debugging session on the reporter's Note 20,
so the numbers below are measurements, not estimates.

This is the finishing of `PLAN_transcript_windowing.md` (designed 2026-06-20). That plan's
steps 1–2 landed and steps 3–5 never did, and **no migration was ever written** for projects
damaged by the pre-step-2 behaviour. That gap is what produced the bug report below. Read
that plan first; this spec assumes it.

## The report

> "depending on what clip I'm on, it shows me the spacings that I made for that clip, but
> none of the other clips… it seems like it's a shifting ground depending on which clip.
> There's no way I would be able to keep track of twenty different cuts."

The user's mental model — one transcript per source, each clip a bookmark/window into it,
edits visible from everywhere — is exactly `PLAN_transcript_windowing.md`'s stated design.
The code had never implemented it.

## What was actually wrong

A `Clip` owns its own `List<NamedTranscript>`, and `Clip`'s copy constructor deep-copied
them. Every split therefore FORKED the text, and the forks then drifted apart independently.

**Two fork shapes exist in the wild, and they need opposite treatment.** Both were measured:

| Project | Shape | Evidence |
|---|---|---|
| `27221664` (Jul 8, legacy) | disjoint PARTITIONS | forks of 1291/524/272/141 words whose start ranges exactly match each clip's own trim, zero overlap |
| `a32d24e2` (active) | full COPIES + word edits | all 5 forks span `[1200, 497610]`; differ by 1–2 words where the user re-timed `'wrong.'` from 9825→9511 and deleted a junk `'�'` |
| `00024cc7`, `66623e32` | byte-identical copies | no word-count divergence at all — pure waste |

Legacy partitions exist because `splitAt` used to BAKE the transcript (`partitionWords`
deleted the far side of every cut). That is fixed for new splits — both `partitionWords` and
`EditScriptApplier.partitionTranscripts` still exist but have **zero callers** — but nothing
ever healed the projects already cut that way.

## The merge rule (and why the obvious ones are wrong)

- **First-wins is wrong.** Tried it; on the live project it discarded a 1112-word fork in
  favour of a 1110-word one. It was caught only by a word-count guard added on a hunch.
- **Union by word is wrong.** It duplicates edited words: `'wrong.'` exists at 9825 in one
  fork and 9511 in another, and a naive union keeps both.
- **Matching words by `startMs` is wrong.** Start times are NOT unique inside a transcript —
  21 and 38 same-start pairs were found within *single* forks in the wild.

**The rule that handles every shape:** the fork with the most words is canonical; a word from
another fork is adopted only when its start time falls OUTSIDE the span the canonical already
covers. Disjoint partitions therefore union completely; full copies adopt nothing and the most
complete one wins untouched.

Validated offline against both real project files before any code shipped:

```
27221664  forks=[1291,524,272,141] -> merged=2228 (+937 recovered)
          forks=[1261,506,262,173] -> merged=2202 (+941 recovered)
a32d24e2  forks=[1110,1112,1112,1112,1112] -> merged=1112 (+0, no duplication)
          forks=[1150 x7]                  -> merged=1150 (+0)
```

Words inside regions that EVERY clip trimmed away are gone for good — no fork retained them.
That is not recoverable and is accepted.

## Landed

- `TranscriptSharing.shareProject()` — the merge rule above, plus `countForks()` as a cheap
  pre-check. Idempotent.
- `ProjectStorage.shareTranscriptsWithBackup()` — runs at load, **backup-first and verified**
  (byte-copy to `backups/project-preshare-<ts>.json`, size-checked) before mutating, mirroring
  `dedupTranscriptsWithBackup`. Persists immediately so the shrink is real.
- `Clip`'s copy constructor and `copy()` now SHARE the `NamedTranscript` instances instead of
  deep-copying, so new splits stop forking.

## OPEN — in rough priority order

1. **Feed the panel a windowed view** — `PLAN_transcript_windowing.md` step 3, never done.
   BUT the reporter wants the opposite of what that plan specified: they want to read AHEAD
   into other clips' text and place breaks there while sitting on clip A. **Recommendation:
   show the whole source transcript with the current clip's range highlighted**, rather than
   windowing it away. This supersedes step 3 as written — do not implement that step blindly.
2. **Steps 4–5 of the windowing plan** — window the two AI read tools to the clip's `[in,out]`,
   and the one-line out-of-window guard in `syncRemovedSpansFromTranscript`.
3. **Named/alternate transcripts as a first-class concept.** The reporter raised loading
   alternate subtitles (e.g. other languages) against one source. The `NamedTranscript` list
   already models several versions; what is missing is that they are per-CLIP rather than
   per-SOURCE. After sharing, that mostly falls out — worth an explicit pass.
4. **File-size follow-up.** Sharing dedupes in memory, but each clip still SERIALIZES its own
   copy. The reporter's `a32d24e2` is a 5.3MB `project.json` with a 17.7MB undo history,
   largely this duplication. Writing each transcript once at project level with clips holding
   ids would shrink both dramatically. Deliberately deferred: it is a schema change and the
   session's priority was correctness, not size.
5. **Verify the migration on device.** Built and validated offline against the two real files;
   NOT yet confirmed by watching it run. Expect
   `transcriptSharing: collapsed=… recovered=… shared=…` on project open.

## Landmine worth remembering

The failure mode here was not a bug in the code that existed — it was a **plan that half
landed with no migration**, plus a doc comment ("Transcript is NOT partitioned on split")
that described the code's *current* behaviour and was silently untrue of the *data on disk*.
Grepping proved the comment right about the code and it was still misleading. When a
behaviour change leaves old data in a shape the new code doesn't expect, the migration is
part of the feature, not a follow-up.
