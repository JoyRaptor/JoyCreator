# PC paper tester scout — 2026-10-29

Read-only source review. No browser, server, build, or device was started.

## What can test the full-resolution paper candidates

- The local watercolor POC is `C:/Users/JoyRaptor/Downloads/Watercolor-POC/index.html`. It accepts one PNG/JPEG through **Load paper height PNG**, converts it to a 128×128 luminance height mask, and uses that mask to affect dry pigment (`index.html:22,70,111`). The 45 master height PNGs are 2048×2048 and below its 6 MB import limit; the largest is 3,419,519 bytes. They can be compared one at a time by manual file selection. This is a narrow, approximate dry-pigment test: it does not read the paper catalogue, look images or fluid maps, and its README explicitly says it has no fluid/wetness simulation (`README.md:42,51`). The two packed comparison profiles need their B height channel extracted before import; the POC computes luminance from RGB.

- The closest in-repository WebGL option is `joybrush/lab/media/index.html`. Its `main.js` loads `../../assets/paper/catalogue.json`, adds every surface to the Paper selector, and accepts `?paper=<id>` (`js/main.js:12–13,23,63–65,526–528`). `js/paper.js` loads surface and fluid maps, expands `packed:false` height maps, and binds the catalogue’s physical fields (`js/paper.js:18–31,43–108,110–131`). It can use the current assets without a catalogue adapter. It is still a separate JavaScript media engine; it loads shared shader files but is not the production Kotlin/Android engine. The Lab uses the surface and fluid maps, not the catalogue’s look images.

- **One-paper-per-reload caution:** the Lab’s `paperSel.onchange` replaces the current paper object, while `loadSurface` creates new surface and fluid GL textures and has no corresponding deletion in that switch path (`js/main.js:528`; `js/paper.js:48,120`). Reload the page between candidates for a bounded exploratory run; a multi-paper session can accumulate those textures.

## Brush Lab and cloud URL

`tools/brushlab/BrushLab.html` runs shared `jb_tip.glsl`/`jb_grain.glsl` and the generic `cloud_256.png` grain. Its only file picker accepts JSON brush presets; paper controls are paper-grain parameters and a color (`BrushLab.html:106,130–135,199–211,384–451`). It cannot load the paper catalogue or its surfaces. The documented URL is local only: `http://127.0.0.1:8777/tools/brushlab/BrushLab.html` (`tools/brushlab/README.md:9–18`). I found no verified cloud-hosted tester URL in the inspected sources. `tools/brushlab_push.sh` watches and ADB-pushes one `brush.json` to a connected Android app; it is not a cloud paper tester (`tools/brushlab_push.sh:1–5,24–31,57–87`).

## Source checkout

Reviewed the paper assets and lab code in `C:/Temp/jb-paper-evaluation-20261029`; the watercolor POC is the separate Downloads path above. The PC Watercolor POC is useful for quick single-height-map experiments. The in-repo Media Lab is the available browser route for comparing all 45 catalogue surfaces and their fluid maps, with the stated engine-parity and texture-lifetime limits.
