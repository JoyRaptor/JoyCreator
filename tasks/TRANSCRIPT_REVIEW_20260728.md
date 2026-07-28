# Transcript context-review — findings, 2026-07-28

Subagent pass over the 5,979-word "Best" transcript of project
`a32d24e2-6b8d-4bd8-8432-ef5a6169dcfc` (a theology/typology lecture). **Report only — nothing
was applied.** Roughly **75 simple 1-for-1 fixes** and **40 needing restructuring**, ~2% of
tokens.

## READ THIS BEFORE APPLYING ANYTHING

**The index scheme used for that pass is unsafe in Region A.** The export wrote 20 words per
line with the first word's index as an anchor, but em-dashes made several Region A lines carry
21–22 tokens, so a running token count drifts up to **+22** by the end of the region. Regions B
and C are exactly 20/line and their indices are reliable.

**Fix before applying:** re-export one word per line as `index<TAB>word`, unambiguous by
construction, and re-run the review against that. Cheap, and it removes the whole class of
off-by-N risk. Do NOT patch Region A by index from the original pass.

**Mechanical constraint that shaped the report:** each timing anchor is bound to a word, so
corrections must be 1-for-1 token replacements. Deletions, splits and merges were kept in a
separate table because they need the timing span re-divided.

## The failure modes (these generalise — worth teaching the in-app AI)

1. **Biblical proper nouns** — the dominant class. Jabal/Japheth ("J-Ball", "J. Fifth"), Cain
   ("Canaan"), Annas ("Ananias"), Belial ("B'lil"), Hophni/Phinehas, Amorite ("Amrit"), Dagon
   ("day gone"), Bethel ("Beth Elnez's"), Zoar ("Zor"), Lamb ("land"), Rephaim/Emim/Zamzummim.
2. **Homophones on load-bearing words** — mix/miss, two/to, four/for, cord/chord, saved/safe,
   beast/**beach**, assurance/insurance. These change meaning while reading as fluent English,
   so they survive a casual proofread.
3. **Spurious mid-sentence sentence-final periods** — ~23 of them, e.g. "the time of the
   indignation is. past." Breaks caption line-splitting.
4. **Duplicate tokens at Whisper segment boundaries** — often with a capitalised restart
   ("these These", "because Because"). NOT all duplicates are errors: "So — so" and "a picture
   of what? What Christ would do" are genuine speech and must be left alone.

## Highest-confidence single fixes (re-locatable by searching the text, not by index)

`loved bra` → bride · `the Gibbon Bride` → given · `beach I saw` → beast · `the land himself
which has seven eyes` → Lamb · `Ananias and Caiaphas` → Annas · `Seth, Canaan and Abel` → Cain ·
`five Amrit kings` → Amorite · `not to miss fig leaves` → mix · `you two witnesses` → your ·
`comeeth` → cometh · `terror the forty and two children` → tare · `sons of B'lil` → Belial ·
`5-Phelocene lords` → Philistine · `two sheavers out of the wood` → she bears · `a byed 10 days`
→ abide · `day gone, prostrate` → Dagon · `atom-side open` → Adam's side · `to collectives` →
two · `lots daughters` → Lot's · `androconology` → non-word, replacement uncertain.

## Density by region

| region | clips | flagged | note |
|---|---|---|---|
| A | 0–6 | ~0.3% | cleanest; only 3 low-confidence flags in 1,112 words |
| B | 7–8 | ~2% | largest absolute count; the clearest blunders |
| C | 9–10 | **~3.3%** | worst; dense with quoted scripture and proper nouns |

## Why this belongs in the app, not in a script

The user's own framing: "ask the AI to make their transcripts better." The app already has an AI
assistant with transcript tools (`AIToolExecutor`), and this pass is the evidence that a
"clean up this transcript" action is worth building — the failure modes above are exactly what a
language model catches and neither Vosk nor Whisper can. Doing it by hand over adb fixes one
project; building it fixes every project.

**Ground rule for whoever builds it:** propose, never auto-apply. The reviewer above correctly
refused to flag unusual theological phrasing as error, and that restraint is the whole game — a
confident wrong "correction" in a published video is worse than a missed one.
