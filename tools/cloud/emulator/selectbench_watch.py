"""Watch logcat for SelectBench JSON rows; for each screenshot request take a screencap and write the ack file.

usage: selectbench_watch.py <out-dir> <package> <run-id> <max-seconds>
Writes results.jsonl (every row), steps.md (pass/fail per step), shot_<event>_<name>.png. Exit 0 only if a summary row
says failed == 0 and passed > 0. Every adb call has a timeout so a dead emulator cannot hang this.
"""
import json
import os
import subprocess
import sys
import time

out, pkg, run_id, limit = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])


def adb(*a, binary=False, timeout=20):
    try:
        r = subprocess.run(["adb", *a], capture_output=True, timeout=timeout)
        return r.stdout if binary else r.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return b"" if binary else ""


seen = set()
rows = []
summary = None
deadline = time.time() + limit
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, "results.jsonl"), "w", encoding="utf-8") as res:
    while time.time() < deadline and summary is None:
        log = adb("logcat", "-b", "main", "-d", "-t", "4000", "-v", "raw", "-s", "JoyBrushPerf:I")
        for line in log.splitlines():
            if not line.startswith("{"):
                continue
            try:
                row = json.loads(line)
            except Exception:
                continue
            if row.get("runId") != run_id or row.get("event") in seen:
                continue
            seen.add(row["event"])
            rows.append(row)
            res.write(json.dumps(row) + "\n")
            res.flush()
            if "selectBenchShot" in row:
                name = row["selectBenchShot"].replace(".png", "")
                png = adb("exec-out", "screencap", "-p", binary=True)
                if png:
                    open(os.path.join(out, "shot_%03d_%s.png" % (row["event"], name)), "wb").write(png)
                # the next event number is the one the bench expects the ack for
                adb("shell", "touch", "/data/data/%s/cache/select-bench-%s-%d.ack" % (pkg, run_id, row["event"]))
                adb("shell", "chmod", "666", "/data/data/%s/cache/select-bench-%s-%d.ack" % (pkg, run_id, row["event"]))
            if row.get("selectBench") == "summary":
                summary = row
        time.sleep(1)

with open(os.path.join(out, "steps.md"), "w", encoding="utf-8") as md:
    md.write("| step | result | detail |\n|---|---|---|\n")
    for r in rows:
        if "selectBench" in r and r["selectBench"] != "summary":
            detail = {k: v for k, v in r.items() if k not in ("selectBench", "pass", "runId", "event")}
            md.write("| %s | %s | %s |\n" % (r["selectBench"], "PASS" if r.get("pass") else "FAIL", json.dumps(detail)[:160]))
    md.write("\nsummary: %s\n" % json.dumps(summary))
print("steps:", len([r for r in rows if "selectBench" in r]), "summary:", summary)
sys.exit(0 if summary and summary.get("failed") == 0 and summary.get("passed", 0) > 0 else 1)
