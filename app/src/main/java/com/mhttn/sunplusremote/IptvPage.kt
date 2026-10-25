package com.mhttn.sunplusremote

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class IptvPage(act: MainActivity) : Page(act) {
    override val title = "IPTV"
    override val icon = "🌐"

    private enum class Stage { APPS, SUB_APPS, CHANNELS }

    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var groupBar: LinearLayout
    private lateinit var groupScroll: HorizontalScrollView
    private lateinit var listView: ListView
    private lateinit var appAdapter: ListAdapter<IptvApp>
    private lateinit var chAdapter: ListAdapter<IptvChannel>

    private var stage = Stage.APPS
    private var apps: List<IptvApp> = emptyList()
    private var loadedOnce = false
    private var currentApp: IptvApp? = null
    private var parentApp: IptvApp? = null
    private var groups: List<Group> = emptyList()
    private var currentGroup: Group? = null
    private val channelCache = HashMap<String, List<IptvChannel>>()
    private var epoch = 0

    override fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), 0)

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        heading = Ui.label(act, "IPTV", 20f, Ui.TEXT, true)
        head.addView(heading, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val refresh = Ui.button(act, "↻", Ui.BTN, 18f)
        refresh.setOnClickListener { reload() }
        head.addView(refresh, LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(38)))
        root.addView(head, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        status = Ui.label(act, "", 12f, Ui.SUBTEXT)
        status.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(4))
        root.addView(status, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        groupScroll = HorizontalScrollView(act)
        groupScroll.isHorizontalScrollBarEnabled = false
        groupBar = LinearLayout(act)
        groupBar.orientation = LinearLayout.HORIZONTAL
        groupScroll.addView(groupBar, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        groupScroll.visibility = View.GONE
        root.addView(groupScroll, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(44)))

        appAdapter = ListAdapter<IptvApp>(emptyList()) { app, _, cv ->
            val row = (cv as? RowView) ?: RowView(act)
            row.set(app.name, if (app.children.isNotEmpty()) "${app.children.size} alt uygulama  ›" else "", false)
            row
        }
        chAdapter = ListAdapter<IptvChannel>(emptyList()) { ch, _, cv ->
            val row = (cv as? RowView) ?: RowView(act)
            row.set(ch.name, "", false)
            row
        }
        listView = ListView(act)
        listView.divider = null
        listView.adapter = appAdapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            when (stage) {
                Stage.APPS, Stage.SUB_APPS -> openApp(appAdapter.items[pos])
                Stage.CHANNELS -> play(chAdapter.items[pos])
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    override fun onShow() {
        if (!loadedOnce) reload()
    }

    override fun onBack(): Boolean {
        return when (stage) {
            Stage.CHANNELS -> {
                val p = parentApp
                if (p != null) showApps(p.children, p.name, Stage.SUB_APPS) else showApps(apps, "IPTV", Stage.APPS)
                true
            }
            Stage.SUB_APPS -> {
                showApps(apps, "IPTV", Stage.APPS)
                true
            }
            Stage.APPS -> false
        }
    }

    private fun supported(): Boolean {
        val d = Box.device
        if (d == null) {
            status.text = "Cihaz bağlı değil. Önce Cihaz sekmesinden alıcıyı seçin."
            status.setTextColor(Ui.RED)
            return false
        }
        if (!d.hasIptv) {
            status.text = "Bu cihaz IPTV desteklemiyor (appstore=${d.appstore}, sPort=${d.sPort})."
            status.setTextColor(Ui.RED)
            return false
        }
        return true
    }

    private fun reload() {
        channelCache.clear()
        if (!supported()) return
        loadedOnce = true
        val my = ++epoch
        status.text = "Uygulama listesi alınıyor…"
        status.setTextColor(Ui.SUBTEXT)
        bg {
            try {
                val list = Api.iptvApps()
                ui {
                    if (my == epoch) {
                        apps = list
                        showApps(list, "IPTV", Stage.APPS)
                        status.text = if (list.isEmpty()) "Alıcıda IPTV uygulaması yok" else "${list.size} uygulama"
                    }
                }
            } catch (e: Throwable) {
                ui {
                    if (my == epoch) {
                        status.text = "Hata: ${e.message}"
                        status.setTextColor(Ui.RED)
                    }
                }
            }
        }
    }

    private fun showApps(list: List<IptvApp>, title: String, st: Stage) {
        stage = st
        heading.text = title
        groupScroll.visibility = View.GONE
        listView.adapter = appAdapter
        appAdapter.set(list)
        if (st == Stage.APPS) parentApp = null
    }

    private fun openApp(app: IptvApp) {
        if (app.children.isNotEmpty()) {
            parentApp = app
            showApps(app.children, app.name, Stage.SUB_APPS)
        } else {
            openGroups(app)
        }
    }

    private fun openGroups(app: IptvApp) {
        if (!supported()) return
        currentApp = app
        stage = Stage.CHANNELS
        heading.text = app.name
        groupBar.removeAllViews()
        groupScroll.visibility = View.GONE
        chAdapter.set(emptyList())
        listView.adapter = chAdapter
        val my = ++epoch
        status.text = "Gruplar alınıyor…"
        status.setTextColor(Ui.SUBTEXT)
        bg {
            try {
                val g = Api.iptvGroups(app.id)
                ui {
                    if (my == epoch) {
                        groups = g
                        buildGroupBar()
                        if (g.isEmpty()) status.text = "Bu uygulamada grup yok" else openGroup(g[0])
                    }
                }
            } catch (e: Throwable) {
                ui {
                    if (my == epoch) {
                        status.text = "Hata: ${e.message}"
                        status.setTextColor(Ui.RED)
                    }
                }
            }
        }
    }

    private fun buildGroupBar() {
        groupBar.removeAllViews()
        for (g in groups) {
            val b: Button = Ui.button(act, g.name, Ui.BTN, 13f)
            b.setOnClickListener { openGroup(g) }
            val lp = LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(36))
            lp.rightMargin = Ui.dp(6)
            lp.topMargin = Ui.dp(4)
            b.setPadding(Ui.dp(14), 0, Ui.dp(14), 0)
            groupBar.addView(b, lp)
        }
        groupScroll.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        highlightGroup()
    }

    private fun highlightGroup() {
        for (i in 0 until groupBar.childCount) {
            val v = groupBar.getChildAt(i)
            val sel = groups.getOrNull(i)?.gid == currentGroup?.gid
            v.background = Ui.round(if (sel) Ui.ACCENT else Ui.BTN, 12)
        }
    }

    private fun openGroup(g: Group) {
        currentGroup = g
        highlightGroup()
        val cached = channelCache[g.gid]
        if (cached != null) {
            chAdapter.set(cached)
            status.text = "${cached.size} kanal"
            status.setTextColor(Ui.SUBTEXT)
            return
        }
        val my = ++epoch
        status.text = "Kanallar alınıyor…"
        status.setTextColor(Ui.SUBTEXT)
        chAdapter.set(emptyList())
        bg {
            try {
                val list = Api.iptvChannels(g.gid)
                ui {
                    if (my == epoch) {
                        channelCache[g.gid] = list
                        chAdapter.set(list)
                        status.text = "${list.size} kanal"
                    }
                }
            } catch (e: Throwable) {
                ui {
                    if (my == epoch) {
                        status.text = "Hata: ${e.message}"
                        status.setTextColor(Ui.RED)
                    }
                }
            }
        }
    }

    private fun play(ch: IptvChannel) {
        if (ch.url.isNotEmpty()) {
            PlayerActivity.start(act, ch.url, ch.name, false, null)
            return
        }
        val dlg = AlertDialog.Builder(act).setMessage("Yayın adresi alınıyor…").setCancelable(false).create()
        dlg.show()
        bg {
            try {
                val info = Api.iptvPlay(ch.cid)
                ui {
                    dlg.dismiss()
                    if (info.url.isEmpty()) toast("Alıcı yayın adresi vermedi")
                    else PlayerActivity.start(act, info.url, ch.name, false, info.ua)
                }
            } catch (e: Throwable) {
                ui {
                    dlg.dismiss()
                    toastLong("Hata: ${e.message}")
                }
            }
        }
    }
}
