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
uniform highp isampler2D u_atlasTable;   // .r = slot index (column + row · slots per row); −1 = not resident
uniform ivec4 u_atlasGrid;               // the table's first tile (x, y) and its size in tiles (w, h)
uniform int u_atlasSlotsX;               // slots per atlas row
uniform int u_atlasShift;                // log2 of THIS pass's target slot side: 8 (256), or 7 for the half-res bead
uniform ivec4 u_atlasWrap;               // a Tile board in full-res layer px: origin (x, y), size (w, h); w == 0 = no wrap
flat in ivec4 v_slot;                    // this fragment's slot origin (atlas texels, xy) and its tile (zw)
#endif

const int JB_SLOT_SHIFT = 8;             // full-resolution slot side 256 = Tiles.SIZE

// This fragment's cell (layer px at the pass's resolution).
ivec2 jb_cell() {
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
int jb_floorDiv(int a, int b) { int q = a / b; if (q * b > a) q -= 1; return q; }

// Layer cell q at slot side (1 << shift) → its atlas texel. ok = false where the tile is not resident.
ivec2 jb_atS(ivec2 q, int shift, out bool ok) {
    if (u_atlasWrap.z > 0) {
        // A Tile board repeats in pixel space; boards need not be tile-aligned. The half-res field wraps at half the
        // size, which the driver only allows for even board rectangles.
        int d = JB_SLOT_SHIFT - shift;
        ivec2 o = u_atlasWrap.xy >> d, n = u_atlasWrap.zw >> d;
        ivec2 r = q - o;
        q = o + r - n * ivec2(jb_floorDiv(r.x, n.x), jb_floorDiv(r.y, n.y));
    }
    ivec2 tile = q >> shift;                       // arithmetic shift: floor, also for negative cells
    ivec2 local = q - (tile << shift);
    if (shift == u_atlasShift && tile == v_slot.zw) { ok = true; return v_slot.xy + local; }   // own tile: the common case
    ivec2 g = tile - u_atlasGrid.xy;
    if (g.x < 0 || g.y < 0 || g.x >= u_atlasGrid.z || g.y >= u_atlasGrid.w) { ok = false; return ivec2(0); }
    int slot = texelFetch(u_atlasTable, g, 0).r;
    if (slot < 0) { ok = false; return ivec2(0); }
    ok = true;
    return (ivec2(slot - (slot / u_atlasSlotsX) * u_atlasSlotsX, slot / u_atlasSlotsX) << shift) + local;
}
#endif

// texelFetch(t, q, 0) of a layer-shaped texture at layer cell q (half-res fields: shift 7).
vec4 jb_fetchS(sampler2D t, ivec2 q, int shift) {
#ifdef JB_ATLAS
    bool ok;
    ivec2 a = jb_atS(q, shift, ok);
    return ok ? texelFetch(t, a, 0) : vec4(0.0);
#else
    return texelFetch(t, q, 0);
#endif
}
vec4 jb_fetch(sampler2D t, ivec2 q) { return jb_fetchS(t, q, JB_SLOT_SHIFT); }

// The window build clamps neighbour cells to the window and skips cells outside it; the atlas has no edge (a cell with
// nothing resident reads as zero), so both become no-ops there.
ivec2 jb_clampCell(ivec2 q, ivec2 size) {
#ifdef JB_ATLAS
    return q;
#else
    return clamp(q, ivec2(0), size - 1);
#endif
}
bool jb_inside(ivec2 q, ivec2 size) {
#ifdef JB_ATLAS
    return true;
#else
    return q.x >= 0 && q.y >= 0 && q.x < size.x && q.y < size.y;
#endif
}

// jb_bilinear (jb_media_common.glsl) of a layer-shaped texture at layer px: the same four taps and weights, addressed.
vec4 jb_bilinearL(sampler2D t, vec2 px) {
#ifdef JB_ATLAS
    vec2 p = px - 0.5;
    ivec2 i = ivec2(floor(p));
    vec2 f = p - vec2(i);
    vec4 v00 = jb_fetch(t, i), v10 = jb_fetch(t, i + ivec2(1, 0));
    vec4 v01 = jb_fetch(t, i + ivec2(0, 1)), v11 = jb_fetch(t, i + ivec2(1, 1));
    return mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
#else
    return jb_bilinear(t, px);
#endif
}
