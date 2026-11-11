# TextLab's own typefaces

Five open-licence faces chosen for the looks (owner's yes, 2026-11-14). Each is under the SIL Open
Font License 1.1, which allows bundling in an app, free or sold, as long as the licence text travels
with the font and the font is not sold on its own. The licence for each sits beside it as `<name>-OFL.txt`.

All were taken from the Google Fonts repository, `github.com/google/fonts`, folder `ofl/`, on 2026-11-14.

| File | Face | What it is for | Taken from |
|---|---|---|---|
| `pacifico.ttf` | Pacifico | A flowing brush script: Liquid Chrome | `ofl/pacifico/Pacifico-Regular.ttf` |
| `bebas_neue.ttf` | Bebas Neue | Tall condensed capitals, the film-poster standby | `ofl/bebasneue/BebasNeue-Regular.ttf` |
| `cinzel_variable.ttf` | Cinzel (weights 400 to 900) | Roman inscription capitals: Carved Stone, gold | `ofl/cinzel/Cinzel[wght].ttf` |
| `racing_sans_one.ttf` | Racing Sans One | A heavy leaning sans: chrome and speed | `ofl/racingsansone/RacingSansOne-Regular.ttf` |
| `orbitron_variable.ttf` | Orbitron (weights 400 to 900) | Wide geometric capitals: Hologram, science fiction | `ofl/orbitron/Orbitron[wght].ttf` |

A look names a face by its key (`"font": {"family": "cinzel"}`); the keys are listed in
`tools/textlab-web/tl-glyphs.js` (`TL.FONTS`) and must be mirrored by the app when it loads these.
