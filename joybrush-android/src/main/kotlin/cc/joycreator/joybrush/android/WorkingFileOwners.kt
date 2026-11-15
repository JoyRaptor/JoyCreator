package cc.joycreator.joybrush.android

/**
 * Which open drawing screen may write a working file: the one that opened it last.
 *
 * Two screens can end up on one drawing (a route that starts JoyBrush again over a screen already showing that file:
 * bug hunt, 2026-11-15). The newer one paints and saves; the one underneath still holds the picture it loaded, and its
 * save on pause wrote that older picture over the newer file: lost work. A screen claims its file when it has shown it;
 * a later claim by another screen takes the file over; a screen that no longer owns its file does not save to it, and
 * loads it again when it comes back to the front.
 *
 * One process, any thread. Pure.
 */
object WorkingFileOwners {
    private val owners = HashMap<String, Any>()

    /** [who] has shown the drawing at [path] and is now the one screen that writes it. */
    @Synchronized fun claim(path: String, who: Any) { owners[path] = who }

    /** True while [who] is the screen that last claimed [path]. A file nobody claimed is nobody's. */
    @Synchronized fun owns(path: String, who: Any): Boolean = owners[path] === who

    /** True when another screen claimed [path] after [who] did (or instead of it). */
    @Synchronized fun takenOver(path: String, who: Any): Boolean = owners[path].let { it != null && it !== who }

    /** [who] is closing: every file it still owns is free again. Files taken over by another screen stay that screen's. */
    @Synchronized fun release(who: Any) { owners.values.removeAll { it === who } }
}
