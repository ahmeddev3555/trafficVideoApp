package com.trafficwatch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.navigation.AppNavigation
import com.trafficwatch.app.ui.theme.TrafficWatchTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var reportRepository: ReportRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TrafficWatchTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavigation(
                        authRepository = authRepository,
                        reportRepository = reportRepository
                    )
                }
            }
        }
    }
}
