package com.youmu.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local playlist membership is independent of remote playlist changes. */
class LocalMusic(context:Context) {
    private val prefs=context.getSharedPreferences("youmu_local_tracks",Context.MODE_PRIVATE)
    @Synchronized fun tracks(id:String):List<QueueTrack> = runCatching {
        val array=JSONArray(prefs.getString(id,"[]"))
        (0 until array.length()).map { i->val p=array.getJSONObject(i);QueueTrack(p.getString("id"),p.getString("title"),p.optString("artist")) }
    }.getOrDefault(emptyList())
    @Synchronized fun add(id:String,track:QueueTrack):Boolean {
        val list=tracks(id).toMutableList()
        if(list.any{it.id==track.id})return false
        list.add(track);save(id,list);return true
    }
    fun remove(id:String,trackId:String){save(id,tracks(id).filterNot{it.id==trackId})}
    fun delete(id:String){prefs.edit().remove(id).apply()}
    private fun save(id:String,list:List<QueueTrack>){
        val array=JSONArray();list.forEach{array.put(JSONObject().put("id",it.id).put("title",it.title).put("artist",it.artist))}
        prefs.edit().putString(id,array.toString()).apply()
    }
    fun audioPlaylist(id:String):AudioPlaylist {
        val list=tracks(id);require(list.isNotEmpty()){"This local playlist is empty. Add a song from Now Playing."}
        return AudioPlaylist("Local playlist",list.map{AudioTrack(it.id,it.title,it.artist,"https://i.ytimg.com/vi/${it.id}/hqdefault.jpg",0)})
    }
    companion object {
        fun isLocal(id:String)=id.startsWith("local_")||id=="youmu_downloads"
        fun create(activity:Activity,track:QueueTrack?=null,done:(Playlist)->Unit={}) {
            val input=EditText(activity).apply{hint="Playlist name";isSingleLine=true;setPadding(32,24,32,24)}
            val dialog=AlertDialog.Builder(activity).setTitle("Create local playlist").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Create",null).create()
            dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val name=input.text.toString().trim()
                if(name.isBlank()){input.error="Enter a playlist name";return@setOnClickListener}
                val playlist=Playlist("local_"+UUID.randomUUID(),name.take(100),count=if(track==null)0 else 1)
                val store=PlaylistStore(activity);val list=store.load();list.add(playlist);store.save(list)
                if(track!=null)LocalMusic(activity).add(playlist.id,track)
                dialog.dismiss();done(playlist)
            }};dialog.show()
        }
        fun addToPlaylist(activity:Activity,track:QueueTrack) {
            val store=PlaylistStore(activity);val list=store.load().filter{it.id.startsWith("local_")}
            AlertDialog.Builder(activity).setTitle("Add song to playlist")
                .setItems((list.map{it.title}+"＋ Create new playlist").toTypedArray()){_,index->
                    if(index==list.size)create(activity,track){Toast.makeText(activity,"Song added to ${it.title}",Toast.LENGTH_SHORT).show()}
                    else {
                        val music=LocalMusic(activity);val added=music.add(list[index].id,track)
                        val all=store.load();all.find{it.id==list[index].id}?.count=music.tracks(list[index].id).size;store.save(all)
                        Toast.makeText(activity,if(added)"Song added" else "Song is already in this playlist",Toast.LENGTH_SHORT).show()
                    }
                }.setNegativeButton("Cancel",null).show()
        }
    }
}
