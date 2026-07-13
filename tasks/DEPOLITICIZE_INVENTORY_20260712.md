# De-politicize / de-brand — precise inventory + punch-list (2026-07-12)

Executes `DESIGN_JOY_CREATOR.md §4` (remove political/activist identity; KEEP the character MECHANIC +
the real privacy features — only the *messaging/theming* goes). Read-only audit; NO edits made yet.
Presented for a scope go/no-go because this is outward-facing brand/content (hard to reverse).

## Status: PARTLY DONE — no advocacy TEXT remains
- **No "Free Palestine" / flag-slogan / activist TEXT anywhere in the shipping app.** Only occurrence of
  "Palestine" in the repo is the design rule itself (`DESIGN_JOY_CREATOR.md:30`).
- About + Ko-fi footers ALREADY neutralized (in-code comments cite §4): `AboutFragment.java:220-221`,
  `KoFiSupportBottomSheet.java:169-170` → "Built on FadSec Lab foundations", no flag/country line.
- What remains is (A) a small flag-motif + character-copy sweep, and (B) a large brand-naming +
  forensics-module reskin that needs JoyRaptor's decisions.

## A. SMALL + well-bounded — safe to execute once JoyRaptor green-lights the design choice
### A1. The "flag accent" behind the timer (the §4 "black-flag motif")
- Asset `res/drawable/fadseclab_flag.png`; setting is **default ON** ("Show FadSec Lab flag (Default)").
- Strings `strings.xml:194-200` (`home_elapsed_flag_option/_desc/_helper/_show/_hide` + descs) + `:161`
  helper mentions "accent **flag**". Pref path in `HomeFragment.java` (`PREF_HOME_ELAPSED_SHOW_FLAG`,
  ELAPSED_FLAG_* ~137-159, 11821-12154).
- **DECISION NEEDED:** drop the flag entirely, or reskin to a neutral "timer accent" (keep the
  customization mechanic, remove the flag imagery/copy)? The mechanic stays either way.
### A2. Character easter-egg copy (6 lines) — reskin, do NOT remove the mechanic
- `strings.xml:599-606` array, shown via `HomeFragment.java:588`. Lines themed as a snarky security/AI
  guard ("restricted area", "secret button", "trying to hack me?"). Reskin to the friendly
  AI-companion voice §4 anticipates. Self-contained, 6 items.

## B. LARGE — brand naming + spy/forensics identity (needs JoyRaptor's decisions; §7 = reskin+hide, not delete)
### B1. "FadSec Lab / FadSec Cloud / FadSec ID" brand identity (~21 string occurrences + ~40 icon assets)
- User-facing: `fadsec_info:6`, `widget_branding_title:821`, `kofi_footer_text:1673`,
  `home_sidebar_copyright:2500`, `annotation_by_fadsec_lab:3001`, streaming keys `2364-2405`
  ("FadSec Cloud"/"FadSec ID"; `e2e_encryption_setup_desc:2398` already half-migrated to "Joy Creator").
- Code footers hard-code "FadSec Lab" + `github.com/fadsec-lab` (`AboutFragment.java:223-234`,
  `KoFiSupportBottomSheet.java:96,173-183`). Assets: `drawable/fadseclab.png`, `fadseclab_flag.png`,
  full `ic_launcher_fadseclab*` set + `ic_launcher_fadseclab_background.xml`.
- **DECISION NEEDED:** GPL requires *some* upstream attribution (§6) — keep "FadSec Lab" as a single
  neutral org-attribution line, or rename all to "Joy Creator"? "FadSec Cloud/ID" → "Joy Creator Cloud/ID"?
### B2. Digital Forensics / Evidence / Intelligence module (~90 string keys, 132 "forensic/evidence"
  occurrences, `strings.xml:1837-1995` + `3188-3194`; themed layouts/drawables; `java/com/fadcam/forensics/**`)
- A REAL feature (on-device AI-detection timeline) themed as surveillance: "Digital Forensics",
  "Evidence timeline/board", "Intelligence Briefing", "Threat Assessment", "Situation Report",
  "Personnel", "Asset monitoring". Design §7 says **reskin + park behind a toggle, do NOT delete**
  (Evidence board → "Story Board", auto-markers).
- **DECISION NEEDED:** confirm the reskin vocabulary (Evidence→Clips/Snapshots? Intelligence
  Briefing→Activity Summary? Threat Assessment→Overview?) and whether to hide-behind-toggle now vs later.
### B3. Alternate launcher-icon labels (borderline identity) — `strings.xml:1799-1815`
- `$ ~/r00t` (`app_icon_redbinary:1804`), blank stealth label (`app_icon_black_alias_label:1815`),
  country-keyed keys (`app_icon_pakistan:1800`, `app_icon_noor:1802`, `app_icon_bat:1803`).
- **DECISION NEEDED:** are the r00t/stealth/country icon variants in scope as "identity", or acceptable
  as neutral icon variety?
### B4. "Classified Mode" / "REDACTED" (`strings.xml:2493-2494`) — spy-theming of a plain
  hide-thumbnails feature → suggest "Hidden Thumbnails" / "Hidden". (KEEP "Privacy Black Screen"
  `2502-2514` — neutral copy, the real privacy feature.)

## Not political (noted for the SAME sweep, rebrand-completeness): locale files (`values-ar/ps/…`)
still say "FadCam" not "Joy Creator"; `Welcome_to_FadCam*.png`, beta banners, `FadCamPro/*`, QR codes.

## Bottom line
Cat A (flag accent + 6 easter-egg lines) is a ~1-hour sweep once the flag design-choice is picked.
Cat B is a real rename/reskin project gated on 4 JoyRaptor-decisions (B1–B4 above). Nothing here should be
mass-edited without those calls — it's the app's public identity.
