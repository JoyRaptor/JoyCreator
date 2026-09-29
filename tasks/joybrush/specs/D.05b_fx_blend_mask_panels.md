# D.05b — Move `FxPanel`, `BlendPickerPopover` and `MaskKeyPanel` into `:studiokit`, with their resources

| | |
|---|---|
| **Tier** | T2 (T1 review — it edits app-reachable resources and the build's resource graph) + T3 phone check |
| **Status** | 🟨 **Draft.** D.05 is the head of this tail and this spec does not contradict it: D.05 moved the maths (`fx/`, `keyframe/`, `model.BlendModes`, `model/MaskSdf`, `tools/GradientRampEditorView`) and deliberately left the three panels in the app "because they use app resources — a later D.05b moves them with their resources". That is this row. **The move itself is mechanical and fully specified below. Two things about it are NOT mine to decide — Q1 (the `ObjectDrawer.Kit` fork) and Q2 (`MaskKeyPanel`'s dependency on the Studio's own media model) — and both are cheap to answer and expensive to guess.** |
| **Needs** | D.05 (`:studiokit` exists, `fx/` + `keyframe/` + `BlendModes` are already in it, Gson is on its classpath) — which itself needs D.02 (`Studio.java`, `tools/ColorPickerDialog.java` are in the kit) |
| **Owner area** | MOVE (git mv, same package names) the files in §"What moves" · EDIT those files **only** to repoint `R` (Decision 2) · EDIT `studiokit/src/main/res/values/strings.xml` (append — D.02 created it) · NEW `studiokit/src/main/res/drawable/` files for the icons in §"What moves" · NEW `tools/check_studiokit_deps.py` |
| **Estimated size** | ~9 `git mv`, 114 `R` reference edits across 7 files, 2 new XML resource files, ~1 new script (~150 lines). **The moved code changes by exactly the `R` lines and nothing else.** |
| **Command** | `python tools/check_studiokit_deps.py` → prints `kit deps OK`, exit 0. Then the watcher: `BUILD SUCCESSFUL` with `:studiokit:compileDebugJavaWithJavac`, `:app:compileDebugJavaWithJavac`, `:joybrush-android:compileDebugKotlin` all EXECUTED. Then the JVM harness: `run-fx.sh`, `run-key.sh`, `run-mask.sh`, `run-lanechain.sh` and the blend test, all green. |

## Goal

JB-2.21's UI *is* the Studio's `FxPanel`, and the owner said it: "if these are built modular, as we
improve them in one place the other places get the enhancements." So the three panels move into
`:studiokit` **whole, under their existing package names, with the resources they read** — so the
Studio keeps calling exactly the class it calls today, and any improvement to the panel lands once
and reaches both. This is R23's "share, don't copy" in its strictest form: a **move**, not a fork,
not a shared base class, and not a second copy of a view builder.

**Pure move: no behaviour change in the Studio, no changed package name, no changed call site.**

## What moves (`git mv`, packages unchanged)

| From `app/src/main/java/com/fadcam/ui/faditor/` | To `studiokit/src/main/java/com/fadcam/ui/faditor/` | Lines | Why it comes |
|---|---|---|---|
| `tools/FxPanel.java` | `tools/FxPanel.java` | 1 809 | named in D.05's "stays in the app" note |
| `tools/BlendPickerPopover.java` | `tools/BlendPickerPopover.java` | 312 | named there |
| `tools/MaskKeyPanel.java` | `tools/MaskKeyPanel.java` | 552 | named there |
| `tools/TextOverlayDrawer.java` | `tools/TextOverlayDrawer.java` | 243 | `FxPanel` calls `TextOverlayDrawer.Kit` 48× app-wide; `Kit` is a nested class, so the file moves with it |
| `tools/RotationDialView.java` | `tools/RotationDialView.java` | 244 | `MaskKeyPanel` constructs it by simple name; imports only `Studio` |
| `tools/ObjectDrawer.java` | `tools/ObjectDrawer.java` | 1 749 | `ObjectDrawer.Kit` is called **251 times in 12 files**, all three panels use it. **Q1 — read this before starting.** |
| `../type/Type.java` | `com/fadcam/ui/type/Type.java` | 151 | the only `com.fadcam` import of `ObjectDrawer`; 151 lines, uses no `R` at all |

**Stays in the app:** `fx/FxGradeMigration.java` (same package either way, so nothing changes for
it — D.05's own note), the three `*TransformHost.java`, `model/*`, and every caller of every one of
the seven files above. Because the package names do not change, **the 251 + 48 + 4 call sites in the
app are not edited at all** — that is the whole point of Decision 1.

## Contract — the resources that move with them

`androidkit` aside, the seven files read **`com.fadcam.R`**, which is the *app's* `R`. A library
cannot see the consuming app's resources, so every one of those references must be repointed at
D.02's kit `R` — **and the kit must then declare every one of them.**

| Kind | Count | Names |
|---|---|---|
| `R.string` in the three panels | **112** (56 in `FxPanel` — all written fully qualified — 33 in `BlendPickerPopover`, 23 in `MaskKeyPanel`) | the list is in §Appendix A |
| `R.string` in `ObjectDrawer` | 12 | also in §Appendix A |
| `R.plurals` in `FxPanel` | 2 | `faditor_fx_load_replaces`, `faditor_fx_effect_count` |
| `R.drawable` in `ObjectDrawer` | 12 | `ic_edit_cut`, `ic_frost_24`, `ic_fx_24`, `ic_lock`, `ic_marker_flag_end`, `ic_marker_flag_start`, `ic_touch_press_24`, `ic_touch_press_off_24`, `ic_visibility_off`, `ic_visibility_on_24`, `ic_volume_off_24`, `ic_volume_up_24` |
| `R.drawable` in `TextOverlayDrawer` | 2 | `ic_check`, `studio_action_pill` |
| `R.id` in `ObjectDrawer` | its own ids | `faditor_tag_drawer_fill` (and the framework's own `R.id.background` / `R.id.progress`, which are **not** ours) |
| `R.attr` | **none of ours** | `state_checked`, `state_pressed` (framework) and `selectable` (Material). Nothing to move. |

**Total: 124 strings, 2 plurals, 14 drawables, 1 id.** The *script* in Decision 3 is the authority on
this number, not this table — the table is here so a builder knows what to expect and so a reviewer
can see at a glance whether something was missed.

### What the kit's `res/values/strings.xml` gets

- **Every one of the 124 names declared, with the English value copied VERBATIM** from
  `app/src/main/res/values/strings.xml` — same text, same placeholders, same `%1$s` order. Not
  retyped, not "cleaned up", not translated.
- **No `values-xx` file, ever.** The app keeps every translation it has today, and because Android
  merges a library's resources with the app's and **the app's value wins** on a name collision, a
  French phone shows the app's French string and an English phone shows the two identical English
  ones. This is D.02 step 3's rule, verbatim, and it is the only reason copying the English text is
  safe.
- The two plurals become `<plurals>` with the same `one` and `other` quantities, verbatim.
- The 14 drawables are `git mv`'d into `studiokit/src/main/res/drawable/` **including any
  `values/` variant they have** (check for `drawable-night`, `drawable-v24`, `drawable-anydpi`);
  the app keeps its own copies, same merge rule.
- `faditor_tag_drawer_fill` is declared in the kit's `res/values/ids.xml`.

## Decisions

1. **A pure move: same package names, no rename, no new base class, no wrapper, no second copy.**
   *Why:* R23 in the owner's own words, and the arithmetic that settles it — `ObjectDrawer.Kit` is
   called from **12 files, 251 times**; the alternative shapes (extract `Kit` to a top-level class,
   or give each panel its own helpers) are a 251-call-site rename in Studio code or a copy. A `git
   mv` into the same package is a move that costs the app **zero** edits and keeps one authority.
2. **Only the `R` references change, and they all change the same way:** `com.fadcam.R.string.x` →
   `com.fadcam.studiokit.R.string.x`; bare `R.string.x` (in the two files that
   `import com.fadcam.R;`) keeps its form and the **import** becomes
   `com.fadcam.studiokit.R`. Nothing else in any moved file changes — not a name, not a colour
   value, not a default, not a comment. *Why:* "improvements land once" is only true if there is
   exactly one file, and that is only true if the move is provably a move.
3. **`tools/check_studiokit_deps.py` is the gate, and it checks BOTH directions.** For every
   `import com.fadcam.…` in `studiokit/src/main/java`: the class must exist inside the kit's own
   source tree (after D.02 and D.05, `fx/`, `keyframe/`, `model/BlendModes`, `model/MaskSdf`,
   `tools/GradientRampEditorView`, `Studio`, `type/Type` are all there), or be on a small, explicit
   allow-list printed by the script. And for every `R.string` / `R.plurals` / `R.drawable` /
   `R.id` **the kit's own `R`** referenced in the kit's tree: the name must be declared in the kit's
   resources. *Why:* a missing name is a **compile error** in the kit, which is the real guarantee;
   the script is the cheap half that also catches the reverse — a name the kit declares that nothing
   uses, which is how a resource bill grows without anyone deciding it.
4. **`android.R.string.ok` and `android.R.string.cancel` in `MaskKeyPanel` do NOT move and are NOT
   required in the kit.** *Why:* they are the platform's own dialog buttons and the app never
   localised them. A naive `grep 'R\.string\.'` counts them and a builder will add two junk entries
   to the kit's `strings.xml`. Decision 3's script must exclude any `android.R.` and any `com.fadcam.R.`
   that is not the kit's own, and case 3 of the Tests is exactly this.
5. **The app keeps its own copies of all 124 strings, 2 plurals, 14 drawables and 1 id.** Nothing is
   deleted from the app. *Why:* the app has translations for the strings and `R.id.faditor_tag_drawer_fill`
   may be referenced from XML layouts; deleting a resource the app still uses is a build break at the
   worst possible moment, and deleting one it does not is a bonus nobody needs today.
6. **`fx/FxGradeMigration.java` stays in the app and is not edited.** *Why:* D.05's own note, and it
   reads the Studio's legacy `effects.EffectStack`; the same package means it still compiles against
   the kit's `FxStack` with no import. Editing it would be a behaviour change inside a "pure move".
7. **The moved files change by the `R` lines only — and the script proves the shape of the diff,
   not just its existence.** `git diff --stat -M` must show all seven files as renames, with the
   changed-line count **equal to the number of `R` references repointed** (140 for the three panels
   and the two Kits, by occurrence, not by unique name). *Why:* a move that quietly reformats, or
   renames a method "while it was in there", is a move that cannot be reviewed by reading the diff,
   and this is the last chance to notice — after D.02c and D.05 land, the same edit is buried.
8. **No new dependency, and the harness sourcepath is NOT changed again.** D.02 already put both
   `app/src/main/java` and `studiokit/src/main/java` on every `run-*.sh`; D.05 already ran the four
   harness scripts this row re-runs. *Why:* the two rows above did that work; doing it twice is how a
   script ends up with three sourcepaths and a separator that only works on one OS (D.04's subject).
9. **`MaskKeyPanel` moves LAST, and only if Q2 is ruled.** The step order is 1–4 then 5, and
   **if Q2 has no ruling when the builder starts, the builder does steps 1–4, commits, and stops**
   with the row at `🟧 Built — 5 of 6 files` and the question quoted. *Why:* ROADMAP §2 step 7 ("never
   guess") and the fact that `MaskKeyPanel` is the only one of the seven with a dependency the kit
   genuinely cannot satisfy. A builder who stops with four files moved and one clean question has
   done the job; a builder who drags `model/Clip.java` into a library to finish a row has not.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (pure move, no rename) | `noCallSiteInTheAppChanged` — the 251 + 48 + 4 uses of the moved classes' nested members still resolve, because `git diff --name-only` contains **no** app source file |
| 2 (only `R` changes) | `theOnlyChangedLinesAreRReferences` — the diff's changed lines are 100 % `R` reference repoints, counted |
| 3 (the gate, both directions) | `everyFadcamImportInTheKitResolvesInsideTheKit` · `everyKitResourceReferencedIsDeclaredInTheKit` · `aKitResourceThatNothingUsesIsReported` |
| 4 (`android.R` excluded) | `frameworkStringsAreNotDemandedFromTheKit` — the script's demand set is exactly 124 + 2 + 14 + 1, and `ok` / `cancel` are **not** in it |
| 5 (the app keeps its copies) | `theAppStillDeclaresEveryNameTheKitDeclares` — the two sets are compared name by name and the app's is a superset |
| 6 (`FxGradeMigration` untouched) | `fxGradeMigrationIsNotInTheDiff` |
| 7 (diff shape) | `theDiffIsSevenRenamesAndNoMore` — `git diff --stat -M` parsed; each of the seven is a rename, and no other path appears |
| 8 (no new work on the harness) | `noRunScriptChanged` — `git diff --name-only` contains no `run-*.sh` |
| 9 (MaskKeyPanel last) | `maskKeyPanelIsTheLastPathInTheStepOrder` — asserted by reading this spec's own Step section in the script's self-test, so a reordering fails loudly |

## Tests

`tools/check_studiokit_deps.py` — a plain Python 3 script, no dependencies, run from the repo root.
It prints one line per check and `kit deps OK` at the end, or a named failure and exit 1. **It is a
test script, not a lint:** every case below is a distinct named failure, and the builder runs each
one by breaking the tree and pasting the message.

1. `everyFadcamImportInTheKitResolvesInsideTheKit`: for every `import com.fadcam.…` in
   `studiokit/src/main/java`, `studiokit/src/main/java/<path>.java` exists, **or** the import is in
   the printed allow-list. Non-vacuous: add `import com.fadcam.ui.faditor.model.Clip;` to any kit
   file and it must fail, naming the file, the import and the allow-list.
2. `everyKitResourceReferencedIsDeclaredInTheKit`: the kit's tree references exactly **124** string
   names, **2** plurals, **14** drawables and **1** id, and every one is declared in
   `studiokit/src/main/res/`. Non-vacuous: delete one `<string>` and it must fail naming the name
   and the file that wanted it.
3. `frameworkStringsAreNotDemandedFromTheKit`: the demand set contains neither `ok` nor `cancel`
   nor any name that appears only after `android.R.` or `com.fadcam.R.`. This is Decision 4 and it
   exists because a builder will get it wrong on the first attempt.
4. `everyEnglishValueIsCopiedVerbatim`: for each of the 124 names, the kit's English value **equals**
   the app's English value in `app/src/main/res/values/strings.xml` — compared after normalising
   whitespace and XML entity escapes, and **not** after stripping placeholders. `%1$s` must be
   present in both, in the same order, with the same type suffix. Non-vacuous: change one character
   in one kit value and it must fail printing both strings.
5. `theTwoPluralsKeepBothQuantities`: `faditor_fx_load_replaces` and `faditor_fx_effect_count` each
   have `one` and `other` in the kit, and both are verbatim copies.
6. `theFourteenDrawablesArePresentInTheKit`: each of the 14 names resolves to a file under
   `studiokit/src/main/res/drawable*/`, and **every variant directory that exists in the app exists
   in the kit** (checked by listing both, not by guessing `drawable-v24`).
7. `theAppStillDeclaresEveryNameTheKitDeclares`: `kit_names ⊆ app_names` for all four kinds, printed
   as a count. This is the guard on Decision 5 and it fails the moment somebody "tidies up" a
   duplicate.
8. `aKitResourceThatNothingUsesIsReported`: a declared-but-unreferenced kit resource is **reported**
   (a warning line, not a failure — D.02's own `faditor_color_set` may legitimately be used only by
   the app's copy) so the bill stays visible.
9. `noRunScriptChanged` and `fxGradeMigrationIsNotInTheDiff` and `noCallSiteInTheAppChanged`: three
   assertions over `git diff --name-only <base>…` — no `tools/jvm-harness/run-*.sh`, no
   `fx/FxGradeMigration.java`, and **no file under `app/src/main/java` except the seven that moved**.
   This is Decision 1 and Decision 7 as one check, and it is the one that would catch a rename.
10. `theDiffIsSevenRenamesAndNoMore`: parse `git diff --stat -M`; exactly seven renames, and the
    sum of the changed-line counts equals the number of `R` references repointed.
11. **Self-test, the builder must run and paste:** break each of cases 1, 2, 4, 5, 6 and 7 once and
    paste the six failure messages. A gate nobody has seen fail is a gate nobody knows works.

**Command:** `python tools/check_studiokit_deps.py` → `kit deps OK`, exit 0. Never gradle on the
owner's PC — the watcher builds (START_HERE rule 3).

## Steps

1. **Read Q1 and Q2 first.** If Q1 is unanswered, the answer changes §"What moves" and nothing
   below is safe. Say so in the row and stop if you cannot proceed.
2. `git mv` the four files of group A: `tools/FxPanel.java`, `tools/BlendPickerPopover.java`,
   `tools/TextOverlayDrawer.java`, `tools/RotationDialView.java` (exact paths in the table).
3. Repoint their `R` references (Decision 2). Change the two `import com.fadcam.R;` lines to
   `import com.fadcam.studiokit.R;`. **Add nothing else.**
4. Append the 112 strings and 2 plurals to `studiokit/src/main/res/values/strings.xml`, verbatim
   from the app. Write the script with cases 2, 3, 4, 5, 8 and run it — it must pass for group A
   before group B exists.
5. **Group B, only if Q1 is answered "move `ObjectDrawer`":** `git mv` `tools/ObjectDrawer.java`
   and `../type/Type.java`, `git mv` the 12 drawables, declare `faditor_tag_drawer_fill`,
   repoint the `R`s, extend the script's expected counts to 124 / 2 / 14 / 1.
6. **Group C, `MaskKeyPanel`, only if Q2 is answered (Decision 9):** `git mv` it, repoint its 23
   strings, re-run the script, run the four harness scripts.
7. Watcher green; harness green; the eleven script cases green; the non-vacuity drill pasted.

## Do not

- **Do not rename anything** — no package, no class, no method, no field, no constant. Not even a
  "clearer" one. The 251 call sites are the proof that the move was free, and a rename deletes it.
- Do not change a colour, a default, a string's English text, a dimension, or a behaviour. The
  English text is **copied**, and case 4 fails the build if one character differs.
- Do not delete anything from the app's `res/`. Decision 5.
- Do not create `values-xx`, `values-night` or any other locale/qualifier under the kit. The app owns
  translation; a translated string in a library is a translation nobody updates.
- Do not add a dependency, a plugin or a new module. If a moved file needs a library the kit does not
  have, that is a finding, not an addition.
- Do not touch `FaditorEditorActivity.java`, `LobbyFragment.java`, the manifest, or any of the six
  R14 leftover app files. Same-package moves need none of them.
- Do not touch `run-*.sh` (D.02 did the sourcepath) and do not "improve" the panels while they are
  open. Improvement lands once, in a later row, with a re-review.
- Do not delete `FxGradeMigration.java` or move it.
- Never run gradle yourself on the owner's PC.

## Definition of done

- [ ] `python tools/check_studiokit_deps.py` → `kit deps OK`, exit 0, all eleven cases pasted
- [ ] the six-case non-vacuity drill pasted
- [ ] `git diff --stat -M` pasted: seven renames, and the changed-line count equals the `R`
      references repointed
- [ ] watcher `build.log` pasted: `BUILD SUCCESSFUL` with `:studiokit:compileDebugJavaWithJavac`,
      `:app:compileDebugJavaWithJavac` and `:joybrush-android:compileDebugKotlin` EXECUTED
- [ ] harness pasted: `run-fx.sh`, `run-key.sh`, `run-mask.sh`, `run-lanechain.sh` and the blend
      test, all green
- [ ] `git status --short` shows only owner-area paths
- [ ] **owner check (T3, 3 minutes, only after the watcher is green):** in the Studio, select a clip →
      open the FX panel → add a blur and a gradient map → open the gradient editor → change a
      keyframe's value; change a clip's blend mode; add a mask and a chroma key and rotate it. All
      of it as before, in English. **Then switch the phone's language to French and check one FX
      string** — that is the merge rule's whole point and it is the only part of this row that can be
      silently wrong.
- [ ] committed `D.05b: fx, blend and mask panels into studiokit`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The move is fully specified and every
part of it is checkable. The two questions below are the only things I could not decide, and both are
answerable in one line.)_

### Q1 — 🔴 for the Lead: `ObjectDrawer.Kit` is the one thing that stops this being a move

All three panels call `ObjectDrawer.Kit` — `note`, `describe`, `rowLabel`, `stepper`, `styleSlider`,
`background`, `pill`, `RING` — and it is a **nested** class, so it cannot travel without its host.
`ObjectDrawer` is 1 749 lines: a whole drawer with tabs, toggles and a `FrostSource`. Three ways:

- **(a) Move `ObjectDrawer` and `com.fadcam.ui.type.Type` into the kit (what this spec assumes).**
  A `git mv` of two files, **zero call-site edits in the app** (the package does not change), and the
  cost is 12 more strings, 12 drawables, 1 id and 1 749 lines of Studio UI sitting in a library that
  Joy Brush will not otherwise use. Purely a *surface area* cost, not a behaviour one — which is
  exactly the trade R23's "share, don't copy" makes.
- **(b) Extract `Kit` to a top-level `DrawerKit` in the kit and rename all 251 call sites in 12
  app files.** The visual language document's own extraction list already recommends this ("item 4:
  `TextOverlayDrawer.Kit` → `DrawerKit.java` as its own javadoc asks"), and it is the *right*
  long-term shape: a drawer that paints is not a drawer that builds controls. But it is a 251-site
  rename in Studio code, `FaditorEditorActivity`'s package, on a 27 000-line file — **app-file work
  under the serialised order**, and it deserves its own row with a T1 review.
- **(c) Give each panel its own private helpers.** A copy. **Ruled out** — it is the exact thing
  R23 exists to prevent, and it is how "preview and export start disagreeing" happens in this repo.

**I have implemented (a) provisionally and marked it**, because (a) is the only option that is
genuinely a *pure move* and the only one that touches zero app call sites. **If you prefer (b), say
so and this spec becomes two rows**: this one moves the five files that have no such problem
(`FxPanel`, `BlendPickerPopover`, `TextOverlayDrawer`, `RotationDialView`, + resources), and a new
D.02d does the `Kit` extraction first.

### Q2 — 🔴 for the Lead: `MaskKeyPanel` cannot move without dragging the Studio's media model

`MaskKeyPanel`'s constructor is `(Activity, Clip, Host)` and it reads and writes
`model.Clip` (2 552 lines), `model.CompositingSpec` (633), `model.ChromaKey` (129) and
`model.MaskAnimator` (212). **None of those is in D.05's list** — D.05 moved `model/BlendModes` and
`model/MaskSdf` and nothing else, and correctly so. So moving this panel honestly means moving
3 526 more lines of the Studio's own timeline model into a shared library, to serve a panel whose
downstream consumer (JB-2.23, layer masks on a `JbDocument`) uses **a different model entirely**.

Three ways:

- **(a) Move the four `model/` classes too.** Maximum surface, and it makes `:studiokit` depend on the
  Studio's clip model — which is the coupling R23 exists to *avoid*, not to enshrine.
- **(b) Give the panel a model-free seam**, which its own javadoc already half-argues for: *"this
  panel moved out instead: the activity now hands over a `Host` and gets smaller"*. The panel's real
  surface is `Host` (`onCompositingChanged`, `playheadMs`, `pickColorFromPreview`,
  `recordCompositingUndo`, `scheduleSave`, `saveNow`) plus a read/write of the compositing values.
  So: move the **view construction** and have the kit define a tiny `MaskTarget` interface
  (`readSpec()` / `writeSpec(…)`), with a 40-line adapter in the app. That is **not a pure move** —
  it is a real refactor of 552 lines — but it is the shape that makes the panel shareable, and it is
  the shape JB-2.23 would need anyway.
- **(c) Do not move `MaskKeyPanel` in this row.** It has **no downstream consumer**: R24 lists
  `D.05b` as "move `FxPanel`, `BlendPickerPopover`, `MaskKeyPanel`", but the only row that names a
  moved panel is **JB-2.21** ("UI = the Studio's `FxPanel`"). `BlendPickerPopover` is wanted by
  JB-2.20a/2.20b; `MaskKeyPanel` is wanted by nothing yet.

**My recommendation: (c) now, and (b) as its own row** (call it **D.05c**, T2 + T1 review, app-file
work under the serialised order) — because (a) makes the kit worse and (b) is a genuine refactor that
should not ride along inside a row whose whole value is that it is a *move*. **And (c) has a
second-order benefit**: it stops this row from being the place where the Studio's clip model quietly
becomes shared, which is a decision nobody took.

Decision 9 already handles the case where you have not ruled: the builder moves the four
unblocked files, commits, and stops.

### Q3 — for the Lead (small): 14 vector drawables into the kit, or an icon gap?

`ObjectDrawer` and `TextOverlayDrawer` read 14 drawables. Moving them is what Decision 6 of the
resource table says, and it is consistent (a library cannot see the app's drawables). The
alternative is that the kit declares an interface for them, which for fourteen icons is not worth a
new abstraction. **I have ruled "move them"; say the word if you would rather the panels keep icon
buttons that only work in the app.** Related, and the same shape of question: `R.attr.selectable` in
`FxPanel` is a **Material** attribute, so the kit needs `libs.material` as an `api` dependency, not
just an `implementation` one, or the panels will compile and then fail to resolve it for a consumer.

---

## Appendix A — the 124 string names the three panels and `ObjectDrawer` read

```
# FxPanel (56)
done faditor_coachmark_got_it faditor_fx_add faditor_fx_blend faditor_fx_curve_edit
faditor_fx_curve_edit_done faditor_fx_delete_look_confirm faditor_fx_edit_gradient
faditor_fx_empty_layer faditor_fx_empty_object faditor_fx_layer_only_left_out
faditor_fx_load faditor_fx_load_confirm faditor_fx_look_desc faditor_fx_look_name_hint
faditor_fx_look_save faditor_fx_needs_pass_body faditor_fx_needs_pass_title
faditor_fx_position faditor_fx_position_done faditor_fx_reorder_hint faditor_fx_save_look
faditor_fx_saved_looks faditor_fx_static_hint faditor_fx_straighten
faditor_fx_toast_keys_cleared faditor_fx_toast_load_failed faditor_fx_toast_saved
faditor_fx_vertices faditor_lc_fx_bypass faditor_lc_fx_collapse faditor_lc_fx_delete
faditor_lc_fx_enable faditor_lc_fx_expand faditor_lc_fx_reorder faditor_lc_key_diamond
faditor_lc_key_next faditor_lc_key_prev faditor_tool_opacity faditor_tools_edit
faditor_undo_fx_add faditor_undo_fx_apply_look faditor_undo_fx_blend
faditor_undo_fx_bypass faditor_undo_fx_clear_keys faditor_undo_fx_delete
faditor_undo_fx_enable faditor_undo_fx_key faditor_undo_fx_opacity
faditor_undo_fx_reorder faditor_undo_fx_straighten faditor_undo_fx_vertices
setting_off setting_on universal_cancel universal_delete

# BlendPickerPopover (33)
faditor_blend_add faditor_blend_color faditor_blend_color_burn faditor_blend_color_dodge
faditor_blend_darken faditor_blend_darker_color faditor_blend_difference faditor_blend_divide
faditor_blend_exclusion faditor_blend_group_color faditor_blend_group_comparative
faditor_blend_group_contrast faditor_blend_group_darken faditor_blend_group_lighten
faditor_blend_group_normal faditor_blend_hard_light faditor_blend_hard_mix faditor_blend_hue
faditor_blend_lighten faditor_blend_lighter_color faditor_blend_linear_burn
faditor_blend_linear_light faditor_blend_luminosity faditor_blend_multiply
faditor_blend_normal faditor_blend_overlay faditor_blend_pin_light faditor_blend_saturation
faditor_blend_screen faditor_blend_soft_light faditor_blend_subtract faditor_blend_title
faditor_blend_vivid_light

# MaskKeyPanel (23 — AND NOT ok / cancel, which are android.R's, Decision 4)
faditor_key_color faditor_key_enable faditor_key_eyedropper faditor_key_eyedropper_failed
faditor_key_section faditor_key_softness faditor_key_spill faditor_key_tolerance
faditor_mask_h faditor_mask_only_inside faditor_mask_remove faditor_mask_rotate
faditor_mask_round faditor_mask_section_shape faditor_mask_soften faditor_mask_title
faditor_mask_w faditor_mask_x faditor_mask_y lane_a_key_black lane_a_key_blue
lane_a_key_green lane_a_key_white

# ObjectDrawer (12)
```

The 12 `ObjectDrawer` names are deliberately **not** listed: the script is the authority (Decision 3)
and a hand-copied list is exactly the kind of thing that goes stale. Run the script, read its
reported set, and check the 12 against it.
