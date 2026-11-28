package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which camera each tracker reads (the owner, 11-27: "you could have a person acting out the character while another person
 * is doing the face ... assign different cameras on the phone to different parts"). The face and the body each choose
 * among the cameras this phone really has, in plain words (Front, Back, Back wide); one camera feeds both by default (Front,
 * one performer). Two different cameras run at once only where the phone can open that pair together; where it cannot,
 * the face's camera feeds both and the room says so in one line. Pure Java: the camera code fills in the phone's lenses and
 * the pairs it can open together, then opens what {@link #plan} says.
 */
public final class CameraPlan {
    private CameraPlan() {}

    /** The choices' keys, as saved in a character's settings. */
    public static final String FRONT = "front", BACK = "back", BACK_WIDE = "back-wide";
    /** A back lens this much shorter than the main one is a wide one (an ultra-wide is about half). */
    static final float WIDE_FOCAL = 0.8f;
    /** The main back camera zooms out below this: its wide end is a wide camera. */
    static final float WIDE_ZOOM = 0.95f;

    /** A camera the phone has, as the camera system lists it. */
    public static final class Lens {
        public final String id;
        public final boolean front, back;
        /** Its shortest focal length (mm; 0 unknown) and how far it zooms out (1 = not below its own view). */
        public final float focalMm, minZoom;

        public Lens(@NonNull String id, boolean front, boolean back, float focalMm, float minZoom) {
            this.id = id;
            this.front = front;
            this.back = back;
            this.focalMm = focalMm;
            this.minZoom = minZoom;
        }
    }

    /** One choice offered: its key, the lens it opens and the zoom to set (below 1: the main back camera's wide end). */
    public static final class Choice {
        public final String key;
        public final Lens lens;
        public final float zoom;

        Choice(@NonNull String key, @NonNull Lens lens, float zoom) {
            this.key = key;
            this.lens = lens;
            this.zoom = zoom;
        }
    }

    /** The choices this phone really has, in the order shown: Front, Back, Back wide (each only where it exists). */
    @NonNull
    public static List<Choice> choices(@NonNull Collection<Lens> lenses) {
        Lens front = null, back = null;
        for (Lens l : lenses) {
            if (l.front && front == null) front = l;
            if (l.back && back == null) back = l;                      // (the camera system's own main back camera comes first)
        }
        List<Choice> out = new ArrayList<>();
        if (front != null) out.add(new Choice(FRONT, front, 1f));
        if (back != null) {
            out.add(new Choice(BACK, back, 1f));
            Lens wide = null;
            for (Lens l : lenses)
                if (l.back && l != back && l.focalMm > 0 && back.focalMm > 0 && l.focalMm < back.focalMm * WIDE_FOCAL
                        && (wide == null || l.focalMm < wide.focalMm)) wide = l;
            if (wide != null) out.add(new Choice(BACK_WIDE, wide, 1f));
            else if (back.minZoom < WIDE_ZOOM) out.add(new Choice(BACK_WIDE, back, back.minZoom));
        }
        return out;
    }

    /** What to open: the face's camera, the body's (null without the body), and whether that is two at once. */
    public static final class Plan {
        @Nullable public final Choice face, body;
        /** Two cameras at once (the face on one, the body on the other). */
        public final boolean two;
        /** Two different cameras were asked for and this phone cannot open them together: the face's feeds both. */
        public final boolean refused;
        /** The body asked for the face's camera at another zoom: one camera at the wider of the two feeds both. */
        public final float sharedZoom;

        Plan(@Nullable Choice face, @Nullable Choice body, boolean two, boolean refused, float sharedZoom) {
            this.face = face;
            this.body = body;
            this.two = two;
            this.refused = refused;
            this.sharedZoom = sharedZoom;
        }
    }

    /**
     * The plan for these choices: {@code faceKey} and {@code bodyKey} as saved (a key this phone lacks becomes Front for the
     * face, the face's camera for the body); {@code body} false when only the face is read; {@code pairs} the sets of
     * camera ids this phone can open together.
     */
    @NonNull
    public static Plan plan(@Nullable String faceKey, @Nullable String bodyKey, boolean body, @NonNull List<Choice> choices,
                            @NonNull Collection<? extends Set<String>> pairs) {
        Choice face = find(choices, faceKey);
        if (face == null) face = find(choices, FRONT);
        if (face == null && !choices.isEmpty()) face = choices.get(0);
        if (face == null) return new Plan(null, null, false, false, 1f);
        if (!body) return new Plan(face, null, false, false, face.zoom);
        Choice b = find(choices, bodyKey);
        if (b == null) b = face;
        if (b.lens.id.equals(face.lens.id)) return new Plan(face, b, false, false, Math.min(face.zoom, b.zoom));
        for (Set<String> p : pairs) if (p.contains(face.lens.id) && p.contains(b.lens.id)) return new Plan(face, b, true, false, face.zoom);
        return new Plan(face, face, false, true, face.zoom);
    }

    /** The phone refused two cameras when they were opened (after saying it could): the face's camera feeds both. */
    @NonNull
    public static Plan oneCamera(@NonNull Plan p) {
        return new Plan(p.face, p.body == null ? null : p.face, false, p.two || p.refused, p.face == null ? 1f : p.face.zoom);
    }

    /** No pairs: a phone that opens one camera at a time. */
    @NonNull
    public static List<Set<String>> none() {
        return Collections.emptyList();
    }

    @Nullable
    private static Choice find(@NonNull List<Choice> choices, @Nullable String key) {
        if (key == null) return null;
        for (Choice c : choices) if (c.key.equals(key)) return c;
        return null;
    }
}
