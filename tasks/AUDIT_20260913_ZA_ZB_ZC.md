# AUDIT 2026-09-13 — SPEC ZA / ZB / ZC (adversarial auditor, independent)

Method: read every diff (ZA committed `6d03b418`; ZB and ZC staged, audited pre-commit),
re-ran the harnesses myself, reproduced the lint negative control myself, verified the dex
myself, and checked each lane's strongest claims against the code rather than the report.
No device work (ZA holds the DEVICE token); everything below is compile/harness-side.

**Grades: ZA A− · ZB A− · ZC B+** — no blocker found in any lane. Flaws below are graded
and owned.

---

## Independently verified (not trusted from the reports)

| check | result |
|---|---|
| `run-clipwarp.sh` | ALL PASS (real serialiser via reflection — confirmed it drives `ProjectStorage.serializeClipObject`, not a transcription) |
| `persist_lint.py` green | PASS, Clip 60 fields, CUSTOM entry present |
| **lint negative control, reproduced by me** | broke `clip.getCornerPin` → `FAIL Clip.cornerPin` → restored byte-identical → green. The lint has teeth. |
| `build-verify.sh CornerPinTextView` / `pinOwner` | both VERIFIED in the packaged dex |
| `run-mesh.sh` / `run-pinbudget.sh` / `run-flip.sh` | 66/66 / ALL GREEN / ALL GREEN |
| KeyframeCodec track tolerance | read the codec: writes/reads ANY track name, no whitelist — ZB's "no new persisted state" claim holds |
| ZC "TextOverlayRenderer is dead code" | VERIFIED — both branches in CompositeExportOverlay's text loop `continue` (`:803`, `:829`); the `render` call at `:871` is unreachable; the live caller is `TextFxGlEffect:119` with a pin-less rebuilt item |
| ZC "pin persistence already ungated" | VERIFIED — pin block writes for all overlays; mesh stays `isImage()`-gated (`:2488`) |
| ZA complementarity | VERIFIED structurally: `filterSpriteItems` drops every `wantsGl()` sprite; the promotion block filters promoted sprites out of `exportSpriteItems`; `allGlImagesZa` is built identically to `allGlImages`, so the re-derivation cannot diverge; the union emits each warped sprite exactly once |
| ZA stamp parity | preview `spritePip` and export `drawMeshFrame` pass field-for-field identical args to `MeshStampGl.renderToStamp`; `rasterContent` does NOT bake the pin (the stamp gets it once — no double distortion); flat path applies pin inside mirror, the SPEC K order |
| ZC view swap safety | every `TextBoxView` check is `instanceof`; no bare `new TextBoxView(` remains; snapshot replay covers everything `TextBoxView.onDraw` reads (the editor is a child view, outside `onDraw`) |
| ZC clock parity | layer `bind`s with `currentTimeMs`; export passes `timelineMs` — same clock |
| ZB "nothing reads it yet" | grep: no production caller of `Clip.hasCornerPin`/`wantsGl` — acceptance 6 holds structurally |
| ZC gate stays shut | no text PinChannel; `setBendAvailable(false)` stands (FaditorEditorActivity:25405) |

---

## ZA — sprite GL export (committed) — A−

1. **[Medium · needs a LEDGER line] Warped-vs-plain sprite z is type-ordered now, not lane-ordered.**
   Pre-ZA both sprites drew on Canvas in lane order. Post-ZA a warped sprite is GL, so it always
   composites below every plain sprite (Canvas pass runs after all GL effects) regardless of lane
   order. Preview has the identical split, so rule 7 holds — the two surfaces agree with each
   other — but the lane intent is lost for that pair, and the commit message says "true lane z"
   without scoping that claim to GL-vs-GL. Same compromise class as blended-images-vs-text, which
   IS documented; this one is not. Record it.
2. **[Low] Image emission order changed from bucket order to lane-z sort.** The claim that the
   below bucket sorts wholly beneath the above bucket is asserted, not proven by a test. Covered
   only if frame parity runs green on a multi-image project.
3. **[Low] `overlayZById` defaults a missing id to 0** (sorts to bottom). Ids come from the same
   walk, so it should never fire; benign.
4. **[Low, accepted pattern] `SpriteFrame.getBitmap` allocates a full-frame bitmap per frame**
   (~8 MB at 1080p) — the documented BitmapOverlay contract, parity with the image path.
5. **[Process] `6d03b418` swept ZC's staged `CompositeExportOverlay` hunks** (the `textPinMatrix`
   field + text-branch concat) — the bare-commit corollary again, file-granularity this time.
   ZC documented it; ZA's commit message does not mention carrying another lane's hunk. Content
   verified intact in HEAD.
6. Acceptance 3 (blend above bent sprite) and the frame comparison remain device-owed — the lane
   is ACTIVE on that now with the DEVICE token.

## ZB — Clip warp model (staged) — A−

1. **[Medium] `SpineSnapshot.matches()` + `meshEqual` + `keysEqual` + `trackList` (~55 lines) have
   NO production caller.** `commitSpineTransform` (FaditorEditorActivity:25377) records undo
   unconditionally; only `ClipWarpTest` calls `matches`. This is the repo's own §3a failure mode
   (test-covered dead code that rots). Either wire it into `commitSpineTransform` so a no-op
   gesture stops pushing an undo step, or leave a comment naming who will consume it (ZD).
2. **[Low · bill ZD now] `hasCornerPin()` walks 8 tracks × all keyframes, and `cornerPinMatrix()`
   allocates `float[8]` per call.** Fine while nothing reads it; SPEC ZD will call these per frame
   per clip. Cache or pass a scratch array consciously.
3. **[Low] `pinOwner` edge:** a demoted spine clip with stale nonzero pin keys in
   `spineTransform` makes `hasCornerPin()` (and therefore ZD's `wantsGl()` → GL promotion) true
   even when the overlay envelope owns the visible values. Conservative, but ZD should know.
4. **[Info] Read-side `getAsFloat()` is unguarded** — exactly parity with the sprite and overlay
   read sites, so not a new risk.
5. Byte-identical save is harness-proven; the on-device save/diff and the forward-compat install
   remain owed (lane says so).

## ZC — text pinned view (staged) — B+

1. **[Medium · NOT documented] Caret misalignment on a pinned box being edited.**
   `updateEditorInsets()` reads the overridden `boxInsetPx()` (now including `pinInsetPx`), so the
   EditText sits over the box rect — but the drawn glyphs are homography-distorted while the
   EditText renders undistorted. The caret lands wrong on any pinned box in edit mode. Reachable
   only via hand-edited JSON today, but the class doc claims "the caret stays on the glyphs" —
   true only for unpinned boxes. Record it as an accepted limitation or suppress the pin while
   `editor != null`.
2. **[Medium · documented] Text WITH FX exports unpinned.** `TextFxGlEffect:119` rasterises a
   rebuilt, pin-less item; the preview shows it pinned. Latent only (no authoring UI), and the
   LEDGER names it as a follow-up — but acceptance 1 is true only for PLAIN text, and the sheet's
   deliverable should say that in so many words.
3. **[Low · borderline on rule 5] A refused solve draws UNPINNED, not nothing.** Defensible for
   text (readable ≠ vanished; stored data untouched, so it is a render fallback, not a clamp) —
   and the decision IS stated in the doc comment. Keep, but it should survive a second opinion.
4. **[Low] `excursionFraction` allocates `float[2]` per tick per pinned box** — parity with the
   image branch; negligible.
5. Device work owed (pinned screenshots, sharpness photos, JSON round-trip) — now possible; ZA
   holds the DEVICE token, so ZC's device pass queues behind it.

---

## Process observations

- Lane discipline held on all three sheets: correct claims, staged-only work, owed items listed
  rather than hidden. The staged-only habit is the reason this audit could see ZB/ZC whole.
- Untracked in the tree: `word30.mp4`, `ws20.mp4`, `ws30.mp4` — JoyRaptor's? Flagging, not touching.
  `SpriteSheetEditorActivity.java` carries unstaged SpriteLab-lane work — likewise untouched.
- Audit artifact: this file. Staged, not committed (no instruction to commit).
