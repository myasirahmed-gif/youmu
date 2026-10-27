package com.youmu.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Optional native Canvas renders for inspecting the actual Android layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerPreviewTest {
    private fun capture(view:View,name:String){
        view.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(843,View.MeasureSpec.EXACTLY));view.layout(0,0,411,843)
        val bitmap=Bitmap.createBitmap(411,843,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val target=File("build/previews/$name.png");target.parentFile!!.mkdirs();target.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun renderPlayerAndLibrary(){
        assumeTrue(System.getProperty("youmu.preview")=="true")
        val app=RuntimeEnvironment.getApplication()
        for(theme in 0..2){
            PlayerPreferences(app).theme=theme;PlayerPreferences(app).visual=if(theme==0)0 else theme;Ui.apply(app)
            val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
            val state=AudioSnapshot(connected=true,playing=true,position=83000,duration=234000,index=0,tracks=listOf(QueueTrack("demo","Somewhere after midnight","The Night Sessions"),QueueTrack("next","Ocean Drive","The Night Sessions")),mode=1)
            val controls=object:AudioControls{
                override fun connect(onChange:(AudioSnapshot)->Unit){onChange(state)}
                override fun snapshot()=state
                override fun command(name:String,value:Long){}
                override fun release(){}
            }
            val player=PlayerScreen(activity,Playlist("PL1234567890","Late night rotation"),{},{},{},controls=controls)
            activity.setContentView(player.view)
            AudioVisuals.frame=AudioVisuals.analyze(FloatArray(512){(.65*sin(2*PI*440*it/44100)+.2*sin(2*PI*3000*it/44100)).toFloat()},44100)
            capture(player.view,"player-$theme");player.destroy();owner.pause().stop().destroy()
        }
        PlayerPreferences(app).theme=0;Ui.apply(app)
        PlaylistStore(app).save(listOf(Playlist("local_preview","Late night rotation",count=8)))
        val owner=Robolectric.buildActivity(MainActivity::class.java).setup()
        capture(owner.get().findViewById(android.R.id.content),"library");owner.pause().stop().destroy()
    }
}
