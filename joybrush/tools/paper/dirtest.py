import numpy as np
from PIL import Image
from scipy import ndimage as ndi
import preview
h = np.asarray(Image.open("out/pulp_handmade_h.png").convert("L"), float) / 255
h = h[:160, :160]
gx = ndi.sobel(h, 1) / 8; gy = ndi.sobel(h, 0) / 8
s = 1.0 / max(np.abs(gx).max(), np.abs(gy).max())
nx, ny = -gx * s, -gy * s            # slope facing direction, ~ -1..1
def cov(level, d, k, edge=0.08):
    face = nx * (-d[0]) + ny * (-d[1])          # >0: faces the oncoming brush
    he = h + k * face
    return np.clip((he - (1 - level)) / edge + 0.5, 0, 1)
paper = preview.lit(h, 2.5)
ink = np.array([0.12, 0.10, 0.09])
rows = []
for d, k in [((0, 0), 0.0), ((-1, 0), 0.35), ((1, 0), 0.35)]:
    c = cov(0.42, d, k)[..., None]
    rows.append(paper * (1 - c) + ink * c)
img = np.concatenate(rows, 1)
Image.fromarray((img * 255).astype(np.uint8)).resize((img.shape[1] * 3, img.shape[0] * 3), Image.NEAREST).save("out/dir_test.png")
