package com.boasnotas.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.min

object Palette {
    val pen = intArrayOf(
        0xFF1C1C1E.toInt(), 0xFF1E4FD8.toInt(), 0xFFD32F2F.toInt(), 0xFF2E7D32.toInt(),
        0xFF7B1FA2.toInt(), 0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFF8D6E63.toInt(),
    )
    val highlighter = intArrayOf(
        0xFFFFE135.toInt(), 0xFF7CF29A.toInt(), 0xFFFF8AD8.toInt(), 0xFF7CC8FF.toInt(), 0xFFFFB45C.toInt(),
    )
    val covers = intArrayOf(
        0xFF3F51B5.toInt(), 0xFF1E88E5.toInt(), 0xFF00897B.toInt(), 0xFF43A047.toInt(),
        0xFFF4511E.toInt(), 0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(),
        0xFF6D4C41.toInt(), 0xFF546E7A.toInt(), 0xFF212121.toInt(), 0xFFFDD835.toInt(),
    )

    /** Espessuras (pt) fina / média / grossa. */
    val penWidths = floatArrayOf(1.1f, 2.0f, 3.4f)
    val highlighterWidths = floatArrayOf(8f, 14f, 22f)
    val eraserRadii = floatArrayOf(4f, 9f, 18f)
    /** Tamanho da letra das caixas de texto (pt). */
    val textSizes = floatArrayOf(11f, 15f, 22f)
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Aplica o espaço das barras do sistema (status e navegação) como padding. */
fun View.applySystemBarPadding() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(left = l + bars.left, top = t + bars.top, right = r + bars.right, bottom = b + bars.bottom)
        insets
    }
}

/** Bolinha de cor (com anel quando selecionada). */
class SwatchView(context: Context, val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val size = context.dpi(36)
        layoutParams = ViewGroup.MarginLayoutParams(size, size)
        isClickable = true
        isFocusable = true
        contentDescription = "Cor"
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (isSelected) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2.5f)
            paint.color = 0xFF3F51B5.toInt()
            canvas.drawCircle(cx, cy, r - context.dp(2f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        val inner = r - context.dp(if (isSelected) 7f else 6f)
        canvas.drawCircle(cx, cy, inner, paint)
        if (Color.luminance(color) > 0.8f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(1f)
            paint.color = 0x33000000
            canvas.drawCircle(cx, cy, inner, paint)
        }
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }
}

/** Botão de espessura: um ponto do tamanho do traço. */
class SizeView(context: Context, private val level: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var dotColor: Int = 0xFF1C1C1E.toInt()
        set(v) { field = v; invalidate() }

    init {
        val size = context.dpi(36)
        layoutParams = ViewGroup.MarginLayoutParams(size, size)
        isClickable = true
        isFocusable = true
        contentDescription = arrayOf("Fino", "Médio", "Grosso")[level.coerceIn(0, 2)]
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (isSelected) {
            paint.style = Paint.Style.FILL
            paint.color = 0x223F51B5
            canvas.drawCircle(cx, cy, min(width, height) / 2f - context.dp(1f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = dotColor
        canvas.drawCircle(cx, cy, context.dp(2.5f + level * 2.8f), paint)
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }
}
