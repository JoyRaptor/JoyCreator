package com.fadcam.ui.faditor.audio;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.AudioClip;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Did anything the audio players were built from change?" -- used by undo and redo so they only
 * tear down and rebuild the audio players when the step really touched sound (undoing a text
 * colour used to rebuild them all).
 *
 * <p>Built by reflection over every field of each {@link AudioClip}, so a field added to the
 * model later is covered without anyone remembering to list it here. Only fields that can never
 * change what is heard are left out (the stored waveform picture, transcripts, captions, the
 * label). Anything the walker does not understand is written with its identity, which makes the
 * fingerprints differ: an unknown always means "rebuild", never "skip".</p>
 *
 * <p>The players also hold the {@link AudioClip} OBJECT they were built for, so the fingerprint
 * carries the objects' identities too: a snapshot restore (new objects) never matches.</p>
 */
public final class AudioStateFingerprint {

    private static final int MAX_DEPTH = 4;

    private final List<AudioClip> clips;
    private final String text;

    private AudioStateFingerprint(List<AudioClip> clips, String text) {
        this.clips = clips;
        this.text = text;
    }

    @NonNull
    public static AudioStateFingerprint of(@NonNull List<AudioClip> audioClips) {
        List<AudioClip> copy = new ArrayList<>(audioClips);
        StringBuilder sb = new StringBuilder();
        for (AudioClip ac : copy) {
            sb.append('{');
            write(sb, ac, 0, new IdentityHashMap<>());
            sb.append('}');
        }
        return new AudioStateFingerprint(copy, sb.toString());
    }

    /** True when the same clip objects are there, in the same order, with the same sound state. */
    public boolean sameAs(@NonNull AudioStateFingerprint other) {
        if (clips.size() != other.clips.size()) return false;
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i) != other.clips.get(i)) return false;
        }
        return text.equals(other.text);
    }

    private static boolean skipField(@NonNull String name) {
        return name.equals("waveform") || name.equals("transcripts") || name.equals("label")
                || name.startsWith("caption") || name.equals("activeTranscriptIndex");
    }

    private static void write(StringBuilder sb, Object o, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (o == null) { sb.append("null"); return; }
        Class<?> c = o.getClass();
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean
                || o instanceof Character || o instanceof Enum || o instanceof android.net.Uri) {
            sb.append(o.toString());
            return;
        }
        if (c.isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            sb.append('[');
            for (int i = 0; i < n; i++) {
                write(sb, java.lang.reflect.Array.get(o, i), depth + 1, seen);
                sb.append(',');
            }
            sb.append(']');
            return;
        }
        if (o instanceof Collection) {
            sb.append('[');
            for (Object e : (Collection<?>) o) { write(sb, e, depth + 1, seen); sb.append(','); }
            sb.append(']');
            return;
        }
        if (o instanceof Map) {
            sb.append('{');
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                write(sb, e.getKey(), depth + 1, seen);
                sb.append('=');
                write(sb, e.getValue(), depth + 1, seen);
                sb.append(',');
            }
            sb.append('}');
            return;
        }
        if (depth >= MAX_DEPTH || seen.put(o, Boolean.TRUE) != null) {
            sb.append(c.getName()).append('@').append(System.identityHashCode(o)); // unknown: never equal
            return;
        }
        sb.append(c.getSimpleName()).append('(');
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || skipField(f.getName())) continue;
                sb.append(f.getName()).append(':');
                try {
                    f.setAccessible(true);
                    write(sb, f.get(o), depth + 1, seen);
                } catch (Exception e) {
                    sb.append("?@").append(System.nanoTime()); // cannot read: never equal
                }
                sb.append(';');
            }
        }
        sb.append(')');
        seen.remove(o);
    }
}
