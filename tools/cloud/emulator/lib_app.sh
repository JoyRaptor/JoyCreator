# App-level helpers for the phone scripts (sourced after lib.sh).

# through_intro: from a fresh install to the lobby (the intro's "Start creating" and "Continue" pages).
through_intro() {
    launch_app
    settle 8
    local i label
    for i in 1 2 3 4 5 6; do
        for label in "Start creating" "Continue" "Skip" "Not now" "Allow" "Got it"; do
            tap_text "$label" >/dev/null 2>&1 && break
        done
        settle 3
        adb shell dumpsys activity activities | grep -q "com.fadcam.MainActivity" && break
    done
}
