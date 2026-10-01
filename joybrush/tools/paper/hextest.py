"""Repeat test: today's tiling vs hex-tiling (random offsets per hex, variance-preserving blend)."""
import numpy as np
from PIL import Image
from scipy import ndimage as ndi
import preview
def sample(tex, u, v):  # bilinear, wrap; u,v in texels
    n = tex.shape[0]
    return ndi.map_coordinates(tex, [v % n, u % n], order=1, mode="grid-wrap")
def hash2(i, j, k):
    x = np.sin(i * 127.1 + j * 311.7 + k * 74.7) * 43758.5453
    return x - np.floor(x)
def hextile(tex, X, Y, cell, gamma=3.0):
    # skewed triangle grid (as in Heitz-Neyret / Mikkelsen); cell = hex size in texels
    s = X / cell; t = Y / cell
    a = s - t / np.sqrt(3); b = t * 2 / np.sqrt(3)
    i0 = np.floor(a); j0 = np.floor(b); fa = a - i0; fb = b - j0
    up = (fa + fb) > 1
    verts = [(np.where(up, i0 + 1, i0), np.where(up, j0 + 1, j0)),
             (i0 + 1, j0), (i0, j0 + 1)]
    w = [np.where(up, fa + fb - 1, 1 - fa - fb), np.where(up, 1 - fb, fa), np.where(up, 1 - fa, fb)]
    mean = tex.mean(); acc = 0; wsum2 = 0; wsum = 0
    for (vi, vj), wi in zip(verts, w):
        wi = wi ** gamma
        ou = hash2(vi, vj, 1) * tex.shape[0]; ov = hash2(vi, vj, 2) * tex.shape[0]
        acc = acc + wi * (sample(tex, X + ou, Y + ov) - mean); wsum += wi; wsum2 += wi * wi
    return mean + acc / np.sqrt(wsum2)  # variance-preserving
W, H = 1600, 700
Y, X = np.mgrid[0:H, 0:W].astype(float)
old = np.asarray(Image.open("../../assets/grain/cloud_fine_256.png").convert("L"), float) / 255
oldv = sample(old, X * 256 / 42.7, Y * 256 / 42.7)  # today: 256 px picture every 42.7 doc px
new = np.asarray(Image.open("out/pulp_artisan_h.png").convert("L"), float) / 255
plain = sample(new, X * 2, Y * 2)             # a 1024 picture, plain repeat, shown at zoom 0.5
hexed = hextile(new, X * 2, Y * 2, 300)
def show(h):
    h = (h - h.min()) / (h.max() - h.min()); return preview.lit(h, 1.5)
rows = [show(oldv), show(plain), show(np.clip(hexed, 0, 1))]
img = np.concatenate([r[:230] for r in rows], 0)
Image.fromarray((img * 255).astype(np.uint8)).save("out/repeat_test.png")
