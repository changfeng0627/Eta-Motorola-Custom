package fuck.andes.agent.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Browser session manager.
 * Responsible for WebView creation, attachment/detachment to containers,
 * page navigation, history, screenshots, JavaScript evaluation, etc.
 */
class AgentBrowserSession(
    private val appContext: Context,
) {
    companion object {
        private const val TAG = "AgentBrowserSession"
        private const val JS_INTERFACE_NAME = "AgentBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewCache = ConcurrentHashMap<String, WebView>()

    private val _snapshot = MutableStateFlow(BrowserSessionSnapshot())
    val snapshot: StateFlow<BrowserSessionSnapshot> = _snapshot.asStateFlow()

    /**
     * Browser session snapshot state.
     */
    data class BrowserSessionSnapshot(
        val currentUrl: String? = null,
        val title: String? = null,
        val isLoading: Boolean = false,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val progress: Int = 0,
        val lastScreenshot: Bitmap? = null,
        val lastError: String? = null,
    )

    /**
     * Create or get a WebView for the given session ID.
     */
    fun getOrCreateWebView(sessionId: String, container: ViewGroup? = null): WebView {
        return webViewCache.getOrPut(sessionId) {
            createWebView(container)
        }
    }

    /**
     * Create a new WebView with default settings.
     */
    private fun createWebView(container: ViewGroup?): WebView {
        val webView = WebView(appContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                defaultTextEncodingName = "UTF-8"
            }

            webViewClient = BrowserClient()
            webChromeClient = BrowserChromeClient()

            addJavascriptInterface(AgentBridge(), JS_INTERFACE_NAME)
        }

        container?.addView(webView)
        return webView
    }

    /**
     * Navigate to a URL.
     */
    fun navigate(sessionId: String, url: String) {
        val webView = webViewCache[sessionId] ?: return
        mainHandler.post {
            webView.loadUrl(url)
        }
    }

    /**
     * Go back in history.
     */
    fun goBack(sessionId: String) {
        val webView = webViewCache[sessionId] ?: return
        mainHandler.post {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    /**
     * Go forward in history.
     */
    fun goForward(sessionId: String) {
        val webView = webViewCache[sessionId] ?: return
        mainHandler.post {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
    }

    /**
     * Reload the current page.
     */
    fun reload(sessionId: String) {
        val webView = webViewCache[sessionId] ?: return
        mainHandler.post {
            webView.reload()
        }
    }

    /**
     * Stop loading.
     */
    fun stopLoading(sessionId: String) {
        val webView = webViewCache[sessionId] ?: return
        mainHandler.post {
            webView.stopLoading()
        }
    }

    /**
     * Take a screenshot of the current page.
     */
    fun takeScreenshot(sessionId: String, callback: (Bitmap?) -> Unit) {
        val webView = webViewCache[sessionId] ?: return
        mainHandler.post {
            webView.drawToBitmap()?.let { bitmap ->
                _snapshot.update { it.copy(lastScreenshot = bitmap) }
                callback(bitmap)
            } ?: callback(null)
        }
    }

    /**
     * Execute JavaScript code.
     */
    fun evaluateJavascript(sessionId: String, script: String, callback: ((String?) -> Unit)? = null) {
        val webView = webViewCache[sessionId] ?: return
        mainHandler.post {
            webView.evaluateJavascript(script) { result ->
                callback?.invoke(result)
            }
        }
    }

    /**
     * Destroy a session and release resources.
     */
    fun destroySession(sessionId: String) {
        val webView = webViewCache.remove(sessionId) ?: return
        mainHandler.post {
            webView.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
        }
    }

    /**
     * Destroy all sessions.
     */
    fun destroyAll() {
        webViewCache.keys.toList().forEach { destroySession(it) }
    }

    /**
     * JavaScript bridge interface.
     */
    inner class AgentBridge {
        @android.webkit.JavascriptInterface
        fun reportPageInfo(json: String) {
            try {
                val obj = JSONObject(json)
                _snapshot.update { snapshot ->
                    snapshot.copy(
                        title = obj.optString("title"),
                        currentUrl = obj.optString("url")
                    )
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        @android.webkit.JavascriptInterface
        fun reportError(error: String) {
            _snapshot.update { it.copy(lastError = error) }
        }
    }

    /**
     * WebView client implementation.
     */
    private inner class BrowserClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            _snapshot.update {
                it.copy(
                    isLoading = true,
                    currentUrl = url,
                    lastError = null
                )
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            _snapshot.update {
                it.copy(
                    isLoading = false,
                    currentUrl = url,
                    canGoBack = view?.canGoBack() ?: false,
                    canGoForward = view?.canGoForward() ?: false,
                    progress = 100
                )
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                _snapshot.update {
                    it.copy(
                        lastError = error?.description?.toString() ?: "Unknown error",
                        isLoading = false
                    )
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // For demo purposes, proceed. In production, you should handle this properly.
            handler?.proceed()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // Allow all URL loading within the WebView
            return false
        }
    }

    /**
     * WebChrome client implementation.
     */
    private inner class BrowserChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            _snapshot.update { it.copy(progress = newProgress) }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            _snapshot.update { it.copy(title = title) }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            // Log console messages for debugging
            consoleMessage?.let {
                android.util.Log.d(TAG, "JS Console: ${it.message()} [${it.sourceId()}:${it.lineNumber()}]")
            }
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            // Auto-dismiss JS alerts
            result?.confirm()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            // Auto-dismiss JS prompts with default value
            result?.confirm(defaultValue ?: "")
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            // Auto-confirm JS confirms
            result?.confirm()
            return true
        }
    }

    /**
     * Extension function to draw WebView to Bitmap.
     */
    private fun WebView.drawToBitmap(): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
