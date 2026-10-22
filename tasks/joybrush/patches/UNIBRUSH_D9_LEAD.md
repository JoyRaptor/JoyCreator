# D9 Unibrush host integration — 2026-10-21

Owned source and disposable combined adapters are verified; phone acceptance and activation remain pending.
D1–D8 verified engine slices remain unchanged. No canonical engine/View/lines/Activity edits.

## Exact integration order

1. Existing D4/D5/D6 engine/View/frozen-resource proposals, corrected D5 held tests and D6 cold-plan constructor.
2. D7 LineShaper forwarding/test proposal and live admission gate. Actual physical publication still needs the Lead transaction; native endStroke is not a substitute.
3. D8_VIEW, D8_FILL and D8_FILL_TESTS patches.
4. D9_PREVIEW_VIEW_HELD then D9_PREVIEW_RETIREMENT_VIEW_HELD.
5. D9_LEGACY_CONTACT_HELD, D9_APP_FIXTURES_HELD and D9_PALETTE_VIEW_HELD. These preserve historical first-dab colour/native taper behavior and add the secondary palette capability.
6. D9_HOST_ACTIVITY_DRAWER_HELD adds Activity and owned Drawer hooks. The canonical Drawer already has Kind.UNIBRUSH naming; apply the small supplied hooks without replacing the whole file.

7. Optional debug-only D10_BENCH_HELD adds explicit nominal size and scoped refusal reporting;
   its contact timing never certifies final scene publication. D10 half-resolution is still a proposal.

The held engine/View/test sets compile and pass tests in the disposable combined shadow.
This is JVM/adapter proof; actual GL scene publication and phone acceptance remain pending.
Never resolve a merge conflict. Keep UnibrushActivation.SHIPPING_ENABLED=false until actual
recorded scene publication, archive/cold-load assets, cancellation/retirement and phone gates pass.
The owned host compiles through UnibrushCanvasPort; only the held View implements that capability.
An absent port must refuse resource-aware work, and must not silently discard its context.

## Owned behavior

Eleven independent stamp modes: Soft, Hard, Calligraphy, Taper, Fade, Texture, Round, Chisel,
Living, Dry, Sparkle. Old presets are unchanged. Shelf shows the family name once; names are mode words.
Quick/Tip/All expose four shape handles, A/B, facing, rounding/aspect/morph, falloff points and Bezier,
fade, pressure/tilt/rotation, colour, ordered PNG/dual tips, packaged grains, ends and actual stabilizer.
The actual engine preview has pressure/tilt input; it never draws into main history.
Sheet controls retain a valid model when host admission refuses; stale picker callbacks are ignored.

New brush is append-only Save As with a fresh UUID. Complete JSON/PNG closure and budgets precede
writes. Files and directories are synced; publishing requires an atomic move on cooperative private
storage, under a JVM monitor/file lock. A publication followed by durability failure reports publishedId
so callers cannot retry into a duplicate. Attribution is retained. Existing modes are never overwritten.
Android directory opens use public SDK flags followed by fstat of that exact descriptor before fsync.
The store maintains a bounded metadata catalog and one selected frozen PNG owner.

Image selection uses ACTION_OPEN_DOCUMENT, bounded streams, complete primary+dual framing/budgets,
sequential Bitmap verification/recycle and safe generated logical paths. Three packaged grains are
actually available; arbitrary imported grain PNGs and a new archive importer Activity are not claimed.
Existing ABR/Procreate/Krita materialization APIs remain separate.

Row and sheet resource previews share one active encoded-resource lease and at most32 queued metadata
jobs. SHA256 first-use runs on IO. Exact recipe/PNG-content/actual-settings/pen-reading snapshots enter
the cache and renderer unchanged. Cancelled GL work holds its lease until a separate terminal callback;
legacy cancelled bitmap delivery remains suppressed. UI and GL resolvers have separate bounded owners.
Production SerialResourcePreviewLane has fake IO/UI/terminal tests, and the app cache has content/key/
resource ownership tests. These tests pass; phone heap and sustained preview latency remain unmeasured.

Startup preserves saved tool IDs while user-mode metadata/PNG resources load. Global stabilizer and secondary colour
preferences are stored separately. The existing Studio colour picker supplies live previews;
confirmed current choices persist, while cancellation and stale selection restore temporary ink. Selected recipe/resources publish together only after admission.
Temporary edits are intentionally unsaved until New brush; switching does not overwrite presets.

## Remaining acceptance

Real phone layout,
continuous editing/preview latency and heap, all eleven modes as raster and editable lines with
move/rotate/recolour/rebrush, Lead merge and owner comparison against Photoshop/Procreate.
D10 half-resolution ownership and actual Note9 bench remain separate uncompleted work.
