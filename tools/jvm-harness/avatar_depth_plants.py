"""Walking toward the camera and away: prove AvatarDepthWalkTest catches what it claims to.

Each plant copies Walker.java, DepthWalk.java or WalkerLegs.java into a scratch folder (run-avatar-depth.sh compiles it instead), breaks it the way
a real regression would, and expects at least one FAIL. A plant whose text is not found (the code moved on) is reported.

Usage:  python -B tools/jvm-harness/avatar_depth_plants.py      (or run-avatar-depth.sh --proof)
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
P = "app/src/main/java/cc/joycreator/avatar/perform/"
W, D = P + "Walker.java", P + "DepthWalk.java"
L = "app/src/main/java/cc/joycreator/avatar/face/WalkerLegs.java"

PLANTS = [
    ("the ground line never walks in depth", [(W, "            baseY += dz;\n", "")]),
    ("it walks in depth as fast as it likes (no cap)", [(W, "            if (Math.abs(dz) > capZ) dz *= capZ / Math.abs(dz);\n", "")]),
    ("its size does not follow depth", [(W, "* (z != null ? z.s(baseY) : 1);", ";")]),
    ("the body is left behind (only the ground line moves)", [(W, "            y += dz;\n            ty += dz;", "            ty += dz;")]),
    ("Home snaps back instead of walking", [(W, "if (z != null && baseY != 0) tz = 0.0;", "if (false) tz = 0.0;")]),
    ("no turn to the front drawing", [(W, "S.turnX += (z.front - S.turnX) * fw;", "S.turnX += 0;")]),
    ("it may go too far away", [(D, "NEAR = 0.45, FAR = 0.35;", "NEAR = 0.45, FAR = 0.5;")]),
    ("the camera's angle is ignored", [(D, "return Math.sin(Math.toRadians(clamp(deg, 0, 89))) / Math.sin(Math.toRadians(ANGLE));", "return 1;")]),
    ("the depth model is never let go", [(D, "        if (!want && !still) {", "        if (false) {")]),
    ("no front drawing found", [(D, "            if (d <= bd) {", "            if (false) {")]),
    ("the room's walker never walks in depth for the face", [(L, "return w.z != null && w.canWalk();", "return false;")]),
    ("the face's depth goes to the wrong limit", [(L, "new double[]{w.z.lim[0], w.z.lim[1]}", "new double[]{w.z.lim[1], w.z.lim[0]}")]),
]


def main():
    caught, broken = 0, []
    bash = shutil.which("bash") or "bash"
    for name, edits in PLANTS:
        tmp = tempfile.mkdtemp(prefix="depth_plant_")
        try:
            ok = True
            for path, old, new in edits:
                dst = os.path.join(tmp, "plant", os.path.basename(path))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                text = open(dst if os.path.exists(dst) else os.path.join(ROOT, path), encoding="utf-8").read().replace("\r\n", "\n")
                if text.count(old) != 1:
                    ok = False
                    break
                open(dst, "w", encoding="utf-8", newline="").write(text.replace(old, new))
            if not ok:
                broken.append(name)
                print("BROKEN PLANT (text not found once): " + name)
                continue
            env = dict(os.environ, DEPTH_PLANT_DIR=os.path.join(tmp, "plant").replace("\\", "/"), DEPTH_OUT=os.path.join(tmp, "out").replace("\\", "/"))
            r = subprocess.run([bash, "tools/jvm-harness/run-avatar-depth.sh"], cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
            fails = [l[6:] for l in r.stdout.splitlines() if l.startswith("FAIL  ")]
            if r.returncode == 1 and fails:
                caught += 1
                print("CAUGHT  %-56s by: %s" % (name, fails[0][:90]))
            elif r.returncode == 2:
                broken.append(name)
                print("BROKEN PLANT (does not compile): " + name + "\n" + r.stdout[-600:] + r.stderr[-600:])
            else:
                print("MISSED  " + name)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    total = len(PLANTS)
    print("%d of %d planted faults caught%s" % (caught, total, "" if not broken else "; %d plants broken" % len(broken)))
    sys.exit(0 if caught == total else 1)


if __name__ == "__main__":
    main()
