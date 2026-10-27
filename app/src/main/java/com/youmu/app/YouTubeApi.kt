package com.youmu.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class YouTubeApi {
    class Unauthorized : Exception("Your YouTube session expired. Connect again.")
    private fun get(endpoint: String, params: Map<String, String>, token: String): JSONObject {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URI("https://www.googleapis.com/youtube/v3/$endpoint?$query").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer $token")
            val code = connection.responseCode
            if (code == 401) throw Unauthorized()
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(text)
            if (code !in 200..299) throw Exception(json.optJSONObject("error")?.optString("message") ?: "YouTube request failed ($code).")
            return json
        } finally { connection.disconnect() }
    }
    fun playlists(token: String): List<Playlist> {
        val items = mutableListOf<Playlist>()
        var next = ""
        do {
            val params = mutableMapOf("part" to "snippet,contentDetails", "mine" to "true", "maxResults" to "50")
            if (next.isNotBlank()) params["pageToken"] = next
            val page = get("playlists", params, token)
            val results = page.optJSONArray("items")
            if (results != null) for (i in 0 until results.length()) items.add(parse(results.getJSONObject(i)))
            next = page.optString("nextPageToken")
        } while (next.isNotBlank())
        return items
    }
    fun playlist(id: String, token: String): Playlist {
        val items = get("playlists", mapOf("part" to "snippet,contentDetails", "id" to id), token).getJSONArray("items")
        require(items.length() > 0) { "This playlist is private, unavailable, or belongs to another account." }
        return parse(items.getJSONObject(0))
    }
    fun tracks(id:String,token:String,onPage:((AudioPlaylist)->Unit)?=null):AudioPlaylist {
        val tracks=mutableListOf<AudioTrack>()
        var next="";var pages=0
        do {
            require(++pages<=200){"This playlist is too large to load in one session."}
            val params=mutableMapOf("part" to "snippet", "playlistId" to id, "maxResults" to "50")
            if(next.isNotBlank())params["pageToken"]=next
            val page=get("playlistItems",params,token)
            val items=page.optJSONArray("items")
            if(items!=null)for(i in 0 until items.length()){
                val snippet=items.getJSONObject(i).optJSONObject("snippet")?:continue
                val video=snippet.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                val title=snippet.optString("title")
                if(!PlaybackFormat.VIDEO_ID.matches(video)||title in listOf("Private video","Deleted video"))continue
                val art=snippet.optJSONObject("thumbnails")?.optJSONObject("high")?.optString("url").orEmpty()
                tracks.add(AudioTrack(video,title,snippet.optString("videoOwnerChannelTitle"),art,-1))
            }
            next=page.optString("nextPageToken")
            if(tracks.isNotEmpty())onPage?.invoke(AudioPlaylist("",tracks.toList()))
        }while(next.isNotBlank())
        require(tracks.isNotEmpty()){"No available videos were found in this playlist."}
        return AudioPlaylist("",tracks)
    }
    private fun parse(p: JSONObject) = Playlist(p.getString("id"), p.getJSONObject("snippet").getString("title"), fromAccount = true, count = p.optJSONObject("contentDetails")?.optInt("itemCount", -1) ?: -1)
}
