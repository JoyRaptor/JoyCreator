# BOT-2: Joybot everywhere, and everything findable through him

**Milestone:** M1 (feeds M2)  ·  **State:** 🔨  ·  **Owner:** Joybot lane (Claude Opus 5.5, 11-13; was "JoyBot UX vision and capabilities")  ·  **Checked:** 2026-11-15 on `joy-creator` (BOT-A39: ideas 1,239, bench 65/65, flights 88, bridge 33, actions 43, prompt budget 50, item names 28; all pass)

## Why it matters (for the user)
Wherever you are, tapping Joybot should offer the right next things and answer "how do I…" for that room.
Today he is strong in Studio and VisLab, thin in JoyBrush, absent in Avatar, and his help barely covers
the newer wings. For a newcomer, a feature Joybot can't explain is a feature that doesn't exist.

## Done when
- [ ] Every room that has a Joybot button opens him with ideas that fit that room (phone-checked per room).
- [ ] His help answers a question about every feature in STATE (with "Take me there" or "Show me how").
- [x] Every Joybot control has a hover label. Checked from the code 11-24 (hover-1 lane, not on a phone): the chat activity and its model picker tabs name every control (`tab(a, text, tooltip)`, layout descriptions; `tools/check_hover_labels.py` reports 0 unnamed icons), and the Joybot button of every room is named (lobby `lobbyv2_joybot`, Studio `btn_ai_assistant` "AI Assistant", TextLab `textlab_hover_joybot`, VisLab `vl_room_joybot`, SpriteLab "Joybot"); every activity and bottom sheet gets the app-wide `HoverLabels` watch. Not covered: Avatar room (claimed), plain dialogs and pop-up windows. A hover look on a phone with a mouse is owed.

## Where it stands (verified 10-28)
Room ideas exist for Studio, Lobby, SpriteLab, Library and VisLab (`IdeaEngine.collect`). JoyBrush opens the
chat as room JOYBRUSH but has no ideas branch (BOT-1 fixes the wrong buttons; richer ideas are here). Avatar
has no Joybot entry. HelpBook has ~60 topics; VisLab is one "visualizer" topic and JoyBrush two (open the
wing, open a drawing): nothing on looks, Words, MIDI, the floating visualiser, brushes, layers, frames,
Select, symmetry, Send to Studio. Hover labels: ~220 controls done 10-15; the chat's own new controls since
(account switcher, bring-in card, plan and review cards) were not re-swept.

## What is left
- [ ] **Help for every STATE feature.** Pure Java, harness-tested (`IdeaEngineTest`).
  - [x] VisLab (8 topics: the room, building a look and parts, music links, Words, MIDI/.mod, the floating
        visualiser, looks in Studio, the Studio effects) and Director mode: 10-28, from STATE 5.5/6; 422/422.
  - [x] JoyBrush, 11 topics (11-04, from STATE 5.6/5.6b; sources checked to be in this tree): `joybrush_brushes`,
        `_lines`, `_select`, `_paper`, `_boards`, `_onion`, `_clips`, `_tile`, `_sprite_roll`, `_export` (incl. Send to
        Studio), `_symmetry`. STATE help links filled in. Still owed: **layer operations (Lock, Merge Down, Flatten,
        Freeze)**: they are in `JoyBrushActivity.kt`'s layer menu but STATE has no row for them (HYG-5 list), so no
        topic yet; frame tweens and watercolour editable lines (STATE: foundation only, not user-reachable).
  - [x] SpriteLab (3 more: `sprite_align`, `sprite_sheets`, `sprite_reorder`), Library (4: `library_find`,
        `library_projects`, `library_import`, `library_look`): 11-04. Space and Trash were already covered by `space`,
        `trash`, `trash_restore`; STATE row mapped.
  - [x] **11-15: Capture and Settings** (BOT-A6, BOT-7 lane): `capture_modes` (the recorder: Camera / Screen, Start,
        Pause, Switch camera, Torch, Home Menu), `capture_photo`, `capture_screenshot` (left out of the Play build, which
        has no Screenshot shortcut: `LaunchGate.PLAY_HIDDEN` `help_capture_screenshot`; any topic can now be hidden as
        `help_<id>`), `dual_camera`, `watermark`, `about`, `lobby_floor`; `remote_lan` names the pairing code under
        Access; `settings_find` names the hub's sections. Labels read from the strings and layouts. Ideas 1,239 (18 new
        question checks, 3 that other topics keep their words, the Play hide). **Asked on the Note 9 11-16 (BOT-A8)** with the 18 JoyBrush/Sprite Lab/Library topics and the TextLab, Settings and
        Remote ones: 37 questions, all right; Tips answer corrected; a how-to no longer starts the create questions, and
        mid-create a question lets go ([evidence](../joybot/evidence/HELP_A8_20261116.md)).
  - [x] **11-13: JoyBrush layer menu and the Studio rows STATE marked "Help: PENDING"** (BOT-A5): `joybrush_layers`
        (Lock, Merge down, Freeze layer, Flatten, Flatten Lower, Clip, Clear, Delete; STATE row `joybrush.layer-ops`
        added), `studio_marks` (bookmarks, regions, loop, region export, "ask me to drop a mark"), `studio_title_rename`,
        `studio_arrange_tools`, `studio_drawer_back`, `export_safety` (Cancel, too-full warning, "name 2"), and `silence`
        now names an audio clip's Cut gaps. Words read from the code (strings, `JoyBrushActivity.layerPanel`,
        `LayerColumnView`). Ideas 843/843 (18 new question checks). **Device ✅ 11-13 19:20 (agent, the test tablet, Tab S8 Ultra, offline helper, the installed 13:58 build):** "how do I lock a layer" and "how do I drop a bookmark" answer with the new topics and a Take me there ([1](../joybot/evidence/help_lock_layer_tablet_20261113.png), [2](../joybot/evidence/help_bookmarks_tablet_20261113.png)).
  - [x] **11-13 (BOT-A7): the 45 STATE rows marked help pending** (list: `tasks/audits/help_pending_20261113.md`).
        All mapped: 5 new topics (`library_item`, `vislab_words_look`, `joybrush_watercolour`, `joybrush_knife`,
        `name_layers`), `textlab_pack` rewritten (packs now reach VisLab and Studio), `library_find` names the
        Recordings row; 16 rows marked "not needed" with the reason (automatic safety, crash fixes, look-only polish);
        frame tweens kept out on purpose (not reachable yet); one owed: Link to a pin of a bent sprite (its
        chooser's words not read yet). The 7 JoyBrush board/paper rows already named topics (their "pending" is
        acceptance). Ideas 951/951. **Device ✅ 11-13 20:25 (agent, the test tablet, build a6cdd3ea4):** `textlab_pack`,
        `name_layers` and `joybrush_knife` answer as written ([evidence](../joybot/evidence/help_pack_name_knife_tablet_20261113.png));
        the other new topics not asked on a device.
  - [ ] Capture: STATE gives no entry point for photo/screenshot capture, dual camera, Remote (LAN streaming) or the
        watermark switch, so no topics (the record, record_screen, record_webcam, record_background topics stand).
        Settings hub v2, Home recorder, About: also no entry-point text in STATE. Needs the rows filled first (HYG-5).
  - [ ] JoyBrush ideas (IdeaEngine) unchanged: the four existing ones stand; the help topics are all "how do I",
        none is an action Joybot can run, so no `doIt` keys were added.
- [x] **JoyBrush ideas** that read the open drawing (11-13, audit BOT-A9): the chat reads `JoybotHands.describe()`
      (the model's own read; no JoyBrush file changed) into `joybot/DrawingFacts`. One layer: "A layer to colour on"
      first, above the named layer; a colour layer already there: not offered; hidden layers: "Show the hidden layer";
      no animation board: "Start an animation"; one frame: "Add 7 more frames"; several: "Next frame from this one"
      naming the frame. JoyBrush closed or unreadable: the four general buttons. "Send this to Studio" waits for a
      joybrush Send tool (BOT-A30, JoyBrush lead). IdeaEngineTest pins describe()'s format against JoybotHands.kt;
      ideas 989/989, bench 58/58. Not seen on a phone.
- [ ] **Avatar room**: a Joybot button and ideas (Avatar stays hidden for launch: decide with ANI-1).
- [x] **Hover-label sweep re-run 11-13** (audit BOT-A37) over every `setOnClickListener` in `ai/**` after the 11-12
      TextLab additions: clean (the two script hits, Send and Attach, are labelled a few lines below). The app-wide
      sweep stays with UI-1.
- [x] **Hover-label sweep** of the chat screen and every card it draws: checked 10-28 by script over every
      `setOnClickListener` in `ai/**` and the chat layout: all labelled (the header and input buttons in code,
      cards through `cardButton`); the new setup, tip and bridge buttons carry labels too.
- [x] **Empty-state copy** the owner drafted 9-09 (11-14, audit BOT-A10). Checked first: the no-AI greeting already
      said what works on the phone (seen on the tablet 11-13) but not "free, no account"; the connected greeting had
      none of his cost words. Now `joybot/Greeting`: offline adds "free and with no account"; connected says "Connected
      to <account> (<model>)", "Joy Creator never adds a markup and never sees your bill", and on a paid model "keep an
      eye on your usage" (free models are not nagged). Ideas 1028/1028, bench 63/63. Not yet seen on a device (the
      connected half needs an AI on the device).
- [x] **"What can I do here?"** (10-28): `joybot/WhatHere` lists the room's real actions from both registries
      in plain words (changes first, then reads; 14 then "and N more"); Studio also names what works with no AI;
      without an AI it says the list needs one. Online or off. Tests in `IdeaEngineTest`. **Phone ✅ 10-28**
      (agent, Note 9, Lobby: four app-wide actions; [evidence](../joybot/evidence/what_can_i_do_here_20261028.png)).
- [x] **"Did you know?" on every empty open** (owner, 10-28). Offline, no AI needed. `joybot/TipBook` holds 51
      tips, each a feature STATE marks built and on joy-creator (Studio, sound, VisLab, JoyBrush, SpriteLab,
      Capture, Library, Joybot himself); `joybot/TipUsage` leans them toward what this person makes (Library, last
      60 days, read at most daily) and where they open Joybot, with about one in four from a wing they haven't
      touched; no tip repeats until all are seen; nothing leaves the phone. The greeting carries the tip, a small
      "Take me there" sits at its right edge (hidden when the tip is about the room you're in), then the idea
      buttons. Tests in `IdeaEngineTest` (routes real, a drawer gets mostly JoyBrush but still others,
      no repeats). Typecheck, ideas 635/635, bench 53/53. **Phone ✅ 10-28** (agent, Note 9: a new tip each open,
      "Take me there" beside it; on a SpriteLab tip with no project it opened Library › Projects;
      [evidence](../joybot/evidence/tip_on_open_20261028.png)).
- [x] **Offline ends on hope** (owner, 10-28): "Your phone isn't online…" is followed by "But I'm still right
      here", what works offline, and a tip (`TipBook.offlineHope`). Not phone-tested (an agent may not switch the
      phone's Wi-Fi off): owner check.
- [x] **11-13 (BOT-A38): 11 new tips**, 62 in all: TextLab 4 (Edit in TextLab, Words in a look in VisLab, saved looks
      move in the Library, .joypack install), Library 3 (Recordings sub-filter, hold an item, lobby recents), JoyBrush 4
      (layer menu, Symmetry, Send to Studio into a project, Water detail Fast). Each from a STATE row on `joy-creator`
      and agent-checked on the Note 9; "Choose several" left out (its batch actions not run on a phone). Ideas 887/887.
- [ ] Later: grow the tip library as STATE grows (JoyBrush tips after [HYG-2](HYG-2_one_main_line.md); never a
      tip for a tool the person doesn't have).

## Specs, handoffs and evidence
- [Joybot animation: principles and toolkit](../joybot/JOYBOT_ANIMATION_PRINCIPLES.md) (11-20): how he moves and acts, wherever he appears.
- [Feature gaps](../joybot/FEATURE_GAPS_20261015.md) (M3, G2, G3), [INBOX 9-09](../INBOX.md) (empty-state copy).

## Files and traps
- `ai/joybot/HelpBook.java`, `IdeaEngine.java`, `ChatAssistantActivity.java`; JoyBrush's button lives in
  `JoyBrushActivity.kt` (JoyBrush lead's file: ask first). Its icon is stretched ~57% wide: the fix
  `ef5fcd84a` on `claude/joybot-icon-aspect-20261027` is the JoyBrush lead's to land.

## Checked 2026-11-24 (Sonnet lane, no phone; queue row 21)
- **Help for every STATE feature:** the "Help: pending" count in STATE went from 17 rows to 6: five rows were mapped (Joybot's own animation and arrival and the moving lobby heroes: not needed, automatic; the lobby stat line: `space`; editable-line watercolour: `joybrush_watercolour`, `joybrush_lines`; the board show: `send_to_studio` already named) and the rest are the Avatar rows (361-363) and face tracking (271), which stay pending on purpose while the Avatar room is hidden for launch.
- **Hover labels:** `HoverLabels.installEverywhere` (HOVER-1, `79cefb069`) names every view with a name and `tools/check_hover_labels.py` reports 0 unnamed; the Joybot controls are covered by it. A real mouse / S Pen hover on a phone is owed.
- **Every room opens him with fitting ideas:** needs the phone, room by room.

- **11-24 (website lane, BOT-A38 again):** six more "Did you know?" tips, each for a feature with a help topic and a STATE row: brush import, rulers and guides, pencils in any colour, the brush editor (JoyBrush), striking words (Studio), Joybot's tricks. 68 tips in all; `IdeaEngineTest` 1363 pass (the tip rules check length, route, wording and the wing mix). Tablet drawers and face pins got no tip on purpose (a wide screen only; the PC pilot only). Room ideas still wait on the phone: each room's idea buttons are phone-checked per room.

- **11-24 (website lane, room ideas):** VisLab and TextLab each had four ideas and showed three, so a person who used them saw nothing new. Added later ideas that appear only once the first four are used or hidden (the first screen is unchanged): VisLab "Try another look", "Keep it as mine", "Float it over my music app"; TextLab "Try another look", "What is this look made of?". Each asks for something the room's tools really do (`vislab_pick_look`, `vislab_save_as`, `vislab_float`, `textlab_pick_look`, `textlab_describe`) and needs a key (hidden offline). `IdeaEngineTest` 1372 pass (new cases pin that the later ideas wait, appear in order and hide offline). Library stays silent when it has nothing to offer on purpose (a test pins it). Avatar room: waits for the launch decision. Phone check per room still owed.

- **11-24 (JoyBrush later ideas):** with the drawing read, two later ideas appear once the first ones are used or hidden: "A layer for shading" (only when a colour layer exists and no layer is called Shading) and "Pick a brush for me" (`joybrush_brush`). `IdeaEngineTest` 1377 pass. **Other rooms, looked at:** Studio's idea tree is already large and rotates by what the project has; Sprite Lab has name-the-cells, align and reorder; Library is silent when it has nothing to offer (a test pins that on purpose); **Capture has no Joybot button and no `IdeaEngine.Room`**, so there is nowhere for ideas to open from (its help topics and `CaptureRules` exist). Giving Capture a button is a UI change that needs a phone.
