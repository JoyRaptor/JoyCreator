"""Generate textlab-icons.json (46 TextLab icons).  python -I gen_textlab_icons.py <out.json>"""
import json
import sys

HEAD = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">')
FILL = ' fill="currentColor" stroke="none"'   # crisp accent, sized as drawn
FILLS = ' fill="currentColor"'                # accent that keeps the round stroke edge


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


def dot(x, y, r):
    return C(x, y, r, FILL)


def dots(pts):
    d = ''.join('M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 -%s 0' % (f(x - r), f(y), f(r), f(r), f(2 * r), f(r), f(r), f(2 * r))
                for x, y, r in pts)
    return P(d, FILL)


def spark(cx, cy, r):
    k = r * 0.24
    pts = [(cx, cy - r), (cx + k, cy - k), (cx + r, cy), (cx + k, cy + k), (cx, cy + r),
           (cx - k, cy + k), (cx - r, cy), (cx - k, cy - k), (cx, cy - r)]
    d = 'M%s %s' % (f(pts[0][0]), f(pts[0][1]))
    for i in range(1, 9, 2):
        d += 'Q%s %s %s %s' % (f(pts[i][0]), f(pts[i][1]), f(pts[i + 1][0]), f(pts[i + 1][1]))
    return P(d + 'Z', FILL)


def drop(x, top, r):
    """Teardrop, point up at (x, top), round bottom of radius r."""
    cy = top + r * 2.2
    return P('M%s %sQ%s %s %s %sA%s %s 0 0 1 %s %sQ%s %s %s %sZ' % (
        f(x), f(top), f(x + r), f(top + r * 1.2), f(x + r), f(cy), f(r), f(r), f(x - r), f(cy),
        f(x - r), f(top + r * 1.2), f(x), f(top)), FILL)


L = 'Look row'
PR = 'Properties'
ICONS = [
    (L, 'Look', 'pick a finished title look (film-poster lettering)',
     svg(P('M4.5 20L10.5 5L16.5 20M6.9 15H14.1'), spark(18.5, 5.5, 3.2)),
     'A bold poster capital with a glint: lettering that has been given a finished look.'),
    (L, 'Plain', 'no look, ordinary text',
     svg(P('M5.5 7V5H18.5V7M12 5V19.5M9.5 19.5H14.5')),
     'A bare type T with its small serifs: ordinary letters, nothing added.'),
    (L, 'Light', 'where the light comes from on the letters',
     svg(P('M3 18H21'), P('M4 18A8 8 0 0 1 20 18'), P('M8.4 14.4L11.2 17.2'), dot(6.35, 12.35, 2.1)),
     'A light dome over the stage with the lamp sitting on it, shining in at the centre.'),
    (L, 'Still', 'the light stays put where you place it',
     svg(C(12, 12, 6.5), P('M12 2.5V5M12 19V21.5M2.5 12H5M19 12H21.5'), dot(12, 12, 2.2)),
     'The light held in cross-hairs: placed and fixed.'),
    (L, 'Sway', 'the light drifts gently back and forth',
     svg(P('M4.5 15Q12 5 19.5 15'), P('M7.4 14.3L4.5 15L4.3 12M16.6 14.3L19.5 15L19.7 12'), dot(12, 10, 2)),
     'The light riding a pendulum arc, arrows both ways.'),
    (L, 'Record tilt', 'record the light by tilting the phone (a phone tilting with motion)',
     svg(R(8.25, 5, 7.5, 14, 2, ' transform="rotate(-15 12 12)"'), P('M15.25 3.07A9.5 9.5 0 0 1 20.61 7.98'),
         P('M8.75 20.93A9.5 9.5 0 0 1 3.39 16.01'), dot(12, 12, 1.7)),
     'A tipped phone between two swing arcs, a record dot on its screen.'),
    (L, 'Plays', 'how the look plays over time',
     svg(P('M8.5 4.5L17 10L8.5 15.5Z'), P('M3.5 19.5H20.5'), dot(12, 19.5, 1.9)),
     'A play mark above a timeline and its playhead.'),
    (L, 'By itself', 'plays by itself from the start',
     svg(P('M20 12A8 8 0 1 1 16.59 5.45'), P('M16.1 2.5L16.6 5.45L13.6 6'), P('M10 8.8L15.2 12L10 15.2Z')),
     'A play mark inside a self-turning loop arrow: it runs on its own.'),
    (L, 'Pinned', 'pinned at a chosen moment (a keyframe)',
     svg(P('M12 3.5L16.5 8L12 12.5L7.5 8Z', FILLS), P('M12 12.5V20.5'), P('M4 17H20')),
     'A keyframe diamond on a stem, pinned through the timeline at one moment.'),
    (PR, 'Shape', 'letter thickness, melting, wobble',
     svg(P('M5 16V5.5Q5 4 6.5 4H17.5Q19 4 19 5.5V10.5Q19 12 17.5 12Q16 12 16 13.5Q16 15 14.5 15Q13 15 13 13.5'
           'Q13 12 11.5 12H10Q8.5 12 8.5 13.5V16Q8.5 17.8 6.75 17.8Q5 17.8 5 16Z'), drop(14.5, 17, 1.4)),
     'A soft letter bar melting into drips, one drop falling: the body can thicken, melt and wobble.'),
    (PR, 'Depth', 'bevel, extrusion, 3D sides',
     svg(R(4, 4, 16, 16, 2), R(8.5, 8.5, 7, 7, 1),
         P('M4.6 4.6L8.5 8.5M19.4 4.6L15.5 8.5M4.6 19.4L8.5 15.5M19.4 19.4L15.5 15.5')),
     'A bevelled tile seen face on: the raised face and its sloping edges.'),
    (PR, 'Coats', 'layered colour coats (fill colours)',
     svg(P('M4 14V6Q4 4 6 4H14'), P('M7 17V9Q7 7 9 7H17'), R(10, 10, 10, 10, 2)),
     'Three coats laid one over another, only the top one seen whole.'),
    (PR, 'Material', 'metal/gloss/the reflective sphere material',
     svg(C(12, 12, 8), P('M4.3 13.8Q12 17.5 19.7 13.8'),
         '<ellipse cx="9" cy="8.4" rx="1.9" ry="1.2" transform="rotate(-35 9 8.4)"%s/>' % FILL),
     'A chrome ball: a horizon reflected across it and one bright glint.'),
    (PR, 'Wear', 'scratches, wear and grime',
     svg(P('M4 6Q4 4 6 4H12.5L14 6.2L16.2 5.4L17.2 7.6L20 8.6V18Q20 20 18 20H6Q4 20 4 18Z'),
         P('M7 16L12 11M10.5 17.5L16 12')),
     'A plate with a chipped corner and two scratches across it.'),
    (PR, 'Light', 'lamp position and shadow',
     svg(P('M9 8H14.5V16.5H9Z'), P('M9 16.5L14.5 20.5H20L14.5 16.5Z', FILL), C(5, 4.8, 1.8), P('M7.6 7.4L8.6 8.4')),
     "A lamp up in the corner throws a block's shadow long across the floor."),
    (PR, 'Sides', 'the extruded side walls of letters',
     svg(P('M4 9H15V20H4Z'), P('M4 9L9 4H20L15 9M20 4V15L15 20'), P('M17.5 6.5V17.5')),
     'An extruded block with a line along its side wall: the walls behind the letter face.'),
    (PR, 'Floor', 'a wet reflective floor under the words',
     svg(P('M7 12.5V8.5A5 5 0 0 1 17 8.5V12.5'), P('M3 14.5H21'), P('M6 17.5H18M9 20.5H15')),
     'A letter standing on a floor line, its reflection breaking into ripples below.'),
    (PR, 'Tube', 'neon glass tube letters',
     svg(P('M4.5 18V10A4 4 0 0 1 12.5 10V14A3.5 3.5 0 0 0 19.5 14V6'), P('M3 18H6M18 6H21')),
     'One bent neon tube looping over and under, capped with electrodes at both ends.'),
    (PR, 'Glass', 'clear glass / ice refraction',
     svg(P('M6 4.5H18L16.6 19Q16.5 20 15.4 20H8.6Q7.5 20 7.4 19Z'), P('M6.6 10.5Q12 12 17.4 10.5'),
         R(10.6, 7.6, 4, 4, 0.8, ' transform="rotate(18 12.6 9.6)"'), P('M9.2 13.5V17')),
     'A clear tumbler with an ice cube at the waterline and a shine down its side.'),
    (PR, 'Surface', 'fine surface texture',
     svg(P('M3.5 18L7.5 6H20.5L16.5 18Z'), P('M9.6 9.5H11.4M14 9.5H15.8M8.4 13H10.2M12.8 13H14.6')),
     'A swatch laid flat in perspective, its face flecked with fine grain.'),
    (PR, 'Pattern', 'pop-art pattern (halftone dots, stripes)',
     svg(R(4, 4, 16, 16, 3), P('M5 10L10 5M5 14.5L14.5 5'), dots([(16, 13.2, 1), (13.2, 16, 1), (16.4, 16.4, 1.5)])),
     'A pop-art swatch: stripes in one corner, halftone dots swelling in the other.'),
    (PR, 'Stone', 'carved stone (chisel marks)',
     svg(P('M4 17L6.5 9L12 5L18.5 7.5L20 15L15 19.5H7.5Z'), P('M6.5 9L11 12.5L18.5 7.5M11 12.5L12.5 19.5')),
     'A chiselled block, its faces cut flat into facets.'),
    (PR, 'Fur', 'furry/shaggy letters',
     svg(P('M3.5 15C7 14 7 9 9.5 7.5S13 5 14 3.5'), P('M5 19C9 18 9 12 12 10S17 7 19 4'),
         P('M8 20.5C12 19.5 12.5 14.5 15 13S19 11 20.5 8.5')),
     'A lock of three flowing strands: the soft combed lie of fur.'),
    (PR, 'Lamps', 'a marquee board of light bulbs',
     svg(R(3, 5, 18, 14, 2.5), P('M8.5 12H15.5'),
         dots([(6.5, 8.5, 1.1), (12, 8.5, 1.1), (17.5, 8.5, 1.1), (6.5, 15.5, 1.1), (12, 15.5, 1.1), (17.5, 15.5, 1.1)])),
     'A marquee sign: rows of bulbs above and below the title bar.'),
    (PR, 'Lightning', 'electric bolts',
     svg(P('M13.5 3L6.5 13H12L10.5 21L17.5 10.5H12Z')),
     'A single hand-cut bolt.'),
    (PR, 'Paper', 'cut paper layers',
     svg(P('M7.5 7.5V6Q7.5 4 9.5 4H18Q20 4 20 6V14.5Q20 16.5 18 16.5H17'), P('M4 10Q6.5 7.5 9 9.5T14 9.5T17 9V18Q17 20 15 20H6Q4 20 4 18Z')),
     'Two sheets layered, the front one cut along a wavy edge.'),
    (PR, 'Burn', 'paper burning away',
     svg(P('M5 20V6Q5 4 7 4H10.5Q11.5 7 13.5 6.8Q14 9.5 16 9.8Q16.5 12 19 13V18Q19 20 17 20Z'), drop(18.2, 2.6, 1.8)),
     'A page whose corner has burned to a ragged edge, a flame still licking at it.'),
    (PR, 'Vine', 'vines with leaves growing over letters',
     svg(P('M6 20C6 15 10 14.5 12 12C14 9.5 13 6 16 5'),
         P('M8.3 15.1C5.4 15.4 3.9 13.3 4.1 11C6.7 10.9 8.6 12.6 8.3 15.1Z'),
         P('M13.2 9.6C14.2 7.2 16.8 6.8 19.4 7.8C18.4 10.2 15.8 11 13.2 9.6Z', FILLS)),
     'A climbing stem with a leaf on each side, one leaf in full colour.'),
    (PR, 'Stitch', 'satin stitch embroidery',
     svg(C(12, 13, 7.5), P('M10.5 5.5V3.5H13.5V5.5'), P('M8.2 15.2L10.2 11.2M10.7 16.4L12.7 12.4M13.2 17.6L15.2 13.6')),
     'An embroidery hoop holding a band of slanted satin stitches.'),
    (PR, 'Balloon', 'inflated foil balloon letters',
     svg(P('M12 3C16 3 18.5 6 18.5 9.5C18.5 13.5 15 16 12 16C9 16 5.5 13.5 5.5 9.5C5.5 6 8 3 12 3Z'),
         P('M8.6 8.8A3.6 3.6 0 0 1 10.8 6'), P('M10.9 17.6L12 16L13.1 17.6Z', FILLS), P('M12 17.6C10.6 18.8 13.4 19.6 12 21')),
     'A blown-up balloon with its shine, tied knot and curling string.'),
    (PR, 'Sand', 'letters heaped from sand',
     svg(P('M3 19.5H21'), P('M4.5 19.5C8 19.5 8.5 12 12 12C15.5 12 16 19.5 19.5 19.5'),
         dots([(11, 4.5, 0.95), (13, 6.9, 0.95), (11.8, 9.3, 0.95)])),
     'Grains pouring down onto a soft heap.'),
    (PR, 'Bits', 'made of many small things (sequins, beads, sprinkles)',
     svg(C(7, 7.5, 2.5), P('M15 4.5L18.5 7.5M5 16.5L9 18'), P('M16.5 12L19.5 15L16.5 18L13.5 15Z'), dot(11.5, 12.8, 1.7)),
     'A scatter of sequin, bead, crystal and sprinkles: one thing made of many.'),
    (PR, 'Ink', 'ink bleeding into paper',
     svg(P('M12 11C15.5 10.5 19.5 12 19 15.5C18.6 18.5 15 20.5 12 19.8C8.5 20.6 4.6 18.6 5 15.5C5.4 12.4 8.5 11.5 12 11Z'),
         P('M9.5 15.6C9.8 14.2 14.4 13.9 14.7 15.4C15 16.9 9.3 17.3 9.5 15.6Z'), drop(12, 2.8, 2)),
     'A drop landing in a blot that spreads out past its dark core.'),
    (PR, 'Front', 'a sweeping front (letters are revealed or carried away as it passes)',
     svg(P('M9.8 7H6Q4.5 7 4.5 8.5V15.5Q4.5 17 6 17H9.8'), P('M12.5 3.5Q9 12 12.5 20.5'),
         dots([(15.6, 8, 1.2), (18.6, 10.6, 1), (15.9, 13.6, 1.3), (19.2, 15.6, 0.9), (16.6, 18, 1)])),
     'A curved front sweeps across: the letter whole on one side, scattering into bits on the other.'),
    (PR, 'Shatter', 'breaking into shards',
     svg(P('M10 3.8L4.5 6.5L9 10.5Z'), P('M13 4.5L20 7.5L13.8 11Z'), P('M4 13.5L10.5 13.5L6.5 19.8Z'),
         P('M13.5 14L19.8 13L17 20Z'), P('M10.6 11.2L13 12L11.2 13.8Z', FILL)),
     'Four shards flying apart from a small chip at the break.'),
    (PR, 'Fog', 'fog on a glass pane (writing in steam)',
     svg(R(4, 4, 16, 16, 3), P('M7.5 10.5Q9.25 7.5 11 10.5T14.5 10.5T18 10.5'), P('M14.5 10.5V14.5'), drop(14.5, 14.6, 1.4)),
     'A finger-drawn squiggle on a steamed pane, one drip running from it.'),
    (PR, 'Flag', 'a waving cloth flag',
     svg(P('M5 21V3.5'), P('M5 4.5C8 3 10.5 6 13.5 5S18 3.5 19.5 4.5V13.5C18 12.5 16 14 13.5 14.5S8 13 5 14.5')),
     'A cloth rippling from its pole.'),
    (PR, 'Panes', 'stained-glass panes with lead lines',
     svg(P('M5 20V10A7 7 0 0 1 19 10V20Z'), P('M12 3V20M5 14L12 11L19 14')),
     'An arched window divided into panes by its lead lines.'),
    (PR, 'Rust', 'peeling paint and rust',
     svg(P('M3.5 18.5H20.5'), P('M8 18.5C14 18.5 19.5 14.5 19 9.5C18.6 5.8 13.6 5.5 13.6 9.2C13.6 11.8 17 12 17.2 9.6'),
         P('M5.5 11L8 10.4L8.4 12.8L6 13.6Z', FILL)),
     'A strip of paint curling up off the surface, a flake drifting away.'),
    (PR, 'Rivets', 'rivets on a metal frame',
     svg(P('M4 5.5Q4 4 5.5 4H9Q10.5 4 10.5 5.5V13.5H18.5Q20 13.5 20 15V18.5Q20 20 18.5 20H5.5Q4 20 4 18.5Z'),
         dots([(7.25, 8, 1.3), (7.25, 16.75, 1.3), (15.5, 16.75, 1.3)])),
     'An angle bracket of plate metal held by three rivets.'),
    (PR, 'Jelly', 'wobbly jelly',
     svg(P('M6.5 17.5C5.5 12.5 8.5 5 12 5C15.5 5 18.5 12.5 17.5 17.5C17.2 19.2 15 19.5 12 19.5C9 19.5 6.8 19.2 6.5 17.5Z'),
         P('M9.3 11.5Q9.6 9.2 11 8.2'), P('M3.5 10Q2.6 12 3.5 14M20.5 10Q21.4 12 20.5 14')),
     'A soft gumdrop of jelly with its shine, quivering at both sides.'),
    (PR, 'Ember', 'glowing embers on hot iron',
     svg(P('M5.5 19.5Q3.5 19.5 4 17.5L5.8 12Q6.4 10.2 8.3 10L15 9.4Q17 9.2 17.8 11L20 17Q20.6 19.5 18 19.5Z'), P('M12.4 9.7L10.8 13L13.4 15.6L12 19.5'), spark(17, 4.8, 2.6)),
     'A coal split by a glowing crack, a spark lifting off it.'),
    (PR, 'Fire', 'real flames',
     svg(P('M12 3C13 6.5 18 8.5 18 14A6 6 0 0 1 6 14C6 10.5 8 9 9 7C9.5 9 10.5 10 11 10.5C11.5 8 11 5.5 12 3Z'),
         P('M12 20A2.6 2.6 0 0 1 9.4 17.4C9.4 15.5 11 14.5 12 12.5C13 14.5 14.6 15.5 14.6 17.4A2.6 2.6 0 0 1 12 20Z')),
     'A full flame with its hot inner tongue.'),
    (PR, 'Hologram', 'sci-fi hologram scanlines',
     svg(C(12, 8.5, 4.5), P('M7.7 7H16.3M7.7 10H16.3'), P('M7 18L8.3 13.5M17 18L15.7 13.5'), '<ellipse cx="12" cy="19" rx="6" ry="1.6"/>'),
     'An orb drawn in scanlines, held in the beam of its projector pad.'),
    (PR, 'Finish', 'the final film finish (bloom, grain, vignette, contrast)',
     svg(R(3, 4, 18, 16, 2.5), P('M7 4V20M17 4V20'),
         P('M5 7.5V8.5M5 11.5V12.5M5 15.5V16.5M19 7.5V8.5M19 11.5V12.5M19 15.5V16.5'), spark(12, 12, 3.4)),
     'A film frame between sprocket edges, glowing at its heart: the last polish of the picture.'),
    (PR, 'Arrive', 'how letters arrive (fly in, scale in)',
     svg(R(3.5, 10.75, 2.5, 2.5, 0.6), R(8, 9.75, 4.5, 4.5, 1), R(14.5, 7.5, 6, 9, 1.5)),
     'A shape growing as it flies in: small, bigger, there.'),
]

out = [{'group': g, 'word': w, 'meaning': m, 'svg': s, 'note': n} for g, w, m, s, n in ICONS]
with open(sys.argv[1], 'w', encoding='utf-8') as fh:
    json.dump(out, fh, ensure_ascii=False, indent=1)
print(len(out))
