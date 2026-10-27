package com.youmu.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class TrackMetadata(val title: String, val artist: String, val artwork: Bitmap?)

object TrackMetadataLoader {
    fun artwork(videoId:String):Bitmap? {
        require(PlaybackFormat.VIDEO_ID.matches(videoId))
        return runCatching {
            val request=connection("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
            try {
                if(request.responseCode==200)request.inputStream.use{input->
                    val bytes=input.readBytesLimited(2_000_000);BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                }else null
            }finally{request.disconnect()}
        }.getOrNull()
    }
    fun load(videoId: String): TrackMetadata {
        require(PlaybackFormat.VIDEO_ID.matches(videoId))
        var title = "YouTube track"
        var artist = "YouTube"
        runCatching {
            val target = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val connection = connection("https://www.youtube.com/oembed?url=$target&format=json")
            try {
                if (connection.responseCode == 200) {
                    val body = connection.inputStream.use { String(it.readBytesLimited(30_000),Charsets.UTF_8) }
                    val data = JSONObject(body)
                    title = data.optString("title", title).take(300)
                    artist = data.optString("author_name", artist).take(150)
                }
            } finally { connection.disconnect() }
        }
        val bitmap = runCatching {
            val connection = connection("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
            try {
                if (connection.responseCode == 200) connection.inputStream.use { input ->
                    val bytes = input.readBytesLimited(2_000_000)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            } finally { connection.disconnect() }
        }.getOrNull()
        return TrackMetadata(title, artist, bitmap)
    }
    private fun connection(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 8_000; instanceFollowRedirects = false
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n=read(buffer);if(n<0)break;require(output.size()+n<=limit);output.write(buffer,0,n) }
        return output.toByteArray()
    }
}
