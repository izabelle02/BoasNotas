package com.boasnotas.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.input.motionprediction.MotionEventPredictor
import com.boasnotas.data.AppSettings
import com.boasnotas.data.NotebookStore
import com.boasnotas.data.PenButtonMode
import com.boasnotas.model.CompositeEdit
import com.boasnotas.model.Edit
import com.boasnotas.model.FloatList
import com.boasnotas.model.Geometry
import com.boasnotas.model.History
import com.boasnotas.model.Notebook
import com.boasnotas.model.Page
import com.boasnotas.model.PageInsertEdit
import com.boasnotas.model.PageRemoveEdit
import com.boasnotas.model.PaperTemplate
import com.boasnotas.model.Stroke
import com.boasnotas.model.StrokesEdit
import com.boasnotas.model.TemplateEdit
import com.boasnotas.model.Tool
import com.boasnotas.render.PageWorker
import com.boasnotas.render.PaperRenderer
import com.boasnotas.render.PenPathBuilder
import com.boasnotas.render.StrokeRenderer
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ToolKind { PEN, HIGHLIGHTER, ERASER, LASSO }

/**
 * A folha onde se escreve. Mostra as páginas uma embaixo da outra (rolagem contínua).
 *
 * Caneta (S Pen): escreve / apaga / seleciona, conforme a ferramenta.
 * Botão lateral da S Pen: vira borracha (segurando ou alternando, veja [PenButtonMode]).
 * Dedos: rolar e dar zoom com pinça. Toque com 2 dedos desfaz, com 3 refaz.
 *
 * O conteúdo fixo (páginas e traços prontos) fica numa camada própria ([content]) guardada
 * pela GPU; o traço que está sendo feito fica por cima ([overlay]). Assim, enquanto você
 * escreve, só a camada de cima é redesenhada e a tinta acompanha a ponta da caneta.
 */
class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onCurrentPageChanged(index: Int, count: Int)
        fun onContentChanged()
        fun onHistoryChanged()
        /** O botão da caneta trocou a ferramenta (modo "alternar"). */
        fun onToolChangedByPen(tool: ToolKind)
        /** O botão da caneta está sendo segurado (modo "segurar"): a borracha está ativa. */
        fun onPenButtonHold(held: Boolean)
        fun onSelectionChanged(hasSelection: Boolean)
    }

    var listener: Listener? = null
    val history = History()

    private lateinit var notebook: Notebook
    private var worker: PageWorker? = null
    private lateinit var settings: AppSettings
    private var startPage = 0

    // ---------- Ferramentas ----------
    var tool: ToolKind = ToolKind.PEN
        set(v) {
            if (field == v) return
            if (v != ToolKind.ERASER) toolBeforeEraser = v
            field = v
            if (v != ToolKind.LASSO) clearSelection()
            overlay.invalidate()
        }
    private var toolBeforeEraser = ToolKind.PEN

    var penColor = Color.BLACK
    var penWidth = 2f
    var highlighterColor = Color.YELLOW
    var highlighterWidth = 14f
    /** Raio da borracha, em pontos da página. */
    var eraserRadius = 8f

    // ---------- Camadas ----------
    private val content = object : View(context) {
        override fun onDraw(canvas: Canvas) = drawContent(canvas)
    }
    private val overlay = object : View(context) {
        override fun onDraw(canvas: Canvas) = drawOverlay(canvas)
    }

    // ---------- Posição das páginas (em pontos, "documento") ----------
    private var pageLeft = FloatArray(0)
    private var pageTop = FloatArray(0)
    private var docWidth = 1f
    private var docHeight = 1f

    // Transformação: tela = documento * zoom + deslocamento
    private var zoom = 1f
    private var offX = 0f
    private var offY = 0f
    private var fitZoom = 1f
    private var laidOut = false
    private var currentPage = -1

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    // ---------- Pintura ----------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val deskColor = Color.rgb(0xE4, 0xE7, 0xEB)
    private val pathCache = StrokeRenderer.PathCache()

    init {
        setWillNotDraw(true)
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setup(nb: Notebook, worker: PageWorker?, settings: AppSettings, startPage: Int) {
        this.notebook = nb
        this.worker = worker
        this.settings = settings
        this.startPage = startPage
        relayout()
    }

    val pageCount get() = notebook.pages.size
    val currentPageIndex get() = max(0, currentPage)

    // ======================================================================
    // Layout e transformação
    // ======================================================================

    private fun relayout() {
        val pages = notebook.pages
        val n = pages.size
        pageLeft = FloatArray(n)
        pageTop = FloatArray(n)
        var maxW = 0f
        for (p in pages) maxW = max(maxW, p.width)
        var y = MARGIN
        for (i in 0 until n) {
            pageLeft[i] = MARGIN + (maxW - pages[i].width) / 2f
            pageTop[i] = y
            y += pages[i].height + GAP
        }
        docWidth = maxW + 2 * MARGIN
        docHeight = y - GAP + MARGIN
        if (width > 0) {
            val oldFit = fitZoom
            fitZoom = width / docWidth
            if (!laidOut) {
                zoom = fitZoom
                laidOut = true
                scrollToPage(startPage.coerceIn(0, max(0, n - 1)))
                return
            } else if (abs(zoom - oldFit) < oldFit * 0.01f) {
                zoom = fitZoom
            }
        }
        clampAndRefresh()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!::notebook.isInitialized) return
        if (!laidOut) {
            relayout()
            return
        }
        // Mantém o mesmo ponto do documento no topo da tela (ex.: ao girar o tablet).
        val docTop = -offY / zoom
        val wasFit = abs(zoom - fitZoom) < fitZoom * 0.01f
        val oldFit = fitZoom
        fitZoom = w / docWidth
        zoom = if (wasFit) fitZoom else zoom * (fitZoom / oldFit)
        offY = -docTop * zoom
        clampAndRefresh()
    }

    private val minZoom get() = fitZoom * 0.6f
    private val maxZoom get() = fitZoom * 8f

    private fun clampOffsets() {
        val cw = docWidth * zoom
        offX = if (cw <= width) (width - cw) / 2f else offX.coerceIn(width - cw, 0f)
        val ch = docHeight * zoom
        offY = if (ch <= height) (height - ch) / 2f else offY.coerceIn(height - ch, 0f)
    }

    private fun clampAndRefresh() {
        clampOffsets()
        content.invalidate()
        overlay.invalidate()
        updateCurrentPage()
        scheduleDetail()
    }

    private fun updateCurrentPage() {
        if (pageTop.isEmpty()) return
        val centerY = (height / 2f - offY) / zoom
        var idx = 0
        for (i in pageTop.indices) if (pageTop[i] - GAP / 2f <= centerY) idx = i
        if (idx != currentPage) {
            currentPage = idx
            listener?.onCurrentPageChanged(idx, pageTop.size)
        }
    }

    fun scrollToPage(index: Int) {
        if (index !in pageTop.indices) return
        stopFling()
        offY = -(pageTop[index] - MARGIN / 2f) * zoom
        clampAndRefresh()
        if (currentPage != index && height > 0) {
            // garante o aviso mesmo quando a página é menor que a tela
            currentPage = index
            listener?.onCurrentPageChanged(index, pageTop.size)
        }
    }

    private fun toDocX(sx: Float) = (sx - offX) / zoom
    private fun toDocY(sy: Float) = (sy - offY) / zoom

    private fun pageAtDoc(dx: Float, dy: Float, tolerance: Float = 0f): Int {
        val pages = notebook.pages
        for (i in pages.indices) {
            val p = pages[i]
            if (dx >= pageLeft[i] - tolerance && dx <= pageLeft[i] + p.width + tolerance &&
                dy >= pageTop[i] - tolerance && dy <= pageTop[i] + p.height + tolerance
            ) return i
        }
        return -1
    }

    // ======================================================================
    // Desenho
    // ======================================================================

    private val visibleTmp = FloatArray(4)

    private fun drawContent(canvas: Canvas) {
        canvas.drawColor(deskColor)
        if (!::notebook.isInitialized) return
        val pages = notebook.pages
        val viewTop = toDocY(0f)
        val viewBottom = toDocY(height.toFloat())
        val viewLeft = toDocX(0f)
        val viewRight = toDocX(width.toFloat())
        var visibleCount = 0
        val skip: Set<Stroke>? = selection?.set
        for (i in pages.indices) {
            val page = pages[i]
            val top = pageTop[i]
            if (top > viewBottom || top + page.height < viewTop) continue
            visibleCount++
            canvas.save()
            canvas.translate(offX + pageLeft[i] * zoom, offY + top * zoom)
            canvas.scale(zoom, zoom)
            val s = 1.5f / zoom
            canvas.drawRect(-s, -s * 0.3f, page.width + s, page.height + s * 2.5f, shadowPaint)
            canvas.clipRect(0f, 0f, page.width, page.height)
            PaperRenderer.draw(canvas, page, paint)
            if (page.isPdf) drawPdfBackground(canvas, page)
            visibleTmp[0] = viewLeft - pageLeft[i]
            visibleTmp[1] = viewTop - top
            visibleTmp[2] = viewRight - pageLeft[i]
            visibleTmp[3] = viewBottom - top
            StrokeRenderer.drawStrokes(
                canvas, page.strokes, paint, pathCache, visibleTmp,
                if (selection?.page === page) skip else null,
            )
            canvas.restore()
        }
        maxPdfCache = max(4, visibleCount + 2)
    }

    // ---- Fundo de PDF: uma imagem da página inteira + um "recorte" nítido quando há zoom ----
    private var maxPdfCache = 4
    private val pdfBase = object : LinkedHashMap<Int, PdfImage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfImage>?) = size > maxPdfCache
    }
    private val pdfPending = HashSet<Int>()

    private class PdfImage(val bitmap: Bitmap, val scale: Float)
    private class PdfTile(val page: Page, val region: RectF, val bitmap: Bitmap)

    private var tile: PdfTile? = null
    private var tileGeneration = 0
    private val tileRect = RectF()

    private fun baseScaleFor(page: Page) = min(fitZoom, MAX_BASE_PX / page.width)

    private fun drawPdfBackground(canvas: Canvas, page: Page) {
        val key = page.pdfPageIndex
        val wanted = baseScaleFor(page)
        val img = pdfBase[key]
        if (img != null) {
            tileRect.set(0f, 0f, page.width, page.height)
            canvas.drawBitmap(img.bitmap, null, tileRect, bitmapPaint)
        }
        if ((img == null || img.scale < wanted * 0.8f) && key !in pdfPending) {
            val w = worker ?: return
            pdfPending.add(key)
            w.requestPdf(key, RectF(0f, 0f, page.width, page.height), wanted) { bmp ->
                pdfPending.remove(key)
                if (bmp != null) {
                    pdfBase[key] = PdfImage(bmp, wanted)
                    content.invalidate()
                }
            }
        }
        val t = tile
        if (t != null && t.page === page) canvas.drawBitmap(t.bitmap, null, t.region, bitmapPaint)
    }

    private val detailRunnable = Runnable { requestDetailTile() }

    private fun scheduleDetail() {
        if (worker == null || !notebook.hasPdf) return
        removeCallbacks(detailRunnable)
        postDelayed(detailRunnable, 200)
    }

    private fun requestDetailTile() {
        val w = worker ?: return
        val pages = notebook.pages
        // Escolhe a página de PDF que mais aparece na tela.
        var best = -1
        var bestArea = 0f
        val vl = toDocX(0f); val vt = toDocY(0f); val vr = toDocX(width.toFloat()); val vb = toDocY(height.toFloat())
        for (i in pages.indices) {
            val p = pages[i]
            if (!p.isPdf) continue
            val l = max(vl, pageLeft[i]); val r = min(vr, pageLeft[i] + p.width)
            val t = max(vt, pageTop[i]); val b = min(vb, pageTop[i] + p.height)
            if (r <= l || b <= t) continue
            val area = (r - l) * (b - t)
            if (area > bestArea) { bestArea = area; best = i }
        }
        if (best < 0) return
        val page = pages[best]
        if (zoom <= baseScaleFor(page) * 1.15f) {
            if (tile != null) { tile = null; content.invalidate() }
            return
        }
        val region = RectF(
            max(vl, pageLeft[best]) - pageLeft[best], max(vt, pageTop[best]) - pageTop[best],
            min(vr, pageLeft[best] + page.width) - pageLeft[best], min(vb, pageTop[best] + page.height) - pageTop[best],
        )
        val gen = ++tileGeneration
        val scale = zoom
        w.requestPdf(page.pdfPageIndex, region, scale) { bmp ->
            if (bmp != null && gen == tileGeneration) {
                tile = PdfTile(page, region, bmp)
                content.invalidate()
            }
        }
    }

    // ---- Camada de cima: traço em andamento, laço, seleção e cursor da borracha ----
    private val tailPath = Path()
    private val livePath = Path()
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99555555.toInt()
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF3F51B5.toInt()
    }
    private val selectionFill = Paint().apply { color = 0x143F51B5 }

    private fun drawOverlay(canvas: Canvas) {
        if (!::notebook.isInitialized) return
        // Traço em andamento
        if (mode == Mode.DRAW && livePage in notebook.pages.indices) {
            withPage(canvas, livePage) {
                val tool = liveTool
                if (tool == Tool.PEN) {
                    StrokeRenderer.preparePaint(overlayPaint, Tool.PEN, liveColor, liveWidth)
                    canvas.drawPath(penBuilder.path, overlayPaint)
                    penBuilder.buildTail(tailPath, if (predicted.size > 0) predicted else null)
                    canvas.drawPath(tailPath, overlayPaint)
                } else {
                    StrokeRenderer.preparePaint(overlayPaint, Tool.HIGHLIGHTER, liveColor, liveWidth)
                    StrokeRenderer.buildHighlighterPath(livePoints.toArray(), livePoints.size / 3, livePath)
                    canvas.drawPath(livePath, overlayPaint)
                }
            }
        }
        // Laço sendo desenhado
        if (mode == Mode.LASSO && lassoPoints.size >= 4 && lassoPage in notebook.pages.indices) {
            withPage(canvas, lassoPage, clip = false) {
                livePath.rewind()
                livePath.moveTo(lassoPoints[0], lassoPoints[1])
                var i = 2
                while (i + 1 < lassoPoints.size) { livePath.lineTo(lassoPoints[i], lassoPoints[i + 1]); i += 2 }
                lassoPaint.strokeWidth = 1.5f * density / zoom
                lassoPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / zoom, 4f * density / zoom), 0f)
                canvas.drawPath(livePath, lassoPaint)
            }
        }
        // Seleção
        val sel = selection
        if (sel != null) {
            val idx = notebook.pages.indexOf(sel.page)
            if (idx >= 0) withPage(canvas, idx, clip = false) {
                canvas.save()
                canvas.translate(dragDx, dragDy)
                StrokeRenderer.drawStrokes(canvas, sel.strokes, overlayPaint, pathCache)
                val b = sel.bounds
                val pad = 6f / zoom * density
                tileRect.set(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
                canvas.drawRect(tileRect, selectionFill)
                lassoPaint.strokeWidth = 1.5f * density / zoom
                lassoPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / zoom, 4f * density / zoom), 0f)
                canvas.drawRect(tileRect, lassoPaint)
                canvas.restore()
            }
        }
        // Cursor da borracha
        if (cursorVisible) {
            val r = eraserRadius * zoom
            cursorPaint.strokeWidth = 1.5f * density
            canvas.drawCircle(cursorX, cursorY, r, cursorFill)
            canvas.drawCircle(cursorX, cursorY, r, cursorPaint)
        }
    }

    private inline fun withPage(canvas: Canvas, index: Int, clip: Boolean = true, block: () -> Unit) {
        val page = notebook.pages[index]
        canvas.save()
        canvas.translate(offX + pageLeft[index] * zoom, offY + pageTop[index] * zoom)
        canvas.scale(zoom, zoom)
        if (clip) canvas.clipRect(0f, 0f, page.width, page.height)
        block()
        canvas.restore()
    }

    // ======================================================================
    // Toque
    // ======================================================================

    private enum class Mode { NONE, DRAW, ERASE, LASSO, MOVE_SELECTION, NAVIGATE, IGNORE }

    private var mode = Mode.NONE
    private var activeId = -1
    private var activeIsStylus = false
    private var activeEraserTip = false
    private var gestureDownTime = 0L
    private var lastStylusTime = 0L
    private var stylusHovering = false
    private var penButtonDown = false

    private var cursorVisible = false
    private var cursorX = 0f
    private var cursorY = 0f

    private val predictor: MotionEventPredictor = MotionEventPredictor.newInstance(this)
    private val predicted = FloatList(64)

    private fun isStylus(ev: MotionEvent, idx: Int): Boolean {
        val t = ev.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /** Botão lateral apertado? Inclui o jeito antigo da Samsung (ações 211–214). */
    private fun buttonPressed(ev: MotionEvent): Boolean {
        val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or
            MotionEvent.BUTTON_SECONDARY
        return (ev.buttonState and mask) != 0 || ev.actionMasked in SAMSUNG_SPEN_ACTIONS
    }

    private fun normalizedAction(ev: MotionEvent): Int = when (ev.actionMasked) {
        SAMSUNG_SPEN_DOWN -> MotionEvent.ACTION_DOWN
        SAMSUNG_SPEN_UP -> MotionEvent.ACTION_UP
        SAMSUNG_SPEN_MOVE -> MotionEvent.ACTION_MOVE
        SAMSUNG_SPEN_CANCEL -> MotionEvent.ACTION_CANCEL
        else -> ev.actionMasked
    }

    /** Ferramenta que vale agora, levando em conta o botão e a ponta-borracha. */
    private fun effectiveTool(): ToolKind = when {
        activeEraserTip -> ToolKind.ERASER
        penButtonDown && settings.penButtonMode == PenButtonMode.HOLD -> ToolKind.ERASER
        else -> tool
    }

    /** Atualiza o estado do botão. Devolve true se a ferramenta efetiva mudou. */
    private fun updatePenButton(pressed: Boolean): Boolean {
        if (pressed == penButtonDown) return false
        val before = effectiveTool()
        penButtonDown = pressed
        when (settings.penButtonMode) {
            PenButtonMode.TOGGLE -> if (pressed) {
                val newTool = if (tool == ToolKind.ERASER) toolBeforeEraser else ToolKind.ERASER
                tool = newTool
                listener?.onToolChangedByPen(newTool)
            }
            PenButtonMode.HOLD -> listener?.onPenButtonHold(pressed)
            PenButtonMode.OFF -> Unit
        }
        overlay.invalidate()
        return effectiveTool() != before
    }

    val isPenButtonHeld get() = penButtonDown && settings.penButtonMode == PenButtonMode.HOLD

    override fun onInterceptTouchEvent(ev: MotionEvent) = true
    override fun onInterceptHoverEvent(event: MotionEvent) = true

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        if (!::notebook.isInitialized) return true
        if (!isStylus(ev, 0)) return super.onHoverEvent(ev)
        lastStylusTime = SystemClock.uptimeMillis()
        activeEraserTip = ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        updatePenButton(buttonPressed(ev))
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                stylusHovering = true
                cursorX = ev.x
                cursorY = ev.y
                setCursor(effectiveTool() == ToolKind.ERASER)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                stylusHovering = false
                if (mode != Mode.ERASE) setCursor(false)
            }
        }
        return true
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!::notebook.isInitialized) return super.onGenericMotionEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> if (isStylus(ev, 0)) {
                lastStylusTime = SystemClock.uptimeMillis()
                updatePenButton(buttonPressed(ev))
                if (stylusHovering) setCursor(effectiveTool() == ToolKind.ERASER)
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                // Roda do mouse / teclado com touchpad (ex.: Samsung DeX)
                stopFling()
                offY += ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * 80f * density
                offX -= ev.getAxisValue(MotionEvent.AXIS_HSCROLL) * 80f * density
                clampAndRefresh()
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun setCursor(visible: Boolean) {
        if (cursorVisible != visible || visible) {
            cursorVisible = visible
            overlay.invalidate()
        }
    }

    private val drawingModes = setOf(Mode.DRAW, Mode.ERASE, Mode.LASSO, Mode.MOVE_SELECTION)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!::notebook.isInitialized) return false
        val action = normalizedAction(ev)
        val stylusEvent = ev.pointerCount > 0 && isStylus(ev, ev.actionIndex.coerceIn(0, ev.pointerCount - 1))
        if (stylusEvent) {
            lastStylusTime = SystemClock.uptimeMillis()
            if (ev.pointerCount == 1 && ev.actionMasked !in SAMSUNG_SPEN_ACTIONS) predictor.record(ev)
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = if (action == MotionEvent.ACTION_DOWN) 0 else ev.actionIndex
                onPointerDown(ev, idx)
            }
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(ev, ev.actionIndex, last = false)
            MotionEvent.ACTION_UP -> onPointerUp(ev, 0.coerceAtLeast(ev.actionIndex), last = true)
            MotionEvent.ACTION_CANCEL -> {
                if (mode in drawingModes) cancelGesture()
                if (mode == Mode.NAVIGATE) velocity?.clear()
                mode = Mode.NONE
                setCursor(false)
            }
        }
        return true
    }

    private fun onPointerDown(ev: MotionEvent, idx: Int) {
        val stylus = isStylus(ev, idx)
        val now = SystemClock.uptimeMillis()
        if (stylus) {
            if (mode in drawingModes && activeIsStylus) return
            if (mode in drawingModes) cancelGesture()
            if (mode == Mode.NAVIGATE) endNavigation(fling = false)
            requestUnbufferedDispatch(ev)
            beginGesture(ev, idx, stylus = true)
            return
        }
        // Dedo
        when (mode) {
            Mode.IGNORE -> return
            Mode.NAVIGATE -> { navReset(ev, -1); navMaxPointers = max(navMaxPointers, fingerCount(ev, -1)); return }
            Mode.DRAW, Mode.ERASE, Mode.LASSO, Mode.MOVE_SELECTION -> {
                if (activeIsStylus) return // palma da mão encostando enquanto escreve
                // Segundo dedo: era para rolar/dar zoom, não para desenhar.
                val shortStroke = now - gestureDownTime < 300
                if (shortStroke) cancelGesture() else finishGesture()
                startNavigation(ev, now = gestureDownTime)
                return
            }
            Mode.NONE -> Unit
        }
        // A caneta acabou de ser usada ou está pairando: é a palma, ignora.
        if (stylusHovering || now - lastStylusTime < PALM_GUARD_MS) {
            mode = Mode.IGNORE
            return
        }
        val sel = selection
        if (sel != null && selectionHit(ev.getX(idx), ev.getY(idx))) {
            activeId = ev.getPointerId(idx)
            activeIsStylus = false
            activeEraserTip = false
            gestureDownTime = now
            startMoveSelection(ev.getX(idx), ev.getY(idx))
            return
        }
        if (settings.fingerDraws && ev.pointerCount == 1) {
            beginGesture(ev, idx, stylus = false)
        } else {
            startNavigation(ev, now)
        }
    }

    private fun beginGesture(ev: MotionEvent, idx: Int, stylus: Boolean) {
        activeId = ev.getPointerId(idx)
        activeIsStylus = stylus
        activeEraserTip = stylus && ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
        gestureDownTime = SystemClock.uptimeMillis()
        if (stylus) updatePenButton(buttonPressed(ev))
        startTool(effectiveTool(), ev.getX(idx), ev.getY(idx), ev.getPressure(idx))
    }

    private fun startTool(kind: ToolKind, sx: Float, sy: Float, pressure: Float) {
        when (kind) {
            ToolKind.PEN, ToolKind.HIGHLIGHTER -> startStroke(kind, sx, sy, pressure)
            ToolKind.ERASER -> startErase(sx, sy)
            ToolKind.LASSO -> if (selection != null && selectionHit(sx, sy)) startMoveSelection(sx, sy) else startLasso(sx, sy)
        }
    }

    private fun onMove(ev: MotionEvent) {
        when (mode) {
            Mode.NAVIGATE -> navMove(ev)
            Mode.DRAW, Mode.ERASE, Mode.LASSO, Mode.MOVE_SELECTION -> {
                val idx = ev.findPointerIndex(activeId)
                if (idx < 0) return
                if (activeIsStylus && updatePenButton(buttonPressed(ev))) {
                    // O botão foi apertado/solto no meio do traço: termina e começa com a outra ferramenta.
                    finishGesture()
                    startTool(effectiveTool(), ev.getX(idx), ev.getY(idx), ev.getPressure(idx))
                    return
                }
                for (h in 0 until ev.historySize) {
                    gesturePoint(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h), ev.getHistoricalPressure(idx, h))
                }
                gesturePoint(ev.getX(idx), ev.getY(idx), ev.getPressure(idx))
                if (mode == Mode.DRAW && activeIsStylus && liveTool == Tool.PEN) updatePrediction(idx)
                overlay.invalidate()
            }
            else -> Unit
        }
    }

    private fun onPointerUp(ev: MotionEvent, idx: Int, last: Boolean) {
        val id = ev.getPointerId(idx)
        if (mode in drawingModes && id == activeId) {
            val canceled = (ev.flags and MotionEvent.FLAG_CANCELED) != 0
            if (canceled) cancelGesture() else {
                gesturePoint(ev.getX(idx), ev.getY(idx), ev.getPressure(idx))
                finishGesture()
            }
            mode = if (last) Mode.NONE else Mode.IGNORE
            if (!stylusHovering) setCursor(false)
        } else if (mode == Mode.NAVIGATE) {
            if (last) endNavigation(fling = true) else navReset(ev, idx)
        }
        if (last) {
            mode = Mode.NONE
            activeId = -1
        }
    }

    private fun gesturePoint(sx: Float, sy: Float, pressure: Float) {
        when (mode) {
            Mode.DRAW -> addStrokePoint(sx, sy, pressure)
            Mode.ERASE -> eraseTo(sx, sy)
            Mode.LASSO -> addLassoPoint(sx, sy)
            Mode.MOVE_SELECTION -> moveSelectionTo(sx, sy)
            else -> Unit
        }
    }

    private fun finishGesture() {
        when (mode) {
            Mode.DRAW -> endStroke()
            Mode.ERASE -> endErase()
            Mode.LASSO -> endLasso()
            Mode.MOVE_SELECTION -> endMoveSelection()
            else -> Unit
        }
        mode = Mode.NONE
    }

    private fun cancelGesture() {
        when (mode) {
            Mode.DRAW -> { livePoints.clear(); predicted.clear() }
            Mode.ERASE -> endErase() // o que já foi apagado fica (dá para desfazer)
            Mode.LASSO -> lassoPoints.clear()
            Mode.MOVE_SELECTION -> { dragDx = 0f; dragDy = 0f }
            else -> Unit
        }
        mode = Mode.NONE
        overlay.invalidate()
    }

    // ---------- Caneta e marca-texto ----------
    private var livePage = -1
    private var liveTool = Tool.PEN
    private var liveColor = Color.BLACK
    private var liveWidth = 2f
    private val livePoints = FloatList(512)
    private val penBuilder = PenPathBuilder()

    private fun startStroke(kind: ToolKind, sx: Float, sy: Float, pressure: Float) {
        val pi = pageAtDoc(toDocX(sx), toDocY(sy))
        if (pi < 0) {
            mode = Mode.IGNORE
            return
        }
        mode = Mode.DRAW
        livePage = pi
        liveTool = if (kind == ToolKind.PEN) Tool.PEN else Tool.HIGHLIGHTER
        liveColor = if (kind == ToolKind.PEN) penColor else highlighterColor
        liveWidth = if (kind == ToolKind.PEN) penWidth else highlighterWidth
        livePoints.clear()
        predicted.clear()
        penBuilder.reset(liveWidth)
        addStrokePoint(sx, sy, pressure)
        overlay.invalidate()
    }

    private fun addStrokePoint(sx: Float, sy: Float, rawPressure: Float) {
        val px = toDocX(sx) - pageLeft[livePage]
        val py = toDocY(sy) - pageTop[livePage]
        val n = livePoints.size
        if (n >= 3) {
            val lx = livePoints[n - 3]
            val ly = livePoints[n - 2]
            if (hypot(px - lx, py - ly) < 0.5f / zoom) return
        }
        val pressure = if (activeIsStylus && settings.pressureEnabled) rawPressure.coerceIn(0f, 1f) else 0.5f
        livePoints.add(px, py, pressure)
        if (liveTool == Tool.PEN) penBuilder.add(px, py, pressure)
    }

    private fun updatePrediction(idx: Int) {
        predicted.clear()
        val p = predictor.predict() ?: return
        try {
            val i = if (p.pointerCount > idx) idx else 0
            val pressure = if (settings.pressureEnabled) p.getPressure(i).coerceIn(0f, 1f) else 0.5f
            for (h in 0 until p.historySize) {
                predicted.add(
                    toDocX(p.getHistoricalX(i, h)) - pageLeft[livePage],
                    toDocY(p.getHistoricalY(i, h)) - pageTop[livePage],
                    pressure,
                )
            }
            predicted.add(toDocX(p.getX(i)) - pageLeft[livePage], toDocY(p.getY(i)) - pageTop[livePage], pressure)
        } finally {
            p.recycle()
        }
    }

    private fun endStroke() {
        predicted.clear()
        if (livePoints.size < 3 || livePage !in notebook.pages.indices) {
            livePoints.clear()
            return
        }
        val stroke = Stroke(liveTool, liveColor, liveWidth, livePoints.toArray())
        if (liveTool == Tool.PEN) {
            penBuilder.finish()
            pathCache.put(stroke, Path(penBuilder.path))
        }
        livePoints.clear()
        val page = notebook.pages[livePage]
        val before = page.strokes
        val after = before + stroke
        page.strokes = after
        pushEdit(StrokesEdit(page, before, after))
        content.invalidate()
        overlay.invalidate()
    }

    // ---------- Borracha ----------
    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private val eraseBefore = IdentityHashMap<Page, List<Stroke>>()

    private fun startErase(sx: Float, sy: Float) {
        mode = Mode.ERASE
        eraseBefore.clear()
        eraseLastX = toDocX(sx)
        eraseLastY = toDocY(sy)
        cursorX = sx; cursorY = sy
        setCursor(true)
        eraseSegment(eraseLastX, eraseLastY, eraseLastX, eraseLastY)
    }

    private fun eraseTo(sx: Float, sy: Float) {
        val dx = toDocX(sx)
        val dy = toDocY(sy)
        cursorX = sx; cursorY = sy
        if (hypot(dx - eraseLastX, dy - eraseLastY) < 0.5f / zoom) return
        eraseSegment(eraseLastX, eraseLastY, dx, dy)
        eraseLastX = dx
        eraseLastY = dy
    }

    private fun eraseSegment(ax: Float, ay: Float, bx: Float, by: Float) {
        val r = eraserRadius
        val whole = settings.eraseWholeStroke
        val pages = notebook.pages
        for (i in pages.indices) {
            val page = pages[i]
            val l = pageLeft[i]; val t = pageTop[i]
            if (max(ax, bx) + r < l || min(ax, bx) - r > l + page.width ||
                max(ay, by) + r < t || min(ay, by) - r > t + page.height
            ) continue
            val x0 = ax - l; val y0 = ay - t; val x1 = bx - l; val y1 = by - t
            val strokes = page.strokes
            var out: ArrayList<Stroke>? = null
            for ((k, s) in strokes.withIndex()) {
                val result: List<Stroke>? = if (whole) {
                    if (Geometry.strokeTouches(s, x0, y0, x1, y1, r)) emptyList() else null
                } else {
                    Geometry.eraseFromStroke(s, x0, y0, x1, y1, r)
                }
                if (result != null) {
                    if (out == null) out = ArrayList<Stroke>(strokes.size).apply { addAll(strokes.subList(0, k)) }
                    out.addAll(result)
                } else {
                    out?.add(s)
                }
            }
            if (out != null) {
                if (!eraseBefore.containsKey(page)) eraseBefore[page] = strokes
                page.strokes = out
                content.invalidate()
            }
        }
    }

    private fun endErase() {
        if (eraseBefore.isNotEmpty()) {
            val edits = eraseBefore.map { (page, before) -> StrokesEdit(page, before, page.strokes) as Edit }
            pushEdit(if (edits.size == 1) edits[0] else CompositeEdit(edits))
            eraseBefore.clear()
        }
        if (!stylusHovering) setCursor(false)
    }

    // ---------- Laço (selecionar, mover, apagar, recolorir) ----------
    private class Selection(val page: Page, val strokes: List<Stroke>) {
        val set: Set<Stroke> = java.util.Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>()).apply { addAll(strokes) }
        val bounds: FloatArray = Geometry.unionBounds(strokes)!!
    }

    private var selection: Selection? = null
    private var lassoPage = -1
    private val lassoPoints = FloatList(256)
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragDx = 0f
    private var dragDy = 0f

    val hasSelection get() = selection != null

    private fun selectionHit(sx: Float, sy: Float): Boolean {
        val sel = selection ?: return false
        val idx = notebook.pages.indexOf(sel.page)
        if (idx < 0) return false
        val px = toDocX(sx) - pageLeft[idx]
        val py = toDocY(sy) - pageTop[idx]
        val pad = 14f * density / zoom
        val b = sel.bounds
        return px >= b[0] - pad && px <= b[2] + pad && py >= b[1] - pad && py <= b[3] + pad
    }

    private fun startLasso(sx: Float, sy: Float) {
        clearSelection()
        val pi = pageAtDoc(toDocX(sx), toDocY(sy), tolerance = 20f)
        if (pi < 0) {
            mode = Mode.IGNORE
            return
        }
        mode = Mode.LASSO
        lassoPage = pi
        lassoPoints.clear()
        addLassoPoint(sx, sy)
    }

    private fun addLassoPoint(sx: Float, sy: Float) {
        val px = toDocX(sx) - pageLeft[lassoPage]
        val py = toDocY(sy) - pageTop[lassoPage]
        val n = lassoPoints.size
        if (n >= 2 && hypot(px - lassoPoints[n - 2], py - lassoPoints[n - 1]) < 2f / zoom) return
        lassoPoints.add(px, py)
    }

    private fun endLasso() {
        val poly = lassoPoints.toArray()
        lassoPoints.clear()
        if (lassoPage !in notebook.pages.indices || poly.size < 6) {
            overlay.invalidate()
            return
        }
        val page = notebook.pages[lassoPage]
        val chosen = page.strokes.filter { Geometry.strokeInLasso(it, poly) }
        setSelection(if (chosen.isEmpty()) null else Selection(page, chosen))
    }

    private fun setSelection(sel: Selection?) {
        val had = selection != null
        selection = sel
        dragDx = 0f
        dragDy = 0f
        content.invalidate()
        overlay.invalidate()
        if (had || sel != null) listener?.onSelectionChanged(sel != null)
    }

    fun clearSelection() {
        if (selection != null) setSelection(null)
    }

    private fun startMoveSelection(sx: Float, sy: Float) {
        mode = Mode.MOVE_SELECTION
        dragStartX = toDocX(sx)
        dragStartY = toDocY(sy)
        dragDx = 0f
        dragDy = 0f
    }

    private fun moveSelectionTo(sx: Float, sy: Float) {
        dragDx = toDocX(sx) - dragStartX
        dragDy = toDocY(sy) - dragStartY
    }

    private fun endMoveSelection() {
        val dx = dragDx
        val dy = dragDy
        dragDx = 0f
        dragDy = 0f
        if (abs(dx) + abs(dy) < 0.5f) {
            overlay.invalidate()
            return
        }
        changeSelection { it.translated(dx, dy) }
    }

    /** Troca cada traço selecionado por [transform] (ou remove, se devolver null). */
    private fun changeSelection(transform: (Stroke) -> Stroke?) {
        val sel = selection ?: return
        val page = sel.page
        val before = page.strokes
        val newSel = ArrayList<Stroke>()
        val after = before.mapNotNull { s ->
            if (sel.set.contains(s)) transform(s)?.also { newSel.add(it) } else s
        }
        page.strokes = after
        pushEdit(StrokesEdit(page, before, after))
        setSelection(if (newSel.isEmpty()) null else Selection(page, newSel))
    }

    fun deleteSelection() = changeSelection { null }

    fun recolorSelection(color: Int) = changeSelection { it.withColor(color) }

    fun duplicateSelection() {
        val sel = selection ?: return
        val page = sel.page
        val copies = sel.strokes.map { it.translated(20f, 20f) }
        val before = page.strokes
        val after = before + copies
        page.strokes = after
        pushEdit(StrokesEdit(page, before, after))
        setSelection(Selection(page, copies))
    }

    // ---------- Navegação com os dedos (rolar, pinça, toque com 2/3 dedos) ----------
    private var navLastX = 0f
    private var navLastY = 0f
    private var navLastSpan = 0f
    private var navPointers = 0
    private var navMaxPointers = 0
    private var navStartTime = 0L
    private var navTravel = 0f
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)

    private fun fingerCount(ev: MotionEvent, skip: Int): Int {
        var c = 0
        for (i in 0 until ev.pointerCount) if (i != skip && !isStylus(ev, i)) c++
        return c
    }

    private fun startNavigation(ev: MotionEvent, now: Long) {
        stopFling()
        mode = Mode.NAVIGATE
        navStartTime = now
        navTravel = 0f
        navMaxPointers = fingerCount(ev, -1)
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
        navReset(ev, -1)
    }

    private fun navReset(ev: MotionEvent, skip: Int) {
        var sx = 0f; var sy = 0f; var c = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip || isStylus(ev, i)) continue
            sx += ev.getX(i); sy += ev.getY(i); c++
        }
        navPointers = c
        if (c == 0) return
        navLastX = sx / c
        navLastY = sy / c
        var span = 0f
        for (i in 0 until ev.pointerCount) {
            if (i == skip || isStylus(ev, i)) continue
            span += hypot(ev.getX(i) - navLastX, ev.getY(i) - navLastY)
        }
        navLastSpan = span / c
    }

    private fun navMove(ev: MotionEvent) {
        velocity?.addMovement(ev)
        var sx = 0f; var sy = 0f; var c = 0
        for (i in 0 until ev.pointerCount) {
            if (isStylus(ev, i)) continue
            sx += ev.getX(i); sy += ev.getY(i); c++
        }
        if (c == 0) return
        if (c != navPointers) { navReset(ev, -1); return }
        val fx = sx / c
        val fy = sy / c
        var span = 0f
        for (i in 0 until ev.pointerCount) {
            if (isStylus(ev, i)) continue
            span += hypot(ev.getX(i) - fx, ev.getY(i) - fy)
        }
        span /= c
        navMaxPointers = max(navMaxPointers, c)
        var scale = 1f
        if (c >= 2 && navLastSpan > 10f) scale = span / navLastSpan
        navTravel += abs(fx - navLastX) + abs(fy - navLastY) + abs(span - navLastSpan)
        if (navTravel > touchSlop || c == 1) {
            val newZoom = (zoom * scale).coerceIn(minZoom, maxZoom)
            val k = newZoom / zoom
            offX = fx - (navLastX - offX) * k
            offY = fy - (navLastY - offY) * k
            zoom = newZoom
            clampAndRefresh()
        }
        navLastX = fx
        navLastY = fy
        navLastSpan = span
    }

    private fun endNavigation(fling: Boolean) {
        val now = SystemClock.uptimeMillis()
        val tap = navTravel < touchSlop * 2 && now - navStartTime < 300
        if (fling && tap && navMaxPointers >= 2 && settings.tapGestures) {
            if (navMaxPointers == 2) undo() else redo()
        } else if (fling && navMaxPointers == 1) {
            velocity?.let { vt ->
                vt.computeCurrentVelocity(1000, maxFling.toFloat())
                val vx = vt.xVelocity
                val vy = vt.yVelocity
                if (abs(vx) > minFling || abs(vy) > minFling) startFling(vx, vy)
            }
        }
        velocity?.recycle()
        velocity = null
        mode = Mode.NONE
    }

    private fun startFling(vx: Float, vy: Float) {
        val cw = docWidth * zoom
        val ch = docHeight * zoom
        val minX = if (cw > width) (width - cw).toInt() else offX.toInt()
        val maxX = if (cw > width) 0 else offX.toInt()
        val minY = if (ch > height) (height - ch).toInt() else offY.toInt()
        val maxY = if (ch > height) 0 else offY.toInt()
        scroller.fling(offX.toInt(), offY.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
        postOnAnimation(flingRunnable)
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                offX = scroller.currX.toFloat()
                offY = scroller.currY.toFloat()
                clampAndRefresh()
                postOnAnimation(this)
            }
        }
    }

    private fun stopFling() {
        scroller.forceFinished(true)
        removeCallbacks(flingRunnable)
    }

    // ======================================================================
    // Desfazer / páginas
    // ======================================================================

    private fun pushEdit(e: Edit) {
        history.push(e)
        listener?.onHistoryChanged()
        listener?.onContentChanged()
    }

    fun undo() {
        if (mode in drawingModes) return
        clearSelection()
        val e = history.undo() ?: return
        afterHistoryChange(e)
    }

    fun redo() {
        if (mode in drawingModes) return
        clearSelection()
        val e = history.redo() ?: return
        afterHistoryChange(e)
    }

    private fun afterHistoryChange(e: Edit) {
        if (e.changesPages) relayout()
        content.invalidate()
        listener?.onHistoryChanged()
        listener?.onContentChanged()
    }

    fun addPage(afterIndex: Int, template: PaperTemplate): Int {
        clearSelection()
        val page = NotebookStore.newPage(template)
        val at = (afterIndex + 1).coerceIn(0, notebook.pages.size)
        notebook.pages.add(at, page)
        relayout()
        pushEdit(PageInsertEdit(notebook.pages, at, page))
        scrollToPage(at)
        return at
    }

    fun duplicatePage(index: Int) {
        if (index !in notebook.pages.indices) return
        clearSelection()
        val page = notebook.pages[index].duplicate(NotebookStore.newId())
        notebook.pages.add(index + 1, page)
        relayout()
        pushEdit(PageInsertEdit(notebook.pages, index + 1, page))
        scrollToPage(index + 1)
    }

    /** Apaga a página (precisa sobrar pelo menos uma). */
    fun deletePage(index: Int): Boolean {
        if (notebook.pages.size <= 1 || index !in notebook.pages.indices) return false
        clearSelection()
        val page = notebook.pages.removeAt(index)
        if (tile?.page === page) tile = null
        relayout()
        pushEdit(PageRemoveEdit(notebook.pages, index, page))
        return true
    }

    fun setTemplate(index: Int, template: PaperTemplate) {
        val page = notebook.pages.getOrNull(index) ?: return
        if (page.template == template) return
        val before = page.template
        page.template = template
        pushEdit(TemplateEdit(page, before, template))
        content.invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopFling()
        removeCallbacks(detailRunnable)
        velocity?.recycle()
        velocity = null
    }

    companion object {
        private const val MARGIN = 16f
        private const val GAP = 18f
        private const val MAX_BASE_PX = 1800f
        private const val PALM_GUARD_MS = 400L

        // Algumas canetas Samsung mandam estas ações quando o botão lateral está apertado.
        private const val SAMSUNG_SPEN_DOWN = 211
        private const val SAMSUNG_SPEN_UP = 212
        private const val SAMSUNG_SPEN_MOVE = 213
        private const val SAMSUNG_SPEN_CANCEL = 214
        private val SAMSUNG_SPEN_ACTIONS = SAMSUNG_SPEN_DOWN..SAMSUNG_SPEN_CANCEL
    }
}
