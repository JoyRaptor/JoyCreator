# JoyBrush papers: size, what is really in the app, and how to finish (2026-11-17)

Written for the owner by the paper wrap-up session (Claude Fable 5.2). Job card:
[JB-PAPER-RES](../jobs/JB-PAPER-RES_resolution_evaluation.md). Measurements and the scripts that made them:
[paper_wrapup_20261117/](paper_wrapup_20261117/). The page to judge by eye:
[PAPER-size-options.html](../design/PAPER-size-options.html).

## The short answers

| Question | Answer | How sure |
|---|---|---|
| How big are the papers? | Today's app carries **25.2 MB** of paper (29 surfaces, 32 looks). The 45 new papers are **205.7 MB** more, about 4.6 MB each. | ✅ measured from the files and inside both built apps |
| Can the 45 come down to a tenth? | **Yes, to 22 to 29 MB**, by storing each at half the width and in a tighter format. Leaving out the 5 doubles gets under 20 MB. Nothing is converted yet: it changes the pictures slightly, so your eye decides first. | ✅ sizes measured on all 45; 🟡 the look is for you to judge on the page |
| Are the 45 in the app you looked at? | **No.** The Note 9 today runs this morning's ordinary build (181 MB, installed 11-17 09:16). The 45-paper test build (380 MB) is no longer on it. What you looked at was the 29 older papers. | ✅ read from the phone, looking only |
| Do the 45 have the full treatment? | Each has all three pictures (colour, tooth, water map) and its six paint numbers. But all 45 are set to "Light off" with the weakest relief of any paper, and nobody has painted on them on a phone. | ✅ data checked; ❌ brush feel not tested |
| Why did the papers look alike? | Five reasons, all measured, in section 2. The biggest: 13 of the 31 older looks are near-white with almost no visible grain, and the chooser shows each paper as a 40-dot circle. | ✅ |

## 1. Size

### What is there today

| Where | Colour pictures | Tooth pictures | Water maps | Total |
|---|---|---|---|---|
| The app as it ships (29 surfaces, 32 looks) | 5.8 MB | 16.6 MB | 2.8 MB | **25.2 MB** |
| The 45 new papers, full size | 63.6 MB | 117.2 MB | 24.9 MB | **205.7 MB** |
| One new paper, on average | 1.4 MB | 2.6 MB | 0.55 MB | 4.6 MB |

- Inside the built apps the paper files are stored as they are, with no extra squeezing: the test app holds
  234.7 MB of paper out of 379.9 MB; the matched ordinary app 25.2 MB out of 169.5 MB. Codex's numbers and file
  fingerprints all checked out exactly.
- The Play version on the Note 9 is 145 MB. Adding the 45 as they are would make it about 350 MB. I believe Play's
  limit for the part of an app that downloads first is 200 MB, but I did not look that up today: **check it
  before relying on it.**

### Why the new ones are so big

1. **They are finer than the page needs.** A new paper stores about 1.7 dots of texture for every dot of your
   drawing. At 100% zoom the phone throws most of that away. Today's shipping papers store 1 dot of tooth and
   0.4 dots of colour per drawing dot. Half-width copies of the new papers are still about as fine as the old
   tooth and twice as fine as the old colour.
2. **Grain is the hardest thing to store exactly.** The tooth is saved in an exact format, and exact formats cannot
   shrink random grain: I tried the best exact format the phone reads (lossless WebP) on every paper and it saved
   1%. So there is **no way to shrink papers without changing a single dot**. A tight format (JPEG, the one the
   colour pictures already use) is where the saving is.
3. **The water map stores four maps and the paint reads one.** Fibre direction and pore size are in the file, but
   no brush reads them today (checked in the shaders).

### What each step saves (all 45 papers, measured)

| Step | Colour | Tooth | Water map | Total | Times smaller |
|---|---|---|---|---|---|
| Today, full size | 63.6 | 117.2 | 24.9 | **205.7 MB** | 1 |
| Half width, exact formats only | 12.4 | 33.0 | 7.7 | 53.1 MB | 3.9 |
| Half width, fine | 12.4 | 17.8 | 7.7 | 38.0 MB | 5.4 |
| **Half width, tighter (my pick)** | 9.0 | 12.4 | 7.7 | **29.1 MB** | **7.1** |
| ...and keep only the water map the paint reads | 9.0 | 12.4 | 0.7 | 22.1 MB | 9.3 |
| ...and leave out the 5 doubles (40 papers) | | | | about 19.6 MB | 10.5 |
| Quarter width (too soft when you zoom in; it is on the page) | 2.9 | 4.4 | 0.7 | 8.0 MB | 26 |

- "Tighter" keeps the tooth's strength within 2% on every paper (worst case 98.3% of the original contrast).
  One step tighter flattens the gentlest papers by up to 16%, so I do not recommend it.
- WebP, the format the previews use, came out the same size as JPEG on paper grain (12.2 against 12.4 MB), and the
  project's test tools cannot read it. So JPEG, which every part already reads: no new decoder in the app.
- **Deriving one map from another:** the app already rebuilds the slopes from the tooth when a paper loads, which
  is why the tooth is one grey picture and not four. The water map cannot be rebuilt from the tooth: it was made
  from the photo plus steps that were not kept.
- **A smaller patch repeated more often:** not recommended. The new papers already repeat every 6 cm of page, and
  nine of them show colour patches or big folds the eye may catch repeating (table below). Smaller patches make
  that worse.
- **A small set in the app, the rest as a pack:** the right end state, and you said paid packs maybe. But papers
  cannot come from a pack yet: the pack reader is built for looks and fonts; for papers it is a written plan only
  (PACK-1). At "tighter", 12 papers in the app are 7.8 MB and the other 33 a 21 MB download.

### Today's 25 MB

Seventeen of today's papers store a 1 MB exact tooth picture each (14.5 MB together). The same tighter format
would bring that to about 7 MB, and the app's papers from 25 MB to about 17 MB. That figure is from the WebP run,
which matched JPEG on the new papers; it was not run as JPEG on the old ones. It alters the tooth slightly, so it
waits for your eye like the rest.

## 2. Are they all used, and are the new ones finished?

### Why you did not see much difference

1. ❌ **You were not looking at the new papers.** They are not in the app on the Note 9 (see above) and not in the
   shipping catalogue.
2. **Most older looks are nearly the same white.** 13 of 31 are near-white (linen, cotton duck, both pulps,
   off-white, both crumpled, silk, the three watercolour papers, drawing paper, Bristol), and 19 of 31 have grain
   fainter than 3 steps out of 255: invisible on a phone. The new papers' grain is five times stronger
   (middle value 11 against 2).
3. **The chooser shows very little.** Each paper is a 40-dot circle showing about a tenth of the texture at true
   size. The tooth circles are all the same grey with at most 10% shading, so they differ only by name.
4. **The relief light is off for most photo papers and capped everywhere.** 16 looks start with Light off, so the
   canvas shows only the colour photo. With Light on, shading is limited to 10% (a ruling made after the old
   strong light looked like "early-90s 3D graphics").
5. **Many brushes barely feel the paper.** Pens and ink feel it at 15%, markers 35%; eraser, fill and smudge not at
   all. Pencils, the sable, and the realistic pencil, watercolour and oil feel it fully. And 13 older surfaces
   (the canvases, silk, fabric, cement, parchment, papyrus, crumpled, pulps) have no water map and share the same
   six paint numbers, so watercolour treats them as one generic paper with a different bump pattern.

Nothing is reused by mistake: no two papers share a tooth file, apart from the two chalkboards, which share one
on purpose.

### The 45, one by one

- **In the chooser today:** ❌ for all 45 (test build only, and that build is not on the phone now).
- **Tooth + water map + numbers:** ✅ means the data is complete, the same as the best older papers. It does not
  mean a brush has been tried on it.
- **Edges join:** measured where the picture wraps around. ✅ = the join is no stronger than ordinary grain;
  ⚠️ = stronger, look for a line.
- **Repeat risk:** ⚠️ = large patches or folds the eye may catch repeating every 6 cm.
- **Size after:** at "half width, tighter" with all four water maps.
- Under each name: what I recommend. "Crumpled paper 2048px" is the firmly crumpled one.

| Paper | In the app's chooser today | Tooth + water map + numbers | Edges join | Repeat risk | Size now | Size after |
|---|---|---|---|---|---|---|
| Crumpled paper 2048px<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ⚠️ big folds | 3.8 MB | 0.51 MB |
| Fine linen canvas 1<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.9 MB | 0.91 MB |
| Fine linen canvas 2<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.3 MB | 0.77 MB |
| Fine linen canvas 3<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.5 MB | 0.85 MB |
| Cotton duck canvas 1<br><sub>Ship</sub> | ❌ | ✅ | ⚠️ 1.6 | ✅ | 4.9 MB | 0.77 MB |
| Cotton duck canvas 2<br><sub>Ship</sub> | ❌ | ✅ | ⚠️ 1.9 | ✅ | 5.1 MB | 0.84 MB |
| Cotton duck canvas 3<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.0 MB | 0.78 MB |
| Rough jute canvas 2<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.6 MB | 0.91 MB |
| Rough jute canvas 3<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.9 MB | 0.97 MB |
| Papyrus 1<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.9 MB | 0.69 MB |
| Papyrus 2<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ⚠️ colour patches | 4.7 MB | 0.68 MB |
| Papyrus 3<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ⚠️ colour patches | 4.6 MB | 0.66 MB |
| Parchment 1<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.5 MB | 0.48 MB |
| Parchment 2<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.7 MB | 0.53 MB |
| Parchment 3<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.5 MB | 0.56 MB |
| Silk 1<br><sub>Ship; your eye: close to General fabric 1</sub> | ❌ | ✅ | ✅ | ✅ | 4.1 MB | 0.61 MB |
| Silk 2<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ⚠️ colour patches | 4.6 MB | 0.55 MB |
| Silk 3<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.2 MB | 0.64 MB |
| General fabric 1<br><sub>Ship; your eye: close to Silk 1</sub> | ❌ | ✅ | ✅ | ✅ | 4.6 MB | 0.69 MB |
| General fabric 2<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ⚠️ colour patches | 4.7 MB | 0.75 MB |
| General fabric 3<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.9 MB | 0.71 MB |
| Cement 1<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ⚠️ colour patches | 5.6 MB | 0.84 MB |
| Gently handled white paper<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ⚠️ big folds | 3.1 MB | 0.33 MB |
| Light crumple flattened paper<br><sub>Ship</sub> | ❌ | ✅ | ⚠️ 1.4 | ⚠️ big folds | 3.4 MB | 0.39 MB |
| Earlier Chalkboard Black 01<br><sub>Drop: the same picture as "supplied 2" (black chalkboard)</sub> | ❌ | ✅ | ✅ | ✅ | 3.4 MB | 0.44 MB |
| Earlier Chalkboard Green 02<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 3.3 MB | 0.44 MB |
| Earlier Chalkboard Green 03<br><sub>Your eye: twin of green 02 (you noted 02 has fewer scuff marks)</sub> | ❌ | ✅ | ✅ | ✅ | 3.4 MB | 0.44 MB |
| Earlier Construction Tan 01<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.1 MB | 0.74 MB |
| Earlier Construction Tan 02<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 5.3 MB | 0.78 MB |
| Earlier Construction Tan 03<br><sub>Drop: the same picture as "supplied 1" (cardboard)</sub> | ❌ | ✅ | ✅ | ✅ | 5.1 MB | 0.75 MB |
| Earlier Papyrus 01<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.6 MB | 0.65 MB |
| Earlier Papyrus 02<br><sub>Ship</sub> | ❌ | ✅ | ⚠️ 1.4 | ✅ | 4.9 MB | 0.70 MB |
| Earlier Papyrus 03<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 4.7 MB | 0.67 MB |
| Earlier Rice Cool 01<br><sub>Your eye: very close to today's cool white rice paper</sub> | ❌ | ✅ | ✅ | ✅ | 3.9 MB | 0.52 MB |
| Earlier Rice Cool 02<br><sub>Ship</sub> | ❌ | ✅ | ✅ | ✅ | 3.6 MB | 0.47 MB |
| Earlier Rice Cream 01<br><sub>Drop: the same picture as "supplied 7" (cream rice paper)</sub> | ❌ | ✅ | ✅ | ✅ | 3.6 MB | 0.46 MB |
| Earlier Sugarcane 01<br><sub>Drop: the same picture as "supplied 9" (sugarcane)</sub> | ❌ | ✅ | ✅ | ✅ | 4.8 MB | 0.65 MB |
| Earlier Sugarcane 02<br><sub>Drop: the same picture as "supplied 10" (Thai kraft)</sub> | ❌ | ✅ | ✅ | ✅ | 5.1 MB | 0.70 MB |
| Cardboard supplied<br><sub>Replace today's Cardboard (same photo, sharper)</sub> | ❌ | ✅ | ✅ | ✅ | 5.2 MB | 0.76 MB |
| Black chalkboard supplied<br><sub>Replace today's Black chalkboard (same photo)</sub> | ❌ | ✅ | ✅ | ✅ | 3.5 MB | 0.44 MB |
| Blue construction paper<br><sub>Replace today's Blue construction paper (same photo)</sub> | ❌ | ✅ | ✅ | ⚠️ big folds | 5.0 MB | 0.70 MB |
| Tan rice paper supplied<br><sub>Replace today's Rice paper, cream (same photo)</sub> | ❌ | ✅ | ✅ | ✅ | 3.6 MB | 0.46 MB |
| White rice paper supplied<br><sub>Replace today's Rice paper, lace (same photo)</sub> | ❌ | ✅ | ✅ | ✅ | 3.8 MB | 0.51 MB |
| Thai paper supplied 1<br><sub>Replace today's Sugarcane paper (same photo)</sub> | ❌ | ✅ | ✅ | ✅ | 4.9 MB | 0.65 MB |
| Thai paper supplied 2<br><sub>Replace today's Thai kraft paper (same photo)</sub> | ❌ | ✅ | ✅ | ✅ | 5.2 MB | 0.71 MB |
| **All 45** | | | | | **205.7 MB** | **29.1 MB** |

**What "full treatment" still lacks, for every one of the 45** (🟡: the data is there, the tuning is not):

- Relief is set to 0.15 and Light is off on all 45. Older photo papers use 0.35, the old canvases 1.0. So on the
  canvas a new paper is its colour photo and nothing else until someone sets these per material.
- The six paint numbers were given per family (the three linens share theirs, and so on), by reasoning, not by
  painting on them.
- Fibre direction and pore size are stored but no brush reads them. That is true of the older papers too.

## 3. Wrap-up: what to ship

Of the 45, **24 are new materials**, **7 are sharper re-makes of papers the app already ships**, **5 are the same
picture twice**, and **9 are extra variants**.

| Group | Papers | Recommendation |
|---|---|---|
| New materials (24) | linen 1-3, cotton duck 1-3, jute 2-3, papyrus 1-3, parchment 1-3, silk 1-3, fabric 1-3, cement, three crumples | ✅ Ship. They take the place of the 10 old drawn ones in the chooser (the canvases, silk, fabric, cement, parchment, papyrus, crumpled). The old ones stay in the app, hidden, so old drawings still open; they are 1.1 MB together. |
| Re-makes of shipping papers (7) | the "supplied" cardboard, black chalkboard, blue construction, cream rice, lace rice, sugarcane, Thai kraft | ✅ Ship **in place of** today's version of each, not beside it. |
| Doubles (5) | earlier chalkboard black 01, construction tan 03, rice cream 01, sugarcane 01 and 02 | ❌ Leave out. Each is the same picture as one of the 7 above (measured: the two pictures line up). |
| Variants (9) | chalkboard green 02 and 03, construction tan 01 and 02, earlier papyrus 01-03, rice cool 01 and 02 | 🟡 Your eye. Two pairs are near twins: green 02 / 03, and rice cool 01 against today's cool white. |

That is **40 papers kept, about 26 MB** at "tighter", taking the place of about 9 MB of today's, so the app grows
by about 17 MB instead of 206 MB. With the one-map water file, about 11 MB.

### What is left before they can ship, in order

1. **Your picks on the page** (colour, tooth, water map, how many). Nothing is converted until then.
2. **Put the 45 back on the Note 9** at the picked size, beside the full-size ones, so the same paper can be
   compared on the phone. The tool is written (`joybrush/tools/paper/shrink_papers.py`); the catalogue rule it
   needs is on the branch.
3. **Set relief and Light per material**, and the paint numbers, by painting on each: pencil, watercolour, oil.
   This is the step that makes them differ under the brush.
4. **Look for seams and repeats** on the flagged papers, at 25% zoom on a full page.
5. **Make the chooser show the difference:** bigger swatches, a pencil rub on the tooth circles, groups by
   material. A screen job; it changes how the sheet looks, so you see it first.
6. **Names and ids:** the 45 are still called "Test ..." with `eval_` ids. Ids are stored in drawings, so they
   must be final before any real drawing uses them.
7. **Papers from packs** (PACK-1), if you want a small set in the app and the rest as a download.

## What was done today, and what was not

| | |
|---|---|
| ✅ | Checked every claim in Codex's handoff against the files, both branches, both built apps and the phone. Its numbers hold. Its one stale point: the 45-paper build is no longer on the Note 9. |
| ✅ | Measured every paper file, and the smaller recipes on all 45. |
| ✅ | Built the true-size before/after page (5 papers, 10 options, 100% and 300% zoom). |
| ✅ | On branch `claude/paper-wrapup-20261117`: the catalogue accepts a grey JPEG tooth for the papers that rebuild their slopes at load; the shrink tool. No shipped paper file is changed. |
| ❌ | Nothing was installed on a phone and nothing was painted. Brush feel, seams on a phone, load time and memory of the smaller files are **not tested**. |
| ❌ | Seams and repeats were measured by numbers, not judged by eye on a full page. |
| ❌ | Play's size limits were not looked up today. |
| ❌ | The JPEG tooth was not decoded on Android. By its code the loader does not care about the file type; the proof on a phone is still owed. |
