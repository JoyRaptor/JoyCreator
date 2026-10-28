# Feature reach audit (DOOR-1), 2026-10-28

Read-only map of how a user reaches every feature from the lobby. Job card: [DOOR-1](../jobs/DOOR-1_feature_map_audit.md).
Code read at `933b2b636` (`origin/joy-creator` on 10-28). No code, layout, string or asset was changed; no phone was used.

**How to read it.** "Taps" counts from the lobby (lobby tap included). "Newcomer finds it?" is yes / maybe / no, judged by
whether something visible on the way says it is there. "FREE-1" marks a route that passes through a FadCam-era screen
(the old tabs Home 0, Records 1, Remote 2, Studio list 3, Settings 4, the forensics "Lab" 5), see [FREE-1](../jobs/FREE-1_fadcam_inventory.md).
Evidence is `file:line` at that commit. Short names: `LOB` = `app/src/main/java/com/fadcam/ui/lobby/LobbyFragment.java`,
`AE` = `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java`, `CHAT` = `.../ui/faditor/ai/ChatAssistantActivity.java`,
`JBA` = `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt`.

**Proof level of this audit.** The lobby, Library, Joybot routing and the "no path" items below I read in the code myself.
The in-wing rows (Studio editor, JoyBrush, Sprite Lab / Avatar, VisLab, Capture, Settings) were traced by three read-only
sub-agents and I re-checked a sample of their claims (Joybot routes, the Capture logo long-press, the AI-key dialog,
the JoyBrush Copy stub, the unused audio Clean tab, the timer actions, the missing timeline marks). Anything marked
**unverified** I did not confirm. Nothing here was settled on a device; where a path can only be settled there it says
"unverified – needs phone".

---

## Worst ten

Ordered by how much it costs a newcomer: valuable and hard to find first, then paths that mislead.

1. **The lobby's "Import" chip imports nothing.** It only opens the Library (`LOB:1748-1749`). The real ways in are
   Library, Projects chip, New project, Browse device (4 taps from the lobby), or the Android share sheet into Joy Creator
   (`ShareInActivity`, manifest `:160`). Misleading, and import is a first-hour need.
2. **The AI key, model and service address have no home in Settings.** They exist only behind the gear inside the Joybot chat
   (`CHAT:319-320`, `showSettingsDialog` `:2923`). The Settings hub catalogue has no AI row. A newcomer who wants Joybot to
   work must guess to open him first. High value, no signpost.
3. **Floor words lie or double up.** "Finder" opens the forensics Lab, a gallery of detected-object evidence (`LOB:1937`, tab 5): the name and the place disagree. "Bits" opens Capture, the same place as the Capture room and "Recording" chip (`LOB:1939`). "Packs" and "Setup" both open the Settings hub (`LOB:1942-1943`). All FREE-1 except Packs/Setup.
4. **Two trash bins, and the hidden one is the old one.** Library, options, Trash opens the new Recently deleted (`LibraryFragment.java:331-333`).
   A long-press on the lobby banner opens the old `TrashFragment` (`LOB:343-356`, `:1352`), as does a long-press on the Capture
   logo (`HomeFragment.java:9658`) and Joybot's "trash" route (`CHAT` route `:2120-2185`, `MainActivity.java:1000-1006`). Nothing hints at the long-press. FREE-1.
5. **Joybot's "Take me there" bypasses the new screens.** `library` goes to the classic Records tab, `setup` to classic Settings,
   `studio` to the classic Studio list, `trash` to the old trash (`CHAT:2120-2185`, `NavigateActions.java:20-33`). He cannot reach Remote or Lab at all. A guided newcomer lands in the old FadCam look. FREE-1.
6. **Timeline marks are "BUILT" in the roadmap but not in the code that ships.** Named bookmarks, In/Out regions, loop and region
   export are on the rescue commit `94a5f7e69` (job STU-3), not on `joy-creator` (`git grep` finds no marks workspace there). What the
   branch has is a bare hold-the-ruler add/remove with no way to list, jump to or clear marks (`TV:400-407`, `AE:5115-5139`).
7. **"Character" does not open Avatar, and neither wing works without a project.** The chip selects Sprite Lab and enters it on the newest project
   (`LOB:1742-1747`); Avatar is a separate room that needs a project too (`LOB:1278-1291`). With no project both fall back to the Library's Projects view.
   Standalone Avatar Studio has one door: the classic Studio tab's avatar button (`FaditorMiniFragment.java:185-187`). Puppeteering (the rigged-picture flow) is one more step away: image drawer, Puppet tab, reached by double-tap or hold on the picture.
8. **Studio effects, including the ten VisLab finishes, are 3 taps in and hidden by type.** Open project, select a clip, Adjust, Add effect, chip (`FxPanel.java:1806-1873`).
   On PiP, image and text only Scanlines, Flash and Vignette are addable; the rest show greyed; sprites and visualizers have no Effects tab (`FxPreviewTier.java:69-77`). A newcomer will not learn which effect works where.
9. **JoyBrush's strongest features are behind a hold or a second tap, and the lobby has no Drawing in its New row.** Brush advanced settings = hold the tool (`JBA:754`); layer options = tap a selected layer again (`LayerColumnView.kt:512`); animation, sprite and tile boards = ⋯, Boards, New X, drag a rectangle (about 4 taps plus a drag, `BoardRuntimeController.kt:754-806`); board export = 5 taps. The New row offers Recording, Project, Character, Import only (`LOB:1728-1749`), no Drawing and no visualiser. Several JoyBrush features have no path at all (section below).
10. **Launch-sensitive FadCam routes are still one tap from the front door.** Floor Remote (streaming server, cloud account), Capture's "Get Pro", the privacy black screen on the logo tap, 15 disguise launcher icons in Appearance, and the accessibility screenshot service are all still present (`LOB:1938`, `HomeFragment:4672-4685`, `:9952`, manifest `:196-312`, `:372-381`). Not a newcomer-confusion item but a path the Play build must close (lane 1.1-1.4). FREE-1.

---

## The front door (lobby)

| Control | Goes to | Taps | Newcomer finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Room dial: Studio, Capture, Sprites, Avatar, Viz Lab, Joy Brush | the room's hero; swiping the hero turns the dial | 1 | yes | Rooms are words on a strip, no explanation of what a room is | `LOB:475-511`, `:1185` |
| Hero action "Carry on / Record / Open / Start" | Studio opens the newest project; Capture is tab 0 (FREE-1); Sprites/Avatar open the editor on the newest project else the Library Projects chip; Viz Lab opens VisLab; Joy Brush opens the last drawing else a new one | 1 | yes | Sprites/Avatar silently depend on a project | `LOB:1224-1292` |
| Hero "New" | Joy Brush: new drawing. Capture: tab 0 (FREE-1). Studio: Library Projects with the new-project sheet. Sprites/Avatar/Viz: the Library Projects view with no sheet | 1 | maybe | "New" in Viz Lab goes to a project list (a comment at `LOB:1085-1092` still says Viz Lab has no room: stale) | `LOB:1206-1211` |
| Joybot orb | the chat, carrying the newest project; glows when he has an idea | 1 | yes | None | `LOB:400`, `:1295-1307` |
| Search glass | Library with the keyboard up | 1 | yes | None | `LOB:409` |
| Library row door and "N ITEMS" | Library (Joy Brush room: all drawings) | 1 | yes | None | `LOB:405`, `:1376` |
| Recents cards | project: editor; drawing: Joy Brush; a recording, photo or export: the Library, not the item | 1 | maybe | A recording card does not play the recording | `LOB:1552-1559` |
| New chips: Recording / Project / Character / Import | Capture (FREE-1) / Library Projects + sheet / Sprite Lab on the newest project / Library | 1 | maybe | "Character" is Sprite Lab, "Import" is only the Library (worst ten 1 and 7) | `LOB:1728-1749` |
| Joybot's one line (yes / dismiss) | Library or "Start" | 1 | yes | Shown only when there is something true to say | `LOB:2002-2048` |
| Stat line (storage, projects, time left) | nothing, display only | n/a | n/a | Looks tappable; is not | `LOB:2058` |
| Floor: Finder / Remote / Bits / Packs / Setup | Lab (FREE-1) / Remote (FREE-1) / Capture (FREE-1) / Settings hub / Settings hub | 1 | no | See worst ten 3 | `LOB:1937-1943` |
| Long-press banner, wordmark or chrome row | old Trash (FREE-1) | hold | no | Hidden, and the old screen | `LOB:343-356` |

How the app starts: Splash, then MainActivity (`SplashActivity.java:29-31`), onboarding if not done (`MainActivity.java:404-420`), then the Joy folder choice opens on top of the lobby until a folder is chosen (`MainActivity.java:1371`, `JoyFolderSetupActivity.java:73-77`). Once in any old tab (0 to 5) the old bottom nav is visible and every other old tab is one tap away; back returns to the lobby (`MainActivity.java:2141-2146`, `:1197`). That nav is how most FadCam-era screens stay reachable.

---

## Per-feature table

Columns: feature (STATE section) | path from the lobby | taps | newcomer finds it? | problem note | evidence.

### Studio (STATE 2, 3, 4, 5.5, 6, 7)

The lobby gets you to the newest project in 1 tap. To open any other project: Library row (1), Projects chip (2), item (3).
"+n" below means n more taps inside the editor, so total = 1 + n for the newest project.

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Make a project from a video | Studio hero "New" or New chip "Project" opens the new-project sheet: blank audio, Browse device (multi-select), or Your recordings | 2-3 | yes | No image or screen-recording option in the sheet | `NewProjectFlow.java:100-110`, `VideoSourceBottomSheet.java:63-72` |
| Export (name, loudness, resolution, quality, 720p-Low draft chip) | project, Export, Start | 1+2 | yes | None | `AE:10161` |
| Single-frame export | Export, tick "Export frame", Start | 1+3 | maybe | Tick-box inside the dialog | `AE:showExportConfirmation` |
| Joybot in the editor | top-bar bot icon | 1+1 | yes | None | `AE:3711` |
| Asset browser (pinned folder panel) | folder icon | 1+1 | maybe | Asks for a folder first | `AE:3708` |
| Rename project | hold the title | 1+1 | no | Hold-only | `AE:2429`, `:5345` |
| Relink / gather / bundle (project file actions) | hold title, More, choice | 1+3 | no | Buried; relink button shows only when media is missing | `AE:5376`, `:3558`, `:17551-17556` |
| Undo / Redo with counts; history list | transport; hold for the list | 1+1 | yes / no | List is hold-only | `AE:3534-3536` |
| Select / marquee mode and batch actions | transport Select; drag box; hold on selection | 1+1..3 | maybe | Batch menu is hold-only | `AE:3656-3661`, `TV:7947` |
| Ripple / gap delete; Link tool; Snap magnet | transport buttons | 1+1 | maybe | Link defaults and snap settings are hold-only | `AE:3586`, `:36077-36090`, `:3562-3564` |
| Proxy ("dummy") object, link follows | hold the link icon | 1+1 | no | Hidden | `AE:36087`, `:36953` |
| Voiceover | transport mic, or Add, Voiceover | 1+1 | yes | None | `AE:3540-3545` |
| Jump to time | tap the time readout | 1+1 | no | Not visibly a button | `AE:45279` |
| Bookmarks on the ruler | hold the ruler | 1+1 | no | Add/remove only; no list, jump or clear (marks wheel not landed, see worst ten 6) | `TV:400-407`, `AE:5115-5139` |
| Settings sheet (ask-to-transcribe, safe zone, drawer see-through, waveform) | tool carousel, Settings (last tool) | 1+1 | maybe | Last in a long carousel | `AE:3637-3643` |
| All-tools sheet; reorder and pin tools | swipe up on the carousel | 1+1 | no | Swipe-only | `AE:1280-1283` |
| Speed, Rotate, Flip, Crop, Canvas ratio | carousel tool | 1+1..2 | yes | Speed needs a selected clip | `AE:6635-6650` |
| Volume, Opacity (tap, drag or hold = keyframe mode) | Sound / Opacity button | 1+1 | maybe | Three gestures on one button | `AE:6507-6577` |
| Split / Heal, Delete, Duplicate | carousel | 1+1 | yes | None | `AE:3605-3607` |
| Add (Video, Image, Black clip, PiP overlay, Audio, Voiceover, AI slide, FX layer, Image as clip) | Add, then row | 1+2 | yes | PiP guarded by `LIVE_PIP` | `AddAssetBottomSheet.java:146-203` |
| Image overlay; image as spine clip | Image; hold Image | 1+1 / 1+2 | yes / no | Second is hold-only | `AE:3614-3620` |
| Text, text animation, text drawer | Text tool opens the drawer | 1+1 | yes | None | `AE:3609`, `:37076`, `:37454` |
| Text countdown / stopwatch ("Make it a timer") | none | | | **No path**: superseded by the new text drawer | `AE:28891`, `:29070` |
| Captions, caption styles, apply to all, hide on all | Captions; chip; hold chip | 1+1..2 | yes / no | "All clips" and "Hidden" are hold-only | `AE:3678`, `:40144-40199` |
| Transcription (Fast / Accurate / Whisper), filler strike, search, versions | Transcript, then chips | 1+1..2 | yes | Word strike and retime are hold | `AE:3666`, `:39377`, `:39721` |
| Transcript picker between clips | hold Transcript | 1+1 | no | Hold-only | `AE:3673` |
| Filter (grade, LUT, presets) | select a clip, Filter | 1+2 | yes | None | `AE:3611`, `:20494` |
| Move (position, layer order) | Move | 1+1 | maybe | None | `AE:3694` |
| Transitions (9 basic + GL cards + user .glsl) | Transitions; tap a seam to edit | 1+1 | yes | None | `AE:3677`, `:3295-3305` |
| Silence detect and remove; Fix audio; Beats; Align audio | carousel tools | 1+1..2 | yes | STATE still says Fix audio is "not exposed"; the button exists | `AE:3630-3635`, `:44627-44848` |
| Audio "Clean" (noise reduction bake) tab | none | | | **No path**: tab built, never added to a drawer | `AudioDrawerTabs.java:485` |
| Loop drawer; Compact (consolidate layers) | carousel | 1+1 | maybe | None | `AE:3669`, `:3653-3655` |
| Sprites palette (play, flips, end behaviour, perform, sweep, dope) | Sprites | 1+1 | yes | None | `AE:3645`, `SpritePalettePanel.java:293-749` |
| Sprite sheets, image sequence from folder or files, Avatars | Sprites, Manage sheets, picker row | 1+3 | maybe | Avatar rows live in the sprite picker | `AE:41957-42012`, `:42857` |
| Adjust: effect stack for a clip or layer | Adjust | 1+1 | yes | None | `AE:3648`, `:30243` |
| New adjustment layer | hold Adjust, or Add, FX layer | 1+2 | maybe | Hold shortcut hidden; Add route visible | `AE:3651`, `AddAssetBottomSheet.java:188` |
| Studio effects: the 10 VisLab finishes + Glow, Flash, Vignette, Ripple, Swirl, Warp, Echo Trails | Adjust, Add effect, chip | 1+3 | maybe | Greyed by type (worst ten 8); Echo Trails offered more widely than STATE says (unverified whether spine export honours it) | `FxPanel.java:1806-1873`, `FxVisLabDefs.java:296-359`, `FxPreviewTier.java:69-77` |
| Music link (♪) on effect sliders | Effects tab, any slider | 1+4 | no | Code comment says a clip's own effects do not hear the soundtrack, STATE says they do (unverified) | `FxPanel.java:402-407` |
| Effect presets ("looks"), reorder cards | Effects tab; hold | 1+4 | no | Hold-only | `FxPanel.java:1685-1745`, `:1206` |
| Visualizer element | select a clip, Visualizer, double-tap or hold its bar | 1+3 | maybe | Tool adds the row without opening its drawer | `AE:3610`, `:20448` |
| VisLab look on a Studio visualizer; Edit in VisLab | visualizer drawer, Style tab | 1+4 | no | Deep | `VisLabStyleTab.java:36-250`, `AE:20596` |
| Sprite element | Sprites / Add | 1+1 | yes | None | `AE:3645` |
| Image drawer: Transform, Blend, Mask, Key, Effects, Lanes, Puppet | add or select Image, double-tap or hold bar, tab | 1+3..4 | no | Mask and Key tabs exist only on image, PiP, adjustment drawers | `AE:35131-35290` |
| Mesh bend on a picture | select picture, "Bend" pill on the box | 1+2 | maybe | Images only | `TransformOverlayView.java:869-882`, `CornerPinTransformHost:863` |
| Puppeteering (pins, bones, take, drag) | image drawer, Puppet tab; marionette badge on a rigged picture | 1+4 | no | Un-rigged first entry is double-tap/hold (unverified – needs phone) | `AE:35274`, `PuppetDrawerTabs.java:114-117`, `AE:33600-33705` |
| Blend modes, masks, chroma key | drawer tabs (see above) | 1+4 | no | Same | `PipDrawerTabs.java:711`, `:1136`, `:1228` |
| Keyframes | diamond in any drawer; hold diamond for ease | 1+3 | maybe | Ease picker hold-only | `AE:26559` |
| Timeline gestures: lane header menu, reorder spine clip, clip sound shelf, slide code sheet, fade handles, pinch zoom | hold / double-tap / drag | 1+1 | no | All gesture; one coach card on first selection explains three (`AE:28097`) | `AE:16047`, `TV:1339`, `TV:9716`, `TV:9699` |
| Slide from AI (copy prompt, paste) | Add, AI slide | 1+2 | yes | None | `AE:46679` |
| AI proposal cards (narrative, b-roll, avatar rig) | Joybot chat only | 1+2 | maybe | No editor button; the model must call the tool | `AIToolExecutor.java:940`, `:1293`, `:1734` |
| Luma key | none | | | **No path**: no code (STATE marks spec-only) | grep, no symbol |
| Share-in "Add to project" | Android share sheet into Joy Creator | outside | maybe | Add-to flow unverified | `ShareInActivity.java:80-222` |
| Joy Creator folder, backup, bring back | Settings hub, Folder, Joy Creator folder | 3 | maybe | Setting up is forced on first run | `SettingsDestinations.java:62`, `JoyFolderSetupActivity.java:129-207` |

### Capture (STATE 1) — all FREE-1

Reached by the Capture room (hero, 1 tap), the "Recording" chip (1), floor "Bits" (1), or Studio hero "New" in Capture. Every row passes through the old Home tab (0).

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Start / stop, pause | big button, pause button | 1+1 | yes | None | `HomeFragment.java:5189`, `:5264` |
| Camera flip, front mirror | buttons | 1+1 | yes | Mirror condition unverified | `:5276`, `:5282` |
| Camera, lens, resolution, fps, codec, bitrate, zoom, splitting, location | the Camera row on the Home card | 1+2 | maybe | Same screen is also in Settings | `:11666`, `VideoSettingsFragment.java:134-149` |
| Dual camera | Video settings, camera type Dual | 1+3 | no | Device state unknown (STATE) | `:5465` |
| Audio, watermark, screen-recording settings | Settings hub, Recording | 2-3 | maybe | Old settings screens | `SettingsDestinations.java:74-77` |
| Torch (tap; hold for options) | button | 1+1 | yes / no | Options hold-only | `:9088`, `:9133` |
| Preview on/off | hold the preview | 1+1 | no | Hidden | `:727`, `:5049` |
| Tap-to-focus, pinch zoom, exposure / AF / zoom tiles | on the preview | 1+1 | maybe | Pinch is a gesture | `:4897`, `:8103-8224` |
| Fullscreen preview; photo | buttons | 1+1 | yes | None | `:10455`, `:10465` |
| Screen recording (FadRec mode) with floating controls, annotation, webcam overlay | mode switcher on tab 0 | 1+2 | maybe | Webcam toggle location unverified | `ModeSwitcherComponent.java:398-406`, `FadRecHomeFragment.java:594-629` |
| FadMic mode | switcher | | | "Coming soon", blocked | `ModeSwitcherComponent.java:421` |
| Self-timer / scheduled recording | none | | | **Not built** | grep, none |
| Screenshot (accessibility) and launcher shortcuts for record, stop, photo, torch | Android shortcuts | outside | maybe | Accessibility service is a Play exclusion | manifest `:372-444` |
| Get Pro | Capture | 1+1 | maybe | Play exclusion | `:4672-4685` |
| Privacy black screen | tap the Capture logo | 1+1 | no | Hidden, Play exclusion | `:9952` |
| Mini apps: Torch, QR scanner work; Compass, speedometer, clinometer, pedometer, metal detector, parking marker, QR generator are "coming soon" | hamburger on Capture | 1+2 | no | Placeholders in the sidebar | `HomeSidebarFragment.java:283-328` |

### Library, Trash, Settings, Remote, Lab

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Library: 12 kind chips (Projects, Drawings, Sprite sheets, Avatars, Recordings, Photos, Exports, Clips, Images, Audio, Documents, Supplies), search, shared-in filter, sort and view cycles | Library row | 1 | yes | None | `LibraryKind.java`, `LibraryFragment.java:131-158` |
| Open an item by kind (project, drawing, sheet, avatar, video, image, other app) | tap | 2 | yes | Fonts and plugins just show the sheet | `LibraryOpener.java:48-76` |
| Item menu: Open, Rename (drawings only), Share, Move to Trash | hold an item | 2+hold | no | Hold-only; most kinds cannot be renamed | `LibraryItemSheet.java:35-87`, `LibraryActions.java:38-61` |
| Recently deleted (new trash) | Library, options (⋯), Trash; or Settings, Folder, Recently deleted | 3 | maybe | See worst ten 4 | `LibraryOptionsSheet.java:30-40` |
| Old trash | hold the lobby banner | hold | no | FREE-1 | `LOB:343-356` |
| "Classic Records", "Classic Studio list" | Library, options | 3 | maybe | FREE-1 escape hatches to tabs 1 and 3 | `LibraryFragment.java:334-335` |
| Settings hub: Folder, Studio, Recording, Appearance, Notifications, Security, Shortcuts, Labs, About | floor "Setup" or "Packs" | 1 | yes | most rows below Studio open old FadCam settings screens | `SettingsCatalog.java:62-125` |
| Language, theme, app icon | Settings hub, Appearance | 3 | maybe | No hub row of their own | `AppearanceSettingsFragment.java` |
| Show intro again; What's New; Debug log; settings backup | Settings hub, About | 3 | maybe | FREE-1 (old screens) | `SettingsDestinations.java:91-96` |
| Classic Settings | Settings hub, last row | 2 | no | FREE-1 (tab 4) | `SettingsCatalog.java:125` |
| Remote: LAN streaming server, endpoints, clients, quality, security, E2E password, cloud account | floor "Remote" | 1 | maybe | Play exclusion for cloud; "Remote" is the old tab 2 | `LOB:1938`, `RemoteFragment.java:330-1655` |
| Lab: detected-object evidence gallery, export centre, insights, clip/tape style | floor "Finder" | 1 | no | FREE-1; name and place disagree | `LOB:1937`, `ForensicIntelligenceFragment.java:66-135` |

### Sprite Lab and Avatar (STATE 5)

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Sprite Lab: slice, align, clips, out (grid, cell editor, onion, auto-centre, plant feet, sequences, bake, export `.sprite.json`) | Sprites hero (newest project's sheet) | 1+1 | maybe | Needs a project; first-time "New sheet" is a Studio-side picker | `LOB:1270-1276`, `SpriteSheetEditorActivity.java:451-514`, `:116-121` |
| Open a named sheet | Sprites hero when it names a sheet; Library, Sprite sheets chip | 1-3 | yes | Sheet with no project opens in another app | `LOB:1248-1252`, `LibraryOpener.java:57-61` |
| Reach Sprite Lab from other places | Studio sprite palette "Open Lab"; JoyBrush Export, Open in SpriteLab; Joybot | 3-5 | no | None | `SpritePalettePanel.java:294`, `BoardExportCoordinator.kt:106` |
| Avatar Studio (pose matrix, parts, pins, face tracking) | Avatar hero | 1+1 | no | Needs a project; "Character" chip does not go here | `LOB:1278-1284`, `AvatarStudioActivity.java:612-1022` |
| Avatar, standalone (no project) | classic Studio tab avatar button | 3+ | no | FREE-1 | `FaditorMiniFragment.java:185-187` |
| Rig a biped from a template | none | | | **No manual path**: only Joybot's `author_avatar_rig` | `ToolRouter.java:164` |
| Avatar lip sync / live drive | Avatar Studio, Track | 1+2 | no | Asks camera and mic | `AvatarStudioActivity.java:1016` |

### VisLab (STATE 5.5)

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Open VisLab; pick a look; change source (demo, microphone, song, MIDI, .kar, .mod) | Viz Lab room hero | 1 | yes | None | `LOB:1254-1256`, `VisLabActivity.java:264-294` |
| Swipe to the next look; tap to hide chrome | gestures | 1+1 | no | Gesture-only | `VisLabActivity.java:169-190` |
| Build a look: add a layer (35 types, parts shelf), knobs, finish, echo, colours | Edit, tab, "+ Add a layer" | 1+3 | maybe | Deep but laid out | `KnobPanel.java:150-320` |
| Music link on a knob | Edit, layer, knob; hold removes | 1+4 | no | 4+ taps, hold to remove | `KnobPanel.java:985-1020` |
| Words layer fonts, Picture layer, Sprite layer | layer controls | 1+4 | maybe | None | `KnobPanel.java:522-700` |
| Save as part; duplicate / rename / delete / revert a look | drawer | 1+3 | maybe | Bottom of a scrolling tab | `KnobPanel.java:240-245`, `:439-473` |
| Use a look in a video | Studio, Visualizer, Style tab | 1+3..4 | no | Cross-wing path (see Studio) | `VisLabStyleTab.java` |
| Floating visualiser over other apps | recorder toolbar toggle | 1+1 | no | FREE-1 (Capture toolbar); does not open the room | `AnnotationService.java:1325-1334` |
| Looks in the Library | none | | | **No path**: no Library kind for looks | `LibraryKind.java` |

### JoyBrush (STATE 5.6, JoyBrush ROADMAP)

Lobby: Joy Brush hero (1) opens the last drawing; hero "New" or the "+ New" card makes one. No project needed.

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Paint, pan / zoom / rotate, undo / redo (also 2- and 3-finger taps) | the canvas | 1 | yes | Finger stops painting once a pen is seen | `JbCanvasView.kt:1366-1436` |
| Hide all chrome | 4-finger tap | 1+gesture | no | Hidden | `JBA:509` |
| Eyedropper | hold 450 ms; drag off the swatch; pen button | 1+hold | no | Three hidden ways | `JbCanvasView.kt:1472-1527`, `JBA:1895-1940` |
| Colour wheel, full picker, recents | swatch | 1+1 | yes | None | `JBA:1855`, `:1885` |
| Brush drawer (shelves, built-in brushes, pencil / watercolour / oil engines) | tap the active tool again, pick | 1+2 | maybe | Second tap | `JBA:751`, `:864-890`, `BrushLibrary.kt:56-77` |
| Brush advanced settings with live preview, curves, Reset, Test | hold the tool or a brush; or ⋯ | 1+1..3 | no | Hold-only first | `JBA:754`, `:882`, `:1049` |
| Size / opacity; smoothing; editable-lines switch | strip drag; ⋯ | 1+1..2 | maybe | None | `ToolStripView.kt:105`, `JBA:1016-1044` |
| Select (lines): move, flip, duplicate, delete, adjust colour, rasterize down | strip Select, then the selection row | 1+1..4 | maybe | Deep | `ToolStripView.kt:98`, `SelectRowView.kt:74-92` |
| Copy / Paste of a selection | Select, More, Copy | 1+3 | | **Copy is a stub** (toast "Copy is coming next"), Paste has no code; STATE 5.6 says Copy/Paste works | `JBA:2591`, `SelectController.kt:403` |
| Layers (add, paper, rename, opacity, 27 blend modes, mask, clip, merge, flatten, reorder) | Layers; tap a layer again; hold to reorder | 1+2..5 | maybe | Blend mode 4-5 taps deep | `JBA:672`, `LayerColumnView.kt:485-546` |
| Paper sheet (looks, surface, tint) | Layers, paper | 1+2 | maybe | Tint-hold restore is hidden | `JBA:1266`, `PaperSheetView.kt:59-95` |
| Guides (grid, isometric, perspective, ruler, snap) | top bar Guides | 1+2 | yes | None | `JBA:1614-1685` |
| Pin a reference picture | pin | 1+1 | maybe | Hold menu hidden | `JBA:668`, `:1691` |
| Boards: Image, Animation, Sprite, Tile; frames, onion, play, fps | ⋯, Boards, New X, drag a rectangle | 1+4 | no | Deep, then holds for frame menus | `JBA:978`, `BoardRuntimeController.kt:522-806` |
| Export: PNG, ORA, GIF, PNG frames, sprite sheet, Send to Studio, Open in SpriteLab | ⋯, Export, format | 1+3..5 | maybe | Send-to-Studio into an existing project is on a branch only | `JBA:1014`, `:2384-2437`, `BoardExportCoordinator.kt:106-107` |
| Save a copy, open, recent drawings | ⋯ | 1+2 | yes | None | `JBA:1011-1013` |
| Joybot hands (add layer or frame, set brush, draw a shape) | top bar | 1+1 | yes | Shapes by Joybot only; no manual shape tool | `JBA:659`, `JoybotHands.kt:388` |
| Pen diagnostics | hold ⋯ | 1+hold | no | Hidden | `JBA:676` |
| Drawings list; rename / delete | Library door in the Joy Brush room; hold | 1-2 | maybe | Hold for rename/delete | `DrawingsActivity.kt:117`, `:152-162` |

### Joybot (STATE 6)

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Chat from the lobby / editor / Sprite Lab / VisLab / JoyBrush / classic Records | orb or top-bar bot | 1 | yes | Not offered from Capture, Remote, Lab, Avatar Studio | `LOB:400`, `AE:3711`, `SpriteSheetEditorActivity.java:500`, `VisLabActivity.java:254`, `JBA:659` |
| Key, model, service address, picture model, read-aloud, accounts | chat gear | 2 | no | Worst ten 2 | `CHAT:319-320`, `:2923` |
| Model switcher; attach picture or video frame; mic (hold = dictate and send) | chat top row | 2 | maybe | Mic hold hidden | `CHAT:311`, `:316-328` |
| Offline help book with no key | chat | 1+1 | yes | None | `CHAT:974-1062` |
| Tool actions (about 100: audio, captions, markers, grade, motion, library, VisLab, JoyBrush) | ask in chat | 2 | yes | JoyBrush actions work only while a drawing is open | `ai/actions/*.java`, `JoybotAgent.java:1280` |
| "Take me there" | chat | 2 | yes | Goes to classic screens (worst ten 5) | `CHAT:2120-2185` |
| Idea glow on the orb | automatic | 0 | yes | None | `LOB:1310-1315` |

### Onboarding, About, launcher-level

| Feature | Path | Taps | Finds it? | Problem | Evidence |
|---|---|---|---|---|---|
| Onboarding slides (welcome, permissions, agreement) | first launch | auto | yes | Screen text is FadCam-era in part (lane 1.8) | `OnboardingActivity.java:110-112`, `:773` |
| Joy folder choice | opens on top of the lobby until chosen | auto | yes | Skipped on a phone with no folder picker | `MainActivity.java:1371` |
| About Joy; Classic About | Settings hub, About, About; foot link | 3 / 4 | maybe | Classic About is FREE-1 | `SettingsDestinations.java:98`, `AboutSignOff.java:55-57` |
| Share to Joy Creator (image, video, audio) | Android share sheet | outside | maybe | None | manifest `:160-177`, `ShareInActivity.java` |
| Home-screen widget | clock widget only | outside | n/a | FadCam-era | manifest `:513-520` |
| 15 disguise launcher icons | Settings hub, Appearance, App icon | 3 | no | Play exclusion (lane 1.1) | manifest `:196-312`, `AppearanceSettingsFragment.java:432-467` |

---

## Features with no path at all (built in code, nothing reaches them)

| Item | Why it is unreachable | Evidence |
|---|---|---|
| Timeline marks: named bookmarks, regions, loop, marker exports | Not on `joy-creator`; lives on rescue commit `94a5f7e69` (STU-3). On `joy-creator` bookmarks have no list, jump or clear | `AE:5115-5139`; HYG-1 card line 28 |
| Audio drawer "Clean" tab (noise-reduction bake) | Function defined, never added to a drawer | `AudioDrawerTabs.java:485` |
| Text "Make it a timer" (countdown, stopwatch) | Old text sheet superseded by the text drawer; no timer control in the drawer | `AE:28891`, `:29070`, `:37076` |
| Interim sprite and visualizer object sheets | Retarget-only callers | `AE:29425`, `:32509` |
| JoyBrush Copy / Paste | Copy shows a toast, Paste has no code | `JBA:2591` |
| JoyBrush tween, animation clips | Core code with no Android caller (a Codex branch carries the integration) | `joybrush/core/.../anim/Tween*.kt`; no reference in `joybrush-android` or `app` |
| JoyBrush brush importers (ABR, Procreate, Krita, MyPaint) and the "Imported" shelf | No caller outside core and tests (unverified feeder) | `joybrush/core/.../brush/imports/*` |
| JoyBrush ten line presets (Fineliner, Chisel marker, Wire...) | README says "not drawer entries yet" | `brushes/lines/*.json`, `brushes/index.txt` |
| JoyBrush symmetry; send-to-Studio into an existing project; PSD/SVG export | Branch only or not built | no code on this tree |
| Biped avatar rig template by hand | Only through Joybot's tool | `ToolRouter.java:164` |
| Bend on sprites, text, PiP, spine clips | `supportsBend` is image-only | `CornerPinTransformHost.java:863` |
| Luma key; self-timer / scheduled recording; FadMic | Not built or blocked | grep; `ModeSwitcherComponent.java:421` |
| Capture sidebar "Profiles" and seven mini apps | "Coming soon" placeholders | `HomeSidebarFragment.java:283-328` |
| Looks in the Library | No Library kind for VisLab looks | `LibraryKind.java` |
| Forensics events screen from the Lab menu | Opens only from the video player and the evidence sheet | `VideoPlayerActivity.java:244` |
| `DecoderBudgetProbeActivity`, `WaveformDebugActivity`, `ApplyEditsActivity` | No in-app caller (developer or adb only) | manifest `:181`, `:184-193` |

## Where STATE.md disagrees with the code (for HYG-5, which owns STATE.md)

- STATE 5.6 says Select supports Copy / Paste. Copy is a stub, Paste does not exist (`JBA:2591`).
- STATE says Fix audio is "not exposed as a button". The button exists (`AE:3631-3632`, `:44667`).
- STATE says Avatar Studio stays out of the UI. It is reachable from three places (lobby Avatar room, Library Avatars chip, Studio sprite picker).
- ROADMAP lists timeline marks as BUILT. They are not on `joy-creator`.
- A comment in `LOB:1085-1092` still says Viz Lab has no room; and the 10-15 lobby audit's Viz Lab "placeholder" row is out of date (`LOB:1254-1256`).
- Earlier lobby-audit items that are now fixed: the Capture hero reads the real camera name (`LOB:1050`), the Sprite / Avatar heroes show a real sheet or character (`LOB:1056-1077`), the room indices are named constants (`LOB:133-138`), Library and Settings are overlays.

## What I did not check

- Nothing was tried on a phone. All "hidden" calls (hold, double-tap, swipe) are from reading the listeners, not from using them.
- Unverified: whether a first-time user with no sheet is offered "New sheet" from the lobby Sprite Lab entry; the keyframe ribbon's visibility rule; the caption keyframe shortcut wiring; Echo Trails on spine clips in export; the FadRec webcam toggle location; the exact mirror-button condition; whether `SlideRenderActivity` being exported is intended (manifest `:192`).
- Branch-only work (animation clips, symmetry, send-to-existing-project, timeline marks) is judged by its absence from `joy-creator`, not by reading those branches.
