package de.firewebkiosk

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_URL = "last_url"
        private const val DEFAULT_URL = "https://example.com"

        // GitHub: immer neuestes Release prüfen
        private const val GITHUB_LATEST_API =
            "https://api.github.com/repos/hbtdul/FireTV-Kiosk/releases/latest"

        // Download: immer über deinen stabilen Link
        private const val UPDATE_APK_URL =
            "https://intern.tanzen-ulm.de/intern/programme/firekiosk/latest.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bildschirm wach halten
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
                return false
            }
        }

        val saved = prefs.getString(PREF_URL, null)
        if (saved.isNullOrBlank()) {
            askForUrlAndLoad(initial = true)
        } else {
            loadUrl(saved)
        }

        // Update-Check beim Start
        checkForUpdate()
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

    // Fernbedienung:
    // - Zurück = WebView zurück
    // - Options (☰) = URL ändern
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            askForUrlAndLoad(initial = false)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // -------------------------
    // Update-Funktion
    // -------------------------

    private fun checkForUpdate() {
        Thread {
            try {
                val conn = URL(GITHUB_LATEST_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")

                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonText)

                val tag = json.getString("tag_name") // z.B. "v1.2"
                val remote = tag.removePrefix("v").trim()
                val local = BuildConfig.VERSION_NAME.trim()

                if (isRemoteNewer(remote, local)) {
                    runOnUiThread {
                        showUpdateDialog(UPDATE_APK_URL, "Neue Version: $tag")
                    }
                }
            } catch (_: Exception) {
                // offline/timeout -> ignorieren
            }
        }.start()
    }

    private fun isRemoteNewer(remote: String, local: String): Boolean {
        fun parse(v: String): List<Int> = v.split(".", "-", "_").mapNotNull { it.toIntOrNull() }

        val r = parse(remote)
        val l = parse(local)
        val n = maxOf(r.size, l.size)

        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun showUpdateDialog(apkUrl: String, notes: String) {
        AlertDialog.Builder(this)
            .setTitle("Update verfügbar")
            .setMessage("$notes\n\nJetzt installieren?")
            .setPositiveButton("Installieren") { _, _ ->
                downloadAndInstallApk(apkUrl)
            }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        Thread {
            try {
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val outFile = File(cacheDir, "update.apk")
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                runOnUiThread { promptInstall(outFile) }
            } catch (_: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Update fehlgeschlagen")
                        .setMessage("Konnte die APK nicht laden.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun promptInstall(apkFile: File) {
        // Ab Android O braucht man ggf. Erlaubnis zum Installieren unbekannter Apps
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
