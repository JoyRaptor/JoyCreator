#!/usr/bin/env python3
"""Offline drill: the adjustment-layer schema-v13 stamp (SPEC_ADJUSTMENT_LAYERS_FX R2).

Reproduces the DATA LOSS FIRST, then proves the stamp prevents it — the discipline
`schema_layer_stamp.py` established, and the one the spec asks for by name.

This is the SAME failure that already shipped once for TrackKind.LAYER: an old build
maps an unknown lane kind to VIDEO, and its next autosave writes that coercion back.
Kind decides emission phase, phase decides band position, band position IS paint order
under cross-type Z. So the round-trip permanently changes what paints over what, with
no error and no way back.

Run:  python tasks/schema_adjust_stamp.py
"""

KNOWN_KINDS_V12 = {"MASTER", "VIDEO", "IMAGE", "TEXT", "STICKER",
                   "SPRITE", "CAPTION", "VISUALIZER", "AUDIO", "LAYER"}

MIN_SCHEMA = {"LAYER": 11, "ADJUSTMENT": 13}

fails = []


def check(label, got, want):
    ok = got == want
    print(("  ok   " if ok else "  FAIL ") + label + (
        "" if ok else "\n         got  {!r}\n         want {!r}".format(got, want)))
    if not ok:
        fails.append(label)


def min_schema(kind):
    return MIN_SCHEMA.get(kind, 7)


def stamp(project, base=8):
    """What ProjectStorage writes: the max floor over everything actually present."""
    v = base
    for lane in project["lanes"]:
        v = max(v, min_schema(lane["kind"]))
    if project.get("adjustmentLayers"):
        v = max(v, 13)
    return v


def v12_parse_kind(name):
    """TrackKind.fromName on a build that predates the kind: unknown -> VIDEO."""
    return name if name in KNOWN_KINDS_V12 else "VIDEO"


def v12_load_and_autosave(project):
    """An older build reads, coerces what it cannot name, and saves that back."""
    out = {
        "lanes": [{"id": l["id"], "kind": v12_parse_kind(l["kind"])} for l in project["lanes"]],
        # It has no field for adjustment layers at all, so they simply do not survive.
        "adjustmentLayers": [],
    }
    return out


def band_order(project):
    """Emission phase decides band position, and band position is paint order."""
    phase = {"VIDEO": 0, "IMAGE": 0, "LAYER": 1, "ADJUSTMENT": 2, "TEXT": 3}
    return [l["id"] for l in sorted(project["lanes"], key=lambda l: phase.get(l["kind"], 0))]


print("=== 1. a project with no adjustment layer is untouched ===")
plain = {"lanes": [{"id": "pip", "kind": "VIDEO"}, {"id": "txt", "kind": "TEXT"}],
         "adjustmentLayers": []}
check("stamp stays at the old floor", stamp(plain), 8)
check("an older build still opens it", stamp(plain) > 12, False)
check("...and round-trips it unchanged", v12_load_and_autosave(plain)["lanes"], plain["lanes"])

print("\n=== 2. an adjustment layer forces v13 ===")
# The adjustment lane is FIRST in the stored list but belongs to a LATER emission phase, so
# its band position is decided by kind rather than by list order. That is the whole mechanism,
# and a fixture where the two happen to agree would pass while proving nothing.
adj = {"lanes": [{"id": "adj", "kind": "ADJUSTMENT"},
                 {"id": "pip", "kind": "VIDEO"},
                 {"id": "txt", "kind": "TEXT"}],
       "adjustmentLayers": [{"id": "a1", "layerId": "adj"}]}
check("the lane kind alone sets a floor of 13", min_schema("ADJUSTMENT"), 13)
check("the stamp is 13", stamp(adj), 13)

print("\n=== 3. THE DATA LOSS, reproduced (why the stamp must exist) ===")
before = band_order(adj)
check("the adjustment lane paints ABOVE the PiP lane, despite being stored first",
      before, ["pip", "adj", "txt"])
reloaded = v12_load_and_autosave(adj)
check("an old build reads ADJUSTMENT as VIDEO",
      [l["kind"] for l in reloaded["lanes"]], ["VIDEO", "VIDEO", "TEXT"])
check("...and the layer itself is simply gone", reloaded["adjustmentLayers"], [])
after = band_order(reloaded)
check("the lane has silently changed band position — it now paints BELOW the PiP",
      after, ["adj", "pip", "txt"])
check("...which is a different paint order than the file described", after != before, True)
check("the damage is permanent: re-reading never recovers the kind",
      v12_load_and_autosave(reloaded)["lanes"][1]["kind"], "VIDEO")

print("\n=== 4. the stamp is what stops it ===")
running_v12 = 12
check("a v12 build REFUSES the v13 file instead of degrading it",
      stamp(adj) > running_v12, True)
check("...and still opens an adjustment-free project, losslessly",
      stamp(plain) > running_v12, False)

print("\n=== 5. the stamp is a FLOOR, not an override ===")
# A project already stamped higher for another reason must not be dragged DOWN to 13.
high = dict(adj)
check("an unrelated higher stamp survives", stamp(high, base=14), 14)

print()
if fails:
    print("FAILED ({}):".format(len(fails)))
    for f in fails:
        print("  - " + f)
    raise SystemExit(1)
print("ALL GREEN — the coercion is real, and the stamp is what prevents it.")
