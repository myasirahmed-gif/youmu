package com.youmu.app

object PlaybackFormat {
    val VIDEO_ID=Regex("^[A-Za-z0-9_-]{11}$")
    fun time(seconds:Double):String {
        val total=seconds.takeIf{it.isFinite()&&it>=0}?.toLong()?:0L
        return if(total>=3600)"%d:%02d:%02d".format(total/3600,total/60%60,total%60)else "%d:%02d".format(total/60,total%60)
    }
}
