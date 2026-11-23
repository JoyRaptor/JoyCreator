"""FACE-1: prove the face checks catch what they claim to.

Each plant copies one or two engine files into a scratch folder that is searched first, breaks them
the way a real regression would, runs FaceEngineTest against it, and expects at least one FAIL.
A plant whose text is not found (the engine moved on) is reported, never silently skipped.

Usage:  python -B tools/jvm-harness/face_plants.py      (or run-face.sh --proof)
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PKG = "app/src/main/java/com/fadcam/ui/faditor/avatar/face/"
M, C, S, F, P, G = (PKG + n + ".java" for n in
                    ("FaceMapper", "FaceCalibration", "FaceStage", "FaceFollower", "FacePass", "FaceSignals"))
JS = "tools/avatarlab/al-face.js"
FR = PKG + "FaceFrame.java"
PT = PKG + "FacePoints.java"
SH = PKG + "ShoulderStage.java"
POSE = "tools/avatarlab/al-pose.js"

PLANTS = [
    ("both eyes always blink alike (no wink)", [(M, "br = lids ? lidBlink(cr) : blink(cr, lookDown);", "br = bl;")]),
    ("blinks are on or off", [(M, "return smoothstep(0.12f, 0.8f, closure - 0.4f * lookDown);",
                               "return closure - 0.4f * lookDown > 0.5f ? 1f : 0f;")]),
    ("one value for both brows", [(M, 'o.put(FaceSignals.BROW_R, sgn((0.7f * s.charR("browOuterUp")',
                                   'o.put(FaceSignals.BROW_R, sgn((0.7f * s.charL("browOuterUp")')]),
    ("no neutral: expressions read from zero", [(C, "float r = rest(raw);", "float r = 0f;")]),
    ("the head not measured from neutral", [(C, "return (deg - rest(axis)) * s;", "return deg * s;")]),
    ("a lost face freezes as it was", [(S, "if (t - lostSince >= HOLD_S) presence", "if (false) presence")]),
    ("a lost face lets go at once (a flicker is a loss)", [(S, "static final double HOLD_S = 0.35;",
                                                            "static final double HOLD_S = 0.0;")]),
    ("a returning face jumps", [(S, "crossFrom = new HashMap<>(lastOut);", "crossFrom = null;"),
                                (S, "presence = Math.min(1, presence + (dt > 0 ? dt / EASE_IN_S : 0));",
                                 "presence = 1;")]),
    ("one smoothing setting for everything", [(G, "case G_BLINK: return new float[]{6.0f, 4.0f};",
                                               "case G_BLINK: return new float[]{1.0f, 0.3f};")]),
    ("the screen trails the face (follow error taken at the wrong time)",
     [(F, "double e0 = s[0] - (p - vp * dt)", "double e0 = s[0] - p")]),
    ("no fill-in prediction between tracker frames", [(F, "PREDICT_FRACTION = 1.0;", "PREDICT_FRACTION = 0.0;")]),
    ("a pass overwrites every lane", [(P, "return lane != null && lanes.contains(lane);", "return true;")]),
    ("a pass snaps back at its ends", [(P, "if (d >= FADE_MS) return 0f;", "if (d > 0) return 0f;")]),
    ("lips welded to the jaw", [(M, 'float m = amt((s.amt("jawOpen") - s.amt("mouthClose")) * 1.7f * tun.mouth);',
                                 "float m = jaw;")]),
    ("Mirror does nothing to the sides", [(M, "return mirror ? personL(base) : personR(base);",
                                           "return personR(base);")]),
    ("no automatic first neutral", [(S, "private volatile boolean autoNeutral = true;",
                                     "private volatile boolean autoNeutral = false;")]),
    ("a moving head is taken as neutral", [(C, "if (maxYaw - minYaw > MAX_SPREAD_DEG || maxPitch - minPitch > MAX_SPREAD_DEG) {",
                                            "if (false) {")]),
    ("closed eyes roll their gaze", [(S, "if (shut > 0.55f) {", "if (false) {")]),
    ("a sheet mouth flickers", [(S, "m.put(FaceSignals.VIS, vis);", "vis = v;")]),
    ("a lost face stares (no idle blinks)", [(S, "if (w <= 0f) return;", "if (true) return;")]),
    ("leaning goes the wrong way", [(C, "private int signLean = -1;", "private int signLean = 1;")]),
    ("the direction check is not applied", [(C, "return EYE.equals(what) ? cal.withSwapSides(answer < 0) : cal.withSign(what, answer);",
                                             "return cal;")]),
    ("lopsided blinks (no eye sync)", [(M, "if (tun.blinkSync) {", "if (false) {")]),
    ("the first half-second shows the uncalibrated face, then jumps",
     [(S, "if (autoNeutral && !c.isNeutralSet()) {", "if (false) {")]),
    ("the pilot's blinks are on or off (pilot and phone disagree)",
     [(JS, "function blink(closure, lookDown) { return smoothstep(0.12, 0.8, closure - 0.4 * lookDown); }",
       "function blink(closure, lookDown) { return closure - 0.4 * lookDown > 0.5 ? 1 : 0; }")]),
    ("the pilot moves both brows as one (pilot and phone disagree)",
     [(JS, "o.browR = sgn((0.7 * cR('browOuterUp')", "o.browR = sgn((0.7 * cL('browOuterUp')")]),
    ("the pilot's lost face freezes (pilot and phone disagree)",
     [(JS, "if (t - this.lostSince >= HOLD) this.presence", "if (false) this.presence")]),
    ("a played-back sheet mouth blends through other shapes",
     [("app/src/main/java/com/fadcam/ui/faditor/avatar/AvatarParamTrack.java",
       "            com.fadcam.ui.faditor.avatar.face.FaceSignals.VIS)); // FACE-1: the camera's sheet-mouth shape",
       "            \"none\")); // planted")]),
    ("the face's points stay in the camera's sideways frame (the outline a quarter turn off)",
     [(FR, "case 270: return new float[]{y, 1f - x};", "case 270: return new float[]{x, y};")]),
    ("a neutral saved in the sideways frame is kept (lean stays sideways)",
     [(C, "if (v < 2) {", "if (false) {")]),
    ("an older neutral keeps answers made for the old directions",
     [(C, "if (v < 3) {", "if (false) {")]),
    ("face pins move when the head turns (the head's turn not taken out)",
     [(PT, "double ux = r[0] * x + r[3] * y + r[6] * z;", "double ux = x;"),
      (PT, "double uy = r[1] * x + r[4] * y + r[7] * z;", "double uy = y;")]),
    ("face pins move when the face comes closer (its size not taken out)",
     [(PT, "double s = Math.sqrt(ss / STEADY.length);", "double s = 0.05;")]),
    ("face pins go the wrong way across the screen",
     [(PT, "out[k * 2] = (float) ((ux - rest[i * 3]) * side);", "out[k * 2] = (float) (ux - rest[i * 3]);")]),
    ("the neutral forgets where the face points rest",
     [(C, "                c.restPoints = pts;", "                /* forgotten */")]),
    ("looking down reads as a half blink", [(M, "return smoothstep(0.12f, 0.8f, closure - 0.4f * lookDown);",
                                             "return smoothstep(0.12f, 0.8f, closure);")]),
    # the lids (the owner's eye test, 11-22)
    ("the lids ignored: only the tracker's weak blink value", [(M, "boolean lids = gl >= 0f && gr >= 0f;", "boolean lids = false;")]),
    ("the stage never measures the lids", [(S, "float[] gaps = FacePoints.eyeGaps(pts);", "float[] gaps = null;")]),
    ("the lids measured on the wrong eye", [(S, "raw.put(FacePoints.GAP_LEFT, gaps[0]);", "raw.put(FacePoints.GAP_LEFT, gaps[1]);"),
                                            (S, "raw.put(FacePoints.GAP_RIGHT, gaps[1]);", "raw.put(FacePoints.GAP_RIGHT, gaps[0]);")]),
    ("an old neutral keeps the weak blinks (never taken again)",
     [(S, "boolean noLids = (raw.containsKey(FacePoints.GAP_LEFT) && c.rest(FacePoints.GAP_LEFT) <= 0f)", "boolean noLids = false && ((raw.containsKey(FacePoints.GAP_LEFT) && c.rest(FacePoints.GAP_LEFT) <= 0f)"),
      (S, "|| (raw.containsKey(FaceCalibration.FACE_SPREAD) && c.rest(FaceCalibration.FACE_SPREAD) <= 0f);", "|| (raw.containsKey(FaceCalibration.FACE_SPREAD) && c.rest(FaceCalibration.FACE_SPREAD) <= 0f));")]),
    ("a smile or looking down reads as a blink", [(M, "return smoothstep(0.3f, 0.75f, closure);", "return smoothstep(0.05f, 0.75f, closure);")]),
    ("the pilot ignores the lids (pilot and phone disagree)", [(JS, "var lids = gl >= 0 && gr >= 0;", "var lids = false;")]),
    ("the pilot measures the lids on the wrong eye (pilot and phone disagree)",
     [(JS, "raw[F.GAP_LEFT] = gaps[0]; raw[F.GAP_RIGHT] = gaps[1];", "raw[F.GAP_LEFT] = gaps[1]; raw[F.GAP_RIGHT] = gaps[0];")]),
    ("the pilot keeps an old neutral (pilot and phone disagree)",
     [(JS, "var noLids = (raw[F.GAP_LEFT] != null", "var noLids = false && (raw[F.GAP_LEFT] != null"),
      (JS, "|| (raw[F.FACE_SPREAD] != null && !(rest(this.cal, F.FACE_SPREAD) > 0));", "|| false;")]),
    ("near read from the outline's width (a nod walks him closer)",
     [(C, "float near = !Float.isNaN(faceSpread) && spread0 > 1e-4f && faceSpread > 1e-4f ? faceSpread / spread0 - 1f : faceSize / size0 - 1f;",
       "float near = faceSize / size0 - 1f;")]),
    ("an old neutral without the face's 3D size is kept", [(S, "|| (raw.containsKey(FaceCalibration.FACE_SPREAD) && c.rest(FaceCalibration.FACE_SPREAD) <= 0f);", ";")]),
    ("the pilot reads near from the outline (pilot and phone disagree)",
     [(JS, "var near = spread === spread && spread != null && spread0 > 1e-4 && spread > 1e-4 ? spread / spread0 - 1 : size / size0 - 1;", "var near = size / size0 - 1;")]),
    ("a head tilt reads as a lean (the swing about the neck left in)", [(M, "static final double TILT_SWING = 0.95;", "static final double TILT_SWING = 0.0;")]),
    ("the pilot leaves the tilt in its lean (pilot and phone disagree)", [(JS, "if (ln) ln[0] -= TILT_SWING * Math.sin(tiltR * Math.PI / 180);", "")]),
    # shoulders (SHRUG-1)
    ("a head tilt reads as a shrug (each shoulder measured from its own ear)",
     [(SH, "return new float[]{(y(xyv, SHOULDER_R, tall) - earY) / w, (y(xyv, SHOULDER_L, tall) - earY) / w};",
       "return new float[]{(y(xyv, SHOULDER_R, tall) - y(xyv, EAR_R, tall)) / w, (y(xyv, SHOULDER_L, tall) - y(xyv, EAR_L, tall)) / w};")]),
    ("coming closer reads as a shrug (not measured in shoulder widths)", [(SH, "if (w < 1e-4f) return null;", "if (w < 1e-4f) return null; w = 0.44f;")]),
    ("the shrug on the wrong side", [(SH, "lastL = mirror ? pr : pl;", "lastL = mirror ? pl : pr;"), (SH, "lastR = mirror ? pl : pr;", "lastR = mirror ? pr : pl;")]),
    ("Mirror does nothing to the shoulders", [(SH, "lastL = mirror ? pr : pl;", "lastL = pl;"), (SH, "lastR = mirror ? pl : pr;", "lastR = pr;")]),
    ("jitter and breathing shrug (no dead zone)", [(SH, "static final float DEAD = 0.04f;", "static final float DEAD = 0.0f;")]),
    ("lost shoulders freeze", [(SH, "presence = Math.max(0, presence - dt / EASE_OUT_S);", "presence = presence;")]),
    ("lost shoulders drop at once (a flicker is a loss)", [(SH, "static final double HOLD_S = 0.35, EASE_OUT_S = 0.8, EASE_IN_S = 0.3;",
                                                          "static final double HOLD_S = 0.0, EASE_OUT_S = 0.8, EASE_IN_S = 0.3;")]),
    ("returning shoulders jump", [(SH, "presence = Math.min(1, presence + dt / EASE_IN_S);", "presence = 1;")]),
    ("no rest of its own (shrugs read from zero)", [(SH, "private boolean wantNeutral = true;", "private boolean wantNeutral = false;")]),
    ("a moving body is taken as the rest", [(SH, "if (capN > 0 && (Math.abs(d[0] - cap0L) > STILL || Math.abs(d[1] - cap0R) > STILL)) capN = 0;", "")]),
    ("Re-centre leaves the shoulders' rest", [(SH, "public void requestNeutral() { wantNeutral = true; capN = 0; }", "public void requestNeutral() { }")]),
    ("the sensitivity slider does nothing", [(SH, "float pl = lift(restL - smL, sensitivity), pr = lift(restR - smR, sensitivity);", "float pl = lift(restL - smL, 1f), pr = lift(restR - smR, 1f);")]),
    ("sensitivity scales the jitter too (no fixed dead zone)", [(SH, "float v = (nearer - DEAD) * sensitivity / (SPAN - DEAD);", "float v = (nearer * sensitivity - DEAD) / (SPAN - DEAD);")]),
    ("the pilot ignores the sensitivity (pilot and phone disagree)", [(POSE, "var pl = P.lift(this.restL - this.smL, this.sensitivity), pr = P.lift(this.restR - this.smR, this.sensitivity);", "var pl = P.lift(this.restL - this.smL, 1), pr = P.lift(this.restR - this.smR, 1);")]),
    ("the pilot's shrug on the wrong side (pilot and phone disagree)", [(POSE, "this.lastL = mirror ? pr : pl;", "this.lastL = mirror ? pl : pr;")]),
    ("the pilot measures each shoulder from its own ear (pilot and phone disagree)",
     [(POSE, "return [(py(xyv, P.SHOULDER_R, tall) - earY) / w, (py(xyv, P.SHOULDER_L, tall) - earY) / w];",
       "return [(py(xyv, P.SHOULDER_R, tall) - py(xyv, P.EAR_R, tall)) / w, (py(xyv, P.SHOULDER_L, tall) - py(xyv, P.EAR_L, tall)) / w];")]),
]


def main():
    caught, broken = 0, []
    bash = shutil.which("bash") or "bash"
    for name, edits in PLANTS:
        tmp = tempfile.mkdtemp(prefix="face_plant_")
        try:
            ok = True
            env_extra = {}
            for path, old, new in edits:
                src = os.path.join(ROOT, path)
                if path == JS:
                    dst = os.path.join(tmp, "al-face.js")
                    env_extra["FACE_PILOT_JS"] = dst
                elif path == POSE:
                    dst = os.path.join(tmp, "al-pose.js")
                    env_extra["POSE_PILOT_JS"] = dst
                else:
                    dst = os.path.join(tmp, "plant", path.split("app/src/main/java/")[1])
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                text = open(dst if os.path.exists(dst) else src, encoding="utf-8").read()
                if text.count(old) != 1:
                    ok = False
                    break
                open(dst, "w", encoding="utf-8", newline="").write(text.replace(old, new))
            if not ok:
                broken.append(name)
                print("BROKEN PLANT (text not found once): " + name)
                continue
            env = dict(os.environ, FACE_PLANT_DIR=os.path.join(tmp, "plant").replace("\\", "/"),
                       FACE_OUT=os.path.join(tmp, "out").replace("\\", "/"), **env_extra)
            r = subprocess.run([bash, "tools/jvm-harness/run-face.sh"], cwd=ROOT, env=env,
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
            fails = [l[6:] for l in r.stdout.splitlines() if l.startswith("FAIL  ")]
            if r.returncode == 1 and fails:
                caught += 1
                print("CAUGHT  %-62s by: %s" % (name, fails[0]))
            elif r.returncode == 2:
                broken.append(name)
                print("BROKEN PLANT (does not compile): " + name + "\n" + r.stdout[-800:] + r.stderr[-800:])
            else:
                print("MISSED  " + name)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    total = len(PLANTS)
    print("%d of %d planted faults caught%s" % (caught, total, "" if not broken else "; %d plants broken" % len(broken)))
    sys.exit(0 if caught == total else 1)


if __name__ == "__main__":
    main()
