"""
Preview/export parity lint: a clip property that shapes the EXPORT must shape the PREVIEW.

WHY THIS EXISTS. SPEC_20260825_PREVIEW_MATCHES_EXPORT §3.3: "add a check that fails when a
clip property affects the export but not the preview." The concrete instance that cost the
session: crop was read by ExportManager and by NOTHING on the preview side -- three preview
implementations existed (GL chain, PlayerView transforms, nothing) and none of them cropped.
A compiler cannot catch that: every side compiles, the picture is just wrong.

The check is FILE-PROVABLE on purpose, like copy_lint.py / deadstate_lint.py / orphan_lint.py:
it walks source text, not semantics. It can be fooled, but it cannot be fooled silently --
and it carries its own NEGATIVE CONTROL (--negctl), because a probe that cannot fail proves
nothing (the repo's own standard, demonstrated across run_negctl_suite.py).

What is watched:
  - CLIP_PROPERTIES: model accessors that shape the rendered picture (crop, rotation, flip,
    speed, grade stack, opacity keyframes, blend mode, compositing/masks).
  - SHARED BUILDERS: Clip.effectiveCropRectNdc / Clip.effectiveCropFractions are the ONE
    per-clip crop decision; BOTH renderers must reference their builder. If either stops
    calling it, this lint fails even though the getters themselves may still appear somewhere
    -- a deleted call site is exactly the regression that started SPEC_20260825.

Coverage rule: a property counts as covered when ANY of these reads it --
  - a preview-side renderer file (compositor/FxLivePreviewController, FxPreviewTextureView,
    OverlayVideoPreviewView, overlay/TextOverlayLayer),
  - a SHARED MODEL BUILDER (model/Clip.java) that both renderers consume.
An entry in EXPECTED exempts a property whose preview gap is deliberate, with the reason.

Usage:
  python tools/jvm-harness/preview_parity_lint.py           # the real check
  python tools/jvm-harness/preview_parity_lint.py --negctl  # prove the check CAN fail
Exits 1 on failure.
"""
import io
import os
import re
import sys

SRC = os.path.join("app", "src", "main", "java")

PREVIEW_FILES = [
    "com/fadcam/ui/faditor/compositor/FxLivePreviewController.java",
    "com/fadcam/ui/faditor/compositor/FxPreviewTextureView.java",
    "com/fadcam/ui/faditor/compositor/OverlayVideoPreviewView.java",
    "com/fadcam/ui/faditor/compositor/MasterPlaybackEngine.java",
    "com/fadcam/ui/faditor/overlay/TextOverlayLayer.java",
]

SHARED_BUILDER_FILE = "com/fadcam/ui/faditor/model/Clip.java"
EXPORT_DIR = "com/fadcam/ui/faditor/export"

# Both renderers MUST route crop through the one shared builder. These strings are the
# call sites themselves -- deleting either one is the bug this lint exists to catch.
REQUIRED_CALLSITES = {
    "Clip.effectiveCropRectNdc": (
        "app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java",
        ["effectiveCropRectNdc"],
    ),
    "FxLivePreviewController -> Clip.effectiveCropFractions": (
        "app/src/main/java/com/fadcam/ui/faditor/compositor/FxLivePreviewController.java",
        ["effectiveCropFractions", "setClipCrop"],
    ),
}

CLIP_PROPERTIES = [
    "getCropPreset", "getCropLeft", "getCropTop", "getCropRight", "getCropBottom",
    "getRotationDegrees", "isFlipHorizontal", "isFlipVertical",
    "getSpeedMultiplier", "getEffectStack", "hasOpacityKeyframes",
    "getOverlayBlendMode", "getCompositing",
]

# TextOverlayItem routing — the GL path decision. 3A.1 showed the lint passed while
# hasExportMask was invisible on the preview side and below-blend plain images were
# stranded on Canvas; export had the predicates, preview had no reader.
TEXT_OVERLAY_ROUTING_PROPS = [
    "hasExportMask", "wantsGlExport", "wantsExportBlend", "hasExportFx", "hasExportKey",
]
TEXT_OVERLAY_EXPORT_DIR = "com/fadcam/ui/faditor/export"
TEXT_OVERLAY_PREVIEW_FILES = PREVIEW_FILES  # same preview files + model

REQUIRED_ROUTING_CALLSITES = {
    "TextOverlayItem.hasExportMask in wantsGlExport": (
        "app/src/main/java/com/fadcam/ui/faditor/model/TextOverlayItem.java",
        ["hasExportMask", "wantsGlExport"],
    ),
    "FxLivePreviewController promotes plain images below a blend": (
        "app/src/main/java/com/fadcam/ui/faditor/compositor/FxLivePreviewController.java",
        ["plainImagesBelowBlend", "belowBlendIds"],
    ),
}

# property -> why the preview legitimately does not render it. Name the row that will,
# or why it never will.
EXPECTED = {}

COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)


def norm(p):
    return p.replace("\\", "/")


def strip_comments(t):
    return COMMENT_RE.sub(" ", t)


def load(rel):
    p = os.path.join(SRC, rel)
    return strip_comments(io.open(p, encoding="utf-8", errors="replace").read())


def strip_crop_wiring(text):
    """Simulate the regression: remove every line through which the preview learns about
    crop (the controller's calls into the shared builder and into the renderer)."""
    kept = []
    for line in text.splitlines(True):
        if ("effectiveCropFractions" in line or "setClipCrop" in line
                or "suppressPreviewCrop" in line or "effectiveCropRectNdc" in line):
            continue
        kept.append(line)
    return "".join(kept)


def strip_routing_wiring_preview(text):
    kept = []
    for line in text.splitlines(True):
        if "plainImagesBelowBlend" in line or "belowBlendIds" in line:
            continue
        kept.append(line)
    return "".join(kept)


def strip_routing_wiring_model(text):
    kept = []
    for line in text.splitlines(True):
        if "hasExportMask" in line:
            continue
        kept.append(line)
    return "".join(kept)


def run_check(sources):
    export_clip_props = set()
    export_routing_props = set()
    export_dir_abs = os.path.join(SRC, EXPORT_DIR)
    for name in sorted(os.listdir(export_dir_abs)):
        if name.endswith(".java"):
            t = sources[norm(os.path.join(export_dir_abs, name))]
            for prop in CLIP_PROPERTIES:
                if re.search(r"\b" + prop + r"\b", t):
                    export_clip_props.add(prop)
            for prop in TEXT_OVERLAY_ROUTING_PROPS:
                if re.search(r"\b" + prop + r"\b", t):
                    export_routing_props.add(prop)

    preview_text = "".join(
        sources[norm(os.path.join(SRC, f))] for f in PREVIEW_FILES
        if norm(os.path.join(SRC, f)) in sources)
    builder_text = sources.get(norm(os.path.join(SRC, SHARED_BUILDER_FILE)), "")
    text_overlay_builder = sources.get(
        norm(os.path.join(SRC, "com/fadcam/ui/faditor/model/TextOverlayItem.java")), "")

    gaps = []
    checked = 0
    for prop in sorted(export_clip_props):
        if prop in EXPECTED:
            continue
        checked += 1
        covered = (re.search(r"\b" + prop + r"\b", preview_text) is not None
                   or re.search(r"\b" + prop + r"\b", builder_text) is not None
                   or re.search(r"\b" + prop + r"\b", text_overlay_builder) is not None)
        if not covered:
            gaps.append(prop)
    for prop in sorted(export_routing_props):
        if prop in EXPECTED:
            continue
        checked += 1
        # Routing to GL must be read by a preview renderer, not just the shared builder.
        covered = re.search(r"\b" + prop + r"\b", preview_text) is not None
        if not covered:
            gaps.append(prop + " (routing: preview must read it, not just the model)")

    wiring_failures = []
    for label, (rel, needles) in sorted(REQUIRED_CALLSITES.items()):
        t = sources.get(norm(rel))
        if t is None:
            wiring_failures.append("%s: file missing (%s)" % (label, rel))
            continue
        for needle in needles:
            if needle not in t:
                wiring_failures.append("%s: '%s' no longer called in %s"
                                       % (label, needle, os.path.basename(rel)))
    for label, (rel, needles) in sorted(REQUIRED_ROUTING_CALLSITES.items()):
        t = sources.get(norm(rel))
        if t is None:
            wiring_failures.append("%s: file missing (%s)" % (label, rel))
            continue
        for needle in needles:
            if needle not in t:
                wiring_failures.append("%s: '%s' no longer called in %s"
                                       % (label, needle, os.path.basename(rel)))

    return checked, gaps, wiring_failures


def main():
    negctl = "--negctl" in sys.argv

    sources = {}
    for root, _d, files in os.walk(SRC):
        for f in files:
            if f.endswith(".java"):
                p = norm(os.path.join(root, f))
                sources[p] = strip_comments(io.open(p, encoding="utf-8",
                                                    errors="replace").read())

    if negctl:
        # Two independent negative controls: crop wiring and routing wiring. Either must
        # trip for the probe to prove it can fail; we strip BOTH and require at least one.
        ctl_path = norm(os.path.join(SRC, PREVIEW_FILES[0]))
        model_path = norm(os.path.join(SRC, "com/fadcam/ui/faditor/model/TextOverlayItem.java"))
        sources[ctl_path] = strip_crop_wiring(sources[ctl_path])
        sources[ctl_path] = strip_routing_wiring_preview(sources[ctl_path])
        if model_path in sources:
            sources[model_path] = strip_routing_wiring_model(sources[model_path])
        checked, gaps, wiring = run_check(sources)
        failed = bool(gaps or wiring)
        print("NEGCTL: stripped crop+routing wiring; %d props checked, "
              "%d gap(s), %d wiring failure(s)" % (checked, len(gaps), len(wiring)))
        for g in gaps:
            print("  (expected gap) %s affects export, not preview" % g)
        for w in wiring:
            print("  (expected failure) %s" % w)
        if failed:
            print("PASS  negative control FAILED the check, so the check can fail")
            sys.exit(0)
        print("FAIL  negative control did NOT trip the check -- the probe proves nothing")
        sys.exit(1)

    checked, gaps, wiring = run_check(sources)
    exempt = sum(1 for p in CLIP_PROPERTIES if p in EXPECTED)
    print("checked %d export-rendered clip properties against the preview side, "
          "%d exempt" % (checked, exempt))

    problems = []
    for g in gaps:
        problems.append("FAIL  %s affects the export but NO preview-side file or shared "
                        "builder reads it" % g)
    problems.extend("FAIL  " + w for w in wiring)

    if problems:
        print()
        for p in problems:
            print(p)
        print()
        print("The preview must show what the export will produce. A property that reaches")
        print("the export but no preview renderer is the exact shape of the crop bug")
        print("(SPEC_20260825): legal Java, wrong picture. Wire the preview, or add the")
        print("property to EXPECTED with the reason it never renders there.")
        print("%d PARITY GAP(S)" % len(problems))
        sys.exit(1)

    print("PASS  every export-rendered clip property has preview coverage")
    print("ALL PARITY")


main()
