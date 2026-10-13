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
import com.local.stardbzzz.ui.theme.ZZZAchievementTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as ZZZAchievementApp).container.repository
        setContent {
            ZZZAchievementTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: AchievementViewModel = viewModel(
                        factory = AchievementViewModelFactory(repository),
                    )
                    AchievementApp(viewModel = viewModel)
                }
            }
        }
    }
}
