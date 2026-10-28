# DOOR-4: Wings that hand work to each other

**Milestone:** M2  ·  **State:** ⬜ partly built  ·  **Owner:** open; each flow with the owning wing's lead  ·  **Checked:** 2026-10-27

## Why it matters
"Interconnected, interwoven": a drawing should become a video clip, a look should go on a timeline, a
character should be usable everywhere, and the Library should show all of it. Today some of these exist,
some are one-way, and some are missing.

## Done when
- [ ] Each flow below works in both directions where that makes sense, is phone-proven, and has a STATE row.

## Flows (state on 10-27)
| Flow | State |
|---|---|
| JoyBrush → Studio: "Send to Studio" | ✅ makes a new project; **into an existing project** is 🟡 built on `claude/send-to-studio-existing-20261027` (clean off `joy-creator`, pushed 10-27; rules test 16/16, receiver type-checked, no phone test): a chooser appears when projects exist, and the board lands as one more looping layer at half the canvas |
| Studio → JoyBrush (edit a frame or picture as a drawing) | ⬜ nothing flows back |
| JoyBrush boards → Studio / SpriteLab (board export, internal receiver) | 🟡 JoyBrush roadmap BOARD-RESIDUAL |
| VisLab looks in Studio's visualiser | ✅ 10-22 (with keyframable knobs) |
| VisLab effects as Studio effects | ✅ agent-tested 10-27 |
| Studio objects drive a VisLab look (R8) | 🔨 VisLab roadmap |
| SpriteLab sheets inside VisLab looks (R7) | 🟡 VisLab roadmap |
| Sprite sheets reusable across projects | ⬜ STATE §9 #13; [ANI-1](ANI-1_puppets_and_sprites.md) |
| Library shows every kind (projects, drawings, recordings, exports, looks, sheets, characters) | 🟡 drawings and projects yes; looks, sheets, characters: check |
| Joybot available in every room | 🟡 [BOT-1](BOT-1_joybot_owed.md) |
| Share-in to any wing | 🟡 into Studio; drawings and looks: check |

## What is left
- [ ] Confirm each row against the code (DOOR-1 will surface most of them).
- [ ] Land "Send to Studio into an existing project": build an APK from `claude/send-to-studio-existing-20261027`, check it on the Note 9 (send a board into an empty project, into one with a video, the same board twice, back out of the chooser), then land. It no longer waits for [HYG-2](HYG-2_one_main_line.md): the receiver is identical on both lines.
- [ ] One flow at a time, smallest gap first.
