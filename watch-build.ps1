# watch-build.ps1 - continuous rebuild + reinstall of the FadCam debug build.
#
# Uses Gradle's built-in continuous build (-t): it watches the inputs itself and
# rebuilds + reinstalls on every source save. The outer loop just restarts Gradle
# if continuous mode exits (e.g. after a hard failure or Ctrl+C-once).
#
# Run it:
#   powershell -ExecutionPolicy Bypass -File "C:\+Projects\Screenrecorder\FadCam\watch-build.ps1"
#
# All output is appended to build.log (UTF-16), so an agent can poll the tail and
# wait for "BUILD SUCCESSFUL" followed by "Waiting for changes to input files".
# Keep ONE instance running. Press Ctrl+C twice to stop.

Set-Location "C:\+Projects\Screenrecorder\FadCam"

while ($true) {
    .\gradlew.bat -t installDefaultDebug --console=plain 2>&1 |
        Tee-Object -FilePath build.log -Append
    Start-Sleep -Seconds 5
}
