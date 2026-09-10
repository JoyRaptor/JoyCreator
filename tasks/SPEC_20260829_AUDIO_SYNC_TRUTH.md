# SPEC — Audio sync truth: kill the poll-and-seek, lock the drift, calibrate the latency

**Written:** 2026-08-29 · **For:** an external agent · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first and claim a lane named `SPEC_20260829_AUDIO_SYNC_TRUTH`.**
Rule 6 (never run gradle — save and read `build.log`) and the WORKING-TREE HAZARD
(`git add` each file the moment you write it) are not optional.

**You are an implementer, not a reporter.** Every claim of "built" is checked against
`build.log`'s LAST LINE and its mtime. Every measured number is checked against
`adb devices`. Inventing a number is worse than reporting that you could not measure it
— on 2026-08-28 an agent invented PSNR figures for an unplugged device and all of its
work had to be re-verified from scratch.

---

## 1. The defect, stated exactly

JoyRaptor, using the editor on a 7-minute music project:

> "When I press play it seems too long before I hear sound. The playhead starts moving
> but there's a delay for audio and it's hard for me to figure out exactly where a word
> starts because of it. We need calibration to know exactly where a sound is. And that
> it is playing at the EXACT TIME you see its spike in the preview tape and hear it."

There are **two engines** and only one of them keeps time honestly.

| | Video clip + its own audio | Audio LAYERS (music, voiceover, imports) |
|---|---|---|
| engine | one ExoPlayer in `compositor/MasterPlaybackEngine` | N separate ExoPlayers in `compositor/AudioClipPreviewPlayer` |
| driven by | its own clock; the playhead is READ from it | a 50 ms polling loop that WRITES to it |
| timing | tight | broken — see below |

The polling loop is `FaditorEditorActivity#syncAudioPlayerWithPlayhead()` (~line 8935),
called every `PLAYHEAD_UPDATE_INTERVAL_MS` (50 ms, line 766). Per tick, per audio clip,
it does:

```java
if (playheadMs >= audioStartMs && playheadMs < audioEndMs) {
    if (!mp.isPlaying()) {
        long seekPos = ac.getInPointMs() + (playheadMs - audioStartMs);
        mp.seekTo(seekPos);
        mp.start();
    }
}
```

**Three compounding defects:**

1. **It reads the playhead, THEN seeks.** An ExoPlayer seek on a compressed source costs
   50–300 ms (decode to the preceding sync sample, refill). By the time the first sample
   reaches the speaker the master clock has already advanced by that much. The audio
   starts late by exactly the seek cost, and nothing accounts for it.
2. **The error is never corrected.** After `start()` the branch is `if (!mp.isPlaying())`
   — so once running, the loop never touches it again. Whatever offset you get at the top
   of the pass is the offset for the whole pass. There is **no drift correction of any
   kind** in this file.
3. **The error is different every run.** Seek cost varies with file, position and device
   load. So the offset is not even a constant the user could learn to compensate for
   mentally. That is precisely why JoyRaptor cannot find where a word starts.

Meanwhile the waveform tape is drawn from decoded PCM at exact sample positions
(`waveform/WaveformExtractor`). **The tape is truthful; the playback is not.** The
mismatch the user perceives is real and it is entirely on the playback side.

**Fourth, separate defect — device output latency is never measured at all.** Even with
the three above fixed, the drawn playhead marks where the decoder is, not where the
*speaker* is. Every Android output path adds latency: ~20–40 ms wired/speaker,
**150–300 ms on Bluetooth**. JoyRaptor has confirmed the problem is worse on Bluetooth,
which is consistent. That component is not a bug — it is physics — but it must be
**measured and compensated**, which today it is not.

---

## 2. Scope, and what NOT to touch

**In scope (your files — claim exactly these):**

```
app/src/main/java/com/fadcam/ui/faditor/audio/AudioLayerSync.java        (NEW)
app/src/main/java/com/fadcam/ui/faditor/audio/AudioLatency.java          (NEW)
app/src/main/java/com/fadcam/ui/faditor/compositor/AudioClipPreviewPlayer.java
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java       (SEE §4 — a
   deliberately TINY edit; another lane may hold this file)
```

**Out of scope — do not touch, do not "improve while you are in there":**

- `MasterPlaybackEngine` and its `UiSyncAudioProcessor`. The master path is not the bug.
- `audio/fx/*` — the FX chain. `run-audio-fx.sh` compiles it against stubs; adding an
  `android.*` dependency there breaks the harness.
- The export path. Export is sample-exact already; this is a PREVIEW-only defect.
- `PLAYHEAD_UPDATE_INTERVAL_MS`. Do not "fix" this by polling faster. Polling faster
  makes the CPU worse and the timing no better — the seek cost dominates, not the tick.

---

## 3. What to build — four parts, in this order

### 3.1 Pre-roll: never seek at play time

**The rule: by the time the user presses play, every audio layer is already parked at
the right sample with a full buffer, and `start()` is the only call made.**

Add to `AudioClipPreviewPlayer`:

```java
/** Park (seek + buffer) at sourceMs WITHOUT starting. Returns immediately; use
 *  isParkedAt(sourceMs) to learn when the seek has actually landed. */
public void parkAt(long sourceMs);

/** True once the player is buffered and its position is within PARK_TOLERANCE_MS of
 *  the requested park point (i.e. start() will produce sound immediately). */
public boolean isParkedAt(long sourceMs);
```

Implement `parkAt` as `setPlayWhenReady(false)` + `seekTo` + `prepare()` if needed.
`isParkedAt` is
`playbackState == STATE_READY && Math.abs(player.getCurrentPosition() - sourceMs) <= PARK_TOLERANCE_MS`.
`PARK_TOLERANCE_MS = 15`. **One constant, one predicate, defined once** — trap 3 in the
handoff (two answers to one question) caused three separate bugs on 2026-08-28.

Who calls `parkAt`, and when — all three, or the pre-roll does not help:

- **On playhead move while paused** (the user scrubs or taps a word), debounced ~120 ms
  so a drag does not fire a hundred seeks.
- **On pause.**
- **On transport arm** — i.e. the moment play is requested, park first, then start.

### 3.2 Coalesced start: everybody begins on the same edge

Today each layer starts on whichever 50 ms tick happens to notice it. Replace that with
one explicit start:

1. Compute the target source position for **every** layer that should be sounding.
2. `parkAt` them all.
3. Wait (async, on the existing playhead handler — do **not** block the main thread) for
   every one to report `isParkedAt`, up to `PARK_WAIT_TIMEOUT_MS = 400`.
4. `start()` them in one pass, then start/resume the master.
5. If the timeout expires, start anyway and let §3.3 pull the stragglers in. **Never
   block playback on a slow park** — a user pressing play must always get playback.

### 3.3 Drift lock: correct with speed, never with a seek

This is the part that makes it stay right, and it is the part most likely to be got
wrong. **Do not re-seek to correct drift.** A corrective seek is audible as a click or a
repeated syllable and it re-introduces the very seek cost this spec removes.

Each tick, for each sounding layer, compute:

```
expectedSourceMs = clip.getInPointMs() + (masterPlayheadMs - clip.getOffsetMs())
actualSourceMs   = player.getCurrentPosition()
errorMs          = actualSourceMs - expectedSourceMs      // + means the layer is AHEAD
```

Then act by band. **These bands are the whole algorithm — get them right:**

| `abs(errorMs)` | Action |
|---|---|
| ≤ `LOCK_MS` (8) | do nothing. This is the locked state. |
| ≤ `TRIM_MS` (120) | set `PlaybackParameters` speed to `1f - clamp(errorMs / 1000f, -0.002f, 0.002f)`. A ±0.2 % rate change is inaudible (well under the ~0.6 % pitch JND) and closes a 120 ms error in about a minute — so hold the trim until the error re-enters the lock band, then restore speed to exactly `1f`. |
| > `TRIM_MS` | genuine desync (a seek, a stall, a clip boundary). Re-park and re-start via §3.1/§3.2. |

Hysteresis matters: once trimming, keep trimming until `abs(errorMs) <= LOCK_MS`, then
restore `1f`. Do not flip-flop on the boundary. Restore `1f` **exactly** — never leave a
residual rate on the player, or a long project slowly detunes.

**Speed changes must not repitch.** Media3's `DefaultAudioSink` uses Sonic, which
time-stretches without pitch shift. But when `setEnableAudioTrackPlaybackParams(true)`
is passed, the AudioTrack hardware path is used instead and it DOES repitch. Read
`AudioClipPreviewPlayer`'s renderer factory (~line 93) and confirm which path is live;
force the Sonic path for these micro-corrections. **If you cannot confirm which path is
active, say so in your report rather than assuming.**

### 3.4 Latency calibration — the "calibration" JoyRaptor asked for

New class `audio/AudioLatency.java`. Two sources of truth, in priority order:

1. **Measured.** `AudioTrack.getTimestamp(AudioTimestamp)` gives `framePosition` and
   `nanoTime` — the difference between presentation time and now IS the output latency.
   Sample it a few times after playback starts, take the median, cache per output-device
   id. Re-measure on device change (`AudioDeviceCallback` — a user unplugging headphones
   MUST invalidate the cache; a stale wired value on Bluetooth is worse than no value).
2. **Estimated fallback**, when `getTimestamp` returns false (it does on some devices):
   `AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER` / `PROPERTY_OUTPUT_SAMPLE_RATE`,
   times a small multiplier. Plus a fixed Bluetooth surcharge when
   `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP` is the active route.

Then a **user offset** on top: `SharedPreferences` key `audio_sync_offset_ms`, integer,
range −500..+500, default 0.

**Where the number is used — this is the point of the whole exercise:** the playhead
DRAWN on the timeline is offset by the total latency, so the line crosses a waveform
spike at the instant the speaker produces it. One accessor:

```java
/** ms to SUBTRACT from the decoder position to get the position the user is HEARING. */
public static int outputLatencyMs(Context ctx);
```

Applied at exactly ONE place: where `editorTimeline.setPlayheadPositionMs(...)` is fed
during playback. **Not** in the model, **not** in seeking, **not** in export — only the
drawn position during playback. If you apply it anywhere else you will make word-tap
seeking wrong, which currently works (`b8e8859d`).

Guard it: when paused, the offset is 0 (there is nothing being heard to compensate for).
A discontinuity of ~30 ms at the play/pause boundary is correct and invisible.

### 3.5 The calibration screen (build it, it is small)

In the audio drawer, a row: **"A/V Sync"** showing the measured latency and the user
offset. Tapping opens a sheet with:

- The measured value, its source (`measured` / `estimated`), and the active route name.
- A slider, −500..+500 ms, live.
- A **Test** button: plays a short click track (a generated 1 kHz 10 ms tick every
  500 ms, synthesised in code — no asset needed) while a dot flashes on screen in time.
  The user slides until flash and click coincide. This is the standard TV audio-sync
  affordance and every user already knows how to use it.
- **Reset to measured.**

---

## 4. The `FaditorEditorActivity` edit — keep it TINY

Another lane may hold this 36k-line file. Therefore: **all logic goes in
`audio/AudioLayerSync.java`.** The activity edit is a delegation and nothing else:

- `syncAudioPlayerWithPlayhead()`'s body becomes a call into `AudioLayerSync#tick(...)`.
- Add `AudioLayerSync#armForPlay(...)` at the existing play call site and
  `#parkOnPause(...)` at the existing pause call site.
- Apply `AudioLatency.outputLatencyMs` at the one playhead-draw site.

Nothing else. If you find yourself editing a fifth place in this file, stop and re-read
this section. `AudioLayerSync` should own: the player list, the park state machine, the
drift bands, and the `audioStoppedSinceMs` debounce that lives in the activity today.

---

## 5. Acceptance — evidence or it did not happen

**Nothing in this section may be reported from reasoning. Each line needs an artefact.**

1. **Build.** Last line of `build.log` is `BUILD SUCCESSFUL` and its mtime is newer than
   your last edit. Paste both. A `BUILD FAILED` reported as success is the single worst
   outcome of this spec.
2. **Device present.** `adb devices` output pasted. If no device: **stop, say so, and do
   not fill in §5.3–5.7.** That is a complete and acceptable outcome for this spec.
3. **Start offset, before and after.** On a project with one video clip and one music
   layer: record the screen with audio (`adb shell screenrecord`) from a paused state
   through 3 seconds of playback. Measure, in the recording, the gap between the playhead
   beginning to move and the first audio sample. Report BEFORE (current build) and AFTER.
   **Target: under 30 ms wired.** If you cannot extract audio from `screenrecord` on this
   device, say so and fall back to §5.4 alone rather than estimating.
4. **Drift over 5 minutes.** Play a 5-minute stretch. Log `errorMs` every 5 s. Report
   max and final. **Target: max ≤ 20 ms, final ≤ 10 ms.** Attach the log.
5. **No audible artefact.** Listen to the 5 minutes. Micro-speed trims must be
   inaudible — no clicks, no wow, no pitch wobble. If you hear anything, the bands in
   §3.3 are too aggressive; report what you heard before retuning.
6. **Latency readout.** Screenshot the A/V Sync row on wired and on Bluetooth. The two
   numbers must differ substantially (BT much larger). If they are identical, the route
   detection is not working and the feature is not done.
7. **Word alignment — the actual user acceptance.** On JoyRaptor's music project: play, and
   confirm that a word's highlight, its waveform spike, and the sound coincide.
   Screenshot at the moment of a transient.

---

## 6. Traps carried from 2026-08-28

- `getSelectedClip()` silently returns `getClip(0)` when nothing is selected — on a music
  project that is the auto-blank black spacer. Use `clipUnderPlayhead()`.
- A value written and never read was the root cause three times in one day
  (`audioPlayersReady` is exactly this bug, fixed in `d1b245bc`). When your new state does
  nothing, check that something CONSUMES it before assuming the logic is wrong.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
- The export runs in its own process (`com.fadcam.beta:export`) and survives app restarts.
  `am force-stop` before timing anything.
