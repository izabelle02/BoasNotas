package com.boasnotas.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.boasnotas.R
import com.boasnotas.data.AppSettings
import com.boasnotas.data.NotebookMeta
import com.boasnotas.data.NotebookStore
import com.boasnotas.model.Notebook
import com.boasnotas.model.PaperTemplate
import com.boasnotas.render.PageWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class EditorActivity : AppCompatActivity(), NoteCanvasView.Listener {

    private lateinit var store: NotebookStore
    private lateinit var settings: AppSettings
    private lateinit var notebook: Notebook
    private var worker: PageWorker? = null
    private lateinit var canvas: NoteCanvasView

    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var pageIndicator: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var sizeRow: LinearLayout
    private lateinit var sizeDivider: View
    private lateinit var toolHint: TextView
    private lateinit var selectionBar: View
    private lateinit var toolButtons: Map<ToolKind, ImageButton>

    private val io = NotebookStore.io
    private val handler = Handler(Looper.getMainLooper())
    private var dirty = false
    private var thumbDirty = true
    private val saveRunnable = Runnable { save() }

    // Cor e espessura escolhidas para cada ferramenta (lembradas entre os cadernos).
    private var penColorIdx = 0
    private var penSizeIdx = 1
    private var hlColorIdx = 0
    private var hlSizeIdx = 1
    private var eraserSizeIdx = 1

    private val saveAsPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@registerForActivityResult
        exportTo { file ->
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                Toast.makeText(this, "PDF salvo", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Não foi possível salvar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_editor)
        findViewById<View>(R.id.root).applySystemBarPadding()

        store = NotebookStore(this)
        settings = AppSettings(this)
        val id = intent.getStringExtra(EXTRA_ID)
        notebook = try {
            store.load(id ?: "")
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o caderno", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        worker = PageWorker(if (notebook.hasPdf) store.pdfFile(notebook.id) else null)

        canvas = findViewById(R.id.canvas)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        pageIndicator = findViewById(R.id.pageIndicator)
        colorRow = findViewById(R.id.colorRow)
        sizeRow = findViewById(R.id.sizeRow)
        sizeDivider = findViewById(R.id.sizeDivider)
        toolHint = findViewById(R.id.toolHint)
        selectionBar = findViewById(R.id.selectionBar)
        findViewById<TextView>(R.id.title).text = notebook.title

        toolButtons = mapOf(
            ToolKind.PEN to findViewById<ImageButton>(R.id.toolPen),
            ToolKind.HIGHLIGHTER to findViewById<ImageButton>(R.id.toolHighlighter),
            ToolKind.ERASER to findViewById<ImageButton>(R.id.toolEraser),
            ToolKind.LASSO to findViewById<ImageButton>(R.id.toolLasso),
        )
        toolButtons.forEach { (kind, btn) -> btn.setOnClickListener { selectTool(kind) } }

        penColorIdx = settings.getInt("penColor", 0).coerceIn(0, Palette.pen.size - 1)
        penSizeIdx = settings.getInt("penSize", 1).coerceIn(0, 2)
        hlColorIdx = settings.getInt("hlColor", 0).coerceIn(0, Palette.highlighter.size - 1)
        hlSizeIdx = settings.getInt("hlSize", 1).coerceIn(0, 2)
        eraserSizeIdx = settings.getInt("eraserSize", 1).coerceIn(0, 2)
        applyToolSettings()

        canvas.listener = this
        val startPage = getPreferences(Context.MODE_PRIVATE).getInt("page_${notebook.id}", 0)
        canvas.setup(notebook, worker, settings, startPage)
        val lastTool = ToolKind.entries.getOrElse(settings.getInt("tool", 0)) { ToolKind.PEN }
        selectTool(if (lastTool == ToolKind.LASSO) ToolKind.PEN else lastTool)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnUndo.setOnClickListener { canvas.undo() }
        btnRedo.setOnClickListener { canvas.redo() }
        pageIndicator.setOnClickListener { showPages() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        findViewById<View>(R.id.selDelete).setOnClickListener { canvas.deleteSelection() }
        findViewById<View>(R.id.selDuplicate).setOnClickListener { canvas.duplicateSelection() }
        findViewById<View>(R.id.selColor).setOnClickListener { pickSelectionColor() }
        findViewById<View>(R.id.selDone).setOnClickListener { canvas.clearSelection() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canvas.hasSelection) canvas.clearSelection() else finish()
            }
        })
        onHistoryChanged()
    }

    // ---------------- Ferramentas ----------------

    private fun selectTool(kind: ToolKind) {
        canvas.tool = kind
        if (kind != ToolKind.LASSO) settings.putInt("tool", kind.ordinal)
        refreshToolbar()
    }

    private fun refreshToolbar() {
        val shown = if (canvas.isPenButtonHeld) ToolKind.ERASER else canvas.tool
        toolButtons.forEach { (k, b) -> b.isSelected = k == shown }
        colorRow.removeAllViews()
        sizeRow.removeAllViews()
        toolHint.text = ""
        when (shown) {
            ToolKind.PEN -> {
                addColors(Palette.pen, penColorIdx) { penColorIdx = it; settings.putInt("penColor", it) }
                addSizes(penSizeIdx, Palette.pen[penColorIdx]) { penSizeIdx = it; settings.putInt("penSize", it) }
            }
            ToolKind.HIGHLIGHTER -> {
                addColors(Palette.highlighter, hlColorIdx) { hlColorIdx = it; settings.putInt("hlColor", it) }
                addSizes(hlSizeIdx, Palette.highlighter[hlColorIdx]) { hlSizeIdx = it; settings.putInt("hlSize", it) }
            }
            ToolKind.ERASER -> {
                addSizes(eraserSizeIdx, 0xFF9AA0A6.toInt()) { eraserSizeIdx = it; settings.putInt("eraserSize", it) }
                toolHint.text = if (settings.eraseWholeStroke) "Apaga o traço inteiro" else "Apaga só onde passa"
            }
            ToolKind.LASSO -> toolHint.text = "Circule o que quer selecionar; depois arraste para mover"
        }
        val hasColors = colorRow.childCount > 0
        colorRow.visibility = if (hasColors) View.VISIBLE else View.GONE
        sizeDivider.visibility = if (hasColors) View.VISIBLE else View.GONE
        sizeRow.visibility = if (sizeRow.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun addColors(colors: IntArray, selected: Int, onPick: (Int) -> Unit) {
        colors.forEachIndexed { i, c ->
            val v = SwatchView(this, c)
            v.isSelected = i == selected
            v.setOnClickListener {
                onPick(i)
                applyToolSettings()
                refreshToolbar()
            }
            colorRow.addView(v)
        }
    }

    private fun addSizes(selected: Int, color: Int, onPick: (Int) -> Unit) {
        for (i in 0..2) {
            val v = SizeView(this, i)
            v.dotColor = color
            v.isSelected = i == selected
            v.setOnClickListener {
                onPick(i)
                applyToolSettings()
                refreshToolbar()
            }
            sizeRow.addView(v)
        }
    }

    private fun applyToolSettings() {
        if (!::canvas.isInitialized) return
        canvas.penColor = Palette.pen[penColorIdx]
        canvas.penWidth = Palette.penWidths[penSizeIdx]
        canvas.highlighterColor = Palette.highlighter[hlColorIdx]
        canvas.highlighterWidth = Palette.highlighterWidths[hlSizeIdx]
        canvas.eraserRadius = Palette.eraserRadii[eraserSizeIdx]
    }

    private fun pickSelectionColor() {
        val names = arrayOf("Preto", "Azul", "Vermelho", "Verde", "Roxo", "Laranja", "Azul-petróleo", "Marrom")
        MaterialAlertDialogBuilder(this)
            .setTitle("Mudar a cor da seleção")
            .setItems(names) { _, which -> canvas.recolorSelection(Palette.pen[which]) }
            .show()
    }

    // ---------------- Avisos da folha ----------------

    override fun onCurrentPageChanged(index: Int, count: Int) {
        pageIndicator.text = "${index + 1} / $count"
    }

    override fun onContentChanged() {
        dirty = true
        thumbDirty = true
        notebook.modified = System.currentTimeMillis()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 1500)
    }

    override fun onHistoryChanged() {
        btnUndo.isEnabled = canvas.history.canUndo
        btnUndo.alpha = if (canvas.history.canUndo) 1f else 0.35f
        btnRedo.isEnabled = canvas.history.canRedo
        btnRedo.alpha = if (canvas.history.canRedo) 1f else 0.35f
        onCurrentPageChanged(canvas.currentPageIndex, canvas.pageCount)
    }

    override fun onToolChangedByPen(tool: ToolKind) = refreshToolbar()

    override fun onPenButtonHold(held: Boolean) = refreshToolbar()

    override fun onSelectionChanged(hasSelection: Boolean) {
        selectionBar.visibility = if (hasSelection) View.VISIBLE else View.GONE
    }

    // ---------------- Menu e páginas ----------------

    private fun showMenu(anchor: View) {
        val page = canvas.currentPageIndex
        val isPdfPage = notebook.pages.getOrNull(page)?.isPdf == true
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        m.add(0, 1, 0, "Nova página depois desta")
        m.add(0, 2, 0, "Duplicar esta página")
        if (!isPdfPage) m.add(0, 3, 0, "Mudar o papel desta página…")
        m.add(0, 4, 0, "Apagar esta página")
        m.add(0, 5, 0, "Exportar / compartilhar PDF")
        m.add(0, 6, 0, "Salvar PDF no tablet…")
        m.add(0, 7, 0, "Configurações")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askTemplate("Papel da nova página") { canvas.addPage(page, it) }
                2 -> canvas.duplicatePage(page)
                3 -> askTemplate("Papel desta página") { canvas.setTemplate(page, it) }
                4 -> confirmDeletePage(page)
                5 -> exportTo { shareFile(it) }
                6 -> saveAsPdf.launch(safeFileName() + ".pdf")
                7 -> SettingsDialog.show(this, settings) { applyToolSettings(); refreshToolbar() }
            }
            true
        }
        popup.show()
    }

    private fun askTemplate(title: String, onPick: (PaperTemplate) -> Unit) {
        val items = PaperTemplate.entries.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                val t = PaperTemplate.entries[which]
                notebook.defaultTemplate = t
                onPick(t)
            }
            .show()
    }

    private fun confirmDeletePage(index: Int) {
        if (canvas.pageCount <= 1) {
            Toast.makeText(this, "O caderno precisa ter pelo menos uma página", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Apagar a página ${index + 1}?")
            .setMessage("Você pode desfazer logo em seguida, se mudar de ideia.")
            .setPositiveButton("Apagar") { _, _ -> canvas.deletePage(index) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPages() {
        val w = worker ?: return
        PagesDialog.show(this, notebook.pages, canvas.currentPageIndex, w,
            onOpen = { canvas.scrollToPage(it) },
            onAdd = { canvas.addPage(notebook.pages.size - 1, notebook.defaultTemplate) },
        )
    }

    // ---------------- Exportar ----------------

    private fun safeFileName() =
        notebook.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Caderno" }

    private fun exportTo(onDone: (File) -> Unit) {
        val w = worker ?: return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Gerando PDF…")
            .setMessage("${notebook.pages.size} página(s)")
            .setCancelable(false)
            .show()
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, safeFileName() + ".pdf")
        w.exportPdf(notebook.pages, file) { error ->
            dialog.dismiss()
            if (error != null) {
                Toast.makeText(this, "Erro ao gerar o PDF: ${error.message}", Toast.LENGTH_LONG).show()
            } else {
                onDone(file)
            }
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, notebook.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartilhar PDF"))
    }

    // ---------------- Salvar ----------------

    private fun save() {
        handler.removeCallbacks(saveRunnable)
        if (!::notebook.isInitialized) return
        if (dirty) {
            dirty = false
            val meta = NotebookMeta.of(notebook)
            val pages = notebook.pages.map { it.snapshot() }
            io.execute {
                try {
                    store.save(meta, pages)
                    NotebookStore.notifyChanged()
                } catch (e: Exception) {
                    handler.post { Toast.makeText(this, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
        if (thumbDirty) {
            thumbDirty = false
            val first = notebook.pages.firstOrNull()?.snapshot() ?: return
            val id = notebook.id
            val w = worker ?: return
            // Roda na thread de desenho (antes de ela fechar, mesmo saindo do caderno).
            w.execute {
                val bmp = w.renderPage(first, 360)
                store.saveThumbnail(id, bmp)
                NotebookStore.notifyChanged()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::canvas.isInitialized) {
            getPreferences(Context.MODE_PRIVATE).edit().putInt("page_${notebook.id}", canvas.currentPageIndex).apply()
        }
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(saveRunnable)
        worker?.close()
    }

    companion object {
        const val EXTRA_ID = "notebook_id"

        fun open(context: Context, id: String) {
            context.startActivity(Intent(context, EditorActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
