package com.mhttn.sunplusremote

import org.json.JSONArray
import org.json.JSONObject

data class Channel(
    val id: Int,
    val handle: Int,
    val name: String,
    val tp: Int,
    val encrypt: Boolean,
    val subs: List<Int>,
    val service: Int
) {
    /** Stable key used for favorites. */
    val favKey: String get() = "$service:$id:$name"
}

data class Handle(val handle: Int, val id: Int, val name: String, val parent: Int)

class ChannelList(
    val service: Int,
    val channels: List<Channel>,
    val main: List<Handle>,
    val subs: Map<String, List<Handle>>
) {
    /** Sub categories (name -> items) that belong to the given main handle. */
    fun subsFor(mainHandle: Int): Map<String, List<Handle>> {
        val out = LinkedHashMap<String, List<Handle>>()
        for ((k, v) in subs) {
            val l = v.filter { it.parent == mainHandle }
            if (l.isNotEmpty()) out[k] = l
        }
        return out
    }
}

data class Crc(val operate: String, val serviceType: Int, val proHandle: Int, val result: Int)

data class IptvApp(
    val id: String,
    val name: String,
    val parent: String,
    val children: MutableList<IptvApp> = ArrayList()
)

data class Group(val gid: String, val name: String, val type: String)

data class IptvChannel(val cid: String, val gid: String, val name: String, val url: String)

data class PlayInfo(val url: String, val ua: String?)

object Parse {
    const val CHUNK_SEPARATOR = "==UCAST_NEXT_LIST=="

    private fun bool(o: JSONObject, key: String): Boolean {
        val v = o.opt(key)
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "true" || v == "1"
            else -> false
        }
    }

    private fun ints(a: JSONArray?): List<Int> {
        if (a == null) return emptyList()
        val out = ArrayList<Int>()
        for (i in 0 until a.length()) out.add(a.optInt(i))
        return out
    }

    private fun handles(a: JSONArray?, into: LinkedHashMap<Int, Handle>) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val h = Handle(o.optInt("handle"), o.optInt("id"), o.optString("name"), o.optInt("parent_handle"))
            into[h.handle] = h
        }
    }

    /** Box answers a channel list as several JSON chunks glued with a separator. */
    fun channelList(body: String): ChannelList {
        var service = 0
        val chans = ArrayList<Channel>()
        val rawChans = ArrayList<JSONObject>()
        val main = LinkedHashMap<Int, Handle>()
        val subs = LinkedHashMap<String, LinkedHashMap<Int, Handle>>()

        for (chunk in body.split(CHUNK_SEPARATOR)) {
            val t = chunk.trim()
            if (t.isEmpty()) continue
            val j = JSONObject(t)
            val head = j.optJSONObject("chnlist_head")
            if (head != null) service = head.optInt("service", service)
            val arr = j.optJSONArray("chnlist")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    rawChans.add(o)
                }
            }
            handles(j.optJSONArray("main"), main)
            val so = j.optJSONObject("subs")
            if (so != null) {
                val keys = so.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val m = subs.getOrPut(k) { LinkedHashMap() }
                    handles(so.optJSONArray(k), m)
                }
            }
        }
        for (o in rawChans) {
            chans.add(
                Channel(
                    id = o.optInt("id"),
                    handle = o.optInt("handle"),
                    name = o.optString("name"),
                    tp = o.optInt("tp"),
                    encrypt = bool(o, "encrypt"),
                    subs = ints(o.optJSONArray("sub_child_handle")),
                    service = service
                )
            )
        }
        val subsOut = LinkedHashMap<String, List<Handle>>()
        for ((k, v) in subs) subsOut[k] = ArrayList(v.values)
        return ChannelList(service, chans, ArrayList(main.values), subsOut)
    }

    fun crc(body: String): Crc {
        val j = JSONObject(body)
        val o = j.optJSONObject("channel_manager") ?: j.optJSONObject("channel_manage")
            ?: throw IllegalStateException(body)
        return Crc(
            o.optString("operate"),
            o.optInt("servicetype"),
            o.optInt("prohandle"),
            o.optInt("result", -1)
        )
    }

    fun managerObject(body: String): JSONObject {
        val j = JSONObject(body)
        return j.optJSONObject("channel_manager") ?: j.optJSONObject("channel_manage")
            ?: throw IllegalStateException(body)
    }

    /** Roots are apps whose parens_id is "0"; others hang under their parent. */
    fun iptvApps(body: String): List<IptvApp> {
        val j = JSONObject(body)
        val arr = j.optJSONArray("list") ?: return emptyList()
        val all = ArrayList<IptvApp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            all.add(IptvApp(o.optString("id"), o.optString("name"), o.optString("parens_id")))
        }
        val roots = LinkedHashMap<String, IptvApp>()
        for (a in all) if (a.parent == "0") roots[a.id] = a
        for (a in all) {
            if (a.parent == "0") continue
            roots[a.parent]?.children?.add(a)
        }
        return ArrayList(roots.values)
    }

    fun groups(body: String): List<Group> {
        val j = JSONObject(body)
        val arr = j.optJSONArray("Group") ?: return emptyList()
        val out = ArrayList<Group>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Group(o.optString("gid"), o.optString("name"), o.optString("type")))
        }
        return out
    }

    fun iptvChannels(body: String): List<IptvChannel> {
        val j = JSONObject(body)
        val arr = j.optJSONArray("channel") ?: return emptyList()
        val out = ArrayList<IptvChannel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(IptvChannel(o.optString("cid"), o.optString("gid"), o.optString("name"), o.optString("url")))
        }
        return out
    }

    fun playInfo(body: String): PlayInfo {
        val j = JSONObject(body)
        val arr = j.optJSONArray("play") ?: throw IllegalStateException(body)
        val o = arr.optJSONObject(0) ?: throw IllegalStateException(body)
        val ua = o.optString("ua")
        return PlayInfo(o.optString("url"), if (ua.isEmpty()) null else ua)
    }
}
