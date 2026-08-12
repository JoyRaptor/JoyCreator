package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The path a Curve gradient runs along: a start anchor, an end anchor, and up to {@link
 * #VERTEX_CAP} vertices in between, each carrying a bezier handle — JoyRaptor's spec, verbatim:
 * <i>"curve (this is just linear with 1, 2, or 3 vertices with vector bezier handles along the
 * way that can be manipulated instead of a straight line)"</i>.
 *
 * <p><b>One handle per anchor, not two.</b> Each anchor carries a single handle VECTOR {@code h};
 * the outgoing control is {@code a + h} and the incoming control is {@code a - h}. That is half
 * the state of an independent in/out pair and it makes the join C1-smooth <i>by construction</i>
 * — a curve cannot develop a kink the user did not ask for, because there is no way to express
 * one. Handles default to zero, which collapses every cubic to its chord, so a fresh Curve is a
 * straight line from start to end and renders exactly like Linear until it is bent.</p>
 *
 * <p><b>Capped, like {@code MaskSdf}.</b> {@link #VERTEX_CAP} is the ceiling the spec asks for and
 * also what keeps {@link #PACKED_LENGTH} small; a vertex added past the cap is refused rather than
 * dropped later, the same contract {@link GradientRamp#addColorStop} follows.</p>
 *
 * <p><b>{@link #samplePoints} resamples at EQUAL ARC LENGTH, and that is the whole trick.</b> The
 * shader needs each pixel's distance ALONG the path, not its straight-line distance, and the
 * tractable way to get that is a polyline plus a cumulative-length lookup. If the samples were
 * evenly spaced in the bezier's own parameter {@code u}, each segment's share of the total length
 * would differ and the shader would need a cumulative-length uniform per sample. Resampling by
 * arc length instead makes segment {@code i} cover exactly {@code [i/N, (i+1)/N]} of the ramp —
 * a COMPILE-TIME literal in the unrolled shader — so only the x/y of each sample has to travel as
 * a uniform. That halves the uniform budget and removes a whole class of pack/emit drift.</p>
 *
 * <p>Android-free so the JVM harness can reach it, exactly like {@link GradientRamp}.</p>
 */
public final class GradientCurve {

    /** The most intermediate vertices the path carries. The spec asks for 1–3. */
    public static final int VERTEX_CAP = 3;

    /** Floats in {@link #toFloatArray()}: 1 count + (2 anchors + CAP vertices) * 4. */
    public static final int PACKED_LENGTH = 1 + (2 + VERTEX_CAP) * 4;

    /**
     * How many points {@link #samplePoints} produces for the shader, and therefore how many
     * chords approximate the path.
     *
     * <p><b>26 points / 25 chords, packed two-per-{@code vec4} into 13 uniform vectors.</b> The
     * count is a straight accuracy-for-cost trade and both ends of it are real. Chord error is
     * the sagitta of the arc each chord replaces: for the ordinary case — one or two vertices,
     * a bend of up to a half turn — 25 chords keep it near {@code 0.001} of the frame, roughly a
     * pixel at 1080p, i.e. invisible. A deliberately extreme path (three vertices, every handle
     * pulled long, several full loops) flattens visibly between samples; that is accepted rather
     * than paid for on every ordinary gradient. The cost side is per-pixel: the shader tests all
     * 25 chords unrolled, about the same arithmetic as a 17-tap blur, on a card the registry
     * already weights at 1.8. 25 is also chosen so {@code 1/25 = 0.04} is exact in binary
     * floating point, which keeps the emitted per-segment literals clean.</p>
     */
    public static final int SAMPLE_POINTS = 26;

    /** Chords between {@link #SAMPLE_POINTS} samples. */
    public static final int SAMPLE_SEGMENTS = SAMPLE_POINTS - 1;

    /** {@code vec4} uniforms the samples pack into, two points per vector. */
    public static final int SAMPLE_VECS = SAMPLE_POINTS / 2;

    /** How finely each cubic is walked before the arc-length resample. Cheap — CPU, once per
     *  uniform push — and fine enough that the resample's own error is far below the chord's. */
    private static final int DENSE_PER_SEGMENT = 48;

    /** One anchor: a position and the handle vector that shapes both sides of it. */
    public static final class Anchor {
        public float x, y;
        /** Outgoing control is {@code (x,y) + (hx,hy)}; incoming is {@code (x,y) - (hx,hy)}. */
        public float hx, hy;

        public Anchor(float x, float y) { this.x = x; this.y = y; }

        public Anchor(float x, float y, float hx, float hy) {
            this.x = x; this.y = y; this.hx = hx; this.hy = hy;
        }

        @NonNull
        public Anchor copy() { return new Anchor(x, y, hx, hy); }
    }

    /** Start of the path. {@code t == 0} here. */
    @NonNull public final Anchor start = new Anchor(0f, 0.5f);
    /** End of the path. {@code t == 1} here. */
    @NonNull public final Anchor end = new Anchor(1f, 0.5f);
    /** 0..{@link #VERTEX_CAP} intermediate vertices, in path order. */
    @NonNull public final List<Anchor> vertices = new ArrayList<>();

    /**
     * The default: a straight line clean across the frame at mid height.
     *
     * <p>That is deliberately the SAME ramp Linear produces at the default centre and angle, so
     * switching a card to Curve changes nothing on screen until a vertex or a handle is moved.
     * A shape chip that visibly rearranges the picture the instant it is tapped reads as a bug.</p>
     */
    @NonNull
    public static GradientCurve defaultCurve() { return new GradientCurve(); }

    // ── Mutation ────────────────────────────────────────────────────────────────────────────

    /** @return false when the path is already at {@link #VERTEX_CAP} — the caller should say so. */
    public boolean addVertex(float x, float y) {
        if (vertices.size() >= VERTEX_CAP) return false;
        vertices.add(new Anchor(x, y));
        return true;
    }

    public boolean removeVertex(int index) {
        if (index < 0 || index >= vertices.size()) return false;
        vertices.remove(index);
        return true;
    }

    /**
     * Grow or shrink to exactly {@code n} vertices, spacing any NEW ones evenly along the
     * straight start→end line so they land on the current path rather than at the origin.
     * Shrinking drops from the end, which is the only choice that leaves the remaining vertices
     * where the user put them.
     */
    public void setVertexCount(int n) {
        n = Math.max(0, Math.min(VERTEX_CAP, n));
        while (vertices.size() > n) vertices.remove(vertices.size() - 1);
        while (vertices.size() < n) {
            float f = (vertices.size() + 1f) / (n + 1f);
            vertices.add(new Anchor(start.x + (end.x - start.x) * f,
                    start.y + (end.y - start.y) * f));
        }
    }

    /** Every anchor in path order: start, vertices, end. Never fewer than two. */
    @NonNull
    public List<Anchor> anchors() {
        List<Anchor> out = new ArrayList<>(2 + vertices.size());
        out.add(start);
        for (int i = 0; i < Math.min(vertices.size(), VERTEX_CAP); i++) out.add(vertices.get(i));
        out.add(end);
        return out;
    }

    // ── Sampling ────────────────────────────────────────────────────────────────────────────

    /**
     * {@code n} points along the path, spaced at EQUAL ARC LENGTH, as {@code [x0,y0,x1,y1,...]}.
     * See the class note for why equal arc length rather than equal bezier parameter.
     *
     * <p>A degenerate path — every anchor coincident, so there is no length to walk — returns
     * {@code n} copies of the start. Degrading to a point rather than dividing by zero is the
     * same "drop it silently, do not crash" contract {@code MaskSdf.packShapes} follows; the
     * shader then reports {@code t == 0} everywhere, which renders a flat fill.</p>
     */
    @NonNull
    public float[] samplePoints(int n) {
        n = Math.max(2, n);
        List<Anchor> a = anchors();

        // Walk every cubic densely, accumulating a polyline and its cumulative length.
        int dense = (a.size() - 1) * DENSE_PER_SEGMENT + 1;
        float[] dx = new float[dense];
        float[] dy = new float[dense];
        float[] cum = new float[dense];
        int w = 0;
        dx[w] = a.get(0).x;
        dy[w] = a.get(0).y;
        cum[w] = 0f;
        w++;
        for (int s = 0; s + 1 < a.size(); s++) {
            Anchor p = a.get(s), q = a.get(s + 1);
            float c1x = p.x + p.hx, c1y = p.y + p.hy;
            float c2x = q.x - q.hx, c2y = q.y - q.hy;
            for (int i = 1; i <= DENSE_PER_SEGMENT; i++) {
                float u = (float) i / DENSE_PER_SEGMENT;
                float m = 1f - u;
                float bx = m * m * m * p.x + 3f * m * m * u * c1x
                        + 3f * m * u * u * c2x + u * u * u * q.x;
                float by = m * m * m * p.y + 3f * m * m * u * c1y
                        + 3f * m * u * u * c2y + u * u * u * q.y;
                cum[w] = cum[w - 1] + (float) Math.hypot(bx - dx[w - 1], by - dy[w - 1]);
                dx[w] = bx;
                dy[w] = by;
                w++;
            }
        }

        float total = cum[w - 1];
        float[] out = new float[n * 2];
        if (!(total > 1e-6f)) {
            for (int i = 0; i < n; i++) { out[i * 2] = dx[0]; out[i * 2 + 1] = dy[0]; }
            return out;
        }
        // Walk the dense table once, emitting the equal-arc-length samples as they are passed.
        int k = 1;
        for (int i = 0; i < n; i++) {
            float want = total * i / (n - 1f);
            while (k < w - 1 && cum[k] < want) k++;
            float lo = cum[k - 1], hi = cum[k];
            float f = hi - lo > 1e-9f ? (want - lo) / (hi - lo) : 0f;
            out[i * 2] = dx[k - 1] + (dx[k] - dx[k - 1]) * f;
            out[i * 2 + 1] = dy[k - 1] + (dy[k] - dy[k - 1]) * f;
        }
        return out;
    }

    // ── Packed float form — what an FxInstance actually stores ────────────────────────────────

    /**
     * Layout: {@code [vertexCount, start.x,start.y,start.hx,start.hy,
     * end.x,end.y,end.hx,end.hy, v0.x,v0.y,v0.hx,v0.hy, v1..., v2...]}.
     *
     * <p>An explicit COUNT rather than {@link GradientRamp}'s {@code >1.0} sentinel, because the
     * two are read by different sides: the ramp's slots are read by the SHADER, which has no way
     * to branch on a count cheaply, whereas these slots are read on the CPU by {@link
     * #fromFloatArray} before anything reaches a uniform. A handle is also a signed VECTOR that
     * legitimately exceeds 1.0, so a "greater than one means unused" sentinel could not tell an
     * unused slot from a long handle in the first place.</p>
     */
    @NonNull
    public float[] toFloatArray() {
        float[] out = new float[PACKED_LENGTH];
        int n = Math.min(vertices.size(), VERTEX_CAP);
        out[0] = n;
        writeAnchor(out, 1, start);
        writeAnchor(out, 5, end);
        for (int i = 0; i < VERTEX_CAP; i++) {
            writeAnchor(out, 9 + i * 4, i < n ? vertices.get(i) : new Anchor(0f, 0f));
        }
        return out;
    }

    private static void writeAnchor(@NonNull float[] out, int o, @NonNull Anchor a) {
        out[o] = a.x; out[o + 1] = a.y; out[o + 2] = a.hx; out[o + 3] = a.hy;
    }

    /** Reverse of {@link #toFloatArray()}. Tolerant of a short or garbled array — falls back to
     *  {@link #defaultCurve()} rather than throwing, matching {@link GradientRamp#fromFloatArray}. */
    @NonNull
    public static GradientCurve fromFloatArray(@Nullable float[] a) {
        if (a == null || a.length < PACKED_LENGTH) return defaultCurve();
        GradientCurve c = new GradientCurve();
        readAnchor(a, 1, c.start);
        readAnchor(a, 5, c.end);
        int n = Math.max(0, Math.min(VERTEX_CAP, Math.round(a[0])));
        for (int i = 0; i < n; i++) {
            Anchor v = new Anchor(0f, 0f);
            readAnchor(a, 9 + i * 4, v);
            c.vertices.add(v);
        }
        return c;
    }

    private static void readAnchor(@NonNull float[] a, int o, @NonNull Anchor dst) {
        dst.x = san(a[o]); dst.y = san(a[o + 1]);
        dst.hx = san(a[o + 2]); dst.hy = san(a[o + 3]);
    }

    private static float san(float v) { return Float.isNaN(v) || Float.isInfinite(v) ? 0f : v; }

    // ── copy/undo ───────────────────────────────────────────────────────────────────────────

    @NonNull
    public GradientCurve copy() {
        GradientCurve c = new GradientCurve();
        c.start.x = start.x; c.start.y = start.y; c.start.hx = start.hx; c.start.hy = start.hy;
        c.end.x = end.x; c.end.y = end.y; c.end.hx = end.hx; c.end.hy = end.hy;
        for (Anchor v : vertices) c.vertices.add(v.copy());
        return c;
    }
}
