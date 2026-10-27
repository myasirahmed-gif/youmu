package com.youmu.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.concurrent.Executors
import java.util.concurrent.Future

@UnstableApi
class PlaybackService:MediaSessionService(){
    private lateinit var player:ExoPlayer
    private var session:MediaSession?=null
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var load:Future<*>?=null
    private var generation=0
    private var disposed=false
    private var retryId=""
    private var retryCount=0
    private var playlistTimeout:Runnable?=null
    private var streamTimeout:Runnable?=null
    internal var queueLoader:(String,Boolean,(AudioPlaylist)->Unit)->AudioPlaylist={id,fromAccount,publish->
        val token=AccountSession.token
        when {
            id=="youmu_downloads"->MusicDownloads(this).audioPlaylist()
            LocalMusic.isLocal(id)->LocalMusic(this).audioPlaylist(id)
            fromAccount&&token!=null->YouTubeApi().tracks(id,token,publish)
            else->AudioRepository.playlist(id,publish)
        }
    }
    private fun cancelTimeouts(){playlistTimeout?.let(main::removeCallbacks);streamTimeout?.let(main::removeCallbacks);playlistTimeout=null;streamTimeout=null}
    internal fun reportFailure(error:Throwable,phase:String){
        cancelTimeouts()
        PlaybackStatus.fail(PlaybackFailure.from(error,phase))
        android.util.Log.e("YouMuPlayback",PlaybackStatus.diagnostics)
        player.stop();stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onCreate(){
        super.onCreate()
        val http=DefaultHttpDataSource.Factory().setUserAgent(AudioRepository.USER_AGENT).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val source=ResolvingDataSource.Factory(DefaultDataSource.Factory(this,http)){spec->
            if(spec.uri.scheme!="youmu")spec else {
                val id=spec.uri.lastPathSegment.orEmpty()
                spec.withUri(MusicDownloads(this).localUri(id)?:Uri.parse(AudioRepository.resolve(id).url))
            }
        }
        val renderers=object:DefaultRenderersFactory(this){
            override fun buildAudioSink(context:android.content.Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(MusicSampleSink()))).build()
        }
        player=ExoPlayer.Builder(this,renderers).setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        PlaybackModes.apply(player,PlayerPreferences(this).mode)
        val activity=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra(EXTRA_RESUME,true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session=MediaSession.Builder(this,player).setSessionActivity(activity).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL).build())
        player.addListener(object:Player.Listener{
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled:Boolean){saveMode()}
            override fun onRepeatModeChanged(repeatMode:Int){saveMode()}
            private fun saveMode(){PlayerPreferences(this@PlaybackService).mode=PlaybackModes.read(player)}
            override fun onIsPlayingChanged(isPlaying:Boolean){PlaybackStatus.playing=isPlaying}
            override fun onPlaybackStateChanged(state:Int){
                streamTimeout?.let(main::removeCallbacks);streamTimeout=null
                if(state==Player.STATE_BUFFERING){
                    val timer=Runnable{if(!disposed&&player.playbackState==Player.STATE_BUFFERING)reportFailure(java.util.concurrent.TimeoutException(),"audio")}
                    streamTimeout=timer;main.postDelayed(timer,45_000)
                }else if(state==Player.STATE_READY){PlaybackStatus.clearFailure()}
            }
            override fun onMediaItemTransition(item:MediaItem?,reason:Int){
                PlaybackStatus.currentTitle=item?.mediaMetadata?.title?.toString().orEmpty()
                if(item?.mediaId!=retryId){retryId=item?.mediaId.orEmpty();retryCount=0}
                PlaybackStatus.clearFailure()
            }
            override fun onPlayerError(error:PlaybackException){
                val id=player.currentMediaItem?.mediaId.orEmpty()
                if(error.errorCode==PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS&&retryCount<1){retryCount++;AudioRepository.invalidate(id);player.prepare();return}
                reportFailure(error,"audio")
            }
        })
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo)=session
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_LOAD->{
                val id=intent.getStringExtra(EXTRA_PLAYLIST).orEmpty()
                if(!Regex("^[A-Za-z0-9_-]{10,150}$").matches(id)){stopSelf();return START_NOT_STICKY}
                if(!intent.getBooleanExtra(EXTRA_FORCE,false)&&PlaybackStatus.playlistId==id&&(player.mediaItemCount>0||PlaybackStatus.loading)){
                    if(player.mediaItemCount>0){if(player.playerError!=null||player.playbackState==Player.STATE_IDLE)player.prepare();player.play()}
                }else loadPlaylist(id,intent.getStringExtra(EXTRA_TITLE).orEmpty(),intent.getBooleanExtra(EXTRA_ACCOUNT,false))
                return START_NOT_STICKY
            }
            ACTION_STOP->{stopPlayback();return START_NOT_STICKY}
        }
        return super.onStartCommand(intent,flags,startId)
    }
    private fun showPreparing(){
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Music playback",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra(EXTRA_RESUME,true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,PlaybackService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("YouMu")
            .setContentText("Preparing your music…").setCategory(Notification.CATEGORY_TRANSPORT).setContentIntent(pending)
            .addAction(Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build()
        startForeground(NOTIFICATION_ID,notification)
    }
    private fun loadPlaylist(id:String,title:String,fromAccount:Boolean){
        val request=++generation;load?.cancel(true);cancelTimeouts()
        player.stop();player.clearMediaItems();PlaybackStatus.clearFailure()
        PlaybackStatus.playlistId=id;PlaybackStatus.playlistTitle=title;PlaybackStatus.message="Loading playlist...";PlaybackStatus.loading=true
        showPreparing()
        val timer=Runnable{
            if(!disposed&&request==generation&&PlaybackStatus.loading){
                generation++;load?.cancel(true);reportFailure(java.util.concurrent.TimeoutException(),"playlist")
            }
        }
        playlistTimeout=timer;main.postDelayed(timer,45_000)
        val publish:(AudioPlaylist)->Unit={page->main.post{
            if(!disposed&&request==generation)appendQueue(page,title)
        }}
        load=worker.submit{
            val result=runCatching{
                queueLoader(id,fromAccount,publish)
            }
            main.post{
                if(disposed||request!=generation)return@post
                playlistTimeout?.let(main::removeCallbacks);playlistTimeout=null
                result.onSuccess{queue->appendQueue(queue,title)}.onFailure{
                    if(player.mediaItemCount==0)reportFailure(it,"playlist")
                    else {
                        PlaybackStatus.loading=false
                        PlaybackStatus.diagnostics=PlaybackFailure.from(it,"pagination").diagnostics
                        android.util.Log.w("YouMuPlayback",PlaybackStatus.diagnostics)
                    }
                }
            }
        }
    }
    private fun appendQueue(queue:AudioPlaylist,title:String){
        if(queue.tracks.isEmpty())return
        val existing=player.mediaItemCount
        val items=queue.tracks.drop(existing).map{track->
            val metadata=MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                .setArtworkUri(track.artwork.takeIf{it.startsWith("https://")}?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
            MediaItem.Builder().setMediaId(track.id).setUri("youmu://video/${track.id}").setMediaMetadata(metadata).build()
        }
        if(items.isEmpty())return
        PlaybackStatus.playlistTitle=title.ifBlank{queue.title};PlaybackStatus.loading=false
        playlistTimeout?.let(main::removeCallbacks);playlistTimeout=null
        if(existing==0){
            PlaybackStatus.clearFailure();player.setMediaItems(items);player.prepare();player.play()
        }else player.addMediaItems(items)
    }
    private fun stopPlayback(){cancelTimeouts();generation++;load?.cancel(true);player.stop();player.clearMediaItems();PlaybackStatus.clear();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){disposed=true;AudioVisuals.clear();generation++;load?.cancel(true);worker.shutdownNow();main.removeCallbacksAndMessages(null);session?.release();session=null;if(::player.isInitialized)player.release();if(!PlaybackStatus.failed)PlaybackStatus.clear();super.onDestroy()}
    companion object{
        const val ACTION_LOAD="com.youmu.app.LOAD_PLAYLIST"
        const val ACTION_STOP="com.youmu.app.STOP_PLAYBACK"
        const val EXTRA_PLAYLIST="playlist_id"
        const val EXTRA_TITLE="playlist_title"
        const val EXTRA_ACCOUNT="from_account"
        const val EXTRA_FORCE="force_reload"
        const val EXTRA_RESUME="resume_player"
        private const val CHANNEL="youmu_music"
        private const val NOTIFICATION_ID=1201
    }
}
