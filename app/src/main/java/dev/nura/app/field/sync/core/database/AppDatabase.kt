package dev.nura.app.field.sync.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import dev.nura.app.field.sync.core.database.dao.AttendanceDao
import dev.nura.app.field.sync.core.database.dao.AuditDao
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.dao.JobDao
import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.dao.UserDao
import dev.nura.app.field.sync.core.database.entity.AttendanceEntity
import dev.nura.app.field.sync.core.database.entity.AuditLogEntity
import dev.nura.app.field.sync.core.database.entity.ChangeSequenceEntity
import dev.nura.app.field.sync.core.database.entity.EmployeeEntity
import dev.nura.app.field.sync.core.database.entity.JobEntity
import dev.nura.app.field.sync.core.database.entity.ProcessedBatchEntity
import dev.nura.app.field.sync.core.database.entity.SyncConflictEntity
import dev.nura.app.field.sync.core.database.entity.SyncStateEntity
import dev.nura.app.field.sync.core.database.entity.TransferHistoryEntity
import dev.nura.app.field.sync.core.database.entity.TrustedDeviceEntity
import dev.nura.app.field.sync.core.database.entity.UserEntity

/**
 * Local database. Bump [version] and add a migration under `core/database/migration`
 * (or an [AutoMigration] when Room can derive it) for every schema change; never fall back
 * to destructive migration in production.
 *
 * Version 2 (Phases 2–5) adds business data, sync bookkeeping and audit tables.
 */
@Database(
    entities = [
        UserEntity::class,
        EmployeeEntity::class,
        AttendanceEntity::class,
        JobEntity::class,
        TrustedDeviceEntity::class,
        SyncStateEntity::class,
        TransferHistoryEntity::class,
        ProcessedBatchEntity::class,
        SyncConflictEntity::class,
        AuditLogEntity::class,
        ChangeSequenceEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun employeeDao(): EmployeeDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun jobDao(): JobDao
    abstract fun syncDao(): SyncDao
    abstract fun auditDao(): AuditDao

    companion object {
        const val NAME = "fieldsync.db"
    }
}
