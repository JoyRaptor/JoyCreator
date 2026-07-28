package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

/**
 * HARNESS STUB — not the real class.
 *
 * <p>The production {@code CaptionStyle} pulls in {@code android.graphics.Typeface} and
 * {@code org.json}, and {@code byId()} drags in {@code CaptionStyleStore} →
 * {@code SharedPreferences}. None of that is reachable in the JVM harness and none of it is
 * what {@code CaptionAnimator} actually uses: the animator touches exactly ONE member of this
 * class, the {@link Anim} enum. So the stub carries that and nothing else.</p>
 *
 * <p><b>The obvious hazard is drift</b> — a stub that quietly stops matching the real enum would
 * make the test pass while pinning the wrong thing. {@code CaptionAnimatorTest.checkStubMatchesReal}
 * closes that by parsing the real {@code CaptionStyle.java} source and asserting the constants
 * agree. If someone adds a fourth {@code Anim}, the harness fails until this stub is updated.</p>
 */
public class CaptionStyle {

    public enum Anim { POP, ZOOM, BOUNCE }

    @NonNull public final String id;
    @NonNull public Anim anim;

    public CaptionStyle(@NonNull String id, @NonNull Anim anim) {
        this.id = id;
        this.anim = anim;
    }
}
