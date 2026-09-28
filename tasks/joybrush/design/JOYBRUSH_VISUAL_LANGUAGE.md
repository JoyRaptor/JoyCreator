# Joy Brush — the visual language it inherits (v1, 2026-09-28)

Purpose: give any agent building a Joy Brush screen everything needed to make it look like it was
always part of Joy Creator — exact values, the component to reuse for each control, the colour
rules, and the traps. Research only; no existing file was changed to write this.

**How claims are marked.** `[R: file]` = read in that file at HEAD `dc56de3`. `[R: rec NN]` = read
in a published design record (see §0). `[I]` = my inference or proposal, not something the repo
says. Where the code and a record disagree, the code is what ships and the disagreement is listed
in §5.

---

## 0. Where the design truth actually lives

| Source | What it is | Status |
|---|---|---|
| `app/src/main/res/values/studio_tokens.xml` | XML token file, "THE PLACE OF TRUTH" | **Authoritative for XML** [R] |
| `app/src/main/java/com/fadcam/ui/faditor/Studio.java` | Java mirror of the tokens (static finals for onDraw) | **Authoritative for code** [R] |
| `ui/faditor/tools/ObjectDrawer.java` → `Kit` | Record 06's drawer component set, the see-through fill, Frost | Authoritative for anything over the picture [R] |
| `ui/faditor/SheetKit.java` | Opaque bottom-sheet parts (header, rows, chips, pill buttons) | Authoritative for opaque sheets [R] |
| `ui/type/Type.java` + `res/font/` | The three typefaces | [R] |
| `ui/motion/Motion.java` | Curves, durations, press | [R] |
| `ui/faditor/layers/ObjectPalette.java` | What an OBJECT is coloured (flat + tape gradient) | [R] |
| `ui/lobby/LobbyFragment.java` | Room (section) gradients, `buildRooms()` | [R] |
| Design records 01–07 | **claude.ai artifacts, not repo files.** Listed with URLs in `tasks/POLISH_CHECKLIST.md` ("The seven records"). I read 01 Swatch Room (`RzCk8dwVURfuezNEcVwjgp`), 04 The Marquee (`HKLVT4iYjZiKYS7vYWSLVA`), 05 The Studio Kit (`DpjtUDLJ7QSTvyHnQCVDST`), 06 Studio Final (`EUSt9oqAwDdmkJepS51bZ5`). | Read via the Artifact tool [R] |
| `tasks/design/*.html` | Only six transform-tool option pages (rotation dial, reframe pill, skew/warp, fade handles). **The `UI-JoyCreator-grand-design.html`, `UI-The-Marquee…`, `UI-The-swatch-room_pill.html` files that INBOX.md cites do not exist in the repo.** | Gap — §5.1 |
| `tools/spritelab/SpriteLab.html`, `SpriteLabMobile.html` | SpriteLab's CSS; the phone's `SpriteIcons.java` is generated from the mobile file | [R] |

---

## 1. Token table

### 1.1 Ground ramp (surfaces) — named by meaning, never by shade

| Token (XML / Java) | Value | Use | Source |
|---|---|---|---|
| `s_ground` / `Studio.GROUND` | `#000000` | True black. The art/timeline ground; pure black is a deliberate art choice so the *user's* colours pop | [R: studio_tokens.xml, Studio.java; INBOX 2026-09-12] |
| `s_surface` / `SURFACE` | `#0D0D10` | Screen behind panels; window background | [R] |
| `s_panel` / `PANEL` | `#111114` | A panel, a sheet (opaque) | [R] |
| — / `SUNK` | `#16161B` | A well cut into a panel (Java only, no XML twin) | [R: Studio.java] |
| `s_raised` / `RAISED` | `#1C1C22` | A control sitting on a panel | [R] |
| `s_pressed` / `PRESSED` | `#26262E` | Held-down control; ripple colour | [R] |
| `s_line` / `LINE` | `#2C2C35` | Divider / hairline | [R] |
| `s_lane_a` / `LANE_A` | `#00000000` | Lane A = the ground showing through | [R] |
| `s_lane_b` / `LANE_B` | `#17171C` | Alternate lane (owner ruling; record 06 says `#0B0B0E`) | [R] |
| `s_film_rail / _edge / _hole` | `#1C1C22 / #33333C / #000000` | Film-strip metaphor (useful for the Animation board's film strip) | [R] |

Record 06: "Panel · Control · Pressed · Line — `#111114 · #1C1C22 · #26262E · #2C2C35`. Sprite Lab's ramp, untouched. Only the ground moved." [R: rec 06 §04]

### 1.2 Ink — TWO ramps, not interchangeable

| Ramp | Tokens | Values | When |
|---|---|---|---|
| **Screen ink** (opaque surfaces) | `s_ink / s_ink_dim / s_ink_faint / s_ink_off`, `s_label` | `#E4E4E7 / #A1A1AA / #71717A / #4B4B55`, label `#C4C4CE` | Everything NOT over the picture. Records 04 and 06 `--ink --dim --dimmer --dimmest` [R: studio_tokens.xml, rec 04, rec 06] |
| **Drawer ink** (over the see-through scrim) | `s_drawer_ink / s_drawer_dim / s_drawer_label` = `Studio.DRAWER_INK / DRAWER_DIM / DRAWER_LABEL` | `#F2F2F5 / #C9C9D3 / #C4C4CE` | ONLY content over the picture (drawers, popovers, transcript). "Nothing outside a drawer may use these." Label measured 4.86:1 over a blown-out frame [R: studio_tokens.xml, Studio.java, rec 06 MAJOR 04] |
| Ink veils | `s_ink_33/40/50/60` | `#55/#66/#80/#99` over `#E4E4E7` | Ordinary surfaces only [R] |
| Ink on a saturated fill | `s_on_go` / `ON_GO` | `#050507` | Text/glyph ON a gradient or an accent fill [R] |

Rule: there is no WHITE token. "Raw white is gone from the editor" — white-alpha fills are `Studio.alpha(DRAWER_INK or INK, a)` [R: POLISH_CHECKLIST, ObjectDrawer.Kit].

### 1.3 STATE colours — global and constant (never a section accent)

| State | Token | Value | Meaning | Form |
|---|---|---|---|---|
| Selected / armed | `s_armed` / `ARMED` | `#22D3EE` cyan | "the one you are pointing at", armed mode, focus ring | **RING** (1.5dp + 3dp halo at 28%; focus 2dp, 0ms) |
| Live | `s_live` / `LIVE` | `#F43F8E` pink | recording, playing, the playhead, "showing now" | ring/line |
| Careful | `s_careful` / `CAREFUL` | `#FBBF24` amber | warning, approximate, unsaved | ring |
| Destroys | `s_danger` / `DANGER` | `#FA3D5D` red | delete / something being lost | **the one state allowed to FILL** |
| Off | `s_off` / `OFF` | `#33333C` | present but unavailable — ONE grey | glyph colour |
| Guide | `s_guide` / `GUIDE` | `#A78BFA` | snap/alignment line (a hint, not a control) | thin line |
| Slice (role) | `s_slice` / `SLICE` | `#F9F462` | "what Split will cut" dashed marker | 1px dash |
| Armed light (ramp step) | `s_armed_light` / `ARMED_LIGHT` | `#55E0F9` | lighter cyan step so a chip reads on a panel | — |

Sources: [R: studio_tokens.xml, Studio.java; owner's Swatch Room message quoted in POLISH_CHECKLIST: Careful `#FBBF24`, Selected `#22D3EE`, Live `#F43F8E`, Destroys `#FA3D5D`]. The fill-vs-ring rule: "Object colour always a FILL · State colour always a RING — cyan selected, pink live, amber careful. Red fills — the one exception." [R: rec 05 §02, rec 06 §04]. INBOX 2026-09-12 hard rule: "STATE colours are global and constant … A section accent must never be a colour that already means a state" [R: INBOX.md; SpriteTheme.java rule 1].

### 1.4 Action vs identity

- **Gradient means ACTION; flat means IDENTITY.** Anything pressable that *does* something wears the aqua→lime gradient `@drawable/studio_action_pill` (`#35F6BF → #97FE8B`, angle 0, radius 999dp) with `ON_GO` ink. **One saturated control per screen.** [R: Studio.java javadoc, studio_action_pill.xml, POLISH §F]
- Disabled primary: `studio_action_pill_off` — flat `s_raised` + 1dp `#2C2C35` stroke; never a faded gradient ("saturation at low alpha … says broken") [R].
- Assistant actions: `joybot_orb_pill` — `#CC27FF → #5C43FD`, angle 315 [R].

### 1.5 Drawer / over-picture values

| Thing | Value | Source |
|---|---|---|
| Drawer fill | `Kit.drawerFill(ctx)` = black at `(100 − seeThrough)%`; **default see-through 50%**, range 20–85, pref `studio_drawer/see_through_pct`; XML twin `s_drawer_scrim #80000000`. **Never opaque.** | [R: ObjectDrawer.Kit; STUDIO_PLAN P1 #7] |
| Frost | same fill + the picture behind blurred: 1/8-size bitmap, two box-blur passes r=2, ~15 fps (66ms), drawn UNDER the fill; toggle `ic_frost_24`, pref `studio_drawer/frost` (default off); needs a `FrostSource` | [R: ObjectDrawer FROST section; STUDIO_PLAN P1 #8] |
| Control fill `--dctl` | `DRAWER_INK @ 0x1A` (≈10%) = `Kit.CTL` | [R] |
| Idle ring `--dring` | `DRAWER_INK @ 0x1F` (≈12%), 1dp = `Kit.RING` | [R] |
| Slider track | `DRAWER_INK @ 0x2E` (18%) = `Kit.TRACK` | [R] |
| Grab pill | `DRAWER_INK @ 0x4D` (30%) = `Kit.GRAB`, 38 × 3.5dp | [R] |
| Top edge (popover) | `DRAWER_INK @ 0x1A`, 1dp = `TextOverlayDrawer.Kit.EDGE` | [R] |
| ON control | the object's accent at **70% (`0xB3`)** = `Kit.onFill(accent)`; ink chosen by contrast `Kit.inkOn(accent)` → `ON_GO` or `DRAWER_INK` | [R: ObjectDrawer.Kit; rec 06 "Control ON object colour at .70"] |
| Max height | 55% of screen (75% audio-only; absolute 82%); min body 96dp; record min 12+38+32+46 = 128dp | [R] |

### 1.6 Other veils

`s_line_20 #332C2C35`, `s_line_80 #CC2C2C35`, `s_lane_b_88 #E017171C`, `s_scrim_20/60/80 = black 20/60/80%` [R: studio_tokens.xml]. In Java: `Studio.alpha(TOKEN, a)` — never re-type an 8-digit hex [R].

### 1.7 Typography

| Face | File | Role | Weights | Source |
|---|---|---|---|---|
| **Archivo** (variable, wght 100–900) | `res/font/archivo_variable.ttf` via `joy_display.xml` | Display: titles, sheet headers, lobby marquee, wordmark | `Type.THIN…BLACK`; usable floor EXTRA_LIGHT 200 when moving | [R: Type.java, joy_display.xml] |
| **IBM Plex Sans** (variable) | `plex_sans_variable.ttf` via `joy_body.xml` | Anything meant to be read; drawer labels/chips | 400–700 | [R] |
| **IBM Plex Mono** | `plex_mono_regular/medium/semibold.ttf` | Timecodes, counts, section labels, values | 400/500/600 | [R] |
| Material Icons font | `materialicons.ttf` | Ligature glyphs in SheetKit and the lobby | — | [R: SheetKit.icons()] |

Call `Type.display(view, Type.EXTRA)` etc. — never `setTypeface(…, BOLD)` (fake bold is disabled on purpose) [R: Type.java].

| Text role | Size / weight / face | Source |
|---|---|---|
| Sheet title | 14.5sp, 800, Archivo, `INK` | [R: SheetKit.TITLE_SP; rec 06 `.sheet .sh b`] |
| Sheet count | 8.5sp mono, `INK_DIM` | [R] |
| Section label (sheet `.tg`) | 8sp mono 500, letter-spacing .14em, UPPERCASE, `INK_DIM`, optional 5dp dot | [R: SheetKit.sectionLabel] |
| Section label (drawer `.dsec`) | 8sp mono 400, .14em, UPPERCASE, `DRAWER_LABEL`, pad 7/3 | [R: ObjectDrawer.Kit.sectionLabel] |
| Drawer header name `.nm` | 12sp, Plex 700, `DRAWER_INK`, one line, max 120dp, ellipsis | [R] |
| Drawer tab label | 11sp, 700 active / 600 idle | [R] |
| Drawer chip | 11sp, 600 off / 700 on | [R] |
| Drawer row label | 11sp `DRAWER_LABEL`, fixed width, one line | [R] |
| Drawer value | 11sp `DRAWER_INK`, `tnum`, right-aligned fixed width (Plex Mono 600 12sp is the record's; `TextOverlayDrawer.Kit.valueText()` does the mono version) | [R] |
| Sheet row | 14sp; icon 18sp; description 12sp `INK_FAINT`, line-spacing 1.25 | [R: SheetKit] |
| Sheet chip | 12sp | [R] |
| Pill button | 13sp; primary 700 on gradient, secondary 600 on `RAISED` | [R] |
| Lobby live room word | 34sp Archivo 900 caps (owner: "make the word studio larger"); others scaled/lighter | [R: POLISH_CHECKLIST, LobbyFragment] |
| Record type scale (records 04/06) | h1 900 −.045em; h2 800; kicker/labels mono 11/8px | [R: rec 04, rec 06] |

### 1.8 Shape — radii, strokes, sizes

| Thing | Value | Source |
|---|---|---|
| Pill / chip / button | 999dp (fully round) | [R: Kit.pill, SheetKit.pillBackground, rec 01 "language A — PILL" chosen] |
| Drawer (hangs from top) | bottom corners only, 18dp | [R: ObjectDrawer ctor] |
| Bottom sheet | top corners 20dp, `PANEL`, flat | [R: SheetKit] |
| Sheet row | 8dp (= 20 − 12 inset, concentric: outer = inner + gap) | [R] |
| Drawer tab face | 7dp, 32×24 inset in a 32dp-tall view | [R] |
| Square icon control (segmented set) | 8dp | [R: Kit.setIconOn] |
| Checkbox | 18dp box, radius 5, ring 1.5dp | [R: TextOverlayDrawer.Kit.checkbox] |
| Card (recents) | 12dp, 1px white-8% ring via outline, offset −1px | [R: rec 04; POLISH] |
| SpriteTheme / SpriteLab CSS | card 14, chip 8 (`--r:14px --r2:11px --pill:999px`) | [R] |
| Toast (record only) | 11dp | [R: rec 05] |
| Hairline ring | 1dp | [R] |
| Focus ring | 2dp `ARMED`, instant | [R: SheetKit.focusable] |
| Selection | 1.5dp cyan + 3dp cyan at 28% (`0x47`) | [R: rec 06; LayerRowRenderer] |
| Playhead | 1.5dp | [R: EditorTimelineView.PLAYHEAD_WIDTH_DP] |
| Slider | 4dp track, 15dp thumb in `DRAWER_INK` | [R: Kit.styleSlider] |
| Icons | stroked 24-box, 1.6 (records) / 1.7 (SpriteIcons), round caps & joins | [R: rec 04/05/06 `svg.ic`; SpriteIcons.java] |
| Grab | 38 × 3.5dp | [R] |

### 1.9 Touch and spacing

| Thing | Value | Source |
|---|---|---|
| Floor | nothing below 28dp; almost everything 40 or 44 | [R: rec 06 §04] |
| Visual ≠ touch size | chip looks 29dp, view ≥ 40; tab looks 24, target 32; checkbox 18 box / 44 row; diamond 13/28 | [R: Kit.chip, rec 05 §04] |
| Drawer header | 38dp; tab strip 32dp; row ≥ 46dp; grip strip 16dp | [R: ObjectDrawer] |
| Drawer paddings | header 10/0/2/0; row side 11; chip gap 6; tab gap 3 | [R] |
| Sheet row | min 46dp, pad 14/8/12/8, inset 12dp, 2dp vertical gap | [R: SheetKit] |
| Transport | 44dp, play 52dp, time labels 46dp | [R: rec 06; POLISH] |
| Tool cell | 56 × 46dp, label capped 52dp, ellipsis, never two lines | [R] |
| Gutter | lobby 17dp; records 20px | [R: rec 04; POLISH] |
| TouchDelegate helper | `com.fadcam.ui.TouchDelegates` (composite; a View holds only one delegate) | [R] |

### 1.10 Motion

| | Value | Source |
|---|---|---|
| Curves | ease-out `(0.23,1,0.32,1)`, ease-in-out `(0.77,0,0.175,1)`, drawer `(0.32,0.72,0,1)`; **never ease-in** | [R: Motion.java, rec 04 §04] |
| Durations | press 140 (scale .97), tip 170, tool swap 170 (cross-fade, no travel), menu/popover 220 (from scale .95, never 0), hero 260 (+2px blur), sheet/drawer 320, once 560, stagger 40 | [R] |
| 100×-a-day controls | **0ms** — undo, play, split, keying, playhead, tab pills. For Joy Brush: brush pick, colour pick, undo, frame step [I] | [R: rec 04; Kit.pressable javadoc] |
| Reduced motion | keep opacity/colour, drop travel/scale (`Motion.reduced`) | [R] |
| Drawer slide in code | 240ms (`ObjectDrawer.SLIDE_MS`) | [R] |

---

## 2. Component inventory — reuse, don't copy

Legend: **REUSE** = public and callable from a new package today. **TRAPPED** = private, or bound to
editor model types, or inside a giant file; needs extracting before Joy Brush can use it.

| Component | Anatomy | Lives in | Status |
|---|---|---|---|
| **Drawer (over the picture)** | `.dh` 38dp: 7dp object-colour dot · name 12sp/700 · tab strip (scrolls, 14dp fade) · header toggles · ✕ (40×38 target) → body (scrolls, peek rows) → `.dgrab` 38×3.5 pill (drag = resize, drag-up/tap = dismiss). Tabs slide L/R; tapping the open tab folds to the header. Bottom-rounded 18dp, see-through fill, Frost toggle auto-added when a `FrostSource` is set. `setAccent(objectColour)` tints dot, active tab, chips, sliders, checkboxes. | `ui/faditor/tools/ObjectDrawer.java` (1,749 lines) — `show(tabs, toggles, …)`, `Tab`, `Toggle`, `FrostSource` | **REUSE** (public class, generic `Tab`/`Toggle`, "knows nothing about compositing"). Caveat: accent is a **static** `sAccent` (assumes one drawer on screen); lives in the editor package. |
| **Frosted panel** | fill = `Kit.drawerFill` (+ blur when Frost on); 1dp `EDGE` top/around | `ObjectDrawer` (drawer) · `TextOverlayDrawer.Kit.surface(ctx, radiusDp)` (popover) · `Kit.followDrawerFill(view)` (any GradientDrawable-backed view repaints live when the slider moves) | **REUSE**. Blur is only inside ObjectDrawer (private); a standalone frosted popover has tint only. |
| **Drawer controls (`Kit`)** | chip `.dchip` (11sp, pad 12/13, 40dp view, 29dp inset pill; on = accent 70%); `setChipOn`, `setIconOn` (8dp square), `sectionLabel`, `row` (≥46dp), `rowLabel`, `value` (tnum), `note` (10sp), `styleSlider`, `stepper` ‹ › (30×28), `styleCheck`, `pressable`, `describe`, `pill`, `background` (keeps padding under InsetDrawable) | `ObjectDrawer.Kit` (static nested) | **REUSE**. `TextOverlayDrawer.Kit` adds `chip` with LP, `setPrimaryAction`, `valueText` (Plex Mono 12/600), `surface`, `popIn`, `checkbox`, `easeOut()`, `drawerCurve()` — lives in a misnamed file its own javadoc says should become `DrawerKit.java`. |
| **Opaque sheet** | `install(dialog)` paints `PANEL` + 20dp top corners; `grab`, `header(title, count)` + `addTrailing`, `closeButton`, `iconButton` (ligature, 40dp), `subtitle`, `sectionLabel(text, dot)`, `divider`, `row` (selected = `ARMED` ink + check), `detailRow` (title + description + trailing switch), `chip` (selected = `ARMED` wash 0x29 + `ARMED` ink), `pillButton(primary)`, `rowBackground`/`cardBackground`/`pillBackground` with PRESSED ripple + 2dp ARMED focus, `label`, `press`, `fitNavBar` | `ui/faditor/SheetKit.java` (688) | **REUSE**. Worked example: `SnapSettingsSheet.java` (header + close + detailRow with MaterialSwitch + chip rows). |
| **Pill button** | primary: `studio_action_pill` + `ON_GO` 13sp/700, min 36dp, pad 16/8; secondary: `RAISED` pill, `INK` 600 | `SheetKit.pillButton`; `TextOverlayDrawer.Kit.setPrimaryAction` | **REUSE** |
| **Round icon button** | drawer header toggle `.dkf i`: 28dp circle on `CTL` + 1dp `RING`, 16dp glyph, view 32×38 | `ObjectDrawer.iconButton` | **TRAPPED** (private). Header toggles are reachable through `ObjectDrawer.Toggle`. Sheet version `SheetKit.iconButton` is public but borderless and ligature-based. Canvas-edge buttons (record 06: 30dp circle, black 50% + blur 8) — no shared component found. |
| **Chip** | see Kit / SheetKit rows above. Two languages: **drawer** = accent FILL at 70%; **opaque sheet** = cyan wash + cyan ink | as above | REUSE (pick by surface) |
| **Slider row** | `[16dp icon][44dp title][FineSeekBar][46dp tap-to-type value, underlined, tooltip][◇ KeyframeDiamondControl or 64dp spacer]`, row pad 2dp; Rotate becomes a `RotationDialView`; Scale has soft detents | `PipDrawerTabs.propRow` (private static) + public `rowIcon(ctx, key)` | **TRAPPED**: `propRow` is private and typed to `ObjectMenuSheet.Prop` + `PipDrawerTabs.Host`. Parts are public: `FineSeekBar` (drag below the bar = 0.2× fine mode), `RotationDialView`, `rowIcon`, `Kit.styleSlider/rowLabel/value`. STUDIO_PLAN P1 #9 still lists 6 panels to adopt it. |
| **Keyframe diamond** | 20dp diamond + ‹ › chevrons; hollow off-key, filled on-key, refused = `DANGER` | `ui/faditor/KeyframeDiamondControl.java` | **TRAPPED** (binds `ObjectMenuSheet.Prop`). Needs a small property interface to serve Joy Brush's animation board. |
| **Scrubbable number** | pill on `SURFACE` + `LINE` ring, pad 9/4, min 26dp; `[13dp icon][key 9.5sp DIMMER][value 11.5sp bold INK]`; 9dp of drag = one step; tap = type; never shrinks (reserves widest); whole drag = one undo | `SpriteSheetEditorActivity.NumPill` (private inner class, file 4,823 lines); record spec `.scrub`/`.dscrub`: key 9.5sp, value Plex Mono 12/600 tnum, dots at both ends, typing = 1.5dp cyan ring | **TRAPPED**. Also hardcodes "Value" / "Set" / "Cancel". Highest-value extraction for Joy Brush (brush size, opacity, grid px, fps). |
| **Segmented control / tab strip** | drawer tab: idle 32×24 icon-only on `CTL`, active widens to icon+label on accent 70%; SpriteLab nav: pill track `RAISED` + `LINE` ring, 2dp pad, active = section accent fill | `ObjectDrawer.paintTabs/buildTabRow` (private, via `Tab`); SpriteLab top-bar nav inside `SpriteSheetEditorActivity` | Drawer tabs REUSE via `ObjectDrawer`; standalone segmented control TRAPPED. |
| **Header (screen top bar)** | Studio: 44dp — ✕, pin, centred title (Archivo 13/800 + mono 7.5 meta), Joybot orb 32dp (`#CC27FF→#5C43FD` 140°), Export pill (gradient). SpriteLab: 42dp — back · name · undo · redo · segmented nav · save (pink while dirty). Lobby chrome 40dp. | Inside `FaditorEditorActivity` / `SpriteSheetEditorActivity` / `LobbyFragment` | **TRAPPED** in each screen. `JoybotView` (`ui/lobby/`) is a reusable view. Export hairline (2dp aqua→lime) likewise per-screen. |
| **Toast** | Record 05: radius 11, pad 9/12, 11.5sp, fill + ring, glyph in object colour, trailing action in cyan ("Undo") | **None.** 451 raw `Toast.makeText` in `ui/faditor` | **GAP** — build once, shared. |
| **Hover label (tooltip)** | Standing rule: every tappable thing gets a hover label = TalkBack name = stylus/mouse tooltip. Never on a view whose long-press is its own gesture (compat tooltip steals long-click below API 26). | `ObjectDrawer.Kit.describe(v, name)`, `SheetKit.label(v, what)` (same thing) | **REUSE** — mandatory for Joy Brush (stylus-first). |
| **Press feedback** | 140ms to .97 via StateListAnimator (never swallows click) | `Kit.pressable`, `SheetKit.press`, `Motion.press` | REUSE (three copies of one idea) |
| **Sheared gradient chip** | parallelogram, 34dp, 10dp shear, CornerPathEffect, horizontal room gradient, pure-black punch-out glyph | `ui/lobby/SlantDrawable.java` (public) | REUSE for a lobby "New drawing" chip |
| **Onion / sprite grid / film chip** | SpriteLab grid cell states, onion past/future pills, clip chip | `SpriteGridEditorView` (view), rest inside `SpriteSheetEditorActivity` | Grid view reusable; controls TRAPPED |
| **Icons** | Generated from HTML `<symbol>`s at stroke 1.7 | `sprite/SpriteIcons.java` via `tools/spritelab/genicons.py` | **REUSE the pipeline** — a Joy Brush icon set should be authored the same way |

**Extraction list for Joy Brush (priority order) [I]:** (1) tokens out of the editor package
(`Studio`, `Type`, `Motion` are fine; `Studio` sits in `com.fadcam.ui.faditor`); (2) `NumPill` →
a shared `ScrubNumber` view; (3) `propRow` → a `SliderRow` taking a tiny `Property` interface
(value/min/max/format/write/keyframeable) that `ObjectMenuSheet.Prop` and Joy Brush both
implement, and `KeyframeDiamondControl` onto the same interface; (4) `TextOverlayDrawer.Kit` →
`DrawerKit.java` as its own javadoc asks; (5) a shared `Toast`/HUD per record 05; (6) the round
header icon button; (7) make the drawer accent per-instance, not static, if Joy Brush can show
two drawers (e.g. a tablet popover beside a phone drawer).

---

## 3. Section colours and state colours actually in use

### 3.1 The wheel (brand palette, DESIGN_JOY_CREATOR §2, record 01 §02) [R]

`#FF008C` neon pink · `#CC27FF` vivid purple · `#8C3DFA` purple · `#5C43FD` indigo · `#4397FD` bright blue · `#55E0F9` cyan · `#35F6BF` aqua · `#97FE8B` lime · `#CEFF5B` yellow-green · `#F9F462` golden yellow · `#FFC341` amber · `#FAA03D` orange · `#FC6818` deep orange · `#FA3D5D` red-pink — in wheel order; **any two ADJACENT colours gradient cleanly**, non-adjacent pairs go muddy through the middle [R: rec 01 §03, ObjectPalette javadoc].

### 3.2 Rooms (sections) — owner's final mapping 2026-09-17

| Room | Gradient A → B | Ink on it | Where it renders | Source |
|---|---|---|---|---|
| **Studio** (editor) | `#35F6BF → #97FE8B` aqua→lime | `ON_GO` dark | marquee, hero bar/action, recents cut, New "Project" chip, export hairline; equals GO by value but a separate role | [R: LobbyFragment.buildRooms, rec 04 `--studio-a/b`] |
| **Capture** (recorder) | `#FA3D5D → #FF008C` red-pink→neon pink | white | marquee, New "Recording" chip; **also the playhead gradient** | [R] |
| **Sprite Lab** | `#FF008C → #CC27FF` neon pink→vivid purple | white | marquee (record calls it `--shop-a/b`), sprite tapes, sprite drawer accent `#FF008C` | [R: buildRooms, ObjectPalette.G_SPRITE] |
| **Avatar Studio** | `#CC27FF → #8C3DFA` vivid purple→purple | white | marquee, New "Character" chip, RIGGED image tapes/drawer accent | [R] |
| **Viz Lab / Sound** | `#FAA03D → #FC6818` orange→deep orange | dark | marquee, recents cut | [R] |
| **Library** | `#4397FD → #55E0F9` bright blue→cyan | — | recents cut, New "Import" chip (`ARMED_LIGHT → ARMED`) | [R: LobbyFragment G_LIBRARY, buildNewRow] |
| **Joybot / AI** | `#CC27FF → #5C43FD` vivid purple→indigo (skip-one pair) | white glyph | orb (lobby, editor top bar, chat), `joybot_orb_pill` | [R: rec 04 `--bot-a/b`, joybot_orb_pill.xml, Studio.ORB_DEEP] |
| **Finder** | `#F9F462 → #FFC341` golden→amber | — | **nowhere** — floor words are grey; no token exists on purpose | [R: rec 04; POLISH] |
| **Remote** | `#5C43FD → #4397FD` indigo→bright blue | — | **nowhere** — floor word, grey | [R: rec 04] |
| Setup / Bits / Packs | — | — | grey floor words | [R: rec 04] |

(Record 01's earlier proposal — Capture pink→violet, Library indigo→blue, "Character" violet→purple, Remote cyan→aqua, Settings grey→cyan, Gadgets grey — was superseded by the table above.) [R: rec 01 §06 vs rec 04]

### 3.3 Object identity (what a thing IS) — `ObjectPalette`

| Kind | Flat (drawer accent, tool glyph) | Tape gradient | Source |
|---|---|---|---|
| Text / sticker | `#8C3DFA` purple | `#8C3DFA → #5C43FD` | [R] |
| Video / master | `#35F6BF` (= Studio room) | `#35F6BF → #97FE8B` ("because it's video") | [R] |
| Image | `#26A69A` teal | `#4397FD → #55E0F9` | [R] |
| Image **with puppet pins** | `#CC27FF` | `#CC27FF → #8C3DFA` (Avatar) | [R] |
| Sprite | `#FF008C` | `#FF008C → #CC27FF` (Sprite Lab) | [R] |
| Audio | `#4ADE80` | `#CEFF5B → #F9F462` | [R] |
| Caption | `#FFC107` | `#FAA03D → #FC6818` | [R] |
| Visualizer | `#EC407A` | `#FA3D5D → #FF008C` | [R] |
| Adjustment | `#8A8A94` | `#52525B → #33333C` (hueless on purpose) | [R] |

Every tape also carries its **type glyph at the left cap** (12dp) so no object is identified by colour alone (record 06 MAJOR 03, deuteranopia) [R].

### 3.4 SpriteLab's own colours (inside the lab)

SpriteLab is neutral ground + categorical accents (Tailwind-400 tier): amber `#FBBF24` grid/slice, cyan `#22D3EE` align/play, violet `#A78BFA` clips/cell identity, green `#35F6BF` (CSS `#34D399`) export, blue `#60A5FA` onion/viewing, pink `#F43F8E` sequence; STATE: cyan = cell used / selected, pink = showing now (2.5dp ring), cyan order badge (pink when now); save button pink while dirty; onion past = pink, future = cyan (tap to recolour) [R: SpriteTheme.java, SpriteLabMobile.html, SpriteSheetEditorActivity]. Its **identity to the rest of the app** is the room gradient `#FF008C → #CC27FF` [R: LobbyFragment, ObjectPalette].

### 3.5 Avatar Studio's look

`AvatarStudioActivity` has **no colour identity of its own**: `SURFACE` root, flat square chips (`setBackgroundColor(RAISED / FILM_EDGE)`, no rounding), hint line in `GO`, a few `Color.WHITE` literals [R]. Its identity to the app is the Avatar room gradient `#CC27FF → #8C3DFA` (lobby, rigged tapes); `ROOM_AVATAR` also marks missing-art placeholders in `PuppetPreviewView`/`SpriteOverlayView` [R]. Pins: `PuppetPalette` — PIN amber `#FBBF24`, STIFF pink `#F43F8E`, DANGLE cyan `#22D3EE`, FREE guide violet, BONE blue `#5AA9FF`, LOCKED `#5A616B`, mesh = INK 18% [R]. STATE.md recommends Avatar Studio be present but not reachable from the UI at launch [R].

### 3.6 Canvas transform and selection (what a selected object looks like)

Selection box = `ARMED` cyan (`HandleModel.COLOR_SELECTION`) over a dark halo; handles white squares (radius 2) with 2dp cyan ring, rotate knob 22dp cyan disc with dark glyph; handle ROLE colours: scale/rotate amber `CAREFUL`, tilt `#6EE7A8`, free `#FF3D7F`, bend `#5AA9FF`, guide `GUIDE` [R: HandleModel.java, rec 06 `.hdl/.selbox/.rotd`, POLISH "Canvas selection box cyan over a dark halo; handle role colours untouched"]. When a text box is selected the drawer accent is `ObjectPalette.TEXT` purple: dot, active tab, ON chips (B/I/U toggles, mixed state = half purple fade), sliders at 70% [R: FaditorEditorActivity `styleToggleState`, `drawer.setAccent(ObjectPalette.forOverlay(o))`].

---

## 4. Joy Brush — proposal [I throughout, owner rules]

### 4.1 The constraint

The wheel is full. Every adjacent pair is already a room, an object tape, or touches a state
colour. The four adjacent pairs still unused all collide:

| Free pair | Why not |
|---|---|
| cyan → aqua `#55E0F9→#35F6BF` | starts on the SELECTED ramp, ends on GO |
| lime → yellow-green `#97FE8B→#CEFF5B` | shares Studio's end stop; the owner already moved Finder off this exact neighbourhood because "Studio, Finder and Remote would have read as one family side by side" [R: LobbyFragment javadoc] |
| amber → orange `#FFC341→#FAA03D` | amber is 2° from CAREFUL; orange is Viz Lab's first stop |
| deep orange → red-pink `#FC4D18→#FA3D5D` | ends on DANGER (and Capture's start) |

### 4.2 Recommendation: **Joy Brush = "Ink" — indigo → bright blue `#5C43FD → #4397FD`** (white ink on it)

- **State-safe.** 25° from SELECTED cyan (ARMED_LIGHT would be the risk; this stops at `#4397FD`), nowhere near LIVE/CAREFUL/DANGER. Only GUIDE violet sits within 15° of indigo, and GUIDE is a 1px snap line — the same form-not-hue exemption Studio.java already grants GUIDE vs AVATAR_DEEP.
- **Renders nowhere today.** It is Remote's reserved pair in record 04, but Remote is a grey floor word with no token, so taking it moves zero pixels. The owner must re-home Remote if it ever gets colour.
- **Reads as a place on the marquee.** Placed after Avatar, the marquee runs around the wheel: Studio (green) · Capture (red-pink) · Sprite Lab (pink→violet) · Avatar (violet→purple) · **Joy Brush (indigo→blue)** · Viz Lab (orange). Nothing adjacent on screen shares a family.
- **Paint metaphor.** Ultramarine to cobalt: ink, the one colour every drawing app starts with.
- **Known closeness, all acceptable by the house rules:** shares indigo with Joybot's disc (`ORB_DEEP`) and the Text tape's far stop, and shares `#4397FD` with the Library/Image start. Hand-over at a shared stop is the wheel's own pattern (Sprite→Avatar hand over at `#CC27FF`), and glyphs tell them apart.
- New tokens, separate roles even where values coincide (the `ROOM_SPRITE_END == ROOM_AVATAR` precedent): `s_room_brush #FF5C43FD`, `s_room_brush_end #FF4397FD` in `studio_tokens.xml` + `Studio.ROOM_BRUSH / ROOM_BRUSH_END`; one `buildRooms()` entry (white ink, glyph e.g. `brush`).
- **Runner-up:** lime → yellow-green, only if the owner prefers warmth and is willing to overrule his own Finder reasoning. **Rejected:** a full-spectrum "rainbow" identity for a painting app — it crosses every state colour and goes muddy.

Primary actions *inside* Joy Brush stay on the house GO gradient (`studio_action_pill`) — "gradient means action" is app-wide; the Joy Brush gradient is identity (lobby, hero bar, recents cut, header hairline, the Canvas board's tab), not buttons.

### 4.3 Boards wear the colour of the app they connect to

A board's identity must be a **FILL** (a label tab / corner cut in the gradient with its type
glyph punched out in `ON_GO` or white), never a coloured frame, because a coloured RING already
means a state (cyan = this board is selected, pink = playing). The board's edge stays neutral
(`LINE` / white 10%).

| Board | Wears | Stops | Why |
|---|---|---|---|
| **Canvas** | Joy Brush | `#5C43FD → #4397FD` | Native to Joy Brush; exports a picture |
| **Animation** | Studio | `#35F6BF → #97FE8B` | "Send to Studio" as video — and video wears the Studio room (ObjectPalette.VIDEO, owner 2026-09-18). Film strip uses `FILM_RAIL/EDGE/HOLE`; current frame and playhead = LIVE; onion follows SpriteLab (past pink, future cyan, tap to recolour) for consistency (see §5.9) |
| **Sprite** | Sprite Lab | `#FF008C → #CC27FF` | Exports to SpriteLab. Inside the board, cell states must match `SpriteGridEditorView` exactly: cyan outline = used, 2.5dp pink = showing now, cyan order badge top-centre |
| **Puppet** | Avatar Studio | `#CC27FF → #8C3DFA` | Exports `.avatar`; matches the RIGGED tape. Pin colours from `PuppetPalette` unchanged |
| **Character** | Avatar (as the lobby's "Character" New chip does) | `#CC27FF → #8C3DFA` + a distinct glyph | The lobby already sends "Character" to this gradient [R: buildNewRow]. Alternative for the owner: a three-stop Sprite→Avatar ramp `#FF008C → #CC27FF → #8C3DFA`, since a character wires sprite boards and puppet boards — but that breaks the two-colour rule |

### 4.4 Selected objects inside Joy Brush — match the editor exactly

| Joy Brush thing | Selection on canvas | Drawer accent (dot, active tab, ON chips, slider fill at 70%) |
|---|---|---|
| Text box | cyan box + white/cyan handles (HandleModel) | `ObjectPalette.TEXT #8C3DFA` — identical to the editor's text drawer and the purple→indigo text tape |
| Placed / reference image | cyan | `ObjectPalette.IMAGE #26A69A` (→ `#CC27FF` once it has pins) |
| Sprite (from a sprite board) | cyan | `#FF008C` |
| A board itself | cyan ring + 28% halo | that board's first stop (table 4.3) |
| Raster pixel selection / lasso | cyan marching line, never the room colour | — |
| Brush / layer drawers | — | the room colour of the board being painted on [I] |
| Anything destructive (clear layer, delete frame) | — | DANGER fill, the only filled state |

Drawers use `ObjectDrawer` + `Kit` (see-through by the same `studio_drawer` preference — record 06: "one preference governs"), header `.dh` with the accent dot, rows and slider rows built from the Kit, every control named with `Kit.describe`.

---

## 5. Contradictions and gaps

1. **Design records are not in the repo.** `tasks/design/` holds only six transform-tool pages. INBOX.md cites `UI-JoyCreator-grand-design.html`, `UI-The-Marquee…`, `UI-The-swatch-room_pill.html` — absent. POLISH_CHECKLIST says the mockups live in a *session scratchpad* (`design/marquee.html`, `studio-final.html`, `catalog.html`, `kit.html`). The only durable copies are the seven claude.ai artifacts. **"Grand Design"** (cited in Studio.java and ObjectPalette as the source of `rSprA`, `rFinA`, room `:root`) is not among the seven listed records and was not found anywhere. [R] → Save copies into `tasks/design/` [I].
2. **The mirror check does not exist.** Studio.java says `tools/check_palette.py` fails if XML and Java drift; there is no such file. [R]
3. **Four drawer opacities.** Live: `Kit.drawerFill` 50% default. Dead constants still say 64% (`ObjectDrawer.SCRIM`, `TextOverlayDrawer.Kit.SCRIM = 0xA3`). `SpriteTheme.DRAWER_SCRIM = 0xF2111114` (95%, grey) is still used by `SpritePalettePanel` and `DopeSheetView` — against the owner's "never opaque" ruling. STATE.md §7.5 still says "Top drawers at 64%". The 4.86:1 / 6.63:1 contrast figures were measured at 64%; **nothing has re-measured drawer ink at the new 50% default.** [R]
4. **Record 05's three surfaces are not built.** Frost uses light control fills (white ~11%); CLEAR should *invert* to dark fills (`rgba(4,4,7,.55)` + white 16% ring). `Kit.CTL` is white 10% whatever the surface. The record's FROST/CLEAR lens pill became an icon toggle (owner asked for compact). [R]
5. **SpriteTheme predates the tokens.** INK `#F4F4F5`, DIM `#8A8A94`, DIMMER `#52525B` (values both records rejected), PANEL `#0D0D10` (= Studio SURFACE), CONTROL `#1F1F26` (not RAISED); off-wheel Tailwind accents (`#60A5FA`, `#A78BFA`, `#34D399`); `resolve()` is never called. SpriteLab HTML uses **Outfit** + system UI, not Archivo/Plex. [R]
6. **State colours reused as identity** despite the hard rule: PuppetPalette STIFF = LIVE pink, DANGLE = SELECTED cyan; onion past/future = LIVE/SELECTED; SpriteTheme ACCENT_SEQ = pink, ACCENT_ALIGN = cyan; Library room ends on `ARMED_LIGHT` (1.2° from SELECTED, rationalised in Studio.java). The playhead ("live") ships as the Capture gradient, which starts at DANGER. [R]
7. **Two "on" languages.** Drawer: object accent FILL at 70%. Opaque sheet (`SheetKit.setChipSelected`, `paintRow`): cyan wash + cyan ink — a state colour as a fill-ish. Joy Brush must choose by surface, not by taste. [R]
8. **Keyframe diamond colour.** Records 05/06 draw a set key AMBER (`--warn`) and a recorded take PINK; `KeyframeDiamondControl` uses GO aqua at 20dp (record: 13dp in a 28dp target). [R]
9. **Records' object colours are stale**: sprite `#FFB74D`, audio `#35F6BF`, video `#4397FD` in records 05/06 vs shipped `#FF008C`, `#4ADE80`, `#35F6BF` (owner rulings). ObjectDrawer's javadoc still says "a sprite's drawer is amber". [R]
10. **Four icon systems.** Records: 1.6-stroke SVG line icons. `SpriteIcons`: generated, 1.7 stroke. `res/drawable/ic_*`: 170 files, ~136 filled Material-style. SheetKit/lobby: Material Icons *font* ligatures. [R] → Joy Brush should author stroked icons in HTML and generate them like SpriteIcons [I].
11. **No toast component** (record 05 specifies one); 451 raw `Toast.makeText` in `ui/faditor`. [R]
12. **The editor is not on the theme system.** `Theme.FadCam.JoyCreator` maps the legacy attrs onto `s_*` tokens (so legacy screens follow), but custom views read `Studio.*` static finals — correct for onDraw speed, yet it means **no runtime theming and no light mode**. The seven legacy palettes (`colors_amoled … snowveil`) still exist. INBOX's "1,092 literals" is superseded by POLISH's "373 → 28". [R]
13. **Value font deferred.** `Kit.value` uses the system face with `tnum` (record: Plex Mono 600); `NumPill` uses `Typeface.DEFAULT_BOLD` and SpriteTheme ink. [R]
14. **Tokens live inside the editor package** (`com.fadcam.ui.faditor.Studio`), and the Joy Brush blueprint puts the app in its own module (`joypaint-app`) with an Android-free core. The app module can import `Studio`, but a shared `ui/theme` home would stop Joy Brush depending on the editor package. [R: blueprint §3.1; I]
15. **Literals outside the token files:** `joybot_orb_pill.xml` (`#CC27FF/#5C43FD`), `studio_action_pill_off.xml` stroke `#FF2C2C35`, HandleModel `#6EE7A8 / #FF3D7F / #5AA9FF`, PuppetPalette `#5A616B`, `AvatarStudioActivity` `Color.WHITE`. [R]
16. **Hardcoded strings** in `NumPill`'s dialog ("Value", "Set", "Cancel"). [R]
17. **Record vs build departures, owner-approved, so not defects:** lane B `#17171C` (record `#0B0B0E`); playhead Capture gradient (record: LIVE); New row = sheared gradient chips (record 04: grey buttons with coloured glyphs — the record's own anti-slop fix); marquee word 34sp caps (record 29px). [R]
18. **Naming.** OWNER_CONSTRAINTS and the blueprint say "Joy Paint"; the latest commit and folder say "Joy Brush". Tokens proposed here say `brush`. [R]
