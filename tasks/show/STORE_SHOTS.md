# Play store pictures: 8 phone, 2 tablet, 1 feature graphic

**Job:** L29 / SHOW-3 prep  ·  **Written:** 2026-11-15  ·  Pairs with [SIZZLE_STORYBOARD.md](SIZZLE_STORYBOARD.md) and [SHOT_LIST.md](SHOT_LIST.md).
**Source of the rules:** Google's own page [Add preview assets to showcase your app (Play Console Help)](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en), read 2026-11-15. A third-party search summary disagreed on counts, so every number below is from Google's page. Re-read it the day you upload: Play changes these.

## The rules, short

| Asset | Rule from Google's page |
|---|---|
| **Feature graphic** | Required to publish. **1024 x 500**, JPEG or 24-bit PNG, **no alpha**. Keep the focal point and the name/slogan/UI **away from the cutoff zones** (the red areas in Google's diagram on that page: check it). Avoid pure white, black and dark grey backgrounds (they blend into Play's page); use vivid colour. No device images. No words like "Best", "#1", "Top", "New", **"Free"**, "Discount", "Sale". No prominent branding that copies the app icon. Add alt text (140 characters or fewer). |
| **Phone screenshots** | Up to **8** per device type. JPEG or 24-bit PNG (no alpha). Each side between **320 and 3840 px**, and **the long side at most twice the short side**. To be eligible for recommendation: at least **four** at **1080 px or more**, **9:16 portrait** (min 1080 x 1920) or 16:9 landscape. Text tagline at most **20% of the image**. No "Free", no price or promo words, no call to action ("Download now"), no awards or testimonials. Real in-app screens. No fingers on the device. **Clean status bar**: no notifications, battery, Wi-Fi, mobile full. No device frames or imagery. Alt text each. |
| **Tablet screenshots** (7-inch and 10-inch sections) | **Minimum 4** recommended for tablets and Chromebooks; 1080 to 7680 px; **16:9 landscape or 9:16 portrait**; no extra text that is not part of the app (it can be cut off). |
| **Preview video** | YouTube URL, public or unlisted, ads off; first 10 s must already show the real app; only the first 30 s autoplays (muted); no price words or call to action; portrait or landscape; no black bars. |

## Three consequences for Joy Creator

1. **The Note 9's native screen (1440 x 2960, about 2.06 : 1) breaks the "twice as long" rule by a hair** (2960 / 1440 > 2). A raw screenshot would be refused or cropped. Every phone picture is therefore built on a **1080 x 1920 (9:16)** canvas: a vivid colour field, a short caption in the top band (under 20% = 384 px; aim for under 300), and the screenshot below it, scaled by one factor on both axes (no stretch), with a clean status bar. Build the canvases in Joy Creator's own Studio (export a still from a text-and-image project) or any tool the owner likes; the point is the **screen is untouched**.
2. **The app's ground is pure black**, and Google asks for vivid fields in the **feature graphic**. Use the app's room colours as the canvas and graphic fields (the website design record has them: Studio `#35f6bf`-`#97fe8b`, Capture `#fa3d5d`-`#ff008c`, Shop `#ff008c`-`#cc27ff`, Avatar `#cc27ff`-`#8c3dfa`, Library `#4397fd`-`#55e0f9`, Sound `#faa03d`-`#fc6818`, Finder `#f9f462`-`#ffc341`, Remote `#5c43fd`-`#4397fd`; `tasks/design/Zai-potential-website-landing-page-desighn.html`). Pure black stays inside the screenshot, not around it.
3. **Two of our own words are on Google's banned list on the pictures: "Free" and "No ads" (price/promo).** The store listing text and the sizzle web cut may say them (the description is the owner's voice and Play allows describing a free app there), but **no caption, graphic or Play video may**. See "Listing text risk" at the end.

## The existing images in the repo are FadCam's

`fastlane/metadata/android/en-US/images/` (featureGraphic.jpg, promoGraphic.jpg, tvBanner.jpg, phoneScreenshots 1-10, icon.png) are inherited from FadCam: the feature graphic reads "FadCam: Record video discreetly & privately" and shows FadCam phones with Arabic text. **None of them may be uploaded.** The new pictures replace them (SHOW-2 decides when to commit new files there). The tvBanner is not needed: Joy Creator is not an Android TV app. Keep the old files in git history; remove them from the folder when the new ones land so nothing stale reaches F-Droid's metadata.

## Eight phone screenshots

Output for each: **1080 x 1920 PNG**, a colour field, one caption (top band), the real screen below. Take the screens from the **same Note 9 shoot** as the sizzle ([SHOT_LIST.md](SHOT_LIST.md)); the "Take" column says which shot's session. Order matters: Google shows the first three prominently, and asks that the first three put the **UI** first.

| # | Screen (what is on it) | State to set up | Caption (top band, 6 words or fewer) | Colour field | Alt text (140 or fewer) | Status |
|---|---|---|---|---|---|---|
| **P1** | **Studio**: multi-track timeline, a few clips on two tracks, a text, the music lane with beat ticks, the preview above, one drawer closed | "Edit Demo" project, playhead mid-timeline, nothing selected, no debug rows; from S06 | "Cut video on a timeline" | Studio green `#35f6bf` to `#97fe8b` | A Joy Creator Studio timeline with video clips, a title and a music track. | **Ready** (🟢 multi-clip, multi-track) |
| **P2** | **JoyBrush**: the drawing in progress, the brush drawer showing pencil and a colour, layers column visible | "Fox in the Rain" mid-stroke state (no hover cursor), a graceful moment not a mess; from S03/S04 | "Paint with real brushes" | A warm orange/magenta field from the Sound and Shop pairs | A pencil and watercolour drawing on the JoyBrush canvas with the brush panel open. | **Ready** for the canvas; **after owner OK** if the picture shows watercolour (🟡 STATE 5.6) or says "real". Fallback caption "Draw on a canvas that fits your phone" over a pencil screen |
| **P3** | **VisLab**: a look full screen on the beat; or the look strip beneath it | "Beat Song", a striking look, the beat frame; no FPS counter; from S09 | "Music visuals that move with your song" | The look's own palette field (colour sampled from the look) | A VisLab music visual in full colour reacting to a song. | **Ready** (🟢 ✔) |
| **P4** | **Captions**: Studio with the transcript and a caption in the picture | "Voice Test", transcript showing, caption style "pop" on the line; from S08 | "Captions made on your phone" | Library blue `#4397fd` to `#55e0f9` | Studio showing a video with animated captions and the transcript below. | **Ready** (🟢 ~; offline transcription) |
| **P5** | **TextLab**: a film-poster title (Gold Crown or Blaze) with the look chips below | TextLab, "JOY CREATOR", the look chips in view; from S11 | "Titles that look like film posters" | Capture red/pink field | A gold metallic title being made in TextLab with a row of look choices. | **After owner OK** (TextLab 🟡; do not promote until his verdict) |
| **P6** | **SpriteLab / puppet**: the dinosaur mid-bend with its rig points and the tape | "Dino", a mid-pose; from S05 | "Rig a drawing. Perform it." | Avatar purple `#cc27ff` to `#8c3dfa` | A drawn dinosaur being bent by a puppet rig with the animation timeline below. | **Ready** (🟢: owner confirmed the dinosaur bending 2026-09-15) |
| **P7** | **Capture**: the recorder with the floating camera bubble over a drawing | Capture running over "Fox in the Rain"; bubble with a face or a toy; from S12 | "Record your screen, face on top" | Capture red `#fa3d5d` to `#ff008c` | The screen recorder with a small camera bubble over a drawing app. | **Ready** (🟢 webcam overlay) |
| **P8** | **Joybot**: the chat drawer with a request and the change it made | Joybot in Studio, "remove the silences" (or the proven prompt) and its card; no key or account visible; from S13 | "Ask Joybot to do the job" | Bot violet `#cc27ff` to `#5c43fd` | The Joybot assistant in Studio answering a request with a change to the timeline. | **Ready** (🟢 ✔). Caption must not imply Joybot needs no key; its listing text says it uses your own key |

**If DOOR-2 lands before the upload:** a lobby picture is a strong alternate for P8 ("One app, five rooms"): lobby with the marquee. Not in the eight now because the lobby is expected to change.

**Not shown on purpose:** frame tweens, Select on pixels, packs, Remote Studio, Joybot as a brush maker (🔵/🟡, not user-reachable or not owner-cleared).

## Two tablet screenshots (and two optional extras)

Device: the **Tab S8 Ultra** test tablet (2960 x 1848, 16:10). Google wants **16:9 landscape (or 9:16 portrait) at 1080 to 7680 px**, so each picture is cropped or placed on a **1920 x 1080 canvas** (not the raw 16:10). **Google recommends a minimum of 4** for tablets, so the two the owner asked for are the floor; T3 and T4 are cheap extras from the same session.

| # | Screen | State to set up | Caption | Status |
|---|---|---|---|---|
| **T1** | **Studio on a tablet**: drawers beside the picture (the Editing desk layout): timeline, the preview, side panels | "Edit Demo" on the tablet, Editing desk layout, a clip selected with its drawer open | "A full editing desk" | **After TAB-1** (11-15: built and tablet-checked, owner has not seen it; STATE) and after the owner looks at the final layout; it must match the release build |
| **T2** | **JoyBrush on a tablet**: layer column pulled out, the brush panel open | "Fox in the Rain" (or a tablet-only test drawing), layer column out, brush panel with starred brushes | "Room for every layer and brush" | **After TAB-1** (TAB-1 §K9, K10) |
| T3 (optional) | VisLab full screen on the tablet | "Beat Song" | "Music visuals, full screen" | After TAB-1 owner check |
| T4 (optional) | Studio with the music visuals layer in the preview and the timeline | "Edit Demo" + VisLab layer | "Your visuals, in your video" | After TAB-1 owner check |

Tablet captions: Google says to **exclude extra text that is not part of the app** in tablet pictures (it can be cut off). So **tablet pictures carry no caption band**: the caption lines above are for the alt text and the Play "what's new" notes. Upload them as plain screenshots at 1920 x 1080.

## The feature graphic (1024 x 500)

**Concept:** a bright colour field, a title **made in Joy Creator**, and three real pieces of the app fanned, centred, with plenty of safe margin.

- **Field:** a flat or gradient field in two room colours that are not near-black (suggest Shop to Avatar, `#ff008c` to `#cc27ff`, or Studio's green to cyan). Not black, not white, not grey.
- **Centre line (title):** "Paint. Animate. Edit." in a TextLab look (gold) **only after the owner clears TextLab** (🟡); otherwise a Studio title in white with a soft shadow. A second small line: "Music visuals too." No "Free", no "No ads", no price words, no "New".
- **Pictures:** three cut-out pieces of real UI at roughly equal weight, centred: a Studio timeline, a drawing, a VisLab look. No phone or device images (Play lists device imagery as inappropriate), no frames.
- **Branding:** the name "Joy Creator" once, small, and **not** a big copy of the icon (Play asks for branding that extends the icon, not duplicates it).
- **Safe area:** everything important inside the central area, away from the cutoff zones in Google's red-zone diagram; background art only at the edges; no small text.
- **Output:** 1024 x 500 JPEG (24-bit) or PNG with no alpha; alt text: "Joy Creator: a timeline, a drawing and a music visual in one phone app."
- **Status:** **ready to make once P1, P2 or P3 screens exist** (shots P1, P2, P3 are all from 🟢 features). Title as a plain Studio title now; TextLab version "after owner OK".

## Preview video (YouTube) for the listing

Use the **Play cut** of the sizzle ([storyboard](SIZZLE_STORYBOARD.md)), uploaded to YouTube as **public or unlisted, ads off, not age-restricted**. The first 10 s already show the real app (S01 timeline, S02 lobby, S03 drawing). Only 30 s autoplays, so the best 30 s is bars 1 to 15 (S01 to S09). It must carry no price words and no "download now".

## Before every capture (the checklist)

1. Notification bar: no notifications, battery full, Wi-Fi on, signal full; no alarm or Do Not Disturb icons; clock plain.
2. No debug overlays, FPS counters, test project names with "TEST", private file names, accounts, keys or tokens.
3. English UI. A neutral light/dark state matching the app's default.
4. The screen is **not** stretched, framed, tilted or edited; only the surrounding canvas is designed.
5. Save the **unedited screen** next to each finished picture so the picture can be rebuilt (the owner or an agent can redo the canvas, not the shoot).

## Captured with the same session as the sizzle

| Picture | From sizzle shot | One take serves |
|---|---|---|
| P1, T1 | S06 | store, reel R-Studio |
| P2 | S03, S04 | store, reel R-JoyBrush |
| P3 | S09 | store, reel R-VisLab |
| P4 | S08 | store, reel R-Studio |
| P5 | S11 | store, reel R-TextLab |
| P6 | S05 | store, reel R-SpriteLab |
| P7 | S12 | store, reel R-Capture |
| P8 | S13 | store, Joybot reel |

## Listing text risk (for the store lane, SHOW-2; not edited here)

The text in `fastlane/metadata/android/en-US/` is not part of this job, but two points came out of reading Google's page:
- `short_description.txt` ("Paint, animate, edit video, make music visuals. Free. No ads, no watermark.") uses **"Free."**; Google lists "price and promotional information" as language not to use in the **short description** and names the words ("New", "Discount", "Sale") without naming "Free". "Free" is named explicitly for the feature graphic. It is a risk, not a certain breach; let the store lane decide, with "No ads, no watermark" likely safer (a statement about the app, not about price). The owner's promise ("free, no subscription") stays in the full description and on the website.
- The full description's "Joy Creator is open source (GPL v3) and is built on FadCam by FadSec Lab" and the data-safety answers belong to the store lane too.

## Owner decisions this plan needs (all optional; defaults chosen)

1. Is the Tab S8 Ultra's 16:10 shot OK as a 1920 x 1080 canvas picture (default: yes).
2. Which two room colours the feature graphic uses (default: Shop to Avatar pink-to-violet, as the website's loudest pair).
3. Whether to risk "Free." in the short description (default: ask the store lane to drop it from the short text, keep it in the long).
