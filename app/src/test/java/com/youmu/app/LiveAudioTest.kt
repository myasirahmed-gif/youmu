package com.youmu.app

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Explicit network smoke test: run with -PliveAudio=true --tests '*LiveAudioTest'. */
class LiveAudioTest {
    @Test fun publicYouTubeAudioReturnsMediaBytes(){
        assumeTrue(System.getProperty("youmu.live")=="true")
        val source=AudioRepository.resolve("M7lc1UVf-VE")
        assertTrue(source.url.startsWith("https://"))
        val client=OkHttpClient.Builder().callTimeout(25,TimeUnit.SECONDS).build()
        val request=okhttp3.Request.Builder().url(source.url).header("User-Agent",AudioRepository.USER_AGENT).header("Range","bytes=0-1023").build()
        client.newCall(request).execute().use{response->
            assertTrue("Audio source HTTP ${response.code}",response.code in 200..299)
            val bytes=ByteArray(16)
            assertTrue("Expected media bytes",response.body!!.byteStream().read(bytes)>0)
        }
    }
    @Test fun publicPlaylistProvidesNativeQueue(){
        assumeTrue(System.getProperty("youmu.live")=="true")
        val playlist=AudioRepository.playlist("PLBCF2DAC6FFB574DE")
        assertTrue(playlist.tracks.isNotEmpty())
        assertTrue(playlist.tracks.all{PlaybackFormat.VIDEO_ID.matches(it.id)&&it.title.isNotBlank()})
    }
}
