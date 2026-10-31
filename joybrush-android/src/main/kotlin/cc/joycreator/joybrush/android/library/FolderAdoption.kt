package cc.joycreator.joybrush.android.library

import cc.joycreator.joybrush.androidkit.JbCanvasView.Companion.LEGACY_SHARED_DOC_ID

/**
 * Which existing file in the Joy Creator folder a drawing's copy may take over when the list has no folder path for it (the
 * person moved or renamed it), and whether a folder file is already in the list. Audit 2026-10-18 P1: every drawing once
 * shared one document id, so "same id" let drawing B's copy overwrite drawing A's. Pure.
 */
internal object FolderAdoption {

    /**
     * The first of [paths] holding the same real document [id] that no OTHER drawing in the list keeps its copy in
     * ([claimed]); null = make a new file. Never by the once-shared id. [idOf] reads a file's id only when needed.
     */
    fun pick(id: String?, paths: Sequence<String>, claimed: Set<String>, idOf: (String) -> String?): String? {
        if (id == null || id == LEGACY_SHARED_DOC_ID) return null
        return paths.firstOrNull { it !in claimed && idOf(it) == id }
    }

    /** Is a folder file with document [id] already in the list? The once-shared id says nothing: those go by path. */
    fun knownById(id: String, known: Set<String>): Boolean = id != LEGACY_SHARED_DOC_ID && id in known

    /** Folder files more than one drawing points at, from each drawing's file name to its folder path. */
    fun shared(pathOf: Map<String, String>): Map<String, List<String>> =
        pathOf.keys.sorted().groupBy { pathOf.getValue(it) }.filterValues { it.size > 1 }

    /**
     * Which of the [drawings] sharing a folder file really is that file: the one with the real document id found inside
     * it ([idInside]). Null when none is, or the file only has the once-shared id: then the file is nobody's to take.
     */
    fun owner(drawings: List<String>, idInside: String?, idOf: (String) -> String?): String? {
        if (idInside == null || idInside == LEGACY_SHARED_DOC_ID) return null
        return drawings.firstOrNull { idOf(it) == idInside }
    }
}
