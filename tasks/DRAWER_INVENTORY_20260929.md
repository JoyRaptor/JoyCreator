# Drawer inventory — what is in each drawer today (Note 9, portrait, 2026-09-29)

A baseline for the owner's "every item in every drawer is still there" check. Read off the
accessibility tree of the live app, so it is what a user (and TalkBack) actually gets. PARTIAL:
the text and image drawers are inventoried; the others are listed as "seen, not enumerated".
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

## Image drawer (ZA_CONTROL, portrait)
Header: name, tabs **Transform | Blend mode | Mask | Chroma key | Effects | Lanes | Puppet**, toggles
Hide / Lock / Let taps pass through.
- **Transform:** start here / span whole / end here (time range), Fit, Fill, Motion, Rotation pivot,
  Clear all keys, animation summary line; rows Pos X, Pos Y, Scale (linked/split toggle) with Scale X /
  Scale Y when split, Rotate, Opacity — each with value, diamond, previous/next key.
- **Chroma key:** "Key out a colour" is exposed. **Lanes:** New lane (Above / Below), Move (Up / Down a lane).
- **Blend mode:** a single blend dropdown ("Screen v"). **Mask:** shape chips (add / remove), the
  mask-mode toggles, and rows Center X, Center Y, Width, Height, Roundness, Rotation, Soften edges,
  each with value and previous/next key. **Effects / Puppet:** not enumerated.
- CORRECTION: an earlier version of this file claimed a TalkBack gap on the Blend/Mask/Effects tabs.
  That was my capture running while the app was out of front; re-checked, Mask exposes all its
  labelled controls. No accessibility gap found.

## Seen working in portrait, not enumerated here
Sprite (header strip + frame palette),
audio (Level / A/V Sync / FX: Level, Pan, In, Out), film double-tap (Volume drawer), video overlay,
visualizer (Style | Transform), caption drawer (Style / Fit).
