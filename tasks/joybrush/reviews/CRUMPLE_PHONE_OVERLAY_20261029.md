# Crumpled paper: resource-only resolution comparison

Staged bytes: approved-surfaces-20261029/crumple-phone-overlay under the existing
local visualization artifact root. Nine runtime files, exactly7,662,021bytes.
See crumple_phone_overlay_manifest_20261029.json for source paths, SHA256, modes,
dimensions and sizes. No diagnostics copied. Staging script preserves source bytes.

## Selective integration by lead

1. Use the latest lead integration worktree, preserving its newer oil/Joybot work.
2. Copy ONLY the nine manifest files into joybrush/assets/paper/, refusing any
   existing filename whose SHA differs. Do not copy a historical catalogue.
3. Read the CURRENT catalogue; append only the three surfaces and three looks
   from crumple_phone_catalogue_additions_20261029.json. Refuse duplicate ids or
   conflicting filenames. Preserve every existing entry, id, default and setting.
4. Labels distinguish Crumpled paper2048px,1024px,512px (actual labels have spaces).
   Existing eval ids remain distinct; no default look/surface change is requested.
5. Review exact catalogue diff before root-controlled build/device workflow.

## Production compatibility gates

Run the production PaperCatalogues parser and problems() on the merged CURRENT
catalogue, then verify all file references, dimensions and per-file manifest SHA.
Master height is L PNG packed=false: SurfaceMaps.expand must pack its height once.
Profiles are RGBA PNG packed=true: SurfaceMaps.expand must return all four channels
unchanged. Decode through the actual Android BitmapFactory options/getPixels path,
assert unpremultiplied bytes including independently filtered alpha=E[h-squared].
Look images are RGB JPEG; fluid images are RGBA PNG. Verify paired pitch and surface
mapping after production parsing, not only Python JSON inspection.

On controlled Android/GL test, compare uploaded base channels and mip variance,
rotation disabled, stable alignment with matching look, save/reopen and export.
Compare all three using identical brush strokes/pressure/zoom: pencil, chalk,
watercolour and oil plus every other available brush family. Inspect seams at1x
and minified zoom and repeat transitions; these artifacts have not passed that
visual/feel matrix. Owner acceptance remains open.

## Memory and evidence limits

Combined logical RGBA8+mips for the three variants:60,555,252bytes (57.75MiB).
Decoded base RGBA total:45,416,448bytes (43.3125MiB), a model, not measured peak.
The existing GrainTextures cache keeps visited textures: combined cost matters
if all three are visited. Both estimates EXCLUDE baseline/current app textures,
driver allocation/padding, Bitmap/IntArray/byte/direct upload buffers, lazy CPU
mips and other app resources. Measure actual process/GPU behavior on Note9.
Encoded overlay bytes are not APK-compressed delta or runtime residency.

No catalogue, package/default, engine/cache/loader, APK or phone change was made
by this staging lane. Remaining master sequence is still stopped at7/45.
