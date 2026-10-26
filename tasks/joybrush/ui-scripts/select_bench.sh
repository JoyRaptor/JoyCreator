#!/bin/bash
# Usage: select_bench.sh <out-dir> [lane-name]
# SELECT_BENCH_BUILD_RECEIPT: receipt for the preserved successful exact-source APK.
# SELECT_BENCH_CASE: select (default) or selectA10 (separate six-report/five-shot UI proof).
set -euo pipefail
HERE=$(cd -- "$(dirname -- "$0")" && pwd)
. "/c/+Projects/Screenrecorder/FadCam/tools/devices.local.sh"
export SANDBOX_SERIAL
export PATH="/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools:$PATH"
exec python -B "$HERE/run_select_bench.py" "${1:?out dir}" "${2:-Select lines specialist}"
