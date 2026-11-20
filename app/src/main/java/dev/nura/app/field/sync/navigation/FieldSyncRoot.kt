package dev.nura.app.field.sync.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.User
import dev.nura.app.field.sync.domain.repository.UserRepository
import dev.nura.app.field.sync.feature.attendance.AttendanceScreen
import dev.nura.app.field.sync.feature.audit.AuditLogScreen
import dev.nura.app.field.sync.feature.auth.LoginScreen
import dev.nura.app.field.sync.feature.auth.SetupScreen
import dev.nura.app.field.sync.feature.dashboard.DashboardScreen
import dev.nura.app.field.sync.feature.employees.EmployeeEditScreen
import dev.nura.app.field.sync.feature.employees.EmployeesScreen
import dev.nura.app.field.sync.feature.jobs.JobEditScreen
import dev.nura.app.field.sync.feature.jobs.JobsScreen
import dev.nura.app.field.sync.feature.reports.ReportsScreen
import dev.nura.app.field.sync.feature.settings.SettingsScreen
import dev.nura.app.field.sync.feature.sync_history.ConflictsScreen
import dev.nura.app.field.sync.feature.sync_history.SyncHistoryScreen
import dev.nura.app.field.sync.feature.transfer.TransferScreen
import dev.nura.app.field.sync.feature.users.UsersScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface RootState {
    data object Loading : RootState
    data object NeedsSetup : RootState
    data object SignedOut : RootState
    data class SignedIn(val user: User) : RootState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    userRepository: UserRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    val state: StateFlow<RootState> = combine(
        userRepository.hasAnyUser,
        sessionManager.currentUser,
    ) { hasAnyUser, user ->
        when {
            user != null -> RootState.SignedIn(user)
            !hasAnyUser -> RootState.NeedsSetup
            else -> RootState.SignedOut
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootState.Loading)
}

/** Chooses between setup, login and the signed-in graph. Logging out discards the signed-in back stack. */
@Composable
fun FieldSyncRoot(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state) {
        RootState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        RootState.NeedsSetup -> SetupScreen()
        RootState.SignedOut -> LoginScreen()
        is RootState.SignedIn -> SignedInNavHost()
    }
}

@Composable
private fun SignedInNavHost() {
    val navController = rememberNavController()
    val back: () -> Unit = { navController.popBackStack() }
    val optionalId = listOf(
        navArgument(Routes.ARG_ID) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        },
    )
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(onNavigate = { route -> navController.navigate(route) })
        }
        composable(Routes.USERS) {
            UsersScreen(onBack = back)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back)
        }
        composable(Routes.EMPLOYEES) {
            EmployeesScreen(
                onBack = back,
                onOpenEmployee = { id -> navController.navigate(Routes.employeeEdit(id)) },
                onAddEmployee = { navController.navigate(Routes.employeeEdit()) },
            )
        }
        composable(Routes.EMPLOYEE_EDIT, arguments = optionalId) {
            EmployeeEditScreen(onDone = back)
        }
        composable(Routes.ATTENDANCE) {
            AttendanceScreen(onBack = back)
        }
        composable(Routes.JOBS) {
            JobsScreen(
                onBack = back,
                onCreateJob = { navController.navigate(Routes.jobEdit()) },
                onEditJob = { id -> navController.navigate(Routes.jobEdit(id)) },
            )
        }
        composable(Routes.JOB_EDIT, arguments = optionalId) {
            JobEditScreen(onDone = back)
        }
        composable(Routes.REPORTS) {
            ReportsScreen(onBack = back)
        }
        composable(Routes.TRANSFER) {
            TransferScreen(onBack = back, onOpenHistory = { navController.navigate(Routes.SYNC_HISTORY) })
        }
        composable(Routes.SYNC_HISTORY) {
            SyncHistoryScreen(onBack = back, onOpenConflicts = { navController.navigate(Routes.CONFLICTS) })
        }
        composable(Routes.CONFLICTS) {
            ConflictsScreen(onBack = back)
        }
        composable(Routes.AUDIT_LOG) {
            AuditLogScreen(onBack = back)
        }
    }
}
