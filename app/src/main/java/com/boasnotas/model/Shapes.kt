package com.boasnotas.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Formas automáticas": transforma um rabisco em linha, círculo/elipse, triângulo,
 * retângulo ou polígono perfeitos. Trabalha só com pontos (x, y), em coordenadas da página.
 */
object ShapeRecognizer {

    enum class Kind { LINE, ELLIPSE, TRIANGLE, RECTANGLE, POLYGON }

    class Shape(val kind: Kind, val points: FloatArray /* x,y pares */)

    /** [xy] = pares x,y do traço desenhado. Devolve null se não parecer uma forma. */
    fun recognize(xy: FloatArray): Shape? {
        val n = xy.size / 2
        if (n < 4) return null
        var length = 0f
        for (i in 1 until n) length += dist(xy, i - 1, i)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, xy[i * 2]); maxX = max(maxX, xy[i * 2])
            minY = min(minY, xy[i * 2 + 1]); maxY = max(maxY, xy[i * 2 + 1])
        }
        val w = maxX - minX
        val h = maxY - minY
        val diag = hypot(w, h)
        if (diag < 8f || length < 12f) return null

        val endGap = dist(xy, 0, n - 1)

        // Linha: o caminho é quase do tamanho da distância entre as pontas.
        if (endGap / length > 0.9f) {
            var x0 = xy[0]; var y0 = xy[1]; var x1 = xy[(n - 1) * 2]; var y1 = xy[(n - 1) * 2 + 1]
            // quase horizontal/vertical? endireita
            val ang = Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
            val a = ((ang % 180) + 180) % 180
            if (a < 4 || a > 176) { val m = (y0 + y1) / 2; y0 = m; y1 = m }
            else if (abs(a - 90) < 4) { val m = (x0 + x1) / 2; x0 = m; x1 = m }
            return Shape(Kind.LINE, floatArrayOf(x0, y0, x1, y1))
        }

        // Formas fechadas: as pontas precisam terminar perto uma da outra.
        if (endGap > max(0.25f * diag, 12f)) return null

        // Elipse: os pontos ficam a uma distância "constante" do centro (normalizada pelos eixos).
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val rx = max(w / 2, 1f)
        val ry = max(h / 2, 1f)
        var err = 0f
        for (i in 0 until n) {
            val dx = (xy[i * 2] - cx) / rx
            val dy = (xy[i * 2 + 1] - cy) / ry
            err += abs(sqrt(dx * dx + dy * dy) - 1f)
        }
        err /= n

        val closed = xy.copyOf(n * 2)
        val simplified = simplifyClosed(closed, 0.07f * diag)
        val corners = simplified.size / 2

        if (err < 0.11f && corners >= 5 || err < 0.07f) {
            var ex = rx
            var ey = ry
            if (abs(rx - ry) / max(rx, ry) < 0.15f) { val r = (rx + ry) / 2; ex = r; ey = r }
            val steps = 72
            val out = FloatArray((steps + 1) * 2)
            for (k in 0..steps) {
                val t = 2 * PI * k / steps - PI / 2
                out[k * 2] = cx + ex * cos(t).toFloat()
                out[k * 2 + 1] = cy + ey * sin(t).toFloat()
            }
            return Shape(Kind.ELLIPSE, out)
        }

        when (corners) {
            3 -> return Shape(Kind.TRIANGLE, closePolygon(simplified))
            4 -> {
                if (isRectangular(simplified)) {
                    // Retângulo "em pé"? usa a caixa; inclinado: mantém os cantos
                    if (axisAligned(simplified)) {
                        return Shape(Kind.RECTANGLE, floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY))
                    }
                }
                return Shape(Kind.POLYGON, closePolygon(simplified))
            }
            in 5..8 -> return Shape(Kind.POLYGON, closePolygon(simplified))
        }
        return null
    }

    /** Converte a forma em pontos de traço (x, y, pressão), bem juntinhos para a borracha funcionar bem. */
    fun toStrokePoints(shape: Shape, pressure: Float): FloatArray {
        val pts = FloatList(256)
        val p = shape.points
        val n = p.size / 2
        for (i in 0 until n) {
            if (i == 0) {
                pts.add(p[0], p[1], pressure)
                continue
            }
            val x0 = p[(i - 1) * 2]; val y0 = p[(i - 1) * 2 + 1]
            val x1 = p[i * 2]; val y1 = p[i * 2 + 1]
            val len = hypot(x1 - x0, y1 - y0)
            val steps = max(1, (len / 3f).toInt())
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                pts.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, pressure)
            }
        }
        return pts.toArray()
    }

    private fun dist(xy: FloatArray, a: Int, b: Int) =
        hypot(xy[a * 2] - xy[b * 2], xy[a * 2 + 1] - xy[b * 2 + 1])

    private fun closePolygon(p: FloatArray): FloatArray {
        val out = p.copyOf(p.size + 2)
        out[p.size] = p[0]
        out[p.size + 1] = p[1]
        return out
    }

    private fun angleAt(p: FloatArray, i: Int): Double {
        val n = p.size / 2
        val a = (i + n - 1) % n
        val c = (i + 1) % n
        val ax = p[a * 2] - p[i * 2]; val ay = p[a * 2 + 1] - p[i * 2 + 1]
        val cx = p[c * 2] - p[i * 2]; val cy = p[c * 2 + 1] - p[i * 2 + 1]
        val d = hypot(ax, ay) * hypot(cx, cy)
        if (d == 0f) return 0.0
        return Math.toDegrees(acos(((ax * cx + ay * cy) / d).toDouble().coerceIn(-1.0, 1.0)))
    }

    private fun isRectangular(p: FloatArray): Boolean = (0 until 4).all { abs(angleAt(p, it) - 90) < 22 }

    private fun axisAligned(p: FloatArray): Boolean = (0 until 4).all { i ->
        val j = (i + 1) % 4
        val dx = abs(p[j * 2] - p[i * 2])
        val dy = abs(p[j * 2 + 1] - p[i * 2 + 1])
        min(dx, dy) < 0.2f * max(dx, dy)
    }

    /**
     * Simplifica um caminho fechado (Douglas-Peucker), devolvendo só os "cantos".
     * Começa pelo ponto mais longe do primeiro, para não depender de onde o desenho começou.
     */
    fun simplifyClosed(xy: FloatArray, epsilon: Float): FloatArray {
        val n = xy.size / 2
        var far = 0
        var farD = 0f
        for (i in 1 until n) {
            val d = dist(xy, 0, i)
            if (d > farD) { farD = d; far = i }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        dp(xy, 0, far, epsilon, keep)
        dp(xy, far, n - 1, epsilon, keep)
        // o trecho final volta ao início: trata (n-1 -> 0) como parte do contorno
        val idx = (0 until n).filter { keep[it] }.toMutableList()
        // remove o último ponto se estiver colado no primeiro
        if (idx.size > 1 && dist(xy, idx.last(), idx.first()) < epsilon) idx.removeAt(idx.size - 1)
        // remove cantos "rasos" (quase 180°)
        var changed = true
        var pts = idx.flatMap { listOf(xy[it * 2], xy[it * 2 + 1]) }.toFloatArray()
        while (changed && pts.size / 2 > 3) {
            changed = false
            for (i in 0 until pts.size / 2) {
                if (angleAt(pts, i) > 155) {
                    pts = pts.filterIndexed { k, _ -> k / 2 != i }.toFloatArray()
                    changed = true
                    break
                }
            }
        }
        return pts
    }

    private fun dp(xy: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray) {
        if (b <= a + 1) return
        var maxD = -1f
        var idx = -1
        for (i in a + 1 until b) {
            val d = sqrt(
                Geometry.distSqPointSegment(xy[i * 2], xy[i * 2 + 1], xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1]),
            )
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > eps) {
            keep[idx] = true
            dp(xy, a, idx, eps, keep)
            dp(xy, idx, b, eps, keep)
        }
    }
}

/** Separa os traços de uma página em linhas de escrita (para o reconhecimento da letra). */
object InkLines {
    fun group(strokes: List<Stroke>): List<List<Stroke>> {
        val pen = strokes.filter { it.tool == Tool.PEN && it.pointCount > 1 }
        if (pen.isEmpty()) return emptyList()
        val sorted = pen.sortedBy { (it.bounds[1] + it.bounds[3]) / 2 }
        class Line(var top: Float, var bottom: Float) { val items = ArrayList<Stroke>() }
        val lines = ArrayList<Line>()
        for (s in sorted) {
            val top = s.bounds[1] + s.width
            val bottom = s.bounds[3] - s.width
            val h = max(bottom - top, 1f)
            val line = lines.lastOrNull { l ->
                val overlap = min(bottom, l.bottom) - max(top, l.top)
                overlap > 0.35f * min(h, l.bottom - l.top) || (top + bottom) / 2 in l.top..l.bottom
            }
            if (line != null && (line.bottom - line.top) < 140f) {
                line.items.add(s)
                line.top = min(line.top, top)
                line.bottom = max(line.bottom, bottom)
            } else {
                lines.add(Line(top, bottom).also { it.items.add(s) })
            }
        }
        // mantém a ordem em que os traços foram escritos dentro de cada linha
        val order = HashMap<Stroke, Int>()
        strokes.forEachIndexed { i, s -> order[s] = i }
        return lines.sortedBy { it.top }.map { l -> l.items.sortedBy { order[it] ?: 0 } }
    }
}
