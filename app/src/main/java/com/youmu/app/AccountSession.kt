package com.youmu.app

/** In-memory Google API authorization only. Never passed to the stream extractor. */
object AccountSession {
    @Volatile var token:String?=null
}
