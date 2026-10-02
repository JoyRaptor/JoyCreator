# Paper UI integration — 2026-10-02

JB-9.07 exposes the catalogue through the final layer-column paper swatch and sheet. Shared picker edits never alter brush colour. Paper visits share chronological stroke undo, preserve exact defaults/export flags, and persist at DOC_VERSION7. None draws a screen checker, retains the physical surface/Bite, and always excludes paper from exports.

Validation: earlier full core1485/0 and androidkit229/0; final focused PaperResources5/0 plus GlPaperHistory2/0 and PaperChrome5/0. UI module compiled. Resource tests prove sliders reuse decoded images and failed resources are not retried every slider event. Preview work is bounded and stale results are rejected.

Not yet accepted: APK/device screenshots; board GPU routing and thumbnail adapters; textured paper pre-layer export composition9.06b; CPU/GPU minification parity9.06c. The last two are assigned to Paper specialist. Existing owner scratches are disposable; no migration or preservation work was introduced.

Handoff: source C:/Temp/jb-region-routing, codex/region-routing. Tests use the shared jb-gradle.lock with the primary watcher paused, then restore it once. Build APK through the watcher, never concurrently. No phone install was performed. JB-9.07 requires owner Home/approval before installation.
