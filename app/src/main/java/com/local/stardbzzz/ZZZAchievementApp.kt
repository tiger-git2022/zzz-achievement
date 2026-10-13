package com.local.stardbzzz

import android.app.Application
import com.local.stardbzzz.data.AppContainer

class ZZZAchievementApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
