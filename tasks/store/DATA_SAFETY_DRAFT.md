# Play Console: Data safety form, draft answers with the code behind each

Written 2026-11-14 (SHOW-2, store-and-legal session). **Nothing was submitted.** Every answer cites the file and line it
rests on, as read on `joy-creator` at `d0696478c`. Paths are under `app/src/main/` unless they start with `app/` or
`joybrush`. Lines drift: re-run the `grep` in the last column if a file has changed. Play's live form wording was not
re-read; check each question against the form when the account exists. Companion drafts:
[CONTENT_RATING_DRAFT.md](CONTENT_RATING_DRAFT.md), [../SHOW2_play_declarations.md](../SHOW2_play_declarations.md)
(permissions and foreground-service justifications), [OWNER_CLICKS.md](OWNER_CLICKS.md).

**Stance.** The owner runs no server and receives nothing. The app sends data off the phone only when the person uses a
feature that needs the internet, and then straight to a third-party service. Google's form counts that as "collected" (and
"shared" when it goes to another company). Google exempts some transfers the user starts knowingly; whether the
bring-your-own-key AI requests fall under that is Google's reading, not ours, so **the cautious route below declares
them, as optional**. A form that says "nothing collected" and is later found wrong risks the listing; a form that says
"optional, user-started" costs nothing. This is the owner's call: see "Decisions" at the end.

## A. The top-level questions

| Question on the form | Draft answer | Evidence |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (all optional, all user-started) | Rows C1 to C4 below |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Every request the app itself makes leaves over HTTPS: `res/xml/network_security_config.xml:13` (`cleartextTrafficPermitted="false"` for everything) with the only exception, lines 20-22, being `localhost` and `127.0.0.1` (the phone talking to itself). The Remote and bridge servers listen on the person's own Wi-Fi in plain http (`streaming/LiveM3U8Server.java:18` and `ui/faditor/ai/bridge/BridgeServer.java:20,46-48`, both NanoHTTPD), but that is the person viewing their own phone from their own device: nothing is sent to the developer or a third party, so it is not "collected". Say so in the form's note if Play asks |
| Do you provide a way for users to request that their data be deleted? | **Yes**, with the free-text note: "The developer holds no user data. Removing the app deletes its storage. The user's files are in a folder they chose and own. No account exists." | No account code exists (no sign-in activity in `AndroidManifest.xml`); folder model in `tasks/SPEC_20261013_FILE_MANAGEMENT.md`; PRIVACY.md "Your control" |
| Data deletion URL | Not required when there is no account; give the privacy-policy page | `docs/privacy.html` |
| Account creation methods | None | No sign-in or registration screens anywhere (`grep -rn "createUserWith\|signUp" app/src/main` finds nothing; the only "pairing" is the local bridge code, `ui/faditor/ai/bridge/BridgeAuth.java`) |
| Independent security review (optional badge) | No | n/a |
| Committed to Play Families Policy | No (not a children's app; see CONTENT_RATING_DRAFT.md "Target audience") | |

## B. What is NOT collected (answer "No" and back it)

| Data type group | Answer | Evidence |
|---|---|---|
| App activity (page views, in-app search, installed apps, other activity), App info and performance (crash logs, diagnostics, performance) | **Not collected** | No analytics, crash-reporting, ads, billing or sign-in library in any build file or the manifest: `grep -rniE "firebase\|crashlytics\|admob\|play-services\|sentry\|appsflyer\|mixpanel\|billing" --include=*.kts --include=*.gradle --include=*.toml app/ build.gradle.kts gradle/` returns nothing; the manifest has no ad or analytics `meta-data` or provider. The app's own log (`FLog`) is written to a local file and only shared if the person presses share (`ui/settingshub/SettingsCatalog.java:121-122`, "Read, search and share the app's log", `res/values/strings_settings_hub.xml:155`) |
| Device or other IDs (advertising ID, Android ID) | **Not collected** | `grep -rln "AdvertisingIdClient\|getAdvertisingId\|ANDROID_ID" app/src/main` returns nothing (checked 11-14) |
| Personal info (name, email, address, phone, race, political, religion, orientation, other) | **Not collected** | No form in the app asks for them |
| Financial info, Health and fitness, Contacts, Calendar | **Not collected** | No such permission in the manifest (`AndroidManifest.xml:9-81` lists every permission: none for contacts, calendar, SMS, call log, body sensors) |
| Audio files, Voice or sound recordings, Music files | **Not collected by the developer or sent anywhere.** Recordings and voice-overs are saved on the phone. Speech to text runs on the phone | Models are downloaded, not uploaded: `ui/faditor/transcript/TranscriptionEngine.java:67-74` (alphacephei.com and huggingface.co are download-only URLs); the recorder writes only to local files (`services/RecordingService.java`) |
| Web browsing history | **Not collected** | The app has no browser. Its WebView pages are its own assets (`assets/web/index.html`, What's New) |

## C. What IS sent off the phone (declare as optional)

| # | Data type (Play's name) | Collected | Shared | Purpose | Optional? | Default state | Goes to | Evidence |
|---|---|---|---|---|---|---|---|---|
| C1 | **Location: Approximate location** and **Precise location** | Yes | Yes | App functionality | Yes | **Off.** `PREF_LOCATION_DATA`, `PREF_WATERMARK_WEATHER` and `PREF_EMBED_LOCATION_DATA` default to `false` | Nominatim (OpenStreetMap) to turn coordinates into a place name; Open-Meteo for weather | Defaults: `SharedPreferencesManager.java:1082-1086` (place names), `:1089-1093` (embed in video), `:1207-1208` (weather). Calls: `services/LocationGeocoder.java:42` (`nominatim.openstreetmap.org/reverse?...lat=%f&lon=%f`), `network/WeatherService.java:26` (`api.open-meteo.com`). Callers: `services/RecordingService.java:4601`, `watermark/WatermarkManager.java:241`, `services/RecordingService.java:4750`. Permission asked only when the switch is turned on: `services/RecordingService.java:1471-1491` |
| C1b | (not sent anywhere) Embedded location in the video file | n/a | n/a | n/a | n/a | Off | Stays inside the file the person made; it travels only if they share the file | `opengl/GLRecordingPipeline.java:1409` (`mediaMuxer.setLocation`), `media/FragmentedMp4MuxerWrapper.java:436`. Not "collected": no transmission |
| C2 | **Messages: Other in-app messages** (what the person types to Joybot, transcripts used as context) | Yes | Yes | App functionality | Yes (Joybot is optional; the rest of the app works without a key) | Off until the person pastes a key | The AI service the person set up: OpenRouter by default, or OpenAI, Anthropic, Google, Groq, xAI, or an address they type (HTTPS only) | `ui/faditor/ai/AiService.java:20` (default base `openrouter.ai/api/v1`); providers `ui/faditor/ai/joybot/AiProviders.java:35-40`; request build `joybot/JoybotAgent.java:1133-1148`; the first request needs a key: `ui/faditor/ai/AiKey.java` |
| C3 | **Photos and videos** (a picture or video frame the person attaches or asks Joybot to look at; a picture Joybot is asked to make) | Yes | Yes | App functionality | Yes | Off | Same AI service; picture requests go to OpenRouter's picture service | Attachment sent as `image_url` with this request only: `joybot/JoybotAgent.java:149,592,1133-1134`; picture service `joybot/JoybotPictures.java:107` |
| C4 | **Files and docs** (the open project's outline: clip names, lengths, layers) | Yes | Yes | App functionality | Yes | Off | Same AI service | Context built in `joybot/JoybotAgent.java` (transcript preview `TRANSCRIPT_PREVIEW = 900` at line 94) |
| C5 | Not a Play data type: **IP address** (implicit) | n/a | n/a | n/a | n/a | n/a | Revealed to any host the app contacts, as with any web request: GitHub (update check, What's New: `services/UpdateCheckService.java:37`, `utils/ChangelogParser.java:25,106`), the model downloads (`TranscriptionEngine.java:67-74`), the connection check (`streaming/util/NetworkMonitor.java:36-37`, google.com and github.com favicons), and the AI services. Play does not ask for IP address as a type; PRIVACY.md states it | |
| C6 | Not Play data: Joybot **model list and key check** (no project content, only the key as the credential) | n/a | n/a | n/a | n/a | n/a | OpenRouter or the chosen service | `joybot/ModelCatalog.java:116,145`, `joybot/KeyCheck.java:30`, `ai/ModelCapabilities.java:50` |

Purposes to tick for C1 to C4: **App functionality** only. Not Analytics, not Advertising, not Personalization, not Account
management, not Fraud prevention.

The "Shared" column: tick **Shared** for C1 to C4 on the cautious route (a different company's service receives the data).
If the owner takes the exemption for user-initiated, in-app-disclosed transfers to a service the person chose, C2 to C4 can
be "collected: no / shared: no" with a note. See "Decisions".

## D. Security practices section

| Question | Draft answer | Evidence |
|---|---|---|
| Data encrypted in transit | Yes | Section A |
| Data stored encrypted on the device | Say so in the free text for the API key only | The key is wrapped by an Android Keystore key: `ui/faditor/ai/AiKey.java:37-45` (`AndroidKeyStore`, AES-GCM, `JoyAiSecrets`), older plain copies deleted on first read |
| Backups | The app turns Android cloud backup off | `AndroidManifest.xml:94` (`android:allowBackup="false"`) |
| Privacy policy URL | `https://joyraptor.github.io/JoyCreator/privacy.html` once Pages is on (see OWNER_CLICKS.md) | `docs/privacy.html`, built by `tools/make_site.py` |
| Third-party libraries | Open-source only (MediaPipe, Vosk, OpenCV, ffmpeg-kit, whisper.cpp, OkHttp, NanoHTTPD, GSAP in assets). None was found to call out to a server in the code read for the policy. The libraries' own code was not audited line by line | `app/build.gradle.kts` dependencies |

## E. Things the Play build lane (SHOW-1) must know, found while reading

These are not Data safety answers but they decide whether the form stays true.

1. **The `play` flavour must really remove what its manifest says.** On the branch `origin/claude/play-build-20261114-1a01c6`
   (commit `941a68eeb`, not yet on `joy-creator`) `app/src/play/AndroidManifest.xml` removes all-files access, the accessibility
   screenshot service, `PrivacyBlackActivity`, the 15 disguise aliases and the old shortcuts. If it does not land, the form's
   "no sensitive access" answers and the policy are wrong.
2. **Still in the manifest after that, and likely to draw a Play question:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (line 78; asked
   in code at `ui/HomeFragment.java:585-588` and `ui/OnboardingPermissionsFragment.java:88`), `BLUETOOTH_SCAN` (line 10; only
   `startBluetoothSco` is used, `fadrec/encoding/ScreenRecordingPipeline.java:749`, `opengl/GLRecordingPipeline.java:2851`, which
   needs CONNECT, not SCAN), `WRITE_MEDIA_VIDEO` (line 17; not a real permission), `FOREGROUND_SERVICE_SPECIAL_USE` (line 54;
   `AnnotationService` and `VisOverlayService`, manifest lines 503 and 506, need a written justification). Recommended to drop
   the first three in the play flavour. Details and justification text: [../SHOW2_play_declarations.md](../SHOW2_play_declarations.md) section 4.
3. `applicationId` was still `com.fadcam` in `app/build.gradle.kts:33` on `joy-creator` at the time of writing; the Data safety
   form belongs to the final ID (`cc.joycreator.app`, owner ruling 11-14).
4. **Remote Studio, Smart Detection and the "connect your computer's AI" bridge** are described honestly in PRIVACY.md. They are
   user-started and local-network only (`BridgeServer.java:24-25`: "on the local network only while the person has it switched on",
   guarded by `BridgeAuth`). If the play flavour hides them, nothing in this form changes.

## F. Decisions that are the owner's (not facts)

1. **Cautious or exempt for the AI requests (C2 to C4).** Recommended: cautious, as drafted. It is a few extra clicks and cannot
   be called wrong.
2. **Contact email on the policy** is `studio@joycreator.cc` until he confirms the mailbox receives mail.
3. **Target audience.** See [CONTENT_RATING_DRAFT.md](CONTENT_RATING_DRAFT.md). If "13 and over" is not chosen, Play's Families
   rules change this whole form.
4. **"Report this reply" for Joybot** (Play's AI-content policy): **now exists** (landed 11-14, commit `959bbd3d9`, after the first draft
   of this file). A flag beside Copy on every Joybot answer, asking first; it saves the reply and the question before it as JSON in the
   person's Joy Creator folder (`Joybot/Reports/`, `ui/faditor/ai/joybot/ReplyReport.java:38`) and opens the person's own email app
   with a prefilled message to `studio@joycreator.cc` (`ReplyReport.java:37,100-101`, an `ACTION_SENDTO` `mailto:` intent; UI at
   `ui/faditor/ai/ChatAssistantActivity.java:3547,3560`). **Data safety reading:** the app transmits nothing; the content reaches the
   developer only if the person presses send in their own mail app, so it is not "collected" by the app. Optionally mention it in
   the form's free text. PRIVACY.md now says this in "What can leave your phone".
