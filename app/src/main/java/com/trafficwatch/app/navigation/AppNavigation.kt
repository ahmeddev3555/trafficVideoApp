package com.trafficwatch.app.navigation

import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.feature.auth.LoginScreen
import com.trafficwatch.app.feature.auth.RegisterScreen
import com.trafficwatch.app.feature.camera.CameraScreen
import com.trafficwatch.app.feature.history.HistoryScreen
import com.trafficwatch.app.feature.history.ReportDetailScreen
import com.trafficwatch.app.feature.permissions.PermissionsScreen
import com.trafficwatch.app.feature.review.ReviewScreen
import com.trafficwatch.app.feature.trim.TrimScreen
import com.trafficwatch.app.feature.upload.UploadScreen
import java.io.File

private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val PERMISSIONS = "permissions"
    const val CAMERA = "camera"
    const val TRIM = "trim"
    const val REVIEW = "review"
    const val UPLOAD = "upload"
    const val HISTORY = "history"
    const val REPORT_DETAIL = "report_detail/{reportId}"
    fun reportDetail(id: String) = "report_detail/$id"
}

@Composable
fun AppNavigation(
    authRepository: AuthRepository,
    reportRepository: ReportRepository
) {
    val navController = rememberNavController()
    val isLoggedIn by authRepository.isLoggedIn.collectAsState(initial = authRepository.getCurrentUser() != null)

    // Shared state threaded through the recording pipeline
    var rawVideoFile by rememberSaveable { mutableStateOf<String?>(null) }
    var trimmedVideoFile by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshotLocation by remember { mutableStateOf<LocationData?>(null) }
    var recordingStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var trimDurationMs by rememberSaveable { mutableLongStateOf(0L) }
    var permissionsNextRoute by rememberSaveable { mutableStateOf(Routes.HISTORY) }

    val startDestination = if (isLoggedIn) Routes.HISTORY else Routes.LOGIN

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.PERMISSIONS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Routes.REGISTER) }
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Routes.PERMISSIONS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onAllGranted = {
                    navController.navigate(permissionsNextRoute) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onNewReport = {
                    permissionsNextRoute = Routes.CAMERA
                    navController.navigate(Routes.PERMISSIONS)
                },
                onReportClick = { id -> navController.navigate(Routes.reportDetail(id)) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.REPORT_DETAIL, arguments = listOf(
            navArgument("reportId") { type = NavType.StringType }
        )) { backStack ->
            val reportId = backStack.arguments?.getString("reportId") ?: return@composable
            ReportDetailScreen(
                reportId = reportId,
                onNavigateBack = { navController.popBackStack() },
                getReport = { id -> reportRepository.getReport(id) }
            )
        }

        composable(Routes.CAMERA) {
            CameraScreen(
                onVideoRecorded = { file, location ->
                    rawVideoFile = file.absolutePath
                    snapshotLocation = location
                    recordingStartedAt = System.currentTimeMillis()
                    navController.navigate(Routes.TRIM)
                }
            )
        }

        composable(Routes.TRIM) {
            val raw = rawVideoFile ?: return@composable
            TrimScreen(
                rawVideoFile = File(raw),
                onTrimComplete = { file ->
                    trimmedVideoFile = file.absolutePath
                    navController.navigate(Routes.REVIEW)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.REVIEW) {
            val trimmed = trimmedVideoFile ?: return@composable

            // Extract duration once; remember so it doesn't re-run on recomposition
            val duration = remember(trimmed) {
                MediaMetadataRetriever().run {
                    setDataSource(trimmed)
                    val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    release()
                    ms
                }
            }
            trimDurationMs = duration

            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = { navController.navigate(Routes.UPLOAD) },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.UPLOAD) {
            val trimmed = trimmedVideoFile ?: return@composable
            UploadScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = trimDurationMs,
                onUploadSuccess = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetry = {
                    navController.navigate(Routes.UPLOAD) {
                        popUpTo(Routes.UPLOAD) { inclusive = true }
                    }
                }
            )
        }
    }
}
