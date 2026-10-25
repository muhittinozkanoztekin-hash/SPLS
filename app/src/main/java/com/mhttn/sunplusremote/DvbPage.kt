package com.mhttn.sunplusremote

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView

class ChannelRow(ctx: Context) : LinearLayout(ctx) {
    val star = TextView(ctx)
    val num = TextView(ctx)
    val name = TextView(ctx)
    val tag = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(4), 0, Ui.dp(10), 0)
        star.textSize = 22f
        star.gravity = Gravity.CENTER
        star.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8))
        num.textSize = 12f
        num.setTextColor(Ui.SUBTEXT)
        num.gravity = Gravity.END
        name.textSize = 16f
        name.setTextColor(Ui.TEXT)
        name.maxLines = 1
        tag.textSize = 14f
        tag.setTextColor(Ui.GREEN)
        addView(star, LayoutParams(Ui.WRAP, Ui.WRAP))
        addView(num, LayoutParams(Ui.dp(38), Ui.WRAP))
        val nlp = LayoutParams(0, Ui.WRAP, 1f)
        nlp.leftMargin = Ui.dp(8)
        addView(name, nlp)
        addView(tag, LayoutParams(Ui.WRAP, Ui.WRAP))
        minimumHeight = Ui.dp(50)
    }
}

class DvbPage(act: MainActivity) : Page(act) {
    override val title = "DVB"
    override val icon = "📺"

    private lateinit var tvBtn: Button
    private lateinit var radioBtn: Button
    private lateinit var favBtn: Button
    private lateinit var filterBtn: Button
    private lateinit var searchEdit: EditText
    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private lateinit var adapter: ListAdapter<Channel>

    private var service = 1
    private var list: ChannelList? = null
    private var shown: List<Channel> = emptyList()
    private var curHandle = -1
    private var curTp = -1
    private var query = ""
    private var favOnly = false
    private var selectedSubs: List<Int> = emptyList()
    private var epoch = 0
    private var visible = false
    private var loading = false
    private var polling = false
    private var failed = false
    private var favs: MutableSet<String> = HashSet()

    private val poller = object : Runnable {
        override fun run() {
            if (!visible) return
            val d = Box.device
            if (d != null && d.hasDvb && !loading && !polling && list != null) {
                polling = true
                bg {
                    try {
                        val crc = Api.checkCrc()
                        ui { onCrc(crc) }
                    } catch (e: Throwable) {
                        // box busy or in editing state - try again later
                    } finally {
                        polling = false
                    }
                }
            }
            App.main.postDelayed(this, 5000)
        }
    }

    private val boxListener: () -> Unit = {
        // a different receiver was selected: start over
        if (visible && Box.device != null && list != null && lastIp != Box.device?.ip) {
            lastIp = Box.device?.ip
            list = null
            load()
        }
    }
    private var lastIp: String? = null

    override fun build(): View {
        favs = Prefs.getSet("favs")
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), 0)

        tvBtn = Ui.button(act, "TV", Ui.ACCENT, 14f, Color.WHITE, true)
        radioBtn = Ui.button(act, "Radyo", Ui.BTN, 14f)
        favBtn = Ui.button(act, "☆ Favori", Ui.BTN, 13f)
        filterBtn = Ui.button(act, "Filtre", Ui.BTN, 13f)
        val refreshBtn = Ui.button(act, "↻", Ui.BTN, 18f)
        tvBtn.setOnClickListener { if (service != 1 || list == null) { service = 1; load() } }
        radioBtn.setOnClickListener { if (service != 2 || list == null) { service = 2; load() } }
        favBtn.setOnClickListener {
            favOnly = !favOnly
            updateButtons()
            applyFilters()
        }
        filterBtn.setOnClickListener { showFilter() }
        refreshBtn.setOnClickListener { load() }
        root.addView(
            Ui.row(act, tvBtn, radioBtn, favBtn, filterBtn, refreshBtn, heightDp = 42, gapDp = 5,
                weights = floatArrayOf(1f, 1.2f, 1.5f, 1.2f, 0.7f))
        )

        searchEdit = EditText(act)
        searchEdit.hint = "Kanal ara"
        searchEdit.setHintTextColor(Ui.SUBTEXT)
        searchEdit.setTextColor(Ui.TEXT)
        searchEdit.textSize = 15f
        searchEdit.isSingleLine = true
        searchEdit.background = Ui.round(Ui.PANEL, 10)
        searchEdit.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilters()
            }
        })
        val slp = LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(42))
        slp.topMargin = Ui.dp(6)
        root.addView(searchEdit, slp)

        statusText = Ui.label(act, "", 12f, Ui.SUBTEXT)
        statusText.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(4))
        statusText.setOnClickListener { if (failed) load() }
        root.addView(statusText, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        adapter = ListAdapter<Channel>(emptyList()) { ch, pos, cv ->
            val row = (cv as? ChannelRow) ?: ChannelRow(act)
            val isFav = favs.contains(ch.favKey)
            row.star.text = if (isFav) "★" else "☆"
            row.star.setTextColor(if (isFav) Ui.YELLOW else Ui.SUBTEXT)
            row.star.setOnClickListener { toggleFav(ch) }
            row.num.text = (pos + 1).toString()
            row.name.text = ch.name
            val playing = ch.handle == curHandle
            row.name.setTextColor(if (playing) Ui.GREEN else Ui.TEXT)
            row.tag.text = (if (ch.encrypt) "🔒 " else "") + (if (playing) "▶" else "")
            row.setBackgroundColor(if (playing) Color.parseColor("#14281F") else Color.TRANSPARENT)
            row
        }
        listView = ListView(act)
        listView.adapter = adapter
        listView.divider = null
        listView.setOnItemClickListener { _, _, pos, _ -> watch(adapter.items[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            options(adapter.items[pos])
            true
        }
        root.addView(listView, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        updateButtons()
        return root
    }

    override fun onShow() {
        visible = true
        Box.addListener(boxListener)
        lastIp = Box.device?.ip
        if (list == null && !loading) load()
        App.main.removeCallbacks(poller)
        App.main.postDelayed(poller, 5000)
    }

    override fun onHide() {
        visible = false
        Box.removeListener(boxListener)
        App.main.removeCallbacks(poller)
    }

    private fun setStatus(s: String, error: Boolean = false) {
        failed = error
        statusText.text = s
        statusText.setTextColor(if (error) Ui.RED else Ui.SUBTEXT)
    }

    private fun updateButtons() {
        val tv = service == 1
        tvBtn.background = Ui.round(if (tv) Ui.ACCENT else Ui.BTN, 12)
        radioBtn.background = Ui.round(if (!tv) Ui.ACCENT else Ui.BTN, 12)
        favBtn.background = Ui.round(if (favOnly) Ui.YELLOW else Ui.BTN, 12)
        favBtn.setTextColor(if (favOnly) Color.BLACK else Ui.TEXT)
        favBtn.text = if (favOnly) "★ Favori" else "☆ Favori"
        filterBtn.background = Ui.round(if (selectedSubs.isNotEmpty()) Ui.ACCENT else Ui.BTN, 12)
    }

    // ------------------------------------------------------------ loading

    private fun load() {
        val d = Box.device
        if (d == null) {
            setStatus("Cihaz bağlı değil. Önce Cihaz sekmesinden alıcıyı seçin.", true)
            return
        }
        if (!d.hasDvb) {
            setStatus("Bu cihaz DVB kanal listesini desteklemiyor (dvb_s_port yok).", true)
            return
        }
        val want = service
        val my = ++epoch
        loading = true
        list = null
        shown = emptyList()
        adapter.set(shown)
        selectedSubs = emptyList()
        updateButtons()
        setStatus("Kanal listesi alınıyor… (liste büyükse birkaç saniye sürebilir)")
        bg {
            try {
                val crc = Api.checkCrc()
                if (my != epoch) return@bg
                if (crc.serviceType != want) {
                    Api.switchType(want)
                    Thread.sleep(700)
                }
                val cl = Api.channelList()
                if (my != epoch) return@bg
                ui {
                    if (my == epoch) applyList(cl, crc.proHandle)
                }
            } catch (e: Throwable) {
                if (my == epoch) {
                    ui {
                        loading = false
                        setStatus("Hata: ${e.message}   (yeniden denemek için buraya dokunun)", true)
                    }
                }
            }
        }
    }

    private fun applyList(cl: ChannelList, handle: Int) {
        loading = false
        list = cl
        if (cl.service != 0) service = cl.service
        curHandle = handle
        curTp = cl.channels.firstOrNull { it.handle == handle }?.tp ?: -1
        updateButtons()
        applyFilters()
    }

    private fun onCrc(crc: Crc) {
        val cl = list ?: return
        if (crc.proHandle != curHandle) {
            curHandle = crc.proHandle
            curTp = cl.channels.firstOrNull { it.handle == curHandle }?.tp ?: curTp
            adapter.notifyDataSetChanged()
        }
    }

    private fun applyFilters() {
        val cl = list ?: return
        val q = query.trim().lowercase()
        shown = cl.channels.filter { ch ->
            (q.isEmpty() || ch.name.lowercase().contains(q)) &&
                (!favOnly || favs.contains(ch.favKey)) &&
                (selectedSubs.isEmpty() || ch.subs.containsAll(selectedSubs))
        }
        adapter.set(shown)
        val total = cl.channels.size
        setStatus(if (shown.size == total) "$total kanal" else "${shown.size} / $total kanal")
    }

    private fun toggleFav(ch: Channel) {
        if (!favs.remove(ch.favKey)) favs.add(ch.favKey)
        Prefs.putSet("favs", favs)
        if (favOnly) applyFilters() else adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------- filter

    private fun showFilter() {
        val cl = list
        if (cl == null || cl.main.isEmpty()) {
            toast("Filtre için önce kanal listesi yüklenmeli")
            return
        }
        val names = ArrayList<String>()
        names.add("Tümü (filtreyi kaldır)")
        for (m in cl.main) names.add(m.name)
        AlertDialog.Builder(act)
            .setTitle("Filtre")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedSubs = emptyList()
                    updateButtons()
                    applyFilters()
                } else {
                    chooseSubs(cl, cl.main[which - 1])
                }
            }
            .show()
    }

    private fun chooseSubs(cl: ChannelList, main: Handle) {
        val groups = cl.subsFor(main.handle)
        if (groups.isEmpty()) {
            toast("Bu başlık için alt kategori yok")
            return
        }
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8))
        val spinners = ArrayList<Pair<Spinner, List<Handle>>>()
        for ((cat, items) in groups) {
            box.addView(Ui.label(act, cat, 13f, Ui.SUBTEXT, true))
            val sp = Spinner(act)
            val labels = ArrayList<String>()
            labels.add("Tümü")
            for (h in items) labels.add(h.name)
            sp.adapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, labels)
            box.addView(sp, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            spinners.add(Pair(sp, items))
        }
        AlertDialog.Builder(act)
            .setTitle(main.name)
            .setView(box)
            .setPositiveButton("Uygula") { _, _ ->
                val chosen = ArrayList<Int>()
                for ((sp, items) in spinners) {
                    val pos = sp.selectedItemPosition
                    if (pos > 0) chosen.add(items[pos - 1].handle)
                }
                selectedSubs = chosen
                updateButtons()
                applyFilters()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ------------------------------------------------------------ actions

    private fun options(ch: Channel) {
        val isFav = favs.contains(ch.favKey)
        val items = arrayOf(
            "Telefonda izle",
            "Alıcıda bu kanala geç",
            if (isFav) "Favorilerden çıkar" else "Favorilere ekle"
        )
        AlertDialog.Builder(act)
            .setTitle(ch.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> watch(ch)
                    1 -> switchOnly(ch)
                    2 -> toggleFav(ch)
                }
            }
            .show()
    }

    private fun busyDialog(msg: String): AlertDialog {
        val dlg = AlertDialog.Builder(act).setMessage(msg).setCancelable(false).create()
        dlg.show()
        return dlg
    }

    /** Tap: stream the channel to the phone (the box may have to retune first). */
    private fun watch(ch: Channel) {
        if (Box.device == null) {
            toast("Cihaz bağlı değil")
            return
        }
        if (ch.tp != curTp) {
            AlertDialog.Builder(act)
                .setTitle(ch.name)
                .setMessage("Bu kanal alıcının şu an ayarlı olduğu frekanstan farklı. Alıcı bu kanala geçecek (TV'deki yayın değişir). Devam edilsin mi?")
                .setPositiveButton("Evet") { _, _ -> switchThenPlay(ch) }
                .setNegativeButton("İptal", null)
                .show()
        } else {
            openStream(ch, null)
        }
    }

    private fun switchOnly(ch: Channel) {
        val dlg = busyDialog("Alıcı kanala geçiyor…")
        bg {
            try {
                val ok = Api.switchChannel(ch)
                ui {
                    dlg.dismiss()
                    if (ok) {
                        curHandle = ch.handle
                        curTp = ch.tp
                        adapter.notifyDataSetChanged()
                        Box.note("alıcı kanalı: ${ch.name}")
                    } else {
                        toast("Kanal değiştirilemedi")
                    }
                }
            } catch (e: Throwable) {
                ui {
                    dlg.dismiss()
                    toastLong("Hata: ${e.message}")
                }
            }
        }
    }

    private fun switchThenPlay(ch: Channel) {
        val dlg = busyDialog("Alıcı kanala geçiyor…")
        bg {
            try {
                val ok = Api.switchChannel(ch)
                if (!ok) {
                    ui {
                        dlg.dismiss()
                        toast("Kanal değiştirilemedi")
                    }
                    return@bg
                }
                ui {
                    curHandle = ch.handle
                    curTp = ch.tp
                    adapter.notifyDataSetChanged()
                }
                openStream(ch, dlg)
            } catch (e: Throwable) {
                ui {
                    dlg.dismiss()
                    toastLong("Hata: ${e.message}")
                }
            }
        }
    }

    /** Asks the box to lock the tuner, then opens the stream address in the player. */
    private fun openStream(ch: Channel, dlg: AlertDialog?) {
        val d = Box.device ?: return
        val shownDlg = dlg ?: busyDialog("Yayın hazırlanıyor…")
        bg {
            try {
                val r = Api.checkLock(ch)
                ui { shownDlg.dismiss() }
                if (r == 0) {
                    val url = Api.liveUrl(d, ch)
                    ui { PlayerActivity.start(act, url, ch.name, service == 2, null) }
                } else {
                    toastLong("Kanal açılamadı (sonuç=$r). Şifreli ya da kilitli olabilir.")
                }
            } catch (e: Throwable) {
                ui { shownDlg.dismiss() }
                toastLong("Hata: ${e.message}")
            }
        }
    }
}
