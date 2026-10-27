package com.youmu.app

import java.net.URI
import java.net.URLDecoder

data class Playlist(
    val id: String,
    var title: String,
    var favorite: Boolean = false,
    var lastPlayed: Long = 0,
    val fromAccount: Boolean = false,
    var count: Int = -1,
    val added: Long = System.currentTimeMillis()
)

object PlaylistLinks {
    fun parse(value: String): String {
        val input = value.trim()
        if (Regex("^(PL|UU|OLAK5uy_|RD)[A-Za-z0-9_-]{8,}$").matches(input)) return input
        val url = try { URI(input) } catch (_: Exception) { throw IllegalArgumentException("Paste a valid YouTube playlist link.") }
        require(url.scheme in listOf("https", "http") && url.host in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) { "Please use a YouTube playlist link." }
        val id = url.rawQuery?.split('&')?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.first() == "list" }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
        require(id != null && Regex("^[A-Za-z0-9_-]{10,150}$").matches(id)) { "This link has no playlist. Copy the playlist link from YouTube." }
        return id
    }
}
