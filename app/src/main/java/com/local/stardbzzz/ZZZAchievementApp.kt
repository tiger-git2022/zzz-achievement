package com.local.stardbzzz

import android.app.Application
import com.local.stardbzzz.data.AppContainer
import com.local.stardbzzz.ui.glass.GlassSettings

class ZZZAchievementApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        GlassSettings.init(this)
    }
}
