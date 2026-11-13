"""Build textlab-icons-v2.json from v1: approved entries copied byte-identical, owner redraws replaced.
python -I gen_textlab_icons_v2.py <v1.json> <v2.json>"""
import json
import sys

HEAD = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">')
FILL = ' fill="currentColor" stroke="none"'


def svg(*parts):
    return HEAD + ''.join(parts) + '</svg>'


def P(d, extra=''):
    return '<path d="%s"%s/>' % (d, extra)


def f(x):
    return ('%.2f' % x).rstrip('0').rstrip('.')


def C(cx, cy, r, extra=''):
    return '<circle cx="%s" cy="%s" r="%s"%s/>' % (f(cx), f(cy), f(r), extra)


def R(x, y, w, h, rx, extra=''):
    return '<rect x="%s" y="%s" width="%s" height="%s" rx="%s"%s/>' % (f(x), f(y), f(w), f(h), f(rx), extra)


def circ(x, y, r):
    return 'M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 -%s 0' % (f(x - r), f(y), f(r), f(r), f(2 * r), f(r), f(r), f(2 * r))


def dots(pts):
    return P(''.join(circ(x, y, r) for x, y, r in pts), FILL)


def bulb(x, y, r, extra=''):
    """Filled bulb: a round glass with its little screw-base box just under it (one shape)."""
    bw, bh, gap = r * 1.3, r * 0.75, 0.4
    box = 'M%s %sV%sH%sV%sZ' % (f(x - bw / 2), f(y + r + gap), f(y + r + gap + bh), f(x + bw / 2), f(y + r + gap))  # same winding as the circle
    return P(circ(x, y, r) + box, FILL + extra)


def spark(cx, cy, r):
    k = r * 0.24
    pts = [(cx, cy - r), (cx + k, cy - k), (cx + r, cy), (cx + k, cy + k), (cx, cy + r),
           (cx - k, cy + k), (cx - r, cy), (cx - k, cy - k), (cx, cy - r)]
    d = 'M%s %s' % (f(pts[0][0]), f(pts[0][1]))
    for i in range(1, 9, 2):
        d += 'Q%s %s %s %s' % (f(pts[i][0]), f(pts[i][1]), f(pts[i + 1][0]), f(pts[i + 1][1]))
    return P(d + 'Z', FILL)


def drop(x, top, r):
    cy = top + r * 2.2
    return P('M%s %sQ%s %s %s %sA%s %s 0 0 1 %s %sQ%s %s %s %sZ' % (
        f(x), f(top), f(x + r), f(top + r * 1.2), f(x + r), f(cy), f(r), f(r), f(x - r), f(cy),
        f(x - r), f(top + r * 1.2), f(x), f(top)), FILL)


def poly(pts):
    return P('M' + 'L'.join('%s %s' % (f(x), f(y)) for x, y in pts) + 'Z')


# Sun for the look-row Light: filled core, seven rays (none on the arrow's side).
SUN_RAYS = ('M10.5 7H11.7M7 10.5V11.7M4.53 9.47L3.68 10.32M3.5 7H2.3'
            'M4.53 4.53L3.68 3.68M7 3.5V2.3M9.47 4.53L10.32 3.68')

REDRAW = {
    ('Look row', 'Light'): (
        svg(C(7, 7, 2.2, FILL), P(SUN_RAYS), P('M10.8 10.8L17.5 17.5M17.5 13.3V17.5H13.3'), P('M3.5 20.5H20.5')),
        'A sun with its light arrowing down to the ground: where the light comes from.'),
    ('Look row', 'Still'): (
        svg(P('M9.2 15.5V14.6C9.2 12.8 6 11.6 6 8.5A6 6 0 0 1 18 8.5C18 11.6 14.8 12.8 14.8 14.6V15.5Z'),
            P('M9.5 18H14.5M10.5 20.5H13.5')),
        'A plain lightbulb: the light simply stays where it is put.'),
    ('Look row', 'Sway'): (
        svg(P('M4.5 15Q6.5 12.33 8.5 11.09M15.5 11.09Q17.5 12.33 19.5 15'),
            P('M7.4 14.3L4.5 15L4.3 12M16.6 14.3L19.5 15L19.7 12'), bulb(12, 8.3, 2.2)),
        'A small bulb at the top of a swing, the arc breaking around it, arrows both ways.'),
    ('Look row', 'Record tilt'): (
        svg(R(8.25, 5, 7.5, 14, 2, ' transform="rotate(-15 12 12)"'), P('M15.25 3.07A9.5 9.5 0 0 1 20.61 7.98'),
            P('M8.75 20.93A9.5 9.5 0 0 1 3.39 16.01'), bulb(12, 11.2, 1.6, ' transform="rotate(-15 12 12)"')),
        'A tipped phone between two swing arcs, a little bulb on its screen.'),
    ('Properties', 'Sides'): (
        svg(C(9.5, 9.5, 5.5), C(9.5, 9.5, 2.1), P('M5.61 13.39L10.61 18.39M13.39 5.61L18.39 10.61'),
            P('M18.39 10.61A5.5 5.5 0 0 1 10.61 18.39')),
        'An O pushed back into depth: its face in front, the curved side wall running back behind it.'),
    ('Properties', 'Burn'): (
        svg(P('M5 20V6Q5 4 7 4H10.5Q11.5 7 13.5 6.8Q14 9.5 16 9.8Q16.5 12 19 13V18Q19 20 17 20Z'),
            P('M12.74 6.75C12.3 5.2 13.6 4.2 14.6 3.2C14.9 4.4 15.7 5 16.4 5.2C16.4 4 17.3 3.1 18.2 2.6'
              'C18.6 4.6 20.8 7 20 9.6C19.6 10.7 18.5 11.3 17 11.7')),
        'A page burned to a ragged corner, a flame outline licking up off the edge.'),
    ('Properties', 'Front'): (
        svg(P('M3.5 12H8.3'), P('M12 3.5Q9 12 12 20.5'), dots([(13.9, 12, 0.95), (16.6, 12, 0.95), (19.3, 12, 0.95)])),
        'Solid line still standing, the sweep, then the same line as dots: carried away.'),
    ('Properties', 'Shatter'): (
        svg(poly([(4, 4.5), (11, 4.5), (9.5, 11), (4, 13)]),
            poly([(14, 4.3), (20, 4.3), (20, 14.8), (12.5, 10.8)]),
            poly([(5.5, 15.5), (11, 13.5), (18.5, 17.5), (18.5, 20), (5.5, 20)])),
        'A tile cracked into three pieces that still fit, drifting a little apart along the cracks.'),
    ('Properties', 'Fog'): (
        svg(R(4, 4, 16, 16, 3), P('M6.5 11Q8.25 8 10 11T13.5 11T17 11V14'), drop(17, 14.3, 1.3)),
        'A finger-drawn squiggle on a steamed pane, a drip running off its end.'),
    ('Properties', 'Finish'): (
        svg(R(3, 4, 18, 16, 0), P('M7 4V20M17 4V20'),
            P('M5 7.2V8.8M5 11.2V12.8M5 15.2V16.8M19 7.2V8.8M19 11.2V12.8M19 15.2V16.8', ' stroke-linecap="butt"'),
            spark(12, 12, 3.4)),
        'A square film frame with square sprocket holes, glowing at its heart: the last polish of the picture.'),
}

v1 = json.load(open(sys.argv[1], encoding='utf-8'))
by_word = {(e['group'], e['word']): e for e in v1}
out = []
for e in v1:
    key = (e['group'], e['word'])
    if key in REDRAW:
        s, note = REDRAW[key]
        out.append({'group': e['group'], 'word': e['word'], 'meaning': e['meaning'], 'svg': s, 'note': note})
    else:
        out.append(dict(e))
    if key == ('Properties', 'Depth'):
        sides = by_word[('Properties', 'Sides')]
        out.append({'group': 'Properties', 'word': 'Depth (swap)', 'meaning': e['meaning'], 'svg': sides['svg'],
                    'note': "Sides' v1 drawing (the extruded block) offered for Depth."})
    if key == ('Properties', 'Sides'):
        depth = by_word[('Properties', 'Depth')]
        out.append({'group': 'Properties', 'word': 'Sides (swap)', 'meaning': e['meaning'], 'svg': depth['svg'],
                    'note': "Depth's drawing (the bevelled tile) offered for Sides."})
out.append({'group': 'TextLab screen', 'word': 'Export', 'meaning': 'quick export / share out',
            'svg': svg(P('M4 13.5V18Q4 20 6 20H18Q20 20 20 18V13.5'), P('M12 15.5V4'), P('M7.5 8.5L12 4L16.5 8.5')),
            'note': 'An open tray with an arrow leaving it: send the finished title out.'})
with open(sys.argv[2], 'w', encoding='utf-8') as fh:
    json.dump(out, fh, ensure_ascii=False, indent=1)
print(len(out))
