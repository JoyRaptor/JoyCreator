package com.fadcam.ui.faditor.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;

/**
 * The MIME type of an image asset, sniffed from its bytes (LEDGER §2e).
 *
 * <p><b>Why this exists at all.</b> media3 decides whether to hand a {@code MediaItem} to the
 * IMAGE pipeline or the VIDEO one by asking for its MIME type, and for a {@code file://} URI it
 * resolves that <em>purely from the file extension</em>
 * ({@code TransformerUtil.getImageMimeType} → {@code uriPath.lastIndexOf(".")}). Faditor copies
 * picked images into {@code files/images/} under names derived from a content-URI id — e.g.
 * {@code asset_1785180024028_image:127376} — which have <b>no extension</b>. The lookup returns
 * null, the still is routed to the video loader, and it fails: on export with "the asset loader
 * has no audio or video track to output" (which killed the whole export), and in the preview by
 * never displaying.</p>
 *
 * <p><b>Why it is shared.</b> The export and the playback engine each build their own image
 * {@code MediaItem}, and fixing only one leaves the same still working in one surface and broken
 * in the other — the preview/export divergence this project treats as a defect class in itself.
 * One authority, two callers.</p>
 *
 * <p>Sniffed rather than assumed because the declared type decides ROUTING only —
 * {@code BitmapFactory} re-detects the real format when decoding — so accuracy costs one short
 * read, and inaccuracy costs a confusing log line much later.</p>
 */
public final class ImageMime {

    private ImageMime() {}

    /**
     * @return an {@code image/*} MIME type for {@code uri}, defaulting to JPEG when the bytes
     *         cannot be read or are unrecognised. Never null, because null is precisely the value
     *         that causes the misrouting this class exists to prevent.
     */
    @NonNull
    public static String of(@NonNull Context context, @Nullable Uri uri) {
        if (uri == null) return MimeTypes.IMAGE_JPEG;
        byte[] h = new byte[12];
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return MimeTypes.IMAGE_JPEG;
            // readNBytes-style loop: InputStream.read is NOT obliged to fill the buffer, and a
            // document-provider stream legitimately returns a few bytes on the first call. The
            // first version of this treated a short read as failure and silently mis-sniffed
            // valid PNGs (adversarial review 2026-08-03).
            int got = 0;
            while (got < h.length) {
                int n = in.read(h, got, h.length - got);
                if (n < 0) break;
                got += n;
            }
            if (got < 12) return MimeTypes.IMAGE_JPEG;
        } catch (Exception e) {
            return MimeTypes.IMAGE_JPEG;
        }
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return MimeTypes.IMAGE_PNG;
        }
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return MimeTypes.IMAGE_JPEG;
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "image/gif";
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return MimeTypes.IMAGE_WEBP;
        }
        // HEIC/AVIF share the ISO-BMFF 'ftyp' box header.
        if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return MimeTypes.IMAGE_HEIF;
        return MimeTypes.IMAGE_JPEG;
    }
}
