/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection

@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
@Suppress("OVERRIDE_DEPRECATION")
class ExportHtmlPreviewView(context: Context) : WebView(context) {
    @Volatile private var files: Map<String, File> = emptyMap()
    @Volatile private var page = ByteArray(0)
    private var revision = 0L

    init {
        setBackgroundColor(Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(true)
        }
        // URL-based callbacks keep local asset loading working on API 14.
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !localUrl(url)

            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse {
                val path = if (localUrl(url)) Uri.parse(url).path.orEmpty().removePrefix("/") else ""
                if (path == "messages.html") return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(page)).also { response ->
                    if (Build.VERSION.SDK_INT >= 21) response.responseHeaders = mapOf("Cache-Control" to "no-store")
                }
                val file = files[path]
                if (file == null) {
                    Timber.w("ExportTrace HTML preview blocked resource local=%s knownAssets=%d", localUrl(url), files.size)
                    return emptyResponse()
                }
                return try {
                    WebResourceResponse(URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream", null, file.inputStream())
                } catch (failure: Exception) {
                    Timber.w(failure, "ExportTrace HTML preview asset unavailable")
                    emptyResponse()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                Timber.d("ExportTrace HTML preview page ready assets=%d htmlBytes=%d width=%d height=%d visible=%s", files.size, page.size, width, height, isShown)
                view.loadUrl("javascript:(function(){var m=document.querySelectorAll('.layout'),visible=0;for(var i=0;i<m.length;i++){if(m[i].clientWidth)visible++;}console.log('ExportTrace HTML DOM '+JSON.stringify({viewport:window.innerWidth,height:document.documentElement.scrollHeight,layouts:m.length,visible:visible,text:document.querySelectorAll('.text').length,artwork:document.querySelectorAll('.drawing').length,rows:document.querySelectorAll('article').length}));})()")
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                Timber.w("ExportTrace HTML preview load failed code=%d", errorCode)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Timber.w("ExportTrace HTML script error line=%d message=%s", message.lineNumber(), message.message())
                else Timber.d("ExportTrace HTML console %s", message.message())
                return true
            }
        }
    }

    override fun onResume() {
        // Widget screens can pause the process-wide WebView timers before opening this editor.
        resumeTimers()
        super.onResume()
        Timber.d("ExportTrace HTML preview resumed")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Timber.d("ExportTrace HTML preview size width=%d height=%d", w, h)
    }

    fun load(directory: File, html: String, paths: List<String>) {
        val root = directory.canonicalPath + File.separator
        files = paths.distinct().mapNotNull { path ->
            val file = File(directory, path).canonicalFile
            if (file.path.startsWith(root) && file.isFile) path to file else null
        }.toMap()
        page = html.toByteArray(Charsets.UTF_8)
        revision++
        Timber.d("ExportTrace HTML preview loading revision=%d bytes=%d assets=%d width=%d height=%d", revision, page.size, files.size, width, height)
        resumeTimers()
        loadUrl(ORIGIN + "messages.html?revision=" + revision)
    }

    private fun localUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && uri.authority == "voyage-export.invalid"
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))

    companion object {
        private const val ORIGIN = "https://voyage-export.invalid/"
    }
}
