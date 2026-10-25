package com.mhttn.sunplusremote

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

abstract class Page(val act: MainActivity) {
    abstract val title: String
    abstract val icon: String
    val view: View by lazy { build() }

    abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}

    /** Return true if the page consumed the back press. */
    open fun onBack(): Boolean = false
    open fun onPermissionResult(granted: Boolean) {}
    open fun onDestroy() {}
}

class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var info: TextView
    private val tabViews = ArrayList<TextView>()
    private lateinit var pages: List<Page>
    private var current = -1

    private val boxListener: () -> Unit = { refreshInfo() }
    private val discoveryListener: () -> Unit = { refreshInfo() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)

        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        pages = listOf(
            DevicePage(this),
            RemotePage(this),
            DvbPage(this),
            IptvPage(this),
            FilePage(this),
            WebPage(this)
        )

        val tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.setBackgroundColor(Ui.PANEL)
        for (i in pages.indices) {
            val p = pages[i]
            val t = TextView(this)
            t.text = p.icon + "\n" + p.title
            t.textSize = 11f
            t.gravity = Gravity.CENTER
            t.setTextColor(Ui.SUBTEXT)
            t.setPadding(0, Ui.dp(6), 0, Ui.dp(6))
            t.setOnClickListener { show(i) }
            tabViews.add(t)
            tabBar.addView(t, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        root.addView(tabBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // The info line lives at the very bottom of the screen.
        info = TextView(this)
        info.textSize = 12f
        info.setTextColor(Ui.TEXT)
        info.setBackgroundColor(Color.parseColor("#0B0D11"))
        info.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7))
        info.maxLines = 2
        root.addView(info, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        setContentView(root)

        Box.addListener(boxListener)
        Discovery.addListener(discoveryListener)

        val saved = Prefs.getString("device")?.let { Device.fromJson(it) }
        if (saved != null) Box.select(saved, false)
        Discovery.start()
        refreshInfo()

        show(1) // Kumanda
    }

    private fun refreshInfo() {
        val d = Box.device
        val sb = StringBuilder()
        if (d != null) {
            val heard = Discovery.all().any { it.ip == d.ip }
            sb.append(if (heard) "● Bağlandı: " else "◐ Kayıtlı cihaz (yayın bekleniyor): ")
                .append(d.name).append("  (").append(d.ip)
                .append("  sPort=").append(d.sPort).append("  dvbSPort=").append(d.dvbSPort).append(")")
            info.setTextColor(if (heard) Color.parseColor("#7EE2A8") else Color.parseColor("#E8C86A"))
        } else {
            sb.append("○ Cihaz aranıyor…  (UDP ").append(Discovery.PORT).append(" dinleniyor)")
            info.setTextColor(Ui.SUBTEXT)
        }
        val err = Discovery.lastError
        if (err.isNotEmpty()) sb.append("\n").append(err)
        else if (Box.lastAction.isNotEmpty()) sb.append("\n").append(Box.lastAction)
        info.text = sb.toString()
    }

    fun show(index: Int) {
        if (index == current) return
        if (current >= 0) pages[current].onHide()
        content.removeAllViews()
        current = index
        content.addView(pages[index].view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        for (i in tabViews.indices) {
            val sel = i == index
            tabViews[i].setTextColor(if (sel) Ui.ACCENT else Ui.SUBTEXT)
        }
        pages[index].onShow()
    }

    fun requestMediaPermission(page: Page) {
        permissionPage = page
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, 77)
    }

    fun hasMediaPermission(): Boolean {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 33) {
            listOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return needed.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    private var permissionPage: Page? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            // On Android 13+ the user may grant only some media types; any grant is enough to list those.
            val granted = grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            permissionPage?.onPermissionResult(granted)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current >= 0 && pages[current].onBack()) return
        if (current != 1) {
            show(1)
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        Box.removeListener(boxListener)
        Discovery.removeListener(discoveryListener)
        for (p in pages) p.onDestroy()
        super.onDestroy()
    }
}
