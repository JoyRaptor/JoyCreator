package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.select.SelectionState

/**
 * Keeps the Select tool honest when the drawing moves under it. The tool reads its lines from the host on its own
 * schedule (a touch, a pick, right after each edit) and shows what it last read, so two things have to be said to it
 * from outside: that an edit it was told had worked did not, and that lines it holds are gone.
 */
object SelectionSync {

    /**
     * An edit the host accepted was refused by the engine. The tool already moved its box (and carried a kept loop with it)
     * as if it had worked. Select the same lines again from what the host now holds: the box is refitted round them where
     * they really are, and a stale loop is dropped. A line that is gone drops out of the selection.
     */
    fun afterRefusedEdit(tool: SelectTool) {
        val selected = tool.selection as? SelectionState.Lines ?: return
        val ids = selected.ids.toList()
        val at = selected.pick ?: SelectionState.Pick(FAR, FAR)   // no spot to cycle from
        tool.end()
        for (id in ids) {
            val now = tool.selection as? SelectionState.Lines
            if (now != null && id in now.ids) continue   // a group already brought it back
            tool.chooseFromOrder(listOf(id), 0, adding = now != null, at = at)
        }
    }

    /**
     * The drawing's lines changed from outside this tool (a stroke, an undo, a layer hidden, a drawing opened). A selection
     * that names a line that is no longer there ends; nothing else about it is guessed at.
     */
    fun afterLinesChanged(tool: SelectTool, host: SelectionHost) {
        val selected = tool.selection as? SelectionState.Lines ?: return
        val there = host.visibleLines().mapTo(HashSet()) { it.address }
        if (selected.ids.any { it !in there }) tool.end()
    }

    private const val FAR = -1.0e9
}
