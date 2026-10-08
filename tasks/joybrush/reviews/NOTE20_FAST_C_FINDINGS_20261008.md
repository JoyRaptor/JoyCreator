# Note20 input investigation: desktop baseline

Owner reports large fast-C faceting, pencil/watercolour seams, reversed pencil tilt and early memory refusal. None is fixed by this benchmark.

Reviewed free-worker benchmark integrated as 8617b762 and 644c34ce. Sole production-independent test: MediaFastCBenchTest. Actual worker JVM rerun: 8 tests, zero failures/errors/skips; Windows i7-1360P, JDK17, one worker, Test1536m/Gradle768m. No phone/GPU/raster timings.

270-degree C fixtures use SCREEN radii250/500/750px, durations60/120/240ms and inverse zoom transforms. Coarse15-segment polyline positive control has9.23px sagitta at750px. Real MediaInput/MediaSpline/DryStroke public pipeline is measured; allocation and output checksums are included. Separated/combined outputs must match.

At750px/60ms, raw8/16/32/64 points produce4628/4696/4713/4726 spline samples; maximum geometric screen errors26.3976/5.5421/1.2862/.3105px. Combined desktop medians approximately.7/.7/.6/.7ms. This supports retaining detailed sensor input; reducing raw points does not substantially reduce generated work in this fixture. Geometric error is not raster fidelity.

32-point varying-input comparison: worst pressure error.01498, tilt.004030rad, wrapped azimuth.008920rad, contact width.1939screenpx. Contact physics is shared production code on both sides, not an independent physics oracle.

Memory source audit distinguishes cold-window refusal from resident-paint ceiling. Current working reservation128MiB dry/236MiB wet; admission uses available RAM minus twice Android low-memory threshold. JB240 storage changes alone do not establish this gate is fixed. Need exact refusal and runtime gate/allocation values; no threshold changes authorized by measurements.

Next root work: establish dry contact/batch window enclosure and flush cost; measure real media render costs before runtime sample changes. Tile sampler GPU checks remain pending; no phone install from these tests.
