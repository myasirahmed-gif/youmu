package com.youmu.app

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI
import java.util.concurrent.TimeUnit

data class AudioTrack(val id:String,val title:String,val artist:String,val artwork:String,val durationSeconds:Long)
data class ResolvedAudio(val url:String,val mimeType:String?)
data class AudioPlaylist(val title:String,val tracks:List<AudioTrack>)

/** Resolves public content only. Google OAuth tokens are never passed to this component. */
object AudioRepository {
    const val USER_AGENT="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
    private var initialized=false
    private val cache=LinkedHashMap<String,Pair<Long,ResolvedAudio>>()
    @Synchronized private fun initialize(){
        if(initialized)return
        NewPipe.init(object:Downloader(){
            private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
            override fun execute(request:Request):Response {
                check(!Thread.currentThread().isInterrupted){"Request cancelled"}
                val builder=okhttp3.Request.Builder().url(request.url())
                request.headers().forEach {(name,values)->values.forEach {builder.addHeader(name,it)}}
                if(request.headers().keys.none{it.equals("User-Agent",true)})builder.header("User-Agent",USER_AGENT)
                val body=request.dataToSend()?.toRequestBody(null)
                builder.method(request.httpMethod(),body)
                return client.newCall(builder.build()).execute().use {response->
                    val bytes=response.body?.source()?.let{source->
                        val buffer=okio.Buffer();var total=0L
                        while(true){val count=source.read(buffer,8192);if(count<0)break;total+=count;require(total<=12_000_000){"Response too large"};check(!Thread.currentThread().isInterrupted){"Request cancelled"}}
                        buffer.readUtf8()
                    }.orEmpty()
                    Response(response.code,response.message,response.headers.toMultimap(),bytes,response.request.url.toString())
                }
            }
        },Localization("en","GB"))
        initialized=true
    }
    fun playlist(id:String,onPage:((AudioPlaylist)->Unit)?=null):AudioPlaylist {
        require(Regex("^[A-Za-z0-9_-]{10,150}$").matches(id))
        initialize()
        val url="https://www.youtube.com/playlist?list=$id"
        val info=PlaylistInfo.getInfo(ServiceList.YouTube,url)
        val tracks=mutableListOf<AudioTrack>()
        fun append(items:List<StreamInfoItem>){items.forEach {item->
            val videoId=videoId(item.url)
            if(videoId!=null)tracks.add(AudioTrack(videoId,item.name,item.uploaderName.orEmpty(),item.thumbnails.lastOrNull()?.url.orEmpty(),item.duration))
        }}
        append(info.relatedItems)
        if(tracks.isNotEmpty())onPage?.invoke(AudioPlaylist(info.name,tracks.toList()))
        var page=info.nextPage;var pageCount=0
        while(Page.isValid(page)){
            check(!Thread.currentThread().isInterrupted){"Request cancelled"}
            require(++pageCount<=200){"This playlist is too large to load in one session."}
            val more=PlaylistInfo.getMoreItems(ServiceList.YouTube,url,page)
            append(more.items);page=more.nextPage
            if(tracks.isNotEmpty())onPage?.invoke(AudioPlaylist(info.name,tracks.toList()))
        }
        require(tracks.isNotEmpty()){"No playable public videos were found in this playlist."}
        return AudioPlaylist(info.name,tracks)
    }
    fun resolve(id:String):ResolvedAudio {
        require(PlaybackFormat.VIDEO_ID.matches(id))
        initialize()
        synchronized(cache){cache[id]?.let{(time,audio)->if(System.currentTimeMillis()-time<5*60_000)return audio}}
        val info=StreamInfo.getInfo(ServiceList.YouTube,"https://www.youtube.com/watch?v=$id")
        require(info.ageLimit<=0){"This video requires account/age verification and cannot be played here."}
        val audio=info.audioStreams.filter{it.isUrl&&it.deliveryMethod==DeliveryMethod.PROGRESSIVE_HTTP}
            .maxByOrNull{it.averageBitrate}?:throw IllegalStateException("No public audio stream is available for this video.")
        val uri=URI(audio.content)
        require(uri.scheme=="https"&&!uri.host.isNullOrBlank()){"The audio source is unavailable."}
        val result=ResolvedAudio(uri.toString(),audio.format?.mimeType)
        synchronized(cache){if(cache.size>=24)cache.remove(cache.keys.first());cache[id]=System.currentTimeMillis() to result}
        return result
    }
    fun invalidate(id:String){synchronized(cache){cache.remove(id)}}
    fun videoId(url:String):String? = runCatching {
        val uri=URI(url)
        if(uri.host !in listOf("www.youtube.com","youtube.com","m.youtube.com","music.youtube.com","youtu.be"))return null
        val id=if(uri.host=="youtu.be")uri.path.trim('/')else uri.rawQuery?.split('&')?.firstOrNull{it.startsWith("v=")}?.substringAfter("v=")
        id?.takeIf{PlaybackFormat.VIDEO_ID.matches(it)}
    }.getOrNull()
}
