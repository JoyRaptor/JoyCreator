# Note9 watercolor smoke — October13, 2026

Owner's direct "phone is unlocked" message was verified in the watercolor chat.
Fresh identity identified the Note9 sandbox, SM-N960U; launcher/Home gate passed
immediately before installation. No Note20 command, uninstall or owner drawing was used.

Installed the verified combined APKdf8df716 in place, preserving app data. Package
lastUpdateTime changed from October12 to October13 13:28:55. The installed base.apk
SHA256 matches the built artifact exactly:
AE90642B3FB07202AE29DD36963E2DACFB3FE61894D0615EA7EA8D2B0AFF16BA.
This artifact predates the subsequently integrated count control; it is not count acceptance.

Two single fresh unsaved debug scribble runs used joybrush.watercolour,721 samples each.
The debug runner skips working saves, finishes its own Activity and preserves existing projects.
The first short hold ended before screenshot; its screenshot showed Home. The second60s hold
allowed inspection of C:/Temp/jb-phone-20261013/watercolour-render.png: a rendered stroke is
visible on paper. No AndroidRuntime crash appears in captured logs. This is a renderer smoke
check, not a judgement that the owner's preferred watercolor appearance or physical feel passes.

| Metric | First run | Second run |
|---|---:|---:|
| First frame |1513.47ms |0.42ms |
| Busy p50 / p95 |15.53 /22.86ms |15.86 /28.80ms |
| Frame gap p50 / p95 |16.74 /21.51ms |17.38 /30.19ms |
| Input latency p50 / p95 |181.27 /1392.85ms |10.60 /64.27ms |
| Stroke frames |68 |150 |
| Gaps over34ms |2 |5 |

The second run's much better initial latency suggests warm-up/cache behavior, not a proven
cause. The cold first-stroke delay and long frames need profiling; the16ms device goal does
not pass from these results. Actual work/GL-finish measures are retained in the numeric JSON.
Do not call mediaMs a watercolor pass measurement: this new line brush reports mediaFrames0.

The benchmark measures only250ms after pen-up, shorter than the cosmetic pooling fade.
Future W5 checks must measure the complete fade/final commit, more cases/repetitions and
physical interaction before accepting latency/performance. No paper/brush parameters or
source were changed during these runs. W4 recorded edits/host remains unbound. Settings,
save/reopen, physical tilt/pen and owner artistic acceptance remain open.

Evidence: reviews/evidence/NOTE9_WATERCOLOUR_SMOKE_20261013.json; raw launch/log/image
artifacts under C:/Temp/jb-phone-20261013. Both debug runs logged done; after the second
automatic completion, fresh foreground check verified launcher/Home. Device lane released.
No unrelated Activity was closed by force.
