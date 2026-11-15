# The Google Play upload key: what it is and how to keep it safe

Written 2026-11-14 by the Play build lane (job [SHOW-1](../jobs/SHOW-1_play_build.md)).

## What it is, in one paragraph

Every app on Google Play is signed with a key. With **Play App Signing** (Google's default and the safest
choice) Google keeps the real signing key, and we keep only an **upload key**: it proves to Google that a new
version really comes from us. If the upload key is ever lost or stolen it is **not** the end of the app: Google
can reset it after an identity check (it takes a few days). Still, treat it like a house key.

## Where it is

| What | Where |
|---|---|
| The key file | `D:\JoyCreator-keys\joycreator-upload.jks` (on this PC, outside the project folder) |
| Its name inside the file ("alias") | `upload` |
| Its password | in `D:\JoyCreator-keys\BACKUP-ME-then-delete.txt` until you have backed it up |
| Fingerprint (to recognise it, not secret) | SHA-256 `C7:60:CD:BF:AB:1C:30:8E:EE:71:1A:67:A6:84:C7:4B:49:31:CB:E2:90:56:5F:75:AD:15:B7:2C:A4:E9:A6:93` |

It is **never** in git: the project ignores `*.jks` files and `local.properties`, and the build reads the
password from `local.properties` on this PC (or from `JOY_KEYSTORE_PASSWORD`-style environment variables).

## Your steps (about five minutes, once)

1. Open your password manager and make a new entry called **"Joy Creator - Google Play upload key"**.
2. Open `D:\JoyCreator-keys\BACKUP-ME-then-delete.txt` in Notepad. Copy its key file path, the alias `upload`
   and the password into the entry.
3. **Attach the key file itself** to the same entry: `D:\JoyCreator-keys\joycreator-upload.jks` (most
   password managers let you attach a file: 1Password "Add file", Bitwarden "Attachments", Google Password
   Manager cannot: then put the file on a USB stick kept somewhere safe, or in a private cloud folder).
4. Check the entry opens and shows the password and the attached file.
5. Delete `D:\JoyCreator-keys\BACKUP-ME-then-delete.txt` (keep the `.jks` file where it is: the build uses it).
6. Tell the Play build lane "key backed up". That is all.

## What happens in Play Console

On the first upload of the app bundle, Play Console asks to use Play App Signing: say **yes** (the default).
Google then creates the real signing key and uses our upload key only to check uploads. Nothing else to do.

## If it is lost

Play Console > the app > Setup > App signing > "Request upload key reset". A new upload key is made the same way
as this one (the build lane's steps are in the SHOW-1 card); the app and its users are not affected.
