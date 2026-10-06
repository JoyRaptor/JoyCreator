# Board connection pass — October 6, 2026

Claude owns the active brush and paper engine work. This pass edits board presentation, board metadata, the export adapter and Activity board glue in the isolated integration checkout. No brush/paper engine, shaders, presets, materials, PaperSheetView, GlPaintEngine or JbCanvasView changes. Shared primary LANES and AGENT_BOARD notices are appended without overwriting other agents' notes.

## Export and held-layer checkpoint

Source `f9d16c2e`, pushed `origin/codex/region-routing`. Watcher build succeeded, 276 tasks, 3m12s. Note9 installed APK and `base.apk` both SHA-256 `D0A94614167D4E4AC0B620A6C61CDF0C300D3D81D76BB443A52E6E2E995463E5`.

- Board corner and app menu open the shared 236dp ExportChoiceSheet. Animation: all/range/current frame; GIF, PNG frames, sprite sheet + JSON, current-frame PNG. Image: this board or all Image boards as numbered PNG files. Sprite: whole sheet + grid JSON.
- Selected board and stable frame/range identities survive the file picker, including Activity state restoration. Changing the frame list refuses the captured range; navigating the saved cursor does not change the requested export frame.
- All outputs stage completely in a fresh private cache directory before destination streams are opened. Folder outputs use a fresh child folder; sequence timing and sheet sidecars are copied after image files. Failed writes report incomplete output and attempt removal of only the new folder. Existing files are not overwritten by folder exports.
- Existing paper renderer is reused; include-paper is explicit, transparent paper disables it. Raster backend refusal remains explicit for unsupported vector content; common board/layer metadata remains PAINT/INK capable.
- K9 marker column: runner = animated, grey mountain = held. Native mask/paper controls remain separate. Toggle calls the existing bounded content transaction and creates one undo step.
- Studio/SpriteLab chips remain disabled: the app's existing activities require project/sheet IDs; neither is a receiver for JoyBrush frame files. No fake handoff is implemented.

Verification: core suite 1625 tests, zero failures/errors, four optional-corpus skips; new export tests seven passing; Android native suite 56 passing. The initial complete androidkit run also passed its 259 existing tests; three new static fixtures failed because the fixture assumed an explicit sharedCelId on a legacy static document. Corrected fixtures, all seven new tests passed, with distinct frame pixels proving target stability. Final metadata/menu label-only changes compile in the watcher APK.

Actual Note9:

- Shared Animation export sheet opens and shows board size, seven frames and current saved frame 7.
- GIF saved through Android Files to Downloads. Pulled and independently decoded: 540×300, seven frames, 30,607 bytes, delays 80/80/80/80/170/80/80 ms. The doubled held frame remains doubled.
- Range form accepts frames 2–3. PNG frames export creates a fresh folder with exactly `0001.png`, `0002.png`, `Animation.timing.txt`; both PNGs independently decode 540×300. Timing file is `0001.png\t83` and `0002.png\t83`.
- First layer runner toggles to mountain; one Undo restores runner. Selected layer thumbnail dimensions/cropping stay unchanged.
- No new crash-buffer entries during this acceptance pass; earlier morning crash records are historical and remain in the buffer.

Not yet phone-proven in this pass: Sprite sheet/JSON, batch Image PNG, current-frame PNG picker restoration, provider write failure cleanup, physical S Pen feel. These have scoped backend/native tests where applicable; do not call them device accepted.

## Next connection

Sprite grid shelf controls are connected in a separate source checkpoint: count/pixel modes, typed dimensions, steppers, whole-cell fitted geometry and session-only sub-grid 2–8. Subagent hit usage limit before any edits; root took ownership. This does not connect cell swaps or sequence preview. Eight BoardDocumentOps tests pass; 60 native Android tests pass. Rapid queued stepper taps derive from the live grid and each remain one metadata edit. Typed forms reject changed board/drawing identities. Grid edits preserve layer/cel addresses and top-left, refuse locked or overflowing geometry, and retain only complete cells. Guide/mode changes do not save or create history and reset on document identity change. Native guide tests wait for the production animation-frame publication instead of reading a view before it has published input.

Remaining broad board work: fullscreen seamless tiling/wrapped commits, Sprite all-layer swaps and sequence UI, onion compositor, bounded Animation geometry/duplicate/remove/move-all transactions, Studio/SpriteLab receiver bridges, shared Studio export presentation, Frost forms/adaptive ink/reduced motion, fill pen and full device acceptance. Boards are not declared complete.
