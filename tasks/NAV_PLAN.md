# JOY CREATOR NAV PLAN — v1 (2026-09-22)

Owner: JoyRaptor. Status: PROPOSAL, needs 4 decisions at the bottom.
Sources: `APP_MANIFEST.md` (code-true, 4 sweeps today), Five-Doors record §02/§06,
Marquee §§01-04, Studio Final §§01-05, DESIGN_JOY_CREATOR §7, DEPOLITICIZE B2,
POLISH_CHECKLIST A-G, `studio_tokens.xml` + `Studio.java` (46 tokens).
Skills applied: jakubkrehel better-colors (ramps not colors, token-only-in-role,
one color one meaning/15°, one filled action per view), better-ui (concentric
radius, shadows=elevation/borders=structure, press .96, exact motion),
designer-skills (existing product → critique first, research→strategy→UI→system→ops).

Art Order note: `tasks/design/UI-The Art Order.html` on disk is a Claude frame
wrapper (artifact content not embedded) — plan uses POLISH_CHECKLIST §G record-07
summary (5 empty-room heroes, 7 first-run stills, demo project, Joybot claymation
states, 8 room glyphs, grain tile, wordmark/icon; eng done: orb face, Archivo,
contextual row). If the live artifact has more, paste it and this plan absorbs it.

## 0. Non-negotiables (from you)
1. No feature lost. Every FadCam capability survives (incl. storage-ring 3-rows,
   clock, stats, 9 shortcuts, clock widget, motion/forensics engines).
2. Nav-bar removal only if replacement is BETTER in all ways.
3. Rebrand vocabulary is decided: Evidence→Snapshots, Evidence Board→Story Board,
   Forensics→Smart Detection, Briefing→Activity Summary, Threat→Overview,
   SITREP→Session Report, Personnel→People. Use these below.
4. Durable modules: SpriteLab pattern is the template — asset store OUTSIDE the
   editor, dual-entry (room + inside-editor drawer), durable across projects,
   plus LIVE use (leave-on visualizer / decorate recordings). Applies to VizLab,
   Puppet/Avatar rigs, and future modules.
5. Look: fill (Pill) for controls, glass pills for transparent/popover over video,
   room gradients for identity, one filled action per view (better-colors), zinc
   ramp untouched, Archivo/IBM Plex already bundled.

## 1. Settings: modular, single-source (your articulation is correct)
Yes — that IS the hybrid. One fragment per setting area (already true: 15+
`*SettingsFragment`, no PreferenceScreen system), hosted anywhere via
`OverlayNavUtil.show()`. Setup hub shows ALL grouped; Capture shows a
REFERENCE (deep link row) to Video/Audio/DualCam/ScreenRec — same instance,
zero duplication, zero drift. Rule: Setup owns the code, rooms own a link.
This is already the architecture — we formalize it, not rebuild it.

## 2. RECOMMENDED: Lobby-as-Home (Marquee + Floor + Fan + Live strip)
Keep what you rate 80%/60% and finish it rather than replacing it.

- TOP (alive): marquee carousel (wrap-around rooms, heavy white on / thin grey
  off, tap=select tap-active=enter) + hero (real frame at 1s-in, 30-35% share,
  3dp room bar, 2px-blur 260ms swap) + meta `N LAYERS · M:SS · AGO`.
- MIDDLE (recency): mixed recents (projects/recordings/exports/photos/streams,
  100dp cards, corner-cut keeps here) + New row (your 5-point sheared chips) +
  Joybot one line (VERIFIED-only, one action + dismiss).
- FLOOR (geography): 5 fixed words that never move — Finder(Smart Detection),
  Remote, Bits, Packs, Setup. Fixed = thumb memory (Five-Doors §06: start here).
  Upgrade: HOLD-TO-FAN (Deck gesture) — hold Capture fans 7 modes, hold Setup
  fans setting groups, hold Bits fans gadgets. One tap stays, hold goes deeper.
  Beats every concept on depth-per-tap.
- GLOBAL: search (build Records search or kill magnifier — dead `searchView`
  line 4897 today) + house = way back (already true) + persistent LIVE STRIP
  (recording/export/AI/transcribe, hairline + expandable, survives leaving
  editor — the orphan-killer for background work).
- SETUP HUB: grouped single scroll (Capture / Library / Studio / Sound / System /
  About), each row either owns its fragment or links it (see §1). 9 themes prune
  to JoyCreator + 1 dark alt (9 is a test matrix, not a feature).
- DURABLE MODULES as rooms: Studio, Capture, Library, Story Board (reskinned
  forensics gallery + auto-markers), Sound/VizLab (drawer engine + live widget),
  Sprites, Avatar/Puppet, Joybot, Remote, Bits. Viz Lab ships when its store +
  live widget both work — not as an empty room.

Why this wins: nothing moves (Floor-Plan virtue), resume is 1 tap (Bench hero),
depth without a second screen (Deck hold), stranger onboarding via verbs only on
first run (Marquee), no GPU-over-video legibility gamble (Stage rejected for
shell — frosted stays in drawers/popovers only, with your FROST/CLEAR toggle).

## 3. ALTERNATIVE: Persistent mini-dock (if lobby-floor feels thin)
Same lobby, plus a 28-44dp thumb dock pinned above the floor on NON-lobby
screens (Capture/Library/Studio-list/Setup/Story Board): 5 fixed discs +
center Studio action. Mirrors for left hand. Collapses to 28px lip on scroll.
+ Always 1 tap home from anywhere (stronger than back-button today).
− Spends ~1/8 screen on navigation everywhere; risks duplicating the floor;
  needs left-hand mirror work; must prove it doesn't eat the editor (never in
  `FaditorEditorActivity` — editor keeps its own transport/tool row).
Take it only if device tests show floor+back isn't enough. Default: NO dock.

## 4. What goes where (orphan-proofing)
- Capture home (viewfinder + torch + zoom + AF/EV chips + 3-row storage card +
  clock + stats + elapsed hero) — stays, facelift residuals only
  (`#4CAF50` preview stroke, hardcode elapsed hero).
- Records (7 chips + 4 sub-rows + batch + dock) / Projects-A / Photos-via-SHOTS /
  Trash / Player-Viewer — stay, facelift; Projects-B (FadRec dialog) merges into A.
- Remote LAN stack — stays, re-skin imagery (`#000` root + server-room bg).
- Studio list + editor + SpriteLab (done) + Avatar (drive before promoting) +
  Puppet (inside editor) + VizLab (build store+widget, then room).
- Story Board (gallery + auto-markers from motion/noise + rough-cut handoff) —
  reskin in place, park behind Smart-Detection toggle per DESIGN §7.
- Setup 15 — stay as fragments, regroup under hub, deep-link from rooms.
- Bits — Torch + QR stay; 8 coming-soon: CUT or COMMIT (no facelift of stubs);
  clock widget stays (tap → Setup as today).
- Sidebar (What's New/Tips/preview toggles/Discord/mini-apps entry) — folds into
  Setup hub + Joybot line; hamburger stays on Capture until hub proves itself.
- System: 15 icon aliases (Play build strips disguise set per LAUNCH §3),
  Wear suite untouched, headless activities untouched, Pro upsell — decision owed
  (conflicts with "no subscription ever" intro).

## 5. Order of work (merge/rebuild before facelift)
0. Tokens frozen (done: 46). Any new surface uses them or adds a role token.
1. Shell: floor+fan + live strip + search decision + back/house audit.
2. Setup hub regroup + deep-link references (no fragment rewrites).
3. Merges: Projects A+B; Finder = gallery+MotionLab+DF-settings; Bits = rows+
   shortcuts+widget. Rebuilds: VizLab store+widget; Records search (or cut).
4. Facelift passes per surface (Home cards → Records/Trash/Player → Remote →
   Story Board → Setup rows → Bits). One surface per pass, device-verified.
5. Editor deep work LAST (20%→done): drawer contents ×15 to spec (drawer ink
   ramp only over scrim, object-FILL/state-RING, 70% chips, 46dp rows), blues
   decision, 30 one-offs, tool-row count already shipped.
6. Cut list (needs your word): coming-soon 8, Viz placeholder, Pro crown,
   support_url (empty today), update org (points at JoyRaptor/JoyCreator, no
   releases → no offer, correct).

## 6. Decisions needed (4, from last round — still open)
1. Setup hub grouping approved? (Capture-link pattern in §1)
2. Coming-soon 8: cut all, or keep compass/level/sound-meter?
3. Viz Lab: build store+widget now, or drop room until it exists?
4. Records search: build or remove magnifier?
+ New: Pro crown vs "no subscription ever" — keep, reword, or cut?
