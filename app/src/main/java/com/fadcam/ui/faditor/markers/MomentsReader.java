package com.fadcam.ui.faditor.markers;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FOLD-1 auto-markers: the moments Detect (Smart Detection) found in a recording, read from the phone's own database.
 * Never throws; an unknown file, an empty database or any failure gives an empty list. Worker thread only (Room).
 */
public final class MomentsReader {
    private MomentsReader() {}

    @NonNull
    public static List<AutoMarkMapper.Moment> forUri(@NonNull Context c, @NonNull String sourceUri) {
        List<AutoMarkMapper.Moment> out = new ArrayList<>();
        try {
            ForensicsDatabase db = ForensicsDatabase.getInstance(c.getApplicationContext());
            MediaAssetEntity media = db.mediaAssetDao().findByCurrentUri(sourceUri);
            if (media == null || media.mediaUid == null) return out;
            List<AiEventEntity> events = db.aiEventDao().getByMediaUid(media.mediaUid);
            if (events == null) return out;
            for (AiEventEntity e : events) {
                out.add(new AutoMarkMapper.Moment(e.startMs, e.endMs, label(e)));
            }
        } catch (Throwable ignored) {
            // no database, a locked one, a schema from another build: no moments, never a crash
        }
        return out;
    }

    /** "Person", "Dog", "Motion": what it was, in plain words. */
    static String label(@NonNull AiEventEntity e) {
        String s = e.className != null && !e.className.trim().isEmpty() ? e.className : e.eventType;
        if (s == null || s.trim().isEmpty()) return "Moment";
        s = s.trim().replace('_', ' ');
        return s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
    }
}
