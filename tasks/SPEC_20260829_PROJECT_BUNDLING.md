# SPEC — Project bundling: make a project a thing you can move, back up, and survive a reinstall

**Written:** 2026-08-29 · **For:** an external agent, FRESH session · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim `SPEC_20260829_PROJECT_BUNDLING`. Rule 6 (never
run gradle), the WORKING-TREE HAZARD, the **no bare `git commit`** corollary all apply.
Device work: `bash tools/phone.sh` or `.\tools\phone.ps1` (PowerShell).

---

## 1. Why

JoyRaptor, 2026-08-28: *"Eventually a consolidate-project-to-this-folder-location is going to
be necessary as we are getting very feature rich."* And 2026-08-29: *"I think we just have
a broken media catalog relinker somewhere."*

Today a project is a `project.json` at
`files/faditor/projects/{projectId}/project.json` (`ProjectStorage:47`) that **points at
media it does not own**:

| Thing | Where it lives | What kills it |
|---|---|---|
| the project file | app-private `getFilesDir()` | uninstall |
| video / image / audio | wherever the user picked it, by `content://` URI | moving the file, clearing an app, revoking a permission, a factory reset |
| **imported fonts** | app-private storage | **uninstall — they are gone, permanently** |

So a project is not a document. It is a set of pointers that decay. The relink catalog
exists to repair that decay by hand, one file at a time, and JoyRaptor reports it is broken.

**A project should be a folder you can copy to another phone and open.** That is the whole
spec.

---

## 2. What to build

### 2.1 Consolidate

A **Consolidate project** action (project menu, and offered in the relink flow) that copies
every referenced asset into the project's own folder and rewrites the references to point
inside it:

```
files/faditor/projects/{projectId}/
    project.json
    media/      videos, images, audio — copied, original names preserved where possible
    fonts/      every imported font the project actually uses
    thumbs/     (optional) generated, never authoritative
```

Rules that matter:

- **Copy, never move.** The user's original files stay exactly where they are. Moving
  someone's video out of their gallery would be indefensible.
- **De-duplicate by content hash**, not by name. A clip used in nine places is copied once.
  Names collide constantly (`FadRec_20260612_165216.mp4` from two folders); hashes do not.
- **Report the size BEFORE doing it** — "Consolidate will copy 14 files, 1.2 GB. Continue?"
  JoyRaptor's device had 19 GB free at one point overnight. Silently eating a gigabyte is not
  acceptable.
- **Skip what is already inside** the project folder, so running it twice is free.
- **Never block the UI.** Progress with a count and a cancel. A cancelled consolidate must
  leave the project working — copy to a temp name and swap on success, the same way
  `PcmSidecar` writes a `.part` file and renames.
- **One undo step**, or an explicit "Revert to external references" if undo is impractical.
  Say which you chose.

### 2.2 A portable reference form

Once consolidated, `project.json` must reference assets **relative to the project folder**,
not by absolute path or `content://` URI. Something like `project://media/clip1.mp4` — the
codebase already uses a `project://` scheme (`3e91ccc5` resolves `project://assets/...`), so
**extend that, do not invent a second scheme.**

Loading must accept BOTH forms indefinitely: a `project://` relative reference, and a legacy
absolute/`content://` one. Old projects must keep opening. **Check this before anything
else** — a change that loses existing projects is a total failure regardless of what else it
does.

### 2.3 Export / import a project

- **Export**: zip the project folder to a user-chosen location (system picker, so it can go
  to Drive or an SD card). Include a small `manifest.json` with app version, schema version,
  file count and total size.
- **Import**: pick a zip, unpack to a new project id, open it.

This is what makes it a backup and what lets JoyRaptor move work between the Note 9 and the
Note 20. **Consolidate is a prerequisite** — exporting an unconsolidated project would
produce a zip full of dead pointers, which is worse than refusing. If the user exports an
unconsolidated project, offer to consolidate first.

### 2.4 Fix the relink catalog

JoyRaptor says it is broken; nobody has diagnosed it. **Diagnose before you change anything**
and write down what was actually wrong — `RelinkCatalogBottomSheet.java` plus the
`relinkPendingIndex` / `spriteRelinkPickerLauncher` paths in `FaditorEditorActivity`.

What it should do once working:

- On open, list every missing asset with a thumbnail (or a type icon), its name, and where
  it was last seen.
- **Auto-match by content hash and by filename** against the app's scanned folders, and
  offer the matches — one tap instead of a file-picker round trip per item.
- Relinking one file should **auto-resolve every other missing file in the same original
  folder**. A user who moved a folder of 40 clips should answer once, not forty times.
- Offer **Consolidate** at the end, so the same break cannot recur.

---

## 3. Files

```
app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java
app/src/main/java/com/fadcam/ui/faditor/project/ProjectBundle.java        (NEW — consolidate/export/import)
app/src/main/java/com/fadcam/ui/faditor/project/AssetResolver.java        (NEW or extend — project:// resolution)
app/src/main/java/com/fadcam/ui/faditor/RelinkCatalogBottomSheet.java
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java        (menu entries ONLY — check LANES)
```

**Out of scope:** the media picker (`SPEC_20260829_MEDIA_IMPORT` owns it), and the
media-browser pin. Note that consolidation makes the picker's persistable-permission problem
much less severe — say so in your report but do not go and change it.

---

## 4. Acceptance

`bash tools/phone.sh devices` / `.\tools\phone.ps1 devices` pasted, plus `build` output with
its **date**.

1. **Old projects still open.** Open three pre-change projects; all render identically.
   Screenshot. **Do this first.**
2. Consolidate a project with video + image + audio + an imported font. Show the size
   estimate, then the resulting folder listing.
3. `project.json` now uses relative references. Paste the relevant lines.
4. Consolidate the same project again: near-instant, copies nothing.
5. A clip used three times is stored once. Show the file count.
6. Cancel a consolidate midway: the project still opens and still plays.
7. Export to a zip. **Uninstall the app. Reinstall. Import the zip. The project opens with
   its media AND its imported font intact.** This is the check the whole spec exists for —
   fonts currently die on uninstall.

   🛑 **STOP. UNINSTALLING DESTROYS EVERY PROJECT ON THAT DEVICE.** Projects live in
   app-private storage (`getFilesDir()`), which Android wipes on uninstall. There is no
   recovery and no prompt.

   On 2026-08-29 this check was run on JoyRaptor's own phone and took **23 of his 24 projects
   with it.** That is my fault, not the agent's — this spec said "uninstall the app" and
   did not say what that would cost. It says it now.

   **Before running this check you MUST:**
   1. `adb shell run-as com.fadcam.beta ls files/faditor/projects` and count them.
   2. If there is more than the one test project, **do not proceed.** Ask JoyRaptor. Either use
      a device with nothing on it, or back every project up first:
      `adb exec-out run-as com.fadcam.beta tar c files/faditor > projects_backup.tar`
      and confirm the tar is non-empty before you touch anything.
   3. Say in your report how many projects were on the device and what you did about them.

   A verification step that destroys the user's work has failed, however green it comes back.
8. Relink: break a project by moving its media folder, open the catalog, and relink. Report
   what was broken and what you changed. Confirm one relink resolves the whole folder.
9. Report total time and size for a ~1 GB project. If consolidate takes minutes, say so.

---

## 5. Traps

- **`strings.xml` is UTF-8 with a BOM.** It was corrupted to UTF-16 on 2026-08-29 and
  restored in `1e5df369`. Check `file app/src/main/res/values/strings.xml` before committing.
- `getSelectedClip()` returns `getClip(0)` when nothing is selected — use
  `clipUnderPlayhead()`.
- The export runs in its own process (`com.fadcam.beta:export`) and survives app restarts.
  `am force-stop` before timing anything.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
- Copying gigabytes on a phone is slow and interruptible. Assume the process dies mid-copy
  and make that safe, rather than assuming it will not.
