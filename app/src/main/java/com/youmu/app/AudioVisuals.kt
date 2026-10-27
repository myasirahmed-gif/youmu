package com.youmu.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class VisualFrame(val spectrum:FloatArray=FloatArray(32),val wave:FloatArray=FloatArray(128),val level:Float=0f,val time:Long=0)

/** Decoded music samples only: no microphone capture, permissions or generated animation. */
object AudioVisuals {
    @Volatile var enabled=false
    @Volatile var frame=VisualFrame()
    fun clear(){frame=VisualFrame()}
    fun analyze(samples:FloatArray,sampleRate:Int):VisualFrame {
        val n=512;val real=DoubleArray(n);val imaginary=DoubleArray(n)
        for(i in 0 until n)real[i]=samples.getOrElse(i){0f}.toDouble()*(.5-.5*cos(2*PI*i/(n-1)))
        var j=0
        for(i in 1 until n){var bit=n shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val t=real[i];real[i]=real[j];real[j]=t}}
        var length=2
        while(length<=n){
            val angle=-2*PI/length;val wr=cos(angle);val wi=sin(angle)
            var base=0
            while(base<n){var r=1.0;var im=0.0;for(k in 0 until length/2){
                val a=base+k;val b=a+length/2;val tr=real[b]*r-imaginary[b]*im;val ti=real[b]*im+imaginary[b]*r
                real[b]=real[a]-tr;imaginary[b]=imaginary[a]-ti;real[a]+=tr;imaginary[a]+=ti
                val old=r;r=r*wr-im*wi;im=old*wi+im*wr
            };base+=length};length=length shl 1
        }
        val spectrum=FloatArray(32){index->
            val low=(40.0*(minOf(16000.0,sampleRate/2.0)/40).pow(index/32.0)*n/sampleRate).toInt().coerceIn(1,n/2-1)
            val high=(40.0*(minOf(16000.0,sampleRate/2.0)/40).pow((index+1)/32.0)*n/sampleRate).toInt().coerceIn(low+1,n/2)
            var peak=0.0;for(bin in low until high)peak=max(peak,hypot(real[bin],imaginary[bin])*4/n)
            ((20*log10(max(peak,0.001))+60)/60).toFloat().coerceIn(0f,1f)
        }
        val wave=FloatArray(128){samples.getOrElse(it*4){0f}.coerceIn(-1f,1f)}
        val rms=sqrt(samples.take(n).sumOf{it.toDouble()*it}/n).toFloat()
        return VisualFrame(spectrum,wave,rms,SystemClock.uptimeMillis())
    }
}

class MusicSampleSink:TeeAudioProcessor.AudioBufferSink {
    private var channels=2;private var encoding=C.ENCODING_PCM_16BIT;private var rate=44100
    private val samples=FloatArray(512);private var count=0;private var last=0L
    override fun flush(sampleRateHz:Int,channelCount:Int,encoding:Int){rate=sampleRateHz;channels=channelCount.coerceAtLeast(1);this.encoding=encoding;count=0;AudioVisuals.clear()}
    override fun handleBuffer(buffer:ByteBuffer){
        if(!AudioVisuals.enabled){count=0;return}
        if(encoding!=C.ENCODING_PCM_16BIT&&encoding!=C.ENCODING_PCM_FLOAT)return
        val input=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);val bytes=if(encoding==C.ENCODING_PCM_FLOAT)4 else 2
        while(input.remaining()>=bytes*channels){
            var sample=0f;repeat(channels){sample+=if(bytes==4)input.float else input.short/32768f}
            samples[count++]=sample/channels
            if(count==samples.size){
                val now=SystemClock.uptimeMillis()
                if(now-last>=30){AudioVisuals.frame=AudioVisuals.analyze(samples,rate);last=now}
                count=0
            }
        }
    }
}

class MusicVisualView(context:Context):View(context) {
    var mode=1
    var playing=false
    var running=false
        set(value){field=value;if(value)postInvalidateOnAnimation()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val bars=FloatArray(32)
    init {background=Ui.shape(context,Ui.surface,28);clipToOutline=true;contentDescription="Real-time audio visualization";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val w=width.toFloat();val h=height.toFloat();val cx=w/2;val cy=h/2
        val frame=AudioVisuals.frame;val live=playing&&SystemClock.uptimeMillis()-frame.time<600
        paint.shader=RadialGradient(cx,cy,w*.7f,intArrayOf(Color.argb(38,Color.red(Ui.accent),Color.green(Ui.accent),Color.blue(Ui.accent)),Ui.surface),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=Ui.border
        for(i in 1..3)canvas.drawCircle(cx,cy,w*(.12f+i*.085f),paint)
        for(i in bars.indices){val target=if(live)frame.spectrum[i] else 0f;bars[i]+=(target-bars[i])*.3f}
        if(mode==1){
            val spacing=w*.78f/32;val base=h*.70f
            paint.style=Paint.Style.FILL
            for(i in bars.indices){
                val x=w*.11f+i*spacing;val height=max(3f,bars[i]*h*.42f)
                paint.shader=LinearGradient(0f,base-height,0f,base,Ui.accent,Color.argb(80,Color.red(Ui.accent),Color.green(Ui.accent),Color.blue(Ui.accent)),Shader.TileMode.CLAMP)
                canvas.drawRoundRect(x,base-height,x+spacing*.6f,base,spacing*.3f,spacing*.3f,paint)
                paint.shader=null;paint.alpha=25;paint.color=Ui.accent;canvas.drawRoundRect(x,base+6,x+spacing*.6f,base+height*.22f+6,2f,2f,paint);paint.alpha=255
            }
        }else{
            val radius=w*.22f;paint.style=Paint.Style.STROKE;paint.strokeWidth=w*.007f;paint.strokeCap=Paint.Cap.ROUND
            for(layer in 0..1){
                path.reset()
                for(i in 0..128){
                    val angle=i*2*PI/128-PI/2
                    val sample=if(live)frame.wave[i%128] else 0f
                    val r=radius+layer*w*.055f+sample*w*.13f*(if(layer==0)1 else -1)
                    val x=cx+cos(angle).toFloat()*r;val y=cy+sin(angle).toFloat()*r
                    if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
                };path.close();paint.color=Ui.accent;paint.alpha=if(layer==0)255 else 100;canvas.drawPath(path,paint)
            };paint.alpha=255
            paint.style=Paint.Style.FILL;paint.color=Ui.accent;canvas.drawCircle(cx,cy,w*.035f+(if(live)frame.level else 0f)*w*.09f,paint)
        }
        paint.style=Paint.Style.FILL;paint.color=Ui.muted;paint.textSize=w*.026f;paint.typeface=Typeface.create("sans-serif-medium",0);paint.textAlign=Paint.Align.CENTER
        canvas.drawText(if(mode==1)"S P E C T R U M" else "O R B I T   W A V E",cx,h*.13f,paint)
        paint.textSize=w*.023f;canvas.drawText(if(playing)"LIVE AUDIO" else "READY WHEN YOU ARE",cx,h*.90f,paint)
        if(running&&isShown)postInvalidateDelayed(33)
    }
}
