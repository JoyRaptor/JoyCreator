# Lilac brush previews on the Note 9 (2026-11-13 to 11-14)

Found by the JoyBrush screens lane on 11-13: in the brush drawer the pencils' previews were blank lilac bars and most oil
previews salmon strokes on lilac; the canvas painted correctly.

| Picture | What it shows |
|---|---|
| `before_previews_pulled_from_the_note9.jpg` | The stored preview pictures as they were on the phone at 01:20 on 11-14 (`cache/brush_previews`): every pencil blank and lilac, eight oil brushes salmon on lilac, three oil brushes and both watercolours right, all from one evening |
| `after_drawer_pencils_and_oils_note9.jpg` | The Pencils shelf and the Oils shelf (two screens) drawn fresh by the fixed build: 9 pencils and 11 oil brushes, none lilac |

Cause: a preview's engine has no water, so its look reads the water stores through `MediaLayerEngine.emptyTex()`, a 1 x 1
texture that was made without data and never cleared (since 10-07). It held whatever the driver left; read as the water's
pigment it tinted everything and hid the pencil's flakes. It is now given zeros when made, and
`BrushPreviewKey.RENDERER_VERSION` 3 replaces the stored pictures. These are an agent's checks.
