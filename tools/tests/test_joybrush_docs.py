"""Focused tests for the read-only live JoyBrush documentation link checker."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check_joybrush_docs.py"
LIVE_DOCS = (
    "tasks/joybrush/ROADMAP.md",
    "tasks/joybrush/AGENT_START_20261026.md",
    "tasks/STATE.md",
)


def make_repo(root: Path, roadmap: str, agent_start: str = "", state: str = "") -> None:
    for relative, content in zip(LIVE_DOCS, (roadmap, agent_start, state)):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


class CheckJoyBrushDocsTests(unittest.TestCase):
    def run_checker(self, root: Path, cwd: Path | None = None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(root)],
            cwd=cwd,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_relative_directory_and_percent_encoded_space_targets_exist(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            (root / "tasks/joybrush/reviews").mkdir(parents=True)
            (root / "tasks/joybrush/review notes.md").write_text("ok", encoding="utf-8")
            make_repo(
                root,
                "[relative](reviews/note.md) [directory](reviews/) "
                "[encoded](review%20notes.md)\n",
            )
            (root / "tasks/joybrush/reviews/note.md").write_text("ok", encoding="utf-8")
            result = self.run_checker(root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("3 local links", result.stdout)

    def test_missing_target_reports_document_line_and_target_and_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_repo(root, "heading\n\n[missing](reviews/not-here.md)\n")
            result = self.run_checker(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("tasks/joybrush/ROADMAP.md:3", result.stderr)
            self.assertIn("reviews/not-here.md", result.stderr)

    def test_external_and_anchor_only_links_are_ignored(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_repo(
                root,
                "[fragment](#section) [web](https://example.invalid/missing) "
                "[mail](mailto:help@example.invalid)\n",
            )
            result = self.run_checker(root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("0 local links", result.stdout)

    def test_fenced_historical_archive_examples_are_not_scanned(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            make_repo(root, "```md\n[historical](deleted-old-plan.md)\n```\n")
            # The archived board is deliberately outside the checker input set.
            (root / "tasks/joybrush/ROADMAP_ARCHIVE_20261026.md").write_text(
                "[old](also-missing.md)\n", encoding="utf-8"
            )
            result = self.run_checker(root)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_explicit_root_works_from_an_alternate_working_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "repo with spaces"
            other = base / "elsewhere"
            other.mkdir()
            make_repo(root, "[home](../../README.md)\n")
            (root / "README.md").write_text("repo root", encoding="utf-8")
            result = self.run_checker(root, cwd=other)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_default_root_is_script_relative_when_run_elsewhere(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, str(SCRIPT)],
                cwd=temporary,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
