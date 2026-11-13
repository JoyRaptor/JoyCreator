package cc.joycreator.textlab;

import android.opengl.GLES30;

import androidx.annotation.NonNull;

/**
 * When a phone cannot draw TextLab's looks: a check made before anything is built, and plain words for the person
 * in place of a technical error (the owner's rule: nothing breaks, no technical error is ever shown). Wherever a
 * look cannot be drawn, Studio shows the title's plain words.
 */
public final class TlTrouble {

    /** This phone's graphics lack what the looks need. */
    public static final class Unsupported extends IllegalStateException {
        Unsupported(String what) {
            super(what);
        }
    }

    private TlTrouble() {}

    /**
     * GL thread, with a context current: throws {@link Unsupported} when the looks cannot be drawn here. They draw
     * into half-float pictures (light brighter than white), which needs OpenGL ES 3 with a half-float colour buffer.
     */
    static void check() {
        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        String version = GLES30.glGetString(GLES30.GL_VERSION);
        boolean es32 = version != null && version.contains("OpenGL ES 3.2");
        boolean half = ext != null && (ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float"));
        if (!es32 && !half) throw new Unsupported("no half-float colour buffer (" + version + ")");
    }

    /** What to tell the person when the picture could not be made. */
    @NonNull
    public static String words(@NonNull Throwable e) {
        if (e instanceof Unsupported) {
            return "This phone's graphics can't draw TextLab's looks. Your titles still show, as plain words.";
        }
        if (e instanceof OutOfMemoryError) return "The phone ran short of memory for this look. Close other apps and try again.";
        return "This look couldn't be drawn on this phone. Try another look; your titles still show as plain words.";
    }
}
