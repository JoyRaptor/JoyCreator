# SPEC W — Stacked adjustment layers, faded badges, and the carry/auto-pan rules

**Difficulty: MEDIUM. Two bugs and three interaction rules. Read `_RULES_READ_FIRST.md` first.**
**Also read `tasks/GIT_PRACTICE.md` — the identity rule. No real names, emails or device serials in
any file or commit message.**

Five items from JoyRaptor, 2026-09-10, all in the timeline. Item 2 is the worst — it makes objects
unreachable.

---

## 1. The selected adjustment layer's fx and trash badges are faded

> "The selected adjustment layer in the timeline has its FX and trash badge faded. Either there is
> some z-order issue where something went in front of it or an opacity change."

He is describing the two states correctly, so establish which it is before changing anything.

`drawFxBadge` (`LayerRowRenderer` ~1989) paints `0xFFB388FF`, fully opaque. The row body fill
(`rowBodyBgPaint`, ~1154) is drawn *before* the item content, so on the face of it it cannot dim
them — which means something drawn **after** the badges is covering them, or the badge is being
drawn into a layer that is itself composited with alpha.

**This is a regression.** It was not there before SPEC U (the neutral-grey banding) and SPEC V (the
collapsed-spine/z-order work). Bisect against those two rather than guessing: both are small and
recent, and the answer is in one of them.

## 2. Adjustment layers all stack into ONE lane — objects must never overlap

> "Adding more adjustment layers doesn't find new lanes, they all stack in ONE lane. Objects should
> never stack."

**This is the serious one.** Two objects in the same lane at the same time means one is on top of
the other and at least one cannot be selected, moved, or deleted from the timeline.

`addAdjustmentLayer()` (`FaditorEditorActivity` ~27977) calls
`project.getTimeline().addAdjustmentLayer(layer)` and — going by the undo comment at ~26527 —
simply **appends**, with no search for a lane that is free over the new layer's time range.

**The rule: adding an object finds the first lane with no overlap over its time span, and creates a
new lane when every existing one is occupied.** Check whether the other add paths (text, image,
sprite, PiP, visualizer) already do this — if one of them has a working "find a free lane" helper,
use it rather than writing a second one. If none does, they may all share this bug; say what you
found for each.

This pairs with item 3: a new lane is exactly what the drop logic should offer instead of an
overlapping placement.

---

## 3, 4, 5 — the carry interaction. JoyRaptor's reasoning is sound; build it as stated

> "Behavior of moving an object and having it rest on a longer object and having it shortcut to
> moving before end doesn't really work with things that span the whole frame. That behavior
> shouldn't work on things that expand the whole timeline, because it just moves you completely
> around, and it's frustrating. It should just either have it where it allows you to move into an
> empty lane or create a lane in between, but not place it on a lane that has something else where
> there'd be overlap."

> "The timeline should not pan for things that are being moved that take up the whole span, nor
> should it move when you're taking smaller objects and hovering them over something that takes up
> the whole timeline, because then it's moving it to like the end of the timeline, but there's no
> end to it. So it should just bypass those and only work with things that have a duration that's
> less than the timeline. I don't know if there's any problem with my logic with that or not?"

**There is no problem with the logic.** "Place this before or after that" is meaningless when
*that* spans the entire timeline — there is no before and no after. The rule follows from the
geometry, and it should be stated in the code that way.

### 3. Full-span objects are exempt from before/after placement

The drop states are `CARRY_FIT` / `CARRY_TRIM` / `CARRY_NEWLANE` (`EditorTimelineView` ~1079).

- If the object being **carried** spans (effectively) the whole timeline, or the object being
  **hovered over** does, the before/after shortcut does not apply.
- What is offered instead: an **empty lane**, or a **new lane** — never a placement that would
  overlap. That is item 2's rule again, and the two should share one definition of "does this
  overlap".
- Define "full span" with a tolerance rather than exact equality — an object within a small
  fraction of the timeline's length counts. Say what threshold you chose and why.

### 4. No auto-pan for full-span objects

`edgeScrollRunnable` (~1246) pans when the finger enters `edgeScrollZonePx`. It must not run when
the carried object is full-span, nor when the object being hovered over is. Panning toward "the
end" of something with no end is what disorients him.

### 5. The hover dwell before auto-placement is far too short

> "I've found the auto-scrolling thing to the end to be more frustrating than not. And I'm not sure
> if it's because it's a bad feature or just because I haven't dialed in the correct parameters...
> I think it needs a much longer hover before it starts moving, so a person trying to go over it
> and staying parked on there with their finger down, as opposed to having it activate just as I'm
> having it being brought up through other lanes. When it activates falsely, it's really
> frustrating because you're like, I was just trying to bring that past an area, but instead you
> moved me all over the place, and now I'm disoriented."

He is distinguishing **passing through** from **parking**, and only parking should arm it.

- Require a **deliberate dwell** — the finger substantially still, over the same target, for a
  clearly longer period than today. Start around 600–800 ms and say what you picked.
- **Movement resets the timer.** Travelling through a lane on the way somewhere else must never
  arm it, however slowly.
- Give it a visible tell when it *is* armed, so activation is never a surprise.
- Keep the numbers as named constants at the top, so the feel can be tuned in one line — he has
  said he is unsure whether the feature is bad or just badly tuned, and easy tuning is how that
  gets settled.

---

## Acceptance criteria

1. The fx and trash badges on a selected adjustment layer are as crisp as on any other item. Say
   what was covering them.
2. Adding several adjustment layers puts each in its own lane. **No two objects ever occupy the
   same lane at the same time**, by any add path.
3. Carrying a full-span object, or hovering over one, offers an empty or new lane — never an
   overlapping placement, and no before/after shortcut.
4. The timeline does not pan in either full-span case.
5. Passing a carried object through lanes never arms auto-placement; parking on one for the dwell
   does, with a visible tell.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and spect 11/11,
   specr 13/13, specq 7/7, mesh 66/66, spech 36/36, bakepop, escape, pinbudget, speck, flip,
   rotation, preview-parity, frame-parity, persist-lint all stay green.

## Boundaries

- You own `timeline/EditorTimelineView.java`, `layers/LayerRowRenderer.java`, and the add-path
  wiring in `FaditorEditorActivity.java`.
- Do **not** touch `ui/faditor/transform/**`, `transform/mesh/**`, `TransformQuad`,
  `CornerPinTransformHost`, the bake path, or `overlay/TextOverlayLayer.java`.
- Do not revert SPEC U item 4's space sharing or SPEC V's rect-freshness stamps.
- **The sandbox phone is the test device. NEVER install to or write on the Note 20.**

## Deliver

Item by item, with device screenshots for 1 and 2. What was covering the badges. Whether the other
add paths share the stacking bug. The full-span threshold and the dwell duration you chose, and
why. Build verdict; compile-verified vs device-verified, per item.
