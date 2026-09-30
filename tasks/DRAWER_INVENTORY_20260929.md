# Drawer inventory — what is in each drawer today (Note 9, portrait, 2026-09-29)

A baseline for the owner's "every item in every drawer is still there" check. Read off the
accessibility tree of the live app, so it is what a user (and TalkBack) actually gets. PARTIAL:
only the text drawer is inventoried so far; the others are listed as "seen, not enumerated".
Re-run after big changes and diff.

## Text drawer (FONT TEST, ZA_CONTROL)
Header: name, style ("Standard"), tabs **Text | Transform | Effects | Lanes**, toggles Hide / Lock /
Let taps pass through / Frost, close.
- **Text tab, top row:** Text color · B · I · U · Tt · TT · small caps · Alignment (cycles) ·
  wrap chip (Auto width / Wrap wide / medium / narrow / Fit to width, + a width slider when on) ·
  Font button · FX. Motion range row (start / span / end markers), selection status
  ("Select all" / "N selected"), Clear, Done, trash. Below: Style (Outline, Glow, Shadow with sliders
  and diamonds), Background colour, Animation (keyframes) note.
- **Font list:** Trendy, Light, Airy, Thin, Medium, Heavy, Condensed, Condensed Bold, Classy (selected
  tick), Classy Italic, Bold Serif, Serif Italic, Country, ... (scrolls).
- **Transform tab:** Pos X, Pos Y, Scale (linked/split toggle), Rotate, Opacity — each with value,
  diamond and previous/next key.
- **Lanes tab:** New lane (Above / Below), Move (Up a lane / Down a lane).

## Seen working in portrait, not enumerated here
Image (Transform / Blend / Mask / Key / Effects / Lanes), sprite (header strip + frame palette),
audio (Level / A/V Sync / FX: Level, Pan, In, Out), film double-tap (Volume drawer), video overlay,
visualizer (Style | Transform), caption drawer (Style / Fit).
