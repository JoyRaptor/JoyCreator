package com.fadcam.vislab.gl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

import com.fadcam.vislab.model.CompiledScene;
import com.fadcam.vislab.model.KnobSet;
import com.fadcam.vislab.model.VisType;

/**
 * The layers that remember: particles, the slime web, reaction-diffusion. Each one steps on a
 * fixed 1/60 s tick, whatever the frame rate, so a 30 fps export and a 60 fps preview grow the
 * same thing. Particle seeds come from a global emission serial, not the slot, so the same
 * moment of a song always births the same particles.
 */
final class LivingLayers {

    private LivingLayers() {}

    static final float TICK = 1f / 60f;

    /** State a layer keeps between frames, freed when the look changes. */
    abstract static class State {
        abstract void free();
    }

    // ------------------------------------------------------------------ particles

    static final class Particles extends State {
        int n;
        GlTarget cur, next;
        int cursor;
        long serial;
        double acc;

        void ensure(int want, @NonNull GlTarget.Format fmt) {
            if (cur != null && n == want) return;
            free();
            int w = 256, h = (want + w - 1) / w;
            cur = GlTarget.create(w, h, fmt, 2, false);
            next = GlTarget.create(w, h, fmt, 2, false);
            // Everyone starts dead (life 2); emission brings them in.
            for (GlTarget t : new GlTarget[]{cur, next}) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fb);
                GLES30.glViewport(0, 0, t.w, t.h);
                GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, new float[]{0, 0, 0, 2}, 0);
                GLES30.glClearBufferfv(GLES30.GL_COLOR, 1, new float[]{0, 0, 0, 0}, 0);
            }
            n = want;
            cursor = 0;
            serial = 0;
            acc = 0;
        }

        void step(@NonNull VisRenderer r, @NonNull CompiledScene.Layer L, float punch) {
            VisType t = L.type;
            KnobSet k = L.knobs;
            int want = Math.max(256, Math.min(131072, Math.round(k.value(t, "count", 20000))));
            ensure(want, r.stateFormat);
            double life = Math.max(0.05, k.value(t, "life", 2.5f));
            acc += k.value(t, "rate", 1f) * n / life * TICK;
            int count = (int) Math.floor(acc);
            acc -= count;
            if (punch > 0) count += (int) Math.floor(k.value(t, "burst", 0.05f) * n * punch);
            count = Math.max(0, Math.min(count, n));
            r.bindTarget(next);
            GLES30.glDisable(GLES30.GL_BLEND);
            GlProgram p = r.program("fullscreen.vert", t.sim);
            int unit = r.useLayer(p, L, 0f, 1f, null);
            r.tex(p, "uState0", cur.texs[0], unit++);
            r.tex(p, "uState1", cur.texs[1], unit);
            GLES30.glUniform4f(p.u("uEmit"), cursor, count, n, (float) (serial % 1048576));
            r.drawFullscreen();
            cursor = (cursor + count) % n;
            serial += count;
            GlTarget s = cur;
            cur = next;
            next = s;
        }

        @Override
        void free() {
            GlTarget.free(cur);
            GlTarget.free(next);
            cur = next = null;
            n = 0;
        }
    }

    // ------------------------------------------------------------------ slime

    static final class Slime extends State {
        int count;
        GlTarget a, b, trailA, trailB;
        int tick;

        void step(@NonNull VisRenderer r, @NonNull CompiledScene.Layer L) {
            VisType t = L.type;
            KnobSet k = L.knobs;
            int want = Math.max(1000, Math.min(300000, Math.round(k.value(t, "count", 60000))));
            float detail = Math.max(0.2f, Math.min(1f, k.value(t, "detail", 0.5f)));
            int tw = Math.max(32, Math.round(r.width() * detail)), th = Math.max(32, Math.round(r.height() * detail));
            boolean init = false;
            if (a == null || count != want) {
                GlTarget.free(a);
                GlTarget.free(b);
                int w = 512, h = (want + w - 1) / w;
                a = GlTarget.create(w, h, r.stateFormat, 1, false);
                b = GlTarget.create(w, h, r.stateFormat, 1, false);
                count = want;
                init = true;
            }
            if (trailA == null || trailA.w != tw || trailA.h != th) {
                GlTarget.free(trailA);
                GlTarget.free(trailB);
                trailA = GlTarget.create(tw, th, r.hdrFormat, 1, true);
                trailB = GlTarget.create(tw, th, r.hdrFormat, 1, true);
                r.clear(trailA);
                r.clear(trailB);
            }
            tick++;
            // a) agents sense, turn, move
            r.bindTarget(b);
            GLES30.glDisable(GLES30.GL_BLEND);
            GlProgram ps = r.program("fullscreen.vert", t.step);
            int unit = r.useLayer(ps, L, 0f, 1f, null);
            r.tex(ps, "uState", a.tex(), unit++);
            r.tex(ps, "uTrail", trailA.tex(), unit);
            GLES30.glUniform4f(ps.u("uSim"), count, init ? 1f : 0f, tick % 9973, 0f);
            r.drawFullscreen();
            GlTarget s = a; a = b; b = s;
            // b) every agent leaves a drop of scent
            r.bindTarget(trailA);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
            GlProgram pd = r.program(t.depositVert, t.depositFrag);
            unit = r.useLayer(pd, L, 0f, 1f, null);
            r.tex(pd, "uState", a.tex(), unit);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count);
            GLES30.glDisable(GLES30.GL_BLEND);
            // c) the trail spreads and fades
            r.bindTarget(trailB);
            GlProgram pf = r.program("fullscreen.vert", t.diffuse);
            unit = r.useLayer(pf, L, 0f, 1f, null);
            r.tex(pf, "uTrail", trailA.tex(), unit);
            r.drawFullscreen();
            s = trailA; trailA = trailB; trailB = s;
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        }

        @Override
        void free() {
            GlTarget.free(a);
            GlTarget.free(b);
            GlTarget.free(trailA);
            GlTarget.free(trailB);
            a = b = trailA = trailB = null;
        }
    }

    // ------------------------------------------------------------------ reaction-diffusion

    static final class Rd extends State {
        GlTarget a, b;
        int kicks;

        void step(@NonNull VisRenderer r, @NonNull CompiledScene.Layer L, float punch) {
            VisType t = L.type;
            KnobSet k = L.knobs;
            float detail = Math.max(0.2f, Math.min(1f, k.value(t, "detail", 0.4f)));
            int rw = Math.max(32, Math.round(r.width() * detail)), rh = Math.max(32, Math.round(r.height() * detail));
            boolean init = false;
            if (a == null || a.w != rw || a.h != rh) {
                GlTarget.free(a);
                GlTarget.free(b);
                a = GlTarget.create(rw, rh, r.stateFormat, 1, true);
                b = GlTarget.create(rw, rh, r.stateFormat, 1, true);
                init = true;
            }
            if (punch > 0) kicks++;
            int iters = Math.max(1, Math.min(24, Math.round(k.value(t, "iterations", 10))));
            GLES30.glDisable(GLES30.GL_BLEND);
            GlProgram p = r.program("fullscreen.vert", t.step);
            for (int i = 0; i < (init ? 1 : iters); i++) {
                r.bindTarget(b);
                int unit = r.useLayer(p, L, 0f, 1f, null);
                r.tex(p, "uState", a.tex(), unit);
                GLES30.glUniform4f(p.u("uSim"), 0f, init ? 1f : 0f, kicks, 0f);
                r.drawFullscreen();
                GlTarget s = a; a = b; b = s;
            }
        }

        @Override
        void free() {
            GlTarget.free(a);
            GlTarget.free(b);
            a = b = null;
        }
    }
}
