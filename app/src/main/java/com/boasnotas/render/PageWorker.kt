package com.boasnotas.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.boasnotas.model.Page
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Uma thread de trabalho para tudo que é pesado: desenhar páginas de PDF, miniaturas e
 * exportar. O PdfRenderer do Android não pode ser usado por duas threads ao mesmo tempo,
 * por isso tudo passa por aqui, em fila.
 */
class PageWorker(pdfFile: File?) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "boasnotas-render") }
    private val main = Handler(Looper.getMainLooper())
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    init {
        if (pdfFile != null && pdfFile.exists()) {
            try {
                fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(fd!!)
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível abrir o PDF", e)
            }
        }
    }

    @Volatile
    private var closed = false

    fun execute(task: () -> Unit) {
        if (closed) return
        executor.execute {
            try {
                task()
            } catch (e: Throwable) {
                Log.e(TAG, "Erro na thread de desenho", e)
            }
        }
    }

    fun onMain(task: () -> Unit) {
        main.post { if (!closed) task() }
    }

    /**
     * Desenha a região [region] (em pontos) da página [pdfIndex] do PDF com escala [scale].
     * Chame só de dentro de [execute].
     */
    fun renderPdfRegion(pdfIndex: Int, region: RectF, scale: Float): Bitmap? {
        val r = renderer ?: return null
        if (pdfIndex !in 0 until r.pageCount) return null
        val w = max(1, ceil(region.width() * scale).toInt())
        val h = max(1, ceil(region.height() * scale).toInt())
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        bmp.eraseColor(Color.WHITE)
        r.openPage(pdfIndex).use { p ->
            val m = Matrix()
            m.setScale(scale, scale)
            m.postTranslate(-region.left * scale, -region.top * scale)
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    /** Pede uma imagem da página do PDF; [callback] roda na thread principal. */
    fun requestPdf(pdfIndex: Int, region: RectF, scale: Float, callback: (Bitmap?) -> Unit) {
        val reg = RectF(region)
        execute {
            val bmp = renderPdfRegion(pdfIndex, reg, scale)
            onMain { callback(bmp) }
        }
    }

    /** Desenha a página inteira (papel, PDF e traços) num bitmap de largura [widthPx]. Só dentro de [execute]. */
    fun renderPage(page: Page, widthPx: Int): Bitmap {
        val scale = widthPx / page.width
        val h = max(1, (page.height * scale).roundToInt())
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        drawPageOffscreen(c, page, scale)
        return bmp
    }

    private fun drawPageOffscreen(c: Canvas, page: Page, pixelScale: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        PaperRenderer.draw(c, page, paint)
        if (page.isPdf) {
            val bmp = renderPdfRegion(page.pdfPageIndex, RectF(0f, 0f, page.width, page.height), pixelScale)
            if (bmp != null) {
                c.drawBitmap(bmp, null, RectF(0f, 0f, page.width, page.height), paint)
                bmp.recycle()
            }
        }
        c.save()
        c.clipRect(0f, 0f, page.width, page.height)
        StrokeRenderer.drawStrokes(c, page.strokes, paint, null)
        c.restore()
    }

    fun requestThumbnail(page: Page, widthPx: Int, callback: (Bitmap) -> Unit) {
        val snap = page.snapshot()
        execute {
            val bmp = renderPage(snap, widthPx)
            onMain { callback(bmp) }
        }
    }

    /** Exporta as páginas para um PDF. Os traços continuam vetoriais (nítidos em qualquer zoom). */
    fun exportPdf(pages: List<Page>, out: File, callback: (Throwable?) -> Unit) {
        val snaps = pages.map { it.snapshot() }
        execute {
            var error: Throwable? = null
            val doc = PdfDocument()
            try {
                snaps.forEachIndexed { i, page ->
                    val info = PdfDocument.PageInfo.Builder(
                        page.width.roundToInt(), page.height.roundToInt(), i + 1,
                    ).create()
                    val p = doc.startPage(info)
                    // Fundo de PDF em 2,5x (180 dpi) para o texto original continuar legível.
                    drawPageOffscreen(p.canvas, page, 2.5f)
                    doc.finishPage(p)
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { doc.writeTo(it) }
            } catch (e: Throwable) {
                error = e
            } finally {
                doc.close()
            }
            onMain { callback(error) }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        executor.execute {
            try {
                renderer?.close()
                fd?.close()
            } catch (_: Exception) {
            }
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "PageWorker"
    }
}
