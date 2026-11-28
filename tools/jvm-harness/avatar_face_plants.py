"""The phone room's face: prove AvatarFaceTest catches what it claims to.

Each plant copies one source into a scratch folder searched first, breaks it the way a real regression would, runs
AvatarFaceTest and AvatarFaceTakeTest (the owner's real face take) against it and expects at least one FAIL. A plant whose text is not found (the code moved on) is reported,
never silently skipped.

Usage:  python -B tools/jvm-harness/avatar_face_plants.py      (or run-avatar-face.sh --proof)
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
D = "app/src/main/java/cc/joycreator/avatar/face/FaceDrive.java"
W = "app/src/main/java/cc/joycreator/avatar/face/FaceWalk.java"
I = "app/src/main/java/cc/joycreator/avatar/perform/Inputs.java"
MC = "app/src/main/java/cc/joycreator/avatar/face/MirrorChip.java"
FM = "app/src/main/java/com/fadcam/ui/faditor/avatar/face/FaceMapper.java"
TR = "app/src/main/java/cc/joycreator/avatar/perform/TakeRecorder.java"
DP = "app/src/main/java/cc/joycreator/avatar/face/DirectionPrompts.java"
MD = "app/src/main/java/cc/joycreator/avatar/face/MaskDrive.java"
FPN = "app/src/main/java/cc/joycreator/avatar/face/FacePins.java"
M = "app/src/main/java/cc/joycreator/avatar/ui/SignalMap.java"
SF = "app/src/main/java/com/fadcam/ui/faditor/avatar/face/ShoulderFeed.java"
SH = "app/src/main/java/cc/joycreator/avatar/face/Shrugs.java"
CP = "app/src/main/java/com/fadcam/ui/faditor/avatar/face/CameraPlan.java"
FWD = "app/src/main/java/cc/joycreator/avatar/face/FaceWalkDrawer.java"

PLANTS = [
    ("the chip skips while-talking-only", [(D, "mode == Mode.ALWAYS ? Mode.TALK : Mode.OFF", "mode == Mode.ALWAYS ? Mode.OFF : Mode.OFF")]),
    ("while-talking-only drives all the time", [(D, "return mode == Mode.ALWAYS || (mode == Mode.TALK && talking);", "return mode != Mode.OFF;")]),
    ("the head jumps to the face's own turn (no taking over from the pad)",
     [(D, "o.turnX = clamp(zero[3] + (fx - zero[0]), -1, 1);", "o.turnX = clamp(fx, -1, 1);")]),
    ("turning your head barely turns his (the owner's 11-27 report)",
     [(D, "o.turnX = clamp(zero[3] + (fx - zero[0]), -1, 1);", "o.turnX = clamp(zero[3] + (fx - zero[0]) * 0.1, -1, 1);")]),
    ("nodding does not nod him", [(D, "o.turnY = clamp(zero[4] + (fy - zero[1]), -1, 1);", "o.turnY = clamp(zero[4], -1, 1);")]),
    ("Re-centre keeps the old starting place", [(D, "    public void recentre() {\n        zero = null;", "    public void recentre() {\n        zero = zero;")]),
    ("a finger on the pad loses to the face", [(D, "if (!headHeld) {", "if (true) {")]),
    ("a mood button is ignored", [(D, "if (!moodHeld || talking) {", "if (true) {")]),
    ("a smile's mouth shape is lost", [(D, 'o.vis = o.mouth > 0.12 ? (smile > 0.5 ? "Ee" : "Aa") : "Neutral";', 'o.vis = o.mouth > 0.12 ? "Aa" : "Neutral";')]),
    ("both brows move as one", [(D, 'o.browR = g(face, "browR");', 'o.browR = g(face, "browL");')]),
    ("the face's lanes never let go", [(D, "} else for (String k : smPt.keySet()) want.put(k, 0.0);", "} else for (String k : smPt.keySet()) want.put(k, smPt.get(k));")]),
    ("expressions are not lanes", [(D, "out.put(FACE + n, c);", "")]),
    ("the face's lanes have no owner in a take", [(D, 'return "facepins";\n        if (lane.equals("shrugL")', 'return null;\n        if (lane.equals("shrugL")')]),
    ("any drift walks (no dead zone)", [(W, "DEAD_X = 0.18, DEAD_Z = 0.15;", "DEAD_X = 0.0, DEAD_Z = 0.15;")]),
    ("a head tilt walks", [(W, "double u = roll > TILT || turn > TURN ? 0 : past(lx, DEAD_X);", "double u = turn > TURN ? 0 : past(lx, DEAD_X);")]),
    ("a turned head walks (his take: turning right walked Topps right)", [(W, "double u = roll > TILT || turn > TURN ? 0 : past(lx, DEAD_X);", "double u = roll > TILT ? 0 : past(lx, DEAD_X);")]),
    ("a head turned even a little holds the walk (his leans stop walking)", [(W, "TURN = 0.5;", "TURN = 0.2;")]),
    ("it walks past the edge", [(W, "w.setTx(Math.max(-limX, Math.min(limX, v)));", "w.setTx(v);")]),
    ("the face keeps the body after it stops", [(W, "static final double LET_GO_S = 0.4;", "static final double LET_GO_S = 99;")]),
    ("a take that plays does not get the body back", [(W, "            own = false;\n            return false;", "            return false;")]),
    ("a finger on the body loses to the face", [(W, "if (!w.held()) {", "if (true) {")]),
    ("Up close needs a face", [(W, "if (upClose) {", "if (upClose && seen) {")]),
    ("closer walks away", [(W, "w.setTz(n > 0 ? n * zl[1] : -n * zl[0]);", "w.setTz(n > 0 ? n * zl[0] : -n * zl[1]);")]),
    # the room's own hands (Inputs)
    ("in the room the head jumps to the face's own turn (the old hookup)", [(I, "double tx = fo.head ? fo.turnX : turnX,", "double tx = faceOn && face != null && face.get(\"turnX\") != null ? face.get(\"turnX\") : turnX,")]),
    ("the idle sway keeps going while the face drives", [(I, "settings.optBoolean(\"idle\", true) && !faceDrives ? 1 : 0;", "settings.optBoolean(\"idle\", true) ? 1 : 0;")]),
    ("the face's wink is lost", [(I, "m.put(\"blinkL\", faceEx ? fo.blinkL : blink ? 1.0 : 0.0);", "m.put(\"blinkL\", blink ? 1.0 : 0.0);")]),
    ("the face's lanes are not in a take's list", [(I, "for (String k : faceLanes) if (!n.contains(k)) n.add(k);", "")]),
    ("the face's lanes have no owner while it drives", [(I, "o.add(\"facepins\");", "")]),
    ("in the room, while-talking-only drives all the time", [(I, "faceDrive.apply(faceOn ? face : null, talking,", "faceDrive.apply(faceOn ? face : null, true,")]),
    ("the face's lanes never reach the solver (face pins move nothing)", [(M, "s.extra.put(k, ((Number) e.getValue()).doubleValue());   // face pins", "{ }   // face pins")]),
    ("the Mirror chip shows the engine's flag as it is (off while it reflects)", [(MC, "return t == null || !t.mirror;", "return t != null && t.mirror;")]),
    ("the Mirror chip sets the engine's flag as it is (on = like someone facing you)", [(MC, "t.mirror = !on;", "t.mirror = on;")]),
    ("nodding down lowers his brows again (the tracker's view from below)", [(FM, "NOD_BROW_FROM = 8.5, NOD_BROW = 0.033;", "NOD_BROW_FROM = 8.5, NOD_BROW = 0.0;")]),
    ("the Puff button puffs nothing", [(D, "if (puff && n.equals(\"cheekPuff\")) v = 1;", "")]),
    ("holding Puff does not own its lane", [(I, "        if (faceDrive.puffing()) o.add(\"puff\");\n", "")]),
    ("the puff lane belongs to the face pins again (a pass with Puff held records nothing)", [(TR, "        if (name.equals(\"face.cheekPuff\")) return \"puff\";", "")]),
    ("Check directions asks for the first move every time", [(DP, "face.check(STEPS[at]);", "face.check(STEPS[0]);")]),
    ("Check directions does not count a move made", [(DP, "        if (ok) answered++;\n", "")]),
    ("the mask ignores Mirror (its right side follows your right)", [(MD, "int j = mirrored && b != null ? b : a;", "int j = a;")]),
    ("a pose may be held past 1.5", [(MD, "Math.max(0, Math.min(1.5, wt[i] + dot / cc[i]))", "Math.max(0, Math.min(9, wt[i] + dot / cc[i]))")]),
    ("a pose may be held backwards", [(MD, "Math.max(0, Math.min(1.5, wt[i] + dot / cc[i]))", "Math.max(-9, Math.min(1.5, wt[i] + dot / cc[i]))")]),
    ("the face does not drive the mask", [(D, "        if (maskSet != null) MaskDrive.drive(maskSet, smPt, maskMirrored, maskRest, out);", "")]),
    ("the camera is not asked to watch the mask's points", [(MD, "                if (!out.contains(a)) out.add(a);\n", "")]),
    ("a face pin stays on the slice tapped (no one piece)", [(FPN, "            if (\"set\".equals(p.optString(\"type\")) && pk != null && \"turn\".equals(pk.optString(\"by\"))) return path.get(i);", "")]),
    ("a face pin's across and up-and-down Shapes follow the wrong way", [(FPN, '.put("sig", "pt." + pt + ".x")', '.put("sig", "pt." + pt + ".y")')]),
    ("taking a face pin off leaves the pin made for it", [(FPN, "        if (q.optBoolean(\"made\") && !used && mesh != null && mesh.optJSONArray(\"pins\") != null) {", "        if (false) {")]),
    ("the All pins dial is ignored", [(FPN, "            if (x != null && id.equals(x.optString(\"face\"))) x.put(\"amt\", v * gain(ch));", "            if (x != null && id.equals(x.optString(\"face\"))) x.put(\"amt\", v);")]),
    ("the camera starts but the face does not drive", [(I, "if (on && faceDrive.mode() == cc.joycreator.avatar.face.FaceDrive.Mode.OFF) faceDrive.setMode(cc.joycreator.avatar.face.FaceDrive.Mode.ALWAYS);", "")]),
    # shoulder shrugs (SHRUG-1): the camera's side (ShoulderFeed), the rig's questions (Shrugs) and Motion's Try (Inputs)
    ("the shrug steps to each body reading (no glide: the owner's 11-22 'a little bit jerky')", [(SF, "        return Math.max(0, Math.min(1, e[0]));", "        return Math.max(0, Math.min(1, target));")]),
    ("the shrug's glide overshoots (too little damping)", [(SF, "e[1] += (GLIDE * GLIDE * (target - e[0]) - 2 * GLIDE * e[1]) * h;", "e[1] += (GLIDE * GLIDE * (target - e[0]) - 0.5 * GLIDE * e[1]) * h;")]),
    ("Shoulders off freezes the last shrug", [(SF, "        if (!on && g(o, ShoulderStage.BODY) > 0) {", "        if (false) {")]),
    ("the shoulders ignore Mirror", [(SF, "        Map<String, Float> o = stage.process(d, t, mirror);", "        Map<String, Float> o = stage.process(d, t, false);")]),
    ("shoulders out of the picture are never counted (no hint)", [(SF, "            unseenS += dt;", "            unseenS = 0;")]),
    ("Re-centre forgets the shoulders", [(SF, "    public synchronized void requestNeutral() {\n        stage.requestNeutral();", "    public synchronized void requestNeutral() {\n")]),
    ("Shoulder sensitivity does nothing", [(SF, "        stage.setSensitivity(sensitivity);", "        stage.setSensitivity(1f);")]),
    ("the arms lift a third as far as the pilot's", [(SH, "if (armL != null) { double l = armLength(armL, b) * 0.6;", "if (armL != null) { double l = armLength(armL, b) * 0.2;")]),
    ("No shrugs leaves the Links on", [(SH, "            for (String role : new String[]{\"armL\", \"armR\", \"head\"}) strip(firstRole(r, role));", "")]),
    ("a character that said no shrugs still shows the Shoulders tick box", [(SH, "        return !ch.optBoolean(\"noShrug\") && rigs(ch) && !has(ch);", "        return rigs(ch) && !has(ch);")]),
    ("a part's own shrug Link is not seen (no Shoulders tick box for it)", [(SH, "l.optJSONObject(i).optString(\"sig\").startsWith(SHRUG)) return true;", "l.optJSONObject(i).optString(\"sig\").startsWith(\"never\")) return true;")]),
    ("Try does nothing", [(I, "        if (tryShrug > 0) for (String k : SHRUG_LANES) {", "        if (false) for (String k : SHRUG_LANES) {")]),
    ("Try stays up after the finger lifts", [(I, "        tryShrug = Math.max(0, Math.min(1, v));", "        tryShrug = Math.max(tryShrug, Math.min(1, v));")]),
    # X6 audit P3: values that are not numbers (a take is JSON)
    ("an infinite camera value reaches the lanes (only NaN was caught)", [(D, "        return v == null || !Float.isFinite(v) ? 0 : v;", "        return v == null || v.isNaN() ? 0 : v;")]),
    ("a shrug that is not a number reaches its lane", [(D, "            if (v != null && Float.isFinite(v)) { o.lanes.put(k, (double) v); shrugSeen = true; }", "            if (v != null) { o.lanes.put(k, (double) v); shrugSeen = true; }")]),
    ("one body reading that is not a number stops the shoulders for good", [(SF, "        if (d != null && (!Float.isFinite(d[0]) || !Float.isFinite(d[1]))) d = null;", "")]),
    ("X6 P10: the mask's lanes have no owner in a take (a second pass replays the old mask)", [(TR, 'if (p.equals("pt.") || p.equals("mk.") || p.equals("mp.") || name.startsWith("face.")) return "facepins";', 'if (p.equals("pt.") || name.startsWith("face.")) return "facepins";')]),
    ("X6: a face control writes a palette grey as hex again", [(FWD, "note.setTextColor(Studio.INK_FAINT);", "note.setTextColor(0xFF71717A);")]),
    # cameras per tracker (the owner, 11-27): what a phone offers and what opens (CameraPlan)
    ("a back camera that zooms out is never offered wide", [(CP, "            else if (back.minZoom < WIDE_ZOOM) out.add(", "            else if (false) out.add(")]),
    ("an ultra-wide listed as its own camera is not used", [(CP, "            if (wide != null) out.add(new Choice(BACK_WIDE, wide, 1f));", "            if (false) out.add(new Choice(BACK_WIDE, wide, 1f));")]),
    ("Back opens the last back lens listed, not the main one", [(CP, "            if (l.back && back == null) back = l;", "            if (l.back) back = l;")]),
    ("two cameras opened without asking the phone", [(CP, "        return new Plan(face, face, false, true, face.zoom);", "        return new Plan(face, b, true, false, face.zoom);")]),
    ("a refused pair says nothing", [(CP, "        return new Plan(face, face, false, true, face.zoom);", "        return new Plan(face, face, false, false, face.zoom);")]),
    ("the body stays on Front when the face moves", [(CP, "        if (b == null) b = face;", "        if (b == null) b = find(choices, FRONT);")]),
    ("Back and Back wide on one camera keep the narrow view", [(CP, "return new Plan(face, b, false, false, Math.min(face.zoom, b.zoom));", "return new Plan(face, b, false, false, face.zoom);")]),
]


def main():
    caught, broken = 0, []
    bash = shutil.which("bash") or "bash"
    for name, edits in PLANTS:
        tmp = tempfile.mkdtemp(prefix="avatar_face_plant_")
        try:
            ok = True
            for path, old, new in edits:
                src = os.path.join(ROOT, path)
                dst = os.path.join(tmp, "plant", path.split("app/src/main/java/")[1])
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                text = open(dst if os.path.exists(dst) else src, encoding="utf-8").read().replace("\r\n", "\n")
                if text.count(old) != 1:
                    ok = False
                    break
                open(dst, "w", encoding="utf-8", newline="").write(text.replace(old, new))
            if not ok:
                broken.append(name)
                print("BROKEN PLANT (text not found once): " + name)
                continue
            env = dict(os.environ, FACE_PLANT_DIR=os.path.join(tmp, "plant").replace("\\", "/"),
                       FACE_OUT=os.path.join(tmp, "out").replace("\\", "/"))
            r = subprocess.run([bash, "tools/jvm-harness/run-avatar-face.sh"], cwd=ROOT, env=env,
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
            fails = [l[6:] for l in r.stdout.splitlines() if l.startswith("FAIL  ")]
            if r.returncode == 1 and fails:
                caught += 1
                print("CAUGHT  %-66s by: %s" % (name, fails[0][:90]))
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
