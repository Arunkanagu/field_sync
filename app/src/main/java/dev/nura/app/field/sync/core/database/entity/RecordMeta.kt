package dev.nura.app.field.sync.core.database.entity

/**
 * Sync metadata embedded in every synchronized record.
 *
 * - [version] increases on every edit, on any device.
 * - [baseVersion] is the highest revision received from a peer that this revision includes.
 *   Together they let [dev.nura.app.field.sync.core.sync.RevisionMerge] tell fast-forwards
 *   apart from concurrent edits without trusting device clocks.
 * - [changeSeq] is a device-local, monotonically increasing sequence used for incremental sync.
 *   It never leaves the device.
 * - [deviceId] is the device that produced this revision.
 */
data class RecordMeta(
    val createdAt: Long,
    val updatedAt: Long,
    val createdBy: String,
    val updatedBy: String,
    val deviceId: String,
    val version: Long,
    val baseVersion: Long,
    val isDeleted: Boolean,
    val changeSeq: Long,
)
