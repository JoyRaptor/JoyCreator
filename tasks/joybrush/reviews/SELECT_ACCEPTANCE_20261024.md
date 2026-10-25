# Select integrated acceptance — October 24

## Exact candidate and baseline

- Frozen by Coordinator: `79f2d4dba49f5bf93f9f950eff1bc297a7828234`, last executable change `9805dbff6`.
- Candidate includes the clipboard/history delivery, C2 menu/reveal/caption repairs, context-preview cleanup
  `d8ddf2ebe`, and interactive mask admission. Candidate is not on main and is not yet installed by this lane.
- Owner authorized RAM and Note 9 testing. Watercolor also received a reservation and acquired the shared build
  slot first; take the next explicitly released bounded slot without interrupting its gestures or installs.
- Read-only baseline: Note 9 connected, 100% battery, 23.3–23.4°C, rotation lock value `0`.
  Installed package last updated October 24 at 15:31:22, SHA256
  `da2104ee437b1a686c28f11a0aded19ec85228391e811f3b8c3c849636a30fc3`.
  This hash identifies the pre-test app only; it proves none of the candidate's changes.
- Coordinator paused its proved automatic-install wrapper for these reserved test windows. It owns restoration.

## Execution ledger

No pass is recorded until the command closes successfully and fresh result XML is counted independently.
Do not edit the frozen checkout during an admitted build. Admission thresholds: 2.2 GB focused,
2.8 GB full suites, 3.2 GB APK; shared `jb-gradle.lock` throughout.

| Check | State | Evidence |
|---|---|---|
| Focused `SelectionMaskAdmissionTest` and `GlContextLossTest` | PASS on `79f2d4dba` | 6 + 8 tests; zero failures/errors/skips; closed 4m11s log; Coordinator independently counted testcase nodes |
| Full standalone `:core:jvmTest :androidkit:test` | Pending | Fresh XML totals, failures/errors/skips and closed log owed |
| App `*Select*`, including host/history and new menu/probe tests; Kotlin compile | Fixture compile failure on `24ba90ea9`; retry owed | Production compiled; missing test runner import stopped test compilation after 1m07s; no behavioral tests executed. One-line fixture fix `28e45e7ef`. |
| Assemble Default Debug | Pending | Exact source, APK hash and build log owed |
| Install Note 9 with `-r` | Pending | Home first; compare installed hash/update time; no data clearing |

## Phone ledger

Use a new disposable drawing, preserve the owner's existing drawings, and record temperature during the session.
Each gesture starts from an inspected screenshot. ADB-injected stylus/finger checks are labelled as such;
they do not establish physical S Pen pressure, tilt, palm rejection or the owner's aesthetic approval.

| Check | State | Evidence owed |
|---|---|---|
| D2 on white/dark paper; Paste absent/present; lock-option reveal and persistence | Pending | Screenshots and action results |
| D2/More in short windows and with larger text; scroll to final control; outside dismissal | Pending | Fit/reachability screenshots; restore any test-only window/text setting |
| Copy/Paste in current drawing and another drawing; fresh selection; Undo/Redo | Pending | Before/after/undo/redo screenshots and line metadata where available |
| Lock/Unlock, Group/Ungroup, Invert and locked picking | Pending | One-step metadata operations; exact layer scope; save/reopen retention |
| HSB preview, cancellation, release and Undo with different starting colours | Pending | Drawing and history stay consistent; relative colours retained |
| Move/Undo/Redo keeps and refits selection; Delete/Undo restores it | Pending | Selected identities and box before/after |
| Layer Select all, scoped Rasterize Down, Flatten Lower/Undo | Pending | Layers above preserved; result and restored line state |
| Nudge ghost before/mid/after, two zooms and crossing lines | Pending | Stationary original at 20%; release/cancel clears it |
| Colour-dot drag recolour; return/cancel; ordinary eyedropper | Pending | One successful edit, no cancelled edit |
| Save/reopen, first edit, drawing switch cannot revive old selection | Pending | Persisted lock/group and active line editing |
| Real pen/button/thumb routing and owner look | Open | Cannot infer these from injected events or host tests |

A9 Slice, A10/L3 badges/toasts, ring Bend/L4, C3 veil and Phase B design approval remain separate open gates.
No acceptance is inferred from the preparatory baseline or from another lane's APK.

Owner's updated instruction: read A10/K25 from `origin/ui/select-accept` (read at `43d8588ef`) and run
`tasks/joybrush/ui-scripts/select_bench.sh <out>` after every step. Its exact source/script must be in the candidate;
it opens an unsaved second Joy Brush screen, drives synthetic S Pen/window events, records model JSON and captures
requested PNGs. Bench evidence is currently owed for this integrated delivery, and does not substitute for new
A1–A10 assertions: extend the bench for each new feature. Preserve the phone lock and current watercolor handoff.

## Additional source findings and probes

Tests-only `f6331f3ed`, corrections `cef0039f4` and `28e45e7ef`, exercise the actual Controller/SeekBar lifecycle,
real host/tool callbacks after a drawing switch, and Activity's actual copy callback with overlapping layer records.
No production fix is inferred from these test additions, and they have not yet executed successfully.

- HSB `onStopTrackingTouch` does not currently distinguish cancellation; dismissing an unfinished preview also has
  no explicit rollback. Probe cancellation/dismiss/old-UP-after-Open against a successful one-step UP control.
- A pending host callback lacks its originating drawing epoch. Probe old success/refusal while drawing B has both
  a pending edit and an unfinished drag, requiring no new UI notice, redraw, refit or lost pending edit.
- Activity globally sorts copied records by `seq`, but sequences are local to physical planes. Probe identical
  opaque red-under/blue-over lines with lower seq100 and upper seq1 through actual Copy and Paste.
- **A1 frame/region fidelity is BLOCKED.** Engine commit keeps a whole raw recipe in each written physical plane,
  relying on that plane's clipping domain. Copying record/brush alone into one unbounded plane may duplicate full
  recipes and reveal clipped portions. Destination-cel tests do not establish source-domain preservation.
  Minimum safe admission must prove the exact source addresses/records and unbounded ownership on the GL side,
  tied to drawing epoch/document generation, and carry immutable proof with the copied payload. UI board metadata
  alone may be stale. Unknown/clipped source domains must refuse until a domain-preserving adapter exists.

The integration checkout remains frozen during every admitted build. Production changes require deliberate
Coordinator unfreeze after precise fixture/behavior evidence; Lead-owned bridges remain separate patch commits.
