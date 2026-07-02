# Joy Creator — Brand & Design Brief (2026-07-01, from the user)

> Authoritative design direction for the rebrand. Any agent doing UI/theming work reads this first.
> The user LIKES the current editor's look — this brief is about extending its quality to the rest
> of the app and removing off-brand elements. Do not restyle the editor wholesale.

## 1. Brand personality
- **Professional serious tool, yet fun.** Reference vibe: the user's other project "Joy Raptor's Bible
  Study" — new-wave / Tron / synthwave elements (neon outline type, chrome bevel, starfield) — but
  Joy Creator applies it with restraint: *slight* synthwave feel via highlights, not a costume.
- Dark theme: two-color gradient highlight elements over **dark gunmetal greys and blacks**.
- Optional future light mode: the inverse — sharp whites and light greys.
- **Frosted dark glass menus** (semi-transparent blur) so the video preview stays visible behind panels.
- Accent direction: **pink over the current default red** reads much better.

## 2. The palette (extracted from the user's companion project; ANY two adjacent colors gradient well)
neon pink `#ff008c` · vivid purple `#cc27ff` · purple `#8c3dfa` · indigo `#5c43fd` · bright blue `#4397fd`
· cyan `#55e0f9` · aqua green `#35f6bf` · lime `#97fe8b` · yellow-green `#ceff5b` · golden yellow `#f9f462`
· amber `#ffc341` · orange `#faa03d` · deep orange `#fc6818` / `#fc4d18` · red-pink `#fa3d5d`

## 3. Section identity via color
Each app section gets its own **two-color gradient combo** for highlight elements (over the shared
gunmetal/black base) so users always know where they are. Existing anchor: the editor already owns
green(s) — keep that. Assign other combos (e.g. files/records = indigo→bright blue, recorder = pink→purple,
settings = cyan→aqua, AI = purple→pink) — propose a final mapping before mass-applying.

## 4. De-politicize / de-brand (KEEP the bones, remove the messaging)
The user wants NO political or activist identity in the app:
- Remove/neutralize "Free Palestine" advocacy elements, the black-flag motif, and spy/security/anonymous
  branding-as-identity (FadSec-style motifs, secret-agent character *theming*).
- **KEEP the interactive long-press character MECHANIC** — do not delete the feature. It will later be
  reskinned as the user's AI companion character. Reskin, never remove.
- The app's actual privacy features are fine — it's the political/ideological *messaging* that goes.

## 5. Menu/panel philosophy (from the user — applies to ALL new surfaces incl. Layers)
- Short drop-downs are fine: brief occlusion is OK when content is small.
- Large menus are a problem wherever they sit: covering the timeline is as bad as (or worse than)
  covering half the preview — it breaks lining things up. Balance per tool.
- Vertically shrink upper drawers where possible; keep drawers dark semi-transparent or frosted so
  underlying data remains legible even when occluded.
- The transcripts side drawer is the gold standard: resizable, quickly toggleable, transparent.
- Layers/multi-track UI must respect this: the layer navigator + timeline must stay usable with
  panels open (resizable, frosted, collapsible).

## 6. Rebrand mechanics (see EVAL_20260701 §7)
- Visible rebrand (name/icon/splash/strings/theme): authorized, do incrementally.
- applicationId change: ONE deliberate migration later, pre-release. Not now.
- GPL: fork must remain GPL w/ attribution if ever distributed. User informed.
