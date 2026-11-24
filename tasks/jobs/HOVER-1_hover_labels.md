# HOVER-1: every button gets a hover label (owner rule; queue job 1)

Branch `claude/hover-labels-8c53af`. Parent: [BOT-2](BOT-2_joybot_everywhere.md).

## How it is done (one way)
`ui/faditor/util/HoverLabels`: a tappable view with a spoken name (content description) and no hover label gets that name
as its hover label (Android 8+). It was switched on in two rooms only (Studio, SpriteLab). **Now
`HoverLabels.installEverywhere(app)` runs from `FadCamApplication.onCreate`**, so every activity in the process
(lobby, Studio, JoyBrush, VisLab, TextLab, SpriteLab, Library, Capture, Settings, Joybot chat, Avatar) is covered, and
views built later (drawers, fragments) are covered by the throttled layout pass.

## Inventory (layouts, by script `tools/check_hover_labels.py`)
Clickable image buttons with no name at all were six: player back, watch-player play/pause, and the close X of the
home, lab, records sidebars and the mini-app sheet. All six now have a name (strings `close`, `back`,
`watch_play_pause_cd`). The script now reports 0 and fails the build step if one comes back.

## Owed (not yet done)
- Views built in code that carry no content description (custom-drawn icon views, glyph ligature labels): need a one-by-one
  list per room. A rough count shows ~35 files create image views in code; most already name them.
- Dialogs and popup windows are separate windows: `HoverLabels` only watches an activity's own window.
- JoyBrush engine/canvas custom views, Avatar/FACE and STU-7 files were not touched (claimed on BOARD).
- Avatar Studio (`tools/avatarlab/**`, `tasks/avatar/**`, the avatar room classes, the lobby Avatar door): not touched, per the
  command session 11-23. Buttons there are owed their own pass after that lane lands; the global install already labels any that carry a name. Phone: the Avatar slice-1 lane holds the Note 9 lock, so the phone check waits.
- Phone check (hover event on the Note 9) owed; cloud build result recorded below.

## Done log
- 11-23: global install + six layout names + host check. Cloud build run 45296875486 green (defaultDebug, 11-23).
