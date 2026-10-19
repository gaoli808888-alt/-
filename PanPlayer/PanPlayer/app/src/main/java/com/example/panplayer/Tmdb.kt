package com.example.panplayer
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object Tmdb {
    private val junk = Regex("""[\[(（【].*?[\])）】]|\b(19|20)\d{2}\b.*|(2160p|1080p|720p|4k|bluray|web-?dl|hdr|x26[45]|h26[45]|hevc|aac|dts).*""", RegexOption.IGNORE_CASE)
    fun cleanTitle(s: String): String = junk.replace(s.replace('.', ' ').replace('_', ' '), "").trim()
    fun clean(name: String): String = cleanTitle(name.substringBeforeLast('.'))

    fun byTitle(t: String): String? {
        if (Store.tmdb.isBlank() || t.isEmpty()) return null
        Store.poster(t)?.let { return it.ifEmpty { null } }
        val u = "https://api.themoviedb.org/3/search/multi?language=zh-CN&query=${URLEncoder.encode(t, "UTF-8")}&api_key=${Store.tmdb}"
        val body = Baidu.client.newCall(Request.Builder().url(u).build()).execute().use { it.body?.string() } ?: return null
        val rs = JSONObject(body).optJSONArray("results")
        var url = ""
        if (rs != null) for (i in 0 until rs.length()) {
            val p = rs.getJSONObject(i).optString("poster_path")
            if (p.isNotEmpty() && p != "null") { url = "https://image.tmdb.org/t/p/w342$p"; break }
        }
        Store.setPoster(t, url)
        return url.ifEmpty { null }
    }
}
