package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.io.ColdRecordedLoadPlan
import java.util.Collections

internal enum class ColdUploadFailurePhase {
    AFTER_GENERATE,
    AFTER_STORAGE,
    AFTER_UPLOAD,
    AFTER_SAVED_SCENE,
    AFTER_ROOT_BIND,
    AFTER_ROOT_BIND_WITH_RETIRE_FAILURE,
}

internal data class ColdUploadFailureTarget(
    val phase: ColdUploadFailurePhase,
    val uploadId: Int?,
) {
    init {
        require((phase in PER_UPLOAD_PHASES) == (uploadId != null)) {
            "Upload failure phases require an upload id; global phases do not"
        }
        require(uploadId == null || uploadId >= 0) { "Cold upload id must be nonnegative" }
    }

    private companion object {
        val PER_UPLOAD_PHASES = setOf(
            ColdUploadFailurePhase.AFTER_GENERATE,
            ColdUploadFailurePhase.AFTER_STORAGE,
            ColdUploadFailurePhase.AFTER_UPLOAD,
        )
    }
}

internal object ColdUploadFailureMatrix {
    private val perUploadPhases = listOf(
        ColdUploadFailurePhase.AFTER_GENERATE,
        ColdUploadFailurePhase.AFTER_STORAGE,
        ColdUploadFailurePhase.AFTER_UPLOAD,
    )

    /** Deterministic bounded fault points; contains no GL names or executable hooks. */
    fun targets(plan: ColdRecordedLoadPlan.Prepared): List<ColdUploadFailureTarget> {
        val uploads = plan.uploads
        require(uploads.all { it.id >= 0 } && uploads.map { it.id }.distinct().size == uploads.size) {
            "Cold upload plan has invalid or duplicate upload ids"
        }
        val firstByKind = ColdRecordedLoadPlan.Kind.values().associateWith { kind ->
            uploads.asSequence().filter { it.kind == kind }.minByOrNull { it.id }
                ?: error("Cold upload plan is missing category $kind")
        }

        val result = ArrayList<ColdUploadFailureTarget>(21)
        for (kind in ColdRecordedLoadPlan.Kind.values()) {
            val uploadId = firstByKind.getValue(kind).id
            for (phase in perUploadPhases) result.add(ColdUploadFailureTarget(phase, uploadId))
        }
        result.add(ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_SAVED_SCENE, null))
        result.add(ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_ROOT_BIND, null))
        result.add(ColdUploadFailureTarget(ColdUploadFailurePhase.AFTER_ROOT_BIND_WITH_RETIRE_FAILURE, null))
        check(result.size == 21 && result.toSet().size == result.size) {
            "Cold upload failure matrix must contain exactly 21 unique targets"
        }
        return Collections.unmodifiableList(result)
    }
}
