package com.local.stardbzzz.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.local.stardbzzz.data.repository.AchievementWriteSyncClient
import com.local.stardbzzz.data.repository.AchievementWriteSyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

class WebViewAchievementWriteSyncClient(
    context: Context,
) : AchievementWriteSyncClient {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var trackerPageLoaded = false
    private var pendingLoad: CompletableDeferred<Unit>? = null
    private var pendingPageResult: PendingResult<String>? = null
    private var pendingSyncResult: PendingResult<AchievementWriteSyncResult>? = null

    override suspend fun fetchTrackerPageHtml(): String =
        mutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                withTimeout(30_000) {
                    ensureTrackerPageLoaded()
                    executeTrackerDataFetch()
                }
            }
        }

    override suspend fun syncCompletion(id: String, completed: Boolean): AchievementWriteSyncResult =
        mutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                withTimeout(30_000) {
                    ensureTrackerPageLoaded()
                    executeCompletionFetch(id = id, completed = completed)
                }
            }
        }

    override suspend fun close() {
        mutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                pendingLoad?.cancel()
                pendingPageResult?.deferred?.cancel()
                pendingSyncResult?.deferred?.cancel()
                pendingLoad = null
                pendingPageResult = null
                pendingSyncResult = null
                trackerPageLoaded = false
                webView?.stopLoading()
                webView?.removeJavascriptInterface(BridgeName)
                webView?.destroy()
                webView = null
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        val existing = webView
        if (existing != null) return existing

        val created = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), BridgeName)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    trackerPageLoaded = url.startsWith(TrackerUrl)
                    pendingLoad?.takeIf { !it.isCompleted }?.complete(Unit)
                    pendingLoad = null
                }
            }
        }
        webView = created
        return created
    }

    private suspend fun ensureTrackerPageLoaded() {
        val view = ensureWebView()
        if (trackerPageLoaded && view.url?.startsWith(TrackerUrl) == true) return

        val load = CompletableDeferred<Unit>()
        pendingLoad = load
        view.loadUrl(TrackerUrl)
        load.await()
    }

    private suspend fun executeTrackerDataFetch(): String {
        val result = CompletableDeferred<String>()
        val callbackId = UUID.randomUUID().toString()
        pendingPageResult = PendingResult(callbackId, result)
        return try {
            ensureWebView().evaluateJavascript(pageFetchScript(callbackId), null)
            result.await()
        } finally {
            pendingPageResult = null
        }
    }

    private suspend fun executeCompletionFetch(id: String, completed: Boolean): AchievementWriteSyncResult {
        val result = CompletableDeferred<AchievementWriteSyncResult>()
        val callbackId = UUID.randomUUID().toString()
        pendingSyncResult = PendingResult(callbackId, result)
        return try {
            ensureWebView().evaluateJavascript(syncScript(callbackId, id, completed), null)
            result.await()
        } finally {
            pendingSyncResult = null
        }
    }

    private fun parseResult(json: String): AchievementWriteSyncResult {
        return runCatching {
            val data = JSONObject(json)
            AchievementWriteSyncResult(
                status = data.optInt("status", 0),
                statusText = data.optString("statusText", "Unknown"),
                body = data.optString("body", ""),
            )
        }.getOrElse { failure ->
            AchievementWriteSyncResult(
                status = 0,
                statusText = "Parse error",
                body = failure.message.orEmpty(),
            )
        }
    }

    private fun pageFetchScript(callbackId: String): String {
        return """
            (async function() {
              const callbackId = ${JSONObject.quote(callbackId)};
              const dataUrl = ${JSONObject.quote(DataUrl)};
              try {
                const dataResponse = await fetch(dataUrl, {
                  method: 'GET',
                  credentials: 'include',
                  headers: { 'accept': 'application/json,*/*;q=0.8' }
                });
                const dataBody = await dataResponse.text();
                if (dataResponse.status === 401 || dataResponse.status === 403) {
                  window.${BridgeName}.onPageResult(callbackId, JSON.stringify({
                    __stardbAuthExpired: true,
                    status: dataResponse.status,
                    body: dataBody || ''
                  }));
                  return;
                }
                if (dataResponse.ok && dataBody && dataBody.trim()) {
                  window.${BridgeName}.onPageResult(callbackId, dataBody);
                  return;
                }
              } catch (ignored) {
              }

              try {
                const response = await fetch(location.href, {
                  method: 'GET',
                  credentials: 'include',
                  headers: { 'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' }
                });
                const body = await response.text();
                if (response.status === 401 || response.status === 403) {
                  window.${BridgeName}.onPageResult(callbackId, JSON.stringify({
                    __stardbAuthExpired: true,
                    status: response.status,
                    body: body || ''
                  }));
                  return;
                }
                window.${BridgeName}.onPageResult(callbackId, body || '');
              } catch (error) {
                window.${BridgeName}.onPageResult(callbackId, document.documentElement.outerHTML || '');
              }
            })();
        """.trimIndent()
    }

    private fun syncScript(callbackId: String, id: String, completed: Boolean): String {
        val method = if (completed) "PUT" else "DELETE"
        val path = "/api/users/me/zzz/achievements/completed/${id}"
        return """
            (async function() {
              const callbackId = ${JSONObject.quote(callbackId)};
              try {
                const response = await fetch(${JSONObject.quote(path)}, {
                  method: ${JSONObject.quote(method)},
                  credentials: 'include',
                  headers: { 'accept': '*/*' }
                });
                const body = await response.text();
                window.${BridgeName}.onSyncResult(callbackId, JSON.stringify({
                  status: response.status,
                  statusText: response.statusText || '',
                  body: body || ''
                }));
              } catch (error) {
                window.${BridgeName}.onSyncResult(callbackId, JSON.stringify({
                  status: 0,
                  statusText: 'JavaScript error',
                  body: String(error && error.message ? error.message : error)
                }));
              }
            })();
        """.trimIndent()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onPageResult(id: String, body: String) {
            val pending = pendingPageResult ?: return
            if (id != pending.id || pending.deferred.isCompleted) return
            pending.deferred.complete(body)
        }

        @JavascriptInterface
        fun onSyncResult(id: String, json: String) {
            val pending = pendingSyncResult ?: return
            if (id != pending.id || pending.deferred.isCompleted) return
            pending.deferred.complete(parseResult(json))
        }
    }

    private data class PendingResult<T>(
        val id: String,
        val deferred: CompletableDeferred<T>,
    )

    private companion object {
        const val TrackerUrl = "https://stardb.gg/zh-cn/zzz/achievement-tracker"
        const val DataUrl = "/zh-cn/zzz/achievement-tracker/__data.json?x-sveltekit-invalidated=01"
        const val BridgeName = "StardbAndroid"
    }
}
