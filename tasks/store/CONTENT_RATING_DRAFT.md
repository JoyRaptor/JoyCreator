# Play Console: content rating (IARC questionnaire) and the other app-content forms, draft answers

Written 2026-11-14 (SHOW-2). **Nothing was submitted.** Each answer cites the code or file it rests on (paths under
`app/src/main/` unless stated). Play's current questionnaire wording was not re-read: the questions below are the standard IARC
groups; match each to the live form. Companion drafts: [DATA_SAFETY_DRAFT.md](DATA_SAFETY_DRAFT.md),
[../SHOW2_play_declarations.md](../SHOW2_play_declarations.md), [OWNER_CLICKS.md](OWNER_CLICKS.md).

## 1. Content rating questionnaire

**Category to pick:** *Utility, productivity, communication, or other* (not a game, not social, not a reference or news app).
Joy Creator is a set of creation tools. Fallback if the form insists on one of the other categories: *Photography / video editing* is not offered as a category; "Utility, productivity... or other" is the home for it.

| Question group | Draft answer | Evidence |
|---|---|---|
| **Violence** (cartoon, fantasy, realistic, blood, sexual violence) | No to all | The app ships no stories, games or characters. Its own content is brushes, papers, looks, fonts, a few sprite sheets and a demo song (`assets/`: `luts`, `gl_transitions`, `shaders`, `text_styles`, `textlab`, `packs`, `audio`, `models`). People make their own drawings and videos; the app publishes none of it |
| **Sexuality and nudity** | No | Same; nothing shipped. The app has no content catalogue or feed |
| **Language** (profanity, crude humour) | No | The shipped strings contain none (`res/values/strings*.xml`); on-device speech to text transcribes what people say in their own videos, which is their content |
| **Controlled substances** (drugs, alcohol, tobacco) | No | none shipped |
| **Gambling** (real or simulated) | No | no such feature |
| **Fear / horror** | No | none shipped |
| **Users can interact or exchange content with each other?** | **No** | No accounts, no feed, no chat between people, no upload. `AndroidManifest.xml` declares no sign-in or messaging activity; Android's share sheet hands a finished file to another app only when the person taps share. Remote (`streaming/LiveM3U8Server.java`) and the computer bridge (`ui/faditor/ai/bridge/BridgeServer.java:24-25`) serve the person's own devices on their own Wi-Fi, not other users |
| **Shares the user's current location with other users?** | **No** | Location only fills a place name or weather on the person's own recording (`services/LocationGeocoder.java:42`, `network/WeatherService.java:26`), off by default (`SharedPreferencesManager.java:1082-1093,1207-1208`). It is never shown to anyone else; an optional switch writes coordinates into the person's own video file (`opengl/GLRecordingPipeline.java:1409`) and they travel only if the person shares the file |
| **Digital purchases / in-app purchases?** | **No** | no billing library: `grep -rniE "billing|purchase" --include=*.kts --include=*.gradle --include=*.toml .` finds nothing (11-14); owner ruling 11-14: no subscription, ever (`tasks/LAUNCH_MONTH_20261114.md` section 0) |
| **Unrestricted web access / a web browser or search?** | **No** | There is no browser. WebViews show the app's own pages (`assets/web/index.html`, What's New: `utils/ChangelogParser.java:25,106`), not arbitrary sites. Network access is Joybot, model downloads and update checks, listed in `PRIVACY.md` |
| **Ads?** | **No** | no ad library; manifest has no ad `meta-data` (see DATA_SAFETY_DRAFT.md section B) |
| **User-generated content that others can see?** | **No** | nothing is published by the app |
| **Generative AI / chatbot** (the newer question) | **Yes: answer honestly** | Joybot (`ui/faditor/ai/joybot/JoybotAgent.java`, default `AiService.java:20`) sends the person's message to an AI service they chose and shows the reply. It can also ask OpenRouter to make a picture (`joybot/JoybotPictures.java:107`). The app does not control what that service says. Joybot changes the project only through validated, undoable steps (AGENTS.md rule 6). **Owner decision:** how the form's wording of "AI output" affects the rating; read it live |

**Expected outcome** (not a rating Google has given): *Everyone* / PEGI 3 / USK 0 for a tool app with no mature content, possibly
raised to *Teen* if the questionnaire treats an open AI chat as "users may encounter unfiltered content". Either is acceptable. The
owner's call is not the number but answering the AI question truthfully.

## 2. Target audience and content (a separate form)

**Owner decision.** Draft recommendation: **age groups 13+ (or 16+)**, not a children's app, not "appeals to children", even
though the owner's own children use JoyBrush at home. Reasons, all from the code: Joybot reaches the open internet through
the person's own key (a child would need an adult's key); `PRIVACY.md` says "not directed at children under 13", and the policy
must match the form. If the owner wants younger ages included, Play's Families policy applies: no data collection beyond need, a
certified-SDK rule (the app has none to certify), and a stricter reading of the AI chat. That is a larger piece of work; flag it
rather than drift into it.

## 3. App content declarations (Policy > App content)

| Declaration | Draft | Evidence |
|---|---|---|
| Privacy policy | `https://joyraptor.github.io/JoyCreator/privacy.html` (after the owner turns Pages on) | `docs/privacy.html` |
| Ads | No ads | DATA_SAFETY_DRAFT.md B |
| App access (reviewers) | All functionality is available without sign-in. A reviewer sees a folder-picking step on first open; any folder works. Joybot needs the reviewer's own key; the rest of the app does not | no login in `AndroidManifest.xml`; folder step in the intro (`SPEC_20261013_FILE_MANAGEMENT.md`) |
| Government app | No | |
| Financial features | No | |
| Health features | No | |
| News app | No | |
| COVID-19 | No | |
| Data safety | See [DATA_SAFETY_DRAFT.md](DATA_SAFETY_DRAFT.md) | |
| Advertising ID | **Not used.** Declare "No" (the form asks because of the Android 13 permission) | `grep -rln "AdvertisingIdClient\|AD_ID" app/src/main` finds nothing (11-14) |
| Photo and video permissions (READ_MEDIA_VIDEO and old READ/WRITE_EXTERNAL_STORAGE) | Core function: a video editor brings the person's videos in. Fill in Play's declaration | `AndroidManifest.xml:16,33,35`; `WRITE_MEDIA_VIDEO` (line 17) is not a real permission: drop it in the play flavour |
| Foreground service types | Use the table in [../SHOW2_play_declarations.md](../SHOW2_play_declarations.md) section 4 (camera, microphone, mediaProjection, mediaPlayback, dataSync, specialUse). Types at `AndroidManifest.xml:386-524`. `specialUse` (annotation overlay and the VisLab overlay, lines 503, 506) needs a written explanation; recommended: a short screen recording in week 3 or fold into `mediaProjection` | |
| Government/US export, encryption | Standard encryption only (HTTPS; Android Keystore for the AI key: `ui/faditor/ai/AiKey.java:37`) | |
| Target API level | `targetSdk = 36` (`app/build.gradle.kts:35`), which meets Play's rule; the Play build lane owns the rest | `app/build.gradle.kts:17,35` |

## 4. Store listing (copy-ready)

The text was written into the repository in plain files, one per Play field:

| Play field | File | Limit | Actual |
|---|---|---|---|
| App name | `fastlane/metadata/android/en-US/title.txt` | 30 | 11 |
| Short description | `.../short_description.txt` | 80 | 75 |
| Full description | `.../full_description.txt` | 4,000 | about 2,560 |

Written in the owner's voice (first person where it helps, plain, no jargon), and only from what STATE shows as proven on a phone
(🟢, or agent-checked on the Note 9; nothing 🔵 or 🔴). What the listing deliberately leaves out, because STATE lists it 🟡 and not
owner-tried or never driven on a device: Remote streaming and control from another device, dual camera, motion detection, the
forensics gallery, packs, the computer-AI bridge. It says "beta".

Suggested Play category: **Video Players & Editors** (the biggest wing); alternative **Art & Design**. Tags (up to five): video
editor, drawing, animation, music visualizer, art. Screenshots, the feature graphic and a short video are week 4 (the old FadCam
`featureGraphic.jpg` in `fastlane/metadata/android/en-US/images/` must be replaced).
