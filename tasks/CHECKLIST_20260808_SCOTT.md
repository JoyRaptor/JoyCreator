# Checklist — JoyRaptor, 2026-08-08

Everything from the morning review. Nothing here is done until it is checked AND verified on
the Note 9 (`SANDBOX_SERIAL`). Struck-through items link the commit that closed them.

---

## A. Preview window: selection and direct manipulation

The umbrella complaint: *"objects of selection and manipulation feels very clunky in the
preview window."* Treat these as ONE feature, not six fixes.

- [x] **A1. Tap any object in the preview to select it.** Every type — PiP, image, text,
      sprite, visualizer. Currently one layer cannot be tapped at all (can be given a mask, but
      not moved by hand; only movable through the video-overlay drawer's numeric options).
- [x] **A2. Tap-and-drag moves the selected object.** No long-press, no mode. Tap, move.
- [~] **A3. Pinch to scale.** Does not work today.
- [~] **A4. Two-finger rotate, snapping at 0°.**
- [x] **A5. Selecting in the preview selects in the TIMELINE.** Bidirectional.
- [x] **A6. Selecting in the preview retargets the OBJECT DRAWER** to that object — never
      leaves another item's options on screen.

## B. Layout and chrome

- [x] **B1. The preview moves DOWN under an open drawer but must NOT shrink.** Reverse the
      scaling half of `reflowPreviewUnderDrawer`; keep the translation.
- [x] **B2. The top drawer stays semi-transparent** — deliberate, so you can work while
      seeing through it. Do not make it opaque to "fix" legibility.

## C. Objects

- [x] **C1. A PiP must start with NO mask.** Today it starts with one. Show a small `+` to add
      the first mask instead.
- [x] **C2. An adjustment layer must move between lanes like any other object.** Today it
      cannot, which defeats the point: the only thing it is above is the master layer, so there
      is nothing else for it to affect.
- [x] **C3. FX badge on EVERYTHING carrying an effect**, in the timeline — not just adjustment
      layers. (Partly done: object/text/adjustment rows carry it as of e65eff5. Audit for
      anything missed.)

## D. Regressions

- [x] **D1. PiP audio drawer no longer expands.** The lane icon shows the PiP has audio, but
      double-tapping no longer opens the audio drawer. This used to work.

## E. Text drawer — full redesign (comes from the BOTTOM, over the timeline)

Rationale from JoyRaptor: when you are working on text you are not working on the timeline, so the
drawer may cover it — and it must NOT cover the preview, because the preview *is* the preview.
No preview inside the drawer; you are formatting live text in the canvas.

Top row:
- [ ] **E1. Font name, rendered IN that font.** Tapping opens a font picker: every font shown
      in its own face, plus an **Import font** button that navigates to a downloaded font file
      and copies it into the project folder.
- [ ] **E2. B / I / U toggles.**
- [ ] **E3. Three case settings:** Capitalise First Word · ALL CAPS · Large Caps + Small Caps.
- [ ] **E4. Alignment button** to the right of those six — one button that toggles through
      left / centre / right / justified, its icon showing the current state.
- [ ] **E5. MOTION button** — unchanged behaviour, just relocated. The `none` label under it
      turns the app's highlight colour when a motion IS set.

STYLE section, each row left→right: colour swatch · label · slider · keyframe controls.
- [ ] **E6. text**
- [ ] **E7. outline**
- [ ] **E8. glow**
- [ ] **E9. shadow** — same, PLUS a direction knob underneath that previews the angle; scrub it
      to rotate; the angle reads out beside it and is tappable to type an exact value. PLUS two
      more sliders: distance and blur. **All shadow values share ONE keyframe** — do not give
      distance/blur/angle their own.
- [ ] **E10. background**
- [ ] **E11. plate**

## F. Universal compact colour dialog (app-wide)

One component, used everywhere a colour is picked.

- [x] **F1. HSB sliders** down the left.
- [x] **F2. Triangle-in-a-ring** hue/saturation/brightness picker.
- [x] **F3. Hex value** beneath it — selectable text, so it can be copied and pasted.
- [x] **F4. H, S and B numeric values** to the right of their sliders. They look like text but
      are tappable to type an exact value.
- [x] **F5. Swatch row** of common useful colours including black and white, and one swatch
      that is a circle with a diagonal line = **none**.
- [x] **F6. Eight empty swatches below** = RECENT COLOURS, shared app-wide. Any colour picked
      anywhere in the app lands here.
- [x] **F7. Small copy icon** left of the hex value — one tap copies the value.

---

## Status, 2026-08-08 midday

Closed: A1 A2 A5 A6 · B1 B2 · C1 C2 C3 · D1 · F1-F7  (commits e65eff5, a3e73dd, da72cc1, 23690c9)
Code landed but NOT device-verified: A3 A4 (pinch/rotate — adb cannot inject a second pointer),
C2's lane drag (no synthetic touch stream the timeline accepts).
STILL TO BUILD: the whole of section E — the bottom text drawer.

## Verification standard

Device-verified means: built, installed on the Note 9, exercised, and the specific claim
confirmed by a screenshot or a log line — not "it compiles".
