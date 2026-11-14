# Play Console declarations: drafts read against the code (jobs L09 and SHOW-2, 2026-11-14)

**What this is.** Copy-ready answers for the Play Console forms: content rating, data safety, permissions and foreground
services, plus the other declarations. Each answer was written from the code on `joy-creator` (manifest, network calls,
settings) and from [PRIVACY.md](../PRIVACY.md), and says where it is a **decision for the owner** rather than a fact. It is a
draft: Play's forms change, so check each question against the live form when the account exists. **Nothing here was
submitted.** Play's own policy pages were not re-read for this draft; where an answer depends on how Play reads a rule, it says so.

The build this describes is the future **`play` flavour** (job SHOW-1): the disguise icons, the accessibility screenshot service,
all-files access and the privacy black screen are removed there. Rows about those say "removed in the play flavour"; if that
does not happen, those rows become real problems.

## 1. Store listing basics

| Field | Draft |
|---|---|
| App name | Joy Creator (11 of 30 characters) |
| Short description | `fastlane/metadata/android/en-US/short_description.txt` (78 of 80) |
| Full description | `.../full_description.txt` (about 1,580 of 4,000), plain text, written only from what the app does today |
| Category | Video Players & Editors, or Art & Design (owner's pick; Video Players & Editors matches the biggest wing) |
| Tags (pick up to 5) | video editor, drawing, animation, music visualizer, art |
| Contact email | [CONTACT EMAIL: OWNER TO CONFIRM, roadmap S4] |
| Privacy policy URL | the GitHub Pages address of `docs/privacy.html`, once Pages is on and the owner has reviewed it: [PAGES URL] |
| Website | [WEBSITE URL, week 4] |
| Free or paid | Free; no in-app purchases, no ads (so answer "No" to both "contains ads" and "in-app purchases") |
| Screenshots / feature graphic | week 4 (see the launch plan); `featureGraphic.jpg` in fastlane is the old FadCam one and must be replaced |

## 2. Content rating (IARC questionnaire)

Answered from the app's real content: a creative-tools app with no violence, sexual content, gambling or user-to-user features of its own.

| Question | Draft answer | Basis |
|---|---|---|
| Category | Utility, Productivity, Communication or other (not a game) | Creative tools |
| Violence, blood, sexual content, nudity, crude humour, profanity, drugs, gambling | No to each | The app ships none. Users make their own drawings and videos; nothing is published by the app |
| Can users interact or exchange content with each other? | No | There are no accounts, feeds or sharing inside the app. (Android's share sheet hands a file to another app on the user's request) |
| Does it share the user's location with others? | No | Location only fills a place name or weather on a person's own recording |
| Does it allow users to buy things or contains digital purchases? | No | None |
| Unrestricted web access or a browser? | No | There is no web browser in the app. The Remote page is the app's own, served on the person's own Wi-Fi |
| Generative AI / chatbot content | **Yes, flag it honestly** | Joybot passes the person's messages to an AI service they chose (their own key) and shows the reply. The app does not control what that service says. **Owner decision:** whether this raises the rating; the questionnaire's wording for AI features should be read live |
| Expected rating | Everyone / PEGI 3, possibly Teen because of the AI chat | Not a rating Google has given: it comes out of the form |

## 3. Data safety form

**Principle used.** The developer (the owner) has no servers and receives no data. The app sends data off the phone only when the
person uses a feature that needs the internet, and then **directly to a third-party service** (listed below). Google's form asks
about data "collected" (sent off the device) and "shared" (sent to others). Google's guidance has exemptions for transfers a user
starts and understands, and for service providers; **whether they cover the bring-your-own-key AI requests is the owner's call
(and Play's)**. The draft below takes the cautious route and declares them as shared, optional, because a wrongly-empty form is
the worse failure. Everything in the table matches PRIVACY.md.

| Section | Draft answer |
|---|---|
| Does the app collect or share any of the required user data types? | Yes (optional, user-started transfers only; see below) |
| Is all of the data encrypted in transit? | **Mostly.** Web calls to AI, location, weather and GitHub use HTTPS. LAN Remote and the "connect your computer" bridge use plain `http` on the person's own network, which is user-started and never goes through us. Answer "No" to "all data encrypted" if Play counts these, or explain in the form's free text |
| Can users ask to delete their data? | Yes, in the sense that the developer holds none: removing the app deletes its own data; the Joy Creator folder is the person's own files. No account exists to delete |
| Account creation | None |

| Data type | Collected | Shared | Purpose | Optional | Where it goes |
|---|---|---|---|---|---|
| Approximate or precise location | Yes | Yes | App functionality (a place name on a recording; weather in the watermark) | Yes, off by default | OpenStreetMap Nominatim (nominatim.openstreetmap.org); Open-Meteo (api.open-meteo.com) |
| Messages / other in-app text (what the person types to Joybot, transcripts) | Yes | Yes | App functionality | Yes | The AI service the person chose (OpenRouter by default; or OpenAI, Anthropic, Google, Groq, xAI, or an address they enter) |
| Photos and videos (a picture or video frame the person attaches or asks Joybot to look at) | Yes | Yes | App functionality | Yes | the same AI service |
| Files and docs (project outline sent as context to Joybot) | Yes | Yes | App functionality | Yes | the same AI service |
| App info and performance, device or other IDs, contacts, calendar, health, financial, web history, audio files sent off-device, crash logs | No | No | n/a | n/a | The app has no analytics, advertising, crash-reporting or account services (checked in the code and manifest 11-13) |
| IP address (implicit) | not asked as a type | | | | Ordinary requests to GitHub (update check, What's New) and the model downloads (alphacephei.com, huggingface.co) reveal an IP address to those hosts; no personal data is attached |

**Security practices section.** Key stored encrypted on the phone (Joybot keys); data in transit HTTPS where it leaves the phone; a
privacy-policy URL is required: [PAGES URL]. **Third-party code:** the app links open-source libraries (MediaPipe, Vosk, OpenCV,
ffmpeg-kit, whisper.cpp, OkHttp and others); none of them reports to a server in the code read for PRIVACY.md. Libraries were
not audited for hidden calls beyond that.

## 4. Permissions and foreground services (read from `AndroidManifest.xml`)

Play asks for a justification for sensitive permissions and a video or text for foreground-service types. "Play flavour" is the
future SHOW-1 build; "keep" means it stays.

| Permission | Why the app needs it | Play flavour |
|---|---|---|
| CAMERA, RECORD_AUDIO | Camera and screen recording with sound, voice-over, dictation | keep |
| POST_NOTIFICATIONS | Recording and export progress | keep |
| READ_MEDIA_VIDEO (and READ/WRITE_EXTERNAL_STORAGE for old Android) | Bring the person's videos into the editor. Play's photo/video permission rule asks core-function justification: a video editor qualifies | keep; fill in Play's **Photo and video permissions** declaration. `WRITE_MEDIA_VIDEO` is not a standard Android permission: remove it |
| MANAGE_EXTERNAL_STORAGE (all-files access) | Inherited from FadCam to reach Documents | **remove** (the Joy Creator folder uses Android's folder picker). If left, Play will reject without a strong core-function case |
| ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION | Optional place name or weather on a recording | keep (foreground use only; the in-app switch is off by default; PRIVACY.md says so) |
| INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE | Joybot, model downloads, updates, LAN Remote | keep |
| SYSTEM_ALERT_WINDOW | Floating record controls and the floating visualiser over other apps | keep with justification; Play checks this one. Tell reviewers it is optional and the person turns it on in Android settings |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Keep a long background recording alive | **Play restricts this permission to apps whose core function breaks without it.** Recommend removing it in the play flavour and linking Android's own setting instead |
| WAKE_LOCK, VIBRATE, MODIFY_AUDIO_SETTINGS | Recording and haptics | keep |
| BLUETOOTH_CONNECT, BLUETOOTH_SCAN (and the old BLUETOOTH, BLUETOOTH_ADMIN) | Bluetooth microphone selection (audio code mentions Bluetooth). Not verified which calls need SCAN | keep CONNECT; **recommend dropping SCAN** unless the code is found to scan; Play asks why |
| USE_BIOMETRIC, USE_FINGERPRINT | Optional app lock (`SecuritySettingsFragment`) | keep or drop with the app lock |
| HIGH_SAMPLING_RATE_SENSORS | High-rate sensors (tilt, puppets); not verified | owner/Play lane to confirm or drop |
| FOREGROUND_SERVICE and the types below | Long jobs must be foreground services | keep, see next table |
| BIND_ACCESSIBILITY_SERVICE (`FadRecScreenshotAccessibilityService`) | A FadCam screenshot helper | **remove** (accessibility use is tightly policed; a creative app cannot justify it) |
| 15 `activity-alias` disguise launcher icons | FadCam's hidden-app feature | **remove** (looks like stalkerware to reviewers) |

| Foreground service type | Used by | Draft justification |
|---|---|---|
| camera, microphone | `RecordingService`, `DualCameraRecordingService`, `FloatingWebcamService`, `TorchService` | The person is recording video or audio and sees the notification |
| mediaProjection (with microphone) | `ScreenRecordingService`, the VisLab overlay | Screen recording the person starts, with Android's own consent dialog |
| mediaPlayback | playback services, `AlarmService` | Playing a recording or sound in the background |
| dataSync | export (`ExportService`), file copy/move/delete jobs, Joybot jobs, LAN Remote and the bridge | Long jobs the person started that must not stop when the screen turns off |
| specialUse | `AnnotationService`, the VisLab overlay | Drawing on screen during a recording. **Play requires a written explanation and may ask for a demo video**: record one in week 3 or fold this into mediaProjection and drop `specialUse` |

## 5. Other declarations

| Item | Draft |
|---|---|
| Target audience and content | **Owner decision.** The privacy policy says the app is not directed at children under 13. If the target age group includes children, Play's Families policy applies (no ads or analytics SDKs, extra rules for AI and permissions). Recommended: 13 and over, not a children's app, even though the owner's own children use JoyBrush at home |
| Ads | No ads |
| App access (for reviewers) | No login. A reviewer meets a folder-picking step on first open; say so and say any folder works |
| Government, health, financial, news, COVID apps | None apply |
| **Generative AI content** | Play's AI-generated-content policy expects apps with generative AI to give users a way to report offensive output. **No "report this reply" control was found in the code (grep of the strings, 11-14).** Options: add one to Joybot's chat (small), or explain that Joybot is a thin client to the user's own chosen service. **Owner / Joybot lane** |
| Export rules, encryption | The app uses standard encryption only (HTTPS, encrypted key storage); answer the US export question accordingly, to be read live |
| App signing | Play App Signing; upload key kept off git (job SHOW-1) |
| Exported components | The manifest has 32 `exported="true"` entries (activities, aliases, services). The play build lane should check each is intentional before upload (Play's pre-launch report flags it) |

## 6. What was not checked
- Play's current form wording and policy text (written from memory of the forms, not re-read).
- Which Bluetooth and high-rate-sensor calls the code really makes; the exact list of exported entries that should stay.
- Whether the Play flavour exists yet: the rows marked "remove" are conditions on it.
