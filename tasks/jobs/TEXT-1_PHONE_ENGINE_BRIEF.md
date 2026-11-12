# TEXT-1 phone engine: brief for the builder (written 2026-11-12 by the TextLab lead)

**Who this is for:** a second session (Opus 5.5 is fine) that puts TextLab's look engine into the app, while
the lead keeps building looks. Read `tasks/AGENT_START.md` first, then the card `tasks/jobs/TEXT-1_textlab.md`.
Work in your own worktree cut from `origin/joy-creator` (fetch first; the owner's main folder is behind).

## The job in one line
Run the SAME shader files and look files the web look book runs, on the phone, and show one look on real
text in a debug screen. Nothing is to be redesigned: this is a port with a picture to match.

## What already exists (do not rewrite, do not fork)
- Shaders, written once for both: `app/src/main/assets/textlab/shaders/*` (GLSL ES 3.00).
- Sliders: `app/src/main/assets/textlab/parts.json` (slider name to uniform `u`, component `i`, default).
- Looks: `app/src/main/assets/textlab/looks/*.text.json` and `looks/index.json`.
- Fonts: `app/src/main/assets/textlab/fonts/` (+ `tubefont.json` for neon and handwriting).
- The reference engine, to be mirrored file for file: `tools/textlab-web/tl-gl.js` (program assembly, typed
  uniform setting, targets), `tl-glyphs.js` (a letter into a distance picture, layout, emitter points),
  `tl-tubes.js` (centre-line letters), `tl-engine.js` (the passes, in order). About 1,100 lines in all.
- VisLab's GL code to reuse, not copy: `vislab/gl/ShaderLib`, `GlProgram`, `GlTarget`, `GpuTimer`,
  `StudioVis`' GL thread, `OffscreenGl`. TextLab's assembly contract is the same as VisLab's.

## The contract that must not drift
`"#version 300 es\n" + PRECISION + one "#define X" line per look feature + common.glsl + "\n#line 1\n" + body`.
If a shader needs changing, change the shared file and tell the lead: the look book must keep drawing the
same picture. Never edit a look file to make the phone match.

## Order of work (stop and show the owner after each)
1. **One still look.** New package `cc.joycreator.textlab` (no FadCam code). `TextLabRenderer` draws
   `dark-steel` for the word TITAN into an offscreen target and shows it in a debug activity. Passes, as in
   `tl-engine.js` `frame()`: backdrop, compose (depth test GREATER, clear depth 0), soften, shade, bloom
   down and up, final, fxaa. Skip fire and particles at first.
2. **Letters.** Kotlin twin of `tl-glyphs.js`: draw the glyph with `android.graphics` at 2x, turn coverage
   into a distance picture (the same two-pass exact distance transform), `GLYPH_EM = 208`, `SPREAD = 0.25`,
   rows bottom to top. Then `tl-tubes.js`. Load the bundled fonts by the keys in `TL.FONTS`.
3. **Everything else in `frame()`**: fire (fixed 1/60 s ticks, heat picture at most 640 wide), particles
   (six vertices each from `gl_VertexID`, the 64 x 256 RGBA32F emitter texture, read with `texelFetch`; rows 192 to 255 are an ordered list, the rivets placed by `TL.rivetsOf`),
   the noise texture (256 x 256 R8 from the fixed xorshift recipe in `tl-gl.js`, unit 7), the photo stage.
4. **Clear output** for compositing: `final.frag` `uOut = 1` (linear, premultiplied). It is written and
   untested: test it here.
5. Only then Studio: an optional look id on `TextOverlayItem` (old projects must still open; one press =
   one undo step), preview and export through this one renderer. Ask the lead before starting this step.

## How to prove a step (no opinions, pictures)
The look book makes the reference still for any look and moment:
`tools/textlab-web/index.html?look=<id>&t=<seconds>&bare=1&w=1280&h=720` (start it with
`node tools/textlab-web/serve.mjs 8791`). Render the same look, words, size and time on the phone, pull the
PNG, and compare. Report the worst per-channel difference and attach both pictures. Start with `dark-steel`
t=3, then `neon-bar`, `blaze` (fire), `slime` (shape pass), `dust` (front-born particles), `ink-bleed`.
Fire and particles are deterministic: the same clock must give the same picture.

## Traps already paid for (card, "Files and traps")
- Half-float render targets (RGBA16F) are required; the field has a depth buffer.
- On the Note 9 a shader branch on a UNIFORM alone is skipped, a per-pixel branch round texture reads is
  not. Do not "optimise" shared shaders on a guess; time it (`GpuTimer`) and ask.
- Surface bumps use computed noise, drifting things use the noise texture. Do not swap them.
- An export must never read the tilt sensor: the renderer takes the light position as an input.
- Phone rules: `tools/phone.ps1 claim/release`; never `adb shell monkey`; never uninstall or clear data;
  the Note 9 is the sandbox; log battery temperature while timing.

## Done means
A debug screen on the Note 9 showing `dark-steel` matching the look book's still, with the frame time on
screen; the card, `tasks/STATE.md` 5.55 and the roadmap row updated; pushed, local equal to remote.
