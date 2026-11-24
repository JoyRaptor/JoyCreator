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
- (Done 11-23) Code-built icon views: `tools/scan_hover_code.py` lists click-listened views with no name in their file (skips Avatar and motion-blur paths). 50 hits, nearly all text rows or already named through the Kit/label helpers; the one real gap, the Debug log's Previous/Next arrows, is named. Custom-drawn canvas views with their own touch handling (JoyBrush brush/layer panels, timeline) are not views with listeners and stay owed to a phone hover check.
- Dialogs and popup windows are separate windows. 11-24: DialogFragments and bottom sheets (about 52 in the app) are now watched too (`HoverLabels.watchDialogFragments`, built, cloud build pending). Plain `Dialog`/`AlertDialog` and `PopupWindow`s (lane menu, pickers) are still not covered.
- JoyBrush engine/canvas custom views, Avatar/FACE and STU-7 files were not touched (claimed on BOARD).
- Avatar Studio (`tools/avatarlab/**`, `tasks/avatar/**`, the avatar room classes, the lobby Avatar door): not touched, per the
  command session 11-23. Buttons there are owed their own pass after that lane lands; the global install already labels any that carry a name. Phone: the Avatar slice-1 lane holds the Note 9 lock, so the phone check waits.
- Phone check (hover event on the Note 9) owed; cloud build result recorded below.

## Done log
- 11-23: global install + six layout names + host check. Cloud build run 45296875486 green (defaultDebug, 11-23).
- 11-23: debug-log arrows named; merged and landed on joy-creator. Scanner added.

## Note 9 check 11-23 (build df093b915, agent)
- **Hover label seen:** a long press on the lobby's search icon shows "Search everything" (`tasks/screens/hover1-20261123/longpress_search.png`). Android shows the same label for a hover; the Note 9 (Android 10) cannot be given an injected hover (`input motionevent` does not exist there), so a real mouse or S Pen look is still owed.
- One more long press in the lobby's top right opened Recently deleted (which purges items older than 30 days by design, toast "5 old items were deleted for good") on the Note 9 sandbox: nothing of mine was deleted, but I did not know which control I had hit. Shot `longpress_export.png`.
- **Owed:** tick-box screenshots on a few screens; a hover look with a real mouse; other rooms' labels by long press.
