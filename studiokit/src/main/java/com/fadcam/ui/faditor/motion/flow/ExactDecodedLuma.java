package com.fadcam.ui.faditor.motion.flow;

/** Makes a decoder's actual output PTS explicit before it can enter a flow-cache key. */
public final class ExactDecodedLuma {
    private ExactDecodedLuma() {}

    public record Frame(long ptsUs, LumaImage luma) {
        public Frame {
            if (ptsUs < 0 || luma == null) throw new IllegalArgumentException("invalid decoded luma frame");
        }
    }

    public interface Reader {
        Frame read(long requestedPtsUs, int width, int height) throws Exception;
    }

    public static DecodedFlowWork.Decoder adapter(Reader reader) {
        if (reader == null) throw new IllegalArgumentException("missing decoded luma reader");
        return (ptsUs, width, height) -> {
            Frame frame = reader.read(ptsUs, width, height);
            if (frame == null) throw new IllegalStateException("decoder returned no frame for " + ptsUs);
            if (frame.ptsUs() != ptsUs) {
                throw new IllegalStateException("decoder returned PTS " + frame.ptsUs()
                        + " for exact flow key " + ptsUs);
            }
            if (frame.luma().width != width || frame.luma().height != height) {
                throw new IllegalStateException("decoder returned " + frame.luma().width + "x"
                        + frame.luma().height + " for " + width + "x" + height + " flow key");
            }
            return frame.luma();
        };
    }
}
