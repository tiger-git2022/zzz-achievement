package com.local.stardbzzz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.stardbzzz.ui.AchievementApp
import com.local.stardbzzz.ui.AchievementViewModel
import com.local.stardbzzz.ui.AchievementViewModelFactory
import com.local.stardbzzz.ui.glass.GlassHost
import com.local.stardbzzz.ui.theme.ZZZAchievementTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ZZZAchievementApp).container
        val repository = container.repository
        if (savedInstanceState == null) {
            container.updater.checkAutomatically()
        }
        setContent {
            ZZZAchievementTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GlassHost {
                        val viewModel: AchievementViewModel = viewModel(
                            factory = AchievementViewModelFactory(repository),
                        )
                        AchievementApp(
                            viewModel = viewModel,
                            updater = container.updater,
                            scanCoordinator = container.scanCoordinator,
                        )
                    }
                }
            }
        }
    }
}
