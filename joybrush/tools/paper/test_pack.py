"""Numerical surface/CLI regressions; synthetic fixtures stay inside this worktree's ignored out/."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import numpy as np
from PIL import Image
from pack import height_bytes, pack

ROOT = Path(__file__).resolve().parent

class PackTest(unittest.TestCase):
    def test_sixteen_bit_heights_scale_instead_of_clipping(self):
        source = np.tile(np.array([0, 257, 32768, 65535], dtype=np.uint16), (4, 1))
        ROOT.joinpath('out').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'out') as folder:
            path = Path(folder) / 'height16.png'
            Image.fromarray(source).save(path)
            with Image.open(path) as image:
                actual = height_bytes(image)
        np.testing.assert_array_equal(actual, np.tile([0, 1, 128, 255], (4, 1)))

    def test_eight_bit_authored_heights_round_trip_unchanged(self):
        source = np.arange(256, dtype=np.uint8).reshape(16, 16)
        np.testing.assert_array_equal(height_bytes(Image.fromarray(source)), source)

    def test_flat_surface_has_valid_range_and_exact_zero_slopes(self):
        with np.errstate(all='raise'):
            rgba, slope_range = pack(np.full((8, 8), 128, dtype=np.uint8))
        self.assertEqual(0.001, slope_range)
        np.testing.assert_array_equal(rgba, np.tile([127, 127, 128, 64], (8, 8, 1)))

    def test_flattened_heights_and_decoded_slopes_keep_smaller_amplitude(self):
        y, x = np.mgrid[:64, :64]
        h = 0.5 + 0.35 * np.sin(2 * np.pi * x / 64) + 0.1 * np.cos(4 * np.pi * y / 64)
        image = Image.fromarray(np.rint(h * 65535).astype(np.uint16))
        full, _ = pack(height_bytes(image), 0.1)
        flattened, _ = pack(height_bytes(image, 0.2), 0.1)
        self.assertLess(flattened[:, :, 2].std(), full[:, :, 2].std() * 0.25)
        full_slope = (full[:, :, :2].astype(float) - 127) / 127 * 0.1
        flat_slope = (flattened[:, :, :2].astype(float) - 127) / 127 * 0.1
        self.assertLess(np.abs(flat_slope).mean(), np.abs(full_slope).mean() * 0.3)
        self.assertGreater(np.abs(flat_slope).max(), 0) # Subtle still has physical relief.
        self.assertLess(flattened[:, :, 2].max() - flattened[:, :, 2].min(), 52)

    def test_cli_preserves_sixteen_bit_variation_and_flattening(self):
        ROOT.joinpath('out').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'out') as folder:
            source, output = Path(folder) / 'height.png', Path(folder) / 'surface.png'
            height = np.tile(np.linspace(0, 65535, 16).astype(np.uint16), (16, 1))
            Image.fromarray(height).save(source)
            completed = subprocess.run([sys.executable, str(ROOT / 'pack.py'), str(source), str(output),
                '0.1', '--height-span', '0.2'], capture_output=True, text=True, check=True)
            self.assertEqual('0.1', completed.stdout.strip())
            with Image.open(output) as image:
                rgba = np.asarray(image)
                self.assertEqual('RGBA', image.mode)
            self.assertEqual((16, 16, 4), rgba.shape)
            self.assertGreater(rgba[:, :, 2].std(), 10)
            self.assertLessEqual(int(rgba[:, :, 2].max()) - int(rgba[:, :, 2].min()), 52)
            self.assertTrue(np.any(rgba[:, :, 0] != 127))

    def test_invalid_physical_ranges_are_refused(self):
        source = np.full((4, 4), 128, dtype=np.uint8)
        for value in (0, -1, float('nan'), float('inf')):
            with self.assertRaises(ValueError): pack(source, value)
        for value in (-0.1, 1.1, float('nan')):
            with self.assertRaises(ValueError): height_bytes(Image.fromarray(source), value)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--xml', type=Path, required=True)
    parser.add_argument('--test')
    args = parser.parse_args()
    suite = (unittest.TestSuite([PackTest(args.test)]) if args.test else
        unittest.defaultTestLoader.loadTestsFromTestCase(PackTest))
    cases = list(suite)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    root = ET.Element('testsuite', name='PaperPackTest', tests=str(result.testsRun),
        failures=str(len(result.failures)), errors=str(len(result.errors)), skipped=str(len(result.skipped)),
        timestamp=datetime.now(timezone.utc).isoformat())
    for case in cases:
        node = ET.SubElement(root, 'testcase', name=case._testMethodName, classname='PaperPackTest')
        for tag, entries in [('failure', result.failures), ('error', result.errors), ('skipped', result.skipped)]:
            for tested, detail in entries:
                if tested.id() == case.id(): ET.SubElement(node, tag).text = detail
    args.xml.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(args.xml, encoding='utf-8', xml_declaration=True)
    sys.exit(0 if result.wasSuccessful() else 1)
