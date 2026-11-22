package com.boasnotas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapesTest {
    private val rnd = java.util.Random(42)
    private fun jitter() = (rnd.nextFloat() - 0.5f) * 3f

    private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
        val out = ArrayList<Float>()
        for (k in 0..60) {
            val t = 2 * PI * k / 60
            out += listOf(cx + r * cos(t).toFloat() + jitter(), cy + r * sin(t).toFloat() + jitter())
        }
        return out.toFloatArray()
    }

    private fun polyline(vararg corners: Float, step: Float = 4f): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until corners.size / 2 - 1) {
            val x0 = corners[i * 2]; val y0 = corners[i * 2 + 1]
            val x1 = corners[i * 2 + 2]; val y1 = corners[i * 2 + 3]
            val n = (kotlin.math.hypot(x1 - x0, y1 - y0) / step).toInt().coerceAtLeast(1)
            for (s in 0 until n) {
                val t = s.toFloat() / n
                out += listOf(x0 + (x1 - x0) * t + jitter(), y0 + (y1 - y0) * t + jitter())
            }
        }
        out += listOf(corners[corners.size - 2], corners[corners.size - 1])
        return out.toFloatArray()
    }

    @Test
    fun circuloViraElipse() {
        val s = ShapeRecognizer.recognize(circle(100f, 100f, 60f))
        assertNotNull(s)
        assertEquals(ShapeRecognizer.Kind.ELLIPSE, s!!.kind)
    }

    @Test
    fun retanguloViraRetangulo() {
        val s = ShapeRecognizer.recognize(polyline(10f, 10f, 210f, 12f, 208f, 110f, 12f, 108f, 11f, 12f))
        assertNotNull(s)
        assertEquals(ShapeRecognizer.Kind.RECTANGLE, s!!.kind)
    }

    @Test
    fun trianguloViraTriangulo() {
        val s = ShapeRecognizer.recognize(polyline(100f, 10f, 180f, 150f, 20f, 150f, 99f, 12f))
        assertNotNull(s)
        assertEquals(ShapeRecognizer.Kind.TRIANGLE, s!!.kind)
    }

    @Test
    fun linhaViraLinhaReta() {
        val s = ShapeRecognizer.recognize(polyline(10f, 50f, 300f, 52f))
        assertNotNull(s)
        assertEquals(ShapeRecognizer.Kind.LINE, s!!.kind)
        // quase horizontal: fica perfeitamente horizontal
        assertEquals(s.points[1], s.points[3], 0.001f)
    }

    @Test
    fun rabiscoQualquerNaoViraForma() {
        // uma letra "S" aberta
        val s = ShapeRecognizer.recognize(polyline(100f, 10f, 20f, 30f, 100f, 80f, 20f, 120f))
        assertNull(s)
    }

    @Test
    fun linhasDeEscritaSaoSeparadas() {
        fun word(x: Float, y: Float) = Stroke(Tool.PEN, 0, 2f, floatArrayOf(x, y, .5f, x + 10, y + 12, .5f, x + 20, y, .5f))
        val strokes = listOf(word(10f, 100f), word(40f, 102f), word(10f, 140f), word(70f, 99f), word(40f, 141f))
        val lines = InkLines.group(strokes)
        assertEquals(2, lines.size)
        assertEquals(3, lines[0].size)
        assertEquals(2, lines[1].size)
    }

    @Test
    fun textosEImagensSaoSalvos() {
        val page = Page(
            "p", PaperTemplate.LINED, 595f, 842f,
            texts = listOf(TextBox("t", 10f, 20f, 200f, "Olá, ação!\nlinha 2", 15f, 0xFF000000.toInt())),
            images = listOf(ImageItem("i", 30f, 40f, 300f, 200f, "abc.jpg")),
        )
        val out = ByteArrayOutputStream()
        NotebookCodec.write(out, listOf(page))
        val back = NotebookCodec.read(ByteArrayInputStream(out.toByteArray()))[0]
        assertEquals(page.texts, back.texts)
        assertEquals(page.images, back.images)
    }

    @Test
    fun redimensionarEDesfazer() {
        val t = TextBox("t", 10f, 10f, 100f, "x", 10f, 0)
        val big = t.scaled(0f, 0f, 2f)
        assertEquals(20f, big.x, 0f)
        assertEquals(200f, big.width, 0f)
        assertEquals(20f, big.size, 0f)
        val page = Page("p", PaperTemplate.BLANK, 595f, 842f, texts = listOf(t))
        val h = History()
        val before = page.content
        page.content = before.copy(texts = listOf(big))
        h.push(ContentEdit(page, before, page.content))
        h.undo()
        assertTrue(page.texts[0] === t)
    }
}
