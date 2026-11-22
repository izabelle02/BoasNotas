package com.boasnotas.model

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Contas de geometria da borracha e do laço (seleção). */
object Geometry {

    fun distSqPointSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0f) ((px - ax) * dx + (py - ay) * dy) / len2 else 0f
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** Distância² entre os segmentos AB e CD. */
    fun distSqSegmentSegment(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return min(
            min(distSqPointSegment(ax, ay, cx, cy, dx, dy), distSqPointSegment(bx, by, cx, cy, dx, dy)),
            min(distSqPointSegment(cx, cy, ax, ay, bx, by), distSqPointSegment(dx, dy, ax, ay, bx, by)),
        )
    }

    private fun eraserReach(stroke: Stroke, radius: Float) = radius + stroke.width * 0.35f

    private fun boundsNear(stroke: Stroke, ax: Float, ay: Float, bx: Float, by: Float, reach: Float): Boolean =
        stroke.intersects(min(ax, bx) - reach, min(ay, by) - reach, max(ax, bx) + reach, max(ay, by) + reach)

    /** A borracha (círculo de raio [radius] indo de A até B) encosta no traço? */
    fun strokeTouches(stroke: Stroke, ax: Float, ay: Float, bx: Float, by: Float, radius: Float): Boolean {
        val reach = eraserReach(stroke, radius)
        if (!boundsNear(stroke, ax, ay, bx, by, reach)) return false
        val r2 = reach * reach
        val n = stroke.pointCount
        if (n == 1) return distSqPointSegment(stroke.x(0), stroke.y(0), ax, ay, bx, by) <= r2
        for (i in 0 until n - 1) {
            val d = distSqSegmentSegment(
                stroke.x(i), stroke.y(i), stroke.x(i + 1), stroke.y(i + 1), ax, ay, bx, by,
            )
            if (d <= r2) return true
        }
        return false
    }

    /**
     * Borracha "de precisão": apaga só a parte do traço por onde a borracha passou.
     * Devolve null se o traço não foi tocado; uma lista vazia se sumiu inteiro;
     * ou os pedaços que sobraram.
     */
    fun eraseFromStroke(stroke: Stroke, ax: Float, ay: Float, bx: Float, by: Float, radius: Float): List<Stroke>? {
        if (!strokeTouches(stroke, ax, ay, bx, by, radius)) return null
        val reach = eraserReach(stroke, radius)
        val r2 = reach * reach
        // Deixa os pontos próximos uns dos outros, para o corte ficar no lugar certo.
        val pts = densify(stroke.points, max(radius * 0.4f, 0.5f))
        val n = pts.size / 3
        val pieces = ArrayList<Stroke>()
        var start = -1
        for (i in 0 until n) {
            val erased = distSqPointSegment(pts[i * 3], pts[i * 3 + 1], ax, ay, bx, by) <= r2
            if (erased) {
                if (start >= 0) addPiece(stroke, pts, start, i, pieces)
                start = -1
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) addPiece(stroke, pts, start, n, pieces)
        return pieces
    }

    private fun addPiece(src: Stroke, pts: FloatArray, from: Int, to: Int, out: MutableList<Stroke>) {
        // Um único ponto que sobrou de um traço longo vira uma "sujeirinha": descarta.
        if (to - from < 2 && src.pointCount > 1) return
        out.add(Stroke(src.tool, src.color, src.width, pts.copyOfRange(from * 3, to * 3)))
    }

    /** Insere pontos intermediários para que nenhum trecho seja maior que [maxStep]. */
    fun densify(points: FloatArray, maxStep: Float): FloatArray {
        val n = points.size / 3
        if (n < 2) return points
        val out = FloatList(points.size * 2)
        out.add(points[0], points[1], points[2])
        for (i in 1 until n) {
            val x0 = points[(i - 1) * 3]
            val y0 = points[(i - 1) * 3 + 1]
            val p0 = points[(i - 1) * 3 + 2]
            val x1 = points[i * 3]
            val y1 = points[i * 3 + 1]
            val p1 = points[i * 3 + 2]
            val len = hypot(x1 - x0, y1 - y0)
            val steps = min(ceil(len / maxStep).toInt(), 200)
            for (s in 1 until steps) {
                val t = s.toFloat() / steps
                out.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, p0 + (p1 - p0) * t)
            }
            out.add(x1, y1, p1)
        }
        return out.toArray()
    }

    /** Ponto dentro do polígono [poly] (pares x,y)? Regra par-ímpar. */
    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** O traço está dentro do laço? (pelo menos 60% dos pontos dentro) */
    fun strokeInLasso(stroke: Stroke, poly: FloatArray): Boolean {
        if (poly.size < 6) return false
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until poly.size / 2) {
            minX = min(minX, poly[i * 2]); maxX = max(maxX, poly[i * 2])
            minY = min(minY, poly[i * 2 + 1]); maxY = max(maxY, poly[i * 2 + 1])
        }
        if (!stroke.intersects(minX, minY, maxX, maxY)) return false
        val n = stroke.pointCount
        var inside = 0
        for (i in 0 until n) if (pointInPolygon(stroke.x(i), stroke.y(i), poly)) inside++
        return inside * 10 >= n * 6
    }

    /** Retângulo que contém todos os traços: minX, minY, maxX, maxY (ou null se vazio). */
    fun unionBounds(strokes: Collection<Stroke>): FloatArray? {
        if (strokes.isEmpty()) return null
        val r = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in strokes) {
            val b = s.bounds
            r[0] = min(r[0], b[0]); r[1] = min(r[1], b[1])
            r[2] = max(r[2], b[2]); r[3] = max(r[3], b[3])
        }
        return r
    }
}

/** Lista de floats que cresce sozinha (sem "encaixotar" cada número). */
class FloatList(initialCapacity: Int = 64) {
    private var data = FloatArray(max(initialCapacity, 4))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun add(a: Float, b: Float, c: Float) {
        add(a); add(b); add(c)
    }

    fun add(a: Float, b: Float) {
        add(a); add(b)
    }

    operator fun get(i: Int): Float = data[i]

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
