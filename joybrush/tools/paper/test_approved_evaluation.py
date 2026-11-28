"""Small synthetic recipe checks; no2048 bake, Gradle, phone or creative approval."""
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

import build_approved_evaluation as pipeline
import approved_phone_profile as profile


class ApprovedEvaluationTest(unittest.TestCase):
    def test_profile_preserves_unresolved_height_variance(self):
        y, x = np.mgrid[:64, :64]
        h = np.where((x + y) % 2, 254, 0).astype(np.uint8)
        rgba, scale = profile.surface_profile(h, 2, .1)
        self.assertEqual(scale, .2)
        self.assertTrue(np.all(rgba[..., 2] == 127))
        variance = rgba[..., 3] / 255 - (rgba[..., 2] / 255) ** 2
        self.assertGreater(float(variance.min()), .24)

    def test_profile_preserves_physical_slope_units_on_smooth_surface(self):
        y, x = np.mgrid[:64, :64]
        h = np.rint((.5 + .2 * np.sin((x + .5) * 2 * np.pi / 64)) * 255).astype(np.uint8)
        coarse, sr = profile.surface_profile(h, 2, .1)
        dx, _ = pipeline.pack.slopes(h.astype(float) / 255)
        # Decode production-packed R, then divide by the doubled physical pitch.
        decoded = (coarse[..., 0].astype(float) - 127) / 127 * sr / 2
        reference = profile.area(dx, 2)
        np.testing.assert_allclose(decoded, reference, atol=.0016)

    def texture(self):
        y, x = np.mgrid[:64, :64]
        return .5 + .15 * np.sin(x * 2 * np.pi / 8) + .1 * np.cos(y * 2 * np.pi / 16)

    def test_embedded_pigment_polarity_does_not_turn_dark_into_pits(self):
        y = self.texture()
        for kind in ('rice', 'chunk', 'chalk'):
            np.testing.assert_allclose(pipeline.infer_height(y, kind, .4),
                                       pipeline.infer_height(1 - y, kind, .4), atol=1e-10)

    def test_flat_material_is_finite_without_invented_relief(self):
        for kind in ('rice', 'chunk', 'chalk', 'weave', 'strip', 'mineral', 'smooth'):
            height = pipeline.infer_height(np.full((64, 64), .4), kind, .7)
            self.assertTrue(np.isfinite(height).all())
            np.testing.assert_allclose(height, .5, atol=1e-10)

    def test_flattened_and_full_crumples_keep_distinct_height_spans(self):
        y = self.texture()
        small = pipeline.infer_height(y, 'crease', .2)
        large = pipeline.infer_height(y, 'crease', .8)
        np.testing.assert_allclose(large - .5, 4 * (small - .5), atol=1e-10)

    def test_decoded_height_moment_and_sampling_metadata_agree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rgb = np.repeat((self.texture() * 255).astype(np.uint8)[..., None], 3, axis=2)
            Image.fromarray(rgb).save(root / 'tile.png')
            job = dict(id='linen-2', label='Fine linen2', description='white gesso primed',
                       directory=str(root), note='')
            out = root / 'runtime'
            out.mkdir()
            r = pipeline.bake(job, out, size=64)
            s, look = r['surface'], r['look']
            self.assertFalse(s['rotatable'])
            self.assertFalse(look['rotatable'])
            self.assertFalse(look['lightByDefault'])
            self.assertEqual(s['texelPx'], look['texelPx'])
            self.assertEqual(s['fluidTexelPx'] * s['fluidHexTexels'],
                             s['texelPx'] * s['hexTexels'])
            self.assertGreater(s['sizing'], .8)
            self.assertLess(s['absorbency'], .3)
            h = np.asarray(Image.open(out / s['file']))
            packed = np.asarray(Image.open(root / 'diagnostics' / ('packed_' + s['id'] + '.png')))
            np.testing.assert_array_equal(h, packed[..., 2])
            np.testing.assert_array_equal(packed[..., 3], np.rint(h.astype(float) ** 2 / 255).astype(np.uint8))
            decoded = np.asarray(Image.open(out / look['file'])).mean(axis=(0, 1))
            self.assertEqual(look['mean'], '#%02X%02X%02X' % tuple(np.rint(decoded).astype(int)))


if __name__ == '__main__':
    unittest.main()
