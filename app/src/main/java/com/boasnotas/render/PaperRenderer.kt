package com.boasnotas.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.boasnotas.model.Page
import com.boasnotas.model.PaperTemplate

/** Desenha o papel (branco + pautas, quadriculado ou pontos), em coordenadas da página. */
object PaperRenderer {
    private const val LINE_SPACING = 25f
    private const val LINE_TOP = 90f
    private const val GRID_SPACING = 18f
    private const val MARGIN_X = 62f

    private val cache = HashMap<String, FloatArray>()

    private val lineColor = Color.rgb(0xC5, 0xD0, 0xDA)
    private val marginColor = Color.rgb(0xF0, 0xB4, 0xB4)
    private val gridColor = Color.rgb(0xDA, 0xE1, 0xE8)
    private val dotColor = Color.rgb(0xA9, 0xB5, 0xBF)

    fun draw(canvas: Canvas, page: Page, paint: Paint, drawWhite: Boolean = true) {
        if (drawWhite) {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.shader = null
            canvas.drawRect(0f, 0f, page.width, page.height, paint)
        }
        if (page.isPdf) return
        drawTemplate(canvas, page.template, page.width, page.height, paint)
    }

    fun drawTemplate(canvas: Canvas, template: PaperTemplate, w: Float, h: Float, paint: Paint) {
        paint.isAntiAlias = true
        paint.pathEffect = null
        when (template) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.LINED -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.6f
                paint.color = lineColor
                canvas.drawLines(lines(template, w, h), paint)
                paint.color = marginColor
                paint.strokeWidth = 0.8f
                canvas.drawLine(MARGIN_X, 0f, MARGIN_X, h, paint)
            }
            PaperTemplate.GRID -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.5f
                paint.color = gridColor
                canvas.drawLines(lines(template, w, h), paint)
            }
            PaperTemplate.DOTTED -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.9f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = dotColor
                canvas.drawPoints(lines(template, w, h), paint)
            }
        }
    }

    @Synchronized
    private fun lines(t: PaperTemplate, w: Float, h: Float): FloatArray =
        cache.getOrPut("${t.ordinal}:$w:$h") {
            val out = ArrayList<Float>()
            when (t) {
                PaperTemplate.LINED -> {
                    var y = LINE_TOP
                    while (y < h - 30f) {
                        out += listOf(0f, y, w, y); y += LINE_SPACING
                    }
                }
                PaperTemplate.GRID -> {
                    val ox = (w % GRID_SPACING) / 2f
                    val oy = (h % GRID_SPACING) / 2f
                    var x = ox
                    while (x <= w) { out += listOf(x, 0f, x, h); x += GRID_SPACING }
                    var y = oy
                    while (y <= h) { out += listOf(0f, y, w, y); y += GRID_SPACING }
                }
                PaperTemplate.DOTTED -> {
                    val ox = (w % GRID_SPACING) / 2f + GRID_SPACING / 2f
                    val oy = (h % GRID_SPACING) / 2f + GRID_SPACING / 2f
                    var y = oy
                    while (y < h) {
                        var x = ox
                        while (x < w) { out += listOf(x, y); x += GRID_SPACING }
                        y += GRID_SPACING
                    }
                }
                PaperTemplate.BLANK -> Unit
            }
            out.toFloatArray()
        }
}
