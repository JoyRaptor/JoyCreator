# Lessons Learned

## `MediaMetadataRetriever` is not thread-safe and must not be allocated per frame

**Pattern:** `android.media.MediaMetadataRetriever` holds a native parser tied to
a single data source. The framework documentation does not guarantee thread
safety; concurrent `setDataSource()` / `getFrameAtTime()` calls on the same
instance corrupt state or crash mediaserver.

**Problem:** Creating a new `MediaMetadataRetriever` for every still-frame
extraction or dimension probe is slow (native setup + file open) and leaks if
not released in a `finally` block. In export/transition paths that decode many
frames, the per-frame allocation also churns the GC.

**Rule:** Use one retriever per worker thread, never share across threads:
- For single-threaded composition building, a `ThreadLocal<MediaMetadataRetriever>`
  is enough. Acquire once per thread, switch `setDataSource()` when the URI
  changes, and release in a `try/finally` after the composition is built.
- For per-overlay retrievers (e.g. `BitmapOverlay` subclasses decoded by Media3
  on worker threads), keep the retriever as an instance field of the overlay and
  synchronize access with a private lock. This gives "one retriever per overlay
  instance", which is safe as long as Media3 does not call the same instance
  concurrently; the lock defends against that case.
- Always call `release()` and clear the reference. Missing `release()` leaks the
  native retriever and the underlying file descriptor.

**Trigger:** User reports slow exports, GC stutter during loop still-frame
extraction, or intermittent native crashes in `libstagefright` / `mediaserver`
when transitions or loop extensions are used.

## Avoid allocating `MaskFilter`/`Shader` objects inside per-frame render paths

**Pattern:** Android `Paint` helpers such as `BlurMaskFilter` and `LinearGradient`
allocate native objects. Creating them inside `onDraw()` / `render()` every frame
is a GC and native-allocation hot spot, especially for animated preview/export
overlays that run at 30 fps.

**Problem:** `WaveformStyleRenderer.drawFrame()` previously called
`new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)` on every frame whenever
glow was enabled. The object was immediately attached to `glowPaint` and
eligible for GC after the frame, causing per-frame allocation pressure.

**Rule:** Cache the filter (or shader) as a field and recreate it only when the
input parameters change. Store the last parameters (`lastGlowRadiusDp`,
`lastDensity`) and compare before allocating. This preserves behavior while
eliminating per-frame allocation.

**Trigger:** Profiler shows frequent `BlurMaskFilter` allocations, or choppy
preview/export playback in a visualizer/effect that uses glow, shadows, or
gradients.

## BitmapOverlay texture cache requires a fresh Bitmap per frame

**Pattern:** `androidx.media3.effect.BitmapOverlay.getTextureId()` keys its
upload cache on `bitmap != lastBitmap || bitmap.getGenerationId() != lastBitmapGenerationId`.
`Bitmap.getGenerationId()` is bumped only when the underlying native memory is
REALLOCATED — NOT when pixels are mutated in place via `Canvas.drawXxx()`.

**Problem:** If your `BitmapOverlay` subclass keeps a single `Bitmap` field and
draws into it every frame (returning the same instance), the first frame uploads
to the GL texture, and every subsequent frame hits the cache and re-samples the
stale texture. The export then shows the first frame frozen for the rest of the
file. The Media3 pipeline logs nothing; this is a silent correctness bug.

**Rule:** Return a NEW `Bitmap` from `getBitmap(long pts)`. Either:
- `Bitmap result = Bitmap.createBitmap(scratch); return result;` (allocates
  per frame, ~8 MB/frame for 1080p), OR
- bump the generation id by mutating via `setPixel` on a sub-pixel, OR
- switch from `BitmapOverlay` to a custom `TextureOverlay` that owns a GL texture
  and updates it in place (no bitmap round-trip).

When using the per-frame allocate pattern, recycle the previous returned bitmap
at the start of the next `getBitmap()` call (after the texture has been
uploaded) to keep native memory bounded.

**Trigger:** User reports "no overlays / no text / no captions in exported video"
despite a clean compile and no errors in logcat. The overlay is being created,
the GL effect is being added, but the texture is never updated past frame 1.

## GLSL uniform stripping on Adreno — declare in `main()` OR make it `const`

**Pattern:** The Adreno 650 GLSL compiler (and many mobile drivers) aggressively
strips `uniform` declarations that are never referenced in `main()`. If the
shader body uses the uniform inside a helper function (e.g. `vec4 transition(vec2 uv)`)
that is itself only called from `main()`, the driver may or may not keep it —
the behavior is undefined and depends on the optimization pass.

**Problem:** `glGetUniformLocation` returns -1 for a stripped uniform, then
`glUniform1f(location, value)` is a no-op or NPE depending on the path. The
`gl-transitions` style library relies heavily on a `ratio` uniform (e.g.
`uv.x * ratio`), so a stripped ratio of 0 collapses the entire transition
horizontally and renders black/garbage on Adreno devices.

**Rule:** For shaders whose main consumer is `main()`'s body, use
`const float NAME = <literal>;` injected at string-generation time. Compute the
value in Java/Kotlin and bake it into the shader source before compiling. The
`const` qualifier is NOT stripped because it is a compile-time constant.

For preview-vs-export divergence (where you want the same shader but different
behavior per GL context), keep two separate template strings. Don't try to
share one template and patch it — drivers behave differently in SurfaceView vs
FBO paths.

Also: bundle-param shaders (those that come with a `// = N` default comment) get
their uniforms INJECTED by the catalog loader. The bundled-path `sanitize()` must
drop the body's own `uniform float NAME;` declarations to avoid duplicates.
External (user-pinned) shaders keep their own uniforms, so the sanitize path
for them is different. Track these as two code paths.

**Trigger:** User reports transition renders correctly on Emulator/Adreno 640
but appears inverted or all-black on Adreno 650. The diff is driver-specific
uniform stripping.

## Effect ordering in Media3 export pipelines must be a single shared helper

**Pattern:** Media3 Transformer applies `videoEffects` in DECLARATION ORDER.
A post-process effect (e.g. opacity/fade/vignette) must come AFTER the
`OverlayEffect` for it to affect the composited result. A `Presentation` effect
for canvas resize must come LAST so it scales the fully-composited frame.

**Problem:** When the same pipeline is assembled in multiple call sites
(`buildClipItem`, `buildLoopExtensionItem`, `buildTransitionItem`, future
`buildStillLoopExtensionItem`), each site is free to put the opacity effect in
the wrong slot. The result is a partial fade that doesn't affect overlays
(opacity before overlay) or a fade that doesn't reach the canvas (presentation
before opacity). These bugs are silent — the export completes, the file plays,
and the user sees the wrong output.

**Rule:** Extract one helper:
```java
private List<Effect> assembleClipVideoEffects(Clip, project, timelineCursorMs, ...) { ... }
```
The canonical order (from first applied to last applied) is:
1. `SpeedChangeEffect`
2. `ScaleAndRotateTransformation` (rotate/flip)
3. `Crop` (preset or custom)
4. `EffectStack.toEffects(...)` (color grading)
5. `OverlayEffect` (text + captions + waveform)
6. **Post-process** (opacity, vignette, future fades) — affects the composited result
7. `Presentation.createForWidthAndHeight(...)` — canvas resize

All three (or N) call sites use this helper. A fresh contributor who adds a
new effect can't accidentally place it wrong.

**Trigger:** User reports "opacity/fade works for video but not for overlays"
or "the fade is the wrong color" — the effect is in the wrong slot in the
videoEffects list.

## Bitmap mutation in place defeats Android's generationId-based caches

**Pattern:** `Bitmap.getGenerationId()` is a monotonic counter bumped only on
reallocation. Any cache (GL texture, view invalidation, surface invalidation)
that keys on generationId assumes "same instance + same gen = same pixels".

**Problem:** This assumption is the OPPOSITE of canvas-mutation semantics. If
you draw to a `Canvas(Bitmap)` repeatedly, the pixels change but the generation
id doesn't. The cache hits, the consumer reads the stale texture, and the bug
is silent. This pattern caused the "no text / no captions / no waveform in
export" bug — `CompositeExportOverlay` returned the same scratch bitmap every
frame and the GL texture was only uploaded once for the lifetime of the export.

**Rule:** For any pattern that involves an external cache keying on
generationId, return a new bitmap per frame, or bypass the cache entirely. The
simplest workaround is `Bitmap result = Bitmap.createBitmap(scratch); return result;`
— the cost is ~8 MB/frame allocation for 1080p ARGB_8888, but for an export
pipeline that runs at 30 fps for a few minutes, that's bounded and acceptable.

A future optimization is to swap from `BitmapOverlay` to a custom
`TextureOverlay` that owns a GL texture and updates it in place — no Java
bitmap round-trip, no native memory churn.

**Trigger:** Any class that extends `BitmapOverlay` and mutates the backing
bitmap must verify it returns a fresh instance, not a mutated one. The
generationId test (`bitmap.getGenerationId() != lastBitmapGenerationId`) is the
guard.

## Per-frame presentation time is timeline-absolute, NOT item-local

**Pattern:** Media3 Transformer adds `offsetToAddUs` (the cumulative duration
of all preceding `EditedMediaItem`s) to each frame before handing it to effects.
`presentationTimeUs` in `GlShaderProgram.drawFrame()` and
`BitmapOverlay.getBitmap()` is therefore the absolute composition time, with the
first frame of the 2nd clip starting after the full duration of the first clip.

**Problem:** Code that assumes `presentationTimeUs` is reset to 0 per item will
add an item start offset to an already-absolute timestamp, double-counting the
position. For `CompositeExportOverlay` this placed text/caption/waveform lookups
far outside the clip window for every clip after the first. Conversely, code
that correctly subtracts an item offset (e.g. `OpacityExportShaderProgram`) was
accused of being wrong because the rest of the pipeline used the opposite
convention.

**Rule:** Treat `presentationTimeUs / 1000` as absolute timeline milliseconds
for all per-frame effects. Pass each item's timeline start offset into effects
that need clip-local time and compute:
```java
long timelineMs = presentationTimeUs / 1000;
long clipLocalMs = timelineMs - itemTimelineStartMs;
```
Use `timelineMs` for timeline-level decisions (text overlay visibility,
wavform timeline-to-source mapping) and `clipLocalMs` for clip-local lookups
(opacity keyframes, caption source time). All effects on the same item MUST use
the same convention; do not mix absolute and relative interpretations.

**Trigger:** Multi-clip export where the first clip looks right but later clips
have missing/stuck overlays, captions, or transitions.

## Relink UX must show a catalog, not a blind queue

**Pattern:** When asking the user to relink missing media, never present a blind
queue ("pick file 1 of 3") without context.

**Problem:** The user doesn't know *which* clip they're relinking, can't tell if
the picked file was accepted for the right slot, and can accidentally relink
three different missing clips to the *same* file without any warning.

**Rule:** Always show a relink catalog (à la After Effects):
- List ALL clips/audio clips with OK / MISSING status badges.
- Show the clip's timeline position and original filename for each row.
- Highlight the *current* relink target prominently.
- Validate the picked file before accepting (type, duration ballpark).
- Confirm with the user before replacing: "Replace clip N (was: X) with Y?"
- Warn if the same file was already used for a different clip.
- Check off repaired items so the user sees remaining vs. repaired count.
- For in-app-generated files (remux cache), say so and offer to fall back
  to the original source if possible.

**Trigger:** User feedback after a bad relink testing experience.

## NEVER persist cache/remux paths in the project JSON

**Pattern:** The app remuxes fragmented MP4s for seekable playback and stores
the remuxed file in cache. If the remuxed path gets saved as the clip's
`sourceUri` in the project JSON, the project breaks when cache is cleared
(reinstall, system cleanup, storage pressure).

**Problem:** User opens a project after an app update. The project references
`/cache/faditor_remux/...mp4` which no longer exists. The relink catalog shows
"MISSING — generated, cache cleared" but the user doesn't know what original
file to pick. They have to relink every chopped-up piece individually, all of
which came from the same original file.

**Rule:**
- ALWAYS store the ORIGINAL source URI in the project JSON.
- The remuxed path is a TRANSIENT runtime concern — never persist it.
- When loading a project, remux on-the-fly if needed, but keep the original URI.
- If a project JSON already has a cache path, try to recover the original
  from the path pattern or display name.

**Trigger:** User feedback — relink always fails because it's looking for
remuxed cache files, not originals.

## Confirmation dialogs should appear near the triggering button

**Pattern:** The close (X) and Export buttons are in the top bar, but their
confirmation dialogs appear at the bottom of the screen.

**Problem:** One-handed phone users have to shift their grip to reach the
confirmation button, which is awkward and error-prone.

**Rule:** Dialogs/sheets triggered from the top bar should appear near the top
(or center) of the screen, not at the bottom. Use `Gravity.TOP` or a centered
dialog instead of a bottom sheet for top-bar actions.

**Trigger:** User feedback about one-handed usability.

## Saved projects must use the same seekable playback URI as new projects

**Pattern:** New-project loading resolves/remuxes the source and passes a temporary playback URI to the player. Saved-project loading must do the same for the selected clip and for every later segment switch, including old projects that do not already have a cached remux.

**Problem:** If a saved project loads the original URI directly, fragmented MP4/content URIs can fail random-access seeking. Pressing play then appears to reset to 0, and split clips can play from the first clip/project start instead of the segment under the playhead.

**Rule:** Centralize preview loading through one helper that resolves file/remuxed playback URIs without changing the persisted original source URI. For old fragmented MP4s, remux synchronously if no cached remux exists yet. The play button should also ensure the loaded clip matches the segment under the playhead before seeking/playing.

**Trigger:** User reported saved/reopened projects always reset to zero on play, including after splitting into a second clip.

## Playback must not let buffering positions overwrite the user's seek

**Pattern:** The editor calls `seekTo()` immediately before `play()`. ExoPlayer can still report the old/current frame while the new seek is buffering.

**Problem:** If `play()` trusts that stale position, it can seek back to trim start. If the playhead updater uses `playWhenReady` instead of actual playback, it can overwrite the timeline playhead with 0 while the seek has not landed.

**Rule:** Track a short seek-in-flight window after `seekTo()` and skip `play()`'s trim-start correction during that window. The playhead updater should only pull position from ExoPlayer while playback is actually playing, not merely while `playWhenReady` is true.

**Trigger:** User reported play always reset to 0 and playhead did not scroll with playback.

## Always update ALL player trim bounds when clip in/out changes

**Problem:** Every subsequent `seekTo()` computed `absoluteMs = trimStartMs + position`
using the stale `trimStartMs`, then clamped to `[trimStartMs, trimEndMs]`. When
`trimStartMs > trimEndMs` (old inPoint > corrected duration), ALL seeks collapsed
to `trimStartMs` — making every timeline tap jump to the start of the clip.

**Rule:** Whenever clip trim points change (correction, relink, split, etc.),
update BOTH `trimStartMs` and `trimEndMs` in the player manager. Never update
only one bound. Add a `updateTrimBoundsSilently(start, end)` method for cases
where you need to update bounds without seeking.

**Defensive:** `seekTo()`, `play()`, `getCurrentPosition()`, `getDuration()`
should all use `Math.min(trimStartMs, trimEndMs)` as the effective start to
avoid nonsensical clamping when state is temporarily inconsistent.

**Trigger:** User reported "clicking anywhere on the timeline always goes to
the start of the selected video."

## Editor UI polish must match the user's mental model

**Pattern:** Small visual primitives (icons, panels, and timeline taps) are not
cosmetic; they are how users understand whether an edit is safe, reversible, and
where the next action will land.

**Problem:** Users noticed confusing insert icons, persistent asset-browser
panels, abrupt transcript/AI panel appearance, and black timeline gaps that felt
permanent even though they were non-destructive.

**Rule:**
- Use obvious directional markers for insert/drop targets.
- Make panels slide from the direction users expect and disappear completely
  when collapsed.
- Keep AI/editor overlays translucent enough that the user still feels inside
  the editor.
- Treat black timeline spans as non-destructive cuts: tapping them should be an
  easy restore path.
- Give detected silence gaps a one-tap remove path after preview.

**Trigger:** User feedback after testing the asset browser, transcript panel, AI
assistant, insert marker, and silence/heal workflow.

## Fast-path optimizations must respect the slow-path feature set

**Pattern:** Media3 Transformer's `experimentalSetTrimOptimizationEnabled(true)`
skips the entire effects chain — overlays, color grading, opacity, etc. — to
do a near-lossless stream copy. Any check that decides "is this export a simple
trim?" must include the SAME feature set the slow path supports.

**Problem:** A "simple trim" check that ignores text overlays, captions, and
waveform overlays will skip the slow path even when the user has added those
features. Result: user adds text to a single-clip simple project, exports, and
gets NEITHER the text NOR the optimized trim — worst of both worlds.

**Rule:** When the slow path adds a feature, the fast-path short-circuit MUST
exclude projects that use that feature. For the export pipeline specifically:
- `isSimpleTrim` (or its equivalent) must check `!hasTextOverlays() && !isCaptionsEnabled() && !hasWaveformOverlays()`.
- If you add a new post-process effect (vignette, fade, LUT), extend the
  short-circuit to exclude projects that use it.
- The fast-path log line should be paired with a corresponding slow-path log
  line that fires when the slow path is chosen BECAUSE of a feature, not just
  when it happens to be slow.

**Trigger:** User reports "no overlays / no text / no captions in export" for
the simplest case (single clip, no canvas, no effects). The fast-trim
optimization is running and silently dropping the overlays.

## GLSL transition assets must compile against the actual uniform injection contract

**Pattern:** Bundled GL transitions in `assets/gl_transitions/*.glsl` are
templated by `GlTransitionShaderLoader` — it injects the catalog's params as
`uniform float NAME;` (and one special `uniform vec4 bgcolor;` for GridFlip),
and `sanitize()` drops the body's own declarations of those uniforms to avoid
duplicates. Other uniform types (vec3, int, ivec2, sampler2D) are NOT injected
and NOT dropped by sanitize.

**Problem:** A shader that declares `uniform vec3 color;` or `uniform int
segments;` will:
- either fail to compile (if the declaration is also kept by sanitize), OR
- silently default to 0 (if the uniform is never set) — meaning the effect
  renders with the wrong color / wrong count.

Either way the user sees a broken transition with no error in logcat.

**Rule:** When adding a new GL transition asset, declare ALL non-catalog
uniforms as `const` (not `uniform`) and hardcode the default value in the
shader body. Catalog-injected `float` uniforms are fine — those will be
injected. Special-case the GridFlip `vec4 bgcolor` pattern in `uniformsFor()`.

When auditing existing assets, grep for `uniform` in every `.glsl` file and
cross-reference against the catalog entries for that transition ID. Anything
that isn't `float NAME` (in catalog) or `vec4 bgcolor` (GridFlip special-case)
needs to be hardcoded as `const`.

**Trigger:** A new bundled transition renders incorrectly on the device but
compiles fine in the editor preview (because the preview uses a different GL
context that may not strip the uniform). Logcat is clean. User has to bisect
to find the broken shader.

## Media3 effect ordering is a silent invariant — opacity must be last

**Pattern:** When assembling `videoEffects` for an `EditedMediaItem`, every
transform must be added in the right order, because Media3 applies them in
declaration order. The `OverlayEffect` composites bitmaps onto the input
texture, and a per-frame post-process (e.g. opacity envelope) must run AFTER
it, not before, or the overlay is drawn on top of an already-dimmed video
and the visual result is "dimmed background with bright foreground" — not a
real fade.

**Problem:** The export pipeline has FOUR separate effect-list assembly sites
(`buildClipItem`, `buildLoopExtensionItem`, `buildTransitionItem`, and any
future site). Each has to know the right ordering. Easy to get wrong:
opacity was placed *before* the overlay, so text/captions/waveforms stayed
at full strength over a dimmed video.

**Rule:** Always make the post-process (color grading, opacity, vignette,
fades) the LAST effect before `Presentation.createForWidthAndHeight`. The
canonical order is:
`speed → rotate/flip → crop → color-grade → overlay → post-process → presentation`.
Bake this into a single helper that takes a list of "pre" and "post" effects
so future authors cannot accidentally re-introduce the bug.

**Trigger:** User reported "no fading or opacity black layer even though I had
animated key frames for it" — a whole-frame fade was being drawn over by the
overlay, hiding the envelope.

## Premultiplied alpha is the correct output for a compositing pass

**Pattern:** Shader passes that lie inside a compositing chain (between the
video and the encoder) must output RGBA that is consistent with the next
consumer. The simplest contract is "premultiplied" — RGB is scaled by alpha
so the next pass can do `out = src + dst*(1-src.a)` without surprises.

**Problem:** A naive `vec4(c.rgb * uOpacity, c.a)` keeps alpha=1.0, which
means a downstream compositor sees an opaque texture and never blends it
correctly. For H.264 (no alpha channel) the result accidentally works for
"fade to black" because RGB=0 is black, but the moment the texture is used
in a true alpha blend (overlay above opacity, HDR path, alpha-aware encoder)
the math is wrong.

**Rule:** A per-pixel scale/shade pass that also wants to fade the frame
should output `vec4(c.rgb * scale, c.a * scale)`. The shape is the same
multiplication for RGB and alpha, so the contract is "I am a premultiplied
opaque-only modifier" — easy to reason about, and downstream can do
`outputColor.rgb = src.rgb + dst.rgb * (1 - src.a)` to recover the over
operator.

**Trigger:** Code review of the opacity export shader — original
`gl_FragColor = vec4(c.rgb * uOpacity, c.a)` was correct for H.264 by
accident only.


## A falling source count means code left the tree — stop and find out what

**Pattern:** `typecheck.sh` prints "TYPECHECK OK — N sources, M classes". That
N is not noise: it is an inventory of what compiled. A fix commit that moves
649 → 640 sources did not fix anything; it silently dropped ~870 lines and the
checker stayed green precisely because the broken code was no longer there to
compile.

**Problem:** C1.E's seven FX processors and A6's ResamplingAudioProcessor were
committed, reported BUILT, then vanished when branch history was rewritten.
`git log --diff-filter=D` finds nothing because nothing deleted them — they
just stopped being ancestors. The next typecheck read "OK" on a tree missing
nine files.

**Rule:** Before trusting any green typecheck, compare the source count to the
last known count. If it DROPPED, stop and account for every file that left
(`git show --stat`, `git log --all --follow` on the missing paths). Green after
a shrink usually means the thing you were supposed to be testing is absent.
Never report BUILT off a run whose counts you did not read.

**Trigger:** Any "TYPECHECK OK — N sources" line where N is lower than the
previous green run, or any row whose code files are not all present in HEAD.

## Compiling is not working — model-touching rows need a harness before BUILT

**Pattern:** `typecheck.sh` proves types resolve. It says nothing about
behavior. A processor that outputs silence, a resampler that glitches at chunk
boundaries, an EQ that ignores its gain parameter — all of these typecheck
clean and are broken.

**Problem:** A6's ResamplingAudioProcessor carried the wrong fractional phase
across queueInput calls (997-frame chunks produced 13044 output frames vs 12000
monolithic for the same 0.25 s input) and FxChain drained each child processor
twice (`getOutput()` swaps in EMPTY_BUFFER, so the second call returned nothing
and the chain emitted silence). Both shipped as "BUILT" behind a green
typecheck. Nothing had ever executed them.

**Rule:** Nothing reads BUILT on a typecheck alone. Every row that touches
model/processor/math code gets a JVM harness (run-envelope.sh pattern: pure
math, off device) with a NEGATIVE CONTROL — a deliberately wrong implementation
fed through the same measurement code, which must be caught. If the negative
control passes undetected, the harness has no teeth and cannot certify
anything.

**Trigger:** Marking any row BUILT where the only evidence is "TYPECHECK OK".

## Never change shipped code to make a checker happy

**Pattern:** typecheck.sh compiles against R.jar, a build ARTEFACT. A resource
id added today is not in yesterday's R.jar, so a DIRECT `R.id.foo` reference
fails typecheck even though `res/layout/*.xml` plainly contains it. The
tempting workaround — `getResources().getIdentifier("foo", "id", pkg)` — makes
the checker green by deleting the very reference the checker exists to verify.

**Problem:** Two shipped call sites were written as getIdentifier string
lookups purely to silence typecheck (`transcript_speaker`, then
`btn_voiceover`). A string lookup loses compile-time checking (a rename
becomes a silent no-op button), costs a runtime scan, and R8 resource shrinking
in RELEASE builds can strip an id with no compiled reference — works in debug,
quietly dead in the shipped app. The actual root cause was a stale FILE LOCK on
R.jar that blocked Gradle from regenerating it; the checker was reporting a
real environment failure all along.

**Rule:** If typecheck cannot see a resource that plainly exists in res/, that
is a TRUE NEGATIVE about the checker — its own header says so. Report it and
stop. Fix the build environment (stale lock on R.jar); never convert direct
`R.id` references to reflection-style lookups to get a green run.

**Trigger:** The urge to write `getResources().getIdentifier(` anywhere in
app code, or any "cannot find symbol R.id.X" where X exists in res/.

## `git commit` without pathspecs sweeps other agents' staged work — verify the stat

**Pattern:** Multi-agent sessions share one working tree. Another lane's finished-but-
uncommitted files sit STAGED in the index. `git commit` with no pathspec commits the whole
index, not "my changes".

**Problem:** Twice in one night, commits meant to be renderer-only or UI-only swept in a
parallel lane's staged ExportManager/fx-wiring hunks (13 files instead of 5; an export file
inside a "renderer-only" commit). Each time required `git reset --soft` + selective
unstage + recommit — and each reset momentarily rewound shared history while another agent
was actively building.

**Rule:** Stage by NAMED path (`git add <file> ...`, never `-A`), then BEFORE finishing run
`git show --stat HEAD` and confirm every file in the list is yours. If a foreign path
appears: `git reset --soft HEAD~1`, `git restore --staged <foreign paths>`, re-commit.
Never `--amend` a hash another lane may have already read.

**Trigger:** Any commit whose stat lists a file you did not edit this session, or a count
of files larger than the number you named.

**Hardening (same night, second occurrence):** named  is NOT enough — the
index may already hold another lane

**Hardening (same night, second occurrence):** named `git add` is NOT enough — the
index may already hold another lane's staged entries, and bare `git commit` commits the
whole index. The bulletproof form is a PATH-LIMITED commit: `git commit -m "..." -- <my
files>`, which commits exactly those paths from the working tree and ignores everything
else in the index. Follow with `git show --stat HEAD` every time.

## View.animate() is ONE shared animator per view � name EVERY axis you don't own

**Pattern:** Multiple features translate the same container on different axes
(
eflowPreviewUnderDrawer uses translationY; H1 transcript reflow uses
translationX). View.animate() returns the same ViewPropertyAnimator instance
every call. Starting an animation that names only YOUR axis CANCELS the other
axis's in-flight tween at its mid-flight value � the other feature's transform
freezes halfway and nothing ever re-derives it.

**Rule:** Every writer of a shared view's transforms must carry the current
TARGETS of all axes it does not own (keep them in fields: drawerReflowShiftY /
transcriptReflowShiftX pattern). Bare setTranslationX/Y are safe mid-flight only
in the sense that a later animate() restart picks up current values � but only
if that later writer also names both axes.

**Trigger:** Two features animating different properties of one view; symptom is
a transform stuck at a partial value after opening two drawers quickly.

## A dead session's last edit can leave DUPLICATE declarations - grep the diff before assuming it compiled

**Pattern (2026-08-31, SPEC_20260831_CAPTION_SLIDES_UX):** the previous session died mid-edit and
left `android.widget.LinearLayout.LayoutParams flp` declared TWICE in one scope in
buildCaptionFitTab (FaditorEditorActivity). The tree could not compile; nothing downstream could
have verified. Its todo.md checkboxes also claimed work that was only half-landed.

**Rule:** When resuming after a crashed/killed session: (1) run the mojibake gate first
(`rg -c "â|Ã|Â"` on every dirty file - that session double-encoded two whole files), (2) scan the
diff for duplicate declarations / orphaned half-edits BEFORE building on top, (3) never trust the
previous session's checkboxes - grep the symbols.

## Tool encoding: only dedicated file Edit tools may touch source; shell writers double-encode

**Pattern:** PowerShell-based writes (Set-Content/Out-File/[IO.File]::WriteAllText with default
encoding) turned every em-dash into `â€"`, box-chars into `â”€` across two 1MB+ files (6,549
corrupted chars). Strict-UTF-8 read + CP1252 re-encode + strict-UTF-8 validate reverses it;
single intentional chars (like the literal â in encoding-check docs) must be left alone, so use a
run-based repair (convert only char runs whose CP1252 bytes form valid UTF-8), never a whole-file
recode on a mixed file.

**Rule:** Source edits go through Read/Edit tools only. After any bulk or scripted edit:
`rg -c "â|Ã|Â"` must be 0 (expected exceptions documented), and `git add` immediately (hazard rule).

## Handles bugs: identify the widget that ACTUALLY draws for the object type first

**Pattern (SPEC B pivot, 2026-09-05):** the pivot fold was implemented in
PreviewHandlesOverlay/textHandlesTarget — but a SELECTED IMAGE is surrendered to
TransformOverlayView + CornerPinTransformHost ("now that [transform] is simply what a selected
image looks like"), so the fix landed on a surface that never draws for images, and three
device rounds were spent on symptoms (8%/30% offsets, under-shoot, flip displacement) before
the routing was checked. The presentation fold must also round-trip: read-side fold in
readQuad comes free via frame(), but writeQuad/writeSimilarity must UN-FOLD (pin offsets and
the pose centre live in the stored pose frame) or every gesture writes polluted offsets.

**Rule:** Before fixing a handles/overlay bug, grep the routing (setTarget(null) +
ensureTransformOverlay) to learn which surface owns the object type. Any presentation-only
transform applied on read must have its exact inverse applied on write. Verify on device by
screenshot (adb shell screencap + pull; NEVER PowerShell > redirection of adb binary
output — it corrupts the PNG).

## 2026-09-06 � SPEC J: the dead panel vs the live surface (and "done" isn't done until the acceptance criteria are re-read)

A rotation-slider fix was applied to MaskKeyPanel, whose opener (showMaskDialog) turned out to have
NO caller � superseded by the object drawer's mask tab. The REACHABLE copy of the same defect
(PipDrawerTabs mask tab, a 0..360 rotation slider writing cur.rotationDeg) survived both audit
passes and was only caught in the final sweep. The spec's rule was explicit ("a rotation SLIDER
must not survive anywhere") � a dead panel satisfied the letter of a per-file grep, not the rule.

**Rules:**
- Before reporting a UI defect fixed, locate the LIVE route to the screen (grep the constructor's
  callers to the activity; zero callers = dead code � find the successor surface and check IT).
- Before declaring a spec done, re-read its acceptance-criteria section line by line and produce
  the exact artifact shapes it demands (per-host/per-method tables, per-site audits with counts).
- Sweep for a class of defect, not an instance: grep for the CONTROL (SeekBar near "rotat"), not
  for the file you already know.

## 2026-09-07 — SPEC K flip follow-ups: mirror is a cross-cutting axis, not a flag

**Pattern:** three stacked defects, each invisible on flat/unrotated pictures (the only states
device-tested), each proven by a JVM harness that failed 1:1 against the old code:
(1) `CornerPinTransformHost.flip` toggled the flag AND negated the pins — but the composition
order is pin-inside-mirror, so an exact central mirror is flag-only; the negate shifted the
picture by twice its own distortion (JoyRaptor: corner flip "flipped on a side axis").
(2) `CornerPinImageView` assembled `preConcat(mirror)` + `postConcat(pin)` = pin.base.mirror
with the mirror pivot in bitmap space — half a frame of sideways shove while the handles sat
correct; the export draws mirror.pin.base. Pin-alone pictures always rendered fine through
postConcat, which is what pins the pre/post Skia convention down (pre = source side).
(3) the GL pin inverse read mirrored coordinates through the unmirrored homography (same swap).
Deeper: mirror does not commute with rotation (`M.R = R(-).M`) or with the pivot fold, so an
exact flip is flag + negated rotation + mirror-aware folds — proven 0.0px over 400 fuzz cases.
The first fix attempt (flag-only, old angle) failed its own harness at 250-850px and forced the
full architecture; the 2x2 solve first written for it went singular at mirror+60 degrees and
collapsed to a closed form with no inverse.

**Rules:**
- A mirror flag is load-bearing in EVERY consumer of the offset it qualifies: pivot folds and
  anchors (all renderers, bake comp + verify, solve, picker comp, travel clamp), both preview
  matrix assemblies, the GL homography. Grep `mirrorSign|isFlipH` after any mirror change and
  account for each site; unmirrored must reduce bit-identically (signs are +1).
- Matrix assembly order is provable off-device: transcribe the pre/post op sequence into a
  3x3 pure-Java check against the export's order. Never trust pre/post naming from memory —
  derive it from a path that visibly works (pin-alone via postConcat).
- When a harness fails after a "complete" fix, do the term-by-term algebra before scoping down:
  the residual's shape (here: rotation-direction mismatch) names the missing piece.

## 2026-09-07 — SPEC K: the bake keeps moving the pose, capped, and why

**Pattern:** an attempt to make the commit bake never translate the pose (folding the
fit's translation back into the residual) failed the round-trip harness by exactly
the rotation angle: without fold terms the shift materializes through rotation
alone, so the absorbed constant must be un-rotated first — and with centre-neutral
folds there IS no fold term to complete it. Translation content genuinely belongs
in the pose for folds/scales (a fold IS mostly translation); the fix for teleports
is a CAP, not a relocation: no single commit recentres by more than 3 picture
sizes (genuine fold swings, sculpt settling and anchor compensation fit; stale-frame
garbage does not), else walk away keeping the gesture. Verify arbitrates regardless.

**Rule:** when two representations both preserve (move-pose vs absorb-to-pins), pick
the one whose error term has somewhere to complete — then prove it in-harness
BEFORE committing to it. An absorption proof that silently assumes fold terms is
worthless under centre-neutral semantics; the harness caught it in one run.

## 2026-09-07 — SPEC K teleport on finger-up: rebase gesture chrome when the rect moves

**Pattern:** resize-then-release teleported the object (commit baked a hundreds-of-px gap that
verify passed as self-consistent). Root cause class: gesture state is frozen in pixels but the
canvas rect can move underneath it mid-drag (drawer resize, controls fade); the frozen snapshot
and the live finger then speak different frames. Skipping the resync mid-drag (to protect the
finger) is correct for MODEL writes but wrong for the FRAME — the fix is a pure-chrome rebase
(rect-to-rect diagonal affine over every stored pixel: quads, finger origin, scaled grab
offset, pinch start fingers/pivot, re-derived grab angle; ring closed), no model write, no
undo. Exact for uniform rect changes at any rotation; bounded for non-uniform (rotation does
not commute with non-uniform scale — stated in the test, not hidden). Proved by
SpecKRebaseTest: same norm finger target stores the same pins with/without a mid-drag move.

**Rules:**
- Any View state frozen in pixels needs a named frame (the canvas rect at grab) and a rebase
  path when the frame moves; "don't sync mid-drag" without one is a teleport on release.
- Commit-time verify can only catch self-INconsistent writes; translation-in-pins from a stale
  frame is self-consistent, so guard the bake's inputs too (walk away when the pose moved
  under a pins-only gesture — distort gestures never write the pose).

## 2026-09-07 — SPEC K folds: range is real only for genuine distortion

**Pattern:** repeated folds "stopped working" from the second fold at rotation: a fold over an
edge of a ROTATED picture measures up to ~2.8 against the unrotated pose box, so the ±2 pin
gate refused what the commit bake would have cleared to mirror flags for free — a deadlock
(write refuses what bake clears). Fix: the range gate opens for exactly what normalizePin
bakes to a flat residual (pure arithmetic mid-drag, deterministic with the commit), genuine
distortion keeps the budget. Discrete fold refusals now say "Pin limit" on the gesture chip
instead of dying silent (mid-drag refusals stay silent — the drag simply stops).


## 2026-09-22 � never install the dirty tree to JoyRaptor's phone without checking it
Pattern: the watcher builds the whole working tree, which always holds other lanes' half-finished work. Installing that APK to the owner's Note 20 shipped someone else's in-flight UI (missing undo button, broken ripple) to his personal phone, and he froze real editing work waiting for safety that was already gone. Rule: before ANY install to the Note 20, run git status + read LANES.md ACTIVE lanes; if files outside your lane are dirty, say so and ask before installing. Staged-but-unverified beats verified-but-lost applies to code � it does not license delivering other lanes' drafts to the owner.

## 2026-09-22 � PC VPN kills wireless ADB (check it first)
Pattern: 10013 on connect + ping failing + mDNS advertising = packets never leave the PC. It was the VPN on the COMPUTER tunneling everything away from the LAN, not the phone, not adb, not the firewall. Rule: when wireless ADB dies with 10013/ping-fail, ask about the PC's VPN before touching anything else (phone VPN second, WiFi toggle third).

## 2026-09-22 — a focusable popover does not dismiss the keyboard, so keyboard-gated preview stays suppressed through the whole pick
Pattern: typing-suppression for text animation is gated on a keyboard-up belief flag (`imeActive`, set in `showIme`). Opening the text drawer raises the keyboard, and the animation picker is a focusable `PopupWindow` — it takes focus but never hides the IME. So the suppression spans the entire preset/granularity pick: the "suppress while typing, not while the drawer is open" fix changed nothing observable, and the stale-preview report came back a month later as a regression-that-was-never-fixed (the original commit even said NOT device-verified).
Problem: any preview gate keyed on keyboard visibility is also keyed on every surface that leaves the keyboard up incidentally. A picker opened FOR previewing keeps the very state that hides the preview.
Rule: when a setting's whole purpose is immediate visual feedback, the apply path must actively establish preview conditions (dismiss keyboard, seek into the animated zone) rather than relying on ambient state. Second hiding place: outside entrance/exit zones every preset draws the identical settled box, so un-suppressing alone still shows nothing with the playhead parked mid-hold — seek mid-entrance on explicit picks, never on undo/redo.
Trigger: "changing X does nothing until I leave and come back" where leaving ends an editing/keyboard session.

## 2026-09-22 � ffmpeg input -ss + -c copy rebases timestamps to zero
Pattern: a pre-trim baked with -ss BEFORE -i and stream copy comes out zero-based (first packet PTS 0.000000, measured), while our composition clips in absolute source time � every padded window then seeks wrong, mostly past EOF into empty items (48-min export: video ending at 1:42 with full-length audio, 137MB file). Validation passed because durations match; only the base shifts. Rule: any ffmpeg window cut feeding timestamp-based clipping MUST pass -copyts, and validation must compare coverage in the file's own base (out - padStart), never absolute out.

## Media3 1.8.0 Composition/EditedMediaItemSequence expose public fields, not getters

**Pattern:** In this project's Media3 fork (1.8.0, media3-patched), Composition.sequences
and EditedMediaItemSequence.editedMediaItems are public final fields. There are no
getSequences() / getEditedMediaItems() methods.

**Problem:** Writing c.getSequences() or seq.getEditedMediaItems() compiles in some
Media3 versions but fails here with cannot find symbol. The build log is UTF-16 and
accumulates stale errors from the watcher, so the real error is easy to miss.

**Rule:** Always read the Media3 source at C:/+Projects/Screenrecorder/media3-patched/
(see local.properties media3.patched.path) before calling Composition/Sequence APIs.
Use c.sequences and seq.editedMediaItems. Also EditedMediaItem.durationUs is a public
field. When uild.log is locked or stale, copy it first (cmd /c "copy build.log %TEMP%\blog.txt /Y")
and read the tail with a seek, not Get-Content on 200+ MB.

## phone.ps1 picks the FIRST attached device; pin  for the Note 20

**Pattern:** 	ools/phone.ps1 Get-Serial selects the first db devices line. With both
the sandbox Note 9 and JoyRaptor's Note 20 (Wi-Fi ADB) attached,
installs go to the wrong phone silently.

**Problem:** An install that succeeds but lands on the sandbox looks green while the owner's
phone keeps the old build. Discovered 2026-09-22 when the smoke screenshot showed the
sandbox UI.

**Rule:** Before any phone.ps1 device command when more than one device is attached, set
$env:PHONE = "<note20-serial>" (see tools/devices.local.sh). Verify with
phone.ps1 devices and a screenshot of the target, not just lastUpdateTime.

## 2026-09-22 - STUDIO_POLISH (Claude/Opus 5.5)

**A hunk-range commit is only as safe as the moment you listed the hunks.** I committed
FaditorEditorActivity with the range `1-99999` because an hour earlier every unstaged hunk in
it was mine. During a usage-limit pause the export lane added five unstaged hunks to the same
file; the commit only survived because an unrelated apply failure stopped it.
**Rule:** immediately before a partial commit, re-list `git diff -U0 -- <file> | grep ^@@`,
identify every hunk's owner, and give ranges covering ONLY yours. Never a whole-file range.

**A device that keeps "reconnecting" is probably someone's.** The Note 20 kept reappearing on
Wi-Fi ADB and I kept disconnecting it so build-install.sh would run - it was another lane's
live connection. **Rule:** never `adb disconnect` a device you did not connect. To install,
use `adb -s <serial> install -r <watcher apk>`; it can only reach that one device.

**`grep -h` hides which file matched.** I grepped for three string names, saw matches, and
read them as "no clash" because the filenames were suppressed. They already existed in
strings.xml; redefining them would have broken the resource merge. **Rule:** when checking for
a clash, never pass -h - print the file.

**Never append to a shared text file by decode -> re-encode.** LANES.md had picked up a
cp1252 byte; decoding it as UTF-8 threw. Re-encoding would have "fixed" another lane's bytes
- the strings.xml-to-UTF-16 incident's mechanism. **Rule:** append raw bytes, and assert the
file still starts with its old contents.

**Never use Undo as test cleanup unless you can PROVE the top step is yours.** The undo count is capped at 50, so it stays at 50 whether or not your action recorded. I dragged a clip (a plain drag pans - the contract says hold-then-drag moves), the count stayed 50, I pressed Undo "to clean up" and reverted a real step instead. Redo recovered it. **Rule:** prove your action by an independent measure (the object's position, a value) before undoing, and verify the same measure after.

## 2026-09-22 - A replaced surface must carry its undo brackets
- The drawers replaced ObjectMenuSheet but dropped its GestureHooks (onSliderStart/onSliderCommit), so every drawer slider/dial bypassed Undo for weeks. When porting controls to a new container, grep the old one for undo/commit hooks and port them FIRST.
- After adb gesture tests on a real project, diff the project's undo history snapshots for values that changed with no entry of their own (a swipe crossed a dial and spun an image -1567 deg). That is how a silent write shows up.

## 2026-09-23 - 48-min export (Claude/Opus 5.5): three models chased a misread gauge
- **Media3 progress is NOT time.** `SequenceAssetLoader.getProgress` is `itemIndex/itemCount + frac/itemCount`, and `TransformerInternal` AVERAGES it across every sequence (video + each audio lane). Every "23x -> 0.13x decay" and "stalls at item 19 / 70%" conclusion came from mapping that percent onto durations. Decoded with the real formula the export was ~1x from the start, stepped to ~0.25x, and stopped at comp ~30:35. **Rule:** never derive a rate or an item from Transformer percent; log real composition time (pts) or invert the formula first.
- **Measure before porting.** Four confident theories died against 2-minute measurements: overlay density (item 8 has more image content than the slow items), caption shadow blur (0.4 ms/frame on the Note 20 via an app_process benchmark), fMP4 deep seeks (the remux is a normal MP4 with a keyframe every ~1 s), VFR frame rate (steady 30-45 fps). **Rule:** a static theory about cost gets a device number before code. `CLASSPATH=x.dex app_process /system/bin Cls` runs framework Canvas code on the phone in minutes (call `Typeface.loadPreinstalledSystemFontMap()` first).
- **The real speed cause was the screen turning off.** ExportService held no wake lock; a foreground service keeps the process, not the CPU. Measured: busy process with screen off = asleep 30-45% of wall time at ~half speed; screen on = 100%. Clue that was in the old trace all along: the 60-second MEM ticks (uptime-based Handler timer) drifted 3-4 s per minute. **Rule:** any long background job holds a PARTIAL_WAKE_LOCK, and uptime-vs-wall drift in a trace means the phone was sleeping.
- **Resume keyed on lastModified is no resume.** The editor re-saves on pause, so opening the project discarded 5.5 h of finished chunks. Key on content.

## 2026-09-23 — a key the writer emits is not "read" just because some reader reads that name

**Pattern:** AudioClip ids were written to project.json but never read back, so every load
re-minted them — and persist_lint stayed green the whole time. The lint matches each model
field's JSON key against the WHOLE ProjectStorage file, and `"id"` is read by clips,
visualizers, link groups and a dozen others, so AudioClip's missing read was invisible. Found
only by diffing two saves of the same untouched project on the device.

**Rules:**
- For common keys (`id`, `startMs`, `label`, `enabled`...), a file-wide lint proves nothing.
  The proof for an object family is a real round trip: save → load → save must write the same
  file. `tools/jvm-harness/run-audioid.sh` is the pattern (real ProjectStorage over a stub
  Context rooted in a temp dir; `AUDIOID_BASE=<rev>` runs the positive control).
- Any model with a `final id = UUID.randomUUID()` needs a restore-with-id constructor, and the
  loader must call it. Check this whenever a model's id gets referenced from somewhere else.

## 2026-09-23 - masks: the "polarity" fix was half the bug; the GL mask was also upside down

**Pattern:** JoyRaptor's report ("export inverted, other layers vanish") was read as ONE
polarity bug. The previous lane fixed the ternary and made the preview agree with the export
on WHICH SIDE shows, but every GL consumer also evaluated the mask at a Y-UP frame uv against
Y-DOWN authored shapes, so the mask sat mirrored top-to-bottom. For a centred box the mirror is
invisible and the polarity looks like the whole story; for his box at Y 6% it is not. Fixing
only polarity made the preview disagree with the export in a NEW way. The feather was also a
2.3x-narrower linear ramp sized off the shape, not the frame.

**Rules:**
- Any shader that reads authored normalized coords must state the space of its uv. Check
  how the SAME caller converts the object's own centre (here `1f - cy` in fxPipFor) and apply
  the same conversion to every other authored coordinate in that shader.
- A preview/export mask check needs an OFF-CENTRE, edge-touching shape. A centred one cannot
  see a mirror.
- Before "the preview is right, fix the export", check the preview against the UI that
  authors the data (sliders, outlines, labels). Here the export matched the UI; the preview
  did not.

## 2026-09-24 — a chunked export's clock restarts per part; "verified" on part 1 proved nothing about parts 3-7

The text-layer fix was checked on frames at 0:16, 0:53 and 4:30 — all inside part 1 of the
48-minute export, where the part's composition clock and project time coincide. From part 3
on, CompositeExportOverlay filtered text boxes and audio captions by comparing absolute item
times with the part-local composition cursor, so they never reached the draw loop. The same
caption y-flip mistake the mask lesson above names was also sitting in the preview's caption
path.

**Rules:**
- A chunked-export fix is verified with a frame from EVERY part (or at least the first, a middle
  and the last), never only the first: part 1 is the one place both clocks agree.
- Any comparison between an item's startMs/endMs and a composition cursor is a bug in chunked
  mode. Items live on EDITOR time = pts + editorTimeOffsetMs (which carries chunkBaseMs).
- Read GL_SAMPLE before optimising: 100% busy GL thread with upload/canvasBlit on top meant
  CPU drawing, not GPU fill, was the limit — and EXPORT_ITEM's effect list showed 37 passes
  per clip, which no amount of shader work would have fixed.
