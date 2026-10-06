package cc.joycreator.joybrush.android.board

import android.graphics.Path
import androidx.core.graphics.PathParser

/** Solid glyph paths generated verbatim from UI-Boards-options.html ICONS; export uses the shared app drawable. */
internal object BoardGlyphs {
    private val paths = mapOf(
        "film" to "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM4.5 5.5h2v2h-2zM8.8 5.5h2v2h-2zM13.2 5.5h2v2h-2zM17.5 5.5h2v2h-2zM4.5 16.5h2v2h-2zM8.8 16.5h2v2h-2zM13.2 16.5h2v2h-2zM17.5 16.5h2v2h-2zM5 9.25h14v5.5H5z",
        "image" to "M3 4h18a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM4.5 6.5v11h15v-11zM5.5 16.5l4.2-5.6 2.9 3.8 1.9-2.4 3.5 4.2zM16 7.6a1.7 1.7 0 1 1 0 3.4 1.7 1.7 0 0 1 0-3.4z",
        "sprite" to "M3 3h5v5H3zM9.5 3h5v5h-5zM16 3h5v5h-5zM3 9.5h5v5H3zM9.5 9.5h5v5h-5zM16 9.5h5v5h-5zM3 16h5v5H3zM9.5 16h5v5h-5zM16 16h5v5h-5z",
        "seam" to "M2.5 2.5h9v9h-9zM12.5 2.5h9v9h-9zM2.5 12.5h9v9h-9zM12.5 12.5h9v9h-9zM12 5.6l6.4 6.4-6.4 6.4-6.4-6.4z",
        "lockC" to "M5 10h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1zM12 13.4a1.6 1.6 0 0 0-.8 3v2.1h1.6v-2.1a1.6 1.6 0 0 0-.8-3zM7.5 10V7.5a4.5 4.5 0 0 1 9 0V10h-2.2V7.5a2.3 2.3 0 0 0-4.6 0V10z",
        "lockO" to "M5 10h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1zM12 13.4a1.6 1.6 0 0 0-.8 3v2.1h1.6v-2.1a1.6 1.6 0 0 0-.8-3zM14.3 10V6.5a2.3 2.3 0 0 0-4.6 0V7H7.5v-.5a4.5 4.5 0 0 1 9 0V10z",
        "swap" to "M7.5 3L3 7.5 7.5 12V9H19V6H7.5zM16.5 12l4.5 4.5-4.5 4.5V18H5v-3h11.5z",
        "runner" to "M15.2 1.6a2.3 2.3 0 1 1 0 4.6 2.3 2.3 0 0 1 0-4.6zM10.6 7.2l4.4-.1c.8 0 1.4.4 1.7 1.1l1.2 2.9 2.9.9-.6 1.9-3.6-1.1-.9-1.9-1.2 4 2.6 2.6V23h-2.2v-5.2l-2.6-2.4-1.6 3.6-4.6 2.5-1-1.9 3.9-2.1 2.2-6.6-1.6.1-1.8 2.8-1.9-1.1 2.3-3.6c.4-.6.9-.9 1.6-.9z",
        "mountain" to "M1.5 20l7-11 4.2 6.2 2.6-3.6L22.5 20zM17.5 3.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z",
        "play" to "M7 4.5v15l12.5-7.5z",
        "pause" to "M6 4.5h4.2v15H6zM13.8 4.5H18v15h-4.2z",
        "loop" to "M17 3.5l3.5 3.5-3.5 3.5V8.25H8a1.75 1.75 0 0 0-1.75 1.75v2H3.75v-2A4.25 4.25 0 0 1 8 5.75h9zM7 20.5L3.5 17 7 13.5v2.25h9A1.75 1.75 0 0 0 17.75 14v-2h2.5v2A4.25 4.25 0 0 1 16 18.25H7z",
        "plus" to "M10.75 4h2.5v6.75H20v2.5h-6.75V20h-2.5v-6.75H4v-2.5h6.75z",
        "subgrid" to "M3 3h18v18H3zM5 5h6v6H5zM13 5h6v6h-6zM5 13h6v6H5zM13 13h6v6h-6z",
        "pencil" to "M15.2 3.6l5.2 5.2L9.2 20H4v-5.2z",
        "dup" to "M9 2.5h10a2.5 2.5 0 0 1 2.5 2.5v10H19V5H9zM5 6.5h10A2.5 2.5 0 0 1 17.5 9v10a2.5 2.5 0 0 1-2.5 2.5H5A2.5 2.5 0 0 1 2.5 19V9A2.5 2.5 0 0 1 5 6.5z",
        "move" to "M12 1.5l4 4h-2.75v5.25h5.25V8l4 4-4 4v-2.75h-5.25v5.25H16l-4 4-4-4h2.75v-5.25H5.5V16l-4-4 4-4v2.75h5.25V5.5H8z",
        "x" to "M6.4 4.6L12 10.2l5.6-5.6 1.8 1.8-5.6 5.6 5.6 5.6-1.8 1.8-5.6-5.6-5.6 5.6-1.8-1.8 5.6-5.6-5.6-5.6z",
        "trash" to "M9 2.5h6l1 2h4.5V7h-17V4.5H8zM5 8.5h14l-1.1 12.1a1.5 1.5 0 0 1-1.5 1.4H7.6a1.5 1.5 0 0 1-1.5-1.4z",
    ).mapValues { (_, data) -> PathParser.createPathFromPathData(data)!!.apply { fillType = Path.FillType.EVEN_ODD } }
    fun path(name: String): Path? = paths[name]
}
