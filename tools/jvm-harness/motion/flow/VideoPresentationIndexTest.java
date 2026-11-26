package com.fadcam.ui.faditor.motion.flow;

public final class VideoPresentationIndexTest {
    public static void main(String[] args) {
        var builder = new VideoPresentationIndex.Builder();
        for (long pts : new long[]{0, 100_000, 33_367, 67_111, 300_000}) builder.add(pts);
        var index = builder.finish();
        boolean decodeOrder = args.length > 0 && "--decode-order".equals(args[0]);
        var pair = decodeOrder ? new VideoPresentationIndex.Pair(0, 100_000)
                : index.next(0, 0, 301_000, 101_000);
        require(new VideoPresentationIndex.Pair(0, 33_367).equals(pair),
                "B frames use display neighbours");
        require(index.next(33_000, 0, 301_000, 101_000) == null, "nearby PTS is not exact");
        require(index.next(33_367, 0, 67_111, 101_000) == null, "exclusive trim fence");
        require(index.next(0, 1, 301_000, 101_000) == null, "trim excludes centre");
        require(index.next(100_000, 0, 301_000, 101_000) == null, "gap stays sharp");
        require(index.next(300_000, 0, 301_000, 101_000) == null, "end stays sharp");
        var prior = index.previous(67_111, 0, 301_000, 101_000);
        if (args.length > 0 && "--skip-donor".equals(args[0])) {
            prior = new VideoPresentationIndex.Pair(0, 67_111);
        }
        require(new VideoPresentationIndex.Pair(33_367, 67_111).equals(prior),
                "paused exposure uses the immediately preceding decoded PTS");
        require(index.previous(67_111, 33_368, 301_000, 101_000) == null,
                "paused exposure cannot borrow from before the trim");
        require(index.previous(67_112, 0, 301_000, 101_000) == null,
                "paused exposure rejects an inexact centre PTS");
        require(index.previous(67_111, 0, 301_000, 30_000) == null,
                "paused exposure rejects a non-contiguous donor");
        var visible = index.visiblePairs(70_000, 60_000, 105_000, 0, 105_000, 40_000, 32);
        require(visible.equals(java.util.List.of(new VideoPresentationIndex.Pair(67_111, 100_000),
                new VideoPresentationIndex.Pair(33_367, 67_111))), "visible pairs are nearest first and fenced");
        require(index.visiblePairs(102_000, 101_000, 105_000, 99_000, 105_000, 40_000, 32)
                .isEmpty(), "no pair when the next frame crosses the segment");
        require(index.visiblePairs(70_000, 60_000, 105_000, 0, 105_000, 40_000, 1).size() == 1,
                "prefetch pair count is bounded");
        builder.add(33_367);
        require(builder.finish() == null && index.size() == 5, "reject duplicate PTS and preserve snapshot");
        var invalid = new VideoPresentationIndex.Builder();
        invalid.add(0);
        invalid.add(1);
        require(!invalid.add(-1) && invalid.finish() == null, "reject negative PTS without partial index");
        var bounded = new VideoPresentationIndex.Builder();
        for (int i = 0; i < VideoPresentationIndex.MAX_SAMPLES; i++) require(bounded.add(i), "within capacity");
        require(!bounded.add(VideoPresentationIndex.MAX_SAMPLES) && bounded.finish() == null,
                "reject over-budget track without publishing a truncated index");
        System.out.println("VideoPresentationIndexTest PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
