# BOT-6: Bring your own AI: send Joybot into Claude Code or Codex

**Milestone:** M1 (the owner wants Joy Creator to lead here, not follow)  ·  **State:** 🔨 design  ·  **Owner:** "JoyBot UX vision and capabilities" (Claude, account C)  ·  **Checked:** 2026-10-28

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
| Codex | MCP `url` servers in `config.toml` | `codex exec` (to confirm) | developers.openai.com/codex/mcp |
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

## What is left
- [x] Facts for Claude Code (10-28, docs; see the table). Still to confirm: `codex exec` and its terms,
      OpenCode's remote MCP form, a Hermes "talk" door.
- [ ] Prove plain-http MCP from Claude Code to the Note 9 on the local network (a 20-line test server first).
- [ ] Owner: ask Anthropic for written OK before Joybot starts a person's Claude Code (S17).
- [ ] Spec with the pairing screens as a mix-and-match design page (the owner picks).
- [ ] Build the phone-side server (pure-Java protocol core + tests), then pairing, then the chat relay.
- [ ] Phone + PC test with Claude Code and with Codex; STATE row; help topic.

## Files and traps
- New code in `ai/bridge/` (small files). Reuses `ai/actions/*` (registry), `AIToolExecutor`, `JoybotStorage`.
- Never expose the server beyond the local network; never without the pairing token; off by default.
- Never take over the person's Claude or ChatGPT login inside our app (against those services' terms).
