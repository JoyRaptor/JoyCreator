# Joy Creator Studio performance and stability audit

November 19, 2026 · Rolling engineering review · Base 7c3e1f04d4744b502d8829f2caae9e7cec3a0462 (origin/joy-creator verified against the server). The Explorer checkout at b184531a4 is 13 commits behind. References use the audited commit unless a different branch is named.

## Source reconciliation

The integrated open-tiles changes (c84c74c60 through ffb3af63b) already defer picture-tile reads during open. Preserve those gains. The newer Avatar Studio work through 7c3e1f04d is a web pilot. The Android Studio renderer and the pilot require separate evidence.

### AV-SAVE · A failed character autosave clears the pending-edit flag

Avatar Studio web pilot. An edit starts an 1.8-second timer. Autosave clears dirty before packaging the character and starting the IndexedDB transaction. Rejection is swallowed. Each save also re-encodes every referenced picture as a PNG data URL, even for a pin-only edit. A failed image decode resolves as success when opening a character. See tools/avatarlab/al-app.js lines 68, 447–455 and 490, and tools/avatarlab/al-model.js lines 112–131.

Host check executed against the actual autosave function with injected store rejection. Two calls with one edit issued one write attempt and left dirty false. The previous committed character can therefore reopen without the latest edit. Keep edit and committed generation numbers, acknowledge only transaction completion, surface failure while keeping the edit, and provide an explicit backup download. Store art blobs once per image revision. On load identify missing pictures before publishing a replacement. Gate with quota/abort, a second edit during an outstanding save, failed PNG decode, and close/reopen with a multi-angle character.

### AV-IDLE · The character builder redraws an unchanged pose every display tick

Avatar Studio web pilot. tools/avatarlab/al-app.js lines 183–205 schedules the next frame unconditionally, evaluates the rig and draws even with Build at rest. tools/avatarlab/al-gl.js lines 54–73 and 77–89 uploads positions, UVs and indices per drawn item. At 60 delivered ticks/s and 20 visible drawable items this is 3,600 bufferData calls/s before mask passes. That is a calculated workload for the example, not a device measurement. Render Build on invalidation and keep ticking while fitting the camera, animating, sampling live input or playing a take. Cache topology by mesh revision. Verify a stationary 30-second Build trace settles to zero recurrent solves/draws, a pointer move wakes within one frame, and live, onion-skin, mask and bridge frames remain pixel-identical.

## Device results already available to engineering

Use these as the baseline for new work. The Note 9 watercolor line bench on efc4e8a41 measured 54.1–55.3 ms median for the old dab step and 6.8 ms for the bounded finish, about 8× on that step. Median frame work went from about 60 to 13.4–13.5 ms. Engineers compared 4,701 partial tile finishes, all matching. The later lift change still recorded a 38–169 ms worst frame on its zigzag workload. The full-oil-page open test on the Note 9 recorded 105 MB sampled Java heap before deferred tiles and 48 MB on the final build run. The report gives 327 versus 267 MB total PSS and one run per variant. The same card records a 7.7 s full oil restore following graphics loss. Preserve the before/after fixtures, measure cold start, steady strokes, lift, open and context restore separately, and report device and build with each result. See lines 545–579 and 842–861. [Atlas engineering evidence](../../jobs/ATLAS-SOLID_never_refuse.md)

**Idle host check.** The production frame function received 600 synthetic display ticks with Build at rest, fixed dimensions and no edits. Instrumented boundaries counted 600 next-frame requests, 600 solver calls, 600 scene draws and 600 overlay draws. This checks scheduling and does not time a GPU.
