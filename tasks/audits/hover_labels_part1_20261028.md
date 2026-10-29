# Hover-label sweep, part 1 (UI-1), 2026-10-28

Scope: Lobby, Library, Recently deleted, Settings hub, Remote. Helper reused: `SheetKit.label(view, text)`
(content description plus hover tooltip) and `TooltipCompat`; XML `android:tooltipText` where the view is in a layout.

## Changed

| Screen | Control | File:line | String | Note |
|---|---|---|---|---|
| Remote | Stream server switch (`streaming_toggle`) | res/layout/fragment_remote.xml:362 | hover_remote_server_toggle | had contentDescription, no tooltip |
| Remote | Password protection switch (`remote_auth_toggle`) | res/layout/fragment_remote.xml:968 | hover_remote_password_toggle | had contentDescription, no tooltip |
| Library | Search lens glyph in the dock pill | ui/library/LibraryDock.java:88 | none | decorative icon-font ligature announced "search" ahead of the field; now importantForAccessibility=no |
| Settings hub | Search lens glyph in the dock pill | ui/settingshub/SettingsSearchDock.java:66 | none | same as above |

New strings: `app/src/main/res/values/strings_hover_labels.xml` (3 strings).

Controls fixed per screen: Lobby 0, Library 0 (1 decorative glyph silenced), Recently deleted 0,
Settings hub 0 (1 decorative glyph silenced), Remote 3.

## Already fine (both tooltip and contentDescription, or visible text label)

Lobby: search, JoyBot, bot-line dismiss, New drawing card (all have both); hero, recents cards, library door,
New chips and floor cells carry a visible label and a contentDescription; wordmark, marquee words, hero buttons are visible text. ~10 controls.
Library: back, sort, view (SheetKit.label), search field, clear, Options (tune), Shared-in cycle, New project pill,
kind chips, cards (LibraryCardView content description plus tooltip), item-sheet rows. ~10.
Recently deleted: back, options, stop-selecting, restore, delete-forever, kind chips, cards. ~7.
Settings hub: back, search field, search clear, search pill, section chips, rows (SettingsRowView.label), section headers. ~7.
Remote: copy URL (both), Cloud account button (tooltip plus visible text), copy chevrons are importantForAccessibility=no,
progress rings have descriptions, rows are visible text. ~6.

## Deliberately skipped

- Rows, chips and buttons that already show their own visible text label with a contentDescription: no tooltip added (would repeat the text).
- Remote chevrons (`Remote.Chevron`): already importantForAccessibility=no.
- Remote lock icon in the E2E row, status icons with `contentDescription=@null`: decorative, left alone.
- Dialogs/sheets opened from Remote (`showSegmentsInfo`, `showNetworkHealth`, etc.) use shared picker/bottom-sheet classes outside this scope.
