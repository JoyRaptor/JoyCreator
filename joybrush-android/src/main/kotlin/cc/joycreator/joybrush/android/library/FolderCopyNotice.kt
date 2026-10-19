package cc.joycreator.joybrush.android.library

/**
 * The owner's rule is that uninstalling never loses work, and the Joy Creator folder is where work survives an uninstall.
 * So when a drawing could not be copied there, the person is told - once per drawing per session, not on every save
 * (each save tries again, and a folder that stays unreachable must not nag).
 */
class FolderCopyNotice {
    private val told = HashSet<String>()

    /** True the first time [drawing] fails to copy this session; false for every later failure of the same drawing. */
    @Synchronized fun firstFailure(drawing: String): Boolean = told.add(drawing)

    companion object {
        /** One clear sentence: what failed, that the drawing is safe where it is, and that it will be tried again. */
        const val SENTENCE = "Couldn't copy this drawing to your Joy Creator folder. It is safe in the app, and the next save will try again."
    }
}
