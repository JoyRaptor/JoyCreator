"""Contact sheet of the brush set from Note 9 screenshots (brush lane, 2026-11-13).

Each brush is drawn alone on a fresh bench canvas (cases `slowC,blue3`: a slow C and a line across it) and the
screen is captured at the hold. This crops the canvas out of each capture and labels it.

    python tasks/joybrush/lead-scripts/brush_set_phone_sheet.py <folder of each_<tag>.png> out.png

A capture that shows the home screen (the hold was missed) is drawn as an empty cell marked "not captured".
"""
import os, sys
from PIL import Image, ImageDraw, ImageFont

GROUPS = [
    ('Pencils', 'd', ['Proto Pencil', 'Proto Pencil 2*', 'Proto Mix', 'Proto', 'Proto 2', '2H', 'HB', '2B', '6B', 'Woodless 8B']),
    ('Watercolour', 'w', ['All-round', 'Round', 'Wash', 'Dry brush', 'Water', 'Thirsty', 'Glaze*', 'Granulating wash*', 'Bloom*',
                          'Rigger*', 'Mop*', 'Edge softener*']),
    ('Oil', 'o', ['All-round', 'Oil flat', 'Oil round', 'Fan blender', 'Palette knife 1', 'Palette knife 2', 'Palette knife 3',
                  'Scraper', 'Impasto flat*', 'Filbert*', 'Oil liner*']),
    ('Unibrush, new modes', 'u', ['Chalk*', 'Charcoal*', 'Technical*', 'Airbrush*', 'Pastel*', 'Ribbon*', 'Rainbow*', 'Foliage*',
                                  'Stipple*', 'Dashes*', 'Hatch*']),
]


def font(n, bold=False):
    try:
        return ImageFont.truetype('arialbd.ttf' if bold else 'arial.ttf', n)
    except OSError:
        return ImageFont.load_default()


def canvas(path):
    """The drawing area of a capture, or None when the capture is not the canvas (paper tan at the bottom centre)."""
    if not os.path.exists(path):
        return None
    im = Image.open(path).convert('RGB')
    r, g, b = im.getpixel((im.width // 2, im.height - 220))
    if not (r > 150 and g > 120 and b < 160 and r > b + 30):
        return None
    return im.crop((90, 330, im.width - 20, 1560))


def main():
    folder, out = sys.argv[1], sys.argv[2]
    cols, cw, ch, lab = 6, 300, 380, 30
    rows = sum((len(n) + cols - 1) // cols for _, _, n in GROUPS)
    sheet = Image.new('RGB', (20 + cols * (cw + 10), 70 + len(GROUPS) * 50 + rows * (ch + lab + 12) + 20), (250, 249, 246))
    d = ImageDraw.Draw(sheet)
    d.text((16, 16), 'JoyBrush brush set drawn by the real engine on the Note 9 (robot pen strokes, 2026-11-13). * = new', font=font(24, True), fill=(20, 20, 24))
    y = 66
    missing = []
    for title, key, names in GROUPS:
        d.text((16, y + 8), title, font=font(22, True), fill=(20, 20, 24))
        y += 50
        for i, name in enumerate(names):
            if i and i % cols == 0:
                y += ch + lab + 12
            x = 16 + (i % cols) * (cw + 10)
            cell = canvas(os.path.join(folder, 'each_%s%d.png' % (key, i)))
            if cell is None:
                missing.append(name)
                d.rectangle((x, y, x + cw - 1, y + ch - 1), fill=(236, 234, 230))
                d.text((x + 70, y + ch // 2 - 8), 'not captured', font=font(18), fill=(120, 120, 126))
            else:
                sheet.paste(cell.resize((cw, ch)), (x, y))
            new = name.endswith('*')
            d.text((x + 2, y + ch + 4), name.rstrip('*'), font=font(17, True), fill=(0, 120, 150) if new else (20, 20, 24))
        y += ch + lab + 12
    sheet.crop((0, 0, sheet.width, y + 8)).save(out)
    print(out, 'missing:', missing)


if __name__ == '__main__':
    main()
