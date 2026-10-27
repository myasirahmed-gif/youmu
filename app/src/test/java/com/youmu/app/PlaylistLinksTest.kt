package com.youmu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistLinksTest {
    @Test fun parsesYouTubeAndMusicLinks() {
        listOf("https://www.youtube.com/playlist?list=PL1234567890", "https://music.youtube.com/playlist?list=PL1234567890", "https://youtu.be/abcdefghijk?list=PL1234567890", "PL1234567890").forEach { assertEquals("PL1234567890",PlaylistLinks.parse(it)) }
    }
    @Test fun rejectsOtherHostsAndMissingPlaylists() {
        listOf("https://youtube.com.evil.example/?list=PL1234567890", "javascript:alert(1)", "https://youtube.com/watch?v=abcdefghijk", "file://youtube.com/?list=PL1234567890", "hello").forEach { assertThrows(IllegalArgumentException::class.java) { PlaylistLinks.parse(it) } }
    }
    @Test fun acceptsEncodedIdsAndRejectsInjectedCharacters() {
        assertEquals("PL1234567890",PlaylistLinks.parse("https://youtube.com/playlist?list=%50%4C1234567890"))
        assertThrows(IllegalArgumentException::class.java) { PlaylistLinks.parse("https://youtube.com/playlist?list=PL1234567890%22%3E") }
    }
}
