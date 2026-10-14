# Privacy Policy — Joy Creator

> **DRAFT 2026-10-14, written from an audit of the code. Awaiting the owner's review before it is published.**
> Two blanks to fill: the contact email (roadmap 0.3) and the publish date. The Play build removes the features
> marked *full build only* (roadmap 1.4); if that changes, this page changes with it.

**Last updated:** [date of publishing]

Joy Creator is a creative studio for your phone: drawing and animation (JoyBrush), video and audio editing
(Studio), sprites, avatars and visualisers. It is made by an independent artist. This page says plainly what
stays on your phone and what, if anything, leaves it.

## The short version

- **Your work stays on your phone**, in the Joy Creator folder you choose. Removing the app never deletes that folder.
- **No account is needed.** No ads. No analytics. No tracking. Nothing about you is sold or shared.
- Something leaves your phone **only when you use a feature that needs the internet**, listed below.

## What stays on your phone

Projects, drawings, recordings, exports, pictures you bring in, and your settings. Projects and drawings are kept in
your Joy Creator folder (a folder you pick); the app also keeps working copies and caches in its own storage, which
Android deletes when the app is removed. Joy Creator never uploads your work on its own.

## What can leave your phone, and when

| When you… | What is sent | Where |
|---|---|---|
| **Talk to Joybot, the AI helper** | Your message, and what the request needs from the open project: its outline (clip names, lengths, layers), transcripts, and any picture or video frame you attach | The AI service shown in Joybot's settings: OpenRouter (openrouter.ai) by default, or a service address you enter. You use your own key; that service's privacy policy covers what it does with the request. Your key is stored encrypted on your phone. |
| **Transcribe speech for the first time** | Nothing about you: the app downloads a speech model | alphacephei.com (Vosk) or huggingface.co (Whisper). Transcribing itself runs entirely on your phone. |
| **Turn on location in recordings** (off by default) | Your location, to look up a place name | OpenStreetMap's Nominatim service (nominatim.openstreetmap.org) |
| **Turn on weather in the watermark** (off by default) | Your approximate location, to get the weather | Open-Meteo (api.open-meteo.com) |
| **Check for updates** | An ordinary web request, no personal data | GitHub (github.com) |
| *Full build only:* **use Remote, live streaming or a cloud account** | Your stream and account details, if you sign in or start a stream | Servers run by the FadCam project (fadseclab.com). Not part of the Google Play build. |

Joy Creator does not use advertising, analytics, crash-reporting or tracking services.

## Permissions

Joy Creator asks for a permission only to do the thing it is named for: the **camera** and **microphone** to
record, **location** only if you turn on location tags, **notifications** to show recording and export progress,
and access to **the folder you choose** (and media you pick) so your work can be saved and opened there.

## Your control

- Everything Joy Creator makes is a file in your folder: open it, move it, back it up or delete it with any file
  manager. Deleted items go to the app's Trash first (Settings → Storage Location → Recently deleted).
- Removing the app deletes its own storage, never your Joy Creator folder.
- To stop anything leaving your phone: don't add an AI key or service address, and leave location and weather off.

## Children

Joy Creator is not directed at children under 13 and does not knowingly collect information from them.

## Changes

If this policy changes, the new version will be posted here with a new date.

## Contact

[developer email]

---

Joy Creator is built on FadCam, which is free software under the GNU GPL v3. "Joy Creator" and "JoyBrush" are the
owner's marks.
