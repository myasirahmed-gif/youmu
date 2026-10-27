package com.youmu.app

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class LoadingFailureTest{
    @Before fun reset(){PlaybackStatus.clear();AccountSession.token=null}
    private fun tree(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{tree(view.getChildAt(it))}else emptyList()
    @Test fun serviceFailureSurvivesServiceDestruction(){
        val owner=Robolectric.buildService(PlaybackService::class.java).create()
        owner.get().reportFailure(UnknownHostException(),"playlist")
        owner.destroy()
        assertTrue(PlaybackStatus.failed);assertFalse(PlaybackStatus.loading)
        assertTrue(PlaybackStatus.message.contains("DNS"));assertTrue(PlaybackStatus.diagnostics.contains("UnknownHostException"))
    }
    @Test fun failedPlaybackOffersAnExplicitRetry(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup();var command=""
        val state=AudioSnapshot(failed=true,message="Could not reach YouTube")
        val controls=object:AudioControls{
            override fun connect(onChange:(AudioSnapshot)->Unit){onChange(state)}
            override fun snapshot()=state
            override fun command(name:String,value:Long){command=name}
            override fun release(){}
        }
        val screen=PlayerScreen(owner.get(),Playlist("PL1234567890","Music"),{},{},{},controls=controls)
        owner.get().setContentView(screen.view)
        val retry=tree(screen.view).filterIsInstance<Button>().first{it.text=="Retry playback"}
        assertEquals(View.VISIBLE,retry.visibility);assertTrue(retry.isEnabled);retry.performClick();assertEquals("retry",command)
        screen.destroy();owner.pause().stop().destroy()
    }
    @Test fun aStalledPlaylistTimesOutAndRejectsLateResults(){
        val owner=Robolectric.buildService(PlaybackService::class.java).create();val service=owner.get()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        service.queueLoader={_,_,_->entered.countDown();try{finish.await(3,TimeUnit.SECONDS)}catch(_:InterruptedException){};throw java.io.IOException("late")}
        service.onStartCommand(Intent(service,PlaybackService::class.java).setAction(PlaybackService.ACTION_LOAD).putExtra(PlaybackService.EXTRA_PLAYLIST,"PL1234567890"),0,1)
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))
        assertTrue(PlaybackStatus.failed);assertFalse(PlaybackStatus.loading);assertTrue(PlaybackStatus.message.contains("timed out"))
        finish.countDown();owner.destroy();assertTrue(PlaybackStatus.failed)
    }
    @Test fun firstPageIsQueuedBeforeLaterPagesFinish(){
        val owner=Robolectric.buildService(PlaybackService::class.java).create();val service=owner.get()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val page=AudioPlaylist("Music",listOf(AudioTrack("a","First song","Artist","",10)))
        service.queueLoader={_,_,publish->publish(page);entered.countDown();try{finish.await(3,TimeUnit.SECONDS)}catch(_:InterruptedException){};page}
        service.onStartCommand(Intent(service,PlaybackService::class.java).setAction(PlaybackService.ACTION_LOAD).putExtra(PlaybackService.EXTRA_PLAYLIST,"PL1234567890"),0,1)
        assertTrue(entered.await(2,TimeUnit.SECONDS));shadowOf(Looper.getMainLooper()).idle()
        val field=PlaybackService::class.java.getDeclaredField("player").apply{isAccessible=true}
        assertEquals(1,(field.get(service)as Player).mediaItemCount)
        finish.countDown();owner.destroy()
    }
    @Test fun diagnosticsDoNotIncludeSecretsOrRequestUrls(){
        val error=PlaybackFailure.from(IllegalStateException("https://example.com/?access_token=secret"),"playlist")
        assertFalse(error.diagnostics.contains("secret"));assertFalse(error.diagnostics.contains("https://"))
        assertTrue(error.diagnostics.contains("SDK"))
        val compatibility=PlaybackFailure.from(NoSuchMethodError("private detail"),"audio")
        assertTrue(compatibility.message.contains("Android version"));assertTrue(compatibility.diagnostics.contains("NoSuchMethodError"))
    }
}
