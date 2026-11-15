package com.fadcam.ui.lobby.arrival.stage;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.lobby.arrival.Flight;
import com.fadcam.ui.lobby.arrival.Show;
import com.fadcam.vislab.gl.GlProgram;
import com.fadcam.vislab.gl.GlTarget;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Draws one arrival: Joybot's picture, his light trail, his thruster rings and sparks, the landing
 * flash and the heat haze. GL thread only. Built on VisLab's GL pieces ({@link GlProgram},
 * {@link GlTarget}); each effect is its own small program (the Note 9 runs two small programs much
 * faster than one big one, TextLab T13).
 *
 * <p>Every frame: the trail buffer fades and gets a fresh one-colour copy of him; the effects are
 * drawn into a half-size buffer (they are soft light, and that halves their cost); then the window
 * gets the heat haze, the effects as light, and his picture on top, sharp.</p>
 */
final class StageRenderer {

    private static final int FLOATS = 19;          // corner 2, centre 2, half 2, rot 1, c1 4, c2 4, p 4
    private static final int MAX_QUADS = 600;
    private static final float TAU = (float) (Math.PI * 2.0);

    private final Context app;
    private final Show show;
    private final Flight.Player player;
    private final Flight.Pose pose = new Flight.Pose();
    private final Fx fx;
    private final float d;
    private final float pegUp;

    private GlProgram ringP, lightP, spriteP, colourP, screenP, hazeP;
    private int vaoBatch, vboBatch, vaoSprite, vboSprite, vaoEmpty;
    private int texIdle, texSpin, texBack, texPanel;
    @Nullable private GlTarget trail, fxT;
    private boolean halfFloat;
    private final FloatBuffer batch = ByteBuffer.allocateDirect(MAX_QUADS * 6 * FLOATS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int quads;
    private final FloatBuffer sprite = ByteBuffer.allocateDirect(6 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private float viewW, viewH;
    private long lastT = -1L;
    /** His pose last frame, for smearing the trail between frames: x, y, rot (rad). */
    private float tpX, tpY, tpRot;
    private boolean tpSet;

    StageRenderer(@NonNull Context c, @NonNull Show show) {
        this.app = c.getApplicationContext();
        this.show = show;
        this.player = new Flight.Player(show.flight);
        this.d = show.density;
        this.pegUp = show.flight.pegUp;
        this.fx = new Fx(pegUp);
    }

    // ── set up and tear down ────────────────────────────────────────────────

    void init() {
        String quad = src("quad.vert"), fill = src("fill.vert");
        ringP = GlProgram.link("joybot ring", quad, src("ring.frag"));
        lightP = GlProgram.link("joybot light", quad, src("light.frag"));
        hazeP = GlProgram.link("joybot haze", quad, src("haze.frag"));
        spriteP = GlProgram.link("joybot sprite", src("sprite.vert"), src("sprite.frag"));
        colourP = GlProgram.link("joybot colour", fill, src("colour.frag"));
        screenP = GlProgram.link("joybot screen", fill, src("screen.frag"));

        int[] ids = new int[3];
        GLES30.glGenVertexArrays(3, ids, 0);
        vaoBatch = ids[0];
        vaoSprite = ids[1];
        vaoEmpty = ids[2];
        GLES30.glGenBuffers(2, ids, 0);
        vboBatch = ids[0];
        vboSprite = ids[1];

        GLES30.glBindVertexArray(vaoBatch);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboBatch);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.capacity() * 4, null, GLES30.GL_STREAM_DRAW);
        int stride = FLOATS * 4;
        int[] sizes = {2, 2, 2, 1, 4, 4, 4};
        int off = 0;
        for (int i = 0; i < sizes.length; i++) {
            GLES30.glEnableVertexAttribArray(i);
            GLES30.glVertexAttribPointer(i, sizes[i], GLES30.GL_FLOAT, false, stride, off * 4);
            off += sizes[i];
        }
        GLES30.glBindVertexArray(vaoSprite);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSprite);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sprite.capacity() * 4, null, GLES30.GL_STREAM_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8);
        GLES30.glBindVertexArray(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

        // a single sharp home pose (joybot/hi/home.webp, for his zoom toward us) is shown much smaller most of the time: mips
        texIdle = upload(show.idle, show.idleCols == 1 && show.idleRows == 1);
        texSpin = show.spin != null ? upload(show.spin, false) : 0;
        texBack = show.back != null ? upload(show.back, true) : 0;
        texPanel = show.panelPic != null ? upload(show.panelPic, false) : 0;

        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        halfFloat = ext != null && (ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float"));
    }

    void release() {
        for (GlProgram p : new GlProgram[]{ringP, lightP, spriteP, colourP, screenP, hazeP}) if (p != null) p.delete();
        GLES30.glDeleteVertexArrays(3, new int[]{vaoBatch, vaoSprite, vaoEmpty}, 0);
        GLES30.glDeleteBuffers(2, new int[]{vboBatch, vboSprite}, 0);
        int[] t = {texIdle, texSpin, texBack, texPanel};
        for (int id : t) if (id != 0) GLES30.glDeleteTextures(1, new int[]{id}, 0);
        if (trail != null) trail.free();
        if (fxT != null) fxT.free();
        trail = fxT = null;
    }

    private int upload(@NonNull Bitmap b, boolean mips) {
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);            // premultiplied, top row first
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                mips ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        if (mips) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        return id[0];
    }

    @NonNull
    private String src(@NonNull String name) {
        try (InputStream in = app.getAssets().open("joybot/stage/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("joybot shader " + name, e);
        }
    }

    private void ensureTargets(int w, int h) {
        int hw = Math.max(8, w / 2), hh = Math.max(8, h / 2);
        if (trail != null && trail.w == hw && trail.h == hh) return;
        if (trail != null) trail.free();
        if (fxT != null) fxT.free();
        GlTarget.Format f = halfFloat ? GlTarget.RGBA16F : GlTarget.RGBA8;
        try {
            trail = GlTarget.create(hw, hh, f, 1, true);
            fxT = GlTarget.create(hw, hh, f, 1, true);
        } catch (IllegalStateException e) {
            halfFloat = false;
            trail = GlTarget.create(hw, hh, GlTarget.RGBA8, 1, true);
            fxT = GlTarget.create(hw, hh, GlTarget.RGBA8, 1, true);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trail.fb);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        tpSet = false;
    }

    // ── a frame ─────────────────────────────────────────────────────────────

    /** False once there is nothing more to draw. */
    boolean render(long t, int w, int h) {
        viewW = w / d;
        viewH = h / d;
        ensureTargets(w, h);
        float dt = lastT < 0 ? 1f / 60f : Math.max(0f, Math.min(.1f, (t - lastT) / 1000f));
        lastT = t;
        player.pose(t, pose);
        if (pose.visible) fx.step(t, pose);
        long settle = show.flight.tSettle();
        long fadeAt = settle + Show.StageEnd.CALM_MS;
        float gain = 1f - smooth((t - fadeAt) / (float) Show.StageEnd.FADE_MS);
        boolean drawHim = pose.visible && t < settle + 60L;

        GLES30.glEnable(GLES30.GL_BLEND);
        // 1. the trail: fade what is there, smear a fresh copy of him in
        GlTarget tr = trail, fb = fxT;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tr.fb);
        GLES30.glViewport(0, 0, tr.w, tr.h);
        float keep = (float) Math.pow(.0015, dt);
        GLES30.glUseProgram(colourP.id);
        GLES30.glBindVertexArray(vaoEmpty);
        GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniform4f(loc(colourP, "uColour"), 0f, 0f, 0f, 1f - keep);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        if (!halfFloat) {
            // eight bits cannot fade the last crumbs to nothing: take them away
            GLES30.glBlendEquation(GLES30.GL_FUNC_REVERSE_SUBTRACT);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
            GLES30.glUniform4f(loc(colourP, "uColour"), 1.5f / 255f, 1.5f / 255f, 1.5f / 255f, 1.5f / 255f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
        }
        float rotRad = (float) Math.toRadians(pose.rot);
        if (pose.visible && fx.speed > 300f && fx.mode != Flight.Mode.CALM && t < show.flight.tLand()) {
            float px = tpSet ? tpX : pose.x, py = tpSet ? tpY : pose.y, pr = tpSet ? tpRot : rotRad;
            int steps = (int) Math.min(6, Math.max(1, Math.ceil(Math.hypot(pose.x - px, pose.y - py) / 6f)));
            float a = Math.min(.45f, .3f * fx.heat / (float) Math.sqrt(steps));
            premultNormal();
            for (int s = 1; s <= steps; s++) {
                float q = s / (float) steps;
                drawSprite(lerp(px, pose.x, q), lerp(py, pose.y, q), lerp(pr, rotRad, q), pose.face,
                        pose.sx, pose.sy, pose.scale, 0f, 0f, a, true);
            }
        }
        tpX = pose.x; tpY = pose.y; tpRot = rotRad; tpSet = pose.visible;

        // 2. the effects, half size
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb.fb);
        GLES30.glViewport(0, 0, fb.w, fb.h);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        // the trail, laid on with Screen
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        copy(tr.tex(), 1f, false);
        float px2 = 2f / d;
        if (pose.visible) {
            // the calm glow on the floor under him, once he is in place
            float floorA = (1f - fx.heat) * (.2f + .06f * (float) Math.sin(t / 2400.0 * TAU));
            if (fx.mode == Flight.Mode.CALM && floorA > .01f) {
                light(show.flight.ex, show.flight.ey + pegUp + 2f, 34f, 34f * .3f, 0f,
                        1f, 70f / 255f, 200f / 255f, floorA, 1f, 70f / 255f, 200f / 255f, floorA, .001f, 0f);
            }
            flush(lightP, px2, additive());
            // the push rings
            for (Fx.Ring r : fx.rings) {
                float f = r.age / r.life;
                float rad = r.r0 + r.grow * (1f - (1f - f) * (1f - f));
                float alpha = (float) Math.pow(1f - f, 1.6) * (r.calm ? .55f : 1f), hot = Math.max(0f, 1f - f * 2.2f);
                float lw = 2f * (1f - f * .6f) + 2.4f * f, blur = 5f + 8f * f;
                ring(r.x, r.y, rad, rad * .3f, r.rot, lw, blur,
                        1f, (70f + 110f * hot) / 255f, (200f + 45f * hot) / 255f, alpha * (.55f + .4f * hot),
                        1f, 40f / 255f, 190f / 255f, alpha * .9f);
                if (f > .35f) {
                    ring(r.x, r.y, rad, rad * .3f, r.rot, 5f + 6f * f, 0f,
                            240f / 255f, 120f / 255f, 200f / 255f, alpha * .12f, 0f, 0f, 0f, 0f);
                }
            }
            flush(ringP, px2, normalBlend());
            // the thruster's light
            float g = fx.glow, gr = (12f + 22f * g) * fx.scale;
            light(fx.pegX, fx.pegY, gr, gr, 0f, 1f, 235f / 255f, 250f / 255f, .5f * g, 1f, 60f / 255f, 200f / 255f, .35f * g, .3f, 0f);
            // sparks: white-hot, cooling to pink
            for (Fx.Spark s : fx.sparks) {
                float sf = s.age / s.life, sa = (1f - sf) * (1f - sf), core = Math.max(0f, 1f - sf * 2.5f);
                float rr = Math.max(.35f, s.s * (1f - sf * .5f));
                light(s.x, s.y, rr + px2, rr + px2, 0f, 1f, (80f + 170f * core) / 255f, (200f + 50f * core) / 255f, sa,
                        0f, 0f, 0f, 0f, 0f, 1f);
            }
            // glints: a four-point flash that pops and fades
            for (Fx.Glint q : fx.glints) {
                float qf = q.age / q.life, sc2 = (float) Math.sin(Math.PI * qf), L = q.s * sc2;
                if (L <= .05f) continue;
                light(q.x, q.y, L * 1.6f, L * 1.6f, q.a, 1f, 220f / 255f, 245f / 255f, .9f * sc2, 0f, 0f, 0f, 0f, 0f, 2f);
            }
            // landing flash: a bright ground ring and a burst of light
            if (fx.flashAge >= 0f) {
                float ff = fx.flashAge / fx.flashLife, fr = (10f + 60f * fx.flashS) * easeOut(ff), fa = (1f - ff) * (.9f * fx.flashS + .1f);
                light(fx.flashX, fx.flashY, fr + 1f, (fr + 1f) * .28f, 0f, 1f, 240f / 255f, 250f / 255f, fa * .8f,
                        1f, 70f / 255f, 200f / 255f, fa * .5f, .6f, 0f);
            }
            flush(lightP, px2, additive());
            if (fx.flashAge >= 0f) {
                float ff = fx.flashAge / fx.flashLife, fr = (10f + 60f * fx.flashS) * easeOut(ff), fa = (1f - ff) * (.9f * fx.flashS + .1f);
                ring(fx.flashX, fx.flashY, fr * 1.1f, fr * 1.1f * .28f, 0f, 3f * .6f, 0f,
                        1f, 150f / 255f, 225f / 255f, fa, 0f, 0f, 0f, 0f);
                flush(ringP, px2, normalBlend());
            }
        }

        // 3. the window
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (texBack != 0 && pose.visible && gain > 0f) haze(t, gain);
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        copy(fb.tex(), gain, true);
        if (drawHim) {
            premultNormal();
            float sway = 0f, bob = 0f;
            if (pose.down) {
                // match the chat's own Joybot as he takes over: his sway and bob, on the same clock
                float wgt = smooth((t - (settle - 300L)) / 300f);
                double ph = (SystemClock.uptimeMillis() % 3600L) / 3600.0 * Math.PI * 2.0;
                sway = wgt * (float) Math.toRadians(Math.sin(ph) * 1.6);
                bob = wgt * (float) Math.sin(ph * 2.0 + .6) * show.actorDp * .012f;
            }
            drawSprite(pose.x, pose.y, rotRad, pose.face, pose.sx, pose.sy, pose.scale, sway, bob, 1f, false);
        }
        GLES30.glBindVertexArray(0);
        return t < show.endsAt();
    }

    // ── his picture ─────────────────────────────────────────────────────────

    /**
     * Joybot at body centre {@code x, y}. Facing left mirrors the picture about his body's own upright
     * axis (owner, 14 Nov: a flip of the picture, never a squeeze of his box). Squash and stretch are
     * about his hover peg; the pop out of the button and the turn are about his body.
     */
    private void drawSprite(float x, float y, float rot, int face, float sx, float sy, float sc,
                            float sway, float bob, float alpha, boolean tint) {
        Bitmap pic = pose.spinning && show.spin != null ? show.spin : show.idle;
        int tex = pose.spinning && show.spin != null ? texSpin : texIdle;
        int cols = pose.spinning && show.spin != null ? show.spinCols : show.idleCols;
        int rows = pose.spinning && show.spin != null ? show.spinRows : show.idleRows;
        int frame = pose.spinning && show.spin != null ? Math.floorMod(pose.spinFrame, Math.max(1, show.spinCount)) : 0;
        float cw = pic.getWidth() / (float) cols, ch = pic.getHeight() / (float) rows;
        float s = Show.cellScale(cw, ch, show.focus, show.actorDp);
        float bw = cw * s, bh = ch * s, left = -bw * show.focus.centerX(), top = -bh * show.focus.centerY();
        int col = frame % cols, row = frame / cols;
        float W = pic.getWidth(), H = pic.getHeight();
        float u0 = (col * cw + .5f) / W, u1 = ((col + 1) * cw - .5f) / W;
        float v0 = (row * ch + .5f) / H, v1 = ((row + 1) * ch - .5f) / H;
        float[][] c = {{left, top, u0, v0}, {left + bw, top, u1, v0}, {left + bw, top + bh, u1, v1}, {left, top + bh, u0, v1}};
        float cr = (float) Math.cos(rot), sr = (float) Math.sin(rot);
        float cs = (float) Math.cos(sway), ss = (float) Math.sin(sway);
        float[] out = new float[16];
        for (int i = 0; i < 4; i++) {
            float lx = c[i][0] * face * sx;
            float ly = pegUp + (c[i][1] - pegUp) * sy;
            lx *= sc;
            ly *= sc;
            if (sway != 0f) {
                float ry = ly - pegUp;
                float nx = cs * lx - ss * ry, ny = ss * lx + cs * ry;
                lx = nx;
                ly = ny + pegUp;
            }
            ly += bob;
            out[i * 4] = x + cr * lx - sr * ly;
            out[i * 4 + 1] = y + sr * lx + cr * ly;
            out[i * 4 + 2] = c[i][2];
            out[i * 4 + 3] = c[i][3];
        }
        sprite.clear();
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int k : order) sprite.put(out, k * 4, 4);
        sprite.flip();
        GLES30.glUseProgram(spriteP.id);
        GLES30.glUniform2f(loc(spriteP, "uView"), viewW, viewH);
        GLES30.glUniform1f(loc(spriteP, "uAlpha"), alpha);
        GLES30.glUniform1f(loc(spriteP, "uTint"), tint ? 1f : 0f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(loc(spriteP, "uTex"), 0);
        GLES30.glBindVertexArray(vaoSprite);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSprite);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 6 * 16, sprite);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
    }

    // ── heat haze ───────────────────────────────────────────────────────────

    private void haze(long t, float gain) {
        Flight.Mode m = fx.mode;
        float under = m == Flight.Mode.BURST ? 1.35f : m == Flight.Mode.COAST ? .45f : m == Flight.Mode.CALM ? .6f : .95f;
        float ua = (m == Flight.Mode.CALM ? .55f : .25f + .7f * fx.heat) * gain;
        hazePatch(fx.pegX + fx.dnX * 10f, fx.pegY + fx.dnY * 10f, 30f * under, 30f * under * 1.15f, ua, 1f);
        int n = 7;
        for (int hi = 1; hi < n; hi++) {
            if (hi - 1 >= fx.marks.size()) break;
            Fx.Mark g = fx.marks.get(hi - 1);
            float px = g.x - (float) Math.sin(g.rot) * pegUp, py = g.y + (float) Math.cos(g.rot) * pegUp;
            float sc = .7f + .08f * hi;
            hazePatch(px, py, 30f * sc, 30f * sc, (1f - hi / (float) n) * .85f * Math.max(.25f, fx.heat) * gain, hi + 1f);
        }
        if (quads == 0) return;
        GLES30.glUseProgram(hazeP.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBack);
        GLES30.glUniform1i(loc(hazeP, "uBack"), 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texPanel != 0 ? texPanel : texBack);
        GLES30.glUniform1i(loc(hazeP, "uPanel"), 1);
        GLES30.glUniform4f(loc(hazeP, "uBackMap"), show.backRect.left, show.backRect.top, show.backRect.width(), show.backRect.height());
        android.graphics.RectF pr = show.reveal.panel;
        GLES30.glUniform4f(loc(hazeP, "uPanelRect"), pr.left, pr.top, pr.width(), pr.height());
        GLES30.glUniform4f(loc(hazeP, "uRev"), show.reveal.bx, show.reveal.by, show.reveal.radius(t), show.reveal.shift(t));
        GLES30.glUniform1f(loc(hazeP, "uHasPanel"), texPanel != 0 ? 1f : 0f);
        GLES30.glUniform1f(loc(hazeP, "uBias"), show.frostBias * show.reveal.openness(t));
        GLES30.glUniform1f(loc(hazeP, "uTime"), t / 1000f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        flush(hazeP, 1f / d, normalBlend());
    }

    private void hazePatch(float x, float y, float hx, float hy, float strength, float seed) {
        if (strength <= .003f) return;
        put(x, y, hx, hy, 0f, strength, 0f, 0f, 0f, 0f, 0f, 0f, 0f, .05f, .12f, 12f, seed * 17.3f);
    }

    // ── batches ─────────────────────────────────────────────────────────────

    private void ring(float x, float y, float rx, float ry, float rot, float lw, float blur,
                      float r1, float g1, float b1, float a1, float r2, float g2, float b2, float a2) {
        float m = Math.max(rx, ry) + lw + blur * 1.2f + 1f;
        put(x, y, m, m, rot, r1, g1, b1, a1, r2, g2, b2, a2, rx, ry, lw, blur);
    }

    private void light(float x, float y, float hx, float hy, float rot,
                       float r1, float g1, float b1, float a1, float r2, float g2, float b2, float a2, float stop, float kind) {
        put(x, y, hx, hy, rot, r1, g1, b1, a1, r2, g2, b2, a2, stop, kind, 0f, 0f);
    }

    private void put(float x, float y, float hx, float hy, float rot,
                     float r1, float g1, float b1, float a1, float r2, float g2, float b2, float a2,
                     float p0, float p1, float p2, float p3) {
        if (quads >= MAX_QUADS) return;
        quads++;
        float[] cx = {-1f, 1f, 1f, -1f, 1f, -1f}, cy = {-1f, -1f, 1f, -1f, 1f, 1f};
        for (int i = 0; i < 6; i++) {
            batch.put(cx[i]).put(cy[i]).put(x).put(y).put(hx).put(hy).put(rot)
                    .put(r1).put(g1).put(b1).put(a1).put(r2).put(g2).put(b2).put(a2)
                    .put(p0).put(p1).put(p2).put(p3);
        }
    }

    private void flush(@NonNull GlProgram p, float pxDp, @NonNull Runnable blend) {
        if (quads == 0) { batch.clear(); return; }
        batch.flip();
        blend.run();
        GLES30.glUseProgram(p.id);
        GLES30.glUniform2f(loc(p, "uView"), viewW, viewH);
        int upx = loc(p, "uPx");
        if (upx >= 0) GLES30.glUniform1f(upx, pxDp);
        GLES30.glBindVertexArray(vaoBatch);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboBatch);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.capacity() * 4, null, GLES30.GL_STREAM_DRAW);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, batch.limit() * 4, batch);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, quads * 6);
        batch.clear();
        quads = 0;
    }

    private void copy(int tex, float gain, boolean light) {
        GLES30.glUseProgram(screenP.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(loc(screenP, "uTex"), 0);
        GLES30.glUniform1f(loc(screenP, "uGain"), gain);
        GLES30.glUniform1f(loc(screenP, "uLight"), light ? 1f : 0f);
        GLES30.glBindVertexArray(vaoEmpty);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private final Runnable normal = () -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    private final Runnable add = () -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);

    @NonNull private Runnable normalBlend() { return normal; }
    @NonNull private Runnable additive() { return add; }
    private void premultNormal() { normal.run(); }

    private final java.util.HashMap<String, Integer> locs = new java.util.HashMap<>();

    /** Uniform location, looked up once per program (always after glUseProgram on the Note 9). */
    private int loc(@NonNull GlProgram p, @NonNull String name) {
        String key = p.id + ":" + name;
        Integer l = locs.get(key);
        if (l == null) {
            l = GLES30.glGetUniformLocation(p.id, name);
            locs.put(key, l);
        }
        return l;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float smooth(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3f - 2f * t); }
    private static float easeOut(float t) { return 1f - (float) Math.pow(1f - t, 3); }
}
