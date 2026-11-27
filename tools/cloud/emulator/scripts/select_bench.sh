# SelectBench on the emulator (JoyBrush Select: pick, lasso, move, rotate, copy/paste, Cut, Undo/Redo, Done, group, lock,
# nudge). Line steps only: the bench draws with a synthetic pen, which the emulator takes as events; real pen pressure and
# tilt stay on the Note 9. Uses the debug-only JoyBrushBenchActivity.
RID=$(python3 -c "import uuid; print(uuid.uuid4().hex)")
echo "run id $RID"
adb logcat -c
adb shell am start -n "$PKG/com.fadcam.joybrush.JoyBrushBenchActivity" --es jb_bench select --es jb_select_run_id "$RID" --el jb_start_ms "${BENCH_START_MS:-12000}" --ez jb_no_shots "${BENCH_NO_SHOTS:-true}" >/dev/null
settle 8
shot bench_started
python3 "$HERE_LIB/selectbench_watch.py" "$OUT/selectbench" "$PKG" "$RID" 420
code=$?
shot bench_end
cat "$OUT/selectbench/steps.md" 2>/dev/null
exit $code
