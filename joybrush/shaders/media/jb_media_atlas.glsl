// jb_media_atlas.glsl — Media Atlas v2 addressing (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md).
// GLSL ES 3.00 functions only: NO #version, NO main. Include AFTER jb_media_common.glsl.
//
// Without JB_ATLAS (the PC lab, the window engine) every helper here is exactly the expression it replaces, so those
// builds are bit for bit what they were. With JB_ATLAS (the app defines it) the layer-shaped textures are an ATLAS of
// square slots (256² at full resolution, 128² for the half-resolution bead field). A page table maps a layer tile to a
// slot; a tile that is not resident reads as zero, which is what its stores hold, because the driver keeps every tile
// within a pass's read radius resident (the residency rule).
//
// Cells are LAYER px: layerPx = (docPx − u_layerOrigin) · u_layerScale, unbounded and possibly negative. A pass draws one
// instance per slot it writes; the instance hands the fragment its slot (v_slot).

#ifdef JB_ATLAS
// highp throughout: u_atlasShift is also declared by the vertex stage, and a uniform shared by two stages must agree
// on precision (a fragment shader's default int is mediump).
uniform highp isampler2D u_atlasTable;   // .r = slot index (column + row · slots per row); −1 = not resident
uniform highp ivec4 u_atlasGrid;         // the table's first tile (x, y) and its size in tiles (w, h)
uniform highp int u_atlasSlotsX;         // slots per atlas row
uniform highp int u_atlasShift;          // log2 of THIS pass's target slot side: 8 (256), or 7 for the half-res bead
uniform highp ivec4 u_atlasWrap;         // a Tile board in full-res layer px: origin (x, y), size (w, h); w == 0 = no wrap
uniform highp ivec4 u_atlasWalls;        // .x = 1 in the water's passes: tiles taken out of the water are walls (B3, D2)
flat in highp ivec4 v_slot;              // this fragment's slot origin (atlas texels, xy) and its tile (zw); a pass that
                                         // is not per-slot (brush cells, the look) gets a tile no layer cell can have
#ifdef JB_ATLAS_NBR
// JB_ATLAS_NBR (with JB_ATLAS): the pass is drawn tile by tile, each draw handed the slots of its tile and the 8 around
// it, which is every tile its reads reach (every reach is under a tile: look 56, wet 80, paste 12 px). A read is then a
// few integer ops: no page table, no branch. Through the table, every read inlined the whole lookup: the look took 194 s to
// compile on a PC and ~100 s on the Note 9 and drew 50x slower than the window's there, long enough to hang the GPU; the
// water's flux 7.6 s to compile and its frames ran 5-15x the window's (2026-10-18). The host fills these from the very
// table (MediaAtlasGl.neighbours), so the same texels are read: the same result, bit for bit. No Tile-board wrap here.
// JB_ATLAS_NBR5: 5x5 instead (the paste brush: its target is the brush's cells, not a slot, so the neighbourhood is
// centred on the tile under the step and must hold the whole step's footprint, up to about 2 tiles from that tile).
#ifdef JB_ATLAS_NBR5
const highp int JB_NBR_R = 2;
#else
const highp int JB_NBR_R = 1;
#endif
const highp int JB_NBR_N = 2 * JB_NBR_R + 1;
uniform highp ivec2 u_nbrTile;           // the tile this draw covers (v_slot.zw in a per-slot pass)
uniform highp ivec2 u_nbrSlot[JB_NBR_N * JB_NBR_N];   // full-resolution slot origins (atlas texels) of tiles u_nbrTile +
                                         // (-R..R, -R..R), row by row; -1 = not here, JB_NBR_WALL = a wall for this (water) pass
const highp int JB_NBR_WALL = -2;
#endif
#endif

const int JB_SLOT_SHIFT = 8;             // full-resolution slot side 256 = Tiles.SIZE

// This fragment's cell (layer px at the pass's resolution).
highp ivec2 jb_cell() {
#ifdef JB_ATLAS
    return ivec2(gl_FragCoord.xy) - v_slot.xy + (v_slot.zw << u_atlasShift);
#else
    return ivec2(gl_FragCoord.xy);
#endif
}

// gl_FragCoord.xy in layer px: the cell's centre (what gl_FragCoord.xy is in the window build).
vec2 jb_fragPx() {
#ifdef JB_ATLAS
    return vec2(jb_cell()) + 0.5;
#else
    return gl_FragCoord.xy;
#endif
}

#ifdef JB_ATLAS
// floor(a / b) for b > 0, exact for any int a (GLSL's % and / are undefined or truncating for negatives).
highp int jb_floorDiv(highp int a, highp int b) { int q = a / b; if (q * b > a) q -= 1; return q; }

// Media Atlas v2 B3 (the Lead's D2): a tile taken out of the water (a drip's dried tail) is marked in the page table:
// resident = its slot + JB_WALL_BIT, not resident = JB_WALL_GONE. Every pass but the water's reads it as what it is; the
// water's passes (u_atlasWalls.x = 1) see a WALL there, exactly as the window build sees its own edge: reads clamp to the
// reader's own tile (zero gradient: the water beside it keeps its own height, no dry front, no dark edge), nothing flows in
// or out (jb_inside, jb_wall).
const highp int JB_WALL_BIT = 16777216;   // 1 << 24, above any slot index
const highp int JB_WALL_GONE = -2;

// Layer cell q clamped into the reading fragment's own tile, at slot side (1 << shift).
highp ivec2 jb_ownCell(highp ivec2 q, highp int shift, out bool ok) {
    highp ivec2 own = v_slot.zw;
    highp ivec2 lo = own << shift;
    highp ivec2 cl = clamp(q, lo, lo + (1 << shift) - 1);
    highp ivec2 g = own - u_atlasGrid.xy;
    if (g.x < 0 || g.y < 0 || g.x >= u_atlasGrid.z || g.y >= u_atlasGrid.w) { ok = false; return ivec2(0); }
    highp int slot = texelFetch(u_atlasTable, g, 0).r;
    if (slot >= JB_WALL_BIT) slot -= JB_WALL_BIT;
    if (slot < 0) { ok = false; return ivec2(0); }
    ok = true;
    return (ivec2(slot - (slot / u_atlasSlotsX) * u_atlasSlotsX, slot / u_atlasSlotsX) << shift) + (cl - lo);
}

// Layer cell q at slot side (1 << shift) → its atlas texel. ok = false where the tile is not resident.
highp ivec2 jb_atS(highp ivec2 q, highp int shift, out bool ok) {
#ifdef JB_ATLAS_NBR
    // Branch-free: the index is clamped into the 3x3 and ok says whether it had to be. A wall reads the reader's own cell
    // nearest q (jb_ownCell). Half-resolution fields (shift 7) sit at half the full-resolution origins.
    highp int down = JB_SLOT_SHIFT - shift;
    highp ivec2 nt = q >> shift;
    highp ivec2 d = nt - u_nbrTile + JB_NBR_R;
    highp ivec2 dc = clamp(d, 0, 2 * JB_NBR_R);
    highp ivec2 o = u_nbrSlot[dc.y * JB_NBR_N + dc.x];
    bool wall = o.x == JB_NBR_WALL;
    highp ivec2 lo = u_nbrTile << shift;
    highp ivec2 a = wall ? (u_nbrSlot[JB_NBR_R * JB_NBR_N + JB_NBR_R] >> down) + clamp(q, lo, lo + (1 << shift) - 1) - lo : (o >> down) + (q - (nt << shift));
    ok = d == dc && (wall || o.x >= 0);
    return ok ? a : ivec2(0);
#else
    if (u_atlasWrap.z > 0) {
        // A Tile board repeats in pixel space; boards need not be tile-aligned. The half-res field wraps at half the
        // size, which the driver only allows for even board rectangles.
        highp int d = JB_SLOT_SHIFT - shift;
        highp ivec2 o = u_atlasWrap.xy >> d, n = u_atlasWrap.zw >> d;
        highp ivec2 r = q - o;
        q = o + r - n * ivec2(jb_floorDiv(r.x, n.x), jb_floorDiv(r.y, n.y));
    }
    highp ivec2 tile = q >> shift;                 // arithmetic shift: floor, also for negative cells
    highp ivec2 local = q - (tile << shift);
    if (shift == u_atlasShift && tile == v_slot.zw) { ok = true; return v_slot.xy + local; }   // own tile: the common case
    highp ivec2 g = tile - u_atlasGrid.xy;
    if (g.x < 0 || g.y < 0 || g.x >= u_atlasGrid.z || g.y >= u_atlasGrid.w) { ok = false; return ivec2(0); }
    highp int slot = texelFetch(u_atlasTable, g, 0).r;
    if (slot == JB_WALL_GONE || slot >= JB_WALL_BIT) {
        if (u_atlasWalls.x != 0) return jb_ownCell(q, shift, ok);
        if (slot < 0) { ok = false; return ivec2(0); }
        slot -= JB_WALL_BIT;
    }
    if (slot < 0) { ok = false; return ivec2(0); }
    ok = true;
    return (ivec2(slot - (slot / u_atlasSlotsX) * u_atlasSlotsX, slot / u_atlasSlotsX) << shift) + local;
#endif
}
#endif

// True where layer cell q (full resolution) is a wall for the water (see JB_WALL_BIT); never in the window build.
bool jb_wall(highp ivec2 q) {
#if defined(JB_ATLAS) && defined(JB_ATLAS_NBR)
    highp ivec2 nt = q >> JB_SLOT_SHIFT;   // walls are marked only while the water's passes draw (MediaAtlasGl.neighbours)
    highp ivec2 d = nt - u_nbrTile + JB_NBR_R;
    highp ivec2 dc = clamp(d, 0, 2 * JB_NBR_R);
    return d == dc && nt != u_nbrTile && u_nbrSlot[dc.y * JB_NBR_N + dc.x].x == JB_NBR_WALL;
#elif defined(JB_ATLAS)
    if (u_atlasWalls.x == 0) return false;
    highp ivec2 tile = q >> JB_SLOT_SHIFT;
    if (tile == v_slot.zw) return false;
    highp ivec2 g = tile - u_atlasGrid.xy;
    if (g.x < 0 || g.y < 0 || g.x >= u_atlasGrid.z || g.y >= u_atlasGrid.w) return false;
    highp int slot = texelFetch(u_atlasTable, g, 0).r;
    return slot == JB_WALL_GONE || slot >= JB_WALL_BIT;
#else
    return false;
#endif
}

// texelFetch(t, q, 0) of a layer-shaped texture at layer cell q (half-res fields: shift 7).
vec4 jb_fetchS(sampler2D t, highp ivec2 q, highp int shift) {
#ifdef JB_ATLAS
    bool ok;
    highp ivec2 a = jb_atS(q, shift, ok);
#ifdef JB_ATLAS_NBR
    vec4 v = texelFetch(t, a, 0);   // a is (0, 0) where !ok: a real texel, so the read needs no branch around it
    return ok ? v : vec4(0.0);
#else
    return ok ? texelFetch(t, a, 0) : vec4(0.0);
#endif
#else
    return texelFetch(t, q, 0);
#endif
}
vec4 jb_fetch(sampler2D t, highp ivec2 q) { return jb_fetchS(t, q, JB_SLOT_SHIFT); }

// The window build clamps neighbour cells to the window and skips cells outside it; the atlas has no edge (a cell with
// nothing resident reads as zero), so both become no-ops there.
highp ivec2 jb_clampCell(highp ivec2 q, highp ivec2 size) {
#ifdef JB_ATLAS
    return q;
#else
    return clamp(q, ivec2(0), size - 1);
#endif
}
bool jb_inside(highp ivec2 q, highp ivec2 size) {
#ifdef JB_ATLAS
    return !jb_wall(q);
#else
    return q.x >= 0 && q.y >= 0 && q.x < size.x && q.y < size.y;
#endif
}

// jb_bilinear (jb_media_common.glsl) of a layer-shaped texture at layer px: the same four taps and weights, addressed.
// [shift] 7 reads a half-resolution field (px in its own cells), the same way.
vec4 jb_bilinearLS(sampler2D t, vec2 px, highp int shift) {
#ifdef JB_ATLAS
    vec2 p = px - 0.5;
    highp ivec2 i = ivec2(floor(p));
    vec2 f = p - vec2(i);
    vec4 v00 = jb_fetchS(t, i, shift), v10 = jb_fetchS(t, i + ivec2(1, 0), shift);
    vec4 v01 = jb_fetchS(t, i + ivec2(0, 1), shift), v11 = jb_fetchS(t, i + ivec2(1, 1), shift);
    return mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
#else
    return jb_bilinear(t, px);
#endif
}
vec4 jb_bilinearL(sampler2D t, vec2 px) { return jb_bilinearLS(t, px, JB_SLOT_SHIFT); }

// The cell under layer px [lp] for a single-texel read. The window build clamps into the window (its edge texel stands
// for everything beyond); the atlas floors (no edge: what is not resident reads as zero).
highp ivec2 jb_cellAt(vec2 lp, vec2 size) {
#ifdef JB_ATLAS
    return ivec2(floor(lp));
#else
    return ivec2(clamp(lp, vec2(0.0), size - 1.0));
#endif
}
// Layer px [lp] is on the layer's texture (the look's bounds test). The atlas has no edge.
bool jb_insidePx(vec2 lp, vec2 size) {
#ifdef JB_ATLAS
    return true;
#else
    return !(lp.x < 0.0 || lp.y < 0.0 || lp.x > size.x || lp.y > size.y);
#endif
}
// A dab pass draws each dab once per slot it touches: a fragment of another slot's area is not this instance's.
bool jb_outsideSlot() {
#ifdef JB_ATLAS
    highp ivec2 local = ivec2(gl_FragCoord.xy) - v_slot.xy;
    highp int side = 1 << u_atlasShift;
    return local.x < 0 || local.y < 0 || local.x >= side || local.y >= side;
#else
    return false;
#endif
}
