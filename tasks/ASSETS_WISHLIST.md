# Joy Creator — Visual Asset Wishlist (2026-07-02)
Things an LLM/agent cannot make well; the user produces or sources these at leisure. Specs are exact so
anything delivered drops straight in. Dark-theme-first everywhere; palette = DESIGN_JOY_CREATOR.md §2.
Drop finished files in a folder like `art/` at the project root and tell any session — wiring them in is our job.

## P1 — Needed for the rebrand to fully land
1. **App icon (adaptive, 3 layers)**
   - Foreground: logo glyph on TRANSPARENT, centered — keep all important detail inside the middle ~66%
     (Android crops corners into circles/squircles). Master 1024×1024 PNG (+ SVG if possible).
   - Background: flat dark or subtle two-color gradient from the palette (single 1024×1024).
   - Monochrome: pure-white flat silhouette of the glyph, transparent bg (for Android 13 themed icons).
2. **Wordmark "Joy Creator"** — horizontal + stacked variants, designed on dark; SVG strongly preferred.
   - Font decision is yours (licensing = human task). Zero-pain route: pick a Google Fonts display face
     you love (synthwave-adjacent: e.g. Orbitron/Audiowide/Monoton class) — tell us the name, we bundle it.
3. **Notification/status-bar glyph** — Android requires a PURE WHITE, alpha-only silhouette; must read at
   24×24dp. Simple shape (the glyph's simplest form). 96×96 PNG master.
4. **Splash branding (optional)** — Android 12+ splash reuses the adaptive icon automatically; optional
   small wordmark PNG for the bottom branding slot (~800×320 transparent).

## P2 — Identity & delight
5. **The companion character** (the kept long-press mechanic gets reskinned into this; later = the AI
   companion's face, and the sprite suite's flagship content):
   - Minimum: character sheet — one main pose + 2–3 expressions, transparent PNGs, consistent canvas.
   - Dream version: sprite frame sets on a fixed canvas (512×512 transparent PNG per frame), folders per
     animation: `idle/`, `blink/`, `talk/`, `happy/`, `thinking/` — even 4–8 frames each is plenty.
6. **Watermark mark** — subtle corner glyph for recordings, PNG w/ alpha, two masters (512px + 256px wide).
7. **Empty-state illustrations** (on-dark, palette accents; ~800×600 transparent each): empty library,
   no projects yet, empty story board, empty trash. 3–5 pieces, consistent style.

## P3 — Test footage for the upcoming compositing features (you can RECORD these — 10–20s each)
8. **Green-screen clip**: you (or any object) moving in front of anything flat green. For chroma-key dev.
9. **Seamless looping overlay animation** (your masking scenario's "beautiful looping animation") — any
   short loopable MP4; if you have one with transparency (WebM/alpha), even better.
10. **Luma-matte clip**: white shapes moving on black (screen-record anything high-contrast). For
    video-as-alpha / track-matte dev.
11. **Hard-motion clip** (fast object crossing frame) — makes ping-pong reversal obvious to the eye.

## P4 — Decisions only you can make (no files needed)
12. **AI generation services**: which image/video generation service(s) you want connected (and have
    accounts/keys for) — shapes the "AI asset intake" integration design.
13. **Section-color mapping approval**: DESIGN §3 needs a final assignment (a proposal will be shown
    before mass-applying — you just pick).
14. **Font choice** (see item 2).

## Format rules of thumb
SVG > PNG where possible; PNG always transparent bg, no baked drop shadows; name files by what they are
(`icon_fg.png`, `wordmark_h.svg`, `char_idle_01.png`); when in doubt, bigger master = better.
