package com.boasnotas.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.boasnotas.model.ImageItem
import com.boasnotas.model.TextBox
import java.io.File
import java.util.WeakHashMap
import kotlin.math.max

/** Desenha caixas de texto e imagens, em coordenadas da página. */
object ItemRenderer {

    private val mainCache = WeakHashMap<TextBox, StaticLayout>()

    fun layout(t: TextBox, useCache: Boolean = true): StaticLayout {
        if (useCache) mainCache[t]?.let { return it }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = t.size
            color = t.color
        }
        val text = t.text.ifEmpty { " " }
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, t.width.toInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        if (useCache) mainCache[t] = l
        return l
    }

    /** Altura ocupada pela caixa de texto (pt). */
    fun textHeight(t: TextBox, useCache: Boolean = true): Float = layout(t, useCache).height.toFloat()

    /** Retângulo da caixa: minX, minY, maxX, maxY. */
    fun textBounds(t: TextBox, useCache: Boolean = true) =
        floatArrayOf(t.x, t.y, t.x + t.width, t.y + textHeight(t, useCache))

    fun imageBounds(im: ImageItem) = floatArrayOf(im.x, im.y, im.x + im.width, im.y + im.height)

    fun drawText(canvas: Canvas, t: TextBox, useCache: Boolean = true) {
        canvas.save()
        canvas.translate(t.x, t.y)
        layout(t, useCache).draw(canvas)
        canvas.restore()
    }

    private val placeholder = Paint().apply { color = Color.rgb(0xEC, 0xEE, 0xF1) }
    fun drawImage(canvas: Canvas, im: ImageItem, bitmap: Bitmap?, paint: Paint) {
        val rect = RectF(im.x, im.y, im.x + im.width, im.y + im.height)
        if (bitmap == null) canvas.drawRect(rect, placeholder)
        else canvas.drawBitmap(bitmap, null, rect, paint)
    }

    /** Lê a imagem já reduzida para ~[maxPx] de lado (economiza memória). */
    fun decode(file: File, maxPx: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }
}
