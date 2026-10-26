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
