# -*- coding: utf-8 -*-
"""Turn the <symbol> block of SpriteLabMobile.html into SpriteIcons.java.

The phone must draw the SAME icons the web design uses, not lookalikes, so the
path data is COPIED rather than redrawn. <rect> and <circle> have no Android
equivalent, so they are converted to the identical path here, once, mechanically.

    python tools/spritelab/genicons.py        (run from the repo root)
"""
import io
import re

SRC = 'tools/spritelab/SpriteLabMobile.html'
OUT = 'app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteIcons.java'

html = io.open(SRC, encoding='utf-8').read()
block = html.split('<svg style="display:none">')[1].split('</svg>')[0]


def attrs(tag):
    return dict(re.findall(r'([a-zA-Z-]+)="([^"]*)"', tag))


def num(d, k, dflt=0.0):
    return float(d.get(k, dflt))


def rect_to_path(a):
    x, y, w, h = num(a, 'x'), num(a, 'y'), num(a, 'width'), num(a, 'height')
    r = num(a, 'rx')
    if r <= 0:
        return 'M%g %gH%gV%gH%gZ' % (x, y, x + w, y + h, x)
    return ('M%g %gH%gA%g %g 0 0 1 %g %gV%gA%g %g 0 0 1 %g %gH%gA%g %g 0 0 1 %g %gV%gA%g %g 0 0 1 %g %gZ'
            % (x + r, y, x + w - r, r, r, x + w, y + r, y + h - r, r, r, x + w - r, y + h,
               x + r, r, r, x, y + h - r, y + r, r, r, x + r, y))


def circle_to_path(a):
    cx, cy, r = num(a, 'cx'), num(a, 'cy'), num(a, 'r')
    return 'M%g %gA%g %g 0 1 0 %g %gA%g %g 0 1 0 %g %gZ' % (
        cx - r, cy, r, r, cx + r, cy, r, r, cx - r, cy)


icons = []
for name, body in re.findall(r'<symbol id="i-([a-z]+)"[^>]*>(.*?)</symbol>', block, re.S):
    strokes, fills = [], []
    for kind, raw in re.findall(r'<(path|rect|circle)\b([^>]*)/>', body):
        a = attrs('<' + kind + raw + '/>')
        if kind == 'path':
            d = a['d']
        elif kind == 'rect':
            d = rect_to_path(a)
        else:
            d = circle_to_path(a)
        (fills if a.get('fill', 'none') not in ('none', '') else strokes).append(d)
    icons.append((name, strokes, fills))


def jstr(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'


L = []
A = L.append
A('package com.fadcam.ui.faditor.sprite;')
A('')
A('import android.graphics.Canvas;')
A('import android.graphics.ColorFilter;')
A('import android.graphics.Paint;')
A('import android.graphics.Path;')
A('import android.graphics.PixelFormat;')
A('import android.graphics.Rect;')
A('import android.graphics.drawable.Drawable;')
A('')
A('import androidx.annotation.NonNull;')
A('import androidx.annotation.Nullable;')
A('')
A('import java.util.HashMap;')
A('import java.util.Map;')
A('')
A('/**')
A(' * The SpriteLab icon set, drawn from the SAME path data the web design uses.')
A(' *')
A(' * <p>GENERATED from the {@code &lt;symbol&gt;} block of')
A(' * {@code tools/spritelab/SpriteLabMobile.html} by {@code tools/spritelab/genicons.py}.')
A(' * Edit the HTML and re-run; do not hand-edit the path strings here, or the phone and the')
A(' * web design will drift apart, which is the one thing this file exists to prevent.</p>')
A(' *')
A(' * <p>Everything is stroked in a 24x24 box at stroke-width 1.7 with round caps and joins,')
A(' * matching {@code svg.i} in the stylesheet, so an icon scales the way the SVG does.</p>')
A(' */')
A('public final class SpriteIcons {')
A('')
A('    private SpriteIcons() {}')
A('')
A('    /** viewBox side. Every path below is authored in this space. */')
A('    private static final float BOX = 24f;')
A('    private static final float STROKE = 1.7f;')
A('')
A('    private static final Map<String, String[]> STROKED = new HashMap<>();')
A('    private static final Map<String, String[]> FILLED = new HashMap<>();')
A('    private static final Map<String, Path[]> CACHE_S = new HashMap<>();')
A('    private static final Map<String, Path[]> CACHE_F = new HashMap<>();')
A('')
A('    static {')
for name, strokes, fills in icons:
    if strokes:
        A('        STROKED.put("%s", new String[]{' % name)
        for d in strokes:
            A('                %s,' % jstr(d))
        A('        });')
    if fills:
        A('        FILLED.put("%s", new String[]{' % name)
        for d in fills:
            A('                %s,' % jstr(d))
        A('        });')
A('    }')
A('')
A('    /** True when {@code name} is one this set actually knows how to draw. */')
A('    public static boolean has(@NonNull String name) {')
A('        return STROKED.containsKey(name) || FILLED.containsKey(name);')
A('    }')
A('')
A('    @NonNull')
A('    private static Path[] parse(@NonNull Map<String, String[]> src,')
A('                                @NonNull Map<String, Path[]> cache, @NonNull String name) {')
A('        Path[] got = cache.get(name);')
A('        if (got != null) return got;')
A('        String[] data = src.get(name);')
A('        if (data == null) { cache.put(name, new Path[0]); return new Path[0]; }')
A('        Path[] made = new Path[data.length];')
A('        for (int i = 0; i < data.length; i++) {')
A('            made[i] = androidx.core.graphics.PathParser.createPathFromPathData(data[i]);')
A('        }')
A('        cache.put(name, made);')
A('        return made;')
A('    }')
A('')
A('    /**')
A('     * An icon in one flat colour. No half-opaque anything: a control that is off gets a grey')
A('     * icon, never a faded coloured one.')
A('     */')
A('    @NonNull')
A('    public static IconDrawable of(@NonNull String name, int colour, int sizePx) {')
A('        IconDrawable d = new IconDrawable(name, colour);')
A('        d.setBounds(0, 0, sizePx, sizePx);')
A('        return d;')
A('    }')
A('')
A('    public static final class IconDrawable extends Drawable {')
A('        private final Path[] stroked;')
A('        private final Path[] filled;')
A('        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);')
A('        private int colour;')
A('')
A('        IconDrawable(@NonNull String name, int colour) {')
A('            this.stroked = parse(STROKED, CACHE_S, name);')
A('            this.filled = parse(FILLED, CACHE_F, name);')
A('            this.colour = colour;')
A('            paint.setStrokeCap(Paint.Cap.ROUND);')
A('            paint.setStrokeJoin(Paint.Join.ROUND);')
A('        }')
A('')
A('        /** Retint in place: a chip that lights up does not need a new drawable. */')
A('        public void setColour(int c) { colour = c; invalidateSelf(); }')
A('')
A('        @Override public void draw(@NonNull Canvas canvas) {')
A('            Rect b = getBounds();')
A('            if (b.width() <= 0 || b.height() <= 0) return;')
A('            float s = Math.min(b.width(), b.height()) / BOX;')
A('            int save = canvas.save();')
A('            canvas.translate(b.left + (b.width() - BOX * s) / 2f,')
A('                             b.top + (b.height() - BOX * s) / 2f);')
A('            canvas.scale(s, s);')
A('            paint.setColor(colour);')
A('            paint.setStyle(Paint.Style.FILL);')
A('            for (Path p : filled) canvas.drawPath(p, paint);')
A('            paint.setStyle(Paint.Style.STROKE);')
A('            paint.setStrokeWidth(STROKE);')
A('            for (Path p : stroked) canvas.drawPath(p, paint);')
A('            canvas.restoreToCount(save);')
A('        }')
A('')
A('        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }')
A('        @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }')
A('        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }')
A('        @Override public int getIntrinsicWidth() { return (int) BOX; }')
A('        @Override public int getIntrinsicHeight() { return (int) BOX; }')
A('    }')
A('}')

io.open(OUT, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
print('wrote %s with %d icons: %s' % (OUT, len(icons), ' '.join(n for n, _, _ in icons)))
