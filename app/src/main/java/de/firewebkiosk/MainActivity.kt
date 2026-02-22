package de.firewebkiosk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_URL = "last_url"
        private const val DEFAULT_URL = "https://example.com"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bildschirm wach halten (verhindert Standby, solange App im Vordergrund ist)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Bleibe im WebView
                return false
            }
        }

        val saved = prefs.getString(PREF_URL, null)
        if (saved.isNullOrBlank()) {
            askForUrlAndLoad(initial = true)
        } else {
            loadUrl(saved)
        }
    }

    private fun normalizeUrl(input: String): String {
        val t = input.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        return "https://$t"
    }

    private fun loadUrl(url: String) {
        val normalized = normalizeUrl(url)
        prefs.edit().putString(PREF_URL, normalized).apply()
        webView.loadUrl(normalized)
    }

    private fun askForUrlAndLoad(initial: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(PREF_URL, DEFAULT_URL) ?: DEFAULT_URL)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(if (initial) "URL eingeben" else "URL ändern")
            .setMessage("Welche Webseite soll angezeigt werden?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val url = input.text?.toString().orEmpty()
                if (url.isBlank()) {
                    askForUrlAndLoad(initial)
                } else {
                    loadUrl(url)
                }
            }
            .setNegativeButton(if (initial) "Abbrechen" else "Zurück") { _, _ ->
                if (initial) {
                    // Falls beim ersten Start abgebrochen wird, lade Default
                    loadUrl(DEFAULT_URL)
                }
            }
            .show()
    }

    // Fernbedienung:
    // - Zurück = WebView zurück
    // - Options (☰) = URL ändern
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {

        // Zurück-Taste → im WebView zurück
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }

        // Options-Taste (☰) → URL ändern
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            askForUrlAndLoad(initial = false)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // Backup: langer Druck auf Play/Pause → URL ändern (kannst du löschen, wenn du nur Options willst)
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            askForUrlAndLoad(initial = false)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
