package com.youmu.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

data class MusicDownload(val track:QueueTrack,val requestId:Long)
data class DownloadState(val status:Int,val bytes:Long,val total:Long,val reason:Int)

/** System DownloadManager owns transfers, including after the activity closes. */
class MusicDownloads(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("youmu_downloads",Context.MODE_PRIVATE)
    private val manager=app.getSystemService(DownloadManager::class.java)
    fun entries():List<MusicDownload> = runCatching {
        val array=JSONArray(prefs.getString("items","[]"))
        (0 until array.length()).map{ i->val p=array.getJSONObject(i);MusicDownload(QueueTrack(p.getString("id"),p.getString("title"),p.optString("artist")),p.getLong("request"))}
    }.getOrDefault(emptyList())
    fun state(item:MusicDownload):DownloadState? = manager.query(DownloadManager.Query().setFilterById(item.requestId))?.use { cursor->
        if(!cursor.moveToFirst())null else DownloadState(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
    }
    fun localUri(id:String):Uri? {
        val entry=entries().find{it.track.id==id}?:return null
        if(state(entry)?.status!=DownloadManager.STATUS_SUCCESSFUL)return null
        val uri=manager.getUriForDownloadedFile(entry.requestId)?:return null
        return runCatching{app.contentResolver.openFileDescriptor(uri,"r")?.use{uri}}.getOrNull()
    }
    fun enqueue(track:QueueTrack,audio:ResolvedAudio):Long { synchronized(recordLock){
        require(PlaybackFormat.VIDEO_ID.matches(track.id))
        val all=entries().toMutableList();val old=all.find{it.track.id==track.id}
        if(old!=null&&state(old)?.status in listOf(DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED))return old.requestId
        if(old!=null&&localUri(track.id)!=null)return old.requestId
        if(old!=null){manager.remove(old.requestId);all.remove(old)}
        require(Uri.parse(audio.url).scheme=="https")
        val name=filename(track,audio.mimeType)
        val request=DownloadManager.Request(Uri.parse(audio.url)).setTitle(track.title).setDescription("YouMu • ${track.artist}")
            .setMimeType(audio.mimeType?:"audio/webm").addRequestHeader("User-Agent",AudioRepository.USER_AGENT)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC,"YouMu/$name")
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
        val id=manager.enqueue(request);all.add(MusicDownload(track,id));save(all);return id
    }}
    fun remove(item:MusicDownload){synchronized(recordLock){manager.remove(item.requestId);save(entries().filterNot{it.requestId==item.requestId})}}
    private fun save(items:List<MusicDownload>){val array=JSONArray();items.forEach{array.put(JSONObject().put("id",it.track.id).put("title",it.track.title).put("artist",it.track.artist).put("request",it.requestId))};prefs.edit().putString("items",array.toString()).apply()}
    fun audioPlaylist():AudioPlaylist {
        val tracks=entries().filter{localUri(it.track.id)!=null}.map{it.track}
        require(tracks.isNotEmpty()){"No completed downloads yet. Open Downloads to check progress or retry."}
        return AudioPlaylist("Downloads",tracks.map{AudioTrack(it.id,it.title,it.artist,"",0)})
    }
    companion object {
        private val recordLock=Any()
        private val executor=Executors.newSingleThreadExecutor()
        private val pending=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun filename(track:QueueTrack,mime:String?):String {
            val extension=when{mime?.contains("mp4")==true->"m4a";mime?.contains("mpeg")==true->"mp3";mime?.contains("ogg")==true->"ogg";else->"webm"}
            val title=track.title.replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").trim().take(80).ifBlank{"Song"}
            return "$title-${track.id}.$extension"
        }
        fun needsPermission(activity:Activity)=Build.VERSION.SDK_INT<=28&&activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED
        fun start(activity:Activity,track:QueueTrack) {
            if(!pending.add(track.id)){Toast.makeText(activity,"Preparing this download…",Toast.LENGTH_SHORT).show();return}
            Toast.makeText(activity,"Preparing download…",Toast.LENGTH_SHORT).show()
            executor.execute {
                val result=runCatching {
                    val downloads=MusicDownloads(activity.applicationContext)
                    val old=downloads.entries().find{it.track.id==track.id}
                    if(downloads.localUri(track.id)!=null)"Already downloaded"
                    else if(old!=null&&downloads.state(old)?.status in listOf(DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED))"This song is already downloading"
                    else {if(old!=null)AudioRepository.invalidate(track.id);downloads.enqueue(track,AudioRepository.resolve(track.id));"Download started • Music/YouMu"}
                }
                pending.remove(track.id)
                activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed)result.onSuccess{Toast.makeText(activity,it,Toast.LENGTH_LONG).show()}.onFailure{AlertDialog.Builder(activity).setTitle("Couldn’t download song").setMessage(PlaybackFailure.from(it,"download").message).setPositiveButton("OK",null).show()}}
            }
        }
    }
}
