package com.youmu.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

class PlayerScreen(
    private val activity:Activity,private val playlist:Playlist,
    private val onBack:()->Unit,private val onFavorite:()->Unit,private val openExternal:(String)->Unit,
    autoStart:Boolean=true,private val controls:AudioControls=NativeAudioControls(activity,playlist,autoStart),
    private val onDownload:(QueueTrack)->Unit={track->if(activity is MainActivity)activity.download(track)else MusicDownloads.start(activity,track)}
){
    private fun dp(value:Int)=Ui.dp(activity,value)
    private val main=Handler(Looper.getMainLooper())
    private val images=Executors.newSingleThreadExecutor()
    private var disposed=false
    private var active=true
    private var dragging=false
    private var currentId=""
    private var state=AudioSnapshot()
    private val body=Ui.column(activity,24)
    val view=ScrollView(activity).apply{isFillViewport=true;addView(body);setBackgroundColor(Ui.background)}
    private val title=Ui.text(activity,playlist.title,25f,bold=true).apply{maxLines=2}
    private val artist=Ui.text(activity,"",14f,Ui.muted).apply{maxLines=1}
    private val status=Ui.text(activity,"Connecting audio player…",11f,Ui.muted).apply{gravity=Gravity.CENTER}
    private val artwork=ImageView(activity).apply{
        scaleType=ImageView.ScaleType.CENTER_CROP;background=Ui.shape(activity,Ui.surface,24);clipToOutline=true
        contentDescription="Current song artwork";setImageResource(R.drawable.ic_artwork)
    }
    private val visual=MusicVisualView(activity)
    private val artFrame=FrameLayout(activity).apply{background=Ui.shape(activity,Ui.surface,28);clipToOutline=true;elevation=dp(6).toFloat();contentDescription="Change artwork or audio visualization";isClickable=true;isFocusable=true}
    private val displayLabel=Ui.text(activity,"",10f,Ui.muted).apply{gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(6));isClickable=true;contentDescription="Display settings"}
    private val mode=SymbolButton(activity,"normal","Playback mode")
    private val download=SymbolButton(activity,"download","Download current song")
    private val add=SymbolButton(activity,"add","Add current song to playlist")
    private val modeLabel=Ui.text(activity,"Normal",10f,Ui.muted).apply{gravity=Gravity.CENTER}
    private val elapsed=Ui.text(activity,"0:00",11f,Ui.muted)
    private val duration=Ui.text(activity,"0:00",11f,Ui.muted)
    private val seek=SeekBar(activity).apply{
        max=1000;isEnabled=false;progressTintList=ColorStateList.valueOf(Ui.accent);thumbTintList=ColorStateList.valueOf(Ui.accent)
        progressBackgroundTintList=ColorStateList.valueOf(Ui.border);setPadding(0,0,0,0);contentDescription="Song progress"
    }
    private val play=SymbolButton(activity,"play","Play",true)
    private val previous=SymbolButton(activity,"previous","Previous track")
    private val next=SymbolButton(activity,"next","Next track")
    private val queue=SymbolButton(activity,"queue","Open queue")
    private val favorite=SymbolButton(activity,if(playlist.favorite)"heart-filled" else "heart",if(playlist.favorite)"Unfavorite playlist" else "Favorite playlist")
    private val retry=Button(activity).apply{
        text="Retry playback";isAllCaps=false;setTextColor(Ui.background);background=Ui.shape(activity,Ui.accent,12)
        visibility=android.view.View.GONE;setOnClickListener{controls.command("retry")}
    }
    private val poll=object:Runnable{
        override fun run(){if(disposed||!active)return;render(controls.snapshot());main.postDelayed(this,500)}
    }
    init{
        val header=Ui.row(activity)
        header.addView(SymbolButton(activity,"back","Back to library").apply{setOnClickListener{onBack()}},LinearLayout.LayoutParams(dp(44),dp(44)))
        val caption=Ui.column(activity).apply{gravity=Gravity.CENTER}
        caption.addView(Ui.text(activity,"NOW PLAYING",9f,Ui.muted,true).apply{letterSpacing=.2f;gravity=Gravity.CENTER})
        Ui.gap(caption,6);caption.addView(Ui.text(activity,playlist.title,12f).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        header.addView(caption,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(10),0,dp(10),0)})
        header.addView(SymbolButton(activity,"more","Player options").apply{setOnClickListener{showOptions()}},LinearLayout.LayoutParams(dp(44),dp(44)))
        body.addView(header);Ui.gap(body,20)
        val screenWidth=activity.resources.displayMetrics.widthPixels/activity.resources.displayMetrics.density
        val screenHeight=activity.resources.displayMetrics.heightPixels/activity.resources.displayMetrics.density
        val artSize=minOf(360,(screenWidth-48).toInt(),maxOf(180,(screenHeight*.34f).toInt()))
        artFrame.addView(artwork,FrameLayout.LayoutParams(-1,-1));artFrame.addView(visual,FrameLayout.LayoutParams(-1,-1))
        body.addView(artFrame,LinearLayout.LayoutParams(dp(artSize),dp(artSize)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        body.addView(displayLabel)
        val chooseDisplay={PlayerPreferences.chooseVisual(activity){refreshDisplay()}}
        artFrame.setOnClickListener{chooseDisplay()};displayLabel.setOnClickListener{chooseDisplay()};refreshDisplay()
        Ui.gap(body,12);body.addView(title);Ui.gap(body,7);body.addView(artist);Ui.gap(body,14)
        body.addView(seek,LinearLayout.LayoutParams(-1,dp(32)))
        val times=Ui.row(activity);times.addView(elapsed,LinearLayout.LayoutParams(0,-2,1f));times.addView(duration);body.addView(times);Ui.gap(body,16)
        val buttons=Ui.row(activity).apply{gravity=Gravity.CENTER}
        listOf(favorite,previous,play,next,queue).forEach{button->buttons.addView(button,LinearLayout.LayoutParams(0,dp(if(button===play)68 else 48),1f).apply{setMargins(dp(3),0,dp(3),0)})}
        body.addView(buttons);Ui.gap(body,18)
        val tools=Ui.row(activity).apply{gravity=Gravity.CENTER;background=Ui.shape(activity,Ui.surface,20);setPadding(dp(8),dp(12),dp(8),dp(12))}
        listOf(mode to "Normal",download to "Download",add to "Add to playlist").forEach{(icon,label)->
            val item=Ui.column(activity).apply{gravity=Gravity.CENTER}
            item.addView(icon,LinearLayout.LayoutParams(dp(44),dp(44)));Ui.gap(item,6)
            item.addView(if(icon===mode)modeLabel else Ui.text(activity,label,10f,Ui.muted).apply{gravity=Gravity.CENTER})
            tools.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        };body.addView(tools);Ui.gap(body,20);body.addView(status);Ui.gap(body,12)
        body.addView(retry,LinearLayout.LayoutParams(-1,dp(48)));Ui.gap(body,12)
        body.addView(Ui.text(activity,"Screen-off playback enabled · YouTube audio",10f,Ui.muted).apply{gravity=Gravity.CENTER})
        previous.setOnClickListener{controls.command("previous")};next.setOnClickListener{controls.command("next")}
        play.setOnClickListener{controls.command(if(state.playing)"pause" else "play")};queue.setOnClickListener{showQueue()}
        favorite.setOnClickListener{onFavorite();favorite.update(if(playlist.favorite)"heart-filled" else "heart",if(playlist.favorite)"Unfavorite playlist" else "Favorite playlist")}
        mode.setOnClickListener{AlertDialog.Builder(activity).setTitle("Playback mode").setSingleChoiceItems(PlayerPreferences.modes,state.mode){dialog,index->controls.command("mode",index.toLong());dialog.dismiss()}.setNegativeButton("Close",null).show()}
        download.setOnClickListener{state.current?.let(onDownload)}
        add.setOnClickListener{state.current?.let{LocalMusic.addToPlaylist(activity,it)}}
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){if(fromUser)elapsed.text=PlaybackFormat.time(value/1000.0*state.duration/1000.0)}
            override fun onStopTrackingTouch(bar:SeekBar){controls.command("seek",(bar.progress/1000.0*state.duration).toLong());dragging=false}
        })
        render(state);controls.connect{snapshot->if(!disposed&&active)render(snapshot)};main.post(poll)
    }
    internal fun render(snapshot:AudioSnapshot){
        if(disposed)return;state=snapshot
        retry.visibility=if(state.failed)android.view.View.VISIBLE else android.view.View.GONE
        retry.isEnabled=!state.loading
        if(state.failed&&state.current==null)artist.text="Playback needs attention"
        val ready=state.connected&&state.tracks.isNotEmpty()
        play.isEnabled=ready;previous.isEnabled=ready;next.isEnabled=ready&&(state.canNext?: (state.index<state.tracks.lastIndex||state.mode==2));queue.isEnabled=ready
        mode.isEnabled=ready;download.isEnabled=state.current!=null;add.isEnabled=state.current!=null
        listOf(download,add,mode).forEach{it.alpha=if(it.isEnabled)1f else .35f}
        mode.update(when(state.mode){1->"shuffle";2->"repeat";3->"repeat-one";else->"normal"},"Playback mode")
        modeLabel.text=PlayerPreferences.modes[state.mode.coerceIn(0,3)]
        visual.playing=state.playing
        listOf(play,previous,next,queue).forEach{it.alpha=if(it.isEnabled)1f else .35f}
        play.update(if(state.playing)"pause" else "play",if(state.playing)"Pause" else "Play")
        seek.isEnabled=ready&&state.duration>0
        if(!dragging){seek.progress=state.progress;elapsed.text=PlaybackFormat.time(state.position/1000.0)}
        duration.text=PlaybackFormat.time(state.duration/1000.0)
        status.text=when{
            state.message.isNotBlank()->state.message
            state.loading->"Preparing audio…"
            state.playing->"Playing · Track ${state.index+1} of ${state.tracks.size}"
            ready->"Paused · Ready when you are"
            else->"Loading your playlist…"
        }
        state.current?.let{track->
            title.text=track.title;artist.text=track.artist.ifBlank{"YouTube"}
            if(track.id!=currentId){
                currentId=track.id;artwork.setImageResource(R.drawable.ic_artwork)
                if(PlaybackFormat.VIDEO_ID.matches(track.id))images.execute{
                    val image=TrackMetadataLoader.artwork(track.id)
                    main.post{if(!disposed&&currentId==track.id&&image!=null){artwork.setImageBitmap(image);artwork.contentDescription="Artwork for ${track.title}"}}
                }
            }
        }
    }
    private fun showQueue(){
        if(state.tracks.isEmpty())return
        val names=state.tracks.mapIndexed{index,track->"${if(index==state.index)"Playing · " else ""}${track.title}"}.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Queue · ${names.size} tracks").setSingleChoiceItems(names,state.index){dialog,index->controls.command("track",index.toLong());dialog.dismiss()}.setNegativeButton("Close",null).show()
    }
    private fun showOptions(){
        AlertDialog.Builder(activity).setTitle("Listening options").setItems(arrayOf("Artwork & visualizations","Open in YouTube","Stop playback","Copy diagnostics")){_,index->
            if(index==0)PlayerPreferences.chooseVisual(activity){refreshDisplay()}
            else if(index==1)openExternal(state.current?.let{"https://www.youtube.com/watch?v=${it.id}"}?:"https://www.youtube.com/playlist?list=${playlist.id}")
            else if(index==2){controls.command("stop");onBack()}
            else{
                val detail=state.diagnostics.ifBlank{"YouMu 0.4.0 | SDK ${android.os.Build.VERSION.SDK_INT} | ${state.message}"}
                activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("YouMu diagnostics",detail))
                Toast.makeText(activity,"Diagnostics copied",Toast.LENGTH_SHORT).show()
            }
        }.show()
    }
    private fun refreshDisplay(){
        val selected=PlayerPreferences(activity).visual;val enabled=selected>0
        artwork.visibility=if(enabled)android.view.View.GONE else android.view.View.VISIBLE
        visual.visibility=if(enabled)android.view.View.VISIBLE else android.view.View.GONE
        visual.mode=selected;visual.running=active&&enabled;AudioVisuals.enabled=active&&enabled
        displayLabel.text=(when(selected){1->"Spectrum";2->"Orbit Wave";else->"Song artwork"})+"  ·  Tap to customize"
    }
    /** Screen visibility affects UI polling only, never audio playback. */
    fun pause(){active=false;visual.running=false;AudioVisuals.enabled=false;main.removeCallbacks(poll)}
    fun resume(){if(!disposed){active=true;refreshDisplay();main.removeCallbacks(poll);main.post(poll)}}
    fun destroy(){if(disposed)return;disposed=true;active=false;visual.running=false;AudioVisuals.enabled=false;main.removeCallbacksAndMessages(null);images.shutdownNow();controls.release()}
}
