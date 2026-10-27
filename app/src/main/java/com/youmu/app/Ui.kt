package com.youmu.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

object Ui {
    val background = Color.rgb(12, 16, 21)
    val surface = Color.rgb(22, 28, 34)
    val border = Color.rgb(42, 51, 59)
    val accent = Color.rgb(213, 241, 139)
    val white = Color.rgb(240, 243, 241)
    val muted = Color.rgb(146, 159, 163)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(context: Context, color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }
    fun column(context: Context, padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, padding); setPadding(p, p, p, p)
    }
    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(context: Context, value: String, size: Float = 14f, color: Int = white, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    fun gap(parent: LinearLayout, height: Int) { parent.addView(View(parent.context), LinearLayout.LayoutParams(1, dp(parent.context, height))) }
}

class SymbolButton(context: Context, var symbol: String, description: String, filled: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foreground = if (filled) Ui.background else Ui.white
    init {
        contentDescription = description; isClickable = true; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), Ui.shape(context, if (filled) Ui.accent else Ui.surface, 100), null)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.translate(width / 2f, height / 2f)
        val scale = minOf(width, height) / 48f; canvas.scale(scale, scale)
        paint.color = foreground; paint.strokeWidth = 1.8f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.style = Paint.Style.STROKE
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        when (symbol) {
            "play" -> { paint.style=Paint.Style.FILL;canvas.drawPath(Path().apply { moveTo(-5f,-8f);lineTo(8f,0f);lineTo(-5f,8f);close() },paint) }
            "pause" -> { paint.style=Paint.Style.FILL;canvas.drawRoundRect(-7f,-8f,-3f,8f,1f,1f,paint);canvas.drawRoundRect(3f,-8f,7f,8f,1f,1f,paint) }
            "previous","next" -> { if(symbol=="previous")canvas.scale(-1f,1f);paint.style=Paint.Style.FILL;canvas.drawPath(Path().apply {moveTo(-7f,-7f);lineTo(4f,0f);lineTo(-7f,7f);close()},paint);canvas.drawRoundRect(6f,-8f,8f,8f,1f,1f,paint) }
            "back" -> {line(5f,-8f,-3f,0f);line(-3f,0f,5f,8f)}
            "queue" -> {for(y in listOf(-6f,0f,6f)){line(-8f,y,3f,y)};line(8f,2f,8f,10f);line(4f,6f,12f,6f)}
            "more" -> {paint.style=Paint.Style.FILL;for(y in listOf(-6f,0f,6f))canvas.drawCircle(0f,y,1.6f,paint)}
            "heart","heart-filled" -> {if(symbol=="heart-filled"){paint.style=Paint.Style.FILL;paint.color=Ui.accent};canvas.drawPath(Path().apply {moveTo(0f,8f);cubicTo(-20f,-5f,-4f,-14f,0f,-5f);cubicTo(4f,-14f,20f,-5f,0f,8f)},paint)}
            "settings" -> {canvas.drawCircle(0f,0f,7f,paint);canvas.drawCircle(0f,0f,2.5f,paint);for(i in 0..7){canvas.save();canvas.rotate(i*45f);line(0f,-7f,0f,-10f);canvas.restore()}}
        }
        canvas.restore()
    }
    fun update(value: String, description: String) { symbol=value;contentDescription=description;invalidate() }
}
