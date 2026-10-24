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
        val selected = tool.selection as? SelectionState.Lines ?: run { tool.restoreDeletedSelectionIfPresent(); return }
        val ids = selected.ids.toList()
        tool.end()
        tool.selectAddresses(ids)
    }

    /**
     * The drawing's lines changed from outside this tool (a stroke, an undo, a layer hidden, a drawing opened). Keep the
     * selected addresses that still resolve, refit the box, and end only when none remain. After Delete, an undo can bring
     * the held addresses back and restore the selection.
     */
    fun afterLinesChanged(tool: SelectTool, host: SelectionHost) {
        when (tool.selection) {
            is SelectionState.Lines -> tool.reconcileLineSelection()
            null -> tool.restoreDeletedSelectionIfPresent()
            is SelectionState.Pixels -> Unit
        }
    }

}
