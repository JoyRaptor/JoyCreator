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
| Focused `SelectionMaskAdmissionTest` and `GlContextLossTest` | Pending | New core mask and backend context-preview regressions |
| Full standalone `:core:jvmTest :androidkit:test` | Pending | Fresh XML totals, failures/errors/skips and closed log owed |
| App `*Select*`, including host/history and new menu tests; Kotlin compile | Pending | Fresh XML and closed log owed |
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
