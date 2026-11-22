package com.boasnotas.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boasnotas.R
import com.boasnotas.data.AppSettings
import com.boasnotas.data.HandwritingIndex
import com.boasnotas.data.NotebookStore
import com.boasnotas.data.PageText

/** Busca em todos os cadernos: títulos, caixas de texto e a letra reconhecida. */
class SearchActivity : AppCompatActivity() {

    private class Entry(val notebookId: String, val title: String, val pages: List<PageText>)
    private class Hit(val notebookId: String, val title: String, val page: Int, val snippet: CharSequence)

    private var entries: List<Entry> = emptyList()
    private var hits: List<Hit> = emptyList()
    private lateinit var input: EditText
    private lateinit var status: TextView
    private val adapter = HitAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.library))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4), dpi(6), dpi(12), dpi(6))
            setBackgroundColor(getColor(R.color.bar))
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(android.R.color.transparent)
            imageTintList = getColorStateList(R.color.icon)
            contentDescription = "Voltar"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        input = EditText(this).apply {
            hint = "Buscar nos cadernos (letra, textos, títulos)"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 17f
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        status = TextView(this).apply {
            setPadding(dpi(20), dpi(12), dpi(20), dpi(4))
            setTextColor(getColor(R.color.textSecondary))
            textSize = 13f
        }
        root.addView(status)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = this@SearchActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dpi(32))
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.applySystemBarPadding()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = search()
        })
        input.requestFocus()

        status.text = "Carregando…"
        val store = NotebookStore(this)
        NotebookStore.io.execute {
            val loaded = store.list().map { info ->
                Entry(info.id, info.title, HandwritingIndex.read(HandwritingIndex.textFile(store, info.id)))
            }
            runOnUiThread {
                entries = loaded
                search()
            }
        }
    }

    private fun search() {
        val q = HandwritingIndex.normalize(input.text.toString().trim())
        if (q.length < 2) {
            hits = emptyList()
            adapter.notifyDataSetChanged()
            status.text = if (AppSettings(this).handwritingSearch) {
                "Digite pelo menos 2 letras. A letra é reconhecida automaticamente quando você sai de um caderno " +
                    "(na primeira vez, o tablet baixa o reconhecimento de português, precisa de internet)."
            } else {
                "O reconhecimento da letra está desligado nas Configurações: só títulos e caixas de texto entram na busca."
            }
            return
        }
        val out = ArrayList<Hit>()
        for (e in entries) {
            if (HandwritingIndex.normalize(e.title).contains(q)) out.add(Hit(e.notebookId, e.title, -1, "Título do caderno"))
            e.pages.forEachIndexed { i, p ->
                val text = listOf(p.typed, p.ink).filter { it.isNotBlank() }.joinToString("\n")
                val norm = HandwritingIndex.normalize(text)
                val at = norm.indexOf(q)
                if (at >= 0) out.add(Hit(e.notebookId, e.title, i, snippet(text, at, q.length)))
            }
        }
        hits = out
        adapter.notifyDataSetChanged()
        status.text = if (out.isEmpty()) "Nada encontrado." else "${out.size} resultado(s)"
    }

    /** Trecho em volta do que foi achado, com a palavra destacada. */
    private fun snippet(text: String, at: Int, len: Int): CharSequence {
        // a normalização pode mudar o tamanho em casos raros; garante limites válidos
        val a = at.coerceIn(0, text.length)
        val start = maxOf(0, a - 40)
        val end = minOf(text.length, a + len + 60)
        val prefix = if (start > 0) "…" else ""
        val raw = prefix + text.substring(start, end).replace('\n', ' ') + if (end < text.length) "…" else ""
        val sp = SpannableString(raw)
        val hs = (a - start + prefix.length).coerceIn(0, raw.length)
        val he = (hs + len).coerceIn(hs, raw.length)
        sp.setSpan(BackgroundColorSpan(0x66FFE135), hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    private inner class HitAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = hits.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(20), dpi(12), dpi(20), dpi(12))
                setBackgroundResource(android.R.drawable.list_selector_background)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            box.addView(TextView(parent.context).apply {
                id = R.id.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.text))
            })
            box.addView(TextView(parent.context).apply {
                id = R.id.info
                textSize = 14f
                setTextColor(getColor(R.color.textSecondary))
            })
            return object : RecyclerView.ViewHolder(box) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = hits[position]
            val v = holder.itemView
            v.findViewById<TextView>(R.id.name).text =
                if (h.page >= 0) "${h.title} · página ${h.page + 1}" else h.title
            v.findViewById<TextView>(R.id.info).text = h.snippet
            v.setOnClickListener { EditorActivity.open(this@SearchActivity, h.notebookId, maxOf(0, h.page)) }
        }
    }
}
