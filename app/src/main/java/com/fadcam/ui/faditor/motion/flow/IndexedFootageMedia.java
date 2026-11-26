package com.fadcam.ui.faditor.motion.flow;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;

/** Shared source identity, analysis geometry and exact decoder for footage motion blur. */
public final class IndexedFootageMedia {
    public static final long MAX_GAP_US = 100_000L;
    private static final int MAX_ANALYSIS_SIDE = 240;

    private IndexedFootageMedia() {}

    public static FlowCache cache(Context context) throws IOException {
        return new FlowCache(new File(context.getCacheDir(), "studio-indexed-flow"));
    }

    @Nullable
    public static PreviewIndexedFlow.Prepared open(Context context, Uri uri,
            FlowAnalyzer.Cancellation cancelled) throws Exception {
        var reader = new MediaCodecLumaReader(context, uri);
        boolean transferred = false;
        try {
            var media = reader.scanSource(cancelled);
            if (media == null) return null;
            int width = media.right() - media.left();
            int height = media.bottom() - media.top();
            if (Math.floorMod(media.rotation(), 180) == 90) {
                int unrotatedWidth = width;
                width = height;
                height = unrotatedWidth;
            }
            if (width <= 0 || height <= 0) return null;
            double scale = Math.min(0.25d, (double) MAX_ANALYSIS_SIDE / Math.max(width, height));
            var geometry = new IndexedFlowQueue.Source(fingerprint(context, uri, cancelled),
                    media.rotation(), media.left(), media.top(), media.right(), media.bottom(),
                    "decoded-pyramid-v1", "quarter", Math.max(1, (int) (width * scale)),
                    Math.max(1, (int) (height * scale)), MAX_GAP_US);
            var prepared = new PreviewIndexedFlow.Prepared(media.index(), geometry,
                    ExactDecodedLuma.adapter(reader), reader);
            transferred = true;
            return prepared;
        } finally {
            if (!transferred) reader.close();
        }
    }

    // Stream the compressed source on the analysis worker. The digest survives relinks and
    // distinguishes files that reuse the same URI, dimensions and presentation timestamps.
    private static String fingerprint(Context context, Uri uri,
                                      FlowAnalyzer.Cancellation cancelled) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var bytes = context.getContentResolver().openInputStream(uri)) {
            if (bytes == null) throw new IOException("Cannot fingerprint footage");
            byte[] block = new byte[64 * 1024];
            while (true) {
                if (cancelled.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                int count = bytes.read(block);
                if (count == -1) break;
                hash.update(block, 0, count);
            }
        }
        return android.util.Base64.encodeToString(hash.digest(),
                android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }
}
