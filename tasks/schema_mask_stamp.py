#!/usr/bin/env python3
"""Offline drill: the multi-shape-mask schema-v13 stamp.

Reproduces the DATA LOSS FIRST, then proves the stamp prevents it — the discipline
`schema_layer_stamp.py` established, and the one SPEC_ADJUSTMENT_LAYERS_FX R2 asks for.

Mirrors, in Python, the two write conditions in `CompositingSpec.toJson`:
    "mode" written  <=> shape.mode == MODE_INTERSECT
    "slot" written  <=> shape.slot != its list index
and the stamp predicate `CompositingSpec.needsSchema13()` = (either of the above).

Run:  python tasks/schema_mask_stamp.py
"""

MODE_ADD, MODE_SUBTRACT, MODE_INTERSECT = 0, 1, 2

fails = []


def check(label, got, want):
    ok = got == want
    print(("  ok   " if ok else "  FAIL ") + label + (
        "" if ok else "\n         got  {!r}\n         want {!r}".format(got, want)))
    if not ok:
        fails.append(label)


def shape(cx=0.5, cy=0.5, w=0.3, h=0.5, corner=1.0, mode=MODE_ADD, slot=None):
    return {"cx": cx, "cy": cy, "w": w, "h": h, "corner": corner, "mode": mode, "slot": slot}


def to_json_masks(masks):
    """The serializer's shape. Key ORDER matters — byte-identity is the claim."""
    out = []
    for i, m in enumerate(masks):
        mj = {"cx": m["cx"], "cy": m["cy"], "w": m["w"], "h": m["h"], "corner": m["corner"]}
        if m["mode"] == MODE_SUBTRACT:
            mj["sub"] = True
        if m["mode"] == MODE_INTERSECT:
            mj["mode"] = MODE_INTERSECT
        slot = i if m["slot"] is None else m["slot"]
        if slot != i:
            mj["slot"] = slot
        out.append(mj)
    return out


def needs_v13(masks):
    for i, m in enumerate(masks):
        if m["mode"] == MODE_INTERSECT:
            return True
        if (i if m["slot"] is None else m["slot"]) != i:
            return True
    return False


def stamp(masks, base=8):
    return max(base, 13) if needs_v13(masks) else base


def v12_parser(masks_json):
    """An OLD build reading the file: it knows "sub", and nothing else."""
    out = []
    for mj in masks_json:
        out.append(shape(mj["cx"], mj["cy"], mj["w"], mj["h"], mj["corner"],
                         MODE_SUBTRACT if mj.get("sub") else MODE_ADD, slot=None))
    return out


print("=== 1. add/subtract-only stays BYTE-IDENTICAL to pre-change output ===")
legacy = [shape(mode=MODE_ADD), shape(cx=0.2, mode=MODE_SUBTRACT)]
# What the serializer wrote BEFORE modes/slots existed: cx,cy,w,h,corner (+ "sub").
pre_change = [
    {"cx": 0.5, "cy": 0.5, "w": 0.3, "h": 0.5, "corner": 1.0},
    {"cx": 0.2, "cy": 0.5, "w": 0.3, "h": 0.5, "corner": 1.0, "sub": True},
]
check("no 'mode'/'slot' key appears", to_json_masks(legacy), pre_change)
check("key order is unchanged too (byte-identity, not just equality)",
      [list(d.keys()) for d in to_json_masks(legacy)],
      [list(d.keys()) for d in pre_change])
check("stamp is untouched — an old build still opens it", stamp(legacy), 8)

print("\n=== 2. INTERSECT forces v13 ===")
inter = [shape(), shape(cx=0.7, mode=MODE_INTERSECT)]
check("'mode':2 is written", to_json_masks(inter)[1].get("mode"), MODE_INTERSECT)
check("stamp raises to 13", stamp(inter), 13)

print("\n=== 3. a diverged SLOT forces v13 (the subtle trigger) ===")
# Three shapes created (slots 0,1,2); shape 1 deleted. Slots never renumber, so the
# survivor at index 1 still carries slot 2 — and its keyframe tracks are named off it.
after_delete = [shape(slot=0), shape(cx=0.9, slot=2)]
check("'slot' is written once it diverges from the index",
      to_json_masks(after_delete)[1].get("slot"), 2)
check("stamp raises to 13 even with no intersect at all", stamp(after_delete), 13)
check("...and while slots still equal indices, nothing is written",
      "slot" in to_json_masks([shape(slot=0), shape(slot=1)])[1], False)

print("\n=== 4. THE DATA LOSS, reproduced (why the stamp must exist) ===")
on_disk = to_json_masks(inter)
reloaded = v12_parser(on_disk)
check("an old build reads the INTERSECT shape as plain ADDITIVE",
      reloaded[1]["mode"], MODE_ADD)
resaved = to_json_masks(reloaded)
check("and its autosave writes that coercion back — the intersect is GONE",
      "mode" in resaved[1], False)
check("the loss is permanent: re-reading never recovers it",
      v12_parser(resaved)[1]["mode"], MODE_ADD)

print("\n=== 5. the stamp is what stops it ===")
# A build refuses a file whose on-disk stamp exceeds the version it understands.
running_v12 = 12
check("a v12 build REFUSES the v13 file instead of silently degrading it",
      stamp(inter) > running_v12, True)
check("...and still opens the add/subtract-only file, losslessly",
      stamp(legacy) > running_v12, False)

print()
if fails:
    print("FAILED ({}):".format(len(fails)))
    for f in fails:
        print("  - " + f)
    raise SystemExit(1)
print("ALL GREEN — stamp fires on both triggers, and only on them.")
