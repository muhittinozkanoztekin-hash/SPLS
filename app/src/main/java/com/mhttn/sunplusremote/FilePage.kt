package com.mhttn.sunplusremote

import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class FilePage(act: MainActivity) : Page(act) {
    override val title = "Dosya"
    override val icon = "📁"

    class Item(val id: Long, val title: String, val ext: String, val size: Long, val durationMs: Long)

    private var kind = "video"
    private lateinit var videoBtn: Button
    private lateinit var musicBtn: Button
    private lateinit var imageBtn: Button
    private lateinit var status: TextView
    private lateinit var grant: Button
    private lateinit var listView: ListView
    private lateinit var adapter: ListAdapter<Item>
    private val cache = HashMap<String, List<Item>>()
    private var epoch = 0

    override fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), 0)

        videoBtn = Ui.button(act, "🎬 Video", Ui.ACCENT, 14f, Ui.TEXT, true)
        musicBtn = Ui.button(act, "🎵 Müzik", Ui.BTN, 14f)
        imageBtn = Ui.button(act, "🖼 Resim", Ui.BTN, 14f)
        videoBtn.setOnClickListener { setKind("video") }
        musicBtn.setOnClickListener { setKind("music") }
        imageBtn.setOnClickListener { setKind("image") }
        root.addView(Ui.row(act, videoBtn, musicBtn, imageBtn, heightDp = 42))

        status = Ui.label(
            act,
            "Bir dosyaya dokununca telefon onu yerel ağda yayınlar ve alıcıya \"oynat\" komutu gönderir. Oynatma bitene kadar uygulamayı açık tutun.",
            12f, Ui.SUBTEXT
        )
        status.setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), Ui.dp(6))
        root.addView(status, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        grant = Ui.button(act, "Medya erişim izni ver", Ui.ACCENT, 14f, Ui.TEXT, true)
        grant.setOnClickListener { act.requestMediaPermission(this) }
        grant.visibility = View.GONE
        root.addView(grant, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(44)))

        adapter = ListAdapter<Item>(emptyList()) { it, _, cv ->
            val row = (cv as? RowView) ?: RowView(act)
            row.set(it.title, describe(it), false)
            row
        }
        listView = ListView(act)
        listView.adapter = adapter
        listView.divider = null
        listView.setOnItemClickListener { _, _, pos, _ -> cast(adapter.items[pos]) }
        root.addView(listView, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    private fun describe(it: Item): String {
        val sb = StringBuilder()
        if (it.size > 0) sb.append(String.format("%.1f MB", it.size / 1048576.0))
        if (it.durationMs > 0) {
            val s = it.durationMs / 1000
            if (sb.isNotEmpty()) sb.append("  ·  ")
            if (s >= 3600) sb.append(String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60))
            else sb.append(String.format("%d:%02d", s / 60, s % 60))
        }
        return sb.toString()
    }

    override fun onShow() {
        if (act.hasMediaPermission()) {
            grant.visibility = View.GONE
            if (cache[kind] == null) load()
        } else {
            grant.visibility = View.VISIBLE
        }
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            grant.visibility = View.GONE
            cache.clear()
            load()
        } else {
            toast("İzin verilmedi - dosyalar listelenemez")
        }
    }

    private fun setKind(k: String) {
        kind = k
        videoBtn.background = Ui.round(if (k == "video") Ui.ACCENT else Ui.BTN, 12)
        musicBtn.background = Ui.round(if (k == "music") Ui.ACCENT else Ui.BTN, 12)
        imageBtn.background = Ui.round(if (k == "image") Ui.ACCENT else Ui.BTN, 12)
        if (!act.hasMediaPermission()) {
            grant.visibility = View.VISIBLE
            return
        }
        val c = cache[k]
        if (c != null) adapter.set(c) else load()
    }

    private fun load() {
        val k = kind
        val my = ++epoch
        adapter.set(emptyList())
        bg {
            val items = try {
                query(k)
            } catch (e: Throwable) {
                toastLong("Dosyalar okunamadı: ${e.message}")
                emptyList()
            }
            ui {
                cache[k] = items
                if (my == epoch && k == kind) adapter.set(items)
            }
        }
    }

    private fun query(k: String): List<Item> {
        val uri: Uri
        val cols: Array<String>
        when (k) {
            "video" -> {
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                cols = arrayOf("_id", "_display_name", "_size", "duration")
            }
            "music" -> {
                uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                cols = arrayOf("_id", "_display_name", "_size", "duration")
            }
            else -> {
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                cols = arrayOf("_id", "_display_name", "_size")
            }
        }
        val out = ArrayList<Item>()
        val c = App.ctx.contentResolver.query(uri, cols, null, null, "date_added DESC") ?: return out
        c.use {
            val iId = it.getColumnIndex("_id")
            val iName = it.getColumnIndex("_display_name")
            val iSize = it.getColumnIndex("_size")
            val iDur = it.getColumnIndex("duration")
            while (it.moveToNext()) {
                val name = if (iName >= 0) (it.getString(iName) ?: "") else ""
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                var ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
                if (ext.isEmpty() || !ext.all { ch -> ch.isLetterOrDigit() }) {
                    ext = when (k) {
                        "video" -> "mp4"
                        "music" -> "mp3"
                        else -> "jpg"
                    }
                }
                if (k == "image") ext = "jpg" // photos are always served as JPEG
                out.add(
                    Item(
                        it.getLong(iId),
                        if (base.isEmpty()) "(adsız)" else base,
                        ext,
                        if (iSize >= 0) it.getLong(iSize) else 0L,
                        if (iDur >= 0) it.getLong(iDur) else 0L
                    )
                )
            }
        }
        return out
    }

    private fun cast(item: Item) {
        val d = Box.device
        if (d == null) {
            toast("Cihaz bağlı değil")
            return
        }
        val k = kind
        bg {
            try {
                val phoneIp = Net.localIpFor(d.ip) ?: throw IllegalStateException("Wi-Fi bağlantısı yok")
                val port = CallbackServer.ensureStarted()
                val url = "http://$phoneIp:$port/$k/${item.id}.${item.ext}"
                val name = if (item.title.length > 32) k else item.title
                val type = when (k) {
                    "video" -> "video"
                    "music" -> "music"
                    else -> "imag"
                }
                Box.sendCast(url, "play", name, type, item.title)
            } catch (e: Throwable) {
                toastLong("Hata: ${e.message}")
            }
        }
    }
}
