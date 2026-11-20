package dev.nura.app.field.sync.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.nura.app.field.sync.core.database.AppDatabase
import dev.nura.app.field.sync.core.database.RoomTransactionRunner
import dev.nura.app.field.sync.core.database.TransactionRunner
import dev.nura.app.field.sync.core.database.dao.AttendanceDao
import dev.nura.app.field.sync.core.database.dao.AuditDao
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.dao.JobDao
import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.dao.UserDao
import dev.nura.app.field.sync.core.security.KeyWrapper
import dev.nura.app.field.sync.core.security.KeystoreKeyWrapper
import dev.nura.app.field.sync.core.serialization.GsonProvider
import dev.nura.app.field.sync.data.repository.AttendanceRepositoryImpl
import dev.nura.app.field.sync.data.repository.EmployeeRepositoryImpl
import dev.nura.app.field.sync.data.repository.JobRepositoryImpl
import dev.nura.app.field.sync.data.repository.UserRepositoryImpl
import dev.nura.app.field.sync.domain.repository.AttendanceRepository
import dev.nura.app.field.sync.domain.repository.EmployeeRepository
import dev.nura.app.field.sync.domain.repository.JobRepository
import dev.nura.app.field.sync.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun provideUserDao(database: AppDatabase): UserDao = database.userDao()

    @Provides
    fun provideEmployeeDao(database: AppDatabase): EmployeeDao = database.employeeDao()

    @Provides
    fun provideAttendanceDao(database: AppDatabase): AttendanceDao = database.attendanceDao()

    @Provides
    fun provideJobDao(database: AppDatabase): JobDao = database.jobDao()

    @Provides
    fun provideSyncDao(database: AppDatabase): SyncDao = database.syncDao()

    @Provides
    fun provideAuditDao(database: AppDatabase): AuditDao = database.auditDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("device_settings") }

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonProvider.create()

    @Provides
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    abstract fun bindEmployeeRepository(impl: EmployeeRepositoryImpl): EmployeeRepository

    @Binds
    abstract fun bindAttendanceRepository(impl: AttendanceRepositoryImpl): AttendanceRepository

    @Binds
    abstract fun bindJobRepository(impl: JobRepositoryImpl): JobRepository

    @Binds
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds
    abstract fun bindKeyWrapper(impl: KeystoreKeyWrapper): KeyWrapper
}
