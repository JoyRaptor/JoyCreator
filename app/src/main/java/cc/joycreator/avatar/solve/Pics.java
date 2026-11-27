package cc.joycreator.avatar.solve;

/**
 * What the solver needs from a character's pictures: their size in the character (the working size: a picture painted finer
 * than the character still takes up its working size, AVA_FORMAT.md "fine"), a version that changes when a picture is
 * replaced (meshes are cached on it), and the alpha plane at the working size (pin distances through the drawing and the
 * outline of a turned shape read it). The phone gives these from Bitmaps; the JVM test from the file's PNGs.
 */
public interface Pics {
    /** False when the character names a picture that is not there (that drawing is not drawn, as in the pilot). */
    boolean has(String img);

    int w(String img);

    int h(String img);

    int ver(String img);

    /** Alpha 0..255 per pixel, row by row, {@code w * h} long, at the working size. */
    byte[] alpha(String img);
}
