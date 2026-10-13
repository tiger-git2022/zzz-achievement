package com.local.stardbzzz.data

import android.content.Context
import com.local.stardbzzz.data.local.AchievementDatabase
import com.local.stardbzzz.data.local.FilterPreferences
import com.local.stardbzzz.data.local.SecureCredentialStore
import com.local.stardbzzz.data.network.RngAchievementIconClient
import com.local.stardbzzz.data.network.StardbNetwork
import com.local.stardbzzz.data.network.WebViewAchievementWriteSyncClient
import com.local.stardbzzz.data.repository.AchievementRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AchievementDatabase.create(appContext)
    private val credentialStore = SecureCredentialStore(appContext)
    private val filterPreferences = FilterPreferences(appContext)
    private val network = StardbNetwork(credentialStore)
    private val writeSyncClient = WebViewAchievementWriteSyncClient(appContext)
    private val iconClient = RngAchievementIconClient(appContext)

    val repository = AchievementRepository(
        dao = database.achievementDao(),
        api = network.api,
        credentialStore = credentialStore,
        filterPreferences = filterPreferences,
        writeSyncClient = writeSyncClient,
        iconClient = iconClient,
    )
}
