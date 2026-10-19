package com.example.panplayer
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder

data class Item(val fsId: Long, val path: String, val name: String, val size: Long, val isDir: Boolean)

object Fx {
    val VIDEO = Regex(".*\\.(mp4|mkv|mov|avi|webm|m4v|flv|ts|wmv|rmvb)$", RegexOption.IGNORE_CASE)
    val SUB = Regex(".*\\.(srt|ass|ssa|vtt)$", RegexOption.IGNORE_CASE)
}

object Baidu {
    const val UA = "pan.baidu.com"
    val client: OkHttpClient = OkHttpClient()

    fun get(url: String): JSONObject {
        val r = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(r).execute().use { return JSONObject(it.body!!.string()) }
    }

    fun list(dir: String): List<Item> {
        val j = get("https://pan.baidu.com/rest/2.0/xpan/file?method=list&order=name&limit=1000" +
            "&dir=${URLEncoder.encode(dir, "UTF-8")}&access_token=${Store.token}")
        val e = j.optInt("errno")
        if (e != 0) error("百度返回错误 errno=$e（-6 表示登录已过期，请退出重登）")
        val a = j.getJSONArray("list")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getLong("fs_id"), o.getString("path"), o.getString("server_filename"), o.optLong("size"), o.optInt("isdir") == 1)
        }
    }

    fun dlinks(ids: List<Long>): Map<Long, String> = ids.chunked(100).flatMap { c ->
        val arr = URLEncoder.encode(c.joinToString(",", "[", "]"), "UTF-8")
        val j = get("https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&dlink=1&fsids=$arr&access_token=${Store.token}")
        val a = j.optJSONArray("list") ?: return@flatMap emptyList<Pair<Long, String>>()
        (0 until a.length()).mapNotNull {
            val o = a.getJSONObject(it)
            val d = o.optString("dlink")
            if (d.isEmpty()) null else o.getLong("fs_id") to (d + "&access_token=" + Store.token)
        }
    }.toMap()
}
