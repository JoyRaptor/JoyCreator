package cc.joycreator.avatar.studio;

import android.graphics.Canvas;

import cc.joycreator.avatar.file.CharacterDoc;

import java.util.Map;

/**
 * The seam between Studio and the character's movement: one character, posed by a take's signals at one moment, drawn
 * into its own drawing space ({@code doc.w} by {@code doc.h}, y down; the caller scales and places it).
 *
 * <p>Studio never solves a character itself. Until the pilot's solver ({@code CharacterSolver}, X2) and its GPU
 * drawing ({@code CharacterGl}, X3) land, {@link RestPoser} stands in and draws the character standing still; the port
 * installs its own {@link Factory} with {@link CharacterPosers#install} and every character in Studio's preview and
 * export moves with it, with no other change here.</p>
 */
public interface CharacterPoser {

    /** Makes the poser for one character (each keeps its own state: swinging parts, blinks in progress). */
    interface Factory {
        CharacterPoser forDoc(CharacterDoc doc, CharacterPictures pictures);
    }

    /**
     * Draws the character at item time {@code seconds}, posed by {@code signals} (the take's lanes at that frame;
     * a missing lane is not driven). Time only moves forward between calls unless {@link #rewind} was called.
     */
    void draw(Canvas c, Map<String, Object> signals, double seconds);

    /** Time went back (a scrub, a new export pass): forget everything that depends on the path taken. */
    void rewind();

    /** Frees the pictures; the poser is not used again. */
    void release();
}
