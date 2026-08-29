# TAP MAP — Note 9 (`SANDBOX_SERIAL`), verified by hand 2026-08-29 13:01

**Why this file exists.** Two verification sweeps failed on navigation, and the second one
reported a **false audio regression** because it reused tap coordinates from an earlier
build. Those coordinates were stale: `QUICK_WINS §1` had added the **Image** button to the
toolbox, which grew the tool row and pushed the transport down ~180px. Eight taps at the old
y landed on nothing, the playhead never moved, and that was written up as "play silence".

So: here is a map, measured by hand on the build installed at **13:00:45**.

⚠️ **These are a STARTING POINT, not a substitute for looking.** Any lane that changes a
layout invalidates them. The rule stands: **screenshot the build you are testing, and derive
the coordinates from that screenshot.** If a tap does not do what this file says it will,
believe the screenshot, not this file — and fix the file.

---

## Device

```
serial   SANDBOX_SERIAL   (SM-N960U, Note 9)
physical 1440 x 2960
OVERRIDE 1080 x 2220   <-- compute ALL taps against this
```

`.\tools\phone.ps1 size` prints both and says which to use. A coordinate computed from the
physical size misses by 25%.

---

## Route into a project

| Step | Command | Then |
|---|---|---|
| 1 | `.\tools\phone.ps1 launch` | wait ~9 s |
| 2 | `.\tools\phone.ps1 tap 628 2110` | Faditor tab (4th in the bottom nav). Wait ~5 s |
| 3 | `.\tools\phone.ps1 tap 330 <row-y>` | opens that project. Wait **~14 s** — a project with media is slow |

Project-list row centres (23 projects, newest first):

```
row 1   y ≈  700
row 2   y ≈  895
row 3   y ≈ 1041
row 4   y ≈ 1230
row 5   y ≈ 1420
```

Rows are ~190px apart. **Screenshot first** — the order changes every time a project is
opened, because the list is sorted by last-modified.

---

## Inside the editor (as of the 13:00 build)

| Control | Coordinate |
|---|---|
| **Transport play/pause** | **541, 1149** |
| Close (×) | 55, 55 |
| Export (↑) | 1015, 55 |
| Toolbox row | y ≈ 2010 |
| Timeline ruler / playhead chip | y ≈ 1300 |

⚠️ **Tap play ONCE.** A second tap pauses it again — a sweep did this and then reported the
resulting `state:idle` AudioTrack as a failure.

---

## Evidence commands — often stronger than a screenshot

```
.\tools\phone.ps1 audio          # AudioTracks + allocation count
.\tools\phone.ps1 log AudioLayerSync
```

Read them like this:

| Signal | Means |
|---|---|
| an AudioTrack at `state:started` | audio really is flowing |
| all tracks `idle` / `paused` | it is not |
| **playhead advancing in `PHDIAG`** | **playback started at all** |
| playhead stuck at `00:00.000` | **playback never began — your tap missed.** Not an audio bug |
| `drift baseline [n] = …` once per layer, then silence | the drift lock is healthy |
| any `repark` line | a real regression |

That fourth row is the one that was misread on 2026-08-29. **If the playhead is not moving,
the problem is upstream of audio — check your tap before you check the code.**
