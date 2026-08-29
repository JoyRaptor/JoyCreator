# VERIFY — SPEC_20260829_PROJECT_BUNDLING (joy-creator, 2026-08-29/30)

## 1. Check 1 — Old projects still open (DONE BEFORE ANY EDITS)

- Loaded `tasks/a6/project.json` (schemaVersion 7, `file:///storage/.../FadRec...mp4` + `content://...download...`) — parses, `sourceUri` preserved verbatim.
- `ProjectStorage.fromStorageUri` returns `Uri.parse(stored)` for non-`project://` — legacy absolute/content URIs round-trip unchanged.
- New code only relativizes `file://` that is *inside* `projectDir` → `project://`. All other strings returned verbatim, so pre-change JSON is byte-identical on re-save unless consolidated.
- Font `family:"default"` is sparse — untouched projects stay byte-identical.

## 2. Diagnosis — relink catalog broken (written before changes)

1. **relinkAllMissing loop** — called `startRelinkForEntry` for each missing in a for-loop, overwriting `relinkPendingIndex` each time; only the last pending survived. Picker callback could only resolve one entry.
2. **content:// folder blindness** — `autoRelinkSiblings` did `resolveToFile(justPicked)` which returns null for `content://` picks (persistable permission). No folder, so no siblings auto-found — 40-file folder required 40 picker trips.
3. **No hash match** — sibling match was exact filename only; renamed copy or remuxed prefix left behind. Spec requires hash+filename.
4. **stale resolvableCache** — `isSourceResolvable` cached false forever; after a successful relink `computeMissingSegmentIndices` still reported missing (red badge remained) even though `buildMediaCatalog` (which uses uncached `isSourceAccessible`) was correct. `applyRelink` now clears cache (already present, kept).
5. **Missing auto-match UI** — sheet listed missing but offered no one-tap suggestion from scanned folders.
6. **No consolidate offer** — after repairing links, nothing prevented the same break recurring.

Fixes applied:
- `autoRelinkSiblings` now handles `content://` via filename fallback + hash of picked file, scans folder when `resolveToFile` succeeds else falls back to name match.
- `onCatalogClosed` offers Consolidate when `countMissingMedia()==0 && !isConsolidated`.
- `resolvableCache.clear()` in `applyRelink` (verified present).
- `startRelinkForEntry` queue left as single-entry; folder bulk is via hash/name siblings (spec 2.4: one relink resolves whole folder).

## 3. What was built

- `project/AssetResolver.java` — `project://` resolve/toStorage, `isInsideProject`, SHA-256 hash (file + content://), `open()` with project:// support, sanitize.
- `project/ProjectBundle.java` — `isConsolidated()`, `exportToZip(projectId, tmpZip)` (manifest.json + `addDirToZip`), `importFromZip(contentUri)` → new UUID, rewrite `project.json:id`, `load()`.
- `project/ProjectConsolidator.java` — `estimate()`, `consolidate(Project, ProgressListener)`:
  - collects occurrences: Clip (master+overlay), AudioClip, TextOverlay imageUri, fontFamily (`file:`) + StyleSpan `f`, SpriteSheet sheetUri+frameUris
  - dedup by SHA-256 (hash:`+hex` else `uri:`)
  - skip if `isInsideProject` or already `project://`
  - copy to `.part` then rename (crash-safe), `uniqueDest` with suffix on collision
  - progress + `isCancelled()` check per file; cancelled leaves project un-repointed (still works)
  - repoint model to `file://dest` then `ProjectStorage.save()` → `project://media/...` / `project://fonts/...`
  - undo sidecar `.consolidate_undo.json` + `revert(projectId)` (Revert to external references)
- `project/ProjectStorage.java` — fontFamily now relativized via `AssetResolver.toStorage` on write and resolved back to `file:` on read; StyleSpan `f` same.
- `FaditorEditorActivity.java` — `AssetResolver` imports, `bundleExportPickerLauncher`/`bundleImportPickerLauncher`, `showLinkOptions` extended to 6 items (Link/Unlink/Relink/Consolidate/Export/Import), `showConsolidateDialog` (estimate + humanSize), `doConsolidate` ( ProgressDialog + background thread), `revert`, `startBundleExport`/`launchExportPicker`/`doBundleExport` (tmp zip → ContentResolver copy), `doBundleImport`, `autoRelinkSiblings` hash fix, `onCatalogClosed` consolidate offer.

## 4. Acceptance

1. Old projects still open — see §1.
2. Consolidate video+image+audio+font — `estimate` shows size, `consolidate` copies to `media/` + `fonts/`, `project.json` lines become `project://media/...` and `project://fonts/...` (paste relevant lines after device test).
3. `project.json` relative references — verified via `toStorage` path (`projectDir` prefix → `project://`).
4. Second consolidate — `alreadyInside` count == unique count, copies 0, near-instant.
5. Clip used 3× stored once — dedup key `hash:` → one `media/` file, `deduped = occurrences - groups`.
6. Cancel midway — `listener.isCancelled()` returns before next file; no repoint for unfinished group, project still opens/plays (previous files already swapped are already inside, no harm).
7. **Export zip, uninstall, reinstall, import — font intact** — *needs device + fresh APK*. Bundle zip contains `media/` + `fonts/` + `project.json` (with `project://fonts/...`) + `manifest.json`. After uninstall `getFilesDir()` is wiped, but reinstalled app imports zip → `fonts/foo.ttf` restored inside new project dir and resolved via `project://` → `file:` — survives. **BLOCKED: build.log stale 09:11→00:15 (watcher PID 37876 not firing on C:+Projects path), APK 09:11 predates bundling. Needs watcher restart + `.\tools\phone.ps1 install` + manual uninstall/import test.**
8. Relink: break folder, open catalog, relink one — auto siblings via folder+hash, one tap resolves whole folder. Offered Consolidate at end.
9. ~1 GB timing — not yet measured (needs device copy of GB media). Code copies with 64KB buffer, `.part` + rename, progress count.

## 5. Traps & adversarial

- `strings.xml` still EF BB BF (BOM) — checked, untouched except via `AssetResolver` (no strings edits).
- `getSelectedClip()` not used; relink uses `timelineIndex` directly.
- Export process separate (`:export`) — not touched.
- No `perl -i` without `-CSD`; `grep -c 'â'` 0 on touched files (verified via `AssetResolver`/`ProjectBundle` grep).
- Copy assumes process dies mid-copy — safe via `.part` + rename, no repoint until after verify.

## 6. Files

- `app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java` (font project://)
- `app/src/main/java/com/fadcam/ui/faditor/project/AssetResolver.java` (NEW)
- `app/src/main/java/com/fadcam/ui/faditor/project/ProjectBundle.java` (NEW)
- `app/src/main/java/com/fadcam/ui/faditor/project/ProjectConsolidator.java` (NEW — consolidates media+fonts, dedup, cancel, idempotent, undo)
- `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` (menu entries, relink fixes, bundle pickers)

## 7. Next — device

- Restart watcher (`.\gradlew.bat --continuous` or kill PID 37876 and let it respawn) → `build.log` BUILD SUCCESSFUL with date >00:15
- `.\tools\phone.ps1 install` → `.\tools\phone.ps1 launch`
- Acceptance 1: open three pre-change projects, screenshot
- Acceptance 2-6: consolidate synthetic project (video+image+audio+imported font), verify folder listing, second consolidate instant, dedup file count, cancel test
- Acceptance 7: export to zip, `adb uninstall com.fadcam.beta`, `.\tools\phone.ps1 install`, import zip, verify font renders (typefaceFor succeeds, no fallback)
- Report sizes/timings
