# JB-0.09 — The lobby room, and the first Joy Brush screen in Joy Creator's look

| | |
|---|---|
| **Tier** | T2-V (a design row: the look is the deliverable, and the owner has to see it) + T3 owner check |
| **Status** | ⛔ **BLOCKED, and blocked on two different things — the second one is mine to clear and the first one is not.** (1) **R14's held merge.** This is **app-file work**: it edits `LobbyFragment.java`, which is one of the six files left uncommitted in the working tree by earlier agent sessions and now sitting on the held branch `bunny/leftover-app-edits`. The Lead merges or drops that branch before any row may edit those files. (2) **The Lead's serialised app-file order.** ROADMAP §6: *"App-file work is serialised (Lead, 2026-09-29): `D.02a → D.02 → D.02c / D.05`, **one at a time.** Never two of these in the tree together, and never alongside `JB-0.09`."* **So JB-0.09 may not be dispatched until that whole sequence has finished.** The spec is written anyway (R12 says to), and everything below is decided and checkable. |
| **Needs** | 0.05 (`joybrush-android` exists and hosts the engine's view — Reviewed) · D.01 (`JbColors`, `jb_tokens.xml`, `tools/check_joybrush_tokens.py` — Reviewed) |
| **Owner area** | EDIT `app/src/main/java/com/fadcam/ui/lobby/LobbyFragment.java` — **`buildRooms()` and `enterRoom()` and `paintHero()` ONLY**, named line by line in Step 3 · EDIT `app/src/main/res/values/strings.xml` (the lobby strings, new names only) · EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (the overlay chrome only — `buildOverlays()`, `pill()`, `oval()`, `OVERLAY_FILL`, `OVERLAY_RING`; **nothing in the save/open/threading code**) · NEW `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JbChrome.kt` · EDIT `tools/check_joybrush_tokens.py` (one new check — see Q2) |
| **Estimated size** | ~40 lines in `LobbyFragment`, ~6 strings, ~260 lines of Kotlin + chrome in `joybrush-android`, ~60 lines added to the check script |
| **Command** | `python tools/check_joybrush_tokens.py` → `tokens in sync` **and** `no colour literal in joybrush-android`, exit 0. Then the watcher: `BUILD SUCCESSFUL` with `:joybrush-android:compileDebugKotlin` and `:app:compileDebugJavaWithJavac` EXECUTED. **There is no `jvmTest` for this row and that is deliberate** — see §Tests. |

> **Two of the owner's screens are in this row and one of them is the first time anybody sees
> Joy Brush's colour.** D.01's Q1 (open, never answered) asked for exactly this: *"the screenshot
> check cannot be satisfied by any builder… move 'confirm the room colour ripples' to JB-0.09."*
> **This row is where that promise is kept.** The two literals at the top of `JoyBrushActivity.kt`
> (`OVERLAY_FILL = 0x1AFFFFFF`, `OVERLAY_RING = 0x1FFFFFFF`) are the last hex values in Joy Brush, and
> this row deletes them.

## Goal

Two things, and they are one thing:

1. **A door.** A Joy Brush room in the lobby's carousel, with the room's own gradient, so the app
   Joy Brush lives in has a way in to it — and so the owner can find it without being told.
2. **The first screen in Joy Creator's look.** The Joy Brush screen today is a `GLSurfaceView` with
   plain white-on-10%-white pills floating on it. It becomes: the room gradient as a 3dp signature
   bar, pills and rings from the token set, a header that names the drawing, and ink that comes from
   `JbColors` — **so changing two hex values in `jb_tokens.xml` recolours the room, the screen and
   everything on it, with no other edit.** That is D.01's whole promise, and this is the row that
   makes it true or leaves it untested forever.

## Contract

### 1. The lobby room (verbatim, so nothing here is invented)

```java
// app/src/main/java/com/fadcam/ui/lobby/LobbyFragment.java — the existing type, pasted whole.
private static final class Room {
    final String title;
    final int gradA, gradB;
    /** Ink that reads on this room's gradient — black for light pairs, white for dark. */
    final int onGrad;
    final String tag;
    final String action;
    final String emptyCopy;
    /** Tab to route to, or -1 when the room opens an activity / has nowhere to go yet. */
    final int tab;
    final String glyph;

    Room(String title, int gradA, int gradB, int onGrad, String tag,
         String action, String emptyCopy, int tab, String glyph) { … }
}
```

The Joy Brush room, in full:

| Field | Value | Where it comes from |
|---|---|---|
| `title` | `getString(R.string.lobby_room_joybrush)` — **"Joy Brush"** | new string; hard-coded English in the app, like every other room |
| `gradA`, `gradB` | `JbColors.palette(context).roomStart`, `.roomEnd` | **D.01's `jb_room_start` / `jb_room_end`**, currently `#5C43FD → #4397FD` |
| `onGrad` | `Color.WHITE` | the same constant the Sprite and Avatar rooms use; §4.2 of the visual language says "white ink on it" |
| `tag` | `"NEW DRAWING"` | the room has no last item to name; see Decision 4 |
| `action` | `getString(R.string.lobby_act_new_drawing)` — "New drawing" | new string; `lobby_act_start` is "Start" and this is not a start |
| `emptyCopy` | `getString(R.string.lobby_empty_joybrush)` — "Draw something. It saves itself." | new string |
| `tab` | **`-1`** | the room opens an activity; there is no tab for it |
| `glyph` | `"brush"` | a ligature name in the existing icon style (`movie_edit`, `videocam`, `directions_run`, `accessibility_new`, `graphic_eq`) |

### 2. The Joy Brush chrome (new file, `:joybrush-android`)

```kotlin
package cc.joycreator.joybrush.android

/**
 * The Joy Brush screen's chrome, in Joy Creator's tokens. **Every colour in this file is a field of
 * [JbColors.Palette]; there is no colour literal and there is no `Context.getColor` call outside
 * [JbColors].** That is not a style preference — it is the promise D.01 made and the check script
 * now enforces, and the two hex constants this file replaces were the last two Joy Brush owned.
 *
 * Everything here is a plain `View` built in code, because `:joybrush-android` has no layout files
 * and adding some is a different row's decision. The shapes are the visual language's, not
 * invented: 40dp targets, 1dp `LINE` rings, 12sp/700 labels, a 3dp signature bar.
 */
object JbChrome {
    /** The 3dp bar under the header, in the room gradient. Identity, so a bar and never a button. */
    @JvmStatic fun signatureBar(context: Context, widthPx: Int): View

    /** A pill: `RAISED` fill, 1dp `LINE` ring, `INK` label, 40dp tall. The control, not an action. */
    @JvmStatic fun pill(context: Context, label: String, what: String): TextView

    /** A pill that IS the action: the room gradient fill and white ink. At most one per screen. */
    @JvmStatic fun actionPill(context: Context, label: String, what: String): TextView

    /**
     * The header: 44dp, the drawing's name in `INK` 13sp/800, a "last saved" moment in
     * `INK_FAINT`, and the close round button in the top-END corner.
     *
     * The name is the DOCUMENT's (`JbDocument.name`), not a file name and not a timestamp —
     * "the header names the thing you are making", which is what every other screen in the app does.
     */
    @JvmStatic fun header(context: Context, name: String, savedLabel: String): View
}
```

## Decisions

1. **The room is APPENDED, at index 5 — never inserted.** *Why, and this is the load-bearing one:*
   `LobbyFragment` addresses rooms **by index** in four places — `enterRoom()` has `idx == 2` for
   Sprite Lab and `idx == 3` for Avatar Studio, `paintHero()` has `rooms.indexOf(r) == 0` for "the
   last project" and `rooms.indexOf(r) != 4` for the Viz Lab's empty state, and `buildRooms()` is
   the only place a room is created. **Inserting Joy Brush at any index but the end would silently
   re-point three of those at the wrong room** — the Studio would open when you asked for a
   character, or Viz Lab would lose its empty state. Appending changes no index. And a new room at
   the end is one more word on a strip that **wraps** (`buildTitleRow` rotates by three and repeats
   the list so it can wrap), so nothing overflows.

2. **`gradA`/`gradB` come from `JbColors`, not from a `Studio.*` constant.** *Why:* the lobby is an
   app file and `Studio` is a Java class; `JbColors` is Kotlin in a module the app can see but
   `Studio` cannot be extended from `joybrush-android` without a copy. More importantly this is
   D.01's actual promise: the owner may change `jb_room_start` and `jb_room_end` **and nothing
   else**, and the room in the lobby is where that will be noticed first. The visual language's own
   table (§3.2) has no Joy Brush row yet, and this is the row that adds one — **Q1**.

3. **`onGrad` is `Color.WHITE`, the same constant the Sprite and Avatar rooms already pass, and NOT
   a new token.** *Why:* §4.2 says "white ink on it", and the three existing dark-gradient rooms
   already pass `Color.WHITE`. Inventing `jb_room_ink` would be a token with one value, used once,
   that the owner would then have to keep in step with the gradient — a second thing to change
   instead of one. (The *other* half of that rule is respected: `joybrush-android` still has **no**
   colour literal, and Decision 6's check is scoped to that module only, precisely so this one
   legitimate app-side use does not make the check a lie.)

4. **`tag` is `"NEW DRAWING"`, and the hero shows the room's own colour, not a project name.**
   *Why, and this one needed reading the code:* `paintHero()`'s `else` branch gives every non-Capture,
   non-Studio room `name = projects.get(0).name` and `sub = "IN <PROJECT>"` — because Sprite Lab,
   Avatar and Viz all live **inside a Faditor project**. **A Joy Brush drawing is not a project** (it
   is a `.joybrush` file in its own store, JB-0.08a/0.08b), so taking that branch would put
   *"IN MY VIDEO"* under a drawing that has nothing to do with that video. The honest branch is the
   one a room with nothing in it already takes: `hasContent = false`, `heroEmpty` visible with
   `emptyCopy`, and the hero art filled with the room gradient. **Q4** asks whether the hero should
   instead name the person's last *drawing*, which is the Sprite Lab shape and would need the working
   file's name plumbed into the lobby.

5. **`tab = -1` and `enterRoom()` gets a new `idx == 5` branch that starts `JoyBrushActivity` with
   no extras — and the existing fallback to the Studio stays as the last line.** *Why:* Joy Brush has
   exactly one screen and no project, so an `Intent` with no extras is the whole hand-off; the
   `try`/`catch` and the `routeTab(TAB_STUDIO)` fallback below it are the existing, tested behaviour
   and stay exactly as they are. `JoyBrushActivity` restores its own working file on launch
   (JB-0.08b), so "enter the room" and "carry on" are the same tap — which is why Decision 4's tag
   is honest.

6. **The check script gains a second check: no colour literal anywhere in `joybrush-android/`.**
   `tools/check_joybrush_tokens.py` already fails when a mirrored token drifts, so this is the same
   script doing the same kind of job. *Why:* D.01's rule "Joy Brush code must use ONLY `JbColors` —
   never a hex literal" was, until this row, a **convention with nothing enforcing it**, and the two
   constants at the top of `JoyBrushActivity.kt` are the proof: they were written *after* D.01
   landed. A rule that is only in a KDoc is a rule the next row breaks. The check is scoped to the
   module, so the two legitimate app-side uses (`LobbyFragment`'s `Color.WHITE`, the app's own
   `studio_tokens.xml`) do not make it a check that always fails. **Q2** asks the Lead to confirm
   the file is mine to extend.

7. **`JoyBrushActivity`'s two literals become tokens, and nothing else about the screen's
   behaviour changes.** `OVERLAY_FILL` (10% white) and `OVERLAY_RING` (12% white) are replaced by
   `JbChrome.pill(...)`, which fills with the token's `raised`/`line` pair over the picture.
   *Why (PROVISIONAL — I am ruling this, not the owner):* the visual language §1.5 gives "over
   picture" values and §1.8 gives a 1dp ring, and the app's own drawer uses `Kit.drawerFill` — but
   those are `CTL`-family values chosen for a **frosted drawer**, and a 40dp pill on top of someone's
   painting is not a drawer. The honest token answer is the one the file already models: pills that
   are *raised* surfaces with a *line* ring, at whatever alpha the token carries. **Q3** asks the
   owner whether pills over the picture should be frosted (the app's `FrostSource` path, which
   `androidkit` cannot do) or stay tinted, and this row ships tinted either way so the row is not
   blocked on the answer.

8. **The header shows the document's name and a "saved" moment, and the save moment is honest.**
   `JB-0.08b` already knows three states: `changes > 0` (unsaved work), `changes == 0` (all on
   disk), and a save that is **owed** because the pen is down (`saveOwed`). *Why:* the header is the
   one place a person looks to ask "is this safe?", and a header that says "Saved" while a stroke is
   still in their hand is the exact lie this app is built not to tell. So: **`saving owed` beats
   `unsaved` beats `saved`**, and there is no fourth state and no toast.

9. **One `actionPill` per screen, and on Joy Brush's first screen there is none.** *Why:* the visual
   language §1.4 is explicit that a gradient means ACTION or IDENTITY, and a gradient pill is
   reserved for the one thing you are here to do. On the drawing screen the one thing is *drawing*,
   and a "Start" button on a screen that is already drawing would be a lie. The room gradient
   appears as the 3dp signature bar, which is identity. (The *lobby's* hero action pill is a
   different screen and the app's own.)

10. **Every tappable thing gets a `contentDescription` AND a `TooltipText`, from the same string.**
    *Why:* the visual language's standing rule — "every tappable thing gets a hover label =
    TalkBack name = stylus/mouse tooltip" — marked **mandatory for Joy Brush (stylus-first)**. The
    existing pills in `JoyBrushActivity` already do this and it must survive the restyle: a
    restyle that drops a `contentDescription` is an accessibility regression dressed as a
    improvement, and it is invisible in a screenshot.

11. **The screen stays full-bleed and the overlay still steps aside of the cutout.** The existing
    `goFullScreen()` and its `OnApplyWindowInsetsListener` are not touched, and the new header
    lives **inside** the padded overlay, so the safe-area arithmetic stays in one place. *Why:* the
    punch-hole is where a header's title goes to die, and the current code is careful about it; a
    restyle that re-implements insetting is a regression with a good screenshot.

12. **No new strings in `joybrush-android`.** The chrome's labels come from the caller, as they do
    today ("Undo", "Redo", "Clear", "Save a copy…", "Open…", the brush pill). *Why:* a first
    screen's worth of labels is a localisation decision, and inventing six new keys in a module
    with no `res/values/strings.xml` is how a module ends up with a half-localised UI. **Q5.**

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (append, never insert) | `joyBrushIsTheLastRoomAndNoExistingIndexMoved` — the room list's first five entries are the same five `Room`s, in the same order, with the same fields, and Joy Brush is `rooms.get(5)` |
| 2 (gradient from `JbColors`) | `theRoomsGradientIsTheTokenAndNotAStudioConstant` — read from the source: the Joy Brush `Room` line contains `roomStart` and `roomEnd` and **no** `Studio.ROOM_*` and no hex |
| 3 (`Color.WHITE`, not a token) | `theRoomInkIsTheSameConstantTheOtherDarkRoomsUse` — the same literal expression appears in the Sprite, Avatar **and** Joy Brush `Room` constructions |
| 4 (honest hero) | `theHeroShowsTheRoomsOwnColourAndNotAProjectName` — with one project loaded and the Joy Brush room active, `heroName` is empty, `heroSub` is empty, `heroEmpty` is `VISIBLE` with the empty copy, and `heroTag` is `GONE` |
| 5 (`tab = -1`, a new branch) | `enteringTheRoomStartsJoyBrushWithNoExtras` — a fake `Context` records the intent: `component == JoyBrushActivity`, `extras.isEmpty()`, and the existing `routeTab(TAB_STUDIO)` line is still the last statement in the method |
| 6 (the new check) | `theCheckFailsOnAColourLiteralInJoyBrush` — add `0xFF00FF00` to any file under `joybrush-android/`; the script exits 1 naming the file and the line, and exits 0 again when it is removed |
| 7 (the two literals go) | `joyBrushActivityHasNoColourLiteral` — the file's text contains no `0x` colour constant and no `Color.WHITE` outside `JbChrome`'s call sites |
| 8 (honest save state) | `theHeaderSaysSavingOwedBeforeItSaysUnsaved` — with `saveOwed = true` and `changes = 0` the label is the "saving" one; with `saveOwed = false, changes > 0` it is "unsaved"; with both clear it is "saved". **Three states, counted, and a fourth fails** |
| 9 (no action pill) | `theDrawingScreenHasNoGradientFilledAction` — the chrome builds **0** `actionPill`s and exactly **1** `signatureBar` |
| 10 (label = description = tooltip) | `everyControlHasOneLabelUsedThreeTimes` — for each control the built in the overlay, `text`, `contentDescription` and the tooltip are **the same string object**; a control with an empty `contentDescription` fails |
| 11 (insets untouched) | `theOverlayStillInsetsItselfAndTheCanvasIsStillFullBleed` — `goFullScreen` and the `OnApplyWindowInsetsListener` are byte-identical to the landed file (asserted by diffing the method bodies against the committed version) |
| 12 (no new strings) | `joyBrushAndroidDeclaresNoNewResource` — `joybrush-android/src/main/res/` gains **no** `strings.xml` entry, and `JbChrome.kt` contains no `R.string` |

## Tests

**This row has no `jvmTest`, and that is a deliberate decision rather than an omission.** Everything
in Decisions 1–5 and 7–11 is a property of a **`View` hierarchy and an app `Fragment`** — there is
no `JbDocument`, no canvas and no arithmetic to pin, and a `Robolectric` harness does not exist in
this repo. Inventing one to test a gradient is the wrong trade. So:

1. **`tools/check_joybrush_tokens.py`, extended.** Two named checks, both exit-code assertions, both
   with the failure message pasted into the row:
   - `tokens in sync` — the existing 24-mirror check, **unchanged**, still failing on a drift.
   - `no colour literal in joybrush-android` — **NEW.** Walks `joybrush-android/src/main/**` and
     fails on any `0x[0-9A-Fa-f]{6,8}` outside a comment, and on `Color.parseColor(`,
     `Color.rgb(`, `Color.argb(`. Reports `file:line` and the literal. Scoped to that module **only**
     (Decision 6). Non-vacuity: add one to any file in the module and paste the failure.
2. **`tools/check_joybrush_tokens.py`, second non-vacuity case:** change one mirrored value by hand
   and paste the mirror failure, then revert. Both halves of the script must be seen failing.
3. **A source-level assertion set, run by hand and pasted**, in the same spirit as JB-3.08's case 19
   and JB-4.03a's key-listing test. Each is a `grep` whose **expected answer is a count**:
   - `grep -c "rooms.add(" LobbyFragment.java` → **6** (was 5).
   - the five pre-existing `Room` constructor argument lists → **byte-identical** to the landed file
     (paste a `git diff` of that hunk: **empty**).
   - `grep -n "idx == " LobbyFragment.java` → `2`, `3`, **5**, and nothing else renumbered.
   - `grep -c "0x" joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt`
     → **0**.
   - `grep -rn "R.string" joybrush-android/src/main/` → **no new file** (Decision 12).
4. **The owner's 📱 check, which is the real test and the one D.01's Q1 is waiting for.** On the
   Note 9: the lobby shows a sixth room wearing indigo→blue; tapping it opens Joy Brush; the screen's
   header names the drawing and the pills are tinted, not white; draw one stroke, leave, come back —
   the drawing is there. **Then the D.01 proof, which no builder can fake:** set
   `jb_room_start` to `#FF0000`, rebuild, and confirm that **the lobby room's gradient AND the
   Joy Brush signature bar** both turn red — and nothing else in the app changed. Revert.
   *That* is D.01's screenshot, three rows late, and it is the row's reason for existing as a
   separate row from the chrome.
5. **Screenshots attached** (R6's spirit): the lobby with the room selected, the Joy Brush screen as
   it stands, and the red-token proof. A T2-V row that ships no picture has not been reviewed.

**Command:** `python tools/check_joybrush_tokens.py` → both lines, exit 0. **Never gradle on the
owner's PC** (START_HERE rule 3) — the watcher builds.

## Steps

1. **Do not start until the ROADMAP's serialised app-file order has finished** (`D.02a → D.02 →
   D.02c / D.05`) **and** the Lead has merged or dropped `bunny/leftover-app-edits` (R14). If either
   is outstanding, set the row `⛔ Blocked`, quote this header, and stop. **Do not ask the owner to
   commit, stash or resolve anything** (R14).
2. `JbChrome.kt` first, and `JoyBrushActivity.kt`'s `buildOverlays()` rebuilt on it: `pill()` and
   `oval()` come from `JbChrome`, the two `OVERLAY_*` constants are **deleted**, and the header is
   added inside the existing padded overlay. Every control keeps its `contentDescription` (Decision
   10) — moving a label is the easiest way to lose one.
3. `LobbyFragment.java`, in this order and **no further**:
   - `buildRooms()`: append the one `Room` (Decision 1's table). Nothing above it changes.
   - `enterRoom()`: the new `idx == 5` branch, before the existing `catch`. Nothing above it
     renumbers.
   - `paintHero()`: the Joy Brush branch (Decision 4), **added as a new `else if`** so the existing
     `else` — which the Sprite, Avatar and Viz rooms all use — is untouched.
   - the six new strings in `app/src/main/res/values/strings.xml`.
   **If any of those four edits needs a fifth, stop and put it in Questions.** `LobbyFragment.java`
   is 107 KB and R14 holds a review of the owner's uncommitted changes to it.
4. `tools/check_joybrush_tokens.py`: add the second check (Decision 6). Do not touch the first.
5. Run both checks; run the count-based assertions in Tests 3 and paste them.
6. The owner's check (Tests 4 and 5) — **this row is not done without it.** D.01's Q1 explicitly
   moved this proof here.

## Do not

- **Do not insert the room anywhere but the end.** Four places in `LobbyFragment` address rooms by
  index. One insertion and the Studio opens when you tap Avatar. Decision 1, and the empty diff on
  the other five `Room` constructions is the proof.
- Do not add a `TAB_JOYBRUSH`, a new tab, a floor word, a Joybot destination or a search entry.
  A room is a room; the tabs and the floor are a different screen's shape.
- Do not put a hex literal, `Color.WHITE`, `Color.parseColor` or a `Paint` colour anywhere in
  `joybrush-android`. Decision 6's check exists so this is not a promise.
- Do not change `jb_room_start` / `jb_room_end` to anything. The owner owns those two lines, and the
  red-token step in the Definition of done **reverts**.
- Do not touch the save / open / autosave / threading code in `JoyBrushActivity.kt` — the
  `saveAsync`, `saveWorkingFile`, `restoreWorkingFile`, `openFrom` and `fileIo` blocks are
  JB-0.08b's and are under a blocked cross-review. **This row restyles the overlay and nothing else**,
  and Decision 8 *reads* `saveOwed` / `changes` rather than writing them.
- Do not add layout XML, a theme, a `styles.xml` or a font to `joybrush-android`. The chrome is
  built in code, as it is today.
- Do not move `JbColors`, `Palette` or `jb_tokens.xml`. D.01 owns them and they are reviewed.
- Do not draw the room's gradient over the hero's whole surface. `LobbyFragment`'s own comment is
  emphatic: the first version tinted the person's footage green, and *"a front door that recolours
  your work is worse than a plain one."* The gradient fills the hero **only** in the empty state,
  which is the existing behaviour and the one Decision 4 selects.
- Do not run gradle. Do not touch the other five R14 leftover files.

## Definition of done

- [ ] **`bunny/leftover-app-edits` merged or dropped (R14), and the serialised app-file order
      finished — ticked with the ROADMAP row, not just claimed here**
- [ ] `python tools/check_joybrush_tokens.py` pasted: `tokens in sync` **and**
      `no colour literal in joybrush-android`, exit 0
- [ ] both non-vacuity failures pasted (mirror drift, and a literal added to the module)
- [ ] the count-based assertions pasted (6 rooms, empty diff on the other five, `idx == 2/3/5`,
      zero `0x` in `JoyBrushActivity.kt`, no new resource)
- [ ] watcher `build.log` pasted: `BUILD SUCCESSFUL` with `:joybrush-android:compileDebugKotlin` and
      `:app:compileDebugJavaWithJavac` EXECUTED
- [ ] `git status --short` shows only the owner-area paths
- [ ] **owner check, Note 9:** the sixth room, the tapped room opening Joy Brush, draw → leave →
      return, and **the red-token proof** (lobby room *and* signature bar both turn red, nothing
      else changes, then reverted)
- [ ] screenshots attached: lobby with the room selected, the Joy Brush screen, the red-token proof
- [ ] committed `JB-0.09: lobby room and first screen chrome`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The chrome and the room are decided
and every decision is checkable. The block is real and it is not mine to clear; the rest are small.)_

### The block, in the Lead's words rather than mine

**⛔ This row is blocked twice over and I have not tried to work around either.**

1. **R14's held merge.** `LobbyFragment.java` is one of the six app files an earlier session left
   uncommitted; they are on `bunny/leftover-app-edits` waiting for the Lead to merge or drop them.
   **Until that merge lands, this row's one app file is a file with somebody else's uncommitted
   changes in it, and editing it is exactly the merge conflict R8's procedure exists to avoid.**
2. **The Lead's serialised order.** ROADMAP §6: *"`D.02a → D.02 → D.02c / D.05`, one at a time. Never
   two of these in the tree together, and never alongside `JB-0.09`."* So even with the merge done,
   **this row may not be dispatched until that sequence is finished** — and `JB-2.01` (screen chrome)
   and `JB-2.03a` and `JB-2.06b` all sit behind it, so this row is on the critical path for four
   other rows. If the Lead wants the runway back, **this is the row to unblock first**, and the
   cheapest version of that is the four `joybrush-android` steps (2 and 4), which touch **no app
   file** and could be split into their own row by whoever schedules it. **Say so and I will write
   that row.**

### Q1 — for the Lead: the visual language has no Joy Brush room row, and this row is where it gets one

`JOYBRUSH_VISUAL_LANGUAGE.md` §3.2 tabulates the owner's **final** room mapping (2026-09-17) and Joy
Brush is not in it — unsurprising, it is a new room. §4.2 does give the pair
(`#5C43FD → #4397FD`, "white ink on it"), and D.01 implemented it as `jb_room_start/end` with the
owner's permission to change them. So the spec is consistent with both documents and the room will
look right.

Two things I did **not** decide:

- **The pair `#5C43FD → #4397FD` is the Remote floor word's pair** (§3.2, marked "nowhere" — Remote
  has no token on purpose). So the first Joy Brush room in the carousel wears a gradient that a
  different part of the app already associates with something else, and §3.1's rule is that **any two
  ADJACENT wheel colours gradient cleanly** — indigo → bright blue *is* adjacent, so it is correct by
  the wheel. It is not *distinct* by the wheel, though. My instinct is that it is fine and that the
  owner will change the pair the first time he sees it, which is the entire design of D.01.
- **Which slot on the wheel Joy Brush takes** in §3.2, so the document stops being out of date.
  Cosmetic, and it is the owner's table.

### Q2 — for the Lead: this row edits `tools/check_joybrush_tokens.py`, which is D.01's owner area

D.01's owner area names that script by path, and its row is `🟩 Reviewed (xr)`. Adding a check to a
reviewed row's file is a small thing and a legitimate one (D.01's own Decision 4 created the script
to be extended), but it means D.01's file changes after its review, so **it needs your nod and a
re-review of the script** — or, if you would rather it stay untouched, the check goes in a new
`tools/check_joybrush_no_literals.py`. **I have ruled "extend the existing script"** because two
scripts guarding one module's colours is worse than one script with two checks, and because the
existing script already has the "read the XML, compare, exit 1" shape this check needs.

### Q3 — for the owner: should the pills over your drawing be frosted, or tinted?

Decision 7 ships **tinted** pills with a 1dp token ring. The app's own drawers can go **frosted**
(`ObjectDrawer`'s `FrostSource` path, and the visual language §1.5 has the values for it), and frost
is what makes chrome disappear behind artwork in the rest of Joy Creator. **`androidkit` /
`joybrush-android` cannot do it today** — the blur lives inside `ObjectDrawer`, which is trapped in
the editor package and is not in this row's owner area. So: tinted now, and if the owner says frost,
that is either a `RUNTIME_EFFECT` blur on the overlay (a few lines, no Studio coupling, slightly
different look) or a follow-up row after D.05 moves `ObjectDrawer` into the kit. **I have not
designed the frost**, because "tinted" is buildable and "frost" is a decision.

### Q4 — for the Lead: should the lobby's Joy Brush room show the person's LAST DRAWING?

The Sprite room's hero says *"LAST SHEET"* and names it, and that is the shape a person expects from
a room they have used. Joy Brush has a working file (`getExternalFilesDir("joybrush")/current.joybrush`,
JB-0.08b) and the screen restores it on launch — so the information exists, and Decision 4's
"NEW DRAWING / empty" hero is the *first-run* state being used as the *permanent* one.

Showing the last drawing needs the lobby to read a file in Joy Brush's private store and know its
name — i.e. the lobby learns about Joy Brush's storage, which is a coupling in the direction R23 does
not want. **Options: (a) empty hero (ruled); (b) the last drawing's name, read from the archive;
(c) "NEW DRAWING" as the tag and no name until documents are a real thing (JB-0.15's future).** I
lean (a) now and (b) when there is more than one drawing, because with exactly one working file
"carry on" and "start" are the same tap and the hero is telling the truth either way.

### Q5 — for the owner (small): the drawing screen's labels are English literals in code, and this row does not change that

"Undo", "Redo", "Clear", "Save a copy…", "Open…", the brush pill, the diagnostics — all hard-coded in
`JoyBrushActivity.kt` today, because the module has no `res/values/strings.xml`. This row restyles
the chrome and **adds no strings** (Decision 12), so the situation is unchanged rather than fixed.
A first screen that is going to be looked at every day is the right place to start doing it
properly — and `JbChrome` is the seam, because every label already arrives as a `String` argument.
**It is a separate row's worth of work and I have kept it out of a row that is already blocked.**
