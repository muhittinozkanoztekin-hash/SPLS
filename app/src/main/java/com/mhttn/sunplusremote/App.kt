package com.mhttn.sunplusremote

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SunplusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }
}

object App {
    lateinit var ctx: Context
        private set
    val main = Handler(Looper.getMainLooper())
    val pool: ExecutorService = Executors.newCachedThreadPool()

    fun init(c: Context) {
        ctx = c.applicationContext
    }
}

/** Run on the UI thread. */
fun ui(block: () -> Unit) {
    App.main.post { block() }
}

/** Run on a background thread; exceptions are printed, never crash the app. */
fun bg(block: () -> Unit) {
    App.pool.execute {
        try {
            block()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}

fun toast(msg: String) {
    ui { Toast.makeText(App.ctx, msg, Toast.LENGTH_SHORT).show() }
}

fun toastLong(msg: String) {
    ui { Toast.makeText(App.ctx, msg, Toast.LENGTH_LONG).show() }
}

object Prefs {
    private fun sp() = App.ctx.getSharedPreferences("ucast", Context.MODE_PRIVATE)

    fun getString(k: String): String? = sp().getString(k, null)

    fun putString(k: String, v: String?) {
        sp().edit().putString(k, v).apply()
    }

    fun getSet(k: String): MutableSet<String> {
        val s = sp().getStringSet(k, null)
        return if (s == null) HashSet() else HashSet(s)
    }

    fun putSet(k: String, v: Set<String>) {
        sp().edit().putStringSet(k, HashSet(v)).apply()
    }
}

/**
 * Tiny JSON writer. The receiver's firmware was written against fastjson
 * output (compact, "/" NOT escaped), while android.org.json escapes "/" as
 * "\/" - so we serialize by hand to stay byte-compatible with the original app.
 */
object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun obj(m: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(',')
            first = false
            sb.append(str(k)).append(':')
            when (v) {
                null -> sb.append("null")
                is Number -> sb.append(v.toString())
                is Boolean -> sb.append(v.toString())
                else -> sb.append(str(v.toString()))
            }
        }
        sb.append('}')
        return sb.toString()
    }
}

fun md5(s: String): String {
    val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
    val sb = StringBuilder()
    for (b in d) sb.append(String.format("%02x", b))
    return sb.toString()
}

object Net {
    /** The phone's own IPv4 on the same subnet as the box (what the box can reach). */
    fun localIpFor(boxIp: String): String? {
        try {
            val box = InetAddress.getByName(boxIp).address
            var fallback: String? = null
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a !is Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    if (sameSubnet(a.address, box, ia.networkPrefixLength.toInt())) return ip
                    if (fallback == null && a.isSiteLocalAddress) fallback = ip
                }
            }
            return fallback
        } catch (e: Exception) {
            return null
        }
    }

    fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        if (a.size != 4 || b.size != 4) return false
        var bits = prefix
        for (i in 0 until 4) {
            val mask = if (bits >= 8) 0xFF else if (bits <= 0) 0 else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
