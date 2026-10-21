package com.local.stardbzzz.data.network

import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface StardbApi {
    @GET("{locale}/login/__data.json")
    suspend fun loginPageData(
        @Path("locale") locale: String = "zh-cn",
        @Query("x-sveltekit-invalidated") invalidated: String = "01",
    ): ResponseBody

    @FormUrlEncoded
    @Headers(
        "Accept: application/json",
        "Origin: https://stardb.gg",
        "Referer: https://stardb.gg/zh-cn/login",
        "x-sveltekit-action: true",
    )
    @POST("{locale}/login")
    suspend fun login(
        @Path("locale") locale: String = "zh-cn",
        @Field("username") username: String,
        @Field("password") password: String,
    ): Response<ResponseBody>

    @Headers("Accept: */*", "Referer: https://stardb.gg/zh-cn/login")
    @GET("{locale}/__data.json")
    suspend fun homeData(
        @Path("locale") locale: String = "zh-cn",
        @Query("x-sveltekit-trailing-slash") trailingSlash: String = "1",
        @Query("x-sveltekit-invalidated") invalidated: String = "10",
    ): ResponseBody

    @Headers("Accept: */*", "Referer: https://stardb.gg/zh-cn/zzz/achievement-tracker")
    @GET("{locale}/zzz/achievement-tracker/__data.json")
    suspend fun achievementTrackerData(
        @Path("locale") locale: String = "zh-cn",
        @Query("x-sveltekit-invalidated") invalidated: String = "01",
    ): ResponseBody

    @Headers(
        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer: https://stardb.gg/zh-cn",
    )
    @GET("{locale}/zzz/achievement-tracker")
    suspend fun achievementTrackerPage(
        @Path("locale") locale: String = "zh-cn",
    ): ResponseBody

    @Headers(
        "Accept: */*",
        "Origin: https://stardb.gg",
        "Referer: https://stardb.gg/zh-cn/zzz/achievement-tracker",
    )
    @PUT("api/users/me/zzz/achievements/completed/{achievementId}")
    suspend fun completeAchievement(
        @Path("achievementId") achievementId: String,
        @Body body: RequestBody = ByteArray(0).toRequestBody(null),
    ): Response<ResponseBody>

    @Headers(
        "Accept: */*",
        "Origin: https://stardb.gg",
        "Referer: https://stardb.gg/zh-cn/zzz/achievement-tracker",
    )
    @DELETE("api/users/me/zzz/achievements/completed/{achievementId}")
    suspend fun uncompleteAchievement(
        @Path("achievementId") achievementId: String,
    ): Response<ResponseBody>
}

data class AchievementDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val primaryCategory: String? = null,
    val category: String? = null,
    val reward: String? = null,
    val version: String? = null,
    val displayOrder: Int = 0,
    val completed: Boolean = false,
    /**
     * Not completed itself, but another member of its stardb group is: a group lists alternative story-branch
     * achievements and its `complete` names the one completed. Counted for the scan report.
     */
    val completedViaGroup: Boolean = false,
    /**
     * The stardb group of alternative achievements this one belongs to, named by the group's first member id;
     * blank for a group of one. The game counts a group as one achievement, done once any member is.
     */
    val group: String = "",
    /** Share of stardb users who completed it (0..1); null when stardb didn't say. Orders 快速同步's categories. */
    val percent: Double? = null,
)
