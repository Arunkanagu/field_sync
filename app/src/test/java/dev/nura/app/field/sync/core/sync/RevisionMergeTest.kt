package dev.nura.app.field.sync.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Walks through the two-device scenarios from the project plan with (version, base) pairs.
 * `decide(localVersion, localBase, remoteVersion, remoteBase)`.
 */
class RevisionMergeTest {

    @Test
    fun unknownRecord_isInserted() {
        assertEquals(MergeDecision.INSERT, RevisionMerge.decide(null, 0, remoteVersion = 1, remoteBase = 0))
    }

    @Test
    fun sameBatchDeliveredTwice_isIgnored() {
        // After accepting v1, the receiver stores (1, 1). A second delivery of v1 changes nothing.
        assertEquals(MergeDecision.IGNORE, RevisionMerge.decide(1, 1, remoteVersion = 1, remoteBase = 0))
    }

    @Test
    fun editOnTopOfReceivedRevision_isAccepted() {
        // A created v1 (1, 0) and sent it; B edited to (2, 1). A sees a fast-forward.
        assertEquals(MergeDecision.ACCEPT, RevisionMerge.decide(1, 0, remoteVersion = 2, remoteBase = 1))
    }

    @Test
    fun echoOfOwnRevision_isAcceptedAsNoOp() {
        // B echoes A's v1 back with base 1; content is identical so callers only update the base.
        assertEquals(MergeDecision.ACCEPT, RevisionMerge.decide(1, 0, remoteVersion = 1, remoteBase = 1))
    }

    @Test
    fun concurrentEdits_areConflicts_onBothSides() {
        // Both had v1. A edited to (2, 0) locally; B edited to (2, 1).
        assertEquals(MergeDecision.CONFLICT, RevisionMerge.decide(2, 0, remoteVersion = 2, remoteBase = 1))
        assertEquals(MergeDecision.CONFLICT, RevisionMerge.decide(2, 1, remoteVersion = 2, remoteBase = 0))
    }

    @Test
    fun moreEditsOnOneSide_doNotSilentlyWin() {
        // A edited twice (3, 1) while B edited once (2, 1): still concurrent, not "higher version wins".
        assertEquals(MergeDecision.CONFLICT, RevisionMerge.decide(2, 1, remoteVersion = 3, remoteBase = 1))
    }

    @Test
    fun resolvedRevision_fastForwardsTheOtherSide() {
        // Conflict between versions 2 and 2 resolved to (3, 2); the other side still has (2, 1).
        assertEquals(MergeDecision.ACCEPT, RevisionMerge.decide(2, 1, remoteVersion = 3, remoteBase = 2))
    }

    @Test
    fun staleRevision_isIgnored() {
        assertEquals(MergeDecision.IGNORE, RevisionMerge.decide(4, 3, remoteVersion = 2, remoteBase = 1))
    }
}
