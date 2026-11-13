# Privacy Policy — Joy Creator

> **DRAFT, revised 2026-11-13 from a read of the code, awaiting the owner's review before it is published.**
> Two blanks only the owner can fill: the **contact email** and the **publish date** (roadmap S4).
> The Google Play build is planned to remove the features marked *full build only* (job SHOW-1); if that changes,
> this page changes with it. Where a line below says "checked in the code", it was read from the source on
> 2026-11-13; it was not tested by watching the phone's network traffic.

**Last updated:** [DATE OF PUBLISHING — owner]

Joy Creator is a creative studio for your phone: drawing and animation (JoyBrush), video and audio editing
(Studio), music visualisers (VisLab), title text looks (TextLab), sprites and avatars (SpriteLab), recording, and
Joybot, an AI helper. It is made by an independent artist. This page says plainly what stays on your phone and
what, if anything, leaves it.

## The short version

- **Your work stays on your phone**, in the Joy Creator folder you choose. Removing the app never deletes that folder.
- **No account is needed.** No ads. No analytics, crash reporting or tracking. Nothing about you is sold or shared.
- **Joy Creator has no servers of its own** and does not send your work to us. (An earlier version of the code this app
  grew from could talk to its original author's servers; that part was removed from Joy Creator in October 2026.)
- Something leaves your phone **only when you use a feature that needs the internet**, listed below.

## What stays on your phone

Projects, drawings, recordings, exports, pictures you bring in, looks and packs, and your settings. Projects and
drawings are kept in your Joy Creator folder (a folder you pick); the app also keeps working copies and caches in
its own storage, which Android deletes when the app is removed. Joy Creator never uploads your work on its own.

## What can leave your phone, and when

| When you… | What is sent | Where |
|---|---|---|
| **Talk to Joybot, the AI helper** | Your message, and what the request needs from the open project: its outline (clip names, lengths, layers), transcripts, and any picture or video frame you attach or ask Joybot to look at | The AI service you set up in Joybot: OpenRouter (openrouter.ai) by default, or OpenAI, Anthropic, Google, Groq, xAI, or a service address you enter. You use **your own key**; that service's privacy policy covers what it does with the request. Your key is stored encrypted on your phone. |
| **Ask Joybot to make a picture** | Your description (and a picture, if you hand it one), plus the price check | OpenRouter's picture service, on your key. Joybot shows the price first. |
| **Transcribe speech for the first time** | Nothing about you: the app downloads a speech model | alphacephei.com (Vosk) or huggingface.co (Whisper). Transcribing itself runs entirely on your phone. |
| **Turn on location in recordings** (off by default) | Your location, to look up a place name | OpenStreetMap's Nominatim service (nominatim.openstreetmap.org) |
| **Turn on weather in the watermark** (off by default) | Your approximate location, to get the weather | Open-Meteo (api.open-meteo.com) |
| **Check for updates, or open What's New** | An ordinary web request, no personal data | GitHub (github.com and raw.githubusercontent.com): the public release feed and change list. What's New's page also loads a font-icon file from cdnjs.cloudflare.com. |
| **Open Remote's network health check** | A tiny request to test your connection | google.com and github.com (a small icon file each). No personal data is attached. |
| **Use Remote over your own Wi-Fi** (live view and control from another device on your network) | Your camera or screen stream, only to devices on the same network that you let in | Your own network; not through us. The control page that opens in the other device's browser loads some page parts (icons, a video-player script, a date picker) from cdnjs.cloudflare.com and cdn.jsdelivr.net, so *that browser* contacts those two services. |
| **Connect your computer's AI to Joybot** ("bring your own AI": Claude Code, Codex or another MCP app) | What your computer's AI asks Joybot to do, and Joybot's answers (project facts, pictures it asks to see) | Directly between your phone and your computer, over your Wi-Fi or a USB cable, guarded by a code you confirm on the phone. It does not go through us. It is off until you turn it on. |
| **Install a Joy pack** (a `.joypack` file with looks, fonts and more) | Nothing: the file is opened from your phone | Nowhere. Packs are read from a file you choose; the app does not fetch packs from the internet. |

Joy Creator does not use advertising, analytics, crash-reporting or tracking services.

## Permissions

Joy Creator asks for a permission only to do the thing it is named for: the **camera** and **microphone** to
record, **location** only if you turn on location tags, **notifications** to show recording and export progress,
**display over other apps** for the floating recording and visualiser controls, **battery optimisation exemption**
so a long recording is not stopped, and access to **the folder you choose** (and media you pick) so your work can
be saved and opened there. Android asks you at the moment you use each feature.

## Your control

- Everything Joy Creator makes is a file in your folder: open it, move it, back it up or delete it with any file
  manager. Deleted items go to the app's Trash first.
- Removing the app deletes its own storage, never your Joy Creator folder.
- To stop anything leaving your phone: don't add an AI key or service address, leave location, weather and Remote
  off, and don't open What's New or check for updates.

## Children

Joy Creator is not directed at children under 13 and does not knowingly collect information from them.

## Changes

If this policy changes, the new version will be posted here with a new date.

## Contact

[CONTACT EMAIL — owner; roadmap S4 suggests studio@joycreator.cc, confirm it receives mail]

---

Joy Creator is built on FadCam by FadSec Lab, which is free software under the GNU GPL v3. "Joy Creator" and
"JoyBrush" are the owner's marks (see [TRADEMARK.md](TRADEMARK.md)).
