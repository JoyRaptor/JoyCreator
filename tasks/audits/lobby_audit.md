# Lobby audit (read-only), 2026-10-15

Scope: the Joy Creator lobby, `app/src/main/java/com/fadcam/ui/lobby/LobbyFragment.java` (2321 lines),
its layout `app/src/main/res/layout/fragment_lobby.xml`, and the routing in `app/src/main/java/com/fadcam/MainActivity.java`.
No source files were edited. Everything below was read from code; file:line references are to the current tree.

## 0. How the lobby is reached

- `MainActivity` opens the lobby by default (`TAB_LOBBY = 6`, MainActivity.java:1897, :599). `createFragmentForPosition` builds it at MainActivity.java:2073-2078.
- Tab numbers the lobby routes to, and what each one actually is (MainActivity.java:2052-2084):
  - 0 = Home (Capture, or FadRec home in FadRec mode)
  - 1 = Records (Library)
  - 2 = Remote
  - 3 = FaditorMiniFragment (Studio project list)
  - 4 = SettingsHomeFragment (Setup)
  - 5 = ForensicIntelligenceFragment (the "Lab" nav item, nav_lab = "Lab")

## 1. Rooms (carousel) and what each opens

Rooms are built in `buildRooms()` (LobbyFragment.java:449-484). Index order matters: code addresses rooms by literal index (2, 3, 4, 5).

| # | Room | Opens (enterRoom, :1137-1183) | Hero content | Status |
|---|------|-------------------------------|--------------|--------|
| 0 | Studio | Last project in FaditorEditorActivity; with no project, the Studio list (tab 3) | Name of the newest project, "IN <NAME>", its frame | Wired |
| 1 | Capture | Tab 0 (Home camera) | Hard-coded "Back camera" plus "READY · ~Xh left" text, no viewfinder | Wired for the action; hero is a text readout, not a live preview (comment at :1001 says so) |
| 2 | Sprite Lab | SpriteSheetEditorActivity for the newest project; with no project, falls back to the Studio list | Newest project's name under the tag "LAST SHEET" | Wired, but the hero shows a project, not a sheet. Tag and content disagree |
| 3 | Avatar | AvatarStudioActivity for the newest project; no project, falls back to the Studio list | Newest project's name under the tag "LAST CHARACTER" | Wired, same tag/content mismatch as Sprite Lab |
| 4 | Viz Lab | Nothing. enterRoom and startNewInRoom both route to the Studio list (tab 3) | Always empty (room excluded from hero content at :1017) | PLACEHOLDER. No Viz code exists (grep for viz/vislab finds nothing outside Joy Brush and unrelated files). Empty copy promises "Make one from any sound you have" |
| 5 | Joy Brush | DrawingLibrary.lastOpened, or a new drawing (DrawingsActivity exists in the manifest; openDrawing :2021) | Last drawing's picture, name, "DRAWING · <age>" | Wired. Colours come from jb_tokens.xml via JbColors (indigo to blue). Appended last on purpose |

Chips in the NEW row (buildNewRow, :1515-1541):
- Recording -> tab 0 (Capture). Wired.
- Project -> tab 3 (Studio list). Wired.
- Character -> sets `active = 2` (Sprite Lab), then enterRoom. Wired only if a project exists; otherwise lands on Studio list.
- Import -> tab 1 (Library). Label says Import, but nothing in the lobby imports. The destination is the Library. Mislabelled, or a placeholder until an import action exists.

Floor words (buildFloor, :1725-1733), the lobby's "bottom nav":
- Finder -> tab 5 (the Lab nav item). Label and destination do not match.
- Remote -> tab 2. Wired.
- Bits -> tab 0 (Capture/Home). Points at Home, not at a Bits surface. Likely placeholder.
- Packs -> tab 4 (Setup). Same destination as Setup. Duplicate.
- Setup -> tab 4. Wired.

Other controls:
- Hero "New" button (:1120-1125): Joy Brush room opens a new drawing; Capture opens tab 0; every other room, including Sprite Lab and Avatar, goes to the Studio list. The comment above it says it routes "to the room". The code does not do that.
- Search (magnifier, :385): opens the Library. Records search is declared but never wired (tasks/APP_MANIFEST.md says so too). Placeholder-quality.
- Joybot (:376): opens ChatAssistantActivity with the newest project as context. Wired.
- Joybot one-line (`paintBotLine`, :1791-1834): see issue P0-1 below.
- Long-press on the banner or wordmark or chrome row (:319-326): opens TrashFragment as an overlay. Wired, but hidden with no visible hint.
- Export hairline (:1882-1957): listens to ExportService broadcasts. Wired.

Hero action on the empty state: "Start one", routes to the Studio list.

## 2. How the LIBRARY gets its items

Section header: "Library" (strings.xml, `lobby_library`). Count line: "N ITEMS" (format string in code, :1280). The row shows at most 10 cards (:1283). The door (whole row) opens the Library tab (tab 1).

Data is merged in `loadRecentsAsync()` (:1445-1502), newest first:
1. Projects: `ProjectStorage.listProjects()` (sorted newest first, ProjectStorage.java:~629). Read synchronously on the main thread in `onResume` via `reloadProjects()` (:1961). Kind "PROJECT".
2. Joy Brush drawings: `DrawingLibrary.list()`. Read synchronously on the main thread (:1983), then kind "DRAWING". Also `syncFromFolder` and `fillMissingThumbnails` run once per visit on a background thread (:1999-2019).
3. Video index: `VideoIndexRepository.getVideos(...)` on a background executor, mapped from `VideoItem.Category` (:1468-1488):
   - FADITOR -> "EXPORT"
   - SCREEN -> "SCREEN"
   - SHOT -> "PHOTO"
   - STREAM -> "STREAM"
   - default (CAMERA, DUAL, MINIAPPS, UNKNOWN) -> "RECORDING". MINIAPPS and UNKNOWN are mislabelled as recordings.

Item kinds that can appear on a card: PROJECT, DRAWING, EXPORT, SCREEN, PHOTO, STREAM, RECORDING.

Card behaviour (`recentCard`, :1313-1397): project opens the editor; drawing opens the drawing; anything else (recordings, exports, screen, photo, stream) goes to the Library. A small yellow dot marks projects with files outside the Joy Creator folder (StudioMirror). The corner cut takes the room colour. In the Joy Brush room the row shows only drawings, with a "+ New" card first (:1243-1289).

## 3. Hard-coded colours and strings (not from Studio tokens or strings.xml)

### 3a. Colours

| Where | What | Note |
|-------|------|------|
| LobbyFragment.java:457, 461, 466, 481 | `Color.WHITE` as the ink on four room gradients | Studio has no pure-white token; INK is #E4E4E7. Pick a token and document it |
| LobbyFragment.java:1301 | `Color.WHITE` for "+ New" text | Same |
| LobbyFragment.java:1132 | `0x00000000` fill of the outline pill | Fine (transparent) |
| LobbyFragment.java:900 | `0xFF000000` in `blend()` | Utility, fine |
| LobbyFragment.java:1556 | `0xFF000000` punch-out on New chips | Comment says it is deliberate; could be `Studio.GROUND` |
| JoybotView.java:315 | `setTint(0xFFFFFFFF)` | Joybot tint hard-coded white |
| fragment_lobby.xml:86 | `#00000000` wordmark text | Intentional: the wordmark is baked into the banner art. The "Joy Creator" text at :85 is a hidden fallback |
| fragment_lobby.xml:17, 33, 113-114 | `@color/studio_ground`, `@color/studio_panel` | Legacy names in colors.xml (:113-114). Same values as s_ground and s_panel, so two names for one colour |
| Room gradients | From Studio ROOM_* (good) and JbColors (good) | No issue |

Layout text colours otherwise all use `@color/s_*` tokens, which match Studio.java values (checked for INK, INK_DIM, INK_FAINT, INK_OFF, ON_GO, CAREFUL, GROUND, PANEL, SURFACE, RAISED, GO, GO_END, ARMED, ARMED_LIGHT, VIDEO, LANE_B, ROOM_*, ORB_DEEP).

### 3b. Fonts

`android:fontFamily="monospace"` is hard-coded in fragment_lobby.xml at :121, :251, :315, :384 (stat text, hero tag, hero sub, library count). Code uses `Type.mono(...)` elsewhere (:1377). Move these to the Type helper.

### 3c. Strings not in strings.xml (English only in code or XML)

Java (LobbyFragment.java):
- Room tags, :452-481: "LAST PROJECT", "READY", "LAST SHEET", "LAST CHARACTER", "LAST VISUALISER", "NEW DRAWING"
- :1004 "Back camera" (hero name for Capture)
- :1019 "IN " prefix; :1027-1028 "DRAWING", "DRAWINGS", " · "; :1031 "LAST DRAWING"
- :1250 "Drawings" (library header in Joy Brush room); :1252 "%d DRAWING%s"; :1280 "%d ITEM%s"
- :1271 "Nothing yet. Make something and it will land here."
- :1300 "+ New"; :1306 and :2025 and :1307 "New drawing"; :2025 "This phone has nowhere to keep a drawing." (toast)
- :1347 "●" (dot glyph, fine)
- :1448, :1454, :1477-1485 kind labels "PROJECT", "DRAWING", "EXPORT", "SCREEN", "PHOTO", "STREAM", "RECORDING"
- :1813 "Your newest recording landed after your last edit." ; :1814 "Open Library"; :1817-1819 "recording(s) and no projects yet." and "Start one"
- :1851-1869 "warning", "storage", "movie_edit", "timer", "%s left", "free", "project(s)"
- :2219 "READY · " ; :2244-2246 "d", "h", "m left"; :2251-2256 "just now", "m ago", "h ago", "d ago", "mo ago"; :2233 "—" (no data)
- :276 and :377-378 tooltips: "Dismiss", "Joybot, your helper — he glows green when he has an idea for your project"
- :384 "Search everything" (tooltip)

XML (fragment_lobby.xml):
- :85 "Joy Creator" (hidden wordmark text)
- :109 "storage" and :133 "search" and :390 "chevron_right" and :494 "close" are icon-font ligature names, not text. Fine.
- :137 "Search everything", :154 "Joybot", :498 "Dismiss" contentDescription literals

Translations: none of the 36 `lobby_*` strings exist in any values-xx folder (checked values-de and values-fr directly, and grep across all values-*/strings*.xml returns nothing). The rest of the app is partly translated (values-de has 2354 of 2926 base strings).

## 4. Issues found while reading (ordered by severity)

### P0 (fix before launch)

1. **Joybot's one line counts drawings and exports as recordings.** `paintBotLine` (:1801-1806) counts every Recent with `projectId == null` as a capture. Drawings (:1258, :1454), exports, photos and streams all have a null projectId. So "3 recordings and no projects yet" can be true only because the user drew three pictures. The file's own rule (:1782-1786) says it may only say what it has verified. Fix: count only RECORDING, CAMERA and SCREEN kinds. Also exclude EXPORT from "newest recording landed after your last edit", since exports are made by the Studio and would always look newer than the last edit.

2. **Viz Lab is a visible, dead room.** It has a tile, a hero, a "Start one" action and empty copy promising a feature. Tapping it opens the Studio list. Either remove it from `buildRooms()` until it exists (the NAV_PLAN.md note says not to ship an empty room), or build a real screen. Removing it shifts indices 4 and 5. The code uses `rooms.indexOf(r) != 4` (:1017) and `ROOM_JOYBRUSH = 5` (:1981), so change those with it.

3. **Floor words that go to the wrong or duplicate place.** "Bits" opens Home (Capture). "Packs" and "Setup" both open Setup. "Finder" opens the Lab item while the label says Finder. Decide each one: real destination with a matching label, or cut it. A dead word is worse than a missing one (per the owner's "dead knob" rule in the design notes).

4. **Main-thread disk reads on every resume.** `onResume` calls `reloadProjects()` (:1961, which parses each project's JSON via `listProjects`) and `reloadDrawings()` (:1983) synchronously. With a large project or drawing library this can stall the front door. Move both to the background, the way recents already are.

### P1 (should fix before launch)

5. **Capture hero is a fixed label.** The name "Back camera" (:1004) is always shown, even if the user has chosen another camera. Read the real camera or drop the name.

6. **Sprite Lab and Avatar hero tags disagree with their content.** The tag says "LAST SHEET" or "LAST CHARACTER" over a project name. Either show the project as "IN <name>" with a neutral tag, or show the actual sheet or character.

7. **Hero "New" for Sprite Lab, Avatar and Viz Lab goes to the Studio list.** The comment at :1113-1119 says it routes to the room. Fix the comment or the behaviour. A hero "New" that lands on the project list is the same confusion the screen was redesigned to remove.

8. **Storage readout is an estimate with a made-up rate.** `approxRecordingTime` (:2240) divides free bytes by an assumed 9 MB/s and prints "Xh left". It is labelled rough in code, but the screen shows it as a number. Also `freeSpaceBytes` (:2222-2224) measures the app's internal files directory, while recordings default to `getExternalFilesDir` (RecordingStoragePaths.java:132) and can go to a SAF folder. Check that the figure is for the right volume, or label it "about".

9. **Import chip and its accessibility text promise an import that does not exist in the lobby.** It opens the Library. Either add an import entry point or relabel.

10. **MINIAPPS and UNKNOWN recordings are labelled RECORDING** (:1484-1485). Give them their own label or leave them out of the recents.

11. **Lobby text is English-only.** The 36 `lobby_*` strings are in values/ only. Move the hard-coded strings in section 3c into strings.xml, then add translations to the locale folders that already carry the rest of the app.

12. **Room addressed by magic numbers.** `active = 2` (:1538), `== 2`, `== 3` (:1161, :1169), `!= 4` (:1017), `ROOM_JOYBRUSH = 5` (:1981). Replace with named constants so a reorder does not silently repoint the chips.

### P2 (polish)

13. **Colour consistency.** Replace `Color.WHITE` (4 room inks plus "+ New") with one named token. Replace `setTint(0xFFFFFFFF)` in JoybotView with a token. Merge `studio_ground`/`studio_panel` in colors.xml into the `s_*` tokens. Replace the four `fontFamily="monospace"` in the layout with the Type helper.

14. **Unused code.** `circleGradient` (:2290) is defined and never called. `ROOM_SPRITE_END` in Studio is unused by the lobby. Remove them.

15. **Per-frame work during scroll.** `layoutMarquee` (:763-811) runs for all 18 marquee words on every scroll frame and calls `setTextColor` on each. It is cheap, but worth checking on the Note 9 with a profiler.

16. **Hidden trash bin.** Long-press on the banner is the only way in, and nothing hints at it. Fine if intentional; note it in the manifest.

17. **Thumbnail executor is never shut down.** `THUMB_IO` (:2059) is a static two-thread pool. Harmless in practice.

18. **Search is a shortcut to the Library, not a search.** Records search is declared but never wired (tasks/APP_MANIFEST.md). Either wire it or remove the magnifier.

19. **Comment drift.** Several comments describe behaviour the code no longer has. The New-button comment is the main one (item 7). The class javadoc (:75-76) still says "when the drawing engine lands it is a word on this row", although Joy Brush is already that room.

## 5. Recommended launch order

1. P0-1 (Joybot count), P0-2 (remove or build Viz Lab), P0-3 (floor words), P0-4 (move reloads off the main thread).
2. P1-5 through P1-8 (hero truth and storage figure).
3. P1-11 (strings and translations), since it is mechanical.
4. P2 items when there is time.

## 6. Files

- Lobby: `app/src/main/java/com/fadcam/ui/lobby/LobbyFragment.java`
- Lobby layout: `app/src/main/res/layout/fragment_lobby.xml`
- Joybot view: `app/src/main/java/com/fadcam/ui/lobby/JoybotView.java`
- Studio tokens (Java): `studiokit/src/main/java/com/fadcam/ui/faditor/Studio.java`
- Studio tokens (XML): `app/src/main/res/values/studio_tokens.xml`
- Joy Brush room colours: `joybrush-android/src/main/res/values/jb_tokens.xml`
- Strings: `app/src/main/res/values/strings.xml` (lobby block at about :3440-3470, accessibility block at :11-26)
- Routing: `app/src/main/java/com/fadcam/MainActivity.java` (:1897, :2052-2084)
- Existing inventory this agrees with: `tasks/APP_MANIFEST.md` (lobby routes, line 19)
