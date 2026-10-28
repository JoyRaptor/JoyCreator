# S19 cut map: FadCam's cloud and promo parts (owner: yes, 10-28)

Scouted read-only on 10-28 against `origin/joy-creator` (paths under `app/src/main/`; J = `java/com/fadcam/`,
W = `assets/web/`). Line numbers drift: re-find each spot before editing. **LAN Remote on the person's own
Wi-Fi stays** (a launch feature, owner 10-17; rebrand "Remote Studio" later, DESIGN_JOY_CREATOR §7).

## Do first (risk)
- Users with `FadCamCloudPrefs` `streaming_mode=1` would have the HTTP server skipped after the cut, silently
  killing LAN Remote. **Migrate the pref to 0** (or ignore it) before removing the cloud path.

## 1. Entry points (remove)
- Remote Cloud button: `layout/fragment_remote.xml:51-115`; `RemoteFragment.java:268-275`.
- Remote "Streaming mode" row: `fragment_remote.xml:445-466`; `RemoteFragment:311-315, 1655-1860`.
- Legacy cloud toggle (`fragment_remote.xml:471-495`) and E2E row (`:902-930`).
- Manifest `:333` WebViewActivity (orphan), `:334-336` CloudAccountActivity. No fadseclab deep links.
- Promo: `HomeFragment.java:4654-4689` Get Pro (`fragment_home.xml:68-82`); manifest `:450-454` FadCamProActivity;
  `AboutFragment.java:229-236` Ko-fi and Discord; `HomeSidebarFragment.java:233-244` Discord row, `:502-523` Pro
  update card; `SettingsHomeFragment.java:388,515` and `settingshub/SettingsDestinations.java:91` README sheet.

## 2. Java
- **Delete:** `J/streaming/CloudAuthManager` (Supabase key), `CloudStatusManager`, `CloudStreamUploader`;
  `J/security/StreamKeyManager`, `SegmentEncryptor` (E2E, cloud-only callers); `J/ui/CloudAccountActivity` +
  `layout/activity_cloud_account.xml`; `J/ui/WebViewActivity`; `J/ui/KoFiSupportBottomSheet`; `J/ui/FadCamProActivity`;
  `J/ui/ReadmeBottomSheetFragment`; `J/ui/ProUpdateBottomSheet`.
- **Shared, edit branches only (LAN path stays):**
  - `RemoteStreamService.java:98-125` (cloud mode skips the HTTP server), `:126-128`, `:143-144`, `:169-195`, `:473-500`, `:541-560`.
  - `RemoteStreamManager.java`: cloud fields `:87-104`; upload hooks `:429-435`, `:503-517`; `generateCloudPlaylist`
    `:671-720`; branches `:816-833`, `:1209-1216`, `:1304-1433`, `:1931-1938`; `:2028-2283` dead (no caller).
  - `RemoteFragment.java`: imports `:38-46`; fields `:101-130`; wiring `:174-190`, `:268-320`, `:404-406`; URL display
    `:633-690` (fadseclab `:659`, `:662`, `:683`); toasts `:753-765`, `:915-935`; methods `:1160-1270`, `:1415-1633`; `:1784-1810`.
  - `ClientDataBottomSheet.java:65-88`: keep the local branch.
  - `LiveM3U8Server.java`: no cloud code; promo routes `:140-146`, `:195-205`; GitHub fetch `:1909-1913`.
  - `RemoteAuthManager.java`: no cloud. Keep.

## 3. Web remote (`W`)
- **Delete:** `js/fadcam-remote.js` (Supabase URL/key; treats `localhost` as cloud), `js/services/E2EKeyManager.js`,
  `FadCamFragLoader.js`, `FadexNotificationManager.js`, `js/constants/NotificationConstants.js`, `fadex/`.
- `index.html` loads them unconditionally. Edit: `:44` fadseclab link; `:51-80`, `:1035-1049`, `:1153`, `:5304-5390`
  fadex bell; `:478-483` Pro button; `:486-499` footer (keep `:500` Endpoints); `:543-580` E2E footer; `:686-767` Pro
  modal; `:1053-1054` Supabase CDN; `:1149-1150` loaders; `:5517-5550`, `:5683-5696` cloud branches; `:5761-5762` include.
- `ApiService.js` cloud branches `:40-50`, `:86-135`, `:213-290`, `:475-520`, `:602-615` (local path stays);
  `HlsService.js:34-63`. Every `FadCamRemote` call is behind a `typeof` guard. Keep hls.js, AuthService.js, config.js.
- Viewer-modal cloud branches (`index.html:1698-1725`, `:2039-2070`, `:3006-3030`, `:3414-3430`): harmless, prune later.

## 4. Build
- `app/build.gradle.kts`: remove flavours notesPro/calcPro/weatherPro (`:146-160`), build types pro/proPlus (`:109-140`),
  `variantFilter` (`:172-190`), the matching `sourceSets` (`:209-219`), `isProBuild` (`:21`); dirs `src/notesPro`,
  `src/calcPro`, `src/weatherPro`. Keep flavour `default` and its dimension: `:app:assembleDefaultDebug` survives.
- Delete `.github/workflows/build-pro.yml`, `build-pro-plus.yml`, `.github/FUNDING.yml`.

## 5. Keep, but repoint (GPL)
- `AboutFragment.java:193-200, :229` and `AboutCredits.java:27`: "View source" points at FadCam's org: repoint to this
  project's source (owner S9 decides the public URL); keep FadCam's credit and licence.
- `UpdateAvailableBottomSheet.java:91` falls back to FadCam's repo: point it at this project or remove the fallback.
- Strings to drop with their screens: cloud/e2e (`values/strings.xml` ~2446-2518), kofi (~1759-1762), fadcam_pro
  (~2340-2364), update_pro (~2967-2969), and their translations.
- Unclear: whether `BetaUpdateBottomSheet` is FadCam's update channel (check before keeping).

## Proof
Build `:app:assembleDefaultDebug`; on the Note 9: Remote starts, the LAN page loads from another device or
`adb forward` + a browser, the live view plays, and no cloud, Pro, Ko-fi or Discord control is left in Remote, Home,
About or Settings.
