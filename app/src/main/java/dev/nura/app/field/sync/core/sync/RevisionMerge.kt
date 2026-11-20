package dev.nura.app.field.sync.core.sync

enum class MergeDecision {
    /** No local copy: store the remote revision. */
    INSERT,

    /** The remote revision was derived from the local one: take it. */
    ACCEPT,

    /** The local revision already includes the remote one (duplicate, echo or stale delivery). */
    IGNORE,

    /** Both sides changed the record independently. */
    CONFLICT,
}

/**
 * Decides how to merge a remote revision using version counters rather than device clocks.
 *
 * Each revision carries `version` (incremented on every edit) and `baseVersion` (the highest
 * peer revision it has integrated). A revision includes another when its base is at least the
 * other's version. Neither including the other means concurrent edits.
 *
 * After merging, callers set:
 * - ACCEPT: version = base = remote version;
 * - CONFLICT with identical content: version = base = max(local, remote) (both sides converge);
 * - CONFLICT resolved to a winner: version = max + 1, base = max, so the result includes both.
 */
object RevisionMerge {

    fun decide(localVersion: Long?, localBase: Long, remoteVersion: Long, remoteBase: Long): MergeDecision = when {
        localVersion == null -> MergeDecision.INSERT
        localBase >= remoteVersion -> MergeDecision.IGNORE
        remoteBase >= localVersion -> MergeDecision.ACCEPT
        else -> MergeDecision.CONFLICT
    }
}
