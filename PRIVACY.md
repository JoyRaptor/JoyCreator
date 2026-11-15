# Privacy Policy: Joy Creator

> Internal note (not published on the web page). Rewritten 2026-11-14 by the store-and-legal session from a read of
> the code; every claim below has its file and line in `tasks/store/DATA_SAFETY_DRAFT.md`. Not tested by watching
> the phone's network traffic. The contact address `studio@joycreator.cc` is a placeholder until the owner confirms
> that it receives mail. The Google Play build (job SHOW-1) removes the disguise icons, the accessibility service,
> all-files access and the privacy black screen; this page describes the app with or without them.

**Last updated:** 14 November 2026

Joy Creator is a creative studio for your phone: drawing and animation (JoyBrush), video and audio editing
(Studio), music visualisers (VisLab), title text looks (TextLab), sprites and characters (SpriteLab), camera and
screen recording, and Joybot, an AI helper. It is made by an independent artist. This page says plainly what stays
on your phone and what, if anything, leaves it.

## The short version

- **Your work stays on your phone**, in the Joy Creator folder you choose. Removing the app never deletes that folder.
- **No account is needed.** No ads. **No analytics, crash reporting or tracking of any kind.** Nothing about you is sold or shared.
- **Joy Creator has no servers of its own** and never receives your work, your messages or your location. (An earlier version of the code this app grew from could talk to its original author's servers; that part was removed from Joy Creator in October 2026.)
- Something leaves your phone **only when you use a feature that needs the internet**, and then it goes straight to the service named below, never through us.

## What stays on your phone

Projects, drawings, recordings, exports, pictures you bring in, looks and packs, and your settings. Projects and
drawings are kept in your Joy Creator folder (a folder you pick); the app also keeps working copies and caches in its own
storage, which Android deletes when the app is removed. Joy Creator never uploads your work on its own.

**Camera and microphone.** They are used only while you record, dictate or make a voice-over, or while you ask the music
visualiser to listen to the microphone. What they capture is saved on your phone. Nothing is streamed anywhere unless you
turn on Remote (below).

**Location in your videos.** Two switches, both **off by default**, use your location. "Embed location in videos" writes
your coordinates into the video file's own information, the way a camera does; they stay in the file, and **travel with
the video if you share it**, so turn it off before sharing something recorded at home. The place-name and weather options
for the on-video label are in the table below.

**Speech to text** (captions, dictation) runs entirely on your phone.

## What can leave your phone, and when

| When you... | What is sent | Where |
|---|---|---|
| **Talk to Joybot, the AI helper** | Your message, and what the request needs from the open project: its outline (clip names, lengths, layers), transcripts, and any picture or video frame you attach or ask Joybot to look at | The AI service you set up in Joybot: OpenRouter (openrouter.ai) by default, or OpenAI, Anthropic, Google, Groq, xAI, or a service address you enter. You use **your own key**; that service's own privacy policy covers what it does with the request, and its answers are its own, so check anything important. Your key is stored encrypted on your phone (Android Keystore). Joybot also asks the service for its list of models and checks your key; these carry no project content. |
| **Ask Joybot to make a picture** | Your description (and a picture, if you hand it one), plus a price check | OpenRouter's picture service, on your key. Joybot shows the price first. |
| **Transcribe speech for the first time** | Nothing about you: the app downloads a speech model | alphacephei.com (Vosk) or huggingface.co (Whisper). The transcribing itself runs on your phone. |
| **Turn on place names in recordings** (off by default) | Your location, to look up a place name | OpenStreetMap's Nominatim service (nominatim.openstreetmap.org) |
| **Turn on weather in the label** (off by default) | Your approximate location, to get the weather | Open-Meteo (api.open-meteo.com) |
| **Check for updates, or open What's New** | An ordinary web request, no personal data | GitHub (github.com and raw.githubusercontent.com): the public release feed and change list. What's New's page also loads a font-icon file from cdnjs.cloudflare.com. |
| **Open Remote's network health check** | A tiny request to test your connection | google.com and github.com (an icon file from each). No personal data is attached. |
| **Use Remote over your own Wi-Fi** (live view and control from another device on your network) | Your camera or screen stream, only to devices on the same network that you let in. It is not encrypted (plain http on your own network), so use it on a network you trust. | Your own network; not through us. The control page that opens in the other device's browser loads some page parts (icons, a video-player script, a date picker) from cdnjs.cloudflare.com and cdn.jsdelivr.net, so *that browser* contacts those two services. |
| **Connect your computer's AI to Joybot** ("bring your own AI": Claude Code, Codex or another MCP app) | What your computer's AI asks Joybot to do, and Joybot's answers (project facts, pictures it asks to see) | Directly between your phone and your computer, over your Wi-Fi or a USB cable, guarded by a code you confirm on the phone. Plain http on your own network. It does not go through us. It is off until you turn it on. |
| **Install a Joy pack** (a `.joypack` file with looks, fonts and more) | Nothing: the file is opened from your phone | Nowhere. Packs are read from a file you choose; the app does not fetch packs from the internet. |

Joy Creator does not use advertising, analytics, crash-reporting or tracking services, and contains no code from any.
Ordinary web requests, like any, show your IP address to the host that answers them (GitHub, OpenRouter and so on).

## Permissions

Joy Creator asks for a permission only to do the thing it is named for, at the moment you use the feature:

- **Camera** and **microphone**: to record, dictate, make a voice-over or listen for the visualiser.
- **Location**: only if you turn on location tags, place names or weather.
- **Notifications**: to show recording and export progress.
- **Display over other apps**: for the floating recording and visualiser controls you switch on yourself.
- **Photos and videos / the folder you choose**: so your work can be brought in, saved and opened.
- **Bluetooth (connect)**: to use a Bluetooth microphone or headset.
- **Biometrics**: only if you turn on the optional app lock; Android does the checking, the app never sees your fingerprint or face.
- **Battery optimisation**: the app may ask to be left alone so a long recording is not stopped.
- **Internet and network state**: for the features in the table above.

## Your control

- Everything Joy Creator makes is a file in your folder: open it, move it, back it up or delete it with any file manager. Deleted items go to the app's Trash first.
- Removing the app deletes its own storage, never your Joy Creator folder.
- **We hold none of your data**, so there is nothing for us to delete or send back. For anything a third-party service (such as your AI service) kept, ask that service.
- To stop anything leaving your phone: don't add an AI key or service address, leave location, weather and Remote off, and don't open What's New or check for updates.

## Children

Joy Creator is not directed at children under 13 and does not knowingly collect information from anyone, children included.

## Changes

If this policy changes, the new version is posted here with a new date.

## Contact

Questions about this policy: studio@joycreator.cc

---

Joy Creator is built on FadCam by FadSec Lab, which is free software under the GNU GPL v3. "Joy Creator" and
"JoyBrush" are the owner's marks (see [TRADEMARK.md](TRADEMARK.md)).
