# BOT-2: Joybot everywhere, and everything findable through him

**Milestone:** M1 (feeds M2)  ·  **State:** 🔨  ·  **Owner:** Joybot lane (Claude Opus 5.5, 11-13; was "JoyBot UX vision and capabilities")  ·  **Checked:** 2026-11-13

## Why it matters (for the user)
Wherever you are, tapping Joybot should offer the right next things and answer "how do I…" for that room.
Today he is strong in Studio and VisLab, thin in JoyBrush, absent in Avatar, and his help barely covers
the newer wings. For a newcomer, a feature Joybot can't explain is a feature that doesn't exist.

## Done when
- [ ] Every room that has a Joybot button opens him with ideas that fit that room (phone-checked per room).
- [ ] His help answers a question about every feature in STATE (with "Take me there" or "Show me how").
- [ ] Every Joybot control has a hover label.

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
  - [x] **11-13: JoyBrush layer menu and the Studio rows STATE marked "Help: PENDING"** (BOT-A5): `joybrush_layers`
        (Lock, Merge down, Freeze layer, Flatten, Flatten Lower, Clip, Clear, Delete; STATE row `joybrush.layer-ops`
        added), `studio_marks` (bookmarks, regions, loop, region export, "ask me to drop a mark"), `studio_title_rename`,
        `studio_arrange_tools`, `studio_drawer_back`, `export_safety` (Cancel, too-full warning, "name 2"), and `silence`
        now names an audio clip's Cut gaps. Words read from the code (strings, `JoyBrushActivity.layerPanel`,
        `LayerColumnView`). Ideas 843/843 (18 new question checks). Not yet asked in the app on a phone.
  - [x] **11-13 (BOT-A7): the 45 STATE rows marked help pending** (list: `tasks/audits/help_pending_20261113.md`).
        All mapped: 5 new topics (`library_item`, `vislab_words_look`, `joybrush_watercolour`, `joybrush_knife`,
        `name_layers`), `textlab_pack` rewritten (packs now reach VisLab and Studio), `library_find` names the
        Recordings row; 16 rows marked "not needed" with the reason (automatic safety, crash fixes, look-only polish);
        frame tweens kept out on purpose (not reachable yet); one owed: Link to a pin of a bent sprite (its
        chooser's words not read yet). The 7 JoyBrush board/paper rows already named topics (their "pending" is
        acceptance). Ideas 951/951. Not asked on a phone.
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
- [ ] **Empty-state copy** the owner drafted 9-09 (no key: what Joybot does free; key: "no markup, watch
      your usage"). Check what the chat shows today first.
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
- [Feature gaps](../joybot/FEATURE_GAPS_20261015.md) (M3, G2, G3), [INBOX 9-09](../INBOX.md) (empty-state copy).

## Files and traps
- `ai/joybot/HelpBook.java`, `IdeaEngine.java`, `ChatAssistantActivity.java`; JoyBrush's button lives in
  `JoyBrushActivity.kt` (JoyBrush lead's file: ask first). Its icon is stretched ~57% wide: the fix
  `ef5fcd84a` on `claude/joybot-icon-aspect-20261027` is the JoyBrush lead's to land.
