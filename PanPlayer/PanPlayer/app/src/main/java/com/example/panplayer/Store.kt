package com.example.panplayer
import android.content.*
import org.json.*

data class Rec(val id: Long, val dir: String, val name: String)

object Store {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("pp", 0) }
    private fun str(k: String) = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    var fileGrid: Boolean
        get() = sp.getBoolean("fileGrid", false)
        set(v) { sp.edit().putBoolean("fileGrid", v).apply() }
    var token: String
        get() = str("token")
        set(v) = put("token", v)
    var appKey: String
        get() = str("appKey")
        set(v) = put("appKey", v)
    var tmdb: String
        get() = str("tmdb")
        set(v) = put("tmdb", v)
    fun pos(id: Long) = sp.getLong("pos$id", 0L)
    fun setPos(id: Long, v: Long) = sp.edit().putLong("pos$id", v).apply()
    fun frac(id: Long) = sp.getFloat("frac$id", 0f)
    fun setFrac(id: Long, v: Float) = sp.edit().putFloat("frac$id", v).apply()
    var roots: List<String>
        get() = str("roots").split("\n").filter { it.isNotEmpty() }
        set(v) = put("roots", v.joinToString("\n"))
    fun poster(t: String): String? = if (sp.contains("p:$t")) str("p:$t") else null
    fun setPoster(t: String, u: String) = put("p:$t", u)
    fun recent(): List<Rec> {
        val a = JSONArray(sp.getString("recent", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Rec(o.getLong("id"), o.getString("dir"), o.getString("name")) }
    }
    fun addRecent(r: Rec) {
        val a = JSONArray()
        (listOf(r) + recent().filter { it.id != r.id }).take(15).forEach {
            a.put(JSONObject().put("id", it.id).put("dir", it.dir).put("name", it.name))
        }
        put("recent", a.toString())
    }
}
