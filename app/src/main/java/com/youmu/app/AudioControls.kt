package com.youmu.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executor

data class QueueTrack(val id:String,val title:String,val artist:String)
data class AudioSnapshot(
    val connected:Boolean=false,val loading:Boolean=false,val playing:Boolean=false,
    val position:Long=0,val duration:Long=0,val index:Int=-1,
    val tracks:List<QueueTrack> = emptyList(),val message:String="",val failed:Boolean=false,val diagnostics:String=""
){
    val current get()=tracks.getOrNull(index)
    val progress get()=if(duration>0)((position.toDouble()/duration)*1000).toInt().coerceIn(0,1000)else 0
}
interface AudioControls{
    fun connect(onChange:(AudioSnapshot)->Unit)
    fun snapshot():AudioSnapshot
    fun command(name:String,value:Long=0)
    fun release()
}
/** Releasing a UI connection never stops the service-owned audio player. */
class NativeAudioControls(private val activity:Activity,private val playlist:Playlist,private val autoStart:Boolean=true):AudioControls{
    private val main=Handler(Looper.getMainLooper())
    private var controller:MediaController?=null
    private var connection:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
    private var disposed=false
    private var callback:((AudioSnapshot)->Unit)?=null
    private var connectionError=""
    private var requestPending=false
    private var connectionTimeout:Runnable?=null
    private val listener=object:Player.Listener{
        override fun onEvents(player:Player,events:Player.Events){callback?.invoke(snapshot())}
    }
    override fun connect(onChange:(AudioSnapshot)->Unit){
        callback=onChange
        if(autoStart)startRequest(false)
        openConnection()
    }
    private fun startRequest(force:Boolean){
        requestPending=true;connectionError=""
        runCatching{
            activity.startForegroundService(Intent(activity,PlaybackService::class.java).setAction(PlaybackService.ACTION_LOAD)
                .putExtra(PlaybackService.EXTRA_PLAYLIST,playlist.id).putExtra(PlaybackService.EXTRA_TITLE,playlist.title)
                .putExtra(PlaybackService.EXTRA_ACCOUNT,playlist.fromAccount).putExtra(PlaybackService.EXTRA_FORCE,force))
        }.onFailure{requestPending=false;PlaybackStatus.fail(PlaybackFailure.from(it,"service"));connectionError=PlaybackStatus.message}
    }
    private fun openConnection(){
        connectionTimeout?.let(main::removeCallbacks)
        connection?.let{MediaController.releaseFuture(it)}
        controller=null
        val token=SessionToken(activity,ComponentName(activity,PlaybackService::class.java))
        val future=MediaController.Builder(activity,token).setListener(object:MediaController.Listener{
            override fun onDisconnected(controller:MediaController){
                if(!disposed&&this@NativeAudioControls.controller===controller){connectionError=PlaybackStatus.message.takeIf{PlaybackStatus.failed}?:"Audio service disconnected. Tap Retry.";callback?.invoke(snapshot())}
            }
        }).buildAsync();connection=future
        val timer=Runnable{
            if(!disposed&&controller==null&&connection===future){
                connection=null;MediaController.releaseFuture(future)
                connectionError="The audio service did not connect. Tap Retry."
                callback?.invoke(snapshot())
            }
        }
        connectionTimeout=timer;main.postDelayed(timer,15_000)
        future.addListener({
            if(disposed||connection!==future){MediaController.releaseFuture(future);return@addListener}
            main.removeCallbacks(timer)
            runCatching{
                controller=future.get();connectionError="";controller!!.addListener(listener)
            }.onFailure{connectionError="Couldn’t connect the audio service. Tap Retry.";PlaybackStatus.fail(PlaybackFailure.from(it,"connection"))}
            callback?.invoke(snapshot())
        },Executor{main.post(it)})
    }
    override fun snapshot():AudioSnapshot{
        val player=controller?:return AudioSnapshot(loading=PlaybackStatus.loading,message=connectionError.ifBlank{PlaybackStatus.message.ifBlank{"Connecting audio player…"}},failed=connectionError.isNotBlank()||PlaybackStatus.failed,diagnostics=PlaybackStatus.diagnostics)
        if(player.mediaItemCount>0||PlaybackStatus.loading||PlaybackStatus.failed)requestPending=false
        val tracks=(0 until player.mediaItemCount).map{index->
            val item=player.getMediaItemAt(index)
            QueueTrack(item.mediaId,item.mediaMetadata.title?.toString().orEmpty().ifBlank{"Track ${index+1}"},item.mediaMetadata.artist?.toString().orEmpty())
        }
        val emptyFailure=player.isConnected&&tracks.isEmpty()&&!PlaybackStatus.loading&&!requestPending
        return AudioSnapshot(connected=player.isConnected,loading=PlaybackStatus.loading||requestPending||player.playbackState==Player.STATE_BUFFERING,
            playing=player.isPlaying,position=player.currentPosition.coerceAtLeast(0),duration=player.duration.takeIf{it!=C.TIME_UNSET&&it>0}?:0,
            index=player.currentMediaItemIndex,tracks=tracks,message=connectionError.ifBlank{PlaybackStatus.message.ifBlank{if(emptyFailure)"No tracks were loaded. Tap Retry." else if(requestPending)"Starting audio player…" else ""}},
            failed=PlaybackStatus.failed||connectionError.isNotBlank()||emptyFailure,diagnostics=PlaybackStatus.diagnostics)
    }
    override fun command(name:String,value:Long){
        if(name=="retry"){
            startRequest(true)
            if(controller?.isConnected!=true)openConnection()
            callback?.invoke(snapshot());return
        }
        val player=controller?:return
        when(name){
            "play"->{if(player.playerError!=null||player.playbackState==Player.STATE_IDLE)player.prepare();player.play()}
            "pause"->player.pause()
            "next"->if(player.hasNextMediaItem()){player.seekToNextMediaItem();if(player.playerError!=null||player.playbackState==Player.STATE_IDLE)player.prepare();player.play()}
            "previous"->{if(player.currentPosition>3000)player.seekTo(0)else if(player.hasPreviousMediaItem())player.seekToPreviousMediaItem();if(player.playerError!=null||player.playbackState==Player.STATE_IDLE)player.prepare();player.play()}
            "seek"->if(player.duration>0)player.seekTo(value.coerceIn(0,player.duration))
            "track"->if(value in 0 until player.mediaItemCount.toLong()){player.seekTo(value.toInt(),0);if(player.playerError!=null||player.playbackState==Player.STATE_IDLE)player.prepare();player.play()}
            "stop"->{PlaybackStatus.clear();activity.startService(Intent(activity,PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))}
        }
    }
    override fun release(){
        if(disposed)return;disposed=true;callback=null
        controller?.removeListener(listener);controller=null;connection?.let{MediaController.releaseFuture(it)};connection=null
        main.removeCallbacksAndMessages(null)
    }
}
