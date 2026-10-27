package com.youmu.app

/** UI hints only. The service owns the player and is the source of playback state. */
object PlaybackStatus {
    @Volatile var playlistId=""
    @Volatile var playlistTitle=""
    @Volatile var message=""
    @Volatile var loading=false
    @Volatile var currentTitle=""
    @Volatile var playing=false
    @Volatile var failed=false
    @Volatile var diagnostics=""
    fun fail(error:PlaybackFailure){loading=false;playing=false;failed=true;message=error.message;diagnostics=error.diagnostics}
    fun clearFailure(){failed=false;diagnostics="";message=""}
    fun clear(){playlistId="";playlistTitle="";message="";loading=false;currentTitle="";playing=false;failed=false;diagnostics=""}
}
