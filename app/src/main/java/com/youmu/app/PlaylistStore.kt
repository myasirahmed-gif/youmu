package com.youmu.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("youmu_library", Context.MODE_PRIVATE)
    fun load(): MutableList<Playlist> = try {
        val array = JSONArray(prefs.getString("playlists", "[]"))
        (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            Playlist(p.getString("id"), p.getString("title"), p.optBoolean("favorite"), p.optLong("lastPlayed"), p.optBoolean("fromAccount"), p.optInt("count", -1), p.optLong("added"))
        }.toMutableList()
    } catch (_: Exception) { mutableListOf() }
    fun save(items: List<Playlist>) {
        val array = JSONArray()
        items.forEach { p -> array.put(JSONObject().put("id", p.id).put("title", p.title).put("favorite", p.favorite).put("lastPlayed", p.lastPlayed).put("fromAccount", p.fromAccount).put("count", p.count).put("added", p.added)) }
        prefs.edit().putString("playlists", array.toString()).apply()
    }
}
