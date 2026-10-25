package com.mhttn.sunplusremote

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class DevicePage(act: MainActivity) : Page(act) {
    override val title = "Cihaz"
    override val icon = "📡"

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var adapter: ListAdapter<Device>

    private val refresh: () -> Unit = { update() }

    override fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), 0)

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        val t = Ui.label(act, "Cihazlar", 20f, Ui.TEXT, true)
        head.addView(t, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val rescan = Ui.button(act, "Yeniden tara", Ui.BTN, 13f)
        rescan.setOnClickListener {
            Discovery.clear()
            Box.select(null, false)
            toast("Taranıyor… alıcı birkaç saniyede kendini duyurur")
        }
        head.addView(rescan, LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(38)))
        root.addView(head, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val hint = Ui.label(
            act,
            "Telefon ve alıcı aynı Wi-Fi ağında olmalı. Orijinal Ucast uygulaması açıksa kapatın.",
            12f, Ui.SUBTEXT
        )
        val hlp = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
        hlp.topMargin = Ui.dp(6)
        hlp.bottomMargin = Ui.dp(8)
        root.addView(hint, hlp)

        empty = Ui.label(act, "", 14f, Ui.SUBTEXT)
        empty.setPadding(Ui.dp(4), Ui.dp(16), Ui.dp(4), 0)
        root.addView(empty, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        adapter = ListAdapter(emptyList()) { d, _, cv ->
            val row = (cv as? RowView) ?: RowView(act)
            val sel = Box.device?.ip == d.ip
            val feat = StringBuilder()
            feat.append("DVB:").append(if (d.hasDvb) "var" else "yok")
            feat.append("   IPTV:").append(if (d.hasIptv) "var" else "yok")
            row.set(
                (if (sel) "✓ " else "") + d.name.ifEmpty { "(adsız cihaz)" },
                "${d.ip}   sPort=${d.sPort}  dvbSPort=${d.dvbSPort}   $feat",
                sel
            )
            row
        }
        list = ListView(act)
        list.adapter = adapter
        list.divider = null
        list.setOnItemClickListener { _, _, pos, _ ->
            val d = adapter.items[pos]
            Box.select(d)
            toast("Seçildi: ${d.name}")
            update()
        }
        root.addView(list, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        update()
        return root
    }

    private fun update() {
        val devs = Discovery.all()
        adapter.set(devs)
        val err = Discovery.lastError
        empty.text = when {
            devs.isNotEmpty() -> ""
            err.isNotEmpty() -> err
            else -> "Cihaz aranıyor…"
        }
        empty.visibility = if (devs.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onShow() {
        Discovery.addListener(refresh)
        Box.addListener(refresh)
        update()
    }

    override fun onHide() {
        Discovery.removeListener(refresh)
        Box.removeListener(refresh)
    }
}
