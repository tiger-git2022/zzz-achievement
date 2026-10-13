package com.local.stardbzzz.data.network

import com.local.stardbzzz.data.local.SecureCredentialStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class StardbNetwork(private val credentialStore: SecureCredentialStore) {
    val api: StardbApi by lazy {
        Retrofit.Builder()
            .baseUrl(BaseUrl)
            .client(client())
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(StardbApi::class.java)
    }

    private fun client(): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .addInterceptor { chain ->
                val token = credentialStore.authToken()
                val sessionCookie = credentialStore.sessionCookie()
                val requestBuilder = chain.request().newBuilder()
                    .header("User-Agent", BrowserUserAgent)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("DNT", "1")
                    .header("Sec-CH-UA", "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"")
                    .header("Sec-CH-UA-Mobile", "?0")
                    .header("Sec-CH-UA-Platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Priority", "u=1, i")

                if (!sessionCookie.isNullOrBlank()) {
                    requestBuilder.header("Cookie", sessionCookie)
                }

                val request = if (token.isNullOrBlank()) {
                    requestBuilder.build()
                } else {
                    requestBuilder
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private class InMemoryCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return

            val hostCookies = this.cookies.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { newCookie ->
                hostCookies.removeAll { existing ->
                    existing.name == newCookie.name &&
                        existing.domain == newCookie.domain &&
                        existing.path == newCookie.path
                }
                hostCookies += newCookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookies[url.host].orEmpty()
                .filter { it.expiresAt > now && it.matches(url) }
        }
    }

    private companion object {
        const val BaseUrl = "https://stardb.gg/"
        const val BrowserUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}
