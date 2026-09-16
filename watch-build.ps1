# watch-build.ps1 - continuous rebuild of the FadCam debug build.
#
# Uses Gradle's built-in continuous build (-t): it watches the inputs itself and
# rebuilds on every source save. The outer loop just restarts Gradle if continuous
# mode exits (e.g. after a hard failure or Ctrl+C-once).
#
# Run it:
#   powershell -ExecutionPolicy Bypass -File "C:\+Projects\Screenrecorder\FadCam\watch-build.ps1"
#
# IT BUILDS. IT DOES NOT INSTALL. (Changed 2026-09-16.)
#
# It used to run `installDefaultDebug`, and that is what kept killing the phone
# connection: every install restarts the adb server, which drops the wireless
# session, and the phone's listener then comes back on a different port. With the
# USB connector damaged (tasks/WIRELESS_ADB_CONNECT.md) wireless is the only path,
# so the watcher was knocking out the very connection it existed to serve. It also
# made every build report BUILD FAILED whenever no phone was attached — the compile
# was clean and only the install had failed — which cost one session about an hour
# chasing a compile error that was never there (tasks/LEDGER.md 2026-09-15).
#
# So: this compiles and packages the APK, and nothing touches adb. To put a build on
# the phone, install it explicitly, which takes a few seconds and breaks nothing:
#
#   bash tools/wifi-adb.sh        # connect, if the connection has dropped
#   bash tools/phone.sh install   # install app-default-arm64-v8a-debug.apk
#
# All output is appended to build.log (UTF-16), so an agent can poll the tail and
# wait for "BUILD SUCCESSFUL" followed by "Waiting for changes to input files".
# Keep ONE instance running. Press Ctrl+C twice to stop.

Set-Location "C:\+Projects\Screenrecorder\FadCam"

while ($true) {
    .\gradlew.bat -t :app:assembleDefaultDebug --console=plain 2>&1 |
        Tee-Object -FilePath build.log -Append
    Start-Sleep -Seconds 5
}
