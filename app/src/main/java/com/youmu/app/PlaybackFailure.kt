package com.youmu.app

import android.os.Build
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class PlaybackFailure(val message:String,val diagnostics:String){
    companion object{
        fun from(error:Throwable,phase:String):PlaybackFailure{
            val chain=generateSequence(error){it.cause}.take(8).toList()
            val names=chain.map{it.javaClass.simpleName}
            val message=when{
                chain.any{it is SocketTimeoutException||it is java.util.concurrent.TimeoutException}->"Loading timed out. Check your connection, then tap Retry."
                chain.any{it is UnknownHostException}->"YouTube could not be reached. Check your internet or DNS, then tap Retry."
                chain.any{it is SSLException}->"A secure connection could not be established. Check your phone’s date and network, then tap Retry."
                chain.any{it is LinkageError}->"A playback component failed on this Android version. Copy diagnostics from Player options."
                names.any{it.contains("ReCaptcha")||it.contains("AntiBot")}->"YouTube blocked this request. Try another network or retry later."
                names.any{it.contains("ContentNotAvailable")||it.contains("Private")||it.contains("AgeRestricted")}->"This content is unavailable without signing in, or is restricted. Try a public playlist."
                error is YouTubeApi.Unauthorized->"YouTube authorization expired. Reconnect your account, then tap Retry."
                else->"Couldn’t load ${if(phase=="playlist")"this playlist" else "this track"}. Tap Retry, or copy diagnostics from Player options."
            }
            // No request URLs, cookies, tokens or exception messages are included.
            val detail="YouMu 0.4.0 | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | phase=$phase | errors=${names.joinToString(" -> ")}"
            return PlaybackFailure(message,detail)
        }
    }
}
