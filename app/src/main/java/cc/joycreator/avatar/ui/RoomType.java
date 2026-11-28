package cc.joycreator.avatar.ui;

import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.type.Type;

/**
 * The room's words in Studio's faces (the owner, 11-28: the room "dosnt look at all like the studio"): body text in Studio's
 * body face, section labels as Studio's sheets write theirs (mono, upper case, spaced, faint), headings in its display face.
 * Every room text helper calls one of these, so a change to Studio's type reaches the room.
 */
public final class RoomType {
    private RoomType() {}

    /** Running words: Studio's body face. */
    public static <T extends TextView> T body(@NonNull T t) {
        Type.body(t, Type.REGULAR);
        return t;
    }

    /** A chip or a button's words: the body face, medium. */
    public static <T extends TextView> T control(@NonNull T t) {
        Type.body(t, Type.MEDIUM);
        return t;
    }

    /** A section label, as Studio's sheets (record 06 .tg): mono, upper case, spaced. */
    public static <T extends TextView> T label(@NonNull T t) {
        Type.mono(t, Type.MEDIUM);
        t.setAllCaps(true);
        t.setLetterSpacing(0.14f);
        t.setTextSize(SheetKit.SECTION_SP);
        return t;
    }

    /** A heading: the display face, extra bold, the size of a sheet's title. */
    public static <T extends TextView> T heading(@NonNull T t) {
        Type.display(t, Type.EXTRA);
        t.setTextSize(SheetKit.TITLE_SP);
        return t;
    }
}
