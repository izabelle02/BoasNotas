package com.boasnotas.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boasnotas.R
import com.boasnotas.data.AppSettings
import com.boasnotas.data.NotebookInfo
import com.boasnotas.data.NotebookStore
import com.boasnotas.model.PaperTemplate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.max

/** Tela inicial: a estante de cadernos. */
class LibraryActivity : AppCompatActivity() {

    private lateinit var store: NotebookStore
    private lateinit var listView: RecyclerView
    private lateinit var empty: View
    private val adapter = NotebookAdapter()
    private var items: List<NotebookInfo> = emptyList()

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_library)
        findViewById<View>(R.id.root).applySystemBarPadding()
        store = NotebookStore(this)

        listView = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        val spans = max(2, resources.configuration.screenWidthDp / 190)
        listView.layoutManager = GridLayoutManager(this, spans)
        listView.adapter = adapter

        findViewById<View>(R.id.fab).setOnClickListener { newNotebookDialog() }
        findViewById<View>(R.id.btnImport).setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { SettingsDialog.show(this, AppSettings(this)) }

        if (savedInstanceState == null) handleIncomingPdf(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingPdf(intent)
    }

    /** PDF aberto pelo app Arquivos ou compartilhado por outro app (WhatsApp, Drive…). */
    private fun handleIncomingPdf(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) importPdf(uri)
    }

    override fun onResume() {
        super.onResume()
        NotebookStore.onChanged = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        NotebookStore.onChanged = null
    }

    private fun refresh() {
        NotebookStore.io.execute {
            val list = store.list()
            runOnUiThread {
                items = list
                adapter.notifyDataSetChanged()
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ---------------- Criar / importar ----------------

    private fun newNotebookDialog() {
        val pad = dpi(22)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dpi(8), pad, 0)
        }
        val name = EditText(this).apply {
            hint = "Nome do caderno"
            setText("Caderno ${items.size + 1}")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            selectAll()
        }
        box.addView(name)

        box.addView(label("Cor da capa"))
        var cover = Palette.covers[items.size % Palette.covers.size]
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = Palette.covers.map { SwatchView(this, it) }
        swatches.forEach { sw ->
            sw.isSelected = sw.color == cover
            sw.setOnClickListener {
                cover = sw.color
                swatches.forEach { it.isSelected = it === sw }
            }
            colors.addView(sw)
        }
        box.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(colors)
        })

        box.addView(label("Papel"))
        val group = RadioGroup(this)
        var template = PaperTemplate.LINED
        PaperTemplate.entries.forEach { t ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = t.label
                isChecked = t == template
                setOnCheckedChangeListener { _, c -> if (c) template = t }
            })
        }
        box.addView(group)

        MaterialAlertDialogBuilder(this)
            .setTitle("Novo caderno")
            .setView(box)
            .setPositiveButton("Criar") { _, _ ->
                val title = name.text.toString().trim().ifEmpty { "Caderno" }
                val c = cover
                val t = template
                NotebookStore.io.execute {
                    val nb = store.create(title, c, t)
                    runOnUiThread { EditorActivity.open(this, nb.id) }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dpi(16), 0, dpi(6))
    }

    private fun importPdf(uri: Uri) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("Importando PDF…")
            .setCancelable(false)
            .show()
        val color = Palette.covers[items.size % Palette.covers.size]
        NotebookStore.io.execute {
            val result = runCatching { store.importPdf(uri, color) }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { EditorActivity.open(this, it.id) }
                result.onFailure {
                    Toast.makeText(this, "Não foi possível importar: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- Opções do caderno (toque longo) ----------------

    private fun showOptions(anchor: View, info: NotebookInfo) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Renomear")
        popup.menu.add(0, 2, 0, "Mudar a cor da capa")
        popup.menu.add(0, 3, 0, "Apagar")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> rename(info)
                2 -> changeCover(info)
                3 -> confirmDelete(info)
            }
            true
        }
        popup.show()
    }

    private fun rename(info: NotebookInfo) {
        val input = EditText(this).apply {
            setText(info.title)
            selectAll()
        }
        val frame = LinearLayout(this).apply {
            setPadding(dpi(22), dpi(8), dpi(22), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Renomear")
            .setView(frame)
            .setPositiveButton("Salvar") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) NotebookStore.io.execute { store.rename(info.id, t); runOnUiThread { refresh() } }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun changeCover(info: NotebookInfo) {
        val colors = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(18), dpi(12), dpi(18), 0)
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        Palette.covers.forEach { c ->
            colors.addView(SwatchView(this, c).apply {
                isSelected = c == info.coverColor
                setOnClickListener {
                    NotebookStore.io.execute { store.setCoverColor(info.id, c); runOnUiThread { refresh() } }
                    dialog?.dismiss()
                }
            })
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Cor da capa")
            .setView(android.widget.HorizontalScrollView(this).apply { addView(colors) })
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(info: NotebookInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apagar \"${info.title}\"?")
            .setMessage("O caderno e todas as páginas serão apagados. Isso não pode ser desfeito.")
            .setPositiveButton("Apagar") { _, _ ->
                NotebookStore.io.execute { store.delete(info.id); runOnUiThread { refresh() } }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Lista ----------------

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cover: View = v.findViewById(R.id.cover)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.info)
    }

    private inner class NotebookAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_notebook, parent, false)
            // Capa com proporção de folha A4
            val spans = (listView.layoutManager as GridLayoutManager).spanCount
            val listW = if (parent.width > 0) parent.width else resources.displayMetrics.widthPixels
            val colW = (listW - parent.paddingLeft - parent.paddingRight) / spans - dpi(24)
            if (colW > 0) v.findViewById<View>(R.id.cover).layoutParams.height = (colW * 1.38f).toInt()
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            h.cover.backgroundTintList = ColorStateList.valueOf(item.coverColor)
            h.name.text = item.title
            val ago = DateUtils.getRelativeTimeSpanString(item.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.info.text = "${item.pageCount} pág. · $ago"
            h.thumb.setImageDrawable(null)
            h.thumb.tag = item.id
            if (item.thumbnail.exists()) {
                val file = item.thumbnail
                NotebookStore.io.execute {
                    val bmp = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
                    runOnUiThread { if (h.thumb.tag == item.id && bmp != null) h.thumb.setImageBitmap(bmp) }
                }
            }
            h.itemView.setOnClickListener { EditorActivity.open(this@LibraryActivity, item.id) }
            h.itemView.setOnLongClickListener { showOptions(h.itemView, item); true }
        }
    }
}
