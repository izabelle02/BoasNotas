package com.boasnotas.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.boasnotas.model.FloatList
import com.boasnotas.model.Stroke
import com.boasnotas.model.Tool
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Monta o desenho de um traço de caneta com espessura variável (pressão da S Pen).
 *
 * O traço é a união de círculos (um em cada ponto) e trapézios ligando um ponto ao próximo,
 * todos no mesmo sentido, dentro de um único Path. Assim não há "buracos" nem partes mais
 * escuras, e dá para montar o traço aos poucos enquanto você escreve.
 * Entre os pontos medidos, a curva é suavizada (Catmull-Rom).
 */
class PenPathBuilder {
    /** Parte do traço que já não muda mais. */
    val path = Path()

    private val raw = FloatList(256) // x, y, raio
    private var emitted = 0          // quantos trechos (i -> i+1) já entraram em [path]
    private var baseWidth = 2f
    private var smoothR = -1f
    private val main = Emitter()
    private val tailEmitter = Emitter()

    private val count get() = raw.size / 3

    fun reset(width: Float) {
        path.rewind()
        raw.clear()
        emitted = 0
        baseWidth = width
        smoothR = -1f
        main.target = path
        main.hasLast = false
    }

    fun add(x: Float, y: Float, pressure: Float) {
        val r = radiusFor(baseWidth, pressure)
        smoothR = if (smoothR < 0f) r else smoothR + (r - smoothR) * 0.35f
        raw.add(x, y, smoothR)
        if (count == 1) {
            main.point(x, y, smoothR)
            return
        }
        // Um trecho só é definitivo quando já existe o ponto seguinte (para a curva).
        while (emitted < count - 2) emitSegment(emitted++, main, count)
    }

    /** Termina o traço: o que ainda era provisório entra no [path]. */
    fun finish() {
        while (emitted < count - 1) emitSegment(emitted++, main, count)
    }

    /**
     * Monta a ponta provisória do traço (o último trecho e os pontos "previstos")
     * em [tail], sem mexer no [path].
     */
    fun buildTail(tail: Path, predicted: FloatList?) {
        tail.rewind()
        if (count == 0) return
        tailEmitter.copyFrom(main)
        tailEmitter.target = tail
        for (i in emitted until count - 1) emitSegment(i, tailEmitter, count)
        if (predicted != null) {
            var i = 0
            while (i + 2 < predicted.size) {
                tailEmitter.point(predicted[i], predicted[i + 1], radiusFor(baseWidth, predicted[i + 2]).let {
                    smoothR + (it - smoothR) * 0.35f
                })
                i += 3
            }
        }
    }

    private fun emitSegment(i: Int, e: Emitter, n: Int) {
        val i0 = max(i - 1, 0)
        val i3 = min(i + 2, n - 1)
        val x0 = raw[i0 * 3]; val y0 = raw[i0 * 3 + 1]
        val x1 = raw[i * 3]; val y1 = raw[i * 3 + 1]; val r1 = raw[i * 3 + 2]
        val x2 = raw[(i + 1) * 3]; val y2 = raw[(i + 1) * 3 + 1]; val r2 = raw[(i + 1) * 3 + 2]
        val x3 = raw[i3 * 3]; val y3 = raw[i3 * 3 + 1]
        val len = hypot(x2 - x1, y2 - y1)
        val steps = ceil(len / STEP).toInt().coerceIn(1, 24)
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val t2 = t * t
            val t3 = t2 * t
            // Catmull-Rom uniforme
            val x = 0.5f * ((2 * x1) + (-x0 + x2) * t + (2 * x0 - 5 * x1 + 4 * x2 - x3) * t2 + (-x0 + 3 * x1 - 3 * x2 + x3) * t3)
            val y = 0.5f * ((2 * y1) + (-y0 + y2) * t + (2 * y0 - 5 * y1 + 4 * y2 - y3) * t2 + (-y0 + 3 * y1 - 3 * y2 + y3) * t3)
            e.point(x, y, r1 + (r2 - r1) * t)
        }
    }

    private class Emitter {
        var target: Path? = null
        var hasLast = false
        var lx = 0f
        var ly = 0f
        var lr = 0f

        fun copyFrom(o: Emitter) {
            hasLast = o.hasLast; lx = o.lx; ly = o.ly; lr = o.lr
        }

        fun point(x: Float, y: Float, r: Float) {
            val p = target ?: return
            if (hasLast) {
                val dx = x - lx
                val dy = y - ly
                val len = hypot(dx, dy)
                if (len < 0.01f) return
                val nx = -dy / len
                val ny = dx / len
                // Mesmo sentido (horário) dos círculos: a união fica sem buracos.
                p.moveTo(lx - nx * lr, ly - ny * lr)
                p.lineTo(x - nx * r, y - ny * r)
                p.lineTo(x + nx * r, y + ny * r)
                p.lineTo(lx + nx * lr, ly + ny * lr)
                p.close()
            }
            p.addCircle(x, y, r, Path.Direction.CW)
            hasLast = true
            lx = x; ly = y; lr = r
        }
    }

    companion object {
        private const val STEP = 1.2f

        fun radiusFor(width: Float, pressure: Float): Float {
            val p = pressure.coerceIn(0f, 1f)
            return max(width * (0.25f + 0.75f * p.pow(0.6f)) * 0.5f, 0.2f)
        }

        fun buildPenPath(s: Stroke): Path {
            val b = PenPathBuilder()
            b.reset(s.width)
            for (i in 0 until s.pointCount) b.add(s.x(i), s.y(i), s.p(i))
            b.finish()
            return b.path
        }
    }
}

object StrokeRenderer {
    const val HIGHLIGHTER_ALPHA = 0x70

    fun buildHighlighterPath(points: FloatArray, count: Int, out: Path) {
        out.rewind()
        val n = count
        if (n == 0) return
        out.moveTo(points[0], points[1])
        if (n == 1) {
            out.lineTo(points[0] + 0.01f, points[1])
            return
        }
        for (i in 1 until n - 1) {
            val cx = points[i * 3]
            val cy = points[i * 3 + 1]
            val mx = (cx + points[(i + 1) * 3]) / 2f
            val my = (cy + points[(i + 1) * 3 + 1]) / 2f
            out.quadTo(cx, cy, mx, my)
        }
        out.lineTo(points[(n - 1) * 3], points[(n - 1) * 3 + 1])
    }

    fun buildPath(s: Stroke): Path = when (s.tool) {
        Tool.PEN -> PenPathBuilder.buildPenPath(s)
        Tool.HIGHLIGHTER -> Path().also { buildHighlighterPath(s.points, s.pointCount, it) }
    }

    fun preparePaint(paint: Paint, tool: Tool, color: Int, width: Float) {
        paint.isAntiAlias = true
        paint.shader = null
        paint.pathEffect = null
        when (tool) {
            Tool.PEN -> {
                paint.style = Paint.Style.FILL
                paint.color = color
            }
            Tool.HIGHLIGHTER -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = width
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.argb(HIGHLIGHTER_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
            }
        }
    }

    /** Guarda os Paths já montados (cada traço é montado uma vez só). Use só na thread principal. */
    class PathCache {
        private val map = WeakHashMap<Stroke, Path>()
        fun get(s: Stroke): Path = map.getOrPut(s) { buildPath(s) }
        fun put(s: Stroke, p: Path) { map[s] = p }
    }

    /**
     * Desenha os traços que aparecem no retângulo visível (coordenadas da página).
     * [cache] pode ser null (desenho fora da tela, em outra thread).
     */
    fun drawStrokes(
        canvas: Canvas,
        strokes: List<Stroke>,
        paint: Paint,
        cache: PathCache?,
        visible: FloatArray? = null,
        skip: Set<Stroke>? = null,
    ) {
        for (s in strokes) {
            if (skip != null && skip.contains(s)) continue
            if (visible != null && !s.intersects(visible[0], visible[1], visible[2], visible[3])) continue
            val path = cache?.get(s) ?: buildPath(s)
            preparePaint(paint, s.tool, s.color, s.width)
            canvas.drawPath(path, paint)
        }
    }
}
