package com.youmu.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDownloadManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class MusicFeaturesTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun tree(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{tree(v.getChildAt(it))}else emptyList()
    @Before fun reset(){
        listOf("youmu_library","youmu_local_tracks","youmu_downloads","youmu_player").forEach{app.getSharedPreferences(it,Context.MODE_PRIVATE).edit().clear().commit()}
        PlaybackStatus.clear();AudioVisuals.enabled=false;AudioVisuals.clear();Ui.apply(app)
    }
    @Test fun newLocalPlaylistKeepsImportedLibraryAndDeduplicatesSongs(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        PlaylistStore(app).save(listOf(Playlist("PL1234567890","Remote",favorite=true)))
        val song=QueueTrack("abcdefghijk","My song","Artist")
        LocalMusic.create(activity,song)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        tree(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("Evening mix")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val saved=PlaylistStore(app).load();assertEquals(2,saved.size);assertTrue(saved.first().favorite)
        val local=saved.last();assertTrue(LocalMusic.isLocal(local.id));assertEquals("Evening mix",local.title)
        val music=LocalMusic(app);assertEquals(listOf(song),music.tracks(local.id));assertFalse(music.add(local.id,song))
        music.add(local.id,QueueTrack("lmnopqrstuv","Second","Other"));assertEquals(2,LocalMusic(app).audioPlaylist(local.id).tracks.size)
        music.remove(local.id,song.id);assertEquals("Second",LocalMusic(app).tracks(local.id).single().title)
        owner.pause().stop().destroy()
    }
    @Test fun serviceRestoresModesAndUsesNativeRepeatShuffle(){
        PlayerPreferences(app).mode=2
        val owner=Robolectric.buildService(PlaybackService::class.java).create()
        val field=PlaybackService::class.java.getDeclaredField("player").apply{isAccessible=true};val player=field.get(owner.get()) as Player
        assertEquals(Player.REPEAT_MODE_ALL,player.repeatMode)
        PlaybackModes.apply(player,1);assertTrue(player.shuffleModeEnabled);assertEquals(Player.REPEAT_MODE_OFF,player.repeatMode)
        assertEquals(1,PlayerPreferences(app).mode)
        PlaybackModes.apply(player,3);assertFalse(player.shuffleModeEnabled);assertEquals(Player.REPEAT_MODE_ONE,player.repeatMode)
        assertEquals(3,PlayerPreferences(app).mode)
        PlaybackModes.apply(player,0);assertEquals(Player.REPEAT_MODE_OFF,player.repeatMode);assertEquals(0,PlayerPreferences(app).mode)
        owner.destroy()
    }
    @Test fun controlsOfferAllModesAndDisplayChoicePersistsWithoutStoppingMusic(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get();val calls=mutableListOf<Pair<String,Long>>()
        val snapshot=AudioSnapshot(connected=true,playing=true,index=0,tracks=listOf(QueueTrack("test","Night Drive","YouMu")))
        val control=object:AudioControls{
            override fun connect(onChange:(AudioSnapshot)->Unit){onChange(snapshot)}
            override fun snapshot()=snapshot
            override fun command(name:String,value:Long){calls.add(name to value)}
            override fun release(){}
        }
        val screen=PlayerScreen(activity,Playlist("PL1234567890","Mix"),{},{},{},controls=control)
        activity.setContentView(screen.view)
        tree(screen.view).first{it.contentDescription=="Playback mode"}.performClick()
        ShadowAlertDialog.getLatestAlertDialog().let{it.listView.performItemClick(null,3,3)}
        assertEquals("mode" to 3L,calls.single())
        tree(screen.view).first{it.contentDescription=="Display settings"}.performClick()
        ShadowAlertDialog.getLatestAlertDialog().let{it.listView.performItemClick(null,2,2)}
        assertEquals(2,PlayerPreferences(activity).visual);assertTrue(AudioVisuals.enabled)
        assertEquals(View.VISIBLE,tree(screen.view).filterIsInstance<MusicVisualView>().single().visibility)
        screen.pause();assertFalse(AudioVisuals.enabled);assertEquals(1,calls.size)
        screen.resume();assertTrue(AudioVisuals.enabled);screen.destroy();assertFalse(AudioVisuals.enabled)
        owner.pause().stop().destroy()
    }
    @Test fun fftRespondsToRealFrequencyAndTeeLeavesAudioBytesUnchanged(){
        val samples=FloatArray(512){(.8*sin(2*PI*1000*it/44100)).toFloat()}
        val tone=AudioVisuals.analyze(samples,44100)
        assertTrue(tone.level>.5f);assertTrue(tone.spectrum.max()>.8f)
        val silence=AudioVisuals.analyze(FloatArray(512),44100);assertEquals(0f,silence.level,0f);assertTrue(silence.spectrum.all{it==0f})
        val tee=TeeAudioProcessor(MusicSampleSink());tee.configure(AudioProcessor.AudioFormat(44100,1,C.ENCODING_PCM_16BIT));tee.flush()
        val input=ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach{input.putShort((it*32767).toInt().toShort())};input.flip()
        val expected=ByteArray(1024);input.duplicate().get(expected)
        AudioVisuals.enabled=true;tee.queueInput(input);val actual=ByteArray(1024);tee.output.get(actual)
        assertArrayEquals(expected,actual);assertTrue(AudioVisuals.frame.level>.5f)
        tee.reset();assertEquals(0f,AudioVisuals.frame.level,0f)
    }
    @Test fun downloadsUseOriginalFormatAndPublicMusicDirectoryAndPreventDuplicateTransfer(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(app).denyPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertEquals(android.os.Build.VERSION.SDK_INT<=28,MusicDownloads.needsPermission(owner.get()))
        shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertFalse(MusicDownloads.needsPermission(owner.get()))
        val downloads=MusicDownloads(app);val track=QueueTrack("abcdefghijk","My / song?","Artist")
        val id=downloads.enqueue(track,ResolvedAudio("https://example.com/audio","audio/mp4"))
        val manager=app.getSystemService(DownloadManager::class.java)
        val request=shadowOf(manager).getRequest(id)
        val shadow=Shadow.extract<ShadowDownloadManager.ShadowRequest>(request)
        assertTrue(shadow.destination.path!!.contains("Music/YouMu/"));assertTrue(shadow.destination.path!!.endsWith(".m4a"))
        assertEquals("audio/mp4",shadow.mimeType);assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,shadow.notificationVisibility)
        shadow.status=DownloadManager.STATUS_RUNNING
        assertEquals(id,MusicDownloads(app).enqueue(track,ResolvedAudio("https://example.com/other","audio/mp4")))
        assertEquals(1,downloads.entries().size);assertNull(downloads.localUri(track.id))
        shadow.status=DownloadManager.STATUS_FAILED
        val retry=downloads.enqueue(track,ResolvedAudio("https://example.com/fresh","audio/webm"))
        assertNotEquals(id,retry);assertTrue(MusicDownloads.filename(track,"audio/webm").endsWith(".webm"))
        downloads.remove(downloads.entries().single());assertTrue(downloads.entries().isEmpty())
        owner.pause().stop().destroy()
    }
    @Test fun themesPersistAndDoNotAlterExistingPlaylistMetadata(){
        val store=PlaylistStore(app);store.save(listOf(Playlist("PL1234567890","Saved mix",favorite=true,fromAccount=true,lastPlayed=42)))
        PlayerPreferences(app).theme=1;Ui.apply(app);val ocean=Ui.accent
        PlayerPreferences(app).theme=2;Ui.apply(app);assertNotEquals(ocean,Ui.accent);assertEquals(android.graphics.Color.BLACK,Ui.background)
        assertEquals(2,PlayerPreferences(app).theme);assertTrue(store.load().single().favorite);assertTrue(store.load().single().fromAccount);assertEquals(42L,store.load().single().lastPlayed)
        val permissions=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertFalse(permissions.contains("android.permission.RECORD_AUDIO"))
    }
}
