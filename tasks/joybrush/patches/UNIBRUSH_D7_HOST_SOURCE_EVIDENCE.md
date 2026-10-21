# Exact source evidence

Source bases are the D6 held copies; canonical Lead files remain untouched.


## C:/Temp/jb-unibrush-d6-held/lead-proposed/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt

SHA256 `2aa1c11dbedb48f5156b66de920fcb6c4e4a3f84066c8c6342c8058475efc068`

- Line507: `private fun requireLocalPaint() {`

- Line4660: `fun beginStroke(layerId: String, argb: Int, opacity: Float, accumulate: Accumulate,`

- Line4970: `fun endStroke(): Int {`

- Line2950: `check(!strokeInProgress && !wcFading) { "Finish the current stroke before replaying a line" }`

- Line2961: `require(preset.line == null) { "Whole-stroke shaping is not connected to live recording yet" }`

- Line3978: `internal fun preparePlainRecordingPromotion(ownerId: String): PlainPromotionTicket {`

- Line5352: `internal fun publishJointPaintStep(step: UndoLog.Step<Int>,`

- Line5364: `"A joint edit requires settled stable paint context"`

- Line5473: `publishedPaint = next // One publication for native sources, records, agreements and the complete cursor.`

- Line5475: `proposal.releaseRetiredAfterPublication()`

- Line5508: `proposal.releaseRetiredAfterPublication()`


## C:/Temp/jb-unibrush-d6-held/lead-proposed/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt

SHA256 `bc2256c1d9bf38284424e096540aae487bc530ec29864424bfe89ad6c78cbd54`

- Line529: `private fun boardStrokeWork(block: () -> Unit) {`

- Line662: `private class LineContact(val capture: StrokeCapture, val brushes: Map<String, BrushPreset>,`

- Line1841: `private fun beginStrokeNow(first: Dab? = null) {`

- Line1910: `private fun finishStroke() {`

- Line1960: `boardStrokeWork { engine.setPendingLine(line, lineBrushes, lineAssets); engine.endStroke(); reportHistory() }`

- Line1966: `private fun cancelStroke() {`


# Canonical protected sources (read-only)


## C:/Temp/jb-codex-unibrush/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt

SHA256 `bf3d79ce155af8d29c9e9c963b051a8357ee2fbc5727d31ce0a6b25dfd921732`

- Line459: `private fun requireLocalPaint() {`

- Line4518: `fun beginStroke(layerId: String, argb: Int, opacity: Float, accumulate: Accumulate,`

- Line4796: `fun endStroke(): Int {`

- Line3852: `internal fun preparePlainRecordingPromotion(ownerId: String): PlainPromotionTicket {`

- Line5175: `internal fun publishJointPaintStep(step: UndoLog.Step<Int>,`

- Line5295: `publishedPaint = next // One publication for native sources, records, agreements and the complete cursor.`


## C:/Temp/jb-codex-unibrush/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt

SHA256 `8dfad628d3301710b1434675292349f12d1b77b697d587cb4e7b901300f19336`

- Line518: `private fun boardStrokeWork(block: () -> Unit) {`

- Line1872: `private fun finishStroke() {`

- Line1921: `boardStrokeWork { engine.setPendingLine(line, lineBrushes); engine.endStroke(); reportHistory() }`
