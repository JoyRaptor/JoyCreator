"""Board import ownership lint (second Codex review 2026-11-01, S1).

Joy Brush "Send to Studio" copies the board picture into the project's assets, saves the
project, then reloads it as a check. Once the save has succeeded the saved project names the
picture, so the picture must stop being "ours to delete" BEFORE the reload: a failed check that
deleted it left the saved project pointing at a missing sheet.

The receive() method is Activity code (no JVM harness reaches it), so this checks the order in
the source: in receive(), `unsavedImage=null` must come after the successful `storage.save(project)`
and before the `storage.load(project.getId())` check.

Usage:  python tools/jvm-harness/board_import_lint.py [path-to-JoyBrushBoardImportActivity.java]
Positive control: run it on the pre-fix revision, it must FAIL:
  git show a8071d3e:app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java > /tmp/old.java
  python tools/jvm-harness/board_import_lint.py /tmp/old.java
"""
import re
import sys

DEFAULT = "app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java"


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    src = open(path, encoding="utf-8").read()
    start = src.find("private void receive(")
    if start < 0:
        print("FAIL receive() not found in " + path)
        return 1
    body = src[start:]
    save = body.find("storage.save(project)")
    reload = body.find("storage.load(project.getId())")
    if save < 0 or reload < 0 or reload < save:
        print("FAIL the save-then-reload shape changed; re-read S1 before updating this lint")
        return 1
    between = body[save:reload]
    if not re.search(r"\bunsavedImage\s*=\s*null\s*;", between):
        print("FAIL the copied board picture is still deletable after the project was saved "
              "(unsavedImage is not cleared between storage.save and the reload check)")
        return 1
    print("PASS the board picture belongs to the project as soon as the save succeeds")
    return 0


if __name__ == "__main__":
    sys.exit(main())
