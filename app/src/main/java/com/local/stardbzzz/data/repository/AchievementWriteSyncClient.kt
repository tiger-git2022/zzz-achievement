package com.local.stardbzzz.data.repository

interface AchievementWriteSyncClient {
    suspend fun fetchTrackerPageHtml(): String

    suspend fun syncCompletion(id: String, completed: Boolean): AchievementWriteSyncResult

    suspend fun close() = Unit
}

class AuthenticationExpiredException(
    message: String = "登录状态已失效，请重新登录",
) : IllegalStateException(message)

data class AchievementWriteSyncResult(
    val status: Int,
    val statusText: String,
    val body: String,
) {
    val isSuccessful: Boolean = status in 200..299

    fun errorMessage(action: String): String {
        val cleanBody = body.trim().take(500)
        return if (cleanBody.isBlank()) {
            "$action failed: HTTP $status $statusText"
        } else {
            "$action failed: HTTP $status $statusText: $cleanBody"
        }
    }
}
