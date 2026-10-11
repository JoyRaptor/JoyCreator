package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.media.MediaStores
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias Key = Triple<String, String?, Long>
private typealias Change = UndoLog.TileChange<Int>

/**
 * Undo/redo TRANSACTION sequences against the real [UndoLog] (JB-NOW-09).
 *
 * What this checks: deterministic mixed sequences of push/undo/redo, new-push-after-undo (branch),
 * clear, over-budget capacity trimming, [UndoLog.mergeNewest] and newest-step extension via
 * [MediaStores.recordWater] / [UndoLog.extendNewest], using identifiable fake Int resource handles.
 * After every operation it fails on history-order, retained-change, metadata, byte-count and release
 * mismatches; each fuzz seed ends with a full undo-all/redo-all drain that compares every step.
 *
 * The oracle is an independent simple chronological list plus a cursor (committed prefix length),
 * a tile-to-current-handle map and a cumulative released set — structurally different from the
 * production two-deque implementation. It re-derives only DOCUMENTED contract rules: the ownership
 * rules in [UndoLog]'s KDoc (undo steps own befores, redo steps own afters, everything else is the
 * layers'; oldest dropped over budget releases befores; discarded redo releases afters; merged
 * intermediates are released; clear releases undo-befores plus redo-afters), the trim call sites
 * (push/redo/merge/extend trim, undo does not, and the newest step is always kept), and the
 * media-step rule behind [MediaStores.recordWater] (a media-tiles-only step with no stack/paper/
 * document change, nothing to redo, disjoint tiles).
 *
 * Existing coverage this builds on (not duplicated): PaintTest.undoLogReleasesEveryTileExactlyOnce
 * (single-tile trim/redo-discard/clear accounting), PaintTest.undoAndRedoSwapWholeTilesBack
 * (RefCanvas-level swap), RegionUndoTest (celId planes in one step; a two-step merge carrying
 * document metadata), LineUndoTest (line folding/budget/replace/extend of editable lines),
 * RunningWaterUndoTest (recordWater join/fresh-push/duplicate-throw, replaceInNewest dry-out),
 * PaperEditingTest (paper steps sharing chronology; paper merge keeps first/last), and
 * BrushTuningTest/TuftTuningTest.severalStepsFoldIntoOneUndoAndTheTilesInBetweenAreReleased
 * (three-step tile merge intermediates). New here: multi-operation randomized sequences with an
 * independent oracle, a branch-after-undo fixture with multi-change steps, a merged-step fixture
 * with document first/last plus an undo/redo round trip, one step combining the look tile with ALL
 * currently defined media-store IDs plus document metadata, a running-water episode across
 * media/non-media steps, and an explicit over-budget newest-kept plus clear-exactly-once fixture.
 * The duplicate-tile throw and basic drip-join appear as small contract anchors inside the water
 * episode; the surrounding sequences are new.
 *
 * Scope limits: this asserts pure history bookkeeping (which changes travel together, in what
 * order, with what metadata, and which handles are released exactly once). Pixel restoration is
 * the CALLER's job per [UndoLog.undo]/[UndoLog.redo] ("the caller puts each change's before/after
 * back into its layer") and is never claimed via GL here. [MediaStores.recordWater] is used only
 * where its public contract applies (media steps, disjoint tiles, empty list as a no-op).
 *
 * Determinism: fixed seeds [SEEDS], 200 bounded operations each (at most 3 changes per step,
 * bounded tile/layer/cel pools). Every fuzz assertion message prints its seed and operation index.
 */
class UndoTransactionSequenceTest {

    private companion object {
        val SEEDS = listOf(20261009L, 9090909L, 1234567L)
        const val OPS_PER_SEED = 200
        const val BUDGET_BYTES = 8L
    }

    private data class OracleStep(
        val changes: List<Change>,
        val stackBefore: LayerStack? = null,
        val stackAfter: LayerStack? = null,
        val paperBefore: Paper? = null,
        val paperAfter: Paper? = null,
        val documentBefore: JbDocument? = null,
        val documentAfter: JbDocument? = null,
    )

    /** Independent list-plus-cursor model of the same documented history contract. */
    private class Oracle(val budget: Long) {
        val steps = ArrayList<OracleStep>()
        var cursor = 0 // active (undoable) prefix length; steps beyond it are redo.
        val layers = HashMap<Key, Int?>() // current handle per tile; null/absent = tile does not exist.
        val released = LinkedHashSet<Int>()
        var trimCount = 0

        fun keyOf(c: Change): Key = Triple(c.layerId, c.celId, c.key)

        fun heldBytes(): Long {
            var b = 0L
            for (i in 0 until cursor) for (c in steps[i].changes) if (c.before != null) b += 1
            for (i in cursor until steps.size) for (c in steps[i].changes) if (c.after != null) b += 1
            return b
        }

        /** The documented media-step rule behind MediaStores.recordWater / isMediaStep. */
        fun isMediaStep(s: OracleStep): Boolean =
            s.stackBefore == null && s.stackAfter == null && s.paperBefore == null && s.documentBefore == null &&
                s.changes.any { MediaStores.isMediaStore(it.layerId) }

        fun noteReleased(h: Int, context: String) {
            check(released.add(h)) { "double release of handle $h ($context)" }
        }

        fun trim() {
            while (cursor > 1 && heldBytes() > budget) {
                val dropped = steps.removeAt(0)
                cursor--
                for (c in dropped.changes) c.before?.let { noteReleased(it, "trim-oldest") }
                trimCount++
            }
        }

        fun pushStep(s: OracleStep, context: String) {
            while (steps.size > cursor) {
                val r = steps.removeAt(steps.size - 1)
                for (c in r.changes) c.after?.let { noteReleased(it, "discard-redo") }
            }
            for (c in s.changes) {
                check(layers[keyOf(c)] == c.before) {
                    "push $context records before=${c.before} for ${keyOf(c)} but layers hold ${layers[keyOf(c)]}"
                }
            }
            steps.add(s)
            cursor++
            for (c in s.changes) layers[keyOf(c)] = c.after
            trim()
        }

        fun undoOne(): OracleStep? {
            if (cursor == 0) return null
            cursor--
            val s = steps[cursor]
            for (c in s.changes) layers[keyOf(c)] = c.before
            return s
        }

        fun redoOne(): OracleStep? {
            if (cursor == steps.size) return null
            val s = steps[cursor]
            cursor++
            for (c in s.changes) layers[keyOf(c)] = c.after
            trim()
            return s
        }

        fun mergeNewest(count: Int) {
            val n = minOf(count, cursor)
            if (n < 2) return
            val slice = steps.subList(cursor - n, cursor).toList()
            val merged = LinkedHashMap<Key, Change>()
            for (s in slice) for (c in s.changes) {
                val k = keyOf(c)
                val prev = merged[k]
                if (prev == null) merged[k] = c
                else {
                    c.before?.let { noteReleased(it, "merge-intermediate") }
                    merged[k] = UndoLog.TileChange(c.layerId, c.key, prev.before, c.after, c.celId)
                }
            }
            val folded = OracleStep(
                changes = merged.values.toList(),
                stackBefore = slice.firstOrNull { it.stackBefore != null }?.stackBefore,
                stackAfter = slice.lastOrNull { it.stackAfter != null }?.stackAfter,
                paperBefore = slice.firstOrNull { it.paperBefore != null }?.paperBefore,
                paperAfter = slice.lastOrNull { it.paperAfter != null }?.paperAfter,
                documentBefore = slice.firstOrNull { it.documentBefore != null }?.documentBefore,
                documentAfter = slice.lastOrNull { it.documentAfter != null }?.documentAfter,
            )
            repeat(n) {
                steps.removeAt(cursor - 1)
                cursor--
            }
            steps.add(cursor, folded)
            cursor++
            trim()
        }

        fun extendTop(changes: List<Change>, accepts: (OracleStep) -> Boolean): Boolean {
            if (cursor != steps.size) return false
            if (cursor == 0) return false
            val top = steps[cursor - 1]
            if (!accepts(top)) return false
            val held = top.changes.mapTo(HashSet()) { keyOf(it) }
            require(changes.none { keyOf(it) in held }) { "a tile already in the newest step was snapshotted again" }
            for (c in changes) {
                check(layers[keyOf(c)] == c.before) {
                    "extend records before=${c.before} for ${keyOf(c)} but layers hold ${layers[keyOf(c)]}"
                }
            }
            steps[cursor - 1] = top.copy(changes = top.changes + changes)
            for (c in changes) layers[keyOf(c)] = c.after
            trim()
            return true
        }

        fun recordWater(changes: List<Change>) {
            if (changes.isEmpty()) return
            if (!extendTop(changes, ::isMediaStep)) pushStep(OracleStep(changes), "recordWater-fresh")
        }

        fun clearSteps() {
            for (i in 0 until cursor) for (c in steps[i].changes) c.before?.let { noteReleased(it, "clear-undo") }
            for (i in cursor until steps.size) for (c in steps[i].changes) c.after?.let { noteReleased(it, "clear-redo") }
            steps.clear()
            cursor = 0
        }

        /** Handles the history still requires: layer currents, active befores, redo afters. */
        fun liveHandles(): Set<Int> {
            val live = HashSet<Int>()
            for ((_, v) in layers) if (v != null) live.add(v)
            for (i in 0 until cursor) for (c in steps[i].changes) c.before?.let(live::add)
            for (i in cursor until steps.size) for (c in steps[i].changes) c.after?.let(live::add)
            return live
        }
    }

    private fun assertStepsEqual(msg: String, expected: OracleStep, actual: UndoLog.Step<Int>) {
        assertEquals(expected.changes.size, actual.changes.size, "$msg: change count")
        for (i in expected.changes.indices) {
            val e = expected.changes[i]
            val a = actual.changes[i]
            assertEquals(e.layerId, a.layerId, "$msg change $i layerId")
            assertEquals(e.key, a.key, "$msg change $i key")
            assertEquals(e.celId, a.celId, "$msg change $i celId")
            assertEquals(e.before, a.before, "$msg change $i before")
            assertEquals(e.after, a.after, "$msg change $i after")
        }
        assertEquals(expected.stackBefore, actual.stackBefore, "$msg stackBefore")
        assertEquals(expected.stackAfter, actual.stackAfter, "$msg stackAfter")
        assertEquals(expected.paperBefore, actual.paperBefore, "$msg paperBefore")
        assertEquals(expected.paperAfter, actual.paperAfter, "$msg paperAfter")
        assertEquals(expected.documentBefore, actual.documentBefore, "$msg documentBefore")
        assertEquals(expected.documentAfter, actual.documentAfter, "$msg documentAfter")
    }

    @Test
    fun transactionSequencesMatchIndependentOracle() {
        for (seed in SEEDS) runSequence(seed)
    }

    private fun runSequence(seed: Long) {
        val rnd = Random(seed)
        val releasedActual = ArrayList<Int>()
        val log = UndoLog<Int>(BUDGET_BYTES, { 1L }, { releasedActual.add(it) })
        val oracle = Oracle(BUDGET_BYTES)

        var nextHandle = 1
        fun fresh(): Int = nextHandle++

        var docSeq = 0
        var idSeq = 0
        fun freshDoc(): JbDocument {
            docSeq++
            return DocOps.newDocument("doc-$docSeq", "Doc $docSeq", 32, 32) { "id-${idSeq++}" }
        }
        var currentDoc = freshDoc()

        val stackA = LayerStack.single("l1")
        val stackB = stackA.add("l2")
        val look = "wc"
        val layerPool = listOf(look) + MediaStores.ALL.map { MediaStores.id(look, it) } + "plain"
        // Distinct planes only: a repeated entry let one step snapshot the same tile twice, an input the engine never makes
        // (the "production double release" JB-NOW-09 reported was this generator, not UndoLog; lead, 2026-10-11).
        val celPool = listOf(null, "shared")
        val tileKeys = listOf(0L, 1L, 2L, 3L)
        fun allKeys(): List<Key> = layerPool.flatMap { l -> celPool.flatMap { c -> tileKeys.map { k -> Triple(l, c, k) } } }

        fun freshChanges(count: Int, disallow: Set<Key>): List<Change> {
            val pool = allKeys().filter { it !in disallow }.shuffled(rnd)
            return pool.take(minOf(count, pool.size)).map { k -> Change(k.first, k.third, oracle.layers[k], fresh(), k.second) }
        }

        fun checkState(op: Int, what: String) {
            val tag = "seed $seed op $op ($what)"
            assertEquals(oracle.cursor, log.undoDepth, "$tag: undoDepth")
            assertEquals(oracle.cursor > 0, log.canUndo, "$tag: canUndo")
            assertEquals(oracle.cursor < oracle.steps.size, log.canRedo, "$tag: canRedo")
            assertEquals(oracle.heldBytes(), log.heldBytes, "$tag: heldBytes")
            assertEquals(oracle.released, releasedActual.toSet(), "$tag: released set")
            assertEquals(releasedActual.size, releasedActual.toSet().size, "$tag: handle released twice: $releasedActual")
            val bad = releasedActual.toSet() intersect oracle.liveHandles()
            assertTrue(bad.isEmpty(), "$tag: released still-required handles $bad")
        }

        fun doUndo(op: Int, tag: String) {
            val expected = oracle.undoOne()
            val actual = log.undo()
            if (expected == null) assertNull(actual, "seed $seed op $op ($tag): undo past bottom must be null")
            else assertStepsEqual(
                "seed $seed op $op ($tag): undone step",
                expected,
                assertNotNull(actual, "seed $seed op $op ($tag): undo returned null, oracle had a step"),
            )
        }

        fun doRedo(op: Int, tag: String) {
            val expected = oracle.redoOne()
            val actual = log.redo()
            if (expected == null) assertNull(actual, "seed $seed op $op ($tag): redo past top must be null")
            else assertStepsEqual(
                "seed $seed op $op ($tag): redone step",
                expected,
                assertNotNull(actual, "seed $seed op $op ($tag): redo returned null, oracle had a step"),
            )
        }

        repeat(OPS_PER_SEED) { op ->
            when (rnd.nextInt(100)) {
                in 0..39, in 96..99 -> {
                    // Push a small bounded step, sometimes carrying document or stack metadata.
                    val changes = freshChanges(1 + rnd.nextInt(3), emptySet())
                    val docBefore = if (rnd.nextInt(100) < 15) currentDoc else null
                    val docAfter = if (docBefore != null) { currentDoc = freshDoc(); currentDoc } else null
                    val withStack = rnd.nextInt(100) < 5
                    val step = UndoLog.Step(
                        changes,
                        stackBefore = if (withStack) stackA else null,
                        stackAfter = if (withStack) stackB else null,
                        documentBefore = docBefore,
                        documentAfter = docAfter,
                    )
                    log.push(step)
                    oracle.pushStep(
                        OracleStep(
                            changes,
                            stackBefore = if (withStack) stackA else null,
                            stackAfter = if (withStack) stackB else null,
                            documentBefore = docBefore,
                            documentAfter = docAfter,
                        ),
                        "push",
                    )
                    checkState(op, "push")
                }
                in 40..59 -> {
                    doUndo(op, "undo")
                    checkState(op, "undo")
                }
                in 60..74 -> {
                    doRedo(op, "redo")
                    checkState(op, "redo")
                }
                in 75..81 -> {
                    val n = 2 + rnd.nextInt(2)
                    log.mergeNewest(n)
                    oracle.mergeNewest(n)
                    checkState(op, "merge-$n")
                }
                in 82..89 -> {
                    // Running-water drip: disjoint from the top step so the contract's
                    // disjointness require can never fire accidentally here.
                    val topKeys =
                        if (oracle.cursor == oracle.steps.size && oracle.cursor > 0) {
                            oracle.steps[oracle.cursor - 1].changes.mapTo(HashSet()) { oracle.keyOf(it) }
                        } else emptySet()
                    val pool = allKeys().filter { it !in topKeys }.shuffled(rnd)
                    if (pool.isEmpty()) {
                        doUndo(op, "water-fallback")
                    } else {
                        val changes = pool.take(minOf(1 + rnd.nextInt(2), pool.size)).map { k ->
                            Change(k.first, k.third, oracle.layers[k], fresh(), k.second)
                        }
                        MediaStores.recordWater(log, changes)
                        oracle.recordWater(changes)
                    }
                    checkState(op, "recordWater")
                }
                in 90..93 -> {
                    val topKeys =
                        if (oracle.cursor == oracle.steps.size && oracle.cursor > 0) {
                            oracle.steps[oracle.cursor - 1].changes.mapTo(HashSet()) { oracle.keyOf(it) }
                        } else emptySet()
                    val pool = allKeys().filter { it !in topKeys }.shuffled(rnd)
                    if (pool.isEmpty()) {
                        doUndo(op, "extend-fallback")
                    } else {
                        val k = pool.first()
                        val changes = listOf(Change(k.first, k.third, oracle.layers[k], fresh(), k.second))
                        val expected = oracle.extendTop(changes) { true }
                        assertEquals(expected, log.extendNewest(changes) { true }, "seed $seed op $op (extend): result")
                    }
                    checkState(op, "extend")
                }
                else -> {
                    log.clear()
                    oracle.clearSteps()
                    checkState(op, "clear")
                }
            }
        }

        assertTrue(
            oracle.trimCount > 0,
            "seed $seed: 200 mixed ops never trimmed; budget $BUDGET_BYTES is too generous to exercise capacity",
        )

        // Full drain: history order, retained changes and metadata, newest-first then oldest-last.
        val layersAfterFuzz = HashMap(oracle.layers)
        var n = 0
        while (true) {
            val expected = oracle.undoOne()
            val actual = log.undo()
            if (expected == null) {
                assertNull(actual, "seed $seed drain: undo past bottom must be null")
                break
            }
            assertStepsEqual(
                "seed $seed drain undo #$n",
                expected,
                assertNotNull(actual, "seed $seed drain undo #$n returned null"),
            )
            n++
        }
        assertEquals(0, log.undoDepth, "seed $seed drain: depth after undo-all")
        assertFalse(log.canUndo, "seed $seed drain: canUndo after undo-all")
        val releasedAfterUndo = releasedActual.toSet()
        n = 0
        while (true) {
            val expected = oracle.redoOne()
            val actual = log.redo()
            if (expected == null) {
                assertNull(actual, "seed $seed drain: redo past top must be null")
                break
            }
            assertStepsEqual(
                "seed $seed drain redo #$n",
                expected,
                assertNotNull(actual, "seed $seed drain redo #$n returned null"),
            )
            n++
        }
        assertEquals(layersAfterFuzz, oracle.layers, "seed $seed: undo-all/redo-all must restore layer contents")
        assertEquals(releasedAfterUndo, releasedActual.toSet(), "seed $seed: draining must release nothing")
        checkState(OPS_PER_SEED, "drained")
    }

    @Test
    fun branchAfterUndoDiscardsRedoAndReleasesItsAfters() {
        val released = ArrayList<Int>()
        val log = UndoLog<Int>(1L shl 30, { 1L }, { released.add(it) })
        fun ch(layer: String, key: Long, before: Int?, after: Int) = UndoLog.TileChange(layer, key, before, after)

        log.push(UndoLog.Step(listOf(ch("L", 1L, null, 101))))
        log.push(UndoLog.Step(listOf(ch("L", 1L, 101, 102), ch("L", 2L, null, 201))))
        val undoneB = assertNotNull(log.undo())
        assertEquals(listOf(1L, 2L), undoneB.changes.map { it.key })
        assertEquals(102, undoneB.changes[0].after)

        // A new push after an undo discards the redo stack and releases its afters.
        log.push(UndoLog.Step(listOf(ch("L", 1L, 101, 103))))
        assertFalse(log.canRedo, "branching must discard redo")
        assertEquals(listOf(102, 201), released, "discarded redo releases its afters in order")
        assertEquals(2, log.undoDepth)

        // History order after the branch: C then A; B is gone.
        assertEquals(103, assertNotNull(log.undo()).changes.single().after)
        assertEquals(101, assertNotNull(log.undo()).changes.single().after)
        assertNull(log.undo())
        assertEquals(101, assertNotNull(log.redo()).changes.single().after)
        assertEquals(103, assertNotNull(log.redo()).changes.single().after)
        assertNull(log.redo())
        assertEquals(setOf(102, 201), released.toSet(), "branching must not release anything else")
    }

    @Test
    fun mergedStepsKeepOldestBeforeNewestAfterAndTravelWithDocument() {
        var idSeq = 0
        fun doc(name: String): JbDocument = DocOps.newDocument("doc-$name", name, 32, 32) { "id-${idSeq++}" }
        val docA = doc("A")
        val docB = docA.copy(name = "B")
        val docC = docA.copy(name = "C")

        val released = ArrayList<Int>()
        val log = UndoLog<Int>(1L shl 30, { 1L }, { released.add(it) })
        fun ch(layer: String, key: Long, before: Int?, after: Int) = UndoLog.TileChange(layer, key, before, after)

        log.push(UndoLog.Step(listOf(ch("L", 9L, null, 1), ch("M", 9L, null, 11)), documentBefore = docA, documentAfter = docB))
        log.push(UndoLog.Step(listOf(ch("L", 9L, 1, 2), ch("M", 9L, 11, 12)), documentBefore = docB, documentAfter = docC))
        log.mergeNewest(2)

        assertEquals(1, log.undoDepth)
        assertEquals(listOf(1, 11), released, "merging releases the tiles in between, oldest step first")
        val merged = assertNotNull(log.undo())
        val byLayer = merged.changes.associateBy { it.layerId }
        assertEquals(null, byLayer.getValue("L").before, "merged keeps the oldest before")
        assertEquals(2, byLayer.getValue("L").after, "merged keeps the newest after")
        assertEquals(null, byLayer.getValue("M").before)
        assertEquals(12, byLayer.getValue("M").after)
        assertEquals(docA, merged.documentBefore, "merged keeps the first document-before")
        assertEquals(docC, merged.documentAfter, "merged keeps the last document-after")

        val redone = assertNotNull(log.redo())
        assertEquals(merged.changes.map { it.after }, redone.changes.map { it.after }, "redo carries the same newest afters")
        assertEquals(docC, redone.documentAfter)
        assertTrue(released.toSet().size == released.size, "no double release: $released")
    }

    @Test
    fun lookAndAllMediaStoresTravelInOneStepWithDocumentMetadata() {
        // A media layer's look keeps the layer's own id; each float store is <layerId>#<store>.
        var idSeq = 0
        fun doc(name: String): JbDocument = DocOps.newDocument("doc-$name", name, 32, 32) { "id-${idSeq++}" }
        val docA = doc("A")
        val docB = docA.copy(name = "B")

        val released = ArrayList<Int>()
        val log = UndoLog<Int>(1L shl 30, { 1L }, { released.add(it) })
        var h = 500
        val changes = listOf(UndoLog.TileChange("wc", 7L, null, h++)) +
            MediaStores.ALL.map { store -> UndoLog.TileChange(MediaStores.id("wc", store), 7L, null, h++) }
        log.push(UndoLog.Step(changes, documentBefore = docA, documentAfter = docB))

        assertEquals(1 + MediaStores.ALL.size, changes.size, "look plus every currently defined media store")
        assertEquals(1, log.undoDepth)
        assertTrue(released.isEmpty(), "pushing releases nothing")

        val undone = assertNotNull(log.undo())
        assertEquals((listOf("wc") + MediaStores.ALL.map { MediaStores.id("wc", it) }).toSet(), undone.changes.map { it.layerId }.toSet())
        assertTrue(undone.changes.all { it.before == null }, "all tiles are first-touched snapshots")
        assertEquals(docA, undone.documentBefore)
        assertEquals(docB, undone.documentAfter)

        val redone = assertNotNull(log.redo())
        assertEquals(undone.changes.map { it.after }, redone.changes.map { it.after })
        assertEquals(docA, redone.documentBefore)
        assertEquals(docB, redone.documentAfter)
        assertTrue(released.isEmpty(), "undo/redo of live steps releases nothing")
    }

    @Test
    fun runningWaterJoinsMediaStepsOnly() {
        val released = ArrayList<Int>()
        val log = UndoLog<Int>(1L shl 30, { 1L }, { released.add(it) })
        fun tile(store: String, key: Long, before: Int?, after: Int) =
            UndoLog.TileChange(MediaStores.id("wc", store), key, before, after)
        val stack = LayerStack.single("l1")

        // A drip during stroke B joins B: history stays linear, one episode is one step.
        log.push(UndoLog.Step(listOf(tile("p0", 1L, null, 10), tile("w0", 1L, null, 11))))
        log.push(UndoLog.Step(listOf(tile("p0", 2L, null, 20))))
        MediaStores.recordWater(log, listOf(tile("w0", 3L, null, 30)))
        assertEquals(2, log.undoDepth, "the drip takes no step of its own")
        assertEquals(listOf(2L, 3L), assertNotNull(log.undo()).changes.map { it.key })
        assertNotNull(log.redo())

        // After an undo there is something to redo, so the water takes a fresh step and redo is gone.
        log.undo()
        MediaStores.recordWater(log, listOf(tile("w0", 4L, null, 40)))
        assertFalse(log.canRedo, "a new mark after undo discards redo")
        assertEquals(listOf(4L), assertNotNull(log.undo()).changes.map { it.key })

        // After a layer change the water takes a fresh step; the rest of the episode joins it.
        log.push(UndoLog.Step(listOf(tile("p0", 5L, null, 50))))
        log.push(UndoLog.Step(emptyList(), stackBefore = stack, stackAfter = stack.add("l2")))
        log.undo()
        assertNull(log.newestExtendable(), "with redo pending nothing is extendable")
        log.redo()
        MediaStores.recordWater(log, listOf(tile("w0", 6L, null, 60)))
        val depthAfterFresh = log.undoDepth
        MediaStores.recordWater(log, listOf(tile("w1", 6L, null, 61)))
        assertEquals(depthAfterFresh, log.undoDepth, "the rest of the episode joins the water step")
        assertTrue(log.newestExtendable() != null, "a media step with nothing to redo is extendable")

        // An empty drip list is a no-op, and re-snapshotting a tile of the top step is refused loudly.
        val depthBeforeEmpty = log.undoDepth
        MediaStores.recordWater(log, emptyList())
        assertEquals(depthBeforeEmpty, log.undoDepth, "empty water records nothing")
        assertFailsWith<IllegalArgumentException> {
            MediaStores.recordWater(log, listOf(tile("w0", 6L, 60, 62)))
        }
    }

    @Test
    fun capacityTrimmingKeepsNewestAndClearReleasesExactlyOnce() {
        val released = ArrayList<Int>()
        val log = UndoLog<Int>(budgetBytes = 2, sizeOf = { 1L }, release = { released.add(it) })
        fun step(before: Int?, after: Int) = UndoLog.Step(listOf(UndoLog.TileChange("L", 1L, before, after)))

        log.push(step(null, 1))
        log.push(step(1, 2))
        log.push(step(2, 3))
        log.push(step(3, 4))
        // Befores held are null,1,2,3 = 3 bytes over budget 2: the two oldest steps go, releasing
        // nothing for the null before and handle 1; the newest step always survives.
        assertEquals(listOf(1), released)
        assertEquals(2, log.undoDepth)
        assertEquals(2L, log.heldBytes)
        assertEquals(4, assertNotNull(log.undo()).changes.single().after, "the newest step is kept even over budget")
        assertNotNull(log.redo())

        log.clear()
        assertEquals(0, log.undoDepth)
        assertFalse(log.canUndo)
        assertFalse(log.canRedo)
        assertNull(log.undo())
        assertNull(log.redo())
        assertEquals(setOf(1, 2, 3), released.toSet(), "clear releases the surviving undo befores: $released")
        assertEquals(released.size, released.toSet().size, "every tile released exactly once: $released")
    }
}
