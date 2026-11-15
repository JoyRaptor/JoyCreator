# BOT-7 on the Note 9, 11-15 (agent check, not owner-accepted)

Build `ec88011f0` (joy-creator 078ea1024 + BOT-7), installed 17:06. Joybot connected to OpenRouter (free models).

| Shot | What it shows |
|---|---|
| after_01 | Joybot opened from the lobby: his own purple, the four buttons, purple send arrow, Take me there in the Library's blue |
| after_02 | Create something: one bubble per kind, icons (placeholders), each in the colours of the room it lands in |
| after_03 | "new blank studio project 3.1" typed: lands in "Untitled 3:1"; FIRST open showed no picture (fixed in bacc368ed: the title card is drawn before the project opens; re-check below) |
| after_04 | The same project reopened: a 3:1 picture, the title card on the timeline (rendered 1920×640) |
| after_05 | "make a transition" in the lobby with an AI: "There's no project open right now… Want me to open your last one?" Nothing changed (the Open it button for this case added in bacc368ed) |
| after_06/07 | Hand animation: the questions in JoyBrush's colours, buttons + Type your own |
| after_08 | Landed: JoyBrush, a new drawing with an Animation board 756×425 (16:9) |
| after_09/10 | Show me around: each room's stop in its colours; Take me there in the destination's colours |

Also seen: "tell me a joke" → one of the 66, with Another one. Found and fixed: restored chat messages lost their room
colour (bacc368ed). The phone's temperature log for the run is in the lane's scratchpad (no throttling seen).
