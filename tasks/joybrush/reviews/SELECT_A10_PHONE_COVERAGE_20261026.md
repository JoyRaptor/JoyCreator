# A10 native UI phone coverage — prepared separately

Source-only draft on `codex/select-a10-acceptance-20261026`; not yet wired, compiled,
or part of a device-verified APK. The existing d11 twenty-case proof remains separate.
Integrate onto the Coordinator's styled candidate only after its verification gates.

The separate `selectA10` case will use these six reports and five ACK-held captures:

| Report | Real UI action / assertion | Capture |
|---|---|---|
| a10Setup | Fresh unsaved drawing, uniquely select shipped Ink by scoped drawer row; verify actual brush ID/tool | — |
| a10LinesOn | Ink hold sheet, real native Switch ON, shared per-id policy and canvas flag agree | 12_a10_lines_on.png |
| a10LinesOff | Real Switch OFF, same flag/policy agreement | 13_a10_lines_off.png |
| a10PixelsOnly | Uniquely pick a shipped media brush and hold; no Switch and exact D22 sentence | 14_a10_pixels_only.png |
| a10DrawerWhite | Ink remains OFF, real drawer shows its raster label/badge over a temporary white canvas | 15_a10_white_canvas_ink_pixels.png |
| a10DrawerDark | Real Ink sheet toggles ON again, drawer shows vector label/badge over a temporary dark canvas | 16_a10_dark_canvas_ink_lines.png |

The brush names are used only for a uniquely scoped real UI target; the actual chosen
`canvas.preset.id` must match the known shipped ID. Ambiguous or clipped targets fail.
Scrolling uses real bounded finger gestures, not direct picker/model calls.

Only the `tools` key and Ink's `joybrush.brushMakesLines.joybrush.ink` key are restored,
with exact original presence and detached typed values. No whole-preferences reset or
unrelated-key restoration. The pixel-only key is read but never written/restored.
The A10 flow does not copy, so it does not replace or blindly restore session clipboard
contents; it exposes a read-only active predicate for the existing stale-Copy guard.
Success/abort must stop gestures before selective restoration and invalidate that guard.

Paper changes belong only to the fresh unsaved benchmark canvas, with a flat white/dark
runtime fixture and exact original document paper restored. No PaperSheet acceptance,
paper history visit, saved default, paint stroke or archive write is performed. Canvas
colour is distinct from the drawer preview's own paper: those previews are explicitly
not asserted to become white/dark. Manual PNG review must preserve that distinction.

Integration still required: a separate BenchRunner dispatch/lifecycle branch, a separate
typed host manifest/case selection, actual lifecycle/queued-Copy restoration regressions,
focused compilation/tests, exact APK receipt/install, canonical script and saved-data/
owned-preference receipts. Reuse the existing run/event ACK protocol and its foreground,
PNG, identity and timeout checks. No D23 or underlying live-stroke publication claim.
