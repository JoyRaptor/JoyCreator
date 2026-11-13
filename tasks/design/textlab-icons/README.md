# TextLab icons (proposal v1, 2026-11-12)

46 custom line icons (24 grid, 1.6 stroke, currentColor) for the Look row in Studio and the 37 property tabs of the
TextLab room. Shown to the owner for approval at https://claude.ai/artifact/BJKryvrHDrtetpFNr1FB7Y (private).
Not in the app yet: they go in once he approves them (each one is kept or sent back for a change).

- `textlab-icons-v1.json`: group, word, meaning, svg, note.
- `gen_textlab_icons.py`: rebuilds the JSON.
- `build_icons_page.py`: builds the review page from the JSON.

## Round two (v2, 2026-11-12 late)

The owner's v1 picks: 34 kept, ten sent back with notes (Look-row Light, Still, Sway, Record tilt, Sides, Burn, Front,
Shatter, Fog, Finish), Plain and Ember unmarked. `textlab-icons-v2.json` (49 entries) redraws the ten as he described,
keeps every approved icon byte-identical, and adds "Depth (swap)" / "Sides (swap)" so he could see the two tabs'
drawings exchanged. Republished at the same link.

**His v2 picks:** every icon kept, including all four of Depth, Depth (swap), Sides, Sides (swap), so one pick is
left: which drawing each of those two tabs gets. (Depth is the tab for the letters' 3D shape: bevel, pushed back,
perspective. Sides is only the colour of the side walls: at the face, at the back, flat bands.) Plain and Ember
were not marked either time; they stay as drawn unless he says otherwise. **Export:** not this set's drawing; the
TextLab screen uses Studio's own export mark (`ic_export_studio`), his call for consistency.

- `gen_textlab_icons_v2.py` rebuilds v2; `build_icons_page_v2.py <v2.json> <out.html> <v1.json>` builds the
  round-two page (redrawn first, then unmarked, then already kept).

## Final (2026-11-12 late): in the app

His last word: "depth should taper as it goes back other then that ship em" (he had sent screenshots of the tile
for Sides and the block for Depth). `textlab-icons-final.json` (46): Depth = the block redrawn with its back face
smaller (a vanishing point up and to the right), Sides = the bevelled tile, no swap entries, no Export drawing.
`python -I to_android.py textlab-icons-v2.json textlab-icons-final.json ../../../app/src/main/res/drawable`
writes `ic_tl_<word>.xml` (Look row) and `ic_tl_tab_<slot>.xml` (tabs): white, tinted in code. Edit the JSON
and rerun; never hand-edit the drawables.
