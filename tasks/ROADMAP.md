# ROADMAP — what happens next, in order

**Companion files:** STATE.md (what exists), ORPHANS.md (started and stopped, still wanted), INBOX.md (unsorted ideas), LEDGER.md (the story).

Two lists, deliberately unequal.

**BEFORE LAUNCH is closed.** Nothing gets added to it without something coming out. That
is the whole point of it. If an idea arrives, it goes to INBOX.md.

**AFTER LAUNCH is open.** Reorder it freely, as often as you like. Reordering costs nothing.

---

# PART ONE — BEFORE LAUNCH

## Lane 0 — Pure waiting. Start these first, they cost only calendar.

| # | Item | Who | Notes |
|---|---|---|---|
| 0.1 | ✅ **DONE 2026-09-09** — repo backed up to github.com/JoyRaptor/JoyCreator | — | 3,253 commits pushed. History rewritten first: name, personal email and device serials removed from every commit, file and message. Local copy of the original history kept at `../joycreator-backup-20260909-2035.git`. **Confirm the GitHub repo is set to Private.** |
| 0.2 | Google Play developer account | JoyRaptor | One-time fee plus identity verification, which takes days |
| 0.3 | A developer email address | JoyRaptor | Goes on the listing publicly. Not the personal one. |
| 0.4 | Recruit 12 closed testers | JoyRaptor | Family, church, one Reddit post |
| 0.5 | Start the 14-day closed test clock | JoyRaptor | **The build does not have to be finished.** It updates during. |

## Lane 0.5 — Look at what is already built. See ORPHANS.md.

| # | Item | Notes |
|---|---|---|
| 0.6 | ✅ **DONE 2026-09-10** — it plays. The 2026-08-29 "critical regression" was an automation artefact, not a defect. | |
| 0.7 | **Re-run the 41-check device sweep** (SPEC_20260829_DEVICE_VERIFY_ALL) | 14 finished features have never been looked at. One evening with a phone. **Highest value per hour on this page.** |
| 0.8 | Fix whatever the sweep finds | |

## Lane 1 — Cannot ship without these

| # | Item | Why |
|---|---|---|
| 1.1 | Remove the 30 disguise icons from the Play build | Stalkerware signature |
| 1.2 | Remove the accessibility screenshot service from the Play build | Policy violation on its own |
| 1.3 | Resolve MANAGE_EXTERNAL_STORAGE for the Play build | Usually refused for editors |
| 1.4 | Remove cloud / streaming / privacy-black-screen from the Play build | LAUNCH_STRATEGY sections 3 and 5 |
| 1.5 | Decide and set the application ID | **Permanent.** Changing it later loses every user. |
| 1.6 | Rewrite README and PRIVACY | They describe FadCam today and would be false about the AI |
| 1.7 | Privacy policy hosted at a public URL | Required before you can submit |
| 1.8 | Rewrite the "verify you are a human" screen | Currently written for a covert recorder |

## Lane 2 — The foundation that gets expensive after users arrive

| # | Item | Why now |
|---|---|---|
| 2.1 | **THE VAULT** — projects move out of app-private storage into a folder the user picks | Today it has to work on one phone. After launch it has to work on every phone, forever. |
| 2.2 | Vault picker in first-run, with "I already have one" | The restore path is what proves the ownership promise |
| 2.3 | Migrate existing projects into the vault on first run | One-time, while JoyRaptor is the only user |
| 2.4 | Recordings land in the vault by default | Ends "where the hell is my screen recording" |
| 2.5 | Always copy on import, silently. No question. | The alternative breaks projects weeks later |
| 2.6 | Graceful "vault unavailable" state | SD card popped out must not look like data loss |
| 2.7 | Fix audio clip IDs regenerating on save (STATE section 9, defect 1) | Data integrity, and it undermines portability |
| 2.8 | Agree the pack folder contract (contract only, no manager UI) | Free now. Four asset systems later if we skip it. |

## Lane 3 — The AI, tightened

| # | Item | Size |
|---|---|---|
| 3.1 | Default model off openrouter/auto | One line. Currently bills every new user on message one. |
| 3.2 | Cap the tool-calling loop; raise the reply limit to 4096 | Small |
| 3.3 | Encrypt the stored API key | Small |
| 3.4 | Rename ai_merge_transcript | Trivial |
| 3.5 | **One-tap buttons for tools that already exist** — Clean up audio, Make chapters, Cut the ums | Small, and the highest value-per-hour item on this page |
| 3.6 | Streaming replies instead of a frozen "Thinking…" | Most of what makes an AI feel good |
| 3.7 | Fix the three tools that only see the video track | One file, three functions, one at a time |
| 3.8 | Split the AI networking behind one seam | Needed for everything after. Ship two providers: OpenRouter and a custom URL box. |
| 3.9 | **Share-in** — accept an image or video from the Android share sheet, straight onto the timeline | The headline. Works with any AI app, no key. |
| 3.10 | generate_image through OpenRouter, into the project | Same pipeline as 3.9. Endpoint verified. |

## Lane 4 — What a stranger meets

| # | Item |
|---|---|
| 4.1 | New intro copy (drafted and approved 2026-09-09) |
| 4.2 | Defer permission requests to the point of use |
| 4.3 | Demo project — a small pre-loaded project you can press play on |
| 4.4 | Hide Avatar Studio and other 🟡 features from the launch UI. Mesh warp / Bend is 🟢 as of 2026-09-10 and ships visible. |
| 4.5 | Promotional screenshots (from the demo project) |
| 4.6 | Feature graphic and store icon |
| 4.7 | Store listing copy (generated from STATE.md) |
| 4.8 | Website — GitHub Pages is free and sufficient |
| 4.9 | 60–120 second demo video |

---

# PART TWO — AFTER LAUNCH

Ordered by current thinking. Reorder freely.

## First update — the "look what landed" moment

- **Video generation.** Prompt, background job, confirm the cost, lands on the timeline.
  Requires: silent audio track added on import or the export dies.
- **Joybot.** The sprite, his animated states, his corner, his tour. Its own session.
- More providers behind the seam built in 3.8.

## Soon after

- Pack manager UI — install, enable, disable, share as a zip
- Theme packs (also the natural Supporter unlock)
- Template gallery
- Finish the AI proposal tools (narrative reorder, b-roll placement)
- New landing screen and the recorder-to-Studio funnel
- Luma key
- Adjustment layers and FX
- Free-up-space action, project sizes in the list

## Later

- Community pack index — a static file on GitHub Pages, zero infrastructure
- Composed-frame vision — the AI sees what you see, overlays included
- Lecture mode — one confirmation card runs the whole edit
- Avatar Studio unhidden, once driven by a human
- Mesh warp unhidden
- Multi-camera and remote-camera-over-LAN promoted properly
- Automated tests, starting with the validators
- Supporter tier via Play Billing
- F-Droid submission

## Parked, deliberately

On-device LLM. Hosted AI credits. WASM or native plugins. Music generation. iOS.
Any subscription. Any advertisement. Any export paywall. Any watermark.

---

## The promise that does not move

Free forever. Unhobbled. No ads, no subscription, no watermark, no export paywall.
AI stays bring-your-own-key at zero margin. Support is optional and unlocks cosmetics only.
