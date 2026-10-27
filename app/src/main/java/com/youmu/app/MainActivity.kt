package com.youmu.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg get()=Ui.background
    private val panel get()=Ui.surface
    private val lime get()=Ui.accent
    private val ink get()=Ui.white
    private val muted get()=Ui.muted
    private val youtubeScope = "https://www.googleapis.com/auth/youtube.readonly"
    private lateinit var store: PlaylistStore
    private var playlists = mutableListOf<Playlist>()
    private lateinit var root: LinearLayout
    private lateinit var cards: LinearLayout
    private lateinit var libraryTitle: TextView
    private lateinit var connectionButton: Button
    private val api = YouTubeApi()
    private val worker = Executors.newSingleThreadExecutor()
    private var accessToken: String? = AccountSession.token
    private var authGeneration = 0
    private var operation = 0
    private var view = "Library"
    private var search = ""
    private var playerScreen: PlayerScreen? = null
    private var nowPlaying: Playlist? = null
    private var pendingDownload:QueueTrack?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(when(PlayerPreferences(this).theme){1->R.style.Theme_YouMu_Ocean;2->R.style.Theme_YouMu_AmOLED;else->R.style.Theme_YouMu})
        super.onCreate(savedInstanceState)
        Ui.apply(this)
        @Suppress("DEPRECATION")
        window.statusBarColor=bg
        @Suppress("DEPRECATION")
        window.navigationBarColor=bg
        savedInstanceState?.getString("downloadId")?.let{id->pendingDownload=QueueTrack(id,savedInstanceState.getString("downloadTitle").orEmpty(),savedInstanceState.getString("downloadArtist").orEmpty())}
        store = PlaylistStore(this)
        playlists = store.load()
        view = savedInstanceState?.getString("view") ?: "Library"
        search = savedInstanceState?.getString("search") ?: ""
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { goBack() }
        showLibrary()
        if(intent.getBooleanExtra(PlaybackService.EXTRA_RESUME,false)||savedInstanceState?.getString("playerPlaylist")==PlaybackStatus.playlistId&&PlaybackStatus.playlistId.isNotBlank())openCurrentPlayer()
        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("https?://[^\\s]+").find(shared)?.value
            // YouTube's share sheet normally provides the URL as the final line.
            val url = shared.lines().lastOrNull { it.trim().startsWith("https://") }?.trim() ?: link ?: shared
            showImport(url)
        }
    }

    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra(PlaybackService.EXTRA_RESUME,false))openCurrentPlayer()}
    private fun openCurrentPlayer(){val id=PlaybackStatus.playlistId;val p=store.load().find{it.id==id}?:if(id=="youmu_downloads")Playlist(id,"Downloads")else null;p?.let{play(it,autoStart=false)}}
    override fun onSaveInstanceState(outState: Bundle) { pendingDownload?.let{outState.putString("downloadId",it.id);outState.putString("downloadTitle",it.title);outState.putString("downloadArtist",it.artist)};outState.putString("playerPlaylist",nowPlaying?.id); outState.putString("view",view); outState.putString("search",search); super.onSaveInstanceState(outState) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat(); if(stroke!=null)setStroke(dp(1),stroke) }
    private fun text(value: String, size: Float=14f, color: Int=ink, bold: Boolean=false) = TextView(this).apply { text=value; textSize=size; setTextColor(color); if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); includeFontPadding=false }
    private fun column(padding: Int=0) = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(padding),dp(padding),dp(padding),dp(padding)) }
    private fun row() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun space(container: LinearLayout, height: Int) { container.addView(View(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun label(container: LinearLayout,value: String) { container.addView(text(value,10f,muted,true).apply { letterSpacing=.16f }) }
    private fun button(title: String, primary: Boolean=false, click: ()->Unit) = Button(this).apply {
        text=title; isAllCaps=false; textSize=13f; minHeight=dp(48); minimumHeight=dp(48); setPadding(dp(16),dp(6),dp(16),dp(6))
        background=shape(if(primary)lime else panel,12,if(primary)null else Color.rgb(55,62,49));setTextColor(if(primary)bg else ink);setOnClickListener{click()}
    }
    private fun field(hint: String, value: String="") = EditText(this).apply { this.hint=hint;setText(value);setTextColor(ink);setHintTextColor(muted);textSize=14f;isSingleLine=true;setPadding(dp(15),dp(10),dp(15),dp(10));background=shape(bg,10,Color.rgb(62,70,54));layoutParams=LinearLayout.LayoutParams(-1,dp(54)) }
    private fun mount(content: View) {
        root=column();root.setBackgroundColor(bg)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if(Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom)}
            else {@Suppress("DEPRECATION") v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)}
            insets
        }
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets()
    }
    private fun showLibrary() {
        destroyPlayer()
        playlists=store.load()
        playlists.filter{LocalMusic.isLocal(it.id)}.forEach{it.count=LocalMusic(this).tracks(it.id).size}
        val scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        val body=column(22);scroll.addView(body)
        val top=row()
        val brand=column();brand.addView(text("youmu.",29f,ink,true));space(brand,6);brand.addView(text("A space for your sound",11f,muted))
        top.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(SymbolButton(this,"settings","Settings").apply {setOnClickListener{showSettings()}},LinearLayout.LayoutParams(dp(46),dp(46)))
        body.addView(top);space(body,25)
        val hero=column(20).apply {background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(blend(lime,panel,.15f),panel)).apply {cornerRadius=dp(24).toFloat();setStroke(dp(1),Ui.border)}}
        label(hero,"YOUR DAILY SOUNDTRACK");space(hero,12)
        hero.addView(text("Find your rhythm.",27f,ink,true));space(hero,8)
        hero.addView(text("Your playlists. Your way to listen.",12f,muted));space(hero,17)
        hero.addView(button("＋  Import playlist",true){showImport()});body.addView(hero);space(body,12)
        connectionButton=button(if(accessToken==null)"G   Connect YouTube" else "✓  YouTube connected · Sync") { connectYouTube() }
        body.addView(connectionButton,LinearLayout.LayoutParams(-1,dp(46)));space(body,12)
        val shortcuts=row()
        shortcuts.addView(button("＋ New playlist"){LocalMusic.create(this){showLibrary()}},LinearLayout.LayoutParams(0,dp(50),1f).apply{rightMargin=dp(6)})
        shortcuts.addView(button("↓ Downloads"){showDownloads()},LinearLayout.LayoutParams(0,dp(50),1f).apply{leftMargin=dp(6)})
        body.addView(shortcuts);space(body,24)
        val heading=row();libraryTitle=text("",23f,ink,true);heading.addView(libraryTitle,LinearLayout.LayoutParams(0,-2,1f));heading.addView(text("ON THIS DEVICE",8f,muted));body.addView(heading);space(body,16)
        val searchField=field("Search your playlists",search);searchField.contentDescription="Search playlists";body.addView(searchField)
        searchField.addTextChangedListener(object:TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){search=s.toString();renderCards()};override fun afterTextChanged(s:Editable?){} })
        space(body,17);cards=column();body.addView(cards);space(body,25)
        body.addView(text("Your library stays on this device.",11f,muted).apply { gravity=Gravity.CENTER });space(body,8)
        body.addView(text("Source: YouTube",10f,muted).apply { gravity=Gravity.CENTER })
        mount(scroll)
        if(PlaybackStatus.playlistId.isNotBlank()){
            val mini=button("Now playing: "+PlaybackStatus.currentTitle.ifBlank{PlaybackStatus.playlistTitle}){openCurrentPlayer()}
            root.addView(mini,LinearLayout.LayoutParams(-1,dp(52)).apply{setMargins(dp(16),dp(6),dp(16),dp(6))})
        }
        val tabs=row().apply { setPadding(dp(10),dp(9),dp(10),dp(9));background=shape(bg,0) }
        listOf("Library", "Favorites", "Recent").forEach { name ->
            tabs.addView(button(name,view==name) { view=name;showLibrary() },LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(dp(3),0,dp(3),0) })
        }
        root.addView(tabs);renderCards()
    }
    private fun renderCards() {
        libraryTitle.text=when(view){"Favorites"->"Your favorites";"Recent"->"Recently played";else->"Your library"}
        cards.removeAllViews()
        val visible=playlists.filter { (view!="Favorites"||it.favorite)&&(view!="Recent"||it.lastPlayed>0)&&it.title.contains(search,true) }
            .sortedByDescending { if(view=="Recent")it.lastPlayed else it.added }
        if(visible.isEmpty()) {
            val empty=column(25).apply { background=shape(panel,16,Color.rgb(48,57,40));gravity=Gravity.CENTER }
            empty.addView(text("♫",42f,lime));space(empty,17)
            empty.addView(text(if(search.isNotBlank())"No matching playlists" else if(view=="Favorites")"Keep your favorites close." else if(view=="Recent")"Your next listen starts here." else "Start with a good playlist.",17f,ink,true).apply { gravity=Gravity.CENTER })
            space(empty,10);empty.addView(text(if(view=="Favorites")"Tap a playlist’s heart to save it here." else "Import a playlist link or connect YouTube.",12f,muted).apply { gravity=Gravity.CENTER });space(empty,17)
            empty.addView(button("Import playlist ↗"){showImport()});cards.addView(empty);return
        }
        visible.forEach { p ->
            val card=column(17).apply { background=shape(panel,18,Ui.border) }
            val top=row();val record=RecordArt(this).apply { contentDescription="Play ${p.title}";isClickable=true;isFocusable=true;setOnClickListener{play(p)} };top.addView(record,LinearLayout.LayoutParams(dp(74),dp(74)))
            val details=column().apply { setPadding(dp(15),0,dp(5),0) };details.addView(text(p.title,17f,ink,true).apply { maxLines=2 });space(details,7)
            details.addView(text((if(p.count>=0)"${p.count} tracks · " else "")+if(LocalMusic.isLocal(p.id))"Local playlist" else if(p.fromAccount)"YouTube account" else "YouTube playlist",11f,muted))
            top.addView(details,LinearLayout.LayoutParams(0,-2,1f));card.addView(top);space(card,16)
            val actions=row();actions.addView(button("▶  Play",true){play(p)},LinearLayout.LayoutParams(0,dp(46),1f))
            actions.addView(SymbolButton(this,if(p.favorite)"heart-filled" else "heart",if(p.favorite)"Unfavorite ${p.title}" else "Favorite ${p.title}").apply {setOnClickListener{p.favorite=!p.favorite;store.save(playlists);renderCards()}},LinearLayout.LayoutParams(dp(48),dp(48)).apply { leftMargin=dp(10) })
            actions.addView(SymbolButton(this,"more","Options for ${p.title}").apply {setOnClickListener{playlistMenu(p)}},LinearLayout.LayoutParams(dp(48),dp(48)).apply { leftMargin=dp(8) });card.addView(actions)
            cards.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(13) })
        }
    }
    private fun showImport(prefill: String="") {
        val content=column(22);content.addView(text("Bring your music over.",23f,ink,true));space(content,10);content.addView(text("Paste a YouTube or YouTube Music playlist link.",13f,muted));space(content,20)
        val url=field("Playlist link",prefill);url.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;content.addView(url);space(content,13)
        val name=field("Playlist name (optional)");content.addView(name);space(content,12);val error=text("",12f,Color.rgb(244,170,152));content.addView(error);space(content,12)
        val importScroll=ScrollView(this).apply { addView(content) }
        val dialog=AlertDialog.Builder(this).setView(importScroll).setNegativeButton("Cancel",null).create()
        content.addView(button("Import playlist",true){
            try {
                val id=PlaylistLinks.parse(url.text.toString());require(playlists.none{it.id==id}){"This playlist is already in your library."}
                val p=Playlist(id,name.text.toString().trim().ifBlank{"YouTube playlist"});playlists.add(p);store.save(playlists);dialog.dismiss();showLibrary();toast("Playlist added. Tap Play when you’re ready.")
                if(accessToken!=null)refresh(p,keepTitle=name.text.isNotBlank())
            }catch(e:Exception){error.text=e.message}
        });space(content,10)
        content.addView(button("Connect YouTube instead"){dialog.dismiss();connectYouTube()});space(content,10)
        content.addView(text("Link playback needs a public or unlisted, embeddable playlist.",11f,muted));dialog.show()
    }
    private fun connectYouTube() {
        val request=AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(youtubeScope))).build()
        connectionButton.isEnabled=false
        Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener(this) { result ->
            connectionButton.isEnabled=true
            if(result.hasResolution()) {
                try { @Suppress("DEPRECATION") startIntentSenderForResult(result.pendingIntent!!.intentSender,1001,null,0,0,0) }
                catch(_:Exception){toast("Could not open Google authorization. Please try again.")}
            } else authorized(result)
        }.addOnFailureListener(this) { e ->connectionButton.isEnabled=true;showMessage("YouTube connection", "Google connection needs an Android OAuth client registered for com.youmu.app and this APK’s signing SHA-1.\n\n${e.localizedMessage ?: "Please try again."}") }
    }
    @Deprecated("Android callback used for the Google consent PendingIntent")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==1001){if(resultCode!=RESULT_OK){toast("YouTube connection cancelled.");return}
            try { authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)) }catch(e:Exception){toast("YouTube connection failed. ${e.localizedMessage.orEmpty()}")}
        }
    }
    private fun authorized(result:AuthorizationResult) {
        if(!result.grantedScopes.contains(youtubeScope)||result.accessToken.isNullOrBlank()){toast("Read access to YouTube is needed to import playlists.");return}
        accessToken=result.accessToken;AccountSession.token=accessToken;authGeneration++;connectionButton.text="✓  YouTube connected · Sync"
        loadAccountPlaylists()
    }
    private fun loadAccountPlaylists() {
        val token=accessToken?:return
        network("Loading your playlists…",{api.playlists(token)}) { items ->
            if(items.isEmpty()){showMessage("Your playlists","No owned playlists were found on this YouTube account.");return@network}
            val selected=BooleanArray(items.size){index->playlists.any{it.id==items[index].id}}
            AlertDialog.Builder(this).setTitle("Choose your playlists")
                .setMultiChoiceItems(items.map{it.title}.toTypedArray(),selected){_,index,checked->selected[index]=checked}
                .setNegativeButton("Cancel",null).setPositiveButton("Import selected"){_,_->
                    var count=0
                    items.forEachIndexed { index,p ->if(selected[index]){val existing=playlists.find{it.id==p.id};if(existing!=null){existing.title=p.title;existing.count=p.count}else playlists.add(p);count++} }
                    store.save(playlists);showLibrary();toast("$count playlists imported.")
                }.show()
        }
    }
    private fun <T> network(message:String,job:()->T,done:(T)->Unit) {
        val generation=authGeneration;val op=++operation
        val content=row().apply { setPadding(dp(24),dp(20),dp(24),dp(20));addView(ProgressBar(this@MainActivity),LinearLayout.LayoutParams(dp(30),dp(30)));addView(text(message,13f).apply { setPadding(dp(15),0,0,0) }) }
        val loading=AlertDialog.Builder(this).setView(content).setNegativeButton("Cancel"){_,_->operation++}.create();loading.setOnCancelListener {operation++};loading.show()
        worker.execute {
            val result=runCatching(job)
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                loading.dismiss();if(generation!=authGeneration||op!=operation)return@runOnUiThread
                result.onSuccess(done).onFailure { e ->
                    if(e is YouTubeApi.Unauthorized){accessToken?.let{Identity.getAuthorizationClient(this).clearToken(ClearTokenRequest.builder().setToken(it).build())};accessToken=null;AccountSession.token=null;connectionButton.text="G   Connect YouTube"}
                    showMessage("Couldn’t load YouTube",e.localizedMessage?:"Please check your internet connection and try again.")
                }
            }
        }
    }
    private fun refresh(p:Playlist,keepTitle:Boolean=false) {
        val token=accessToken
        if(token==null){showMessage("Connect YouTube","Connect your account first to load playlist names and video counts.");return}
        network("Refreshing playlist…",{api.playlist(p.id,token)}){updated->if(!keepTitle)p.title=updated.title;p.count=updated.count;store.save(playlists);renderCards();toast("Playlist updated.")}
    }
    private fun playlistMenu(p:Playlist) {
        if(LocalMusic.isLocal(p.id)){localMenu(p);return}
        AlertDialog.Builder(this).setTitle(p.title).setItems(arrayOf("Refresh playlist details","Open in YouTube","Remove from library")){_,which->when(which){0->refresh(p);1->openExternal("https://www.youtube.com/playlist?list=${p.id}");2->AlertDialog.Builder(this).setTitle("Remove playlist?").setMessage("This removes it from YouMu on this device.").setNegativeButton("Cancel",null).setPositiveButton("Remove"){_,_->playlists.remove(p);store.save(playlists);renderCards()}.show()}}.show()
    }
    private fun play(p:Playlist,autoStart:Boolean=true) {
        if(autoStart&&p.id.startsWith("local_")&&LocalMusic(this).tracks(p.id).isEmpty()){
            showMessage("Your playlist is ready","Play a song from a YouTube playlist, then tap Add to playlist on Now Playing to save it here.");return
        }
        destroyPlayer()
        nowPlaying=p
        p.lastPlayed=System.currentTimeMillis()
        store.save(playlists)
        val screen=PlayerScreen(this,p,
            onBack={showLibrary()},
            onFavorite={val all=store.load();p.favorite=!p.favorite;all.find{it.id==p.id}?.favorite=p.favorite;store.save(all)},
            openExternal={openExternal(it)},autoStart=autoStart)
        playerScreen=screen
        mount(screen.view)
    }
    private fun showSettings() {
        val options=arrayOf("Theme · ${PlayerPreferences.themes[PlayerPreferences(this).theme]}","Artwork & audio visualizations","Downloads","About & privacy","Disconnect YouTube","Clear local library")
        AlertDialog.Builder(this).setTitle("YouMu settings").setItems(options){_,which->when(which){
            0->AlertDialog.Builder(this).setTitle("Choose your theme").setSingleChoiceItems(PlayerPreferences.themes,PlayerPreferences(this).theme){dialog,index->PlayerPreferences(this).theme=index;dialog.dismiss();Ui.apply(this);recreate()}.setNegativeButton("Close",null).show()
            1->PlayerPreferences.chooseVisual(this)
            2->showDownloads()
            3->AlertDialog.Builder(this).setTitle("Your music. Your data.").setMessage("Playlists and preferences stay on this device. Google requests read-only YouTube access; access tokens stay in memory.\n\nDownloads save original audio files in Music/YouMu and can play offline. Spectrum and Orbit Wave react to decoded music, without microphone access.\n\nVersion 0.4.0 · Android").setPositiveButton("Done",null).setNeutralButton("Google privacy"){_,_->openExternal("https://policies.google.com/privacy")}.setNegativeButton("YouTube terms"){_,_->openExternal("https://www.youtube.com/t/terms")}.show()
            4->disconnect()
            5->AlertDialog.Builder(this).setTitle("Clear your library?").setMessage("All local playlists, favorites and recent plays will be removed. Downloaded audio files remain in Music/YouMu.").setNegativeButton("Cancel",null).setPositiveButton("Clear"){_,_->authGeneration++;operation++;stopActivePlayback();accessToken=null;AccountSession.token=null;playlists.forEach{LocalMusic(this).delete(it.id)};playlists.clear();store.save(playlists);showLibrary()}.show()
        }}.show()
    }
    private fun blend(a:Int,b:Int,f:Float)=Color.rgb((Color.red(a)*f+Color.red(b)*(1-f)).toInt(),(Color.green(a)*f+Color.green(b)*(1-f)).toInt(),(Color.blue(a)*f+Color.blue(b)*(1-f)).toInt())
    private fun localMenu(p:Playlist){
        AlertDialog.Builder(this).setTitle(p.title).setItems(arrayOf("View songs","Rename playlist","Delete playlist")){_,index->when(index){
            0->showLocalSongs(p)
            1->{val input=field("Playlist name",p.title);val dialog=AlertDialog.Builder(this).setTitle("Rename playlist").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{val name=input.text.toString().trim();if(name.isBlank())input.error="Enter a name" else{p.title=name.take(100);store.save(playlists);dialog.dismiss();showLibrary()}}};dialog.show()}
            2->AlertDialog.Builder(this).setTitle("Delete ${p.title}?").setMessage("Downloaded song files will stay on your phone.").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->if(PlaybackStatus.playlistId==p.id)stopActivePlayback();LocalMusic(this).delete(p.id);playlists.removeAll{it.id==p.id};store.save(playlists);showLibrary()}.show()
        }}.show()
    }
    private fun showLocalSongs(p:Playlist){
        val music=LocalMusic(this);val tracks=music.tracks(p.id)
        if(tracks.isEmpty()){showMessage(p.title,"This playlist is empty. Use Add to playlist on Now Playing to save songs here.");return}
        AlertDialog.Builder(this).setTitle("${p.title} · ${tracks.size} songs").setItems(tracks.map{"${it.title}\n${it.artist}"}.toTypedArray()){_,index->
            AlertDialog.Builder(this).setTitle(tracks[index].title).setItems(arrayOf("Download song","Remove from playlist")){_,action->
                if(action==0)download(tracks[index])else{music.remove(p.id,tracks[index].id);p.count=music.tracks(p.id).size;store.save(playlists);showLibrary();showLocalSongs(p)}
            }.setNegativeButton("Close",null).show()
        }.setPositiveButton("Play playlist"){_,_->play(p)}.setNegativeButton("Close",null).show()
    }
    internal fun download(track:QueueTrack){
        if(MusicDownloads.needsPermission(this)){
            pendingDownload=track
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),2002)
        }else MusicDownloads.start(this,track)
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==2002){val track=pendingDownload;pendingDownload=null;if(track!=null){if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED)MusicDownloads.start(this,track)else toast("Storage permission is needed to save songs in Music/YouMu.")}}
    }
    private fun showDownloads(){
        val downloads=MusicDownloads(this);val items=downloads.entries()
        if(items.isEmpty()){showMessage("Downloads","Tap Download on Now Playing to save a song in Music/YouMu. Completed downloads can play offline.");return}
        val labels=items.map {item->val state=downloads.state(item);val detail=when(state?.status){
            android.app.DownloadManager.STATUS_SUCCESSFUL->if(downloads.localUri(item.track.id)!=null)"Saved · Ready offline" else "File removed · Tap to retry"
            android.app.DownloadManager.STATUS_RUNNING->if(state.total>0)"Downloading · ${state.bytes*100/state.total}%" else "Downloading…"
            android.app.DownloadManager.STATUS_PENDING->"Waiting to download"
            android.app.DownloadManager.STATUS_PAUSED->"Waiting for network or storage"
            else->"Download failed · Tap to retry"
        };"${item.track.title}\n$detail"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("Downloads · ${items.size}").setItems(labels){_,index->
            val item=items[index];val ready=downloads.localUri(item.track.id)!=null
            AlertDialog.Builder(this).setTitle(item.track.title).setItems(arrayOf(if(ready)"Play offline collection" else "Retry download","Add to playlist","Delete download")){_,action->when(action){
                0->if(ready)play(Playlist("youmu_downloads","Downloads"))else download(item.track)
                1->LocalMusic.addToPlaylist(this,item.track)
                2->AlertDialog.Builder(this).setTitle("Delete downloaded file?").setMessage(item.track.title).setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->if(PlaybackStatus.playlistId=="youmu_downloads")stopActivePlayback();downloads.remove(item);showDownloads()}.show()
            }}.setNegativeButton("Close",null).show()
        }.setPositiveButton("Play all"){_,_->if(items.any{downloads.localUri(it.track.id)!=null})play(Playlist("youmu_downloads","Downloads"))else toast("Wait for a download to finish first.")}
            .setNeutralButton("Refresh"){_,_->showDownloads()}.setNegativeButton("Close",null).show()
    }
    private fun stopActivePlayback(){if(PlaybackStatus.playlistId.isNotBlank()){PlaybackStatus.clear();startService(Intent(this,PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))}}
    private fun disconnect() {
        if(playlists.any{it.id==PlaybackStatus.playlistId&&it.fromAccount})stopActivePlayback()
        val old=accessToken;accessToken=null;AccountSession.token=null;authGeneration++;operation++;playlists.removeAll{it.fromAccount};store.save(playlists);showLibrary()
        if(old==null){toast("Local account imports removed. Manage Google access in your Google account.");return}
        Identity.getAuthorizationClient(this).clearToken(ClearTokenRequest.builder().setToken(old).build())
        // Google's account connections page lets the user revoke previously granted consent.
        AlertDialog.Builder(this).setTitle("Disconnected on this device").setMessage("Account imports and the current token have been removed. You can also revoke YouMu’s permission in your Google account.").setNegativeButton("Done",null).setPositiveButton("Manage Google access"){_,_->openExternal("https://myaccount.google.com/connections")}.show()
    }
    private fun showMessage(title:String,message:String){if(!isFinishing&&!isDestroyed)AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show()}
    private fun openExternal(url:String){try{val uri=Uri.parse(url);if(uri.scheme in listOf("http","https"))startActivity(Intent(Intent.ACTION_VIEW,uri))}catch(_:Exception){toast("No app is available to open this link.")}}
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
    private fun destroyPlayer(){playerScreen?.destroy();playerScreen=null;nowPlaying=null}
    private fun goBack(){if(nowPlaying!=null)showLibrary() else if(view!="Library"){view="Library";showLibrary()}else finish()}
    @Deprecated("Legacy Android back navigation") override fun onBackPressed(){goBack()}
    override fun onPause(){playerScreen?.pause();super.onPause()}
    override fun onResume(){super.onResume();playerScreen?.resume();if(playerScreen==null&&PlaybackStatus.playlistId.isNotBlank())showLibrary()}
    override fun onStop(){super.onStop()}
    override fun onDestroy(){operation++;destroyPlayer();worker.shutdownNow();super.onDestroy()}
}

class RecordArt(context:android.content.Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);val cx=width/2f;val cy=height/2f;val radius=minOf(width,height)*.48f
        paint.color=Ui.surface;canvas.drawCircle(cx,cy,radius,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f
        for(i in 0..6){paint.color=Ui.border;canvas.drawCircle(cx,cy,radius*(.45f+i*.075f),paint)}
        paint.style=Paint.Style.FILL;paint.color=Ui.accent;canvas.drawCircle(cx,cy,radius*.37f,paint);paint.color=Ui.background;canvas.drawCircle(cx,cy,radius*.07f,paint)
    }
}
