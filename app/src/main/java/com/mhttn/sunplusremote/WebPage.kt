package com.mhttn.sunplusremote

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.net.URLEncoder

class WebPage(act: MainActivity) : Page(act) {
    override val title = "Web"
    override val icon = "🔗"

    private class Found(val url: String, val title: String)

    private lateinit var web: WebView
    private lateinit var urlEdit: EditText
    private lateinit var foundBtn: Button
    private val found = ArrayList<Found>()

    private val sites = listOf(
        "YouTube" to "https://m.youtube.com",
        "Dailymotion" to "https://www.dailymotion.com",
        "Vimeo" to "https://vimeo.com",
        "Twitch" to "https://www.twitch.tv",
        "Facebook" to "https://m.facebook.com",
        "CDA" to "https://www.cda.pl",
        "Google" to "https://www.google.com"
    )

    private val mediaExt = listOf(".m3u8", ".mp4", ".flv", ".mpd", ".mp3", ".m4a", ".m4v", ".webm", ".mkv", ".mov", ".aac")

    override fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)

        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(4))

        urlEdit = EditText(act)
        urlEdit.hint = "Adres veya arama"
        urlEdit.setHintTextColor(Ui.SUBTEXT)
        urlEdit.setTextColor(Ui.TEXT)
        urlEdit.textSize = 14f
        urlEdit.isSingleLine = true
        urlEdit.imeOptions = EditorInfo.IME_ACTION_GO
        urlEdit.background = Ui.round(Ui.PANEL, 10)
        urlEdit.setPadding(Ui.dp(10), 0, Ui.dp(10), 0)
        urlEdit.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                go()
                true
            } else {
                false
            }
        }
        bar.addView(urlEdit, LinearLayout.LayoutParams(0, Ui.dp(40), 1f))

        val goBtn = Ui.button(act, "Git", Ui.BTN, 13f)
        goBtn.setOnClickListener { go() }
        val glp = LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(40))
        glp.leftMargin = Ui.dp(6)
        bar.addView(goBtn, glp)

        foundBtn = Ui.button(act, "🎬 0", Ui.BTN, 13f)
        foundBtn.setOnClickListener { showFound() }
        val flp = LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(40))
        flp.leftMargin = Ui.dp(6)
        bar.addView(foundBtn, flp)
        root.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val scroll = HorizontalScrollView(act)
        scroll.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(act)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(Ui.dp(6), 0, Ui.dp(6), Ui.dp(4))
        for ((name, url) in sites) {
            val b = Ui.button(act, name, Ui.BTN_DARK, 12f)
            b.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
            b.setOnClickListener { open(url) }
            val lp = LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(32))
            lp.rightMargin = Ui.dp(6)
            chips.addView(b, lp)
        }
        scroll.addView(chips, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        web = WebView(act)
        web.setBackgroundColor(Color.WHITE)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) sniff(request.url.toString())
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) urlEdit.setText(url)
                found.clear()
                updateCount()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        root.addView(web, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        web.loadUrl("https://www.google.com")
        return root
    }

    private fun go() {
        val t = urlEdit.text.toString().trim()
        if (t.isEmpty()) return
        open(t)
    }

    private fun open(input: String) {
        var u = input
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = if (u.contains(".") && !u.contains(" ")) "https://$u"
            else "https://www.google.com/search?q=" + URLEncoder.encode(u, "UTF-8")
        }
        web.loadUrl(u)
    }

    private fun sniff(url: String) {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (mediaExt.none { path.endsWith(it) }) return
        ui {
            if (found.none { it.url == url }) {
                found.add(Found(url, web.title ?: ""))
                updateCount()
            }
        }
    }

    private fun updateCount() {
        foundBtn.text = "🎬 ${found.size}"
        foundBtn.background = Ui.round(if (found.isEmpty()) Ui.BTN else Ui.GREEN, 12)
    }

    private fun showFound() {
        val labels = ArrayList<String>()
        labels.add("▶ Bu sayfanın adresini alıcıya gönder")
        for (f in found) {
            val file = f.url.substringBefore('?').substringAfterLast('/')
            labels.add(if (file.isEmpty()) f.url else file)
        }
        AlertDialog.Builder(act)
            .setTitle("Bulunan videolar (${found.size})")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    val u = web.url
                    if (u.isNullOrEmpty()) toast("Önce bir sayfa açın")
                    else Box.castWebUrl(u, web.title ?: "video")
                } else {
                    val f = found[which - 1]
                    Box.castWebUrl(f.url, if (f.title.isEmpty()) "video" else f.title)
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    override fun onShow() {
        web.onResume()
    }

    override fun onHide() {
        web.onPause()
    }

    override fun onBack(): Boolean {
        if (web.canGoBack()) {
            web.goBack()
            return true
        }
        return false
    }

    override fun onDestroy() {
        try {
            web.destroy()
        } catch (e: Exception) {
        }
    }
}
