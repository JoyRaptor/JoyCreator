"""Compose the curated brush set's contact sheet from the PC lab's renders (brush lane, 2026-11-13).

The lab (joybrush/lab/media, the phone's own shaders in a browser) draws one cell a brush on the sheets `setdry`,
`setwet` and `setoil` (lab/media/js/tests.js): two cells across, 800 doc px wide, in the order given by ?tools=
(default: table order). This script crops each cell and writes the brush's name and one-line purpose under it.

    node joybrush/tools/media_render.js dry.png "test=setdry"
    node joybrush/tools/media_render.js oil.png "test=setoil"
    node joybrush/tools/media_render.js wet1.png "test=setwet&tools=All-round,Round,Wash,Dry brush,Water,Thirsty&dryS=15"
    node joybrush/tools/media_render.js wet2.png "test=setwet&tools=Glaze,Granulating wash,Bloom,Rigger,Mop,Edge softener&dryS=15"
    python tasks/joybrush/lead-scripts/brush_set_sheet.py out.png dry.png:400:dry oil.png:400:paste wet1.png:600:wet:0:6 wet2.png:600:wet:6:6

Each input is `file:cellHeight:medium[:firstIndex[:count]]`. PC lab renders only: the phone is the acceptance, not this sheet.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

# name, purpose; NEW marks the brushes added on 2026-11-13.
SET = {
    'dry': [
        ('Proto Pencil', 'The flagship from the owner\'s pencil tuner', False),
        ('Proto Pencil 2', 'Retuned against the Proko samples: whole lines, fine even tone', True),
        ('Proto Mix', 'The mean of the three Proto pencils', False),
        ('Proto', 'The first soft woodless stick', False),
        ('Proto 2', 'Finer point, one blended footprint', False),
        ('2H', 'Hard and pale: construction lines', False),
        ('HB', 'The everyday pencil', False),
        ('2B', 'Softer and darker: sketching', False),
        ('6B', 'Soft and black: shading', False),
        ('Woodless 8B', 'A fat soft stick: broad tone', False),
    ],
    'wet': [
        ('All-round', 'Hairline to full belly on pressure and tilt', False),
        ('Round', 'The standard pointed round', False),
        ('Wash', 'A flat wash brush', False),
        ('Dry brush', 'Broken, dragged colour', False),
        ('Water', 'Clear water: rewets and moves paint', False),
        ('Thirsty', 'A damp clear brush that lifts paint out', False),
        ('Glaze', 'Thin staining tint that stays put over dried work', True),
        ('Granulating wash', 'Pigment settles into the paper\'s dips', True),
        ('Bloom', 'Floods and leaves back-runs in a damp wash', True),
        ('Rigger', 'Long thin liner: branches, grass, signatures', True),
        ('Mop', 'The biggest, softest: tint a whole area', True),
        ('Edge softener', 'Small damp clear brush: feather or lose an edge', True),
    ],
    'paste': [
        ('All-round', 'Hairline to full belly', False),
        ('Oil flat', 'The standard flat', False),
        ('Oil round', 'The standard round', False),
        ('Fan blender', 'Feathers and blends', False),
        ('Palette knife 1', 'The trowel knife: thick texture', False),
        ('Palette knife 2', 'Blade held like the pen', False),
        ('Palette knife 3', 'Held like a real knife: lays, breaks, cuts', False),
        ('Scraper', 'An empty knife that takes paint off', False),
        ('Impasto flat', 'Heavily loaded: thick ridged paint that covers', True),
        ('Filbert', 'The rounded flat: petals, faces, soft edges', True),
        ('Oil liner', 'Long thin round: stems, whiskers, highlights', True),
    ],
}
TITLE = {'dry': 'Pencils', 'wet': 'Watercolour', 'paste': 'Oil'}


def font(size, bold=False):
    for name in (['arialbd.ttf', 'segoeuib.ttf'] if bold else ['arial.ttf', 'segoeui.ttf']):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def cells(spec):
    parts = spec.split(':')
    # A Windows path has a drive colon: rejoin it.
    if len(parts[0]) == 1:
        parts = [parts[0] + ':' + parts[1]] + parts[2:]
    path, ch, medium = parts[0], int(parts[1]), parts[2]
    first = int(parts[3]) if len(parts) > 3 else 0
    count = int(parts[4]) if len(parts) > 4 else len(SET[medium]) - first
    im = Image.open(path).convert('RGB')
    names = SET[medium][first:first + count]
    rows = (len(names) + 1) // 2
    # The sheet is drawn y up from the bottom: its cells fill the last rows * ch pixels of the picture.
    top = im.height - rows * ch
    out = []
    for i, entry in enumerate(names[:rows * 2]):
        x, y = (i % 2) * 800, top + (i // 2) * ch
        out.append((medium, entry, im.crop((x, y, x + 800, y + ch))))
    return out


def main():
    out, specs = sys.argv[1], sys.argv[2:]
    W, CW, CH, LAB = 1640, 400, 250, 46
    groups = {}
    for s in specs:
        for medium, entry, cell in cells(s):
            groups.setdefault(medium, []).append((entry, cell))
    rows = sum((len(v) + 3) // 4 for v in groups.values())
    sheet = Image.new('RGB', (W, 70 + len(groups) * 56 + rows * (CH + LAB + 14) + 40), (250, 249, 246))
    d = ImageDraw.Draw(sheet)
    d.text((20, 18), 'JoyBrush curated set: pencil, watercolour, oil (PC lab render, 2026-11-13)', font=font(28, True), fill=(20, 20, 24))
    y = 70
    for medium in ('dry', 'wet', 'paste'):
        if medium not in groups:
            continue
        d.text((20, y + 10), TITLE[medium], font=font(26, True), fill=(20, 20, 24))
        y += 56
        for i, ((name, why, new), cell) in enumerate(groups[medium]):
            x = 20 + (i % 4) * CW
            if i and i % 4 == 0:
                y += CH + LAB + 14
            thumb = cell.resize((CW - 10, int(cell.height * (CW - 10) / cell.width)))
            if thumb.height > CH:
                thumb = thumb.crop((0, 0, CW - 10, CH))
            sheet.paste(thumb, (x, y))
            d.rectangle((x, y, x + CW - 11, y + CH - 1), outline=(205, 203, 198))
            d.text((x + 2, y + CH + 3), name + ('  NEW' if new else ''), font=font(18, True), fill=(0, 120, 150) if new else (20, 20, 24))
            d.text((x + 2, y + CH + 25), why, font=font(13), fill=(90, 90, 96))
        y += CH + LAB + 14
    sheet.crop((0, 0, W, y + 10)).save(out)
    print(out, sheet.size)


if __name__ == '__main__':
    main()
