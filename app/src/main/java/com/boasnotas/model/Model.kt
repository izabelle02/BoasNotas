package com.boasnotas.model

/**
 * Modelo de dados dos cadernos. Este pacote não usa nada do Android, para poder ser
 * testado no computador (veja app/src/test).
 *
 * Coordenadas: tudo é guardado em "pontos" da página (1 pt = 1/72 polegada, como no PDF).
 * Uma folha A4 tem 595 x 842 pt.
 */

enum class Tool { PEN, HIGHLIGHTER }

enum class PaperTemplate(val label: String) {
    BLANK("Em branco"),
    LINED("Pautado"),
    GRID("Quadriculado"),
    DOTTED("Pontilhado");

    companion object {
        fun fromOrdinal(i: Int): PaperTemplate = entries.getOrElse(i) { BLANK }
    }
}

/**
 * Um traço. [points] guarda trios (x, y, pressão) e nunca é alterado depois de criado:
 * mover, recolorir ou apagar um pedaço sempre cria traços novos. Isso deixa o "desfazer"
 * e o salvamento em segundo plano simples e seguros.
 */
class Stroke(
    val tool: Tool,
    val color: Int,
    val width: Float,
    val points: FloatArray,
) {
    val pointCount: Int get() = points.size / 3

    fun x(i: Int) = points[i * 3]
    fun y(i: Int) = points[i * 3 + 1]
    fun p(i: Int) = points[i * 3 + 2]

    /** Retângulo que contém o traço (já com a espessura): minX, minY, maxX, maxY. */
    val bounds: FloatArray by lazy {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until pointCount) {
            val x = x(i)
            val y = y(i)
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val pad = width
        floatArrayOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    fun intersects(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val b = bounds
        return b[0] <= right && b[2] >= left && b[1] <= bottom && b[3] >= top
    }

    fun translated(dx: Float, dy: Float): Stroke {
        val out = points.copyOf()
        var i = 0
        while (i < out.size) {
            out[i] += dx
            out[i + 1] += dy
            i += 3
        }
        return Stroke(tool, color, width, out)
    }

    fun withColor(newColor: Int): Stroke = Stroke(tool, newColor, width, points)
}

class Page(
    val id: String,
    var template: PaperTemplate,
    val width: Float,
    val height: Float,
    /** Página do PDF importado usada como fundo, ou -1 se for uma folha comum. */
    val pdfPageIndex: Int = -1,
    /** Lista imutável: cada mudança troca a lista inteira (veja [StrokesEdit]). */
    var strokes: List<Stroke> = emptyList(),
) {
    val isPdf: Boolean get() = pdfPageIndex >= 0

    fun snapshot(): Page = Page(id, template, width, height, pdfPageIndex, strokes)

    /** Cópia com um id novo (para "Duplicar página"). */
    fun duplicate(newId: String): Page = Page(newId, template, width, height, pdfPageIndex, strokes)

    companion object {
        const val A4_WIDTH = 595f
        const val A4_HEIGHT = 842f
    }
}

class Notebook(
    val id: String,
    var title: String,
    var coverColor: Int,
    val created: Long,
    var modified: Long,
    var defaultTemplate: PaperTemplate,
    var hasPdf: Boolean,
    val pages: MutableList<Page>,
)
