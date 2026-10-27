package com.youmu.app

import android.app.AlertDialog
import android.content.Context

class PlayerPreferences(context:Context) {
    private val prefs=context.getSharedPreferences("youmu_player",Context.MODE_PRIVATE)
    var theme:Int
        get()=prefs.getInt("theme",0).coerceIn(0,2)
        set(value){prefs.edit().putInt("theme",value.coerceIn(0,2)).apply()}
    var visual:Int
        get()=prefs.getInt("visual",0).coerceIn(0,2)
        set(value){prefs.edit().putInt("visual",value.coerceIn(0,2)).apply()}
    var mode:Int
        get()=prefs.getInt("mode",0).coerceIn(0,3)
        set(value){prefs.edit().putInt("mode",value.coerceIn(0,3)).apply()}
    companion object {
        val themes=arrayOf("Midnight • Sage","Ocean • Ice blue","AMOLED • Lavender")
        val visuals=arrayOf("Song artwork","Spectrum • Frequency bars","Orbit Wave • Audio waveform")
        val modes=arrayOf("Normal","Shuffle","Repeat all","Repeat one")
        fun chooseVisual(context:Context,changed:()->Unit={}) {
            val prefs=PlayerPreferences(context)
            AlertDialog.Builder(context).setTitle("Now playing display")
                .setSingleChoiceItems(visuals,prefs.visual){dialog,index->prefs.visual=index;dialog.dismiss();changed()}
                .setNegativeButton("Close",null).show()
        }
    }
}
