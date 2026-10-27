package com.youmu.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.SeekBar
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class NativePlayerTest{
    private class FakeControls:AudioControls{
        var value=AudioSnapshot();var released=false;val calls=mutableListOf<Pair<String,Long>>()
        override fun connect(onChange:(AudioSnapshot)->Unit){onChange(value)}
        override fun snapshot()=value
        override fun command(name:String,value:Long){calls.add(name to value)}
        override fun release(){released=true}
    }
    private fun tree(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{tree(view.getChildAt(it))}else emptyList()
    @Test fun nativeArtworkPlayerControlsFollowTheTimeline(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get();val controls=FakeControls()
        val screen=PlayerScreen(activity,Playlist("PL1234567890","Late night"),{},{},{},controls=controls)
        activity.setContentView(screen.view)
        assertTrue(tree(screen.view).none{it is WebView})
        assertFalse(tree(screen.view).first{it.contentDescription=="Play"}.isEnabled)
        controls.value=AudioSnapshot(connected=true,playing=true,position=30_000,duration=120_000,index=0,tracks=listOf(QueueTrack("a","Song A","Artist A"),QueueTrack("b","Song B","Artist B")))
        screen.render(controls.value)
        tree(screen.view).first{it.contentDescription=="Pause"}.performClick();assertEquals("pause",controls.calls.last().first)
        assertEquals(250,tree(screen.view).filterIsInstance<SeekBar>().single().progress)
        assertTrue(tree(screen.view).filterIsInstance<TextView>().any{it.text=="Song A"})
        assertTrue(tree(screen.view).filterIsInstance<TextView>().any{it.text=="0:30"})
        screen.destroy();owner.pause().stop().destroy()
    }
    @Test fun screenOffAndUiDestructionNeverPauseOrStopTheService(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get();val controls=FakeControls()
        val screen=PlayerScreen(activity,Playlist("PL1234567890","Late night"),{},{},{},controls=controls)
        activity.setContentView(screen.view)
        screen.pause();assertTrue(controls.calls.isEmpty());assertFalse(controls.released)
        screen.resume();screen.destroy();screen.destroy()
        assertTrue(controls.released);assertTrue(controls.calls.isEmpty())
        owner.pause().stop().destroy()
    }
    @Test fun queueSelectsTheRequestedNativeTrack(){
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val controls=FakeControls();val activity=owner.get()
        val screen=PlayerScreen(activity,Playlist("PL1234567890","Late night"),{},{},{},controls=controls)
        activity.setContentView(screen.view)
        screen.render(AudioSnapshot(connected=true,index=0,tracks=listOf(QueueTrack("a","Song A","A"),QueueTrack("b","Song B","B"))))
        tree(screen.view).first{it.contentDescription=="Open queue"}.performClick()
        val dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.listView.performItemClick(dialog.listView.getChildAt(1),1,1)
        assertEquals("track" to 1L,controls.calls.last());screen.destroy();owner.pause().stop().destroy()
    }
    @Test fun serviceDeclaresBackgroundAudioPermissions(){
        val app=RuntimeEnvironment.getApplication()
        val service=app.packageManager.getServiceInfo(ComponentName(app,PlaybackService::class.java),0)
        if(android.os.Build.VERSION.SDK_INT>=29)assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,service.foregroundServiceType)
        assertFalse(service.exported)
        val permissions=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"));assertTrue(permissions.contains("android.permission.WAKE_LOCK"))
    }
    @Test fun mediaServiceCreatesAndReleasesItsSession(){
        val owner=Robolectric.buildService(PlaybackService::class.java).create()
        val service=owner.get()
        PlaybackStatus.playlistId="test"
        service.onStartCommand(Intent(service,PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP),0,1)
        assertEquals("",PlaybackStatus.playlistId)
        owner.destroy()
    }
    @Test fun timingAndSourceValidationAreSafe(){
        assertEquals(250,AudioSnapshot(position=30_000,duration=120_000).progress)
        assertEquals(0,AudioSnapshot(position=30_000,duration=0).progress)
        assertEquals("0:00",PlaybackFormat.time(Double.NaN));assertEquals("1:01:01",PlaybackFormat.time(3661.0))
        assertNull(AudioRepository.videoId("https://youtube.com.evil.example/watch?v=abcdefghijk"))
        assertEquals("abcdefghijk",AudioRepository.videoId("https://youtu.be/abcdefghijk"))
    }
}
