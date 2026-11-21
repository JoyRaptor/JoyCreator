"""Check the export routing contract. The GPU parity fixture exercises the pixels separately."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
export = root / "app/src/main/java/com/fadcam/ui/faditor/export"
effect = (export / "TextFxGlEffect.java").read_text(encoding="utf-8")
draw = effect.split("public void drawFrame(int inputTexId", 1)[1].split("private GlProgram passProgram", 1)[0]
# The bitmap overlay uploads the result of renderForExport/readBitmap. It must be
# reachable for compatibility, with a separate sampling path for a GL export.
assert "frame.getTextureId(presentationTimeUs)" in draw, "lost Canvas compatibility path"
assert "gpuTitle.draw(" in draw, "every animated title frame still requires a bitmap readback and upload"
assert "gpuTitle.sampled()" in draw, "no consumer-to-producer ownership handoff"
assert draw.index("gpuTitle.sampled()") > draw.index("GLES20.glDrawArrays"), "released before the draw"
gpu = (root / "app/src/main/java/cc/joycreator/textlab/ExportTitleTexture.java").read_text(encoding="utf-8")
body = gpu.split("public int draw(", 1)[1].split("public int width()", 1)[0]
events = ["makeProducerCurrent();", "waitFor(consumed);", "StudioTextLab.drawMoment(", "clearTexture();",
          "produced = signal();", "restore(draw, read);", "waitFor(produced);", "return texture;"]
assert [body.index(e) for e in events] == sorted(body.index(e) for e in events), "shared texture reused out of order"
assert "readBitmap(" not in body and "glReadPixels(" not in body
print("GL export routes a title texture directly and retains the bitmap fallback")
