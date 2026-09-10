# START HERE — 2026-08-06 (evening). Supersedes HANDOFF_20260806_PICKUP.md.

Branch `joy-creator`. HEAD `3bc55b0`. Tree clean apart from JoyRaptor's own untracked
`TRADEMARK.md` and modified `LAUNCH_STRATEGY.md` / `OUTREACH_ANONFADED.md` — **leave those
alone, they are his.**

JoyRaptor is not a developer. Make ENGINEERING calls yourself; bring him PRODUCT/UX decisions only.
He is often away while you work; keep going rather than blocking.

Read this file. Read `HANDOFF_20260806_IMAGE_SEQUENCES.md` only if you need image-sequence
detail. The older `HANDOFF_20260806_PICKUP.md` is superseded — its §3 (OpenRouter research) is
still the best reference for the API, but its "next feature" framing is done.

---

## 0. HOW TO WORK HERE — the two things that will otherwise cost you an hour

```bash
bash tools/build-install.sh          # build + install to the sandbox phone, prints APK freshness
bash tools/jvm-harness/typecheck.sh  # whole-app javac in ~1 min, no Gradle
```

**You CAN build from inside the agent.** `Unable to establish loopback connection` is a red
herring that cost three sessions: loopback is fine, the real failure is `Selector.open()` on
JDK 17/Windows building its wakeup pipe from an AF_UNIX socket, which fails when
`java.io.tmpdir` is an 8.3 short path. `build-install.sh` sets the fix
(`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp` — must be JAVA_TOOL_OPTIONS,
the daemon needs it too).

**ALWAYS check the APK timestamp against the wall clock.** JoyRaptor's watcher died silently once
mid-session while `build.log` kept showing an old `BUILD SUCCESSFUL`; ~20 minutes of device
testing ran against stale bits and a working feature looked broken. The script prints both.

### Harnesses — all green at HEAD
```bash
bash tools/jvm-harness/run-sequence.sh   # 100/100 + 50/50
bash tools/jvm-harness/run-key.sh        # + MaskAnimator 22/22
bash tools/jvm-harness/run-matte.sh      # run-anchor.sh (35), run-promote.sh (24)
```

---

## 1. DEVICE — everything you need to drive it

- **Sandbox is Note 9 `<note9-serial>` ONLY.** If Note 20 `<note20-serial>` is attached, STOP —
  it holds JoyRaptor's real 45-minute project. `build-install.sh` refuses when it sees it. Both were
  attached at different points today; check `adb devices` before every device action.
- Screen is **1080×2220** (the runbook's 1440×2960 is wrong for this device).
- `am start -n com.fadcam.beta/com.fadcam.SplashActivity` — **never `monkey`**, it silently
  unlocks rotation.
- **Screenshots and file pulls must go through the Bash tool**, not PowerShell — PowerShell
  redirection corrupts binaries. `adb exec-out screencap -p > f.png`, and
  `adb exec-out run-as com.fadcam.beta cat <path> > f.png` for app-private binaries
  (plain `adb shell run-as ... cat` mangles them).

### Tap coordinates that worked today (1080×2220, portrait)
| Target | Tap |
|---|---|
| Faditor bottom-nav icon | `628 2107` |
| First project row in the list | `540 705` (second row `540 895`) |
| Sprites tool (bottom tool row) | `938 2160` |
| AI Assistant (green icon, editor top bar) | `936 55` |
| Chat attach button | `54 2109` · send arrow `1022 2109` |
| Editor close (X) | `55 55` · export/upload arrow `1018 55` |
| Sprite palette grab handle (drag up = next detent) | swipe `538 1782` → `538 1600` |

**Gotchas that ate time:** tapping outside the sprite palette collapses it, so a mis-aimed tap
eats the gesture you meant. The lane band scrolls independently and lanes collapse — the row you
want is often off-screen. An "Export Complete" overlay can sit invisibly over the editor and
swallow taps; check `dumpsys activity activities | grep ResumedActivity` when taps do nothing.

### Projects and backups
- `302da9ac` — designated sandbox, pristine `md5 aac2ba5c`.
- `aeb0517e` — restored byte-exact, `md5 f764e002`, backup at `project.json.bak-seq-20260806`.
- `cebc19e0` — restored byte-exact, `md5 4c1b03c5`, backup at `project.json.bak-vision-20260806`.
- **`98e303a6` — the verification fixture, left in place deliberately.** One clip + a 12-frame
  image sequence. Sprites tool → ▦ chip → dope sheet in two taps. Use this one.
- Test frames live at `/sdcard/Download/seqtest/frame_001..012.png` (numbered coloured discs).
- **Autosave rewrites a project just from opening it.** Back up with `run-as cp` BEFORE opening,
  `md5sum` after, restore if it drifted. This bit me today.

---

## 2. WHAT SHIPPED (do not rebuild)

`SPEC_IMAGE_SEQUENCE.md` is **complete**, including the load-bearing proof: exported the fixture,
extracted frames with ffmpeg at six timestamps, and every one showed exactly the frame the weight
model predicts — with nothing drawn past the sequence's end. Preview == export, on real pixels.

Also landed today: copy-on-write for sheet-scoped edits; the ABSOLUTE left-trim; an adversarial
review that found 13 issues (8 real, 7 fixed); the animation staleness audit; five corrected doc
status lines; the OpenRouter **eye indicator** and **send-this-frame** (both device-verified).

---

## 3. THE TODO LIST — verified against code, not memory, on 2026-08-06 evening

### A. VERIFICATION OWED — built, never tested on a phone. **Do this first.**
Cheapest possible catch, and the likeliest place a regression hides.

1. **§2a ABSOLUTE left-trim** — drag a sequence's LEFT edge in ABSOLUTE mode; frames should come
   off the FRONT and return when you drag back out. Harness-pinned, never touched on device.
2. **§9c live readout** (`24 frames · 4.0s · 6.0 fps` during an edge drag). Needs a mid-gesture
   capture: run `adb shell input draganddrop x1 y1 x2 y2 3000` in the BACKGROUND, sleep ~1.5s,
   then screencap.
3. **Copy-on-write** — place one sequence twice, resize one, confirm the other is untouched and a
   "frames 2" sheet appeared in the manager.
4. **§8 MISSING placeholder** — rename a frame file on device; expect the dashed amber slash and
   "1 of 12 frames missing" in the sheet manager.
5. **§7 AI sequence tools** — `describe_sequence` / `edit_sequence`. JoyRaptor has an OpenRouter key
   configured now, so this is finally testable.
6. **Mask tab** — `PipDrawerTabs.maskTab` has "Move with the object" + "◆ Key at playhead".
   Select a PiP → its drawer → Mask tab → toggle → confirm `"link": true` and `linkBase*` in
   project.json. I could not reach the PiP menu by coordinate tapping (its lane row stays
   collapsed and it overlaps an image overlay in the preview).

### B. VISION FEATURE — remaining
- **The round trip is UNCONFIRMED.** The free router's reply to an attached frame was
  `"User Safety: safe"` — a real response, but not a description, so whether that routed model
  looked at the pixels is unproven. Retry against a pinned vision slug
  (e.g. `nvidia/nemotron-nano-12b-v2-vl:free`) before believing it end to end.
- **Composed frame, not just source video.** Today it sends the clip's own frame; overlays,
  text, sprites and sequences are absent, so it cannot answer "does my caption read over this".
  That needs `CompositeExportOverlay` run offscreen — a real piece of work, and the natural
  follow-up.
- Audio/video input: `openrouter/free` is text+image only. Needs a pinned slug or paid.

### C. ORPHANS — built but unreachable (recounted today)
| What | Hits | Note |
|---|---|---|
| **`MaskKeyPanel`** (466 lines) | `showMaskDialog` = 1 (its own declaration) | Dead since the PiP-drawer redesign. **JoyRaptor's call: delete or revive.** |
| **`AvatarRigTemplates.bipedTemplate()` / `templateJson()`** | harness only | No "start from a biped" flow; the AI tool hand-writes its own rig shape instead. |
| `isCellMissing()` | 1 | Declaration only. |
| `SequenceImportDialog.showFor()`, `FrameTrack.resort()`, `SpriteSheet.getOrder()`, `SequenceFrameCache.residentLimit()`, `ObjectTimeScrubSession.currentLane()/currentStart()`, `TimeShuttleView.setMaxMsPerSec()`, `DangleSim.isPrimed()` | 1 each | Small. `getOrder()` means the spec'd `order: "row-major"` is stored, serialised and never read. |

✅ **Closed since the audit:** `openDopeSheet()` (now the ▦ chip) and `clearMissingCache()` (now
wired to editor-resume and manager-open).

### D. MISSING — spec'd but never built (all verified 0 hits / absent today)
| What | Source |
|---|---|
| `set_sprite_grid`, `label_sprite_cells`, `author_sprite_animation`, `apply_sprite_proposal` | PLAN_SPRITE_ANIMATION FF-B — **largest genuinely-missing block** |
| Palette per-track arm toggles (Pos/Scale/Rot/Opacity) | S3 — arming exists, but in `ObjectMenuSheet` |
| Dope-sheet **transform** rows + per-key easing | FF-A — `DopeSheetView` is frames-only |
| `sw600dp` tablet layouts | S2b — **no `values-sw600dp` or `layout-sw600dp` dir exists at all** |
| Time scrubber for sprite / audio / PiP | SPEC_OBJECT_TIME_SCRUBBER §12 — one call site, text only |
| Cross-lane vertical y-GLIDE + push-through relayer | Scrubber §9 |
| Retrigger-on-value-change (timer tick pop) | SPEC_TEXT_ANIMATION |
| **Duplicate-layer button** | JoyRaptor's request — onto its OWN LANE at the SAME time position (cannot overlap, needs no push-through, matches the no-overlap invariant) |

### E. SMALLER KNOWN ISSUES
- `SequenceFrameCache`'s 24MB budget is **per cache**, and up to 3 caches exist per sheet
  (editor / timeline / export) → ~144MB ceiling with three sequences open.
- Scrubber **B-CLAMP**: a scrub can push an object past the timeline end forever. **B-PREVIEW**:
  the move preview is not always legible.
- `SequenceDetector` caps at 600 offered frames.

---

## 4. DECISIONS JOYRAPTOR HAS MADE (do not re-litigate)

- **Copy-on-write (option A)** for sheet-scoped edits — BUILT.
- **Instanced-vs-independent objects: NOT building it.** He floated twin badges / link states /
  a settings default, then talked himself out of it — correctly. Loop already covers the main
  "many copies of one thing" case, and a link mode is the invisible state SPEC_IMAGE_SEQUENCE
  §3b warns against. **If the need resurfaces, the cheap version is ONE chip — "Apply to all 3
  copies" — shown only when copies exist.** Explicit, named, no mode.
- **Duplicate button** → own lane, same position (see §3D).

### Still needs JoyRaptor, not you
1. Delete or revive `MaskKeyPanel`.
2. Is the composed-frame (overlays included) vision work worth it?
3. Do tablet layouts matter yet?

---

## 5. SUGGESTED ORDER
1. §3A verification (~1 hour).
2. FF-B's four sprite AI tools — largest missing block, and there is a key now.
3. Duplicate-layer button — small, requested, no decision needed.

## 6. WORKING NOTES EARNED THE HARD WAY
- **Ask "who calls this?" before believing anything is built.** Two orphans were found where the
  only grep hit was the declaration. Highest-yield check in this repo.
- **Adversarially review your own work.** A read-only review agent over the session diff found
  13 issues, 8 real, two of which made headline gestures silently inert.
- **Do not trust any doc's status line.** Five were wrong, in both directions. Verify in code.
- The dope-sheet rebuild runs from its own `ACTION_UP`, so it must be **posted**, not inline, or
  the view detaches mid-dispatch.
- When a tool blames the network, check whether the network is actually broken.
