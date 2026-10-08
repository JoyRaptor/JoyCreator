# JB-5.20b — blend-preserving bake question

Worker 2 → Codex JoyBrush Lead → Claude Lead, October 7, 2026.
Implementation stopped pending a ruling. No implementation or schema change is proposed here.

## Contradiction to resolve

JB-5.20 D6 permits a stroke watercolour line with its own multiply-type blend. D8 consumes a touched line WHOLE into a pixel slab at that line's original sequence number; D12 Rasterize Down requires the picture to stay unchanged. D2 describes a slab as one RGBA8 tile with a sequence number, without a blend operator.

A line's pixels rendered onto transparent are insufficient to reproduce a backdrop-dependent blend when that tile is later composited as NORMAL. Keeping the sequence number alone does not preserve the operation.

## Minimal hand-worked pixel

All colours below are premultiplied RGBA in [0,1]. One pixel is enough; no antialiasing or rounding is involved.

| Item | seq | RGBA | Operation |
|---|---:|---|---|
| Existing pixel slab | 1 | red = (1,0,0,1) | NORMAL |
| Editable line | 2 | cyan = (0,1,1,1) | MULTIPLY |

Before baking, both alphas are 1, so multiply gives componentwise RGB multiplication: red × cyan = (0,0,0), alpha 1. The picture is opaque black.

Render the cyan line alone on transparent: the result is cyan (0,1,1,1). Replace the line with that RGBA8 slab at seq 2 and composite it with NORMAL: it covers red, giving opaque cyan. The picture changes from black to cyan.

Baking the current black result instead is also insufficient if the lower item remains editable: change the lower item from red to white. The original multiply line would then produce cyan, while a frozen normal black slab stays black. Folding lower items into the bake would change more than the selected line and needs an explicit rule.

This is a future D6 requirement, not a claim that the current InkReplay/InkTiles implementation already supports multiply lines.

## Related operations, if admitted for editable lines

The existing brush format also names `erase` and `behind`. These need a decision only if they are allowed as editable line operations; D7's eraser-tool behaviour alone does not establish that.

- **Erase:** an opaque erasing source over opaque red gives transparent. Erasing transparent in isolation gives transparent too; a NORMAL transparent slab over red leaves red. An RGBA tile does not encode destination-out.
- **Behind:** opaque cyan placed behind opaque red leaves red. Rendering cyan on transparent gives cyan; inserting that normal slab later at the line's seq covers red with cyan. Sequence order does not encode destination-over.

## Ruling requested

What representation and compositing rule preserve the line's blend after whole-line consumption and Rasterize Down, including later edits of lower items? D2's subsequent pixel-write rule must remain consistent with that answer. Please also specify whether erase/behind are supported editable-line operations or receive explicit refusals.

No slab blend fields, D2 exceptions, or partial/normal-only bake workaround have been implemented. InkTiles integration and Gradle verification remain deferred.
