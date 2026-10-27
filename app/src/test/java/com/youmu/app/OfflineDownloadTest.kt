package com.youmu.app

import android.app.DownloadManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class OfflineDownloadTest {
    @Test fun completedDownloadPlaysFromLocalProviderAndMissingFileIsDetected(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("youmu_downloads",Context.MODE_PRIVATE).edit().clear().commit()
        val file=File(app.cacheDir,"saved-audio.webm").apply{writeBytes(byteArrayOf(1,2,3,4))}
        val provider=object:ContentProvider(){
            override fun onCreate()=true
            override fun getType(uri:Uri)="audio/webm"
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor?=null
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
            override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        provider.attachInfo(app,android.content.pm.ProviderInfo().apply{authority="downloads";exported=true;grantUriPermissions=true})
        ShadowContentResolver.registerProviderInternal("downloads",provider)
        val downloads=MusicDownloads(app);val track=QueueTrack("abcdefghijk","Offline song","Artist")
        val id=downloads.enqueue(track,ResolvedAudio("https://example.com/audio","audio/webm"))
        val manager=app.getSystemService(DownloadManager::class.java)
        Shadow.extract<ShadowDownloadManager.ShadowRequest>(shadowOf(manager).getRequest(id)).status=DownloadManager.STATUS_SUCCESSFUL
        assertEquals(DownloadManager.STATUS_SUCCESSFUL,downloads.state(downloads.entries().single())!!.status)
        val systemUri=manager.getUriForDownloadedFile(id);assertNotNull("DownloadManager must supply a saved file URI",systemUri)
        app.contentResolver.openFileDescriptor(systemUri!!,"r")!!.use{assertEquals(4L,it.statSize)}
        val uri=downloads.localUri(track.id);assertNotNull(uri);assertEquals("content",uri!!.scheme)
        assertEquals(track.id,downloads.audioPlaylist().tracks.single().id)
        val owner=Robolectric.buildService(PlaybackService::class.java).create()
        assertEquals("Offline song",owner.get().queueLoader("youmu_downloads",false,{}).tracks.single().title)
        owner.destroy()
        assertTrue(file.delete());assertNull(downloads.localUri(track.id))
    }
}
