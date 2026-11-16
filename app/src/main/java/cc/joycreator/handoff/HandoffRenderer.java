package cc.joycreator.handoff;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.gl.GlProgram;
import com.fadcam.vislab.gl.GlTarget;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Draws one frame of a hand-off (FX-1): the two rooms warped into each other, and Joybot over them
 * with his light trail, push rings, sparks and flashes. GL thread only.
 *
 * <p>Built on VisLab's GL pieces ({@link GlProgram}, {@link GlTarget}). Joybot's own light is drawn
 * with the SAME shaders as his arrival ({@code assets/joybot/stage/}, BOT-5; read, never changed), in
 * the same order: the trail buffer fades and gets a fresh one-colour copy of him, the effects go
 * into a half-size buffer, then the window gets the rooms, the effects as light, and him on top,
 * sharp. Each room move is its own small program (the Note 9 runs small programs much faster than
 * one big one), and only the one this show uses is built.</p>
 */
final class HandoffRenderer {

    private static final int FLOATS = 19;          // corner 2, centre 2, half 2, rot 1, c1 4, c2 4, p 4
    private static final int MAX_QUADS = 600;
    private static final float TAU = (float) (Math.PI * 2.0);

    private final Context app;
    private final int mode;
    private final HandoffSprites sprites;
    private final HandoffFx fx = new HandoffFx();
    private final float[] colA1, colA2, colB1, colB2;

    private GlProgram ringP, lightP, spriteP, colourP, screenP, roomP, sheetP, starsP, carryP;
    /** VisLab's Lyric Flight, for Peel and Carry; null: the plain field (starsP) draws instead. */
    @Nullable private HandoffStars lyric;
    private final int a1, a2, b1, b2;
    private int vaoBatch, vboBatch, vaoSprite, vboSprite, vaoEmpty;
    private final int[] texStrip = new int[HandoffMoves.ACTS];
    private int texA, texB;
    @Nullable private GlTarget trail, fxT, stars;
    private boolean halfFloat;
    private final FloatBuffer batch = ByteBuffer.allocateDirect(MAX_QUADS * 6 * FLOATS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int quads;
    private final FloatBuffer sprite = ByteBuffer.allocateDirect(6 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] corners = new float[16];
    private final HandoffMoves.Pose shown = new HandoffMoves.Pose();
    private float viewW, viewH, d = 1f;
    private float tpX, tpY, tpRot;
    private boolean tpSet;
    private boolean mirror;
    private float haveB, whiteFlash, whiteLife = .18f, glow;
    private final HashMap<String, Integer> locs = new HashMap<>();

    HandoffRenderer(@NonNull Context c, int mode, @NonNull HandoffSprites sprites, int a1, int a2, int b1, int b2) {
        this.app = c.getApplicationContext();
        this.mode = mode;
        this.sprites = sprites;
        this.a1 = a1; this.a2 = a2; this.b1 = b1; this.b2 = b2;
        colA1 = rgb(a1); colA2 = rgb(a2); colB1 = rgb(b1); colB2 = rgb(b2);
    }

    private static float[] rgb(int c) {
        return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f};
    }

    // ── set up and tear down ────────────────────────────────────────────────

    /** {@code winW, winH}: the window's size in pixels, as near as is known yet (to build things at their real size). */
    void init(int winW, int winH) {
        String quad = src("joybot/stage/quad.vert"), fill = src("joybot/stage/fill.vert");
        ringP = GlProgram.link("handoff ring", quad, src("joybot/stage/ring.frag"));
        lightP = GlProgram.link("handoff light", quad, src("joybot/stage/light.frag"));
        spriteP = GlProgram.link("handoff sprite", src("joybot/stage/sprite.vert"), src("joybot/stage/sprite.frag"));
        colourP = GlProgram.link("handoff colour", fill, src("joybot/stage/colour.frag"));
        screenP = GlProgram.link("handoff screen", fill, src("joybot/stage/screen.frag"));
        String common = src("handoff/common.glsl");
        String[] room = {"pull", "peel", "kick", "vacuum"};
        roomP = GlProgram.link("handoff " + room[mode], fill, common + src("handoff/" + room[mode] + ".frag"));
        if (mode == HandoffMoves.PULL || mode == HandoffMoves.KICK) {
            sheetP = GlProgram.link("handoff sheet", fill, common + src("handoff/sheet.frag"));
        }
        if (mode == HandoffMoves.PEEL) {
            starsP = GlProgram.link("handoff stars", fill, common + src("handoff/starfield.frag"));
        }
        if (mode == HandoffMoves.PEEL || mode == HandoffMoves.VACUUM) {
            carryP = GlProgram.link("handoff carry", quad, src("handoff/carry.frag"));
        }

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

        for (int i = 0; i < texStrip.length; i++) texStrip[i] = upload(sprites.strips[i].picture, false);
        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        halfFloat = ext != null && (ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float"));
        if (mode == HandoffMoves.PEEL) {
            lyric = HandoffStars.open(app, a1, a2, b1, b2);
            if (lyric != null) {
                // build its programs now, while nothing is moving, not in the middle of his flight
                GlTarget warm = GlTarget.create(Math.max(8, winW / 2), Math.max(8, winH / 2), GlTarget.RGBA8, 1, true);
                lyric.draw(0f, 0f, 1f / 60f, warm.fb, warm.w, warm.h);
                lyric.draw(0f, 1f / 60f, 1f / 60f, warm.fb, warm.w, warm.h);
                warm.free();
            }
        }
    }

    /** The room we are leaving, pictured. */
    void setRoomA(@NonNull Bitmap b) {
        if (texA != 0) GLES30.glDeleteTextures(1, new int[]{texA}, 0);
        texA = upload(b, false);
    }

    /** The room we are going to, pictured: it fades in over its stand-in. */
    void setRoomB(@NonNull Bitmap b) {
        if (texB != 0) GLES30.glDeleteTextures(1, new int[]{texB}, 0);
        texB = upload(b, false);
    }

    boolean hasRoomA() { return texA != 0; }

    void release() {
        for (GlProgram p : new GlProgram[]{ringP, lightP, spriteP, colourP, screenP, roomP, sheetP, starsP, carryP}) {
            if (p != null) p.delete();
        }
        GLES30.glDeleteVertexArrays(3, new int[]{vaoBatch, vaoSprite, vaoEmpty}, 0);
        GLES30.glDeleteBuffers(2, new int[]{vboBatch, vboSprite}, 0);
        for (int id : texStrip) if (id != 0) GLES30.glDeleteTextures(1, new int[]{id}, 0);
        if (texA != 0) GLES30.glDeleteTextures(1, new int[]{texA}, 0);
        if (texB != 0) GLES30.glDeleteTextures(1, new int[]{texB}, 0);
        GlTarget.free(trail);
        GlTarget.free(fxT);
        GlTarget.free(stars);
        trail = fxT = stars = null;
        if (lyric != null) lyric.release();
        lyric = null;
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
    private String src(@NonNull String path) {
        try (InputStream in = app.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("hand-off shader " + path, e);
        }
    }

    private void ensureTargets(int w, int h) {
        int hw = Math.max(8, w / 2), hh = Math.max(8, h / 2);
        if (trail != null && trail.w == hw && trail.h == hh) return;
        GlTarget.free(trail);
        GlTarget.free(fxT);
        GlTarget.free(stars);
        GlTarget.Format f = halfFloat ? GlTarget.RGBA16F : GlTarget.RGBA8;
        try {
            trail = GlTarget.create(hw, hh, f, 1, true);
            fxT = GlTarget.create(hw, hh, f, 1, true);
        } catch (IllegalStateException e) {
            halfFloat = false;
            trail = GlTarget.create(hw, hh, GlTarget.RGBA8, 1, true);
            fxT = GlTarget.create(hw, hh, GlTarget.RGBA8, 1, true);
        }
        stars = mode == HandoffMoves.PEEL ? GlTarget.create(hw, hh, GlTarget.RGBA8, 1, true) : null;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trail.fb);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        tpSet = false;
    }

    // ── a frame ─────────────────────────────────────────────────────────────

    /** Draw {@code f} into the bound window surface of {@code w} x {@code h} pixels. */
    void render(@NonNull HandoffScript.Frame f, @NonNull HandoffMoves.Stage s, float density, int w, int h, float dt) {
        d = density;
        mirror = s.mirror;
        viewW = w / d;
        viewH = h / d;
        ensureTargets(w, h);
        HandoffMoves.Pose him = f.him;
        if (s.mirror) { shown.mirrored(f.him, s.w); him = shown; }
        float pegUp = s.cell * sprites.pegShare();
        fx.step(dt * 1000f, him.x, him.y, him.rot, him.sc, him.fx, pegUp, him.visible);
        if (f.launched) fx.puff(him.x, him.y, 0f, 1.4f);
        if (f.kick) {
            fx.land(s.mx(s.x(350f)), s.y(390f), 1.5f);
            whiteFlash = .6f; whiteLife = .14f;
        }
        if (f.slap) {
            fx.land(s.mx(s.itemCx()), s.itemCy(), 1.3f);
            whiteFlash = .7f; whiteLife = .18f;
            glow = 1f;
        }
        if (f.done) fx.puff(s.mx(s.homeX), s.homeY, 0f, 1.2f);
        if (texB != 0) haveB = Math.min(1f, haveB + dt / .15f);
        glow = Math.max(0f, glow - dt / 1.3f);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GlTarget tr = trail, fb = fxT;

        // 1. the trail: fade what is there, smear a fresh one-colour copy of him in
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tr.fb);
        GLES30.glViewport(0, 0, tr.w, tr.h);
        float keep = (float) Math.pow(.0015, dt);
        GLES30.glUseProgram(colourP.id);
        GLES30.glBindVertexArray(vaoEmpty);
        GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniform4f(loc(colourP, "uColour"), 0f, 0f, 0f, 1f - keep);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        if (!halfFloat) {
            GLES30.glBlendEquation(GLES30.GL_FUNC_REVERSE_SUBTRACT);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
            GLES30.glUniform4f(loc(colourP, "uColour"), 1.5f / 255f, 1.5f / 255f, 1.5f / 255f, 1.5f / 255f);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
        }
        int frame = frameOf(him, f);
        boolean flying = him.fx == HandoffMoves.FX_FLY || him.fx == HandoffMoves.FX_BURST;
        if (him.visible && fx.speed > 300f && flying) {
            float px = tpSet ? tpX : him.x, py = tpSet ? tpY : him.y, pr = tpSet ? tpRot : him.rot;
            int steps = (int) Math.min(6, Math.max(1, Math.ceil(Math.hypot(him.x - px, him.y - py) / 6f)));
            float a = Math.min(.45f, .3f * fx.heat / (float) Math.sqrt(steps));
            if (him.carry) a *= .4f;                 // circling with a load he crosses his own trail: keep it a wisp, not a cloud
            normal.run();
            float hx = him.x, hy = him.y, hr = him.rot;
            boolean pinned = him.hand;
            him.hand = false;
            for (int k = 1; k <= steps; k++) {
                float q = k / (float) steps;
                him.x = px + (hx - px) * q; him.y = py + (hy - py) * q; him.rot = pr + (hr - pr) * q;
                drawHim(him, s, frame, a, true);
            }
            him.x = hx; him.y = hy; him.rot = hr; him.hand = pinned;
        }
        tpX = him.x; tpY = him.y; tpRot = him.rot; tpSet = him.visible;

        // 2. his light, half size
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb.fb);
        GLES30.glViewport(0, 0, fb.w, fb.h);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        copy(tr.tex(), 1f, false);
        float px2 = 2f / d;
        // the new item's glow, in the house's "on" cyan, once it is down
        if (glow > 0f) {
            float gw = (s.itemR - s.itemL) * .5f + 26f, gh = (s.itemB - s.itemT) * .5f + 20f;
            light(s.mx(s.itemCx()), s.itemCy(), gw, gh, 0f, 34f / 255f, 211f / 255f, 238f / 255f, .55f * glow,
                    34f / 255f, 211f / 255f, 238f / 255f, .3f * glow, .55f, 0f);
            flush(lightP, px2, add);
        }
        for (HandoffFx.Ring r : fx.rings) {
            float u = r.age / r.life;
            float rad = r.r0 + r.grow * (1f - (1f - u) * (1f - u));
            float alpha = (float) Math.pow(1f - u, 1.6) * (r.calm ? .55f : 1f), hot = Math.max(0f, 1f - u * 2.2f);
            float lw = 2f * (1f - u * .6f) + 2.4f * u, blur = 5f + 8f * u;
            ring(r.x, r.y, rad, rad * .3f, r.rot, lw, blur,
                    1f, (70f + 110f * hot) / 255f, (200f + 45f * hot) / 255f, alpha * (.55f + .4f * hot),
                    1f, 40f / 255f, 190f / 255f, alpha * .9f);
            if (u > .35f) {
                ring(r.x, r.y, rad, rad * .3f, r.rot, 5f + 6f * u, 0f,
                        240f / 255f, 120f / 255f, 200f / 255f, alpha * .12f, 0f, 0f, 0f, 0f);
            }
        }
        flush(ringP, px2, normal);
        if (him.visible) {
            float g = fx.glow, gr = (12f + 22f * g) * fx.scale;
            light(fx.pegX, fx.pegY, gr, gr, 0f, 1f, 235f / 255f, 250f / 255f, .5f * g, 1f, 60f / 255f, 200f / 255f, .35f * g, .3f, 0f);
        }
        for (HandoffFx.Spark sp : fx.sparks) {
            float sf = sp.age / sp.life, sa = (1f - sf) * (1f - sf), core = Math.max(0f, 1f - sf * 2.5f);
            float rr = Math.max(.35f, sp.s * (1f - sf * .5f));
            light(sp.x, sp.y, rr + px2, rr + px2, 0f, 1f, (80f + 170f * core) / 255f, (200f + 50f * core) / 255f, sa,
                    0f, 0f, 0f, 0f, 0f, 1f);
        }
        for (HandoffFx.Glint q : fx.glints) {
            float qf = q.age / q.life, sc2 = (float) Math.sin(Math.PI * qf), len = q.s * sc2;
            if (len <= .05f) continue;
            light(q.x, q.y, len * 1.6f, len * 1.6f, q.a, 1f, 220f / 255f, 245f / 255f, .9f * sc2, 0f, 0f, 0f, 0f, 0f, 2f);
        }
        if (fx.flashAge >= 0f) {
            float ff = fx.flashAge / fx.flashLife, fr = (10f + 60f * fx.flashS) * easeOut(ff), fa = (1f - ff) * Math.min(1f, .9f * fx.flashS + .1f);
            light(fx.flashX, fx.flashY, fr + 1f, (fr + 1f) * .28f, 0f, 1f, 240f / 255f, 250f / 255f, fa * .8f,
                    1f, 70f / 255f, 200f / 255f, fa * .5f, .6f, 0f);
        }
        flush(lightP, px2, add);
        if (fx.flashAge >= 0f) {
            float ff = fx.flashAge / fx.flashLife, fr = (10f + 60f * fx.flashS) * easeOut(ff), fa = (1f - ff) * Math.min(1f, .9f * fx.flashS + .1f);
            ring(fx.flashX, fx.flashY, fr * 1.1f, fr * 1.1f * .28f, 0f, 3f * .6f, 0f,
                    1f, 150f / 255f, 225f / 255f, fa, 0f, 0f, 0f, 0f);
            flush(ringP, px2, normal);
        }

        // the star field behind the peeled room
        if (mode == HandoffMoves.PEEL && f.rooms && stars != null && f.p > .01f && f.p < .999f) {
            float k = HandoffMoves.ss(.34f, .76f, f.p);
            if (lyric == null || !lyric.draw(k, f.t, dt, stars.fb, stars.w, stars.h)) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, stars.fb);
                GLES30.glViewport(0, 0, stars.w, stars.h);
                GLES30.glDisable(GLES30.GL_BLEND);
                roomUniforms(starsP, f, w, h);
                GLES30.glBindVertexArray(vaoEmpty);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
                GLES30.glEnable(GLES30.GL_BLEND);
            }
        }

        // 3. the window
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (f.rooms && texA != 0) {
            GLES30.glDisable(GLES30.GL_BLEND);
            roomUniforms(roomP, f, w, h);
            if (mode == HandoffMoves.PULL) {
                GLES30.glUniform1f(loc(roomP, "uAmp"), f.amp);
                GLES30.glUniform1f(loc(roomP, "uSig"), f.sig);
                GLES30.glUniform1f(loc(roomP, "uSheet"), f.sheet);
            } else if (mode == HandoffMoves.KICK) {
                GLES30.glUniform1f(loc(roomP, "uPre"), f.pre);
            } else if (mode == HandoffMoves.VACUUM) {
                GLES30.glUniform1f(loc(roomP, "uSweep"), f.sweep);
                GLES30.glUniform1f(loc(roomP, "uColl"), f.coll);
            } else if (stars != null) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stars.tex());
                GLES30.glUniform1i(loc(roomP, "uStars"), 2);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            }
            GLES30.glBindVertexArray(vaoEmpty);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            GLES30.glEnable(GLES30.GL_BLEND);
            if (sheetP != null && f.sheet > 0f) {
                normal.run();
                roomUniforms(sheetP, f, w, h);
                GLES30.glUniform1f(loc(sheetP, "uSheet"), f.sheet);
                GLES30.glUniform1f(loc(sheetP, "uOff"), f.off);
                GLES30.glBindVertexArray(vaoEmpty);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            }
            // the ball forming in the middle of the sweep, before he picks it up
            if (mode == HandoffMoves.VACUUM && f.phase == HandoffScript.Phase.MAIN && !him.carry && him.load > .02f) {
                carried(s.mx(s.c0x()), s.c0y(), (.04f + .16f * him.load) * s.cell * 1.6f, 0f, 1f, f.t, 1f);
            }
        }
        if (whiteFlash > 0f) {
            normal.run();
            GLES30.glUseProgram(colourP.id);
            GLES30.glUniform4f(loc(colourP, "uColour"), whiteFlash, whiteFlash, whiteFlash, whiteFlash);
            GLES30.glBindVertexArray(vaoEmpty);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            whiteFlash = Math.max(0f, whiteFlash - dt / whiteLife * .7f);
        }
        GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        copy(fb.tex(), 1f, true);
        if (him.visible) {
            normal.run();
            drawHim(him, s, frame, 1f, false);
            if (him.carry && carryP != null) {
                // in his fists at his chest, his mouth showing over it (owner, 16 Nov)
                float size = s.cell * him.sc;
                float cs = (float) Math.cos(him.rot), sn = (float) Math.sin(him.rot);
                float lx = HandoffMoves.CHEST_X * size * (him.flip ? -1f : 1f), ly = HandoffMoves.CHEST_Y * size;
                float cx = him.x + lx * cs - ly * sn, cy = him.y + lx * sn + ly * cs;
                if (mode == HandoffMoves.PEEL) carried(cx, cy, .26f * size, .065f * size, 0f, f.t, him.rot + (s.mirror ? .28f : -.28f));
                else carried(cx, cy + .02f * size, .15f * size, 0f, 1f, f.t, 1f);
            }
        }
        GLES30.glBindVertexArray(0);
    }

    /** True while sparks, rings or a flash are still fading: the window is kept until they are gone. */
    boolean busy() {
        return !fx.rings.isEmpty() || !fx.sparks.isEmpty() || !fx.glints.isEmpty() || fx.flashAge >= 0f
                || whiteFlash > 0f || glow > 0f;
    }

    private void roomUniforms(@NonNull GlProgram p, @NonNull HandoffScript.Frame f, int w, int h) {
        GLES30.glUseProgram(p.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texA);
        GLES30.glUniform1i(loc(p, "uA"), 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texB != 0 ? texB : texA);
        GLES30.glUniform1i(loc(p, "uB"), 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glUniform1f(loc(p, "uHaveB"), texB != 0 ? haveB : 0f);
        GLES30.glUniform1f(loc(p, "uP"), f.p);
        GLES30.glUniform1f(loc(p, "uT"), f.t);
        GLES30.glUniform1f(loc(p, "uAspect"), h / (float) w);
        GLES30.glUniform2f(loc(p, "uGrab"), f.gx, f.gy);
        GLES30.glUniform1f(loc(p, "uMirror"), mirror ? 1f : 0f);
        GLES30.glUniform3f(loc(p, "uA1"), colA1[0], colA1[1], colA1[2]);
        GLES30.glUniform3f(loc(p, "uA2"), colA2[0], colA2[1], colA2[2]);
        GLES30.glUniform3f(loc(p, "uB1"), colB1[0], colB1[1], colB1[2]);
        GLES30.glUniform3f(loc(p, "uB2"), colB2[0], colB2[1], colB2[2]);
    }

    // ── his picture ─────────────────────────────────────────────────────────

    private int frameOf(@NonNull HandoffMoves.Pose him, @NonNull HandoffScript.Frame f) {
        HandoffSprites.Strip st = sprites.strips[him.act];
        boolean once = him.act == HandoffMoves.ACT_SLAP || him.act == HandoffMoves.ACT_SALUTE;
        float rate = st.fps * (him.act == HandoffMoves.ACT_SLAP ? 2.2f : 1.35f);
        int i = (int) Math.floor((once ? f.actT : f.t) * rate);
        if (once) i = Math.min(i, st.seq.length - 1);
        return st.seq[Math.floorMod(i, st.seq.length)];
    }

    /**
     * Joybot. Usually hung from his body centre; while he pulls, hung from his FISTS, which stay on
     * the point he holds whatever the frame (owner, 16 Nov: a seam centred on his body, with the
     * fists past it, does not sell).
     */
    private void drawHim(@NonNull HandoffMoves.Pose him, @NonNull HandoffMoves.Stage s, int frame, float alpha, boolean tint) {
        HandoffSprites.Strip st = sprites.strips[him.act];
        Bitmap pic = st.picture;
        float cw = pic.getWidth() / (float) st.cols, ch = pic.getHeight() / (float) st.rows;
        float size, left, top, ox, oy;
        if (him.act == HandoffMoves.ACT_PULL) {
            size = s.cell * HandoffMoves.PULL_CELL * him.sc;
            if (him.hand) {
                float[] fist = HandoffMoves.FISTS[frame % HandoffMoves.FISTS.length];
                left = (-.5f - fist[0]) * size; top = (-.5f - fist[1]) * size;
                ox = him.hx; oy = him.hy;
            } else {
                left = -.5f * size; top = -.5f * size;
                ox = him.x; oy = him.y;
            }
        } else {
            size = s.cell * him.sc;
            left = -size * sprites.focusCx(); top = -size * sprites.focusCy();
            ox = him.x; oy = him.y;
        }
        int col = frame % st.cols, row = frame / st.cols;
        float tw = pic.getWidth(), th = pic.getHeight();
        float u0 = (col * cw + .5f) / tw, u1 = ((col + 1) * cw - .5f) / tw;
        float v0 = (row * ch + .5f) / th, v1 = ((row + 1) * ch - .5f) / th;
        float[][] c = {{left, top, u0, v0}, {left + size, top, u1, v0}, {left + size, top + size, u1, v1}, {left, top + size, u0, v1}};
        float cr = (float) Math.cos(him.rot), sr = (float) Math.sin(him.rot), face = him.flip ? -1f : 1f;
        for (int i = 0; i < 4; i++) {
            float lx = c[i][0] * face * him.sx, ly = c[i][1] * him.sy;
            corners[i * 4] = ox + cr * lx - sr * ly;
            corners[i * 4 + 1] = oy + sr * lx + cr * ly;
            corners[i * 4 + 2] = c[i][2];
            corners[i * 4 + 3] = c[i][3];
        }
        sprite.clear();
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int k : order) sprite.put(corners, k * 4, 4);
        sprite.flip();
        GLES30.glUseProgram(spriteP.id);
        GLES30.glUniform2f(loc(spriteP, "uView"), viewW, viewH);
        GLES30.glUniform1f(loc(spriteP, "uAlpha"), alpha);
        GLES30.glUniform1f(loc(spriteP, "uTint"), tint ? 1f : 0f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texStrip[him.act]);
        GLES30.glUniform1i(loc(spriteP, "uTex"), 0);
        GLES30.glBindVertexArray(vaoSprite);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboSprite);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 6 * 16, sprite);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
    }

    /** The scroll (kind 0: {@code hx} long, {@code hy} thick) or the ball (kind 1: {@code hx} its radius). */
    private void carried(float x, float y, float hx, float hy, float kind, float t, float rot) {
        if (carryP == null || texA == 0) return;
        float halfY = kind < .5f ? hy : hx;
        put(x, y, hx, halfY, kind < .5f ? rot : 0f, colA1[0], colA1[1], colA1[2], 1f, colA2[0], colA2[1], colA2[2], 1f, kind, t, 0f, 0f);
        GLES30.glUseProgram(carryP.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texA);
        GLES30.glUniform1i(loc(carryP, "uA"), 0);
        flush(carryP, 1f / d, normal);
    }

    // ── batches (as the arrival's stage) ────────────────────────────────────

    private void ring(float x, float y, float rx, float ry, float rot, float lw, float blur,
                      float r1, float g1, float b1, float a1, float r2, float g2, float b2, float a2) {
        float m = Math.max(rx, ry) + lw + blur * 1.2f + 1f;
        put(x, y, m, m, rot, r1, g1, b1, a1, r2, g2, b2, a2, rx, ry, lw, blur);
    }

    private void light(float x, float y, float hx, float hy, float rot,
                       float r1, float g1, float b1, float a1, float r2, float g2, float b2, float a2, float stop, float kind) {
        put(x, y, hx, hy, rot, r1, g1, b1, a1, r2, g2, b2, a2, stop, kind, 0f, 0f);
    }

    private static final float[] CX = {-1f, 1f, 1f, -1f, 1f, -1f}, CY = {-1f, -1f, 1f, -1f, 1f, 1f};

    private void put(float x, float y, float hx, float hy, float rot,
                     float r1, float g1, float b1, float a1, float r2, float g2, float b2, float a2,
                     float p0, float p1, float p2, float p3) {
        if (quads >= MAX_QUADS) return;
        quads++;
        for (int i = 0; i < 6; i++) {
            batch.put(CX[i]).put(CY[i]).put(x).put(y).put(hx).put(hy).put(rot)
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

    private void copy(int tex, float gain, boolean asLight) {
        GLES30.glUseProgram(screenP.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(loc(screenP, "uTex"), 0);
        GLES30.glUniform1f(loc(screenP, "uGain"), gain);
        GLES30.glUniform1f(loc(screenP, "uLight"), asLight ? 1f : 0f);
        GLES30.glBindVertexArray(vaoEmpty);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private final Runnable normal = () -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    private final Runnable add = () -> GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);

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

    private static float easeOut(float t) { return 1f - (float) Math.pow(1f - t, 3); }
}
