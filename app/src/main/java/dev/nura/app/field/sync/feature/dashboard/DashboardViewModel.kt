package dev.nura.app.field.sync.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.device.DeviceSettingsRepository
import dev.nura.app.field.sync.core.sync.ConflictResolver
import dev.nura.app.field.sync.core.util.formatTimestamp
import dev.nura.app.field.sync.data.repository.SyncStatusRepository
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.AttendanceSummary
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.User
import dev.nura.app.field.sync.domain.repository.AttendanceRepository
import dev.nura.app.field.sync.domain.repository.EmployeeRepository
import dev.nura.app.field.sync.domain.repository.JobRepository
import dev.nura.app.field.sync.domain.repository.UserRepository
import dev.nura.app.field.sync.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Quick actions from the project plan (section 14). */
enum class QuickAction(val label: String, val permission: Permission?, val route: String) {
    ADD_EMPLOYEE("Add Employee", Permission.ADD_EMPLOYEES, Routes.employeeEdit()),
    EMPLOYEES("Employees", Permission.VIEW_EMPLOYEES, Routes.EMPLOYEES),
    ATTENDANCE("Attendance", Permission.MARK_ATTENDANCE, Routes.ATTENDANCE),
    ASSIGN_JOB("Assign Job", Permission.CREATE_JOBS, Routes.jobEdit()),
    JOBS("Jobs", Permission.UPDATE_JOB_PROGRESS, Routes.JOBS),
    REPORTS("Reports", Permission.VIEW_REPORTS, Routes.REPORTS),
    TRANSFER("Transfer", Permission.RECEIVE_DATA, Routes.TRANSFER),
    SYNC_HISTORY("Sync History", Permission.VIEW_TRANSFER_HISTORY, Routes.SYNC_HISTORY),
    MANAGE_USERS("Manage Users", Permission.MANAGE_USERS, Routes.USERS),
    AUDIT_LOG("Audit Log", Permission.MANAGE_USERS, Routes.AUDIT_LOG),
    SETTINGS("Settings", null, Routes.SETTINGS),
}

data class SummaryItem(val label: String, val value: String, val route: String? = null)

data class DashboardUiState(
    val user: User? = null,
    val deviceName: String = "",
    val deviceId: String? = null,
    val summary: List<SummaryItem> = emptyList(),
    val openConflicts: Int = 0,
    val quickActions: List<QuickAction> = emptyList(),
)

private data class Counts(
    val employees: Int,
    val attendance: AttendanceSummary,
    val activeJobs: Int,
    val pending: Int,
    val lastSync: Long?,
    val conflicts: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    sessionManager: SessionManager,
    deviceSettings: DeviceSettingsRepository,
    private val userRepository: UserRepository,
    employeeRepository: EmployeeRepository,
    attendanceRepository: AttendanceRepository,
    jobRepository: JobRepository,
    syncStatusRepository: SyncStatusRepository,
    conflictResolver: ConflictResolver,
) : ViewModel() {

    private val counts: Flow<Counts?> = combine(sessionManager.currentUser, deviceSettings.deviceRole) { user, role -> user to role }
        .flatMapLatest { (user, role) ->
            if (user == null) return@flatMapLatest flowOf(null)
            val deviceRole = role ?: user.role
            val attendance = attendanceRepository.observeSummary(attendanceRepository.today())
            combine(
                employeeRepository.observeActiveCount(),
                attendance,
                jobRepository.observeActiveCount(),
                syncStatusRepository.observePendingCount(deviceRole),
                combine(syncStatusRepository.observeLastSuccessfulSync(), conflictResolver.observeOpenCount(), ::Pair),
            ) { employees, today, jobs, pending, (lastSync, conflicts) ->
                Counts(employees, today, jobs, pending, lastSync, conflicts)
            }
        }
        .catch { emit(null) }

    val uiState: StateFlow<DashboardUiState> = combine(
        sessionManager.currentUser,
        deviceSettings.deviceName,
        deviceSettings.deviceId,
        counts,
    ) { user, deviceName, deviceId, counts ->
        DashboardUiState(
            user = user,
            deviceName = deviceName,
            deviceId = deviceId,
            summary = if (user != null && counts != null) summaryFor(user, counts) else emptyList(),
            openConflicts = counts?.conflicts ?: 0,
            quickActions = user?.let(::quickActionsFor).orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun logout() = userRepository.logout()

    private fun summaryFor(user: User, counts: Counts): List<SummaryItem> {
        val isAdmin = user.role == Role.ADMIN
        val today = counts.attendance
        val marked = today.present + today.halfDay + today.absent + today.leave
        return listOf(
            SummaryItem(if (isAdmin) "Employees" else "Assigned employees", counts.employees.toString(), Routes.EMPLOYEES),
            SummaryItem(
                "Today's attendance",
                "${today.present + today.halfDay}/${marked + today.unmarked}",
                Routes.ATTENDANCE,
            ),
            SummaryItem("Active jobs", counts.activeJobs.toString(), Routes.JOBS),
            SummaryItem(if (isAdmin) "Pending outbound changes" else "Pending updates to send", counts.pending.toString(), Routes.TRANSFER),
            SummaryItem(
                "Last successful sync",
                counts.lastSync?.let(::formatTimestamp) ?: "Never",
                Routes.SYNC_HISTORY,
            ),
        )
    }

    private fun quickActionsFor(user: User): List<QuickAction> {
        val candidates = when (user.role) {
            Role.ADMIN -> listOf(
                QuickAction.ADD_EMPLOYEE,
                QuickAction.EMPLOYEES,
                QuickAction.ATTENDANCE,
                QuickAction.ASSIGN_JOB,
                QuickAction.JOBS,
                QuickAction.REPORTS,
                QuickAction.TRANSFER,
                QuickAction.SYNC_HISTORY,
                QuickAction.MANAGE_USERS,
                QuickAction.AUDIT_LOG,
                QuickAction.SETTINGS,
            )
            Role.MANAGER -> listOf(
                QuickAction.EMPLOYEES,
                QuickAction.ATTENDANCE,
                QuickAction.JOBS,
                QuickAction.REPORTS,
                QuickAction.TRANSFER,
                QuickAction.SYNC_HISTORY,
                QuickAction.SETTINGS,
            )
        }
        return candidates.filter { action -> action.permission?.let(user::can) ?: true }
    }
}
