# Paper backdrop invalidation — narrow contrast repair

Basef6287b921, isolated ui/dark-canvas-contrast-20261026. Coordinator authorized
only runtime paper setter notification and Activity contrast invalidation seams,
plus tests. No palette, shader, stroke recipe, history or polling changes.

A10run3 image16 shows black enabled top glyphs on near-black canvas. TopButton's
existing LIGHT/DARK token mapping is correct and readIcons already samples actual
backdrop. Runtime paper changes requested rendering but never requested icon sampling.
Document-paper callback refreshPaper also did not schedule sampling.

JbCanvasView now emits onPaperBackdropChanged only for a changed resolved backdrop,
after queued paper synchronization and requestRender. It is distinct from document
paper metadata/history notification, so applyDocumentPaper's exact callback is not
duplicated and the normalized resolved value cannot replace exact document metadata.
UI setters notify directly; worker setters post to UI and look up the current callback
at delivery, allowing destroyed screens to clear it. Bench paper restoration takes
the same setter path. Existing document-load callbacks also request icon sampling.

Activity binds this display notification to existing debounced checkIcons. The
request coalesces duplicates, including a setter plus document metadata notification.
readIcons still uses existing nine-point sampleScreen, which answers after the next
rendered frame; no immediate base-colour recolouring substitutes for actual artwork
or pinned-reference sampling. A generation guard rejects an older outstanding sample
after a newer invalidation, and destroyed screens do not sample/apply late results.
onDestroy clears the display callback and pending contrast check.

Four source-only regressions cover runtime changed/same/cleanup notification,
exact document metadata callback, worker-to-main delivery/cleared late observer,
and native TopButton white/dark glyph pixels with cyan ring/disabled-state retention.
Native pixel test intentionally connects the setter to the existing IconContrast
classifier with a flat backdrop; it does not claim complete Activity/GLES sampling.
Current tests are UNEXECUTED. Diff reviewed only; no extra build/phone slot granted.

Root must selectively apply the small setter/Activity hunks, preserving newer View
diagnostics around2930+, then execute regressions and real white/dark paper change
and restore checks. Existing source-only code must not replace a whole hot file.

## October27 final source review / Claude integration receipt

Reviewed frozen contrast12b6efcef against root candidatec971c8c0c. The intended
setter171 and Activity hunks do not overlap native diagnostic additions near2930+.
The isolated branch lacks those newer diagnostics: DO NOT restore its entire View.
Read-only `git apply --check` of the intended two-file patch also passed against
current coordinator3d8f09a1b on October27; no canonical files were changed.

Integration: use `git diff a5144c492 12b6efcef --` restricted to JbCanvasView.kt and
JoyBrushActivity.kt, then selectively apply/review only those hunks. Add new
chrome/PaperBackdropContrastTest.kt and this report. Preserve current roadmap;
reconcile its UX-COMPLETE row, not a stale whole-file replacement. The four tests
remain UNRUN; Coordinator must admit a specific focused gate before execution.
No API/semantic issue found in bounded source review: display callback is separate
from onPaperChanged, exact document callback remains once, unchanged resolved paper
does not notify, worker delivery uses current UI observer, duplicate sampling requests
coalesce, stale callbacks are guarded, and history/revisions are unchanged.

Actual executed UX proof remains the separate menu candidatee955cd617:199 cases,
29 suites,0 failures/errors/skips, receipt C:/Temp/jb-lead-ops/ux-menu-e955cd617-count.json.
Those results DO NOT cover this contrast code. A10run3 has separate five-image visual
acceptance in A10_VISUAL_ACCEPTANCE_20261026.md; no owner LIVE or physical feel claim.

Blank Proto Pencil preview cause remains explicitly UNKNOWN. Source trace confirms
openSettings creates a real preview JbCanvasView, onReady queues replaceWithStrokes,
drawStrokes dispatches the media preset through startMediaStroke. That path can
refuse GPU support, window memory or initialization; openSettings does not bind the
preview's onRefused callback, so a refused preview can appear blank without an inline
explanation. The saved A10 evidence includes no preview readiness/refusal telemetry
to distinguish these causes from rendering failure. It is not documented as an
unsupported-preview placeholder, nor is it evidence freehand pencil drawing failed.
Next diagnostic should capture preview readiness/refusal and completion on the actual
fixture before touching brush recipes; this handback makes no follow-on code change.

No active UX build, queue, device action or phone claim. Preserve both red menu logs
and the verified XML set. Remaining contrast gate: selective integration, four focused
tests, then exact-APK dark/white change and paper-restoration glyph checks.
