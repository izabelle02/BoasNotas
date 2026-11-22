package com.boasnotas.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ModelTest {

    /** Traço horizontal de (0,50) até (100,50), um ponto a cada 2 pt. */
    private fun line(width: Float = 2f): Stroke {
        val pts = ArrayList<Float>()
        var x = 0f
        while (x <= 100f) { pts += listOf(x, 50f, 0.5f); x += 2f }
        return Stroke(Tool.PEN, 0xFF000000.toInt(), width, pts.toFloatArray())
    }

    @Test
    fun borrachaLongeNaoTocaNoTraco() {
        val s = line()
        assertFalse(Geometry.strokeTouches(s, 50f, 80f, 60f, 80f, 5f))
        assertNull(Geometry.eraseFromStroke(s, 50f, 80f, 60f, 80f, 5f))
    }

    @Test
    fun borrachaNoMeioDivideOTracoEmDois() {
        val s = line()
        val pieces = Geometry.eraseFromStroke(s, 50f, 40f, 50f, 60f, 5f)
        assertNotNull(pieces)
        assertEquals(2, pieces!!.size)
        // pedaço da esquerda termina antes do corte; o da direita começa depois
        val left = pieces[0]
        val right = pieces[1]
        assertTrue(left.x(left.pointCount - 1) < 45f)
        assertTrue(right.x(0) > 55f)
        assertEquals(0f, left.x(0), 0.001f)
        assertEquals(100f, right.x(right.pointCount - 1), 0.001f)
    }

    @Test
    fun borrachaQueCruzaRapidoUmTrechoLongoTambemCorta() {
        // Só dois pontos bem distantes: a borracha passa no meio, longe dos dois pontos.
        val s = Stroke(Tool.PEN, 0, 2f, floatArrayOf(0f, 0f, 0.5f, 200f, 0f, 0.5f))
        assertTrue(Geometry.strokeTouches(s, 100f, -30f, 100f, 30f, 3f))
        val pieces = Geometry.eraseFromStroke(s, 100f, -30f, 100f, 30f, 3f)!!
        assertEquals(2, pieces.size)
    }

    @Test
    fun borrachaSobreOTracoTodoApagaTudo() {
        val s = line()
        val pieces = Geometry.eraseFromStroke(s, -10f, 50f, 110f, 50f, 5f)
        assertNotNull(pieces)
        assertTrue(pieces!!.isEmpty())
    }

    @Test
    fun lacoSelecionaSoOQueEstaDentro() {
        val inside = line()
        val outside = line().translated(0f, 300f)
        val poly = floatArrayOf(-10f, 0f, 120f, 0f, 120f, 100f, -10f, 100f)
        assertTrue(Geometry.strokeInLasso(inside, poly))
        assertFalse(Geometry.strokeInLasso(outside, poly))
    }

    @Test
    fun salvarELerDevolveOMesmoCaderno() {
        val p1 = Page("a", PaperTemplate.GRID, 595f, 842f, strokes = listOf(line(), line(4f).withColor(123)))
        val p2 = Page("b", PaperTemplate.BLANK, 612f, 792f, pdfPageIndex = 3)
        val out = ByteArrayOutputStream()
        NotebookCodec.write(out, listOf(p1, p2))
        val pages = NotebookCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, pages.size)
        assertEquals("a", pages[0].id)
        assertEquals(PaperTemplate.GRID, pages[0].template)
        assertEquals(2, pages[0].strokes.size)
        assertEquals(123, pages[0].strokes[1].color)
        assertEquals(4f, pages[0].strokes[1].width, 0f)
        assertTrue(p1.strokes[0].points.contentEquals(pages[0].strokes[0].points))
        assertEquals(3, pages[1].pdfPageIndex)
        assertEquals(612f, pages[1].width, 0f)
    }

    @Test
    fun desfazerERefazer() {
        val page = Page("a", PaperTemplate.LINED, 595f, 842f)
        val h = History()
        val s = line()
        page.strokes = listOf(s)
        h.push(StrokesEdit(page, emptyList(), page.strokes))
        assertTrue(h.canUndo)
        h.undo()
        assertTrue(page.strokes.isEmpty())
        assertTrue(h.canRedo)
        h.redo()
        assertEquals(1, page.strokes.size)

        val pages = mutableListOf(page)
        val p2 = Page("b", PaperTemplate.LINED, 595f, 842f)
        pages.add(p2)
        h.push(PageInsertEdit(pages, 1, p2))
        h.undo()
        assertEquals(1, pages.size)
        h.redo()
        assertEquals(2, pages.size)
        // nova alteração apaga o "refazer"
        h.undo()
        h.push(TemplateEdit(page, PaperTemplate.LINED, PaperTemplate.GRID))
        assertFalse(h.canRedo)
    }

    @Test
    fun moverTracoNaoAlteraOOriginal() {
        val s = line()
        val moved = s.translated(10f, 20f)
        assertEquals(0f, s.x(0), 0f)
        assertEquals(10f, moved.x(0), 0f)
        assertEquals(70f, moved.y(0), 0f)
    }
}
