package cc.joycreator.joybrush.core.brush.imports

/**
 * JBX-18: which brush importer a picked file goes to. The file's name decides first (the picker shows every file, and
 * the person picked this one for its name); when the name says nothing, the first bytes decide. A ZIP with no telling
 * name may be a Procreate brush, a Procreate set or a Krita bundle, so all three are offered in that order and the
 * caller keeps the first that brings at least one brush across.
 */
enum class BrushFileKind(val words: String) {
    ABR("Photoshop brushes"),
    PROCREATE_BRUSH("a Procreate brush"),
    PROCREATE_SET("a Procreate brush set"),
    KRITA_PRESET("a Krita brush"),
    KRITA_BUNDLE("a Krita bundle"),
    MYPAINT("a MyPaint brush");

    companion object {
        private val BY_EXTENSION = mapOf(
            "abr" to ABR,
            "brush" to PROCREATE_BRUSH,
            "brushset" to PROCREATE_SET,
            "kpp" to KRITA_PRESET,
            "bundle" to KRITA_BUNDLE,
            "myb" to MYPAINT,
        )

        /** The extensions the picker can say it takes, for a hint line. */
        val extensions: Set<String> get() = BY_EXTENSION.keys

        /**
         * The importers to try for a file called [name] whose first bytes are [head] (any length; 8 is plenty), best
         * first. Empty: this is not a brush file JoyBrush can read.
         */
        fun candidates(name: String?, head: ByteArray): List<BrushFileKind> {
            val ext = name?.substringAfterLast('.', "")?.lowercase()?.trim()
            BY_EXTENSION[ext]?.let { return listOf(it) }
            return when {
                head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> listOf(KRITA_PRESET)
                head.startsWith('P'.code, 'K'.code) -> listOf(PROCREATE_BRUSH, PROCREATE_SET, KRITA_BUNDLE)
                head.firstNonBlank() == '{'.code -> listOf(MYPAINT)
                // ABR: a big-endian version word, 1, 2 or 6..10 (Photoshop 7 to CC).
                head.size >= 2 && head[0].toInt() == 0 && (head[1].toInt() in 1..2 || head[1].toInt() in 6..10) -> listOf(ABR)
                else -> emptyList()
            }
        }

        private fun ByteArray.startsWith(vararg b: Int): Boolean =
            size >= b.size && b.indices.all { (this[it].toInt() and 0xFF) == b[it] }

        private fun ByteArray.firstNonBlank(): Int? {
            var i = 0
            // A UTF-8 byte-order mark, then spaces and line ends.
            if (size >= 3 && (this[0].toInt() and 0xFF) == 0xEF && (this[1].toInt() and 0xFF) == 0xBB && (this[2].toInt() and 0xFF) == 0xBF) i = 3
            while (i < size && (this[i].toInt() and 0xFF) in setOf(0x20, 0x09, 0x0A, 0x0D)) i++
            return if (i < size) this[i].toInt() and 0xFF else null
        }
    }
}
