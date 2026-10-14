# SPEC 2026-10-13 — Best-in-class file management, for the whole studio

**Asked by the owner, 2026-10-13:** "we need to make sure file management is best in class. what do
we need to do to get there?" Also: share-in lands "as soon as we have the perfect place to put
things", so this plan is what share-in waits on.

**Scope: all of Joy Creator** — Studio (A/V editor), JoyBrush, SpriteLab, VisLab, recordings, and
whatever comes next. One plan, one home. This supersedes ROADMAP Lane 2's Studio-only framing.

**Evidence:** three read-only sweeps on 2026-10-13 (Studio code, JoyBrush code, every doc), with the
key claims re-checked against the code by the lead session. File:line citations are in this
session's agent reports; the important ones are repeated below.

---

## 1. Where we are (verified)

| Area | Today | Grade |
|---|---|---|
| Studio save | temp file → `.bak` → rename. Single `.bak` generation. **fsync added 2026-10-13** (was missing: a power cut could empty the file) | 🟡 |
| Studio projects location | `getFilesDir()/faditor/projects` (ProjectStorage.java:55). **Deleted on uninstall.** This is what destroyed 23 of 24 projects on 2026-08-29 | 🔴 |
| JoyBrush save | Atomic zip with fsync and `.bak` (JbArchive.kt:153-188). Good | 🟢 |
| JoyBrush autosave | 30 s *debounce* (JoyBrushActivity.kt:118, 1675). Drawing continuously means **no autosave until you stop for 30 s**. An install or force-stop skips onPause, so the whole session can be lost | 🔴 |
| JoyBrush location | One file, `getExternalFilesDir("joybrush")/current.joybrush`. Deleted on uninstall | 🔴 |
| JoyBrush documents | **Only one drawing exists.** "New drawing" in the lobby reopens the same file. No gallery, no thumbnails (thumbnailPng always null, JbCanvasView.kt:1833) | 🔴 |
| Recordings & exports | `Android/data/.../FadCam/...` by default — deleted on uninstall. A SAF folder is optional in settings | 🟡 |
| Android backup | `allowBackup="false"`, empty backup rules. Nothing survives a reinstall | 🔴 |
| Project list | Delete with confirm; **no trash, no undo**. No rename/duplicate from the list, no search, folders or sizes. Thumbnail re-decoded every time | 🟡 |
| Project delete | **Leaves media behind.** Deletes top-level files only, so `media/`, `assets/`, `backups/` etc. stay on disk forever (ProjectStorage.java:682-688) | 🔴 |
| Cache cleanup on delete | **Never matches.** `substring(0, max(lastIndexOf('.'), length()))` is always the full name (ProjectStorage.java:645, 664). Same bug zeroes the list's size figure | 🔴 |
| Import | Small files copied into the project; big videos referenced by URI. Three different copy rules (25 MB per-project, 10 MB to shared folders, audio to a third folder) | 🟡 |
| Missing media | Detected on open, relink catalogue, then offers Consolidate. Good | 🟢 |
| Bundles (.zip) | Built. Import doesn't open the result; zip carries undo history and caches; never device-run (VERIFY_BUNDLING_20260829) | 🟡 |
| Cross-wing | JoyBrush → Studio is a one-way copy into a *new* project. No shared library | 🟡 |
| Share-in / share-out | No share-in anywhere. Share-out only from Records | 🔴 |
| Free space | Never checked before recording, export, import or consolidate | 🔴 |
| Caches | Remux/pretrim pruned only after a *successful export*; waveform caches never pruned; SAF-mode export staging lives in cacheDir (OS may delete it mid-export) | 🟡 |

**Homes for files today: at least seven** — filesDir projects, filesDir shared images/videos/audio,
Android/data recordings, Android/data JoyBrush, Pictures/FadCam/assets (B-roll),
Documents/FadRec/Projects, Documents/JoyBrush exports. The owner's ruling on the shared asset
library was "do not design a separate home" (INBOX.md:187-189). Seven homes is the core problem.

---

## 2. What "best in class" means here

Measured against Procreate, LumaFusion, Concepts, Clip Studio:

1. **You never lose work.** Not to a crash, a force-stop, an update, an uninstall, a full disk or a mis-tap.
2. **Your files are yours.** They live in a folder you can see, back up, sync and open with other apps.
3. **One place, every wing.** A drawing, a sprite sheet, a recording and a video project sit side by side and can be used in each other.
4. **Projects travel.** One file moves a whole project to another phone and opens there.
5. **Nothing to manage unless you want to.** Imports, shares and recordings land in the right place by themselves.
6. **Space is visible and recoverable.** You can see what is big and free it safely.

**The owner's rulings (2026-10-13), which everything below obeys:**
- Uninstalling Joy Creator must never lose projects.
- The folder sits as close to the top of storage as possible, and the user sets where at the start.
  No `Documents/Joy Creator`-style nesting.
- The fewest folders that still keep order. He browses by seeing everything and sorting by type or name.
- The Obsidian model: it is just your folder. Brushes, sprite JSON and styles are plain files you
  can open and edit by hand.
- Readable names. No SKU or UUID-style numbers.
- Things shared in land somewhere shared; with the Studio open they also go onto the timeline.

---

## 3. The Joy Creator folder

### 3.1 The tree

```
Joy Creator/            ← you choose where at first run; we suggest the top of your storage
│
├─ Lecture 3.joyproject          ← your WORK sits right here, one tap from the top:
├─ Dino walk.joybrush               projects, drawings, sprite sheets, avatars,
├─ Star guy.png                     from every wing, side by side
├─ Star guy.sprite.json
│
├─ Media/      what goes IN:  recordings, imports, share-ins, voiceovers, B-roll
├─ Exports/    what comes OUT: videos, GIFs, PNGs, exported sheets
├─ Library/    your TOOLS and LOOKS: brushes, papers, palettes, LUTs, fonts,
│                                  caption and visualizer styles, mods
│
└─ .joycreator/   hidden: Trash, earlier versions, undo history, app settings
```

You only ever need to know four things: **your work is at the top, Media is what goes in,
Exports is what comes out, and Library holds your tools.** The app never makes another folder.
You can make as many as you like.

### 3.2 The rules

- **R1. The whole folder is scanned, as Obsidian does.** Make `Client A/` at the top, or
  `Media/Dinosaurs/`, or drop a downloaded brush pack into `Library/`, and the app finds
  everything by what it is, not by where it sits. Your own subfolders are your buckets. The
  B-roll bucket (today in `Pictures/FadCam/assets`, with its `.broll_tags.json`) becomes `Media/`
  and keeps its tags.
- **R2. One kind of thing, one readable extension**, so sorting by type in any file manager works:

  | Kind | File |
  |---|---|
  | Studio project | `Lecture 3.joyproject` (JSON, opens in a text editor) |
  | Drawing | `Dino walk.joybrush` (zip, OpenRaster-style) |
  | Sprite sheet | `Star guy.png` + `Star guy.sprite.json` (the existing convention) |
  | Avatar / rig | `Rex.avatar.json` + its sheet |
  | Brush | `Library/Soft pencil.brush.json` |
  | Paper | `Library/Cold press.paper.json` + its texture |
  | Palette | `Library/Autumn.palette.json` |
  | Colour look | `Library/Warm film.grade.json`, `Library/Teal orange.cube` |
  | Caption style | `Library/Bold yellow.caption.json` |
  | Visualizer style | `Library/Neon bars.waveform.json` (the existing convention) |
  | Font | `Library/Inter.ttf` |
  | Mod / pack | `Library/<Mod name>/` (a folder with `mod.json`), or a `.joymod` zip until unpacked |

- **R3. Names are words.** `Screen recording 2026-10-13 14.32.mp4`, `Camera 2026-10-13 14.32.mp4`,
  `Lecture 3.mp4`, `Lecture 3 720p.mp4`. Today's `Faditor_20261013_143200.mp4` and UUID-named
  project folders go away. Code-like names that arrive from outside (`1000004521.jpg`, AI apps'
  random names) get a readable one: `From ChatGPT 2026-10-13 14.32.png` when Android tells us the
  source app, otherwise `Shared 2026-10-13 14.32.png`. Human names are kept as they are.
- **R4. Everything you made is a file you can open.** Brushes, styles, palettes, looks and
  projects are pretty-printed JSON. If you edit one by hand and break it, the app shows "can't
  read Soft pencil.brush.json, line 12" and never overwrites your file.
- **R5. Things you'd miss go in your folder; things the app can rebuild stay inside the app.**
  Thumbnails, waveforms, video helper copies (the 2 GB remux files) and export staging stay in
  app storage. They are safe to lose, and inside your folder they would bloat backups and make
  sync apps upload gigabytes of junk. Settings you built up (brush tuning, colour recents, grade
  and caption presets, today in SharedPreferences) move into the folder, so a reinstall gets them
  back too.
- **R6. A project uses media; it does not own it.** Files live in `Media/`, and a project points
  at them. One copy serves every project, everything sorts together, and deleting a project never
  deletes media another project uses. To travel, a project is packed into one bundle file (Phase 3).
- **R7. The copy rule: inside the folder is safe, outside gets copied in.** A recording already in
  `Media/` is used where it is, so a 2 GB screen recording never gets a second copy. Anything from
  outside (gallery, Downloads, share-in) is copied into `Media/` silently, matched by content so the
  same file is never stored twice. This settles the copy-versus-reference contradiction (ROADMAP 2.5
  vs SPEC_20260829_MEDIA_IMPORT §2.4).

### 3.3 Where the folder goes, and Android's "three places"

Android has three storage areas with walls between them:
1. app-private storage, which no file manager can see;
2. `Android/data`, which Android 11+ hides from file managers;
3. normal shared storage, which is what your file manager shows.

Everything a user would miss goes in area 3, in one folder. Only rebuildable caches stay in area 1.
Nothing goes in area 2. That is what makes uninstalling safe.

First run asks once: **"Where should Joy Creator keep your work?"**
- **Suggested:** a `Joy Creator` folder at the very top of the phone's storage.
- **Your choice:** anywhere, including an SD card.
- **"I already have one"** reconnects an existing folder after a reinstall or on a new phone.

Android 11+ won't let an app use the storage root itself, but a folder directly under the root
is fine. The system picker opens at the top with "Joy Creator" ready to create, so it is one tap.
The folder can be moved later from Settings, and the app moves the files.

### 3.4 Share-to: where things land

**The answer to "project folder or shared assets":** the file always lives in shared `Media/`.
"In the project" means the project uses it, not that the file is stored inside the project. One
copy serves everyone, it is findable by type and name, and it survives the project being deleted.

| Shared in while… | The file goes to | Then |
|---|---|---|
| Studio is open on a project | `Media/` | Placed on the timeline at the playhead |
| JoyBrush is open on a drawing | `Media/` | Brought into the drawing (JoyBrush lane decides layer vs reference) |
| Lobby, or the app is closed | `Media/` | "Saved to Media" card: **Add to a project · Open in JoyBrush · Done** |
| It is a brush, palette, LUT, font or style | `Library/` | In the picker immediately |
| It is a Procreate `.brush` or Photoshop `.abr` | `Library/` | Converted to a readable `.brush.json`; the original is kept beside it |
| It is a project bundle | Project to the top, its media to `Media/<Project name>/` | Opens it |
| It is a mod | `Library/<Mod name>/` | Listed with mods |

### 3.5 Edge cases

| Case | Behaviour |
|---|---|
| The same file is shared twice | Reused. No `photo (2).jpg` |
| A different file with the same name | `photo 2.jpg` |
| Several files at once | All go to `Media/`, and onto the timeline in the order shared |
| The file is already inside the Joy Creator folder | Not copied (R7) |
| A huge video and too little space | Checked before copying; you are told how much is needed |
| Deleting a project | Goes to Trash. The app also offers to trash media **only that project used**, listed by name. Shared media stays |
| You rename or move files in a file manager | The app finds them again inside the folder by name, size and fingerprint. The relink screen appears only if a file is truly gone |
| A bundle's media names clash with yours | Its media goes in `Media/<Project name>/`, so nothing is overwritten |
| A hand-edited JSON is broken | Shown as broken with the line number; never overwritten, never a crash |
| The SD card is removed or permission revoked | "Joy Creator folder unavailable — reconnect". Nothing is marked missing or lost |
| A sync app (Drive, Syncthing) brings conflict copies | Shown side by side; never merged silently |
| Two copies of Joy Creator (Play and sideload) on one phone | Both can point at the same folder, which is also how the application-ID change migrates (D4) |

### 3.6 Owner's thinking, 2026-10-13 evening (leaning, NOT locked; supersedes 3.1-3.4 where they differ)

Page: `tasks/design/Joy-Creator-folder-options.html` (artifact v2, "Folders you earn").

- **Work at the top, in general.** Leaning toward almost flat, with Consolidate turning a project into a box when wanted.
- **Let users organize; give them good tools and hints.** Starting with folders makes people think it must be that way, because they don't know what breaks. Joy Creator must be anti-fragile: moving things never breaks anything.
- **Share-in:** with a project open, it goes to wherever that project's file lives (the top, if the project is at the top). With nothing open, it goes to the shared Assets folder if one exists, otherwise the top.
- **Consolidate project:** moves everything the project uses into a folder named after the project. Nothing depends on that name afterwards. Files also used by other projects get a choice: "leave them where they are" or "move to a shared folder", reusing Assets if it already exists. A checkbox decides whether to pull recordings out of Recordings.
- **File browser modes:** "not used in any project" (and its siblings).
- **Terms he likes:** Assets, Exports or Finished, Recordings, and Gear or Supplies (not "Kit": it doesn't say what's inside).
- **Folders he's considering:** Recordings, Exports, Finished. He's unsure how much to impose.
- **Brushes:** asked whether each brush is a folder or a naming scheme. Plugins probably need a default folder.
- **Workflow breadth:** some users only record their screen, paint, animate, do audio or edit video; some make assets for videos; some export ranges to remix. A single-purpose user shouldn't get folders that don't fit them. "Making Joy Creator almost like an operating system."

**Lead's proposal answering this (on the page):** folders you earn.
- Rule 1: anything belonging to a work follows its file.
- Rule 2: anything belonging to no work goes to its kind folder if one exists, otherwise the top.
- Rule 3: files are tracked by content, so moving never breaks anything.
- No folder is made in advance. Suggestions are shown once when things pile up ("38 recordings at the top, make a Recordings folder?").
- Folder roles are stored, so a renamed Recordings folder still works.
- Supplies has one subfolder per kind, created only when needed. One brush is `Name.brush.json` plus same-name pictures beside it; a set is a folder; one folder per brush is also accepted. Plugins are a folder each.

---

## 4. Engineering decisions (the lead's calls, with end-user reasons)

- **D1. The folder is a SAF tree the user picks.** It survives uninstall, it is how a reinstall or
  a new phone finds your work, and it removes MANAGE_EXTERNAL_STORAGE (Play blocker 1.3).
- **D2. A fast working copy inside the app, mirrored to the folder on every save.** SAF writes are
  slow and can't rename atomically everywhere. Editing stays fast and crash-safe locally, and the
  folder is never more than seconds behind. Big media is never duplicated: it lives only in
  `Media/`. *A timing spike on the Note 9 and Note 20 comes first; if SAF is fast enough, the mirror
  collapses to a direct write.*
- **D3. The folder ships before the application ID changes.** A new ID is a new app that can't read
  the old one's private storage. The folder is the bridge.
- **D4. Delete goes to Trash for 30 days, in every wing.** Emptying Trash removes everything, which
  also fixes the leaked-folders bug.
- **D5. Stopgap until the folder ships:** `hasFragileUserData="true"`, so an uninstall from Settings
  asks "keep app data?".

---

## 5. The work, in order (lane in brackets)

Lanes, per the owner 2026-10-13: Codex = JoyBrush engine; the JoyBrush-features Claude account = new
JoyBrush features; the cross-cutting Claude account = miscellaneous and cross-cutting; other accounts =
Studio. Rows touching JoyBrush code are written up for the JoyBrush lanes, not taken from here.

### Phase 0 — Stop losing work
- [x] 0.1 fsync the Studio project save (ProjectStorage.writeProjectJson) — 2026-10-13, compile owed [cross-cutting]
- [ ] 0.2 JoyBrush autosave during continuous drawing: save at stroke end when ≥ 60 s since the last save; keep the 30 s idle save [JoyBrush]
- [ ] 0.3 Studio: write `undo_history.json` and `bookmarks.json` atomically [Studio]
- [ ] 0.4 Free-space check before recording, export, import, consolidate and bundle [Studio]
- [ ] 0.5 Move SAF-mode export staging and bundle temp zips out of cacheDir; clean up when a copy fails [Studio]
- [x] 0.6 `hasFragileUserData="true"` (D5) — 2026-10-13, compile owed [cross-cutting]
- [x] 0.7 (2026-10-13) Docs: remove the remaining "uninstall" instructions (DEVICE_CONTROL_RUNBOOK.md:208-212, :474; VERIFY_BUNDLING_20260829.md:74) [cross-cutting]

### Phase 1 — The Joy Creator folder
- [ ] 1.1 Spike: SAF write/rename/read timings on Note 9 + Note 20 (decides D2) [cross-cutting]
- [x] 1.2 (built 2026-10-13, 17 tests pass) One shared `JoyFolder` storage layer for every wing: resolve, atomic write, mirror, scan, trash. Lives in `studiokit` so JoyBrush can use it [cross-cutting → reviewed by the Studio and JoyBrush leads]
- [~] 1.3 (built 2026-10-13, device test owed) The setup screen with "Make my Joy Creator folder" / "I already have one", reached from a banner on the Studio project list and a Storage settings row. Not yet in first-run onboarding (Onboarding files are another lane's) [cross-cutting]
- [~] 1.4 (Studio built 2026-10-13, device test owed) Studio projects are mirrored to the folder after every save; media that only lives inside the app is copied in after the user agrees to the size shown. Originals untouched. JoyBrush: brief in `tasks/joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md` [cross-cutting + both leads]
- [ ] 1.5 Recordings and exports land in `Media/` and `Exports/` with readable names (R3) [cross-cutting]
- [ ] 1.6 The copy rule (R7) on every import path; retire `files/images|videos` and `Pictures/FadCam/assets` [Studio]
- [ ] 1.7 Presets and settings out of SharedPreferences into the folder (R5) [both]
- [x] 1.8 (built 2026-10-13, device test owed) "Folder unavailable" state on the banner, the settings row and the setup screen [cross-cutting]
- [ ] 1.9 Drop MANAGE_EXTERNAL_STORAGE and the unused legacy storage requests (closes ROADMAP 1.3) [cross-cutting]
- [ ] 1.10 Then, and only then, the application ID change (ROADMAP 1.5) [cross-cutting]

### Phase 2 — One gallery, every wing (meets the Lobby polish)
- [ ] 2.1 JoyBrush multi-document: New drawing makes a new file; a thumbnail is written on every save [JoyBrush features]
- [ ] 2.2 Lobby opens any drawing, project or sheet, not just the last one [Lobby — owner is scoping]
- [ ] 2.3 Gallery: thumbnails, size, sort by type/name/date, search, the user's own folders, multi-select [Lobby]
- [ ] 2.4 Rename, duplicate, move and Trash from the gallery; a Trash screen with restore [Lobby]
- [ ] 2.5 **Share-in** per §3.4 (ROADMAP 3.9) [cross-cutting]
- [ ] 2.6 Share-out from any gallery item [Lobby]
- [ ] 2.7 Sprite sheets leave project.json and become files at the top (STATE §9 #13) [Studio/SpriteLab]

### Phase 3 — Protect and travel
- [ ] 3.1 Version history: automatic snapshots (hourly for a day, daily for a week), "Restore an earlier version"
- [ ] 3.2 Bundles: open after import, leave out caches and undo history, validate the manifest; run the owed device checks *on a phone with nothing on it*
- [ ] 3.3 JoyBrush layered export (ORA is built, not wired); brush/paper import into `Library/` (importers built, not wired) [JoyBrush]
- [ ] 3.4 Storage screen: size per project, caches by kind, "free up space" that only ever removes rebuildable things
- [ ] 3.5 Cache housekeeping on a schedule; fix the cache-name bug (ProjectStorage.java:645, 664) without deleting caches another project shares
- [ ] 3.6 A live JoyBrush ↔ Studio link: a drawing placed in a project stays editable and updates [JoyBrush + Studio]

Cloud: none of ours. Because the folder is yours, any sync app backs it up.

---

### Build log, 2026-10-13 (cross-cutting account)

| Piece | Where | Proof |
|---|---|---|
| Core: kinds, readable names, three placement rules, roles, fingerprints, index, atomic writes + crash recovery, Trash, Consolidate planner, usage, suggestions | `studiokit/src/main/java/com/fadcam/joyfolder/` | `bash tools/joyfolder-tests/run.sh`: 17 passed, 0 failed |
| Android folder access (SAF) + chosen-folder memory + picker opening at the top of storage | `studiokit/.../joyfolder/android/` | watcher BUILD SUCCESSFUL |
| Studio mirror after every save, Trash on delete, restore after reinstall, consent for media size | `app/.../joyfolder/studio/StudioMirror.java` + two hooks in `ProjectStorage` | watcher BUILD SUCCESSFUL |
| Setup screen, Studio-list banner, Storage settings row | `app/.../joyfolder/studio/` | watcher BUILD SUCCESSFUL |
| Project delete now removes its subfolders (was leaking media forever) | `ProjectStorage.delete` | watcher BUILD SUCCESSFUL |

**Device-verified on the Note 9 (2026-10-13 20:24–20:45):**

| Check | Result |
|---|---|
| Storage settings row; setup screen; picker opens at the top of storage; "Joy Creator" made there | ✅ |
| Consent shows the real size (103 MB) and free space; "Copy them too" | ✅ (after fixing a stuck-button bug found here) |
| 9 projects mirrored as `<name>.joyproject` with readable media names; identical files stored once | ✅ 21 files / 64 MB (first run, before content dedupe, made 35 files / 103 MB) |
| References inside the folder copy are `joy:<file>`; the user's own gallery files stay as their originals | ✅ |
| Folder renamed away → amber "unavailable, tap to reconnect"; reconnect via the picker | ✅ (after adding a real existence check found here) |
| "Bring back projects": a copy with a new id restored; opened in the editor with video, filmstrip, waveform, sprites, images and font all loading from the folder | ✅ |
| An edit saves → folder copy rewritten ~4 s later, still `joy:` paths | ✅ |
| Delete → app copy fully removed (subfolders too); folder copy in `.joycreator/Trash` as "2026-10-13 20.44 Restore test.joyproject" | ✅ |
| New delete-dialog wording ("kept in Trash for 30 days") | compile-verified only |

Test leftovers on the Note 9: `/sdcard/Joy Creator (first test)` (the pre-dedupe run; safe to delete) and the
project "Restore video test" (a copy of ZA_CONTROL; safe to delete).

Not yet: recordings and exports writing straight into the folder (the Records tab reads the recorder's own folder
layout, so this needs its own careful step); the Consolidate dialog and gallery filters (UI belongs with the Lobby
polish); share-in (lands after the folder is proven on the phone).

## 6. Answered and open questions

Answered 2026-10-13: the folder sits at the top, and the user picks where; the copy rule is now
shared `Media/` (R6/R7); share-to destinations are in §3.4.

Still open:
1. Work at the top level (as drawn), or in a `Projects/` folder? The lead's call is the top level:
   one less tap, and Obsidian puts notes there too.
2. Recordings straight into `Media/` (as drawn), or their own `Recordings/` folder? The lead's call
   is `Media/`: they are raw material, and readable names keep them grouped when sorted.
3. Trash keeps things 30 days?

## 7. Contradictions found and how this spec settles them
- Copy vs reference on import → R6/R7.
- Bundling marked 🟢 in STATE.md but never device-run → treat as 🟡 until 3.2's checks pass.
- Audio clip IDs listed open (STATE §9 #1, ROADMAP 2.7) but fixed 2026-09-23 (LEDGER.md:3810) → close after a device check.
- MANAGE_EXTERNAL_STORAGE "decided" (LAUNCH_STRATEGY:70) vs "open" (ROADMAP 1.3) → closed by 1.9.
