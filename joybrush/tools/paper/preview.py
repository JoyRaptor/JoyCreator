"""Preview: lit relief of a height map. Writes <name>_lit.png (a 512 crop at 1:1) for eyeballing."""
import numpy as np, sys, os
from PIL import Image
from scipy import ndimage as ndi
OUT = os.path.join(os.path.dirname(__file__), "out")
def lit(h, strength=2.5, base=(0.95, 0.94, 0.90), light=(-0.6, -0.6, 0.55)):
    gx = ndi.sobel(h, 1, mode="wrap") / 8; gy = ndi.sobel(h, 0, mode="wrap") / 8
    n = np.dstack([-gx * strength * 255 / 40, -gy * strength * 255 / 40, np.ones_like(h)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    L = np.array(light); L = L / np.linalg.norm(L)
    d = np.clip((n * L).sum(2), 0, 1) / L[2]
    shade = 0.75 + 0.25 * d
    return np.clip(np.array(base)[None, None] * shade[..., None], 0, 1)
for f in sys.argv[1:]:
    h = np.asarray(Image.open(os.path.join(OUT, f)).convert("L"), float) / 255
    img = lit(h)
    crop = img[:512, :512]
    Image.fromarray((crop * 255).astype(np.uint8)).save(os.path.join(OUT, f.replace("_h.png", "_lit.png")))
