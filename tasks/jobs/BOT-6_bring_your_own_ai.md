# BOT-6: Bring your own AI: send Joybot into Claude Code or Codex

**Milestone:** M1 (the owner wants Joy Creator to lead here, not follow)  ·  **State:** 🔨 built, phone check next  ·  **Owner:** "JoyBot UX vision and capabilities" (Claude, account C)  ·  **Checked:** 2026-10-28

## Why it matters (for the user)
People already pay $20 a month for Claude or ChatGPT. With this, they plug that subscription into Joy
Creator in a minute: their Claude Code or Codex gets every Joybot tool (edit the video, change a VisLab look,
draw in JoyBrush, look at the finished frame), and they can talk to it from Joybot's own chat or from their
computer. No API key, no extra cost to them or to us. Descript, DaVinci Resolve and OpusClip have opened
similar doors in 2026; Joy Creator's harness is already built for it.

## The owner's rules (10-28)
- **Nothing runs on our side.** No website, relay or server we make or keep up. Everything stays between the
  person's phone, their own computer and their own subscription, under their control.
- **Wi-Fi or a USB cable**, phone ↔ their computer.
- **iPhone-simple.** Pairing is a code and a stylish QR: the aqua-to-lime gradient across the code, a punched-out
  centre with Joybot's face in the same gradient. It should feel like sending Joybot into Claude or Codex.
- **Both ways if at all possible:** talk to Claude Code through Joybot, or let Joybot hand Claude Code a task,
  or talk to Claude Code directly; the person picks.
- **Build toward the desktop:** Joy Creator on PC and Mac later, all of them talking to each other. The bridge's
  protocol must not be Android-only.

## Done when
- [ ] On the Note 9 and a PC on the same Wi-Fi: pair in under a minute with no typing beyond one paste.
- [ ] Claude Code (Pro/Max login, no API key) and Codex (ChatGPT login) each run a real request through the
      bridge: one undo per request, locked items stay locked, deletes ask the person on the phone.
- [ ] A message typed in Joybot reaches the paired Claude Code and its answer comes back into Joybot's chat
      (or the card says plainly why that half isn't possible yet).
- [ ] STATE row + help topic; nothing of ours running anywhere.

## Design (draft, facts being checked 10-28)
- **The plug is MCP** (Model Context Protocol), which Claude Code, Claude desktop and Codex all speak. The phone
  runs a small MCP server inside Joy Creator, off until the person turns it on, on the local network only, with a
  pairing token. Its tools are Joybot's action registry as it is (typed schemas already = MCP tools), the older
  tools, `look` (pictures), and a read-only summary of where the person is.
- **The app keeps the rules, not the AI:** the same host checks Joybot uses (one undo per request, locks,
  `PersonSaid` / a confirm card on the phone for deletes and paid things). Every step appears in Joybot's chat as
  "Claude Code: …", with a Stop that cuts the connection.
- **Pairing:** Joybot › "Connect Claude Code or Codex" shows the QR (owner's design) and a short code. Open
  question: a PC rarely has a camera, so for the PC the code and one copyable line are the main path; the QR
  suits phone-to-phone and the future desktop app (webcam). A free Claude Code plugin in a GitHub repo could make
  it "tell Claude Code: pair with Joy Creator 482-913" (a repo is not a server; nothing to keep running).
- **Talking to Claude Code from Joybot** needs something on the computer that can start a Claude Code turn
  (headless `claude -p`, `codex exec`, or a push feature if Claude Code has one). Being checked.
- **USB:** the same server over a cable (Android's port forwarding), for people without shared Wi-Fi.

## Any harness, not one (owner, 10-28)
The bridge is harness-neutral: the **tools half is plain MCP** (any MCP client can use Joy Creator), and the
**talk half has one small adapter per harness**. Each harness brings its own models, login and terms; Joy Creator
never handles a person's AI login.

| Harness | Tools half (uses Joy Creator) | Talk half (Joybot sends it a message) | Source |
|---|---|---|---|
| Claude Code | ✅ MCP over streamable http with a header token (`claude mcp add --transport http joycreator http://<phone>:<port>/mcp --header "Authorization: Bearer <token>"`); pictures in tool results are seen inline. Plain `http://` on the local network is not documented either way: **test it** | **Remote Control** (Pro/Max): the person chats from the Claude phone app or claude.ai/code, Claude Code runs on their PC with Joy Creator's tools; relayed by Anthropic, not by us. Headless `claude -p` works on a subscription for the person's own use, but Anthropic's terms say third-party apps may not "route requests through Free, Pro, or Max plan credentials on behalf of their users": **Joybot starting the person's Claude Code needs Anthropic's written OK first** (S17). "Channels" (push into a session) is a research preview; custom ones are blocked outside testing | code.claude.com/docs: mcp, remote-control, headless, channels, legal-and-compliance (10-28) |
| Codex | ✅ `codex mcp add joycreator --url http://<phone>:<port>/mcp` (or a `[mcp_servers.joycreator]` table with `url` in `~/.codex/config.toml`); a token via `bearer_token_env_var`; needs Codex CLI 0.46 or later; the ChatGPT desktop app and IDE extension share this config | `codex exec` (to confirm, with its terms) | developers.openai.com/codex/mcp (10-28) |
| OpenCode (power option) | MCP (to confirm the remote form) | **`opencode serve`**: a local HTTP server; `POST /session`, then `/session/:id/prompt_async`; password via `OPENCODE_SERVER_PASSWORD`; OpenAPI at `/doc` | opencode.ai/docs/server |
| Hermes Agent (power option) | MCP client: stdio and remote http servers in `~/.hermes/config.yaml` | to find | hermes-agent.nousresearch.com/docs/user-guide/features/mcp |
| Claude desktop / ChatGPT desktop | local MCP (to confirm) | the person types there | |

Order: MCP tools half first (every harness gets it at once), then the talk adapters, simplest first
(OpenCode's server API is the most direct). For Claude Code the talk half is Remote Control (the person's own
Claude app) until Anthropic says yes to anything more.

**Facts checked 10-28 (Claude Code docs):** sampling is not supported (so Joybot's chat can't borrow the
person's Claude); the Claude phone app's custom connectors must be reachable from the public internet, so a phone
on home Wi-Fi can't be one (that's why the computer is in the middle); no install link can add an MCP server,
but a Claude Code plugin (from a GitHub marketplace: a repo, not a server) can bundle the config.

**Safety (from the same check):** a token sent over plain http on shared Wi-Fi can be read by others on that
network. So: pairing only while the screen is open, a fresh token per pairing, only the paired computer's
address accepted, Stop ends it; USB (`adb reverse`, the PC reaches the phone at localhost) for anyone on a shared
network. Android kills background servers, so the bridge runs as a foreground service with its notification.

## Setting it up by talking to Joybot (owner, 10-28)
"So simple my grandma can use it." Connecting any AI (an API key, OpenRouter's free one, or Claude Code / Codex
on a computer) is a conversation, not a settings page; ⚙ stays for power users.
- Joybot asks: do you already pay for an AI? Which one (Claude, ChatGPT, something else, none)? Then explains the
  choices in good news first: free (OpenRouter's free models: slower, fine for most things), your own key (pay as
  you go, no markup from us), or your $20 plan through your computer (no key, uses what you already pay for).
- **A key typed in the chat is caught on the phone before anything is sent**: it never reaches the AI, never
  enters the conversation history, the message log, the bench or any file; it is sealed like `AiKey` and the
  bubble shows only "key ending …1234 saved". Paste-detection by the providers' key shapes (`sk-or-`, `sk-ant-`,
  `sk-`), plus "here's my key". Tested off-device with fake keys only.
- Stage by stage with a check at each: key works (one free call) → model picked → "try asking me something".
- Never takes over the person's Claude or ChatGPT login in our app; for a $20 plan he walks them through their
  own computer instead (below).

## The $20 plans: how people really connect (checked 10-28; these products change monthly, re-check before relying)
| Plan | What they install | Sign in | Joy Creator's part |
|---|---|---|---|
| Claude Pro / Max | Claude Code on their computer | run `claude`, sign in with their claude.ai account in the browser (`/login`, `/status` to check; unset `ANTHROPIC_API_KEY` if set) | one `claude mcp add …` line (or a plugin) from the pairing screen. To talk from the phone: `claude remote-control` (`claude rc`) shows a QR to scan in the Claude app's Code tab: research preview, the docs say all plans; Team/Enterprise need an admin toggle |
| ChatGPT Plus / Pro | Codex CLI (or the ChatGPT desktop app / IDE extension) | run `codex`, choose "Sign in with ChatGPT" | one `codex mcp add …` line from the pairing screen |
Sources: code.claude.com/docs/en/remote-control, developers.openai.com Codex MCP docs, 10-28. Free Claude plans
don't include Claude Code.

## Tablets (owner, 10-28)
Tablets have cameras, so **phone ↔ tablet** pairing is the QR's best use (Joy Creator on both: the tablet scans
the phone's code); the PC path stays code + one copyable line until the desktop app has a webcam path.

## What is left
- [x] Facts for Claude Code (10-28, docs; see the table). Still to confirm: `codex exec` and its terms,
      OpenCode's remote MCP form, a Hermes "talk" door.
- [x] **Tools half built (10-28, owner away: "build everything").** `ai/bridge/`: `McpCore` (MCP JSON-RPC:
      initialize, ping, tools/list, tools/call; pictures from `look` as image content), `BridgeAuth` (a six-digit
      code kept between openings so a set-up computer just reconnects; "New code" resets it; only the first
      computer that uses it; locked after 8 wrong codes; any request with an Origin header, i.e. a web page, is
      refused), `BridgeServer` (NanoHTTPD, POST `/mcp`, JSON answers, 202 for notifications), `BridgeService`
      (foreground, notification with Stop; never restarts by itself), `BridgeActivity` ("Connect your computer":
      Open the door, the owner's QR (aqua-to-lime dots, rounded corners, punched-out centre with Joybot's face),
      the code, "Say this to Claude Code" with Copy and Send to my computer, "Do it yourself" commands for Claude
      Code, Codex, any MCP app and USB, a "What happened" list, Stop, New code), `BridgeQr`, `BridgeWords`,
      `BridgeAddress`. Tools run through `JoybotAgent.Bridged`: Joybot's own host on his own worker; calls within
      30 s are one Undo; nothing from the computer counts as the person's yes (`PersonSaid`), so locks hold and
      deletes wait for a tap on the phone; `where_am_i` gives the open project; `propose_plan` is left out.
      Reached from the setup chat ("Connect my computer"), the help topic "bridge" and a tip.
- [x] **Proved on the PC** (10-28): `BridgeTest` 32/32 (protocol, pairing rules, words); `run-joybot-bench.sh
      --bridge` 19/19: the real server and Joybot's real tools over plain HTTP the way Claude Code calls them
      (initialize → tools/list (123 tools) → where_am_i → set_text_style changed the project → a second change in
      the same burst = still one undo step; wrong code 401, web page 403, a locked title refused and the computer
      can't unlock it). Found and fixed there: a refused request's unread body broke the next request on the same
      connection.
- [x] **Proved on the Note 9** (agent, 10-28, USB `adb forward`, the phone had no Wi-Fi): the screen saw no
      Wi-Fi and offered the cable; the QR, code and words drew as designed; Claude Code-style initialize → 124
      tools → `where_am_i` read the project → `set_text_style` changed a title (confirmed by `get_item`) → `look`
      sent the finished frame as a picture; wrong code 401, web page 403; the notification read "Connected to
      Claude Code · Stop ends it" and opened the screen; "New code" closed the door and retired the code
      ([evidence](../joybot/evidence/bridge_connected_20261028.png)). `add_marker` through the bridge found an editor
      undo gap (stays off; BOARD).
- [ ] A real Claude Code or Codex on the same Wi-Fi as the phone (the owner's own login; his call when to try).
- [ ] Owner: ask Anthropic for written OK before Joybot starts a person's Claude Code (S17).
- [ ] Spec with the pairing screens as a mix-and-match design page (the owner picks), including the
      conversational setup and the tablet QR.
- [x] Conversational setup (10-28): `KeyCatcher` (a key typed or pasted in the chat is caught before anything
      sees it; known shapes any time, any long key-like text right after Joybot asked; joined when a copy broke it
      across lines), `KeyCheck` (one free call before saving: works / wrong key / no credit / offline / service
      trouble), `SetupTalk` (the words), `SetupFlow` (the cards). The typing box goes password-style with no
      keyboard suggestions while it waits for a key. "Connect an AI" button on the greeting when none is
      connected; "connect an AI", "I have Claude Pro" and the like start it in words. Tests with fake keys only.
      **Phone ✅ 10-28** (agent, Note 9, offline at the time): "connect an AI" → the four choices → Claude's card
      with Connect my computer; an obviously fake OpenRouter-shaped key typed in the chat showed only as "key
      ending …cdef", the check said "couldn't reach OpenRouter… Nothing was saved", the account stayed as it
      was and the fake key appears nowhere in logcat ([evidence](../joybot/evidence/key_caught_offline_20261028.png)).
      Not tried: a real key end to end (an agent never types one).
- [ ] The talk half: Joybot's chat sending to the computer's AI (OpenCode's `opencode serve` first; Claude
      Code waits for S17; meanwhile Claude Code's own Remote Control lets the person talk from the Claude app).
- [ ] Bridged requests show in Joybot's chat as "Claude Code: …" only when the chat is open for that project;
      a Review card for a bridged burst is not built.
- [ ] Phone + PC test with Claude Code and with Codex. STATE row and help topic: done 10-28.

## Files and traps
- New code in `ai/bridge/` (small files). Reuses `ai/actions/*` (registry), `AIToolExecutor`, `JoybotStorage`.
- Never expose the server beyond the local network; never without the pairing token; off by default.
- Never take over the person's Claude or ChatGPT login inside our app (against those services' terms).
