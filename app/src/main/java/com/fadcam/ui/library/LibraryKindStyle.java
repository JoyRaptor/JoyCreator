package com.fadcam.ui.library;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.fadcam.R;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.faditor.Studio;

/**
 * How each kind looks and reads: its chip label, its card tag, its glyph, its colour pair and its
 * empty-state line. The model ({@link LibraryKind}) stays pure; everything Android lives here.
 *
 * <h3>Colour</h3>
 * Colour guides; greys fade. Things a ROOM makes wear that room's gradient, exactly the pairs the
 * lobby's recents use (so a recording's corner is the same red-to-pink in both places). Material
 * that came from outside, supplies and copies are zinc: they are not anyone's product, so they get
 * no hue to compete with the user's own work.
 */
final class LibraryKindStyle {

    private LibraryKindStyle() {}

    @StringRes
    static int label(@Nullable LibraryKind k) {
        if (k == null) return R.string.library_kind_all;
        switch (k) {
            case PROJECTS:      return R.string.library_kind_projects;
            case DRAWINGS:      return R.string.library_kind_drawings;
            case SPRITE_SHEETS: return R.string.library_kind_sprite_sheets;
            case AVATARS:       return R.string.library_kind_avatars;
            case RECORDINGS:    return R.string.library_kind_recordings;
            case PHOTOS:        return R.string.library_kind_photos;
            case EXPORTS:       return R.string.library_kind_exports;
            case CLIPS:         return R.string.library_kind_clips;
            case IMAGES:        return R.string.library_kind_images;
            case AUDIO:         return R.string.library_kind_audio;
            case DOCUMENTS:     return R.string.library_kind_documents;
            case SUPPLIES:      return R.string.library_kind_supplies;
            case PROJECT_MEDIA: return R.string.library_kind_project_media;
            default:            return R.string.library_kind_other;
        }
    }

    /** The short caps tag in a card's meta line. Supplies say FONT when they are one. */
    @StringRes
    static int tag(@NonNull LibraryItem it) {
        switch (it.kind) {
            case PROJECTS:      return R.string.library_tag_project;
            case DRAWINGS:      return R.string.library_tag_drawing;
            case SPRITE_SHEETS: return R.string.library_tag_sheet;
            case AVATARS:       return R.string.library_tag_avatar;
            case RECORDINGS:    return R.string.library_tag_recording;
            case PHOTOS:        return R.string.library_tag_photo;
            case EXPORTS:       return R.string.library_tag_export;
            case CLIPS:         return R.string.library_tag_clip;
            case IMAGES:        return R.string.library_tag_image;
            case AUDIO:         return R.string.library_tag_audio;
            case DOCUMENTS:     return R.string.library_tag_document;
            case SUPPLIES:
                return it.fileKind == FileKind.FONT ? R.string.library_tag_font : R.string.library_tag_supply;
            case PROJECT_MEDIA: return R.string.library_tag_project_media;
            default:            return R.string.library_tag_other;
        }
    }

    @StringRes
    static int empty(@Nullable LibraryKind k) {
        if (k == null) return R.string.library_empty_all;
        switch (k) {
            case PROJECTS:      return R.string.library_empty_projects;
            case DRAWINGS:      return R.string.library_empty_drawings;
            case SPRITE_SHEETS: return R.string.library_empty_sprite_sheets;
            case AVATARS:       return R.string.library_empty_avatars;
            case RECORDINGS:    return R.string.library_empty_recordings;
            case PHOTOS:        return R.string.library_empty_photos;
            case EXPORTS:       return R.string.library_empty_exports;
            case CLIPS:         return R.string.library_empty_clips;
            case IMAGES:        return R.string.library_empty_images;
            case AUDIO:         return R.string.library_empty_audio;
            case DOCUMENTS:     return R.string.library_empty_documents;
            case SUPPLIES:      return R.string.library_empty_supplies;
            case PROJECT_MEDIA: return R.string.library_empty_project_media;
            default:            return R.string.library_empty_other;
        }
    }

    /** A materialicons ligature: what stands in until (or instead of) a picture. */
    @NonNull
    static String glyph(@Nullable LibraryKind k) {
        if (k == null) return "apps";
        switch (k) {
            case PROJECTS:      return "movie_edit";
            case DRAWINGS:      return "brush";
            case SPRITE_SHEETS: return "grid_view";
            case AVATARS:       return "face";
            case RECORDINGS:    return "videocam";
            case PHOTOS:        return "photo_camera";
            case EXPORTS:       return "movie";
            case CLIPS:         return "smart_display";
            case IMAGES:        return "image";
            case AUDIO:         return "music_note";
            case DOCUMENTS:     return "description";
            case SUPPLIES:      return "font_download";
            case PROJECT_MEDIA: return "perm_media";
            default:            return "insert_drive_file";
        }
    }

    /** The kind's two stops: {start, end}. Rooms' products get the room; the rest is zinc. */
    @NonNull
    static int[] colours(@NonNull Context c, @Nullable LibraryKind k) {
        if (k == null) return new int[]{Studio.INK_DIM, Studio.INK_FAINT};
        switch (k) {
            case PROJECTS:
                return new int[]{Studio.ROOM_STUDIO, Studio.ROOM_STUDIO_END};
            case DRAWINGS:
                return joyBrush(c);
            case SPRITE_SHEETS:
                return new int[]{Studio.ROOM_SPRITE, Studio.ROOM_SPRITE_END};
            case AVATARS:
                return new int[]{Studio.ROOM_AVATAR, Studio.ROOM_AVATAR_DEEP};
            case RECORDINGS:
                return new int[]{Studio.ROOM_CAPTURE, Studio.ROOM_SPRITE};
            case PHOTOS:
            case EXPORTS:
                return new int[]{Studio.VIDEO, Studio.ARMED_LIGHT};
            default:
                return new int[]{Studio.INK_FAINT, Studio.INK_OFF};
        }
    }

    /** Joy Brush's own room pair (jb_tokens.xml), the same one the lobby's Joy Brush room wears. */
    @NonNull
    private static int[] joyBrush(@NonNull Context c) {
        try {
            cc.joycreator.joybrush.android.Palette p = cc.joycreator.joybrush.android.JbColors.palette(c);
            return new int[]{p.getRoomStart(), p.getRoomEnd()};
        } catch (Throwable t) {
            return new int[]{Studio.ORB_DEEP, Studio.VIDEO};
        }
    }
}
