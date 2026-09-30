# JB-3.00 — Create a board of any `BoardKind`, and wire `activeBoardId`

| | |
|---|---|
| **Tier** | **T2** for the whole `core/` half (this is where the row lives) · **T2-V** for a 90-line popover · **T3** for the device check. **The phone wiring is ⛔ Blocked — see Q1.** |
| **Status** | Draft spec (written 2026-09-30, spec writer `openrouter/stealth/space-bunny-alpha`) |
| **Depends on** | JB-2.01 (chrome, **Built today**), JB-0.02 (`DocModel`/`DocOps`/`DocJson`, Built), JB-3.01 (`AnimOps`, Built), JB-2.04 (layer column, Built). **Soft-depends on JB-0.08c** (`DocMerge` — written tonight, ⚪ Outline, not landed) — see Q2. |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardOps.kt` · (2) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BoardMenu.kt` · (3) NEW `.../core/commonTest/.../doc/BoardOpsTest.kt` · (4) NEW `.../core/commonTest/.../chrome/BoardMenuTest.kt` · (5) NEW `.../core/jvmTest/.../doc/BoardOpsNoSecondTruthTest.kt` · (6) `joybrush-android/.../chrome/BoardPopoverView.kt` (NEW) · (7) `joybrush-android/.../JoyBrushActivity.kt` — **exactly 3 edit sites = 3 contiguous changed runs = 3 `@@` hunks**, table at §Step 3 · **NOTHING ELSE.** In particular **NOT** `DocOps.kt`, **NOT** `DocModel.kt`, **NOT** `AnimOps.kt`, **NOT** `JbCanvasView.kt` (R30 item 2), **NOT** `GlPaintEngine.kt` (R30 item 2), **NOT** `FilmStrip.kt`, **NOT** any Gradle or build file, nothing in `app/`. |
| **Estimated size** | ~210 lines `:core` (of which ~90 are KDoc), ~150 lines chrome, **3 runs** in the Activity. |

> **The finding this row was opened for, verified independently — and one correction.**
> Spec writer: `openrouter/stealth/space-bunny-alpha`, 2026-09-30. **Every claim below was checked by
> reading the file it is attributed to.** I ran no gradle and no git.

---

## 0. Verification of the finding that opened this row

The four-point claim, checked one at a time. **Three hold exactly; one holds in substance with a stale
line number, and one of them (claim 2) is wrong in a way that matters for this spec.**

| # | Claim | Verdict | How I checked |
|---|---|---|---|
| **1** | `ROADMAP.md`'s Phase 3 table has no row that creates a board | **CONFIRMED** | Read `tasks/joybrush/ROADMAP.md` §Phase 3 (lines 228–251) and §Phase 4 (252+). Every board-adjacent row is a *consumer*: 3.01 model, 3.02 paper, 3.03 strip, 3.04 onion, 3.05 playback, 3.06 export, 4.01/4.02 sprite, 7.01/7.04 puppet/character. **The only row that creates one is JB-3.00 itself.** |
| **2** | `JbCanvasView.kt:876` calls `DocOps.newDocument(...)`, which builds `BoardKind.CANVAS` | **CONFIRMED IN SUBSTANCE · LINE NUMBER WRONG** | `DocOps.kt:36` is `kind = BoardKind.CANVAS` — the only `BoardKind` written in `DocOps.kt`. The call site is **`JbCanvasView.kt:1173`**, not `:876`; the file is 1367 lines. `:876` is inside `load`. **A stale anchor, inherited by JB-3.02b Q2 and copied into the ROADMAP row.** Use `:1173`. |
| **3** | A repo-wide grep for `BoardKind.ANIMATION` hits only `core/`, tests and `JbColors.kt:66` | **CONFIRMED, and slightly stronger than claimed** | `rg 'BoardKind\.(ANIMATION\|SPRITE\|PUPPET\|CHARACTER\|CANVAS)'` over the whole repo excluding `.claude/`, `worktrees/`, `build/`: every hit is in `joybrush/core/src/commonMain`, `joybrush/core/src/commonTest`, `joybrush/core/src/jvmTest`, `joybrush/androidkit/src/test`, or `joybrush-android/.../JbColors.kt:65-69`. **The only hit in `joybrush-android` outside tests is `JbColors.kt:66`**, inside `boardGradient` (`:62-72`) — a `when` with **no production caller at all** (`rg boardGradient` finds the definition and three spec mentions, zero call sites). **`joybrush-android/src/main` has no test source set**, so nothing in it can be JVM-tested here. |
| **4** | `JoyBrushActivity.kt` contains no `JbDocument` | **CONFIRMED** | `rg 'JbDocument\|activeBoardId\|boards'` over the 1613-line file returns **zero matches**. The Activity never holds a document: `beginSave` receives one from `canvas.snapshot { contents -> … }` (`:1336`) and hands it straight to `JbArchive.save` (`:1342`). |

### 0.1 The load-bearing one: **JB-3.08 is dead code, and I read the file to prove it**

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/ThreeFingerSwipe.kt`, lines **122–127**, verbatim:

```kotlin
    private fun frameCountOf(doc: JbDocument): Int {
        val active = doc.activeBoardId ?: return 0
        val board = doc.boards.firstOrNull { it.id == active } ?: return 0
        if (board.kind != BoardKind.ANIMATION) return 0
        return board.frames.size
    }
```

- **`:125` is the `kind` check; `:126` is `board.frames.size`.** The check comes **first** — that order is
  load-bearing and is asserted by JB-3.08's mutation 5 (`SwipeFrames.commit` reordering them goes red).
- Every document the app can produce has exactly **one** board, of kind `CANVAS` (`DocOps.kt:36`). So `:125`
  returns 0 for all of them, `frameCountOf` is **always 0**, `automatic()` (`:113-114`) compares `0 >= 2`
  and is **always `SwipeMode.BRUSH`**, and the `FRAMES` half of `Step` (`:133`) and of `stepFrames` (`:243`)
  is unreachable in production.
- `rg ThreeFingerSwipe` over every `*.kt`: the class is constructed **only inside
  `ThreeFingerSwipeTest.kt`** (16 sites). **Nothing in production constructs it.** So the `activeBoardId`
  read at `:76`, `:98` and `:123` has **no production caller anywhere** — a ✅ Built row reading a
  document field that no production path can satisfy.
- One correction to JB-3.02b Q2: it says `activeBoardId` is "set once, in `newDocument`". That is true of
  the *factory*, but `JB-0.08c`'s `DocMerge.carried` (spec `:444-455`) **also writes it**, and JB-0.08c is
  ⚪ Outline. See Q2 — the two rows both want that line and neither has landed.

**So JB-3.08 is not merely unprovable on a phone. It is unreachable on any document this codebase can
currently construct, and it is unreachable in production because nothing constructs it at all.** This row
is what makes the predicate answerable, and §Decision 9 says exactly what becomes reachable.

### 0.2 What the row is, in one paragraph

Give the screen **a document it holds**, give `core` **the algebra of boards** (`BoardOps`: add, name,
activate, delete — pure, testable, no Android), and give the phone **one popover**: tap a board to go to
it, ＋ to add one of a chosen kind. The engine is untouched, so the phone shows the new board's *rect*
and the same pixels until JB-3.02b/3.03 land; this row's job is to make the **document** true and the
**choice** available, and to unblock nine rows' core halves tonight.

---

## 1. Goal

A person can add a board of a chosen kind to the drawing they are in, see which board they are on, and
come back to it — and every row that reads `activeBoardId` finally has a phone that can make that field
mean something.

Today the answer is "there is no such thing". The document is manufactured at save time inside
`JbCanvasView.readContents` from the GPU's layer stack, and it is always one `CANVAS` board. Nothing in the
tree has ever built an `ANIMATION` board, so `ThreeFingerSwipe`'s frame-flip branch is dead, the film
strip has no board to scrub, onion skin has no frames to ghost, and playback has nothing to play. **One
thin row fixes that; nine rows stop being dead ends.**

---

## 2. Contract (verbatim)

Everything below was read out of the landed file named. **Nothing is recalled.**

### 2.1 `core/doc/DocModel.kt` — the board and the document (lines 61–79, 161–171)

```kotlin
@Serializable enum class BoardKind { CANVAS, ANIMATION, SPRITE, PUPPET, CHARACTER }

@Serializable data class Frame(
    val id: String,
    val holdFrames: Int = 1,                // shown for this many ticks of the board's fps
)

@Serializable data class SpriteGrid(val cols: Int, val rows: Int, val cellW: Int, val cellH: Int)

@Serializable data class Board(
    val id: String,
    val name: String,
    val kind: BoardKind,
    val rect: RectPx,
    val clipToBoard: Boolean = false,
    val fps: Float = 12f,                   // ANIMATION only
    val frames: List<Frame> = emptyList(),  // ANIMATION only, in play order
    val grid: SpriteGrid? = null,           // SPRITE only
)

@Serializable data class JbDocument(
    val format: String = DOC_FORMAT,
    val version: Int = DOC_VERSION,
    val id: String,
    val name: String,
    val paper: Paper = Paper(),
    val boards: List<Board>,
    val layers: List<Layer>,                // bottom -> top
    val activeLayerId: String? = null,
    val activeBoardId: String? = null,
)
```

`DOC_FORMAT = "joybrush.document"` (`:6`), **`DOC_VERSION = 3`** (`:8`), `RectPx(x, y, w, h)` (`:37`).

### 2.2 `core/doc/DocOps.kt` — the four rules a new board must satisfy

Read in full (211 lines). What constrains a board:

| Rule | Line | What it demands of a board |
|---|---|---|
| 2 | `:64` | `out += duplicateIds(doc.boards.map { it.id }) { "two boards are both called \"$it\"" }` — **board ids unique.** |
| 3 | `:71-75` | `b.rect.w <= 0 \|\| b.rect.h <= 0` → `"board \"${b.id}\" has no room"`. |
| 4 | `:78-88` | **ANIMATION only:** `frames` non-empty; frame ids unique; every `holdFrames >= 1`; `b.fps !in 1f..60f` refused. |
| 5 | `:91-98` | **SPRITE only:** `grid` non-null, and `cols/rows/cellW/cellH >= 1`. |
| 9 | `:163-165` | `activeBoardId`, if set, names a board in the document. |
| 11 | `:182-183` | `doc.boards.isEmpty()` → `"this document has no boards"`; same for layers. |

`newDocument` (`:24-51`) sets `activeBoardId = boardId` (`:49`) and names the board **`"Board 1"`**
(`:35`) with `kind = BoardKind.CANVAS` (`:36`) and `rect = RectPx(0, 0, w, h)` (`:37`).

**Which kinds are constructible today, with no change to anything:**

| Kind | Constructible? | What it needs |
|---|---|---|
| `CANVAS` | **yes, today** | nothing. This is `newDocument`'s board. |
| `ANIMATION` | **yes, with arguments** | `frames` non-empty (rule 4). `JB-3.01` Q2 ruled `addFrame(BLANK)` works on an empty board and "is how such a board is made into a board"; `AnimOps.addFrame` (`AnimOps.kt:209`) is the landed way in. `fps` default 12f is inside 1..60. |
| `SPRITE` | **yes, with arguments** | `grid` non-null (rule 5). `SpriteGridMath.bySize(rect, cellW, cellH)` is the landed maths; `SpriteBoard.fitted(grid)` (`:57-61`) resizes the rect to the grid. |
| `PUPPET` | **yes, trivially** | no rule in `validate` mentions it. |
| `CHARACTER` | **yes, trivially** | no rule in `validate` mentions it. |

**So every one of the five kinds is constructible today with the model as it stands. Nothing in
`DocModel.kt` changes and no `DOC_VERSION` bump is required** — see Decision 3.

### 2.3 `core/doc/AnimOps.kt` — the frame entry point (the ONE that exists)

`AnimOps.addFrame` (`AnimOps.kt:209`), reached through `FilmStrip.add` (`FilmStrip.kt:214-218`, verbatim
`): AnimResult = AnimOps.addFrame(doc, boardId, afterFrameId, mode, ids)`), signature:

```kotlin
fun addFrame(doc: JbDocument, boardId: String, afterFrameId: String?, mode: NewFrame, ids: () -> String): AnimResult
```

with `enum class NewFrame { BLANK, DUPLICATE, LINK }` (`:44-56`) and
`data class AnimResult(val doc: JbDocument, val work: List<CelWork>)` (`:33`).

Its refusals, verbatim from the KDoc at `:204-208`: an unknown board, a board that is not
`[BoardKind.ANIMATION]`, an `[afterFrameId]` that is not a frame of this board, `DUPLICATE`/`LINK` on a
board with no frames, an animated layer with no cel for the source frame, and **an id the document is
already using**.

**This row does not reimplement any of that. It calls `addFrame` for the seed frames** (Decision 4), and
it does so through `AnimOps`, not through a private copy.

### 2.4 The chrome that exists today (the affordance must live in it)

`joybrush-android/.../chrome/`, all Built 2026-09-30, **none of it drafted**:

| File | What this row uses from it |
|---|---|
| `Popovers.kt` (171 lines) | `show(content: View, anchor: View, side: Side = Side.BESIDE, widthDp: Float = 0f, onClosed: (() -> Unit)? = null)` (`:36`); `enum class Side { BESIDE, BELOW, ABOVE }` (`:20`); `close()` (`:99`); `isOpen` (`:26`); `topInsetPx` (`:29`). **One panel at a time** — `show` calls `close()` first (`:37`). |
| `ChromeKit.kt` (79 lines) | `dp`/`dpi` (`:25-26`), `surface(view, radiusDp)` (`:37`), `chromeSurface` (`:49`), `p: Palette` (`:22`), `ink(alpha)` (`:29`), `label(v, text)` (`:56` — **hover label = TalkBack name = tooltip at once**), `TOUCH_DP = 40f` (`:73`). |
| `LayerColumnView.kt` (379 lines) | **The precedent for a board list.** `interface Host { … }` (`:38-45`), `show(s: LayerStack, maxLayers: Int, maskEditing: Boolean = false)` (`:80`), `pageAspect` (`:61`), `WIDTH_DP = 60f` / `CELL_W_DP = 44f` / `PLUS_DP = 44f` (`:373-377`), `plus.setOnClickListener { host.addLayer() }` (`:74`), and the `corner(c, s, x, baseline, left)` pill helper (`:280-287`). Its KDoc at `:33` is the house rule this row copies: **"The column only reports; the canvas owns the stack."** |
| `Popovers`' catcher | a tap outside closes and is **CONSUMED** (`:40`), "so closing a panel never also leaves a dot on the drawing". |

`JoyBrushActivity.kt` today, read at the anchors this row needs:

| Anchor | What is there |
|---|---|
| `:63` | `import cc.joycreator.joybrush.core.doc.BlendMode` — the last `core.doc` import |
| `:206` | `private var columnOpen = false` |
| `:241` | `private val ui = Handler(Looper.getMainLooper())` |
| `:412-413` | `layersBtn = TopButton(kit, JbIcon.LAYERS, "Layers")` … then `val more = TopButton(kit, JbIcon.MORE, …)` |
| `:422` | `topButtons.addAll(listOf(home, undoBtn, redoBtn, pinBtn, layersBtn, more))` |
| `:633-681` | `private fun moreMenu(anchor: View)` — the ⋯ popover. `menuRow(text, label, dot = null, action)` at `:742-763` is the row builder; `popovers.show(box, anchor, Popovers.Side.BELOW, widthDp = 240f)` at `:680`. |
| `:676` | `box.addView(menuRow("Put everything back", …))` — the last non-destructive row, and the line above the destructive one |
| `:796-809` | `toggleChrome()` — four fingers hide the chrome; it calls `popovers.close()` (`:798`) and lists `topBar, strip, hairline, column` (`:799`) |
| `:813-829` | `private val columnHost = object : LayerColumnView.Host { … }` — the precedent for a `Host` object |
| `:1336-1351` | `canvas.snapshot { contents -> … }` inside `beginSave` — the ONLY place a document exists on this screen |

**1613 lines total**, verified by reading the file.

### 2.5 `JbColors.boardGradient` — the one thing that already knows all five kinds

`joybrush-android/.../JbColors.kt:61-72`, verbatim, and **it has no production caller**:

```kotlin
    @JvmStatic
    fun boardGradient(context: Context, kind: BoardKind): GradientDrawable {
        val p = palette(context)
        val stops: IntArray = when (kind) {
            BoardKind.CANVAS -> intArrayOf(p.boardCanvasStart, p.boardCanvasEnd)
            BoardKind.ANIMATION -> intArrayOf(p.boardAnimationStart, p.boardAnimationEnd)
            BoardKind.SPRITE -> intArrayOf(p.boardSpriteStart, p.boardSpriteEnd)
            BoardKind.PUPPET -> intArrayOf(p.boardPuppetStart, p.boardPuppetEnd)
            BoardKind.CHARACTER -> intArrayOf(p.boardCharacterStart, p.boardCharacterEnd)
        }
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, stops)
    }
```

**An exhaustive `when` with no `else`** — so a sixth `BoardKind` is a *compile error here*, not a silent
fallback. That is the R3-adjacent property D.01's review called out, and it is why this row can offer all
five kinds with **zero new colour tokens** and zero edits to `JbColors.kt` or `jb_tokens.xml`.
The blueprint's rule (OWNER_CONSTRAINTS, 2026-09-28: *"Boards wear the colours of what they connect to"*)
is already paid for.

---

## 3. Decisions already made

**D1 — The affordance is a ＋ row inside the existing ⋯ popover (`moreMenu`), and the boards themselves
are a second popover. Not a top-bar button.**
*R30 item 1 makes every new top-bar pill expensive and the file is serialised. A ＋ row in a popover that
already exists costs one line in `moreMenu` and reuses `Popovers` wholesale. The nine blocked rows are
about documents, not about chrome real estate; a seventh top-bar button is not what unblocks them.*

**D2 — The popover is a `Popovers` panel, not a new always-on strip.**
`LayerColumnView` is an always-on column because layers are used constantly; boards are not, and a
permanent board bar would eat the canvas on a phone (`OWNER_CONSTRAINTS`: *"Panels are small and local:
drawers on a phone, small drop-downs/popovers on a larger screen, with little or no other change"*).
One panel at a time is already guaranteed by `Popovers.show` → `close()` (`Popovers.kt:37`).

**D3 — `activeBoardId` is DOCUMENT state and it is ALREADY a serialised field. There is no new field and
no `DOC_VERSION` bump. This is the single most load-bearing decision in the row, and it is the opposite of
what the row was opened expecting.**
`JbDocument.activeBoardId` **already exists** (`DocModel.kt:170`, `@Serializable`, nullable, default
`null`). It is already validated (`DocOps.kt:163-165`). It is already in `JB-0.02d`'s exhaustive
key-walk's known set (`JB-0.02d_refuse_unknown_keys.md:176` lists `activeBoardId` as a root key), so a
file carrying it is **not** refused. **R31 therefore does not apply: this row adds no key and no enum
constant, so there is nothing for `DocJson.decode` to refuse and no version to bump.**
*What changes is the **owner**: today the field is written by a factory (`DocOps.kt:49`) and by
`JB-0.08c`'s not-yet-landed `DocMerge` (`:444-455`). This row gives it a third writer and makes it MOVE
during a session — which is the point. `DOC_VERSION` stays **3**; a builder who bumps it has invented a
contract change that does not exist.*
**This is the Lead's to ratify (Q3), because "the active board is session state that happens to be saved"
is a real contract statement, and the alternative — a second, unsaved field — is what the owner
constraints forbid.**

**D4 — A new board is created with its kind's arguments already filled, in one call, and the ANIMATION
seed frames go through `AnimOps.addFrame` — never through a private copy of it.**
So: `addBoard(doc, ANIMATION, …)` produces a board that `DocOps.validate` is **already happy with**
(rule 4 needs frames; JB-3.01 Q2 is explicit that a frame-less animation board is *not* a board). The
seed is **`NEW_FRAMES = 2`** — see §Numbers. `SPRITE` gets a `1 × 1` grid over the board's own rect, which
is what `SpriteGridMath.bySize(rect, rect.w, rect.h)` returns and what keeps the rect unmoved (Decision
in T7). `PUPPET`/`CHARACTER` need nothing: no `validate` rule mentions them.
*Rationale: a board that is born invalid is a board the archive refuses at save time
(`JbArchive.write` calls `DocOps.validate` and throws on any problem, `JbArchive.kt:183-186`), and a
person who adds a board and cannot save is the worst thing this row could ship.*

**D5 — Exactly one board may be active, always, and adding a board activates it.**
This is `LayerStack`'s rule, not a new one: `LayerStack.add` (`:45-51`) inserts above the active layer
**and makes it active**, and `LayerStack`'s `init` (`:28-32`) `require`s that the active id is in the
stack. `BoardStack` is the same shape for the same reason: the person should never have to ask "which
board am I on?" after making one. `DocOps` rule 9 enforces the invariant from the other side.

**D6 — `BoardStack` is never empty, exactly like `LayerStack` — so DELETING THE LAST BOARD IS REFUSED,
not answered.**
`LayerStack.delete` returns `null` for the last layer (`:66-73`) with the words *"Refused (null) for the
last layer: a drawing always has somewhere to paint."* `DocOps` rule 11 says a document with no boards is
not a document. `AnimOps.deleteFrame` refuses the last frame in the same words (`AnimOps.kt:311-314`).
**Three landed precedents, one rule.**

**D7 — Deleting the board you are on moves the active board to the nearest surviving board, by
`LayerStack.delete`'s exact rule: the board BELOW, or the one ABOVE if there was none below.**
`LayerStack.kt:71` verbatim: `val active = if (id != activeId) activeId else list[(i - 1).coerceAtLeast(0)].id`,
then `.normalized()`. **Copied rule, not a new one** — so the two columns behave identically and there is
one thing to learn. This is the answer to "what is active if a person deletes the board they were on", and
it is **not open.**

**D8 — What deletion does to layers animated in that board: `BoardOps.deleteBoard` REFUSES, in words,
naming the layer. It does not repair.**
`DocOps` rule 7 (`:113-117`) would then fire (`"layer … animates on board … which is not in this
document"`), and `AnimOps` states the doctrine it would break: *"It never repairs a broken document
quietly and it never hands back a half-applied one"* (`AnimOps.kt:74-76`), and *"Guessing is not
available and repairing is not allowed"* (`:85-86`). Un-animating the layer first is a one-liner in a
later row; guessing here is how a person loses frames. **The refusal costs one message; the alternative
costs their frames.** This is the same trade `deleteFrame` made when it refused to delete the last frame.

**D9 — What becomes reachable for JB-3.08, precisely.**
With `activeBoardId` naming a board of kind `ANIMATION` with **2** frames: `frameCountOf` passes `:125`
(the `kind` check, first, unchanged) and returns `board.frames.size` = **2** at `:126`;
`0 >= MIN_FLIPPABLE_FRAMES` becomes `2 >= 2`, so `automatic()` (`:113-114`) answers **`FRAMES`**;
`badge(doc)` (`:75-80`) answers `FRAMES`; `begin` (`:183-201`) latches `FRAMES` and does **not** build a
`SizeOpacityDrag` (the `if (mode == SwipeMode.BRUSH)` arm at `:195` is skipped, `brush` stays `null`);
`move` routes to `stepFrames` (`:213`) and a swipe of `±STEP_DP × dp` flips a frame. **The `kind`-check-
before-count order at `:125-126` is untouched by this row and is not re-derived anywhere** — the new
predicate lives in `BoardStack`, and a test asserts the two agree.
**What this row does NOT make reachable:** the *phone* has no way to flip a frame yet. `ThreeFingerSwipe`
is constructed nowhere in production (verified, §0.1) and the finger stream does not reach it. That is
JB-3.08's own wiring, and `JbCanvasView.kt` is R30 item 2. **This row makes the predicate answerable; it
does not make the gesture happen.**

**D10 — The engine is not touched, so a new board on the phone shows the SAME PIXELS on a NEW RECT until
a later row can do better. This is stated on the popover, in words, rather than hidden.**
`JbCanvasView` holds one board's pixels and `refusalFor` refuses any file with more than one board
(`:1079-1085`). A new board therefore cannot have its own artwork on the phone yet, and saying so in the
popover is the honest thing. **A board that quietly showed the old board's art as its own would be a lie
the person discovers at export.** See Q4 — this is a product statement and it is the Lead's.

**D11 — The board list is a `core` value (`BoardStack`), not a `View`, and the `View` is a shell.**
House pattern, verified: `LayerStack` (`core/layers/`) is pure data with `add`/`delete`/`move`/`rename`/
`nextName`/`freshId`; `LayerColumnView` is the Android shell over it. `BoardStack` is the same file for the
same reason, and it is why the algebra in §Decision 3–8 is JVM-testable at all.

**D12 — The ⋯ row says how many boards there are, so the affordance is never invisible.**
`LayerColumnView`'s ＋ prints `"${s.size}/$max"` under the cross (`LayerColumnView.kt:162`) — *"The
budget, always on screen: the limit is visible before it is reached."* The ⋯ row is
`"Boards (2)"` with the accessibility label naming the active board. A row that says only "Boards" on a
document with one board teaches nobody anything.

**D13 — The board name is `"Board N"`, one more than the highest N in use — `LayerStack.nextName`'s rule,
word for word.**
`LayerStack.kt:121-124` verbatim, including the `NAME = Regex("Layer (\\d+)")` `matchEntire` and the
`?: 0` fallback. So a drawing with "Board 1" offers "Board 2", delete it, and it offers "Board 2" again.
**A number that never repeats is not a memory aid.**

**D14 — Ids come from the caller, in one fixed shape, and an id already in the document is REFUSED.**
`DocModel.kt:31-32`: *"Every id is a String. UUIDs are generated by the CALLER (this module has no platform
uuid)."* `AnimOps` draws **the new frame first, then one cel per animated layer** (`AnimOps.kt:196-201`),
with *"Ids are drawn in a fixed SHAPE … so a failure names the same id the code used"*, and refuses a
repeat against a **flat** id set (`usedIds`, `:633-644`: *"Deliberately stricter than `DocOps.validate` …
One flat rule beat four namespaces to remember"* — JB-3.01 Q4). **`BoardStack.add` follows both: the board
id first, then its seed frames in play order, and it uses the same flat-set rule.**

**D15 — `BoardStack` does NOT fold fps, clipToBoard, grid resizing or frame holds. It is the board LIST.**
Those are `AnimOps.setHold` (`:368`), `SpriteBoard.withGrid`/`fitted` (`SpriteBoard.kt:42`, `:57`) and
JB-4.01's, all landed and all reviewed. **A row that re-implements a landed operation to save a line is
how two copies of one number get in** — the exact failure JB-3.08's own mutation 18 guards.

---

## 4. Numbers, derived

Every constant this row introduces, with the arithmetic. **A builder must not compute any of these.**

| Name | Value | Derivation |
|---|---|---|
| `NEW_FRAMES` | **2** | `DocOps.kt:79` refuses an ANIMATION board with no frames, so ≥1. `ThreeFingerSwipe.MIN_FLIPPABLE_FRAMES = 2` (`ThreeFingerSwipe.kt:312`) is the smallest count that makes the context-aware swipe answer `FRAMES` at all. **1 leaves the feature dead; 2 is the least that works.** This is also the number JB-3.08's badge predicate turns on, so it is pinned by a test rather than left to taste. |
| `NEW_FPS` | **12f** | `Board.fps`'s own default (`DocModel.kt:76`), written nowhere else. `DocOps.kt:87` allows `1f..60f`, so 12 is legal. **Not a new number: it is the model's.** |
| `MAX_NAME` | **40** | `LayerStack.MAX_NAME = 40` (`LayerStack.kt:137`). Two lists, one name budget. |
| Seed name | `"Board N"`, `N = max + 1` | `LayerStack.nextName` (`LayerStack.kt:121-124`) verbatim. `N` is parsed with `Regex("Board (\\d+)").matchEntire(...).groupValues[1].toIntOrNull()`; a board named "Board" or "Board x" contributes nothing, and the fallback is `0` so the first is `1`. |
| Board ids | `"board-N"`, `N ≥ 1`, skipping taken | `LayerStack.freshId` (`:127-131`) verbatim in shape: *"An id not in the stack: "layer-N"."* Starts at `boards.size + 1` and increments while taken. |
| Frame ids | `"frame-N"`, `N ≥ 1`, skipping taken | Same rule in the same namespace as `AnimOps`' frames. **The generator is the CALLER's** (`ids: () -> String`) exactly as `AnimOps.addFrame` and `DocOps.newDocument` take it — so a test passes a counting lambda and gets `["board-2", "frame-1", "frame-2"]`. |
| Seed frame ids | `"$boardId-f1"`, `"$boardId-f2"` | Read out of the caller's generator, **in the order the generator is asked**, per Decision 14. With the counting lambda that is literally `frame-1`, `frame-2`. |
| Seed grid | `SpriteGrid(cols = 1, rows = 1, cellW = rect.w, cellH = rect.h)` | `SpriteGridMath.bySize(rect, cellW = rect.w, cellH = rect.h)` is whole cells that exactly fill the rect — one cell. `SpriteBoard.fitted(grid)` (`:57-61`) then computes `SpriteGridMath.fitRect(rect, grid)`, which for a 1×1 grid over the rect is the rect: **a new sprite board does not resize itself.** Test 7 pins it. |
| `boardRect` | the ACTIVE board's rect, verbatim | A new board lands where the person is working. `DocOps` rule 3 (`DocOps.kt:71-75`) only requires `w > 0 && h > 0`, and the active board's rect already satisfies it. **`x`/`y` are carried too** — a board at `(-1400, 300)` is a real case (`PaperGeometryTest.kt:541`). |
| Popover width | `240f` dp | `moreMenu` already uses `widthDp = 240f` (`JoyBrushActivity.kt:680`). The board popover is the same width, so the two panels are the same object to the eye. |
| Row min height | `40` dp | `menuRow` sets `minHeight = dp(40)` (`JoyBrushActivity.kt:755`); `ChromeKit.TOUCH_DP = 40f` (`:73`) is the same number for the same reason. |
| Board-row height | `44` dp | `LayerColumnView.PLUS_DP = 44f` (`:375`) — a cell in a column. The board popover is a list, so its rows are cells, not menu rows. **Two heights, two jobs: a menu row is a command, a board cell is a target.** |
| Corner pill | `2` dp radius, `9` dp tall, `4` dp side pad | `LayerColumnView.corner` (`:280-287`) verbatim: `kit.dp(3f)` radius, `baseline - kit.dp(9f)` to `baseline + kit.dp(1.5f)`, `x - w - kit.dp(4f)`. Copied, not invented. |
| Active ring | `2f` dp stroke, `p.stateSelected` | `LayerColumnView.kt:227-235` verbatim (`drawRoundRect` inset by `kit.dp(2f)`, radius `kit.dp(7f)`). *"A state colour is always a ring, never a fill"* (`ChromeKit.kt:61`). |
| Badge | `10f` dp radius, `p.stateLive` | **PROVISIONAL** — the one visual number with no landed precedent for a *count badge*. `LayerColumnView` has no badge. Rounded to 10 dp and marked; see Q5. |
| Panel radius | `14f` dp | `Popovers.place` uses `kit.surface(this, 14f)` (`:48`). The sheet uses 16 (`:82`); this is a popover, so 14. |

---

## 5. Contract — the new `core` file, in full

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardOps.kt` — **NEW**. This is the
whole of it; a builder writes this file and its tests, and the phone is a shell over it.

```kotlin
package cc.joycreator.joybrush.core.doc

/**
 * The board list and the active board (JB-3.00), as PURE document maths.
 *
 * The sibling of `core/layers/LayerStack.kt`, for the same reason and with the same rules: a stack is
 * immutable, pure, and every operation returns a new one, so the screen, the undo stack and the tests
 * all speak about the same value. **The GL engine holds one board's pixels and knows nothing about
 * this** — a new board is a document fact today (Decision 10), and this object is where that fact is
 * made, checked and moved.
 *
 * **Why this is a NEW object and not a change to `DocOps`.** `DocOps.newDocument` is Built and
 * cross-reviewed, and its KDoc (`:17-23`) states its contract in the owner's words: *"A new document:
 * one CANVAS board at (0,0,w,h) named "Board 1", one PAINT layer "Layer 1"."* Changing it would change
 * what every caller in the tree gets. **An additive sibling in the same package is the whole of the
 * change**, and `JB-0.08c` has already set that precedent with `DocMerge` (also new, also in
 * `core/doc/`). `DocOps.kt` is not edited by this row — not one line.
 *
 * Every operation returns a document `DocOps.validate` is **happy with**, or refuses in words with a
 * `DocException` naming the thing that was wrong. It never repairs a broken document quietly, for
 * `AnimOps`' reason (`AnimOps.kt:74-76`): *"It never repairs a broken document quietly and it never
 * hands back a half-applied one."*
 *
 * WHAT THIS DOES NOT DO, because each of these is somebody else's landed operation and a second copy
 * of a number is how two things drift apart: it does not fold a frame's hold (`AnimOps.setHold`), it
 * does not write or fit a grid (`SpriteBoard.withGrid` / `.fitted`, JB-4.01's), it does not resize a
 * rect, and it does not touch `fps`.
 */
object BoardOps {

    /**
     * The board list, bottom-of-the-list first, and which board is active.
     *
     * **Never empty**, exactly as `LayerStack` is never empty (`:28-32`): there is always a board to be
     * on, and a document with no boards is one `DocOps.validate` rule 11 refuses. So deleting the last
     * board is refused rather than answered.
     *
     * The three `require`s are `LayerStack`'s three, in its order and in its idiom, because they are
     * the same three facts: ids are unique, the list is not empty, and the active board is in the list.
     */
    data class BoardStack(val boards: List<Board>, val activeId: String) {

        init {
            require(boards.isNotEmpty()) { "a document always has a board" }
            require(boards.map { it.id }.toSet().size == boards.size) { "board ids are unique" }
            require(boards.any { it.id == activeId }) { "the active board is in the list" }
        }

        val active: Board get() = boards.first { it.id == activeId }
        val activeIndex: Int get() = indexOf(activeId)
        val size: Int get() = boards.size

        fun indexOf(id: String): Int = boards.indexOfFirst { it.id == id }
        operator fun get(id: String): Board? = boards.firstOrNull { it.id == id }

        /** The board of kind [kind] the person is on, or null. One question, one answer. */
        fun activeOfKind(kind: BoardKind): Board? = active.takeIf { it.kind == kind }

        /** Every board of [kind], in list order. */
        fun ofKind(kind: BoardKind): List<Board> = boards.filter { it.kind == kind }

        /** The board an operation is allowed to act on: the one called [boardId], refused in words. */
        fun board(boardId: String): Board = this[boardId]
            ?: throw DocException("this document has no board \"$boardId\"")

        /**
         * [id] is the active board. An id not in the list changes nothing, exactly as
         * `LayerStack.select` (`:42`) — *"An id not in the stack changes nothing."*
         */
        fun select(id: String): BoardStack = if (this[id] == null) this else copy(activeId = id)
    }

    /** A new board of [kind] over [rect], and the document with it at the END of the list. */
    fun addBoard(
        doc: JbDocument,
        kind: BoardKind,
        rect: RectPx,
        name: String? = null,
        ids: () -> String,
    ): JbDocument

    /** [doc] with [stack], and nothing else touched. The one door a host needs. */
    fun withStack(doc: JbDocument, stack: BoardStack): JbDocument

    /** The stack of [doc]. Refuses a document with no boards, in words (rule 11). */
    fun stackOf(doc: JbDocument): BoardStack

    /** "Board N" over [boards]: one more than the highest N already used. `LayerStack.nextName`'s rule. */
    fun nextName(boards: List<Board>): String

    /** A new board of [kind] over [rect], and the stack it goes into. */
    fun add(stack: BoardStack, kind: BoardKind, rect: RectPx, name: String? = null, ids: () -> String): BoardStack

    /** Without [id]. Refused for the LAST board. The active board moves by `LayerStack.delete`'s rule. */
    fun delete(stack: BoardStack, id: String): BoardStack?

    /** [id] renamed, trimmed to [MAX_NAME]; a blank name keeps the old one. */
    fun rename(stack: BoardStack, id: String, name: String): BoardStack

    /** A new board id: "board-N", N from `boards.size + 1`, skipping any that is taken. */
    fun freshId(boards: List<Board>): String

    /** The frame count the context-aware swipe turns on: the active board's, and 0 for anything else. */
    fun activeFrameCount(doc: JbDocument): Int

    companion object {
        /** Seed frames on a new ANIMATION board. See §Numbers: 1 leaves JB-3.08 dead. */
        const val NEW_FRAMES = 2

        /** The model's own default fps, named once so this file does not restate `12f`. */
        const val NEW_FPS = 12f

        /** A board name is at most this long. `LayerStack.MAX_NAME`. */
        const val MAX_NAME = 40

        private val NAME = Regex("Board (\\d+)")
    }
}
```

### 5.1 The five behaviours, in words, so there is nothing left to decide

1. **`addBoard(doc, kind, rect, name, ids)`** builds the document the same way `newDocument` does — the
   `ids` lambda is called for the board id, and then for each of `NEW_FRAMES` frame ids, **in that order**
   (Decision 14) — and returns `doc.copy(boards = … , activeBoardId = newId)`.
   - `name` null → `nextName(doc.boards)`.
   - **`fps` is `NEW_FPS` for every kind**, so the field is never a default-that-moved. It is only *read*
     for `ANIMATION` (`AnimOps.playableFps`, `AnimOps.kt:438-440`), and setting it for a `CANVAS` board is
     harmless and keeps one construction path. **Say this in the KDoc**, or a reviewer will file it as a
     second copy of a default.
   - `frames` = `NEW_FRAMES` `Frame(id = …, holdFrames = 1)` for `ANIMATION`; **`emptyList()` for every
     other kind.** A `CANVAS` board with frames would satisfy rule 4's *letter* (it is gated on
     `ANIMATION`) and break `frameStartsMs` for a board `playableSchedule` refuses — the JB-3.01 review's
     finding 3, exactly.
   - `grid` = `SpriteGrid(1, 1, rect.w, rect.h)` for `SPRITE`; **`null` for every other kind** (rule 5 is
     gated on `SPRITE`, and a grid on a non-sprite board is a document nothing can describe).
   - `clipToBoard` = `false` — the model's own default, and `DocMerge` carries the *existing* board's
     value (JB-0.08c `:452`), so this only ever applies to a brand-new board.
   - **Refuses**, in words, a `rect` with `w <= 0 || h <= 0` (rule 3, `DocOps.kt:71-75`) — the same guard
     `newDocument` has at `:25`, and for the same reason (*"a caller making a document is a program, and
     should hear about it at once"*).
   - **Refuses** an `ids` generator that hands back an id this document already uses **anywhere** —
     `AnimOps.usedIds`'s flat set (`AnimOps.kt:633-644`), not `DocOps.validate`'s per-namespace rule
     (JB-3.01 Q4: *"One flat rule beat four namespaces to remember"*).
   - **Refuses** a document that is already broken in a way this cannot fix, rather than making it
     differently broken. **Which ways: exactly the ones `addBoard` could otherwise leave behind** —
     it makes no change to `layers`, so it introduces none.

2. **`delete(stack, id)`** returns `null` for an unknown id **and for the last board** (Decision 6), and
   otherwise removes it and moves the active board by `LayerStack.delete`'s rule (Decision 7):
   `val active = if (id != activeId) activeId else list[(i - 1).coerceAtLeast(0)].id`.
   **`deleteBoard(doc, id)` is the `doc`-shaped twin and it is the one a host calls**, because a host
   holds a document. It refuses, in words, a board **a layer animates in** (Decision 8), naming the
   layer: `"board \"$id\" cannot be deleted yet: layer \"${l.id}\" animates on it"`. It returns a
   `JbDocument`, never null — **a refusal is an exception, not a null**, because every caller of this is
   a program (D8, D6). (`BoardStack.delete` returns `null` because that is `LayerStack.delete`'s own
   shape and the popover asks "can I?" — a *view* question. Two shapes, two callers, both named.)

3. **`rename(stack, id, name)`** — `name.trim().take(MAX_NAME)`; empty keeps the old name
   (`LayerStack.rename`, `:104-107`, verbatim: *"a blank one keeps the old name (a layer with no name
   cannot be found in a list)"*).

4. **`activeFrameCount(doc)`** — the ONE place the "is there anything to flip" question is asked in this
   row, and it is **`ThreeFingerSwipe`'s predicate, re-derived in `core` and pinned against it by a
   test** (Decision 9, and JB-3.08's T8b discipline: re-derive, then assert the two agree, because a
   hand-written second copy of a predicate is how the JB-2.20a parity failures happened).
   It reads: `activeBoardId ?: return 0`; the board, or `return 0`; **`kind != ANIMATION` → `return 0`**;
   `board.frames.size`. **The order is `ThreeFingerSwipe`'s order (`:125` before `:126`) and it is
   load-bearing** — the same finding JB-3.08's mutation 5 guards.
   **It is a `BoardOps` function and NOT a `ThreeFingerSwipe` change:** `ThreeFingerSwipe` is Built,
   xr-cleared, 23 tests, and its whole reason for existing is the *gesture*. This row does not touch it
   and does not need to.

5. **`withStack(doc, stack)` / `stackOf(doc)`** — the two ends of the seam. `stackOf` **refuses a
   document with no boards** in words (rule 11), never returns an empty stack, because
   `BoardStack`'s `init` cannot represent one and a `require` firing deep in a constructor is a worse
   message than a refusal at the door.

---

## 6. Steps

> **Anchor convention (the same form as JB-0.08c, so a reviewer can COUNT rather than believe).** An
> **edit site** is *one contiguous run of changed lines* — which is exactly one `@@` hunk in
> `git diff -U0`, and exactly one row of the table. The "run today" column is the inclusive line range
> that changes, in the file **as it stands today (1613 lines)**. A line or two of drift from somebody
> else moving code is fine and the range still identifies the site; **the hunk count must be 3.**

### Step 1 — the `core` half, first, and it is the whole of the row's value

**1a.** Write `BoardOps.kt` (§5) **verbatim**, into
`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardOps.kt`.

**1b.** Write `BoardOpsTest.kt` into
`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardOpsTest.kt`, from §Tests
cases **T1–T14**. **`commonTest`, not `jvmTest`** — this file opens nothing, and `commonTest` is the
source set `jvmTest` actually runs (only the JVM target is enabled; JB-0.08c `:461-464` states this
rule and R44 is why).

**1c.** Write `BoardMenu.kt` (§7) and `BoardMenuTest.kt` (**T15–T19**).

**1d.** Write `BoardOpsNoSecondTruthTest.kt` into
`joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/doc/BoardOpsNoSecondTruthTest.kt`
(**T20–T24** — the census tests; they read files, so they are `jvmTest`).

**1e.** **Stop here and report.** Everything above is `core`-only, touches no app file, and is the part
that unblocks nine rows' core halves. The app wiring (Step 3) is **⛔ Blocked on Q1** — do not start it.

### Step 2 — the chrome shell (only after Q1 is answered)

**2a.** `joybrush-android/.../chrome/BoardPopoverView.kt` — **NEW**, a `LinearLayout` in the style of
`LayerColumnView` (which is itself a `LinearLayout(kit.context)`, `:36`). A ＋ row, then one cell per
board. **Each cell paints a 2 dp identity stripe in `JbColors.boardGradient(context, board.kind)`'s two
stops** (§2.5) — the rule from `OWNER_CONSTRAINTS`: *"Boards wear the colours of what they connect to …
so a board is recognisable before you have read its name."* No new token, no `JbColors.kt` edit.
The `corner(...)` pill (`LayerColumnView.kt:280-287`) shows the board's own summary; the active cell wears
`p.stateSelected` as a **ring** (`ChromeKit.kt:61`).
`interface Host { fun selectBoard(id: String); fun addBoard(kind: BoardKind); fun deleteBoard(id: String) }`
— **the column only reports; the canvas owns the stack** (`LayerColumnView.kt:33`).

**2b.** `BoardMenu.kt` (`core/chrome/`) is what the *menu row* asks: which kinds to offer, what the row
says, and what each cell's corner says. **Pure, so the wording is JVM-testable** — the accessibility
label is not a device-only string here, and `ChromeKit.label` (`:56`) makes it three things at once
(hover / TalkBack / tooltip), so a wrong one is a wrong three times.

### Step 3 — ⛔ the app wiring, **3** edit sites, gated on Q1

**`JoyBrushActivity.kt`. Nothing outside this table.** Do not reorder, do not tidy a neighbouring line,
do not fix anything you notice — the file is R30's lock.

| # | Run today | Anchor | What |
|---|---|---|---|
| **A1** | insert at `:64` | after `import cc.joycreator.joybrush.core.doc.BlendMode` (`:63`), before `import cc.joycreator.joybrush.core.layers.BlendNames` (`:64`) | three imports, as one contiguous block: `cc.joycreator.joybrush.core.doc.Board`, `cc.joycreator.joybrush.core.doc.BoardKind`, `cc.joycreator.joybrush.core.doc.BoardOps`. **Nothing else** — `RectPx` is used only inside `core`; `JbContents` is already imported (`:53`). |
| **A2** | insert at `:677` | after `box.addView(menuRow("Put everything back", …))` (`:676`) and before the two comment lines at `:677-678` | **one line**: `box.addView(boardRow(anchor))`. The whole row is built by `boardRow` in A3, because `menuRow` is a *private member of this Activity* (`:742`) and `core` may not hold a `View`. **The two comment lines at `:677-678`, the `"Clear drawing"` row at `:679` and the `popovers.show` at `:680` do NOT change** — that is what keeps this ONE run and not three. The board row carries **no red dot**: adding a board destroys nothing, and `stateDestroy` is reserved for the row below it (`:679`). |
| **A3** | insert at `:682` | after the closing `}` of `moreMenu` (`:681`), before the `openSettings` KDoc at `:683` | **one new region, pasted in ONE go**, verbatim in §7.4: the `// ── boards ──` banner, the two state fields, and the six functions `boardRow(anchor)`, `boardPanel(anchor)`, `reopen(anchor)`, `addBoard(kind)`, `selectBoard(id)`, `removeBoard(id)`. **If you paste this in two goes you get 4 hunks and the count is wrong.** |

**Total: 3 edit sites = 3 contiguous changed runs = 3 `@@` hunks.**

**3 numbered sites = 3 contiguous changed runs = 3 `@@` hunks**, in file order: A1 `:64` · A2 `:677` ·
A3 `:682`. The mechanical check is in §Definition of done. **If the `@@` count comes out at 2, two sites
have merged; at 4, one has split. That is the thing to look at, and it is the only thing the count is
for.**

**The four boundaries that keep the three runs apart** — each is a line a "helpful" builder would tidy,
and each tidy merges two sites into one: the imports at `:62` (`SaveQueue`) and `:65` (`LayerBudget`);
`:676`'s "Put everything back" row; and the closing `}` of `moreMenu` at `:681`.

**And the two places a builder will think a fourth and fifth site are needed, which they are not:**

- **No `moreBtn` field, and `:413-414` is NOT touched.** `moreMenu` is declared
  `private fun moreMenu(anchor: View)` (`:633`) — **the anchor is already a parameter**, so `boardRow`
  and `boardPanel` take it as one and nothing reaches into `buildOverlays` for it. A first pass at this
  table declared a `moreBtn` field and claimed four sites; **that was wrong and would have cost two more
  runs**, because a field declared beside `layersBtn` (`:204`) and a use at `:413` are 200 unchanged lines
  apart and can never be one run.
- **`toggleChrome()` at `:796-809` is NOT changed.** It already calls `popovers.close()` at `:798`, so the
  board popover closes with the chrome for free. **Verify that; do not add it.**

### Step 4 — what this row does NOT do, so a builder does not "helpfully" do it

- **It does not make the board survive a save.** The screen's boards are a `MutableList<Board>` in the
  Activity and `JbCanvasView.readContents` builds a **fresh** document at snapshot time from the GPU
  (`JbCanvasView.kt:1173`). **The next autosave writes one `CANVAS` board and the boards the person added
  are gone.** That is not a bug in this row, it is the **seam**: the fix is `DocMerge` (JB-0.08c), which
  is written and not landed. **Q2 is therefore not academic — it decides whether this row's feature works
  at all**, and the honest answer is that the row is *dispatchable and useful* without it (the nine rows'
  core halves unblock tonight) and *not shippable as a feature* until it lands.
- **It does not touch `JbCanvasView.kt` or `GlPaintEngine.kt`** (R30 item 2, the Lead's, a different lock
  order). Not one line, not a patch, not a "two-line move".
- **It does not make the gesture happen.** `ThreeFingerSwipe` is constructed nowhere in production
  (§0.1) and the finger stream does not reach it (`JbCanvasView.kt`, R30 item 2). JB-3.08's wiring.
- **It does not rename or reflow the top bar** and does not add a seventh `TopButton` (D1).
- **It does not open the door on a new drawing** — that is the lobby's (JB-0.09) and it is a document
  creation, not a board creation.

---

## 7. The chrome contract

### 7.1 `core/chrome/BoardMenu.kt` — NEW, pure, JVM-testable

```kotlin
package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind

/**
 * What the ⋯ row says, and what a board cell says (JB-3.00). Pure strings over pure values, so the
 * WORDING is testable — and the wording is three things at once, because `ChromeKit.label` (`:56`)
 * makes one string the hover label, the TalkBack name and the tooltip. A wrong label is a wrong three
 * times, and none of the three can be checked on a JVM.
 *
 * `LayerColumnView` is the precedent: the column prints its budget under the ＋ (`:162`) so the limit is
 * visible before it is reached, and every cell is labelled (`:189`).
 */
object BoardMenu {

    /** Every kind, in the order a person meets them: the drawing, then the animation, then the rest. */
    val KINDS: List<BoardKind> = listOf(
        BoardKind.CANVAS, BoardKind.ANIMATION, BoardKind.SPRITE, BoardKind.PUPPET, BoardKind.CHARACTER,
    )

    /** The kind as a person reads it. `ANIMATION` → `"Animation"`, never `"ANIMATION"`. */
    fun kindName(kind: BoardKind): String

    /** The ⋯ row's text: `"Boards"` with the count, `"Boards (2)"` once there is more than one. */
    fun rowLabel(boardCount: Int): String

    /**
     * The ⋯ row's accessibility label, naming the ACTIVE board: what a person who cannot see the row
     * needs in order to know where they are. Never blank — a blank `contentDescription` is an
     * invisible control to TalkBack.
     */
    fun rowDescription(boards: List<Board>, activeId: String?): String

    /** A board cell's corner pill: the board's own summary, or `""` for a kind with nothing to count. */
    fun corner(board: Board): String

    /** One board cell's accessibility label: the name, the kind, and the count. Never blank. */
    fun cellDescription(board: Board, active: Boolean): String
}
```

**The five kinds' words, decided (a builder does not choose these):** `CANVAS` → **"Canvas"** ·
`ANIMATION` → **"Animation"** · `SPRITE` → **"Sprite"** · `PUPPET` → **"Puppet"** ·
`CHARACTER` → **"Character"**. Sentence case, matching `BlendNames.name`'s spelling style
(`core/layers/LayerNames.kt:29+`).

**`corner(board)`, decided:** `ANIMATION` → `"${board.frames.size} frames"`; `SPRITE` → the grid as
`"${g.cols}×${g.rows}"` with `×` (U+00D7) — one character, no spaces, because the pill is
`corner(...)`-narrow (`LayerColumnView.kt:274` prints `"100%"` and a blend abbreviation in the same
space); `CANVAS`/`PUPPET`/`CHARACTER` → **`""`**, and an empty string means the pill is not drawn at
all. **Never `"0 frames"`: a cell with no count is cleaner than a cell lying.**

**`cellDescription`, decided:** `"<name> — <kindName>, active"` for the active cell and
`"<name> — <kindName>"` for the rest, with the corner's count appended when there is one. Em dash
(U+2014) with spaces, which is the separator `LayerColumnView.kt:189` already uses
(*"Layer — tap to paint on it…"*).

### 7.2 What the popover shows, and what one tap does — the whole of the design

- The ⋯ menu gains **one row**, between `"Put everything back"` and the two red-dot rows
  (`JoyBrushActivity.kt:676-679`): **`"Boards"`**, or **`"Boards (2)"`**, wearing no dot.
  Accessibility label: *"Boards — Animation 1 is showing"*.
- Tapping it opens a popover **beside the ⋯ button** (`Popovers.Side.BELOW`, `widthDp = 240f`, matching
  `moreMenu`'s own call at `:680`), containing, top to bottom:
  1. a ＋ row, `"Add a board"`, 44 dp;
  2. one 44 dp cell per board, top board first (so the list reads like `LayerColumnView`, which shows the
     top layer at the top — `LayerColumnView.kt:129` `for (layer in s.layers.asReversed())`), each with a
     2 dp identity stripe in that kind's gradient, the board's name, its corner pill, and the
     `p.stateSelected` ring on the active one;
  3. nothing else. **No header, no footer, no explanation.**
- **One tap on a board cell → that board becomes active**, and the popover closes (`Popovers.show` already
  consumed the outside tap; the cell's own listener calls `popovers.close()` first, as `menuRow` does at
  `:758-760`).
- **One tap on ＋ → a second popover** with one row per `BoardMenu.KINDS`, each wearing its gradient and
  `kindName`, listed in `KINDS` order. Tapping one adds a board of that kind, makes it active, and closes
  both panels. **`Popovers` holds one panel at a time** (`show` calls `close()`, `:37`), so the second
  popover replaces the first — which is why the first must be closed *before* the second is shown, or the
  anchor's `doOnLayout` fires on a removed view. **The exact call order is in §7.4.**
- **Deleting a board is NOT in this popover.** It needs a confirmation and a red dot, and `LayerColumnView`
  has no delete affordance at all (its delete lives in the layer *panel*, `:885+`). **The popover's
  ＋ row is the only mutation a person can make from here, and adding delete is a Question (Q6) — a
  half-built delete is worse than none.** `BoardOps.delete` exists, is tested (T11–T13) and has no caller
  until a row gives it one. **Say that in the file's KDoc**, or a reviewer will ask.

### 7.3 The exact line the Activity writes, and why the row is not a `core` function

**One line**, at A2:

```kotlin
box.addView(boardRow(anchor))
```

`boardRow` returns a `TextView` built exactly as `menuRow` builds one
(`JoyBrushActivity.kt:742-763`): `drawerText(text, 14f)`, `gravity = CENTER_VERTICAL`,
`setPadding(dp(10), 0, dp(10), 0)`, `minHeight = dp(40)`, `kit.label(this, description)`,
`background = pressWash()`, `setOnClickListener { popovers.close(); … }`. **`pressWash()` (`:779`) and
`drawerText` (`:764`) are `private` members of the Activity**, so a row builder cannot live in `core` —
`boardRow` is a private function of `JoyBrushActivity`, written verbatim in §7.4 as part of A3.

**What `core` supplies is the three STRINGS and the KIND LIST**, in `BoardMenu` (§7.1), and they are what
T16–T19 test. That split is the whole reason the wording is checkable on a JVM: `ChromeKit.label` (`:56`)
makes one string the hover label, the TalkBack name **and** the tooltip, so a wrong label is a wrong three
times and none of the three can be seen from a test. **The `View` is §7.4's function plus
`BoardPopoverView.kt` — no third file, and no file in `core` that imports Android.**

**And the second line, at A1, is three imports** — `Board`, `BoardKind`, `BoardOps` — because `BoardMenu`
is `core` and is **called, never reimplemented** (D11, and T24's census).

### 7.4 A3's code, verbatim

**The anchor is already in scope and this is why there is no `moreBtn` field.** `moreMenu` is declared
`private fun moreMenu(anchor: View)` (`:633`), so `anchor` is a **parameter** of the function the ⋯ row
is written inside. Every new function that needs it takes it as a parameter too. **No field is added, and
`:413-414` is not touched** — which is what keeps this row at three sites and not five.

```kotlin
    // ── boards (JB-3.00): the list this screen is drawing, and which one it is on ──
    //
    // The engine knows about ONE board: `JbCanvasView.readContents` builds a fresh document at snapshot
    // time from the GPU's layer stack, and it is always one CANVAS board. So this list is what the
    // document *will* say, and it is SESSION STATE until Q1 and Q2 are answered — nothing here reaches
    // the file. Nothing here is a second copy of anything in `core` either: the wording is
    // `BoardMenu`'s and the algebra is `BoardOps`'s, both called, never restated.

    /** The boards this screen is drawing on, bottom first. Never empty; see `BoardOps`. */
    private var boards: MutableList<Board> = ArrayList()

    /** The active board's id, or null before the first board. Never null once one exists. */
    private var activeBoardId: String? = null

    /** The ⋯ row, built exactly as `menuRow` builds one, with `BoardMenu`'s words. */
    private fun boardRow(anchor: View): View = drawerText(BoardMenu.rowLabel(boards.size), 14f).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        minHeight = dp(40)
        kit.label(this, BoardMenu.rowDescription(boards, activeBoardId))
        background = pressWash()
        setOnClickListener {
            popovers.close()
            popovers.show(boardPanel(anchor), anchor, Popovers.Side.BELOW, widthDp = 240f)
        }
    }

    private fun boardPanel(anchor: View): View =
        BoardPopoverView(kit, boards, activeBoardId, object : BoardPopoverView.Host {
            override fun selectBoard(id: String) { selectBoard(id); reopen(anchor) }
            override fun addBoard(kind: BoardKind) { addBoard(kind); reopen(anchor) }
        })

    /** The panel again, after a change — the same call [boardRow]'s listener makes. */
    private fun reopen(anchor: View) = popovers.show(boardPanel(anchor), anchor, Popovers.Side.BELOW, widthDp = 240f)

    private fun addBoard(kind: BoardKind) {
        val w = canvas.pageWidth
        val h = canvas.pageHeight
        if (w <= 0 || h <= 0) {
            toast("The drawing has no size yet — wait a moment and try again")
            return
        }
        val active = boards.firstOrNull { it.id == activeBoardId } ?: boards.first()
        val doc = BoardOps.stackOf(JbDocument(id = "", name = "", boards = boards, layers = emptyList(), activeBoardId = activeBoardId))
        val after = BoardOps.addBoard(doc, kind, active.rect, ids = { BoardOps.freshId(boards) })
        boards = ArrayList(after.boards)
        activeBoardId = after.activeBoardId
    }

    private fun selectBoard(id: String) {
        activeBoardId = id
    }

    /** Built and tested, and NOT reachable from this popover (Q6). `BoardOps.delete` gets its caller later. */
    private fun removeBoard(id: String) {
        val stack = BoardOps.stackOf(JbDocument(id = "", name = "", boards = boards, layers = emptyList(), activeBoardId = activeBoardId))
        val after = BoardOps.delete(stack, id) ?: return
        boards = ArrayList(after.boards)
        activeBoardId = after.activeId
    }
```

**Two things in that code are placeholders a builder must NOT ship, and they are the row's honest edges:**

1. **`BoardOps.addBoard(doc, …)` takes a `JbDocument` and the screen has none** — so the code above
   fabricates a throwaway one with `layers = emptyList()`. That is a **smell, not a design**, and it is
   here only so the wiring is unambiguous while **Q1** is open. **The real answer is D3's: the Activity
   holds a `JbDocument`, not a `MutableList<Board>`** — and that is exactly what Q1 asks. **If Q1 is
   answered "the Activity holds the document", this whole block collapses to four one-liners over that
   field and the fabricated document disappears.** Do not leave the fabricated document in a landed file.
2. **`removeBoard` has no caller** (Q6, §7.2), which is why `BoardPopoverView.Host` does **not** declare
   `deleteBoard`. It is written because `BoardOps.delete` is tested and Decision 7 already answers the
   hard half of the question. **Keep the one-line KDoc on it**, or a reviewer will ask why dead code is
   being added on purpose.

**The whole of A3 is the eight declarations above, pasted in ONE go at `:682`.** Split it and the hunk
count is wrong.

---

## 8. Tests

Every case below is in `commonTest` unless marked `jvmTest`. **Each expected value is derivable from §4 or
from the file it is attributed to, and the derivation is in the case.** Write the tests **first**.

### `BoardOpsTest.kt` — the algebra

The fixture, stated once so every case below is checkable:

```kotlin
/** A one-board document, exactly as `DocOps.newDocument` makes it. */
private fun fresh(w: Int = 100, h: Int = 200): JbDocument =
    DocOps.newDocument("d1", "Drawing", w, h) { "id-${ids++}" }
```

with `private var ids = 0` reset in `@BeforeTest`. So `fresh()` has board `id-0` named **"Board 1"**,
`kind = CANVAS`, `rect = RectPx(0, 0, 100, 200)`, one layer, `activeBoardId = "id-0"`. **Every expected
value below follows from that, and the id sequence is `id-0`, `id-1`, `id-2` in that order** because
`newDocument` draws board, layer, cel in that order (`DocOps.kt:26-28`).

| # | Case | Asserts |
|---|---|---|
| **T1** | `aNewAnimationBoardIsValidTheMomentItIsMade` | `BoardOps.addBoard(fresh(), ANIMATION, RectPx(0,0,100,200), ids = { "id-${i++}" })` → **`DocOps.validate(after)` is `emptyList()`**. This is the case the whole row exists for: rule 4 (`DocOps.kt:79`) refuses an animation board with no frames, and JB-3.01 Q2 is explicit that a frame-less animation board is not a board. |
| **T2** | `aNewAnimationBoardHasExactlyNewFramesFrames` | `frames.size == BoardOps.NEW_FRAMES == 2`; every `holdFrames == 1`; `fps == 12f`; frame ids are distinct. **2 is derived in §4**: rule 4 needs ≥1 and `ThreeFingerSwipe.MIN_FLIPPABLE_FRAMES` is 2. |
| **T3** | `addingABoardMakesItTheActiveOneAndAppendsIt` | `boards.size == 2`; `boards[0] == fresh().boards[0]` (**the existing board is untouched, by equality — not "field by field"**); `boards[1].id == activeBoardId`; `boards[1].name == "Board 2"` (`LayerStack.nextName`'s rule over `"Board 1"`). |
| **T4** | `idsAreDrawnInTheStatedShapeBoardFirstThenItsFrames` | With a counting lambda, the ids handed out are `["b-2", "f-1", "f-2"]` **in that order** — the board id, then the seed frames in play order (Decision 14, `AnimOps.kt:196-201`'s "a fixed SHAPE … so a failure names the same id the code used"). **Pins the shape on purpose, and only here** — JB-3.01 Q4 is explicit that the shape is for diagnosis and which layer gets which id is not a contract. |
| **T5** | `anIdTheDocumentAlreadyUsesIsRefused` | A generator handing back `"id-0"` (the existing board's id) throws `DocException` **naming the id and what it was asked for** — `AnimOps.freshId`'s exact message shape (`AnimOps.kt:647-656`). **And the flat set is the point:** handing back a *layer*'s id is refused too, which `DocOps.validate` would not have caught. |
| **T6** | `aNewCanvasBoardCarriesNoFramesAndNoGrid` | `ANIMATION`-only fields stay empty for the other four kinds: `CANVAS`/`PUPPET`/`CHARACTER` → `frames.isEmpty()`; `SPRITE` → `frames.isEmpty()` **and** `grid != null`; **no kind but `ANIMATION` has frames and no kind but `SPRITE` has a grid**. This is `DocOps` rules 4 and 5's gating (`:78`, `:91`) turned into an assertion, and it is the JB-3.01-review finding 3 (a `CANVAS` board with a frames list passes rule 4's letter and then breaks `frameStartsMs`). |
| **T7** | `aNewSpriteBoardGetsAOneByOneGridAndDoesNotMove` | `grid == SpriteGrid(1, 1, 100, 200)` **and** `rect == RectPx(0,0,100,200)` — unchanged. Derivation in §4: a 1×1 grid over the rect is what `SpriteGridMath.bySize(rect, rect.w, rect.h)` gives, and `SpriteBoard.fitted(grid)`'s `fitRect` for a 1×1 grid is the rect. **A sprite board that resized itself on creation would be a surprise with no undo.** |
| **T8** | `aBoardWithNoRoomIsRefused` | `rect = RectPx(0, 0, 0, 200)` and `RectPx(0, 0, 100, -1)` each throw, and the message says the size — rule 3 (`DocOps.kt:71-75`), the same guard `newDocument` has at `:25`. |
| **T9** | `theNewBoardLandsWhereThePersonIs` | Adding to a document whose active board is at `RectPx(-1400, 300, 512, 512)` gives the new board **that exact rect**, x and y included. `PaperGeometryTest.kt:541` shows a board at a negative x is a real case, so "just use 0,0" is wrong. |
| **T10** | `deletingTheActiveBoardMovesToTheOneBelowAndFallsBackUp` | Three boards A, B, C with **B active** → `delete(stack, "b")` → `activeId == "a"`. Three boards with **A active** → `delete(stack, "a")` → `activeId == "b"` (there is nothing below, so the one above takes it). `LayerStack.kt:71` verbatim: `list[(i - 1).coerceAtLeast(0)].id`. **Decision 7, and it is the row's answer to "what is active if a person deletes the board they were on".** |
| **T11** | `deletingABoardThatIsNotActiveLeavesTheActiveOneAlone` | Active C, delete A → `activeId == "c"`. Same line, other branch. |
| **T12** | `theLastBoardCannotBeDeleted` | One board → `BoardOps.delete(stack, id) == null` **and** `BoardOps.deleteBoard(doc, id)` throws. Decision 6, three landed precedents (`LayerStack.kt:66-73`, `AnimOps.kt:311-314`, `DocOps.kt:182`). **A drawing always has somewhere to paint** — the words `LayerStack` uses. |
| **T13** | `deletingABoardALayerAnimatesInIsRefusedInWords` | A document with `Layer(animatedIn = "b-anim")`; `deleteBoard(doc, "b-anim")` throws, and the message **names the layer**. Decision 8, `AnimOps`'s doctrine (`AnimOps.kt:74-76`). A delete that quietly un-animated the layer would be a repair `AnimOps` forbids. |
| **T14** | `activeFrameCountAgreesWithTheGesturePredicate` | **The re-derivation test (Decision 9).** For a matrix of documents — `activeBoardId` null; an id naming nothing; a `CANVAS` board; an `ANIMATION` board with 0, 1 and 2 frames; a `SPRITE` board with 5 frames — assert `BoardOps.activeFrameCount(doc)` equals what `ThreeFingerSwipe` answers, **by asking `ThreeFingerSwipe`**: a fresh `ThreeFingerSwipe()` per document, `begin(doc, 0, 12f, 1f, 1f)`, then `move(-36f, 0f)` and the step's type. `FRAMES` iff `count >= 2`, `BRUSH` otherwise. **The two answers must be equal for all six documents.** This is the JB-2.20a parity lesson applied in the right direction: not two hand-written transcriptions compared with each other, but **the new function against the landed one**. |
| **T15** | `theKindCheckComesBeforeTheCount` | The order is load-bearing (`ThreeFingerSwipe.kt:125` before `:126`, JB-3.08's mutation 5). Assert it **structurally**: a `SPRITE` board with 5 frames answers **0**, not 5. If the order were reversed, a sprite board's 5 frames would answer 5 and the swipe would offer `FRAMES` on a board that cannot play (`AnimOps.playableSchedule` refuses it, `AnimOps.kt:506`). **This test is what stops the re-derivation in T14 from being a paraphrase.** |

*(T15 above is listed under `BoardOpsTest` but it is a `core`-to-`core` cross-check; it lives in
`BoardOpsTest.kt` because it needs no file access.)*

### `BoardMenuTest.kt` — the wording

| # | Case | Asserts |
|---|---|---|
| **T16** | `everyKindHasANameAndTheOrderIsTheOnesAPersonMeets` | `KINDS == listOf(CANVAS, ANIMATION, SPRITE, PUPPET, CHARACTER)`; `kindName` is non-blank for all five and equals `"Canvas"/"Animation"/"Sprite"/"Puppet"/"Character"`. **Never `"ANIMATION"`** — a person does not read an enum. |
| **T17** | `theRowShowsTheCountOnlyWhenThereIsMoreThanOne` | `rowLabel(1) == "Boards"`, `rowLabel(2) == "Boards (2)"`, `rowLabel(0) == "Boards"`. `LayerColumnView`'s ＋ prints the count always (`:162`); **the ⋯ row is a different control with a different job** and `"Boards (1)"` teaches nothing. |
| **T18** | `noLabelIsEverBlank` | For a matrix of boards — each of the five kinds, active and not, an empty-name board, an `activeId` naming nothing — **`rowDescription` and `cellDescription` are non-blank**, and `cellDescription` contains the board's name and its kind name. **A blank `contentDescription` is an invisible control to TalkBack**, and `ChromeKit.label` (`:56`) makes one string three things. **This case is the reason the wording is in `core` at all.** |
| **T19** | `cornerCountsOnlyWhatCanBeCounted` | `ANIMATION` → `"2 frames"`, `"1 frame"`, `"12 frames"`; `SPRITE` → `"3×2"`; `CANVAS`/`PUPPET`/`CHARACTER` → `""`. **Never `"0 frames"`** — an empty string means the pill is not drawn, and a zero is a lie about a board that has none yet. **And `"1 frame"`, not `"1 frames"`: this is asserted, not left to taste.** |

### `BoardOpsNoSecondTruthTest.kt` — the censuses (`jvmTest`; these read files)

| # | Case | Asserts |
|---|---|---|
| **T20** | `theNewFramesCountIsTheGestureConstantAndNotACopy` | `BoardOps.NEW_FRAMES` and `ThreeFingerSwipe.MIN_FLIPPABLE_FRAMES` are **the same value**, and `BoardOps.kt`'s source contains `MIN_FLIPPABLE_FRAMES` **as a reference** and contains **no literal frame-count of its own**. **A census over the source, not a grep for the token** — the JB-3.08 review's m1 was a transcription that was off by one line, and T8b's own non-vacuity example did not contain the token it forbade. **If someone changes `MIN_FLIPPABLE_FRAMES` to 3, this goes red and `NEW_FRAMES` follows — which is the point.** |
| **T21** | `noAndroidTypeReachesCore` | `BoardOps.kt` and `BoardMenu.kt` contain no `android.`, `import android`, `Bitmap`, `Canvas`, `Context`, `View`, `Handler`, `Looper`, `Dispatchers`, `Thread`, `suspend`, or `Uri`. **The iOS door** (`OWNER_CONSTRAINTS`, 2026-09-28) and the JB-2.11 split are the same rule. |
| **T22** | `noSerialisedFieldIsAddedAndNoVersionIsBumped` | **The landed `EnumFreezeTest` already does this** — `EnumFreezeTest.kt:94` is `assertEquals(3, DOC_VERSION)` and the file freezes `BoardKind`'s five constants in order (`:125`, `:187`). **So T22 is not a new test: it is a REQUIREMENT that `EnumFreezeTest` stays green and untouched.** A builder who bumps `DOC_VERSION` or reorders `BoardKind` reds a test they were told not to edit, which is the point. **What this row adds is the *reason* written down**, in Decision 3 and in the KDoc: the row adds no key and no constant, so the version is untouched because there is nothing to change, **not** because the test was not run.
| **T23** | `boardOpsCallsAnimOpsAndNeverCopiesIt` | `BoardOps.kt` mentions `AnimOps.addFrame` **as a call** and contains no `data class AnimResult`, no `enum class NewFrame`, no `sealed class CelWork`, and no `"CelWork"`/`"CopyCel"`/`"DropCel"` token. The seed frames go through the landed operation (Decision 4) — **and a file that re-declared `CelWork` would be a second copy of a contract class, which is how `doc → core` coupling rots.** |
| **T24** | `noNumberIsRestated` | `BoardOps.kt` contains **no** `12f`, no `40` used as a name length, no `2` used as a frame count, and no `"Board ("` other than the one in `NAME`. Every one of §4's numbers is either a named constant or a **reference to the landed one** (`Board.fps`'s default via `NEW_FPS`, `LayerStack.MAX_NAME` is named in the KDoc and `MAX_NAME` is asserted equal to it, `ThreeFingerSwipe.MIN_FLIPPABLE_FRAMES` via T20). **This is the census that would have caught the last two rows' copied constants.** |

**Command (the ONLY thing in this row that may be proved by running gradle — and the builder runs it,
not the reviewer, and not me):**

```
./gradlew -p joybrush :core:jvmTest
```

**Passing is `BUILD SUCCESSFUL` with 0 failures**, and the run must include `BoardOpsTest`,
`BoardMenuTest` and `BoardOpsNoSecondTruthTest`. **Report the count of tests run, not just the verdict.**

### 8.1 Non-vacuity — the mutations, and what must go red

Nine mutations. **Each names the test that must catch it; if a mutation does not redden, the TEST is
fixed, not the verdict.** (R25's lesson: JB-3.08a recorded a mutation that did not redden and the builder
proved why.)

| # | Mutation | Must go red |
|---|---|---|
| **M1** | `NEW_FRAMES = 1` | T1, T2, **T14** (the swipe answers `BRUSH`), T20 |
| **M2** | `addBoard` appends but does not set `activeBoardId` | T3 |
| **M3** | `delete` uses `coerceAtLeast(1)` instead of `coerceAtLeast(0)` | T10 (the A-active case answers C, not B) |
| **M4** | `delete` allows the last board | T12 |
| **M5** | `activeFrameCount` counts before checking `kind` | **T15** (a `SPRITE` board with 5 frames answers 5) |
| **M6** | `addBoard` gives every kind a frames list | T6 |
| **M7** | `nextName` returns the lowest free N rather than `max + 1` | T3 and the T3b case below |
| **M8** | `BoardMenu.rowDescription` returns `""` for an unknown `activeId` | T18 |
| **M9** | `BoardOps.kt` restates `MIN_FLIPPABLE_FRAMES` as a literal `2` | **T20** |

**T3b, added because M7 needs it:** boards `["Board 1", "Board 3"]` → `nextName` is **`"Board 4"`**, not
`"Board 2"`. `LayerStack.nextName`'s KDoc says why (`LayerStack.kt:120`): *"so names never repeat as
layers come and go"*. **A gap in the numbering is not a free name; reusing one is how two boards end up
with the same label and one of them is never found again.**

**M7's arithmetic, stated so a builder can check it:** over `["Board 1", "Board 3"]`,
`mapNotNull { NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }` = `[1, 3]`, `maxOrNull()`
= `3`, so `"Board ${3 + 1}"` = **"Board 4"**. Over `["Board"]` (no match) the list is empty, `maxOrNull()`
is `null`, `?: 0` gives 0, so **"Board 1"**. Over `["Board x"]` (no digits, `toIntOrNull()` null) the
same: **"Board 1"**.

---

## 9. Do not

- **Do not edit `DocOps.kt`. Not one line, not a KDoc.** `newDocument` is Built and cross-reviewed and its
  contract is the owner's wording (`DocOps.kt:17-23`). `BoardOps` is a **sibling**. The temptation is to
  add a `kind` parameter to `newDocument` — **that is the change this row exists to avoid**, and it would
  alter what every caller in the tree gets.
- **Do not edit `DocModel.kt`**, and do not bump `DOC_VERSION`. Decision 3, and the landed
  `EnumFreezeTest.kt:94` reds if you do. **No new field is needed: `activeBoardId` already exists.**
- **Do not edit `JbCanvasView.kt` or `GlPaintEngine.kt`.** R30 item 2, the Lead's, a different lock
  order. Not a patch, not a "two-line move", not a comment.
- **Do not touch `FilmStrip.kt`, `AnimOps.kt`, `SpriteBoard.kt`, `SpriteGridMath.kt`,
  `LayerStack.kt` or `LayerColumnView.kt`.** They are **called**. `LayerColumnView` is the *style*
  reference; copying its constants is the point, editing it is not.
- **Do not add a seventh `TopButton`** to the top bar. D1, R30 item 1. The affordance is one row in a
  popover that already exists.
- **Do not make a board that has no artwork look like it has some.** D10. If a later row gives a new
  board its own pixels, the popover's wording changes then, not before.
- **Do not name the file `BoardOps.kt` and put view code in it**, or put Android types in `core`. T21.
- **Do not assert a number you have not derived.** Every number in §4 carries its derivation; a fixture
  whose expected value is a guess is a fixture that hides a real change.
- **Do not run gradle as the reviewer.** This is app-file work; the watcher's `build.log` is the
  authority for the app module (§Definition of done).
- **Do not ship a delete affordance.** §7.2. `BoardOps.delete` is tested and has no caller until a row
  gives it one; that is deliberate and it goes in the KDoc.
- **Do not treat "the count is 3" as negotiable.** If your `@@` count is 2, you merged two sites; if it
  is 4, you split one. **A bare `grep -c` can match KDoc text and return the wrong number** — anchor it
  with `^@@` as the command below does.
- **Do not add a `moreBtn` field, and do not touch `:413-414`.** `moreMenu(anchor: View)` (`:633`)
  already has the anchor as a parameter; §7.4's functions take it as one. A field declared beside
  `layersBtn` (`:204`) and a use at `:413` are 200 unchanged lines apart, so that "obvious" refactor
  turns three runs into five. **This was the exact mistake this spec made while being written**, and it
  is written down for that reason.

---

## 10. Definition of done

- [ ] `BoardOps.kt`, `BoardMenu.kt` and the three test files exist, and **nothing else in `core` changed**.
- [ ] `./gradlew -p joybrush :core:jvmTest` → `BUILD SUCCESSFUL`, **0 failures**, with the **count of
      tests run** pasted. **This is the only gradle in the row and the builder runs it, never the
      reviewer and never the spec writer.**
- [ ] `git status --short` shows **only** the seven owner-area paths, pasted.
- [ ] **`git diff -U0 -- joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt | grep -c '^@@'` → 3.**
      That is the mechanical form of "3 edit sites" under this spec's convention (**one contiguous run
      of changed lines = one `@@`) — see §Step 3), so a reviewer can count without reading the file.
      `git diff --stat` shows per *file*, which is not what is being claimed.
      **⚠ The `^@@` anchor is not optional.** A bare `grep -c '@@'` also matches KDoc text, and a
      builder who adds a `/** … @@ … */` comment above a site changes the count without changing a site.
      The last two view specs claimed "three" when it was five, in four places each, and a reviewer could
      not check them. **This spec states the count in §Step 3 and here, and nowhere else.**
- [ ] **The owner must look at exactly this in the watcher's `build.log`,** because **this is app-file
      work and it is verified by the watcher's build, never by running gradle here**:
      1. `joybrush-android` (or `app`) **compiles** — a green `:core:jvmTest` does **not** prove the
         Activity compiles, and A1–A3 are in Kotlin that no JVM test touches;
      2. **no new warning from the three edited runs** — an unused import from A1 is the one that will
         happen if a builder imports `Board` and then never names the type;
      3. `:core:jvmTest` is **718 + the new count, 0 failures** — 718 is the number `ROADMAP.md:242`
         records for the suite as of today, and a **drop** means something was deleted;
      4. the watcher shows the app build **green**, which is R29's standing check.
- [ ] `INDEX.md` → "Built — awaiting T1 review". Commit with the spec id. **Never mark it Proven.**
- [ ] **Q1 answered and A1–A3 done** — or, if Q1 is unanswered, **Step 1 and Step 2 only**, and the report
      says so in those words.

---

## 11. Questions

_(Spec writer `openrouter/stealth/space-bunny-alpha`, 2026-09-30. **Q1 and Q2 block the app wiring. Q3 is
the Lead's contract ruling. The rest do not block a builder.** Read §0 before this section: the finding
this row was opened on is confirmed, with one stale line number.)_

### Q1 — ⛔ **Blocked for Claude: where does the document live on the phone, and does it need a new field to say "which board am I on"?**

**This is the row's real question, and it is a contract question, not a plumbing one.** Stated as
precisely as I can:

Today the screen **has no document**. `beginSave` receives one from `canvas.snapshot { contents -> … }`
(`JoyBrushActivity.kt:1336`) and hands it to `JbArchive.save` (`:1342`); `showContents` (`:1435`)
receives one and hands it to `canvas.load`. The document is manufactured inside `JbCanvasView` from the
GPU's layer stack. **There is nowhere for a board list to live except the Activity, and the Activity is
R30 item 1.**

**Three answers, and they are not equivalent:**

| | Answer | What it costs | What it means for the nine rows |
|---|---|---|---|
| **(a)** | **The Activity holds a `JbDocument`** — the one it loaded or made — and `BoardOps` mutates that one. The snapshot merges it (JB-0.08c's `DocMerge`, Q2). | A field in the Activity and the `DocMerge` seam. **No new serialised field** (Decision 3). | **Full unblock.** The document on the phone is real, `activeBoardId` is real, and a board survives a save. |
| **(b)** | **The boards are session state** — a `MutableList<Board>` in the Activity, as §7.4's placeholder code assumes. | Nothing today. | **Core halves only.** The predicate becomes answerable in `core` and on no phone; the feature is not shippable and the popover would be a lie about persistence. |
| **(c)** | **A new session-only field on `JbDocument`** (`activeBoardIdSession: String?` or similar), not serialised. | **A new key in the model → R31 applies → `DOC_VERSION` bumps → and `DocJson.decode` (JB-0.02d, Built) now REFUSES a same-version file carrying a key this build does not know**, so it *cannot* be added without the version bump, which is the same conclusion. | Same as (b), with a contract change. **I do not recommend it and I have not designed it.** |

**I have written the row for (a) and made §7.4's code obviously provisional so (b) is never shipped by
accident.** (a) is my recommendation because it is the only one of the three in which the phone's document
and the file's document are the same document — and the moment they are not, a person adds a board, the
autosave writes a different document, and the board is gone with no error anywhere. **That is work loss,
and this project's standing is that a save that silently drops a thing is worse than a refusal.**

**What I need ruled:** (a), (b) or (c). And if (a): does the Activity hold the whole `JbDocument`, or a
`BoardStack` plus a `JbDocument` it re-derives? **I have assumed the whole `JbDocument`**, because a
`BoardStack` alone cannot answer `DocOps.validate` and cannot be written into an archive.

### Q2 — ⛔ **Blocked for Claude: does JB-3.00 land before or after JB-0.08c, and do they merge at one line?**

`JB-0.08c` (spec written tonight, ⚪ **Outline**, not landed) introduces `DocMerge.carried(fresh, carried)`
and a `@Volatile private var carried: JbContents?` in the Activity. Its site **A9** is
`JoyBrushActivity.kt:1336-1351` — **the same `canvas.snapshot { … }` callback** that is the only place a
document exists on this screen, and its site **A10** adds `private fun carriedInto(contents)`.

**They want the same seam, and one of them has to have it.**

- **If JB-0.08c lands first**, this row's app wiring adds **one line** to its existing A9 run: the board
  list is `held.boards` / `held.activeBoardId`, and `DocMerge` already carries them — **but it does not
  today.** `DocMerge.carried` (`JB-0.08c:451-453`) maps `fresh.boards` and takes `activeBoardId` only
  `takeIf { it == from.id }` (`:444`) — **so a document with two boards has its second board DROPPED by
  `DocMerge`, and its `activeBoardId` reset to board 0's.** That is a real interaction between two rows
  and neither spec knows about it.
- **If JB-3.00 lands first**, JB-0.08c's A9 must learn to carry `boards[1..]` and an `activeBoardId` that
  is not board 0's.
- **Either way, `DocMerge` needs one change I am not allowed to make** (`DocMerge.kt` is JB-0.08c's owner
  area and that file does not exist yet): its `boards` line must stop being `fresh.boards.mapIndexed { i, b
  -> if (i == 0) … else b }` and start being **"every board in `carried` that `fresh` does not have,
  appended after `fresh`'s own."**

**What I need ruled:** the order, and whether `DocMerge` is widened here or there. **I have written
neither.** But the honest statement is: **without this, this row unblocks nine rows' `core` halves and
ships no phone feature**, and I would rather say that in a spec than let a builder find it on a phone.

### Q3 — 🔴 **For the Lead: ratify Decision 3 — "the active board is document state, and there is NO new field and NO `DOC_VERSION` bump."**

I am confident this is right and I have a landed test for it (`EnumFreezeTest.kt:94` pins
`DOC_VERSION == 3`), but it is a **contract statement** and contracts are yours.

The claim: `activeBoardId` **already exists** as a `@Serializable` nullable field of `JbDocument`
(`DocModel.kt:170`), is already validated (`DocOps.kt:163-165`), is already in `JB-0.02d`'s known-key set
(`JB-0.02d:176`) so a file carrying it is not refused, and is already written by `newDocument` (`:49`).
**This row adds no key and no enum constant**, so R31 has nothing to refuse and there is no version to
bump. `DOC_VERSION` stays **3**.

**The part that is a decision, not a derivation:** this row makes the field **move during a session**,
which no current writer does. It becomes *"the board the person was last on"*, saved with the drawing.
**The alternative is that it is a per-open cursor that is written once and never updated**, which is
closer to today's behaviour and further from what nine rows want. **I chose the first** because the
owner's blueprint §6 Q1 keys the gesture to "the ACTIVE board" and asks for it to behave predictably
across a document with several boards — and a cursor that resets on every reopen is not that.

**Also yours, and smaller:** is the active board **per document** (as above) or **per session** (reset on
open)? Per-document is what the field already is. **And: can a person have two animation boards at once?**
**Yes** — nothing in `DocOps` forbids it, `FrameStepper`/`PlaybackClock` are pure functions of one `Board`
(`FilmStrip.kt:51`: *"IT IS BUILT FROM A `Board` IT WAS GIVEN AND NEVER RE-READS ANYTHING"*), and
`ThreeFingerSwipe`'s badge override is already **per board** (R25, `ThreeFingerSwipe.kt:66`). **So two
animation boards is not a special case anywhere and needs no rule — I mention it only because nine rows
will ask.**

### Q4 — 🔴 **For the Lead: a new board shows the OLD board's pixels until a later row. Is that shippable, or does the row wait?**

Decision 10 states the fact; this asks whether the fact is acceptable. A person adds "Animation 1", it
becomes active, the popover closes, and **the same artwork is on screen**. The board's rect is its own, so
a differently-sized board would show the old art at a new size — which is a thing a person will notice
and a thing I cannot fix without `JbCanvasView` (R30 item 2).

**Three answers:** (i) **ship it and say so in the popover** — the row's current design, and the wording
goes in `rowDescription` so it is testable; (ii) **the row waits** for the engine, which strands nine rows
on a core-only patch; (iii) **the new board starts with the active board's rect verbatim and the popover
says "no artwork of its own yet"** — which is (i) with the sentence moved. **I have assumed (iii)**, and
I would rather be told "no" than have a person find it at export.

### Q5 — 🟡 **PROVISIONAL — Claude to confirm: the one number with no precedent, `BOARD_BADGE_DP = 10f`.**

Every other dp in §4 is copied from a landed file. This one is not: `LayerColumnView` has **no count
badge** anywhere, so there is nothing to copy and I picked 10 dp as a corner-of-a-cell dot. **It is
inert** — a wrong badge radius is invisible next to a wrong frame count, and it is the only number in
this row not derivable. Say the word and it is one constant.

### Q6 — 🟡 **PROVISIONAL — Claude to confirm: no delete affordance in this popover.**

§7.2 says the popover can only **add** and **select**, and `BoardOps.delete` is built, tested (T10–T13)
and **has no caller**. That is deliberate: a delete needs a confirmation and a red dot, and
`LayerColumnView`'s delete lives in the layer *panel* (`:885+`), not in the column — so the pattern for
"where does a board's delete go" does not exist yet. **Decision 7 already answers the hard half (what
becomes active), so the algebra is ready the moment a panel is.** My assumption: this row ships
add-and-select, and a board's delete arrives with JB-3.02b's panel or a board-settings row.

---

## 12. What this row unblocks — the nine, restated against what is actually true

| Row | Blocked by | Unblocked by this row? |
|---|---|---|
| **JB-3.02b** paper overlay view | no animation board on a phone | **core: yes** (a document with one). **phone: only if Q1+Q2 land** |
| **JB-3.03b** strip thumbnails | no board to scrub | **core: yes** (T1–T2 give it a valid 2-frame board). **phone: Q1+Q2** |
| **JB-3.04a** onion core maths | waits on R34's `OnionMath` (D.02) first — **not this row** | **no** — R34's order is its own |
| **JB-3.04b** onion view | ghosts with nothing to ghost | **core: yes.** **phone: Q1+Q2** |
| **JB-3.05** playback | core ✅ Built; transport is the peg bar (R33) | **core: yes.** **phone: Q1+Q2** |
| **JB-3.05a** playback clock | ✅ Built; nothing on a phone can show a frame changing | **core: yes.** **phone: Q1+Q2** |
| **JB-3.08** context-aware swipe | **DEAD CODE** (§0.1) | **the predicate becomes answerable (T14/T15) and T14 proves it against the landed class.** The gesture still needs its own wiring (`JbCanvasView`, R30 item 2) |
| **JB-4.01** sprite board | view half only; core ✅ Built | **core: yes** (T7 gives a valid 1×1 grid). **view: Q1+Q2** |
| **JB-4.02** cell order & play | view half only | **core: yes** (T7). **view: Q1+Q2** |

**The honest summary, which belongs in the ROADMAP row rather than only here: this row unblocks the
`core` half of seven of the nine tonight, and the phone half of all nine only if Q1 and Q2 are answered.
Two of the nine (JB-3.04a, and the *gesture* half of JB-3.08) are blocked on something else entirely and
this row does not touch them.** A row that claimed all nine would be claiming something it cannot do.
