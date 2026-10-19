package com.example.panplayer
import android.content.Context
import org.json.*
import java.io.File
import java.net.URLEncoder

data class Ep(val s: Int, val e: Int)
data class Vid(val id: Long, val path: String, val name: String)
data class Group(val title: String, val vids: List<Vid>, val isShow: Boolean)

object Lib {
    private val SE = Regex("""[Ss](\d{1,2})[ ._-]?[Ee][Pp]?(\d{1,3})""")
    private val CN = Regex("""第\s*(\d{1,3})\s*[集话話]""")
    private val EPN = Regex("""(?:^|[ ._\[-])[Ee][Pp]?(\d{1,3})(?:[ ._\]-]|$)""")
    private val SEASON = Regex("(?i)^(season\\s*\\d+|s\\d+|第.{1,3}季|specials?)$")

    fun ep(name: String): Ep? {
        SE.find(name)?.let { return Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        CN.find(name)?.let { return Ep(1, it.groupValues[1].toInt()) }
        EPN.find(name)?.let { return Ep(1, it.groupValues[1].toInt()) }
        return null
    }

    fun title(v: Vid): String {
        val n = v.name.substringBeforeLast('.')
        if (ep(v.name) != null) {
            val m = SE.find(n) ?: CN.find(n) ?: EPN.find(n)
            val t = if (m != null) Tmdb.cleanTitle(n.substring(0, m.range.first)) else ""
            if (t.isNotBlank()) return t
            var dirs = v.path.split('/').dropLast(1).filter { it.isNotEmpty() }
            if (dirs.isNotEmpty() && SEASON.matches(dirs.last())) dirs = dirs.dropLast(1)
            val p = dirs.lastOrNull()?.let { Tmdb.cleanTitle(it) }.orEmpty()
            if (p.isNotBlank()) return p
        }
        return Tmdb.clean(v.name).ifBlank { v.name }
    }

    fun group(vs: List<Vid>): List<Group> = vs.groupBy { title(it) }.map { (t, l) ->
        Group(t, l.sortedWith(compareBy<Vid>({ ep(it.name)?.s ?: 0 }, { ep(it.name)?.e ?: 0 }, { it.name })),
            l.size > 1 && l.any { ep(it.name) != null })
    }.sortedBy { it.title }

    private fun file(c: Context) = File(c.filesDir, "lib.json")
    fun load(c: Context): List<Vid> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { val o = a.getJSONObject(it); Vid(o.getLong("i"), o.getString("p"), o.getString("n")) }
    }.getOrDefault(emptyList())

    private fun listAll(dir: String): List<Item> {
        val out = ArrayList<Item>(); var cur = 0L
        while (true) {
            val j = Baidu.get("https://pan.baidu.com/rest/2.0/xpan/multimedia?method=listall&recursion=1&limit=1000&start=$cur" +
                "&path=${URLEncoder.encode(dir, "UTF-8")}&access_token=${Store.token}")
            if (j.optInt("errno") != 0) error("扫描失败 errno=${j.optInt("errno")}")
            val a = j.optJSONArray("list")
            if (a != null) for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optInt("isdir") == 0) out.add(Item(o.getLong("fs_id"), o.getString("path"), o.getString("server_filename"), o.optLong("size"), false))
            }
            if (j.optInt("has_more") != 1) break
            cur = j.optLong("cursor"); Thread.sleep(400)
        }
        return out
    }

    fun scan(c: Context, roots: List<String>, step: (String) -> Unit): List<Vid> {
        val out = ArrayList<Vid>()
        for (r in roots) { step(r); listAll(r).filter { Fx.VIDEO.matches(it.name) }.forEach { out.add(Vid(it.fsId, it.path, it.name)) } }
        val res = out.distinctBy { it.id }
        val a = JSONArray(); res.forEach { a.put(JSONObject().put("i", it.id).put("p", it.path).put("n", it.name)) }
        file(c).writeText(a.toString()); return res
    }
}
