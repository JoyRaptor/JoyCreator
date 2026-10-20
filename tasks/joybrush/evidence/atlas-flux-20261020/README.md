Per-pass GPU timing, window build vs atlas build, in the Note 9's Chrome (Adreno 630), 2026-10-20. One file per address-function
variant (see ../../reviews/MEDIA_ATLAS_FLUX_COST_20261020.md): base = shipped, own = every read in the draw's own slot (the floor),
fast = per-read own-tile branch, tbl = page table fetched per read, cidx = constant array index, q = dynamic index only,
tor = toroidal modulo addressing. `passtime.sh <out.json> <JB_X>` runs one; `node ptsum.js <out.json>` prints the table.
The variants live on branch lead/atlas-exp (e72ea559e); they return wrong data on purpose and are timing only.
