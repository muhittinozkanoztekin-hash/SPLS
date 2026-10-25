package com.mhttn.sunplusremote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.example.ucast.LocalApplication
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reverse-engineered from the "Ucast" companion app (v5.0.1). Wire format:
 *  - every message is a JSON text, run through the native LocalApplication
 *    encodeString(), prefixed with "json=" and sent as one UDP datagram;
 *  - anything the box answers/broadcasts has the same "json=" + encoded form.
 *  - Remote keys and cast commands go to the box's s_port (fire and forget).
 *  - Requests that need an answer go to dvb_s_port (DVB) or s_port (IPTV);
 *    the box answers by POSTing to an HTTP server that runs on the phone
 *    (see HttpServer.kt), at the "url" we put into the request.
 */

/**
 * The native encode/decode is a black box (compiled C++), so we never call it from
 * two threads at once - key presses, discovery and requests all go through this lock.
 */
object Native {
    private val lock = Any()

    fun encode(s: String): String = synchronized(lock) { LocalApplication.encodeString(s) }

    fun decode(s: String): String = synchronized(lock) { LocalApplication.decodeString(s) }
}

data class Device(
    val ip: String,
    val name: String,
    val sPort: Int,
    val dvbSPort: Int,
    val appstore: Int
) {
    val hasDvb: Boolean get() = dvbSPort != 0
    val hasIptv: Boolean get() = sPort != 0 && appstore != 0

    fun toJson(): String {
        val m = LinkedHashMap<String, Any?>()
        m["ip"] = ip
        m["name"] = name
        m["s_port"] = sPort
        m["dvb_s_port"] = dvbSPort
        m["appstore"] = appstore
        return Json.obj(m)
    }

    companion object {
        fun fromJson(s: String): Device? {
            return try {
                val j = JSONObject(s)
                Device(j.getString("ip"), j.optString("name"), j.optInt("s_port"), j.optInt("dvb_s_port"), j.optInt("appstore"))
            } catch (e: Exception) {
                null
            }
        }
    }
}

object Keys {
    const val UP = "/api/ir/up"
    const val DOWN = "/api/ir/down"
    const val LEFT = "/api/ir/left"
    const val RIGHT = "/api/ir/right"
    const val ENTER = "/api/ir/enter"
    const val CHANNEL_UP = "/api/ir/channel_up"
    const val CHANNEL_DOWN = "/api/ir/channel_down"
    const val VOLUME_UP = "/api/ir/volumn_up"   // "volumn" typo is in the original firmware API
    const val VOLUME_DOWN = "/api/ir/volumn_down"
    const val POWER = "/api/ir/power"
    const val MUTE = "/api/ir/mute"
    const val MENU = "/api/ir/menu"
    const val EXIT = "/api/ir/exit"
    const val RETURN = "/api/ir/return"
    const val INFO = "/api/ir/info"
    const val EPG = "/api/ir/epg"
    const val FAV = "/api/ir/fav"
    const val AUDIO = "/api/ir/audio"
    const val SUBTITLE = "/api/ir/subtitle"
    const val SOURCE = "/api/ir/source"
    const val DEV = "/api/ir/dev"
    const val TV_RADIO = "/api/ir/tv_radio"
    const val PAGE_UP = "/api/ir/pageup"
    const val PAGE_DOWN = "/api/ir/pagedown"
    const val RED = "/api/ir/red"
    const val GREEN = "/api/ir/green"
    const val YELLOW = "/api/ir/yellow"
    const val BLUE = "/api/ir/blue"
    const val PLAY = "/api/ir/play"
    const val PAUSE = "/api/ir/pause"
    const val STOP = "/api/ir/stop"
    const val REWIND = "/api/ir/rewind"
    const val FAST_FORWARD = "/api/ir/fast_forward"
    const val PREVIOUS = "/api/ir/previous"
    const val NEXT = "/api/ir/next"
    const val RECORD = "/api/ir/record"
    const val REPEAT = "/api/ir/repeat"
    const val ZERO = "/api/ir/zero"
    const val ONE = "/api/ir/one"
    const val TWO = "/api/ir/two"
    const val THREE = "/api/ir/three"
    const val FOUR = "/api/ir/four"
    const val FIVE = "/api/ir/five"
    const val SIX = "/api/ir/six"
    const val SEVEN = "/api/ir/seven"
    const val EIGHT = "/api/ir/eight"
    const val NINE = "/api/ir/nine"
}

/** Listens for the box's periodic announcement on UDP 8998. */
object Discovery {
    const val PORT = 8998

    private val devices = LinkedHashMap<String, Device>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var running = false

    @Volatile
    private var socketRef: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    @Volatile
    var lastError: String = ""

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun all(): List<Device> = synchronized(devices) { ArrayList(devices.values) }

    fun clear() {
        synchronized(devices) { devices.clear() }
        notifyChanged()
    }

    private fun notifyChanged() {
        ui { for (l in listeners) l() }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        try {
            val wm = App.ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ml = wm.createMulticastLock("ucast")
            ml.setReferenceCounted(false)
            ml.acquire()
            lock = ml
        } catch (e: Exception) {
            // not fatal
        }
        val t = Thread { loop() }
        t.isDaemon = true
        t.start()
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socketRef?.close()
        } catch (e: Exception) {
        }
        try {
            lock?.release()
        } catch (e: Exception) {
        }
        lock = null
    }

    private fun loop() {
        while (running) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                s.soTimeout = 4000
                socketRef = s
                lastError = ""
                val buf = ByteArray(8192)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    try {
                        handle(p)
                    } catch (e: Throwable) {
                        lastError = "Yayın çözülemedi: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    lastError = "Keşif hatası: ${e.message}"
                    notifyChanged()
                    try {
                        Thread.sleep(3000)
                    } catch (ie: InterruptedException) {
                    }
                }
            } finally {
                try {
                    s?.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun handle(p: DatagramPacket) {
        val raw = String(p.data, p.offset, p.length, Charsets.UTF_8)
        if (raw.length <= 5) return
        val ip = p.address.hostAddress ?: return
        val decoded = Native.decode(raw.substring(5))
        val d = parseBeacon(ip, decoded) ?: return

        var changed = false
        synchronized(devices) {
            if (devices[ip] != d) {
                devices[ip] = d
                changed = true
            }
        }

        val cur = Box.device
        if (cur == null) {
            val saved = Prefs.getString("device")?.let { Device.fromJson(it) }
            if (saved == null || saved.ip == d.ip || (saved.name.isNotEmpty() && saved.name == d.name)) {
                Box.select(d)
            }
        } else if (cur.ip == d.ip) {
            if (cur != d) Box.select(d)
        } else if (cur.name.isNotEmpty() && cur.name == d.name) {
            // same box, new IP (DHCP): follow it unless the old address is still alive
            val oldAlive = synchronized(devices) { devices.containsKey(cur.ip) }
            if (!oldAlive) Box.select(d)
        }
        if (changed) notifyChanged()
    }

    /**
     * The original app cuts the text between "uuid and "state before parsing
     * (that part of the box's JSON is not always valid), we do the same.
     */
    fun parseBeacon(ip: String, decoded: String): Device? {
        var t = decoded
        val i1 = t.indexOf("\"uuid")
        val i2 = t.indexOf("\"state")
        if (i1 >= 0 && i2 > i1) t = t.replace(t.substring(i1, i2), "")
        try {
            val j = JSONObject(t)
            return Device(ip, j.optString("name"), j.optInt("s_port"), j.optInt("dvb_s_port"), j.optInt("appstore"))
        } catch (e: Exception) {
            // fall through to a tolerant text scan
        }
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(decoded)?.groupValues?.get(1) ?: return null
        fun num(key: String): Int =
            Regex("\"$key\"\\s*:\\s*\"?(\\d+)").find(decoded)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Device(ip, name, num("s_port"), num("dvb_s_port"), num("appstore"))
    }
}

/** The currently selected receiver plus everything that talks to it. */
object Box {
    @Volatile
    var device: Device? = null

    @Volatile
    var lastAction: String = ""

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Single thread: "1" then "2" must reach the box in that order. */
    private val sender: ExecutorService = Executors.newSingleThreadExecutor()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun fire() {
        ui { for (l in listeners) l() }
    }

    fun select(d: Device?, save: Boolean = true) {
        device = d
        if (d != null && save) Prefs.putString("device", d.toJson())
        fire()
    }

    fun note(s: String) {
        lastAction = s
        fire()
    }

    // ---------------------------------------------------------------- sending

    /** Same key order the original app produces (fastjson sorts keys). */
    fun cmdMap(url: String, use: String, name: String, type: String): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["len"] = url.length
        m["name"] = name
        m["type"] = type
        m["url"] = url
        m["use"] = use
        return m
    }

    private fun sendUdp(ip: String, port: Int, json: String) {
        val enc = "json=" + Native.encode(json)
        val bytes = enc.toByteArray()
        val s = DatagramSocket()
        try {
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), port))
        } finally {
            s.close()
        }
    }

    /** Remote key press (fire and forget, like the original). */
    fun sendKey(cmd: String, label: String) {
        val d = device
        if (d == null) {
            toast("Cihaz bağlı değil")
            return
        }
        if (d.sPort == 0) {
            toast("Cihazda kumanda portu (s_port) yok")
            return
        }
        sender.execute {
            try {
                sendUdp(d.ip, d.sPort, Json.obj(cmdMap(cmd, "post", "", "key")))
                note("son tuş: $label")
            } catch (e: Throwable) {
                toast("Gönderilemedi: ${e.message}")
            }
        }
    }

    /** Ask the box to play something (media URL served by us, or any web URL). */
    fun sendCast(url: String, use: String, name: String, type: String, label: String) {
        val d = device
        if (d == null) {
            toast("Cihaz bağlı değil")
            return
        }
        if (d.sPort == 0) {
            toast("Cihazda s_port yok")
            return
        }
        sender.execute {
            try {
                sendUdp(d.ip, d.sPort, Json.obj(cmdMap(url, use, name, type)))
                note("gönderildi: $label")
                toast("Alıcıya gönderildi")
            } catch (e: Throwable) {
                toast("Gönderilemedi: ${e.message}")
            }
        }
    }

    /** Sends a web video URL; very long URLs are handed over through our /down/ endpoint. */
    fun castWebUrl(playUrl: String, title: String) {
        val d = device
        if (d == null) {
            toast("Cihaz bağlı değil")
            return
        }
        sender.execute {
            try {
                val name = if (title.length > 32) "video" else title
                if (playUrl.length > 700) {
                    val phoneIp = Net.localIpFor(d.ip) ?: throw IllegalStateException("Wi-Fi bağlantısı yok")
                    val port = CallbackServer.ensureStarted()
                    val key = md5(playUrl)
                    val body = LinkedHashMap<String, Any?>()
                    body["url"] = playUrl
                    CallbackServer.downCache[key] = Json.obj(body)
                    sendUdp(d.ip, d.sPort, Json.obj(cmdMap("http://$phoneIp:$port/down/$key", "down", name, "video")))
                } else {
                    sendUdp(d.ip, d.sPort, Json.obj(cmdMap(playUrl, "play", name, "video")))
                }
                note("gönderildi: web videosu")
                toast("Alıcıya gönderildi")
            } catch (e: Throwable) {
                toast("Gönderilemedi: ${e.message}")
            }
        }
    }

    /** Blocking request/answer exchange. Call from a background thread only. */
    fun request(name: String, params: Map<String, Any?>, dvb: Boolean, timeoutMs: Long = 20000): String {
        val d = device ?: throw IllegalStateException("Cihaz bağlı değil")
        val port = if (dvb) d.dvbSPort else d.sPort
        if (dvb && port == 0) throw IllegalStateException("Bu cihaz DVB desteklemiyor")
        if (!dvb && (port == 0 || d.appstore == 0)) throw IllegalStateException("Bu cihaz IPTV desteklemiyor")

        val httpPort = CallbackServer.ensureStarted()
        val phoneIp = Net.localIpFor(d.ip) ?: throw IllegalStateException("Wi-Fi bağlantısı yok")

        val m = LinkedHashMap<String, Any?>(params)
        m["name"] = name
        val id = md5(Json.obj(m) + SystemClock.uptimeMillis())
        m["url"] = "http://$phoneIp:$httpPort/dvb/$id"
        val bytes = ("json=" + Native.encode(Json.obj(m))).toByteArray()

        val q = ArrayBlockingQueue<String>(1)
        CallbackServer.pending[id] = q
        val sock = DatagramSocket()
        try {
            val addr = InetSocketAddress(InetAddress.getByName(d.ip), port)
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            var nextSend = 0L
            while (true) {
                val now = SystemClock.uptimeMillis()
                if (now >= deadline) throw IllegalStateException("Zaman aşımı: alıcı cevap vermedi")
                if (now >= nextSend) {
                    sock.send(DatagramPacket(bytes, bytes.size, addr))
                    nextSend = now + 4000   // UDP can drop packets; ask again
                }
                val r = q.poll(400, TimeUnit.MILLISECONDS)
                if (r != null) return decode(r)
            }
        } finally {
            CallbackServer.pending.remove(id)
            sock.close()
        }
    }

    private fun decode(body: String): String {
        return if (body.startsWith("json=")) Native.decode(body.substring(5)) else body
    }
}

/** The calls the original app makes, with its exact parameters. */
object Api {
    private const val EDITING = "{\"error_result\":\"STB is in the program editing state!\"}"

    class EditingState : IllegalStateException("Alıcı program düzenleme modunda")

    fun checkCrc(): Crc {
        val body = Box.request("/json/channel/check/crc", emptyMap(), true)
        if (body.trim() == EDITING) throw EditingState()
        return Parse.crc(body)
    }

    /** 1 = TV, 2 = radio. True when the box accepted. */
    fun switchType(service: Int): Boolean {
        val p = LinkedHashMap<String, Any?>()
        p["servicetype"] = service.toString()
        val o = Parse.managerObject(Box.request("/json/channel/switchtype", p, true))
        return o.optInt("result", -1) == 0
    }

    fun channelList(): ChannelList {
        return Parse.channelList(Box.request("/json/channel/list", emptyMap(), true, 45000))
    }

    private fun chParams(ch: Channel): Map<String, Any?> {
        val p = LinkedHashMap<String, Any?>()
        p["cid"] = ch.id.toString()
        p["cname"] = ch.name
        p["handle"] = ch.handle.toString()
        p["tp"] = ch.tp.toString()
        return p
    }

    /** Makes the box itself tune to this channel. */
    fun switchChannel(ch: Channel): Boolean {
        val o = Parse.managerObject(Box.request("/json/channel/handle/switch", chParams(ch), true))
        return o.optString("result") == "success"
    }

    /** Returns the "result" code, 0 means the stream can be opened. */
    fun checkLock(ch: Channel): Int {
        val o = Parse.managerObject(Box.request("/json/channel/check/lock", chParams(ch), true))
        return o.optInt("result", -1)
    }

    fun liveUrl(d: Device, ch: Channel): String = "http://${d.ip}:9891/live/${ch.id}"

    private fun iptvParams(type: String): LinkedHashMap<String, Any?> {
        val p = LinkedHashMap<String, Any?>()
        p["use"] = "post"
        p["len"] = 64
        p["type"] = type
        return p
    }

    fun iptvApps(): List<IptvApp> = Parse.iptvApps(Box.request("", iptvParams("app_list"), false))

    fun iptvGroups(appId: String): List<Group> = Parse.groups(Box.request(appId, iptvParams("group_list"), false))

    fun iptvChannels(gid: String): List<IptvChannel> =
        Parse.iptvChannels(Box.request(gid, iptvParams("ch_list"), false, 40000))

    fun iptvPlay(cid: String): PlayInfo = Parse.playInfo(Box.request(cid, iptvParams("play_url"), false))
}
