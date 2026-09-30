# Drawer inventory — what is in each drawer today (Note 9, portrait, 2026-09-29)

A baseline for the owner's "every item in every drawer is still there" check. Read off the
accessibility tree of the live app, so it is what a user (and TalkBack) actually gets. PARTIAL:
the text, image, sprite and audio drawers are inventoried; the others are listed as "seen, not enumerated".
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

## Sprite drawer (Starguy)
Header: name, tabs **Transform | Lanes**, toggles Hide / Lock. Transform: Pos X, Pos Y, Scale, Rotate,
Opacity, each with value and previous/next key (Pos X shows the diamond row). Frame palette below.

## Audio drawer
Header: tabs **Level | A/V Sync | Effects**, actions Start here / End here / Split at playhead, toggles
Mute / Lock / Bypass effects (A/B). Level: volume (%, keyable), Pan (C), In / Out fades (Fade in /
Fade out, ms). Effects: Enhance voice, Beat-reactive link, Compressor gain-reduction meter.

## Visualizer drawer (double-tap the VIZ lane)
Tabs **Style | Transform**. Style: Lanes (layer chips, add, duplicate, delete, prev/next), **Shape** (all eight
options showing as chips with a drawn icon: Bars, Line, Filled, Dots, Squares, Peaks, Ring, Particles;
scrolls sideways), Color (swatch dots), Opacity, Softness, Gain, **Blend** (Normal | Add), Trails, Spread,
Phase, Mirror (Off | On), Glow (Off | On) + Glow radius. Transform: position, scale, rotation, opacity,
each keyable (diamond + prev/next).

## Video overlay drawer (ZA_CONTROL, HOLD on the overlay clip; double-tap only selects it)
Header: tabs **Transform | Blend mode | Mask | Chroma key | Effects | Lanes**, toggles Mute, Show this
overlay's sound on the timeline, Hide, Lock, Let taps pass through, close. Transform: Pos X, Pos Y, Scale,
Rotate, Opacity, Volume (each with value, diamond, previous/next key). Chroma key: "Key out a colour"
(eyedropper). Lanes: New lane (Above / Below), Move (Up / Down a lane).

## Caption drawer (ZA_CONTROL, HOLD on the CC lane)
Header: Captions chip (dot + eye toggle), add (+), tabs **Style | Fit**, Frost, close. Style: caption
position and size sliders (6%), Font (Standard) with save / delete / share / import, Highlight (Pop, Zoom,
Bounce), Text / Highlight colours, Box, box colour, Outline + colour, Shadow, Box…, Motion (None + an
alignment menu), Timing (In / Out as a share of the line, 0%). Preset strip along the bottom: Pop, Zoom,
Bounce, Boxed, Hot, Meme, Bright, hide-captions eye, timer.

## Seen working in portrait, not enumerated here
Film double-tap (Volume drawer).
