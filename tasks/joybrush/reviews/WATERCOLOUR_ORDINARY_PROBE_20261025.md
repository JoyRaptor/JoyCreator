# Ordinary watercolor diagnostic — authored, unverified

Base coordinator a81fb7780, isolated branch vector/watercolour-ordinary-probe-20261025.
Only WatercolourOrdinaryProbe.kt and this scope note are added. No dispatch,
engine/View/shader/cache/codec or capability change. Existing WatercolourEditProbe
remains the selected-line cache diagnostic, unchanged.

The standalone run(engine,layerId,preset,input): JSONObject follows the disposable
probe convention. Caller supplies a new initialized engine, empty continuous paint
plane/history, immutable frozen watercolor preset and matching raw record, then
releases the engine. Never use the drawing engine. The probe owns only its read
FBO and temporary source units; source units are discarded even on read failure.
It never calls beginLift, so Add/Replace precede selected-line cache eligibility.

Checks: ordinary Add; LineTransform integer translation (+37,-19); tint Replace;
two Undo and two redo steps, exact raw record/seed/frame/seq and frozen-preset
checks at every history endpoint. Translation expected pixels come from transporting
the ORIGINAL premultiplied RGBA8 source across tile boundaries, not replaying the
moved record or comparing only frame metadata. Original, translated, estimated
and tinted source tiles form one persistent comparison union. A separate moved
ordinary replay discovers its actual/halo keys before Add, but supplies no pixels
to the translation reference; observed current
tiles/footprints can only enlarge it. Null and all-zero tiles are equivalent;
all bytes of transparent, old-position and halo/estimate tiles are checked.
The independent tint control and the actual installed tint must each change
RGB at at least one pixel whose alpha is nonzero in both before/after images.
The report records both changed-pixel counts; invisible/no-op tint cannot pass.

Admission is deliberately narrow: no captured/board/mask/media inputs, normal or
multiply on transparent backing, 1..2048 finite raw samples within +/-4096px,
runtime size1..300, max128 union tiles. Caller must supply paperCut=0 and grain=0:
world-fixed canvas paper/fine grain intentionally do not travel with a wash, so
they cannot be included in an exact translated-source oracle. The probe refuses
such presets rather than silently rewriting the frozen recipe. Wash texture,
pooling and gravity remain whatever the caller's frozen fixture specifies.
The engine must have exactly one layer; its stack state must be opacity1,
visible, normal blend, unlocked, without mask or clip. These checks precede any
render or Add. readTile reads the layer's own premultiplied visible-tile storage,
not the final screen compositor's layer opacity/mask/style result; the guard
still refuses non-neutral layer state to keep the disposable fixture explicit.
No claim applies to the owner's grainy preset or physical grain/paper quality:
paperCut=0 and grain=0 isolate transported wash optics only. Generic canvas
compositing and multi-layer styling are outside this diagnostic's acceptance.

Prerequisites: root reviews source; compile androidkit and matching debug APK
under the shared lock/resource guard; root later wires a separate truthful debug
case through existing disposable-engine dispatch. Then use a fresh Note9 claim,
matching APK hash/source, configured frozen fixture and GL-thread invocation.
No compilation, device execution or native pixel success is claimed yet.
Full equality can legitimately fail and must be investigated rather than relaxed.
Read-only Luna source/API review found no concrete compile or domain issue;
this is review evidence, not a successful compiler or GPU run.

This checks ordinary native replay, not cache reuse, single-line selected lift,
real pen-up fade, View selection, cold-start speed, POC visual acceptance or save/
recovery. Reopen starts with existing recordedSavedProbe configured for WC;
real View snapshot/load, force-stop and context-recovery checks remain separate.
