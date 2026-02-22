package de.firewebkiosk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_URL = "last_url"
        private const val DEFAULT_URL = "https://example.com"

        // mögliche Werte: auto, landscape, portrait, reverse_landscape, reverse_portrait
        private const val PREF_ORIENTATION = "orientation_mode"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bildschirm wach halten (verhindert Standby, solange App im Vordergrund ist)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Gespeicherte Orientierung anwenden (vor setContentView ist am saubersten)
        applySavedOrientation()

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
                if (initial) loadUrl(DEFAULT_URL)
            }
            .show()
    }

    // Fernbedienung: Zurück = WebView zurück; Options (☰) = Menü
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showOptionsMenu()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun showOptionsMenu() {
        val items = arrayOf(
            "URL ändern",
            "Rotation / Ausrichtung"
        )

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(buildMenuHeaderView())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askForUrlAndLoad(initial = false)
                    1 -> showOrientationMenu()
                }
            }
            .setNegativeButton("Schließen", null)
            .create()

        dialog.show()
    }

    private fun buildMenuHeaderView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 8)
        }

        val logo = ImageView(this).apply {
            // Logo muss unter app/src/main/res/drawable/tendance_logo.png liegen
            setImageResource(R.drawable.tendance_logo)
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { marginEnd = 24 }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val title = TextView(this).apply {
            text = "FireKiosk – Optionen"
            textSize = 20f
            setPadding(0, 30, 0, 0)
        }

        container.addView(logo)
        container.addView(title)
        return container
    }

    private fun showOrientationMenu() {
        val labels = arrayOf(
            "Auto (Standard)",
            "Landscape",
            "Portrait",
            "Reverse Landscape",
            "Reverse Portrait"
        )

        val values = arrayOf(
            "auto",
            "landscape",
            "portrait",
            "reverse_landscape",
            "reverse_portrait"
        )

        val current = prefs.getString(PREF_ORIENTATION, "auto") ?: "auto"
        val checkedIndex = values.indexOf(current).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("Rotation / Ausrichtung")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                prefs.edit().putString(PREF_ORIENTATION, values[which]).apply()
                applySavedOrientation()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun applySavedOrientation() {
        when (prefs.getString(PREF_ORIENTATION, "auto")) {
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "reverse_landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "reverse_portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED // Auto
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webView.isInitialized) {
            webView.destroy()
        }
    }
}
