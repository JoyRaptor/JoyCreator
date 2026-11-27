# TXT-TRANSFER on the Note 9 (11-27)

Codex's `codex/r4-txt-transfer` (an export title drawn in its own graphics context and handed to Studio's export as a GPU
texture, with fences) checked on the Note 9 against `joy-creator` at d8e448c8e. The same debug fixture went into both
builds: `TitleTransferFixture` (debug only, started from `ExportDebugActivity` with `--ez txt_transfer_fixture true`).

**Fixture:** 4 s of `card2.mp4`, with a title wearing the Magma TextLab look (animated) and its own effects (glow, hue).
The title is corner-pinned, moves and turns on keyframes, and grows from 1x to 1.8x between 2.0 and 2.6 s (the title
picture is redrawn bigger: the **resize**). A hue adjustment with a feathered, turned shape mask sits above it.
`--ez txt_transfer_cut true` splits the 4 s into two clips with the title running over the cut. Each clip is its own
export item, so the second starts the title cold at 2.0 s, mid-animation: the **seek**. The range export, the natural
seek, cannot run here: it needs a sound track and the demo cards have none (a follow-up, below).

Builds: new = `test/txt-transfer-rebase` 86335da7f, old = `test/txt-transfer-base` fc8c477fa (cloud debug APKs), Note 9
at 28-30 °C, export at 960x540, 30 fps, 120 frames.

## Frames (decoded RGB, `ffmpeg`)

| Compared | Result |
|---|---|
| Old vs old, new vs new (re-exports, full and cut) | Identical decoded frames (max difference 0): each build is deterministic, so any difference below is real |
| Old vs new, full: start (0.0 s) | PSNR 47.7 dB, largest difference 10/255 |
| Old vs new, full: the resize (2.0-2.6 s, worst frame 2.0 s) | PSNR 38.4 dB, largest 47/255 on 0.08% of pixels (title edges) |
| Old vs new, full: end (3.97 s) | PSNR 42.2 dB, largest 28/255 |
| Old vs new, full: all 120 frames | mean 42.0 dB, lowest 38.4 dB |
| Old vs new, cut: after the seek (2.0 s on) | 40-42 dB, no worse than before the cut; mean 42.1 dB over 118 frames |

Side by side, the frames cannot be told apart. The difference image is thin outlines along the title's edges: the old
path draws the title into a full-frame bitmap on a Canvas and samples that, while the new one samples the title's own
texture once, through the placement and pin. The video under the title also differs very slightly, where the encoder
spends its bits a little differently. No black, stale or torn title, and no crash. The new build logged no fallback
("Using bitmap export" / "Title texture shader unavailable"). The blurred first frame is in both builds: it is the
look's own opening.

## Export time (encode; then to done, including the copy to the folder)

| Build | Full | Cut |
|---|---|---|
| Old | 13.6, 16.7 s (to done 15.7, 18.5 s) | 13.2, 13.5 s |
| New | 10.0 (first, shaders), 6.3, 5.9 s (to done 7.5, 7.0 s) | 6.3, 6.1 s |

The new build exports this title about **2x faster**. The old build spent 63-76% of its export time waiting for the title
picture to come back from the GPU (`LookTicket.await` in the export's GL_SAMPLE line).

## Verdict

Landed: the frames match within resampling and encoder differences, and the export is faster. **Not covered here:**
an export running while the preview plays, a driver without ES3 fences (the `glFinish` fallback), a rebuilt graphics
context, and the Fold (no Fold on hand).

**Follow-up (existing, not from this change):** a range export of a project with no sound track fails ("The sound
pass failed twice": `CHUNK sound pass` gets an Asset loader error on a silent clip). Seen on the new build, which
does not touch the sound pass; not re-run on the old build.
