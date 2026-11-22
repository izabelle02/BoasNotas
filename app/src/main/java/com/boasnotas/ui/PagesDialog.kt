package com.boasnotas.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boasnotas.R
import com.boasnotas.model.Page
import com.boasnotas.render.PageWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.IdentityHashMap
import kotlin.math.max

/** Grade com as miniaturas de todas as páginas, para pular direto para uma delas. */
object PagesDialog {

    fun show(
        activity: Activity,
        pages: List<Page>,
        current: Int,
        worker: PageWorker,
        onOpen: (Int) -> Unit,
        onAdd: () -> Unit,
    ) {
        val list = RecyclerView(activity)
        val widthDp = activity.resources.configuration.screenWidthDp
        val spans = max(2, (widthDp * 0.85f / 150f).toInt())
        list.layoutManager = GridLayoutManager(activity, spans)
        list.setPadding(activity.dpi(12), activity.dpi(8), activity.dpi(12), activity.dpi(8))
        list.clipToPadding = false

        var dialog: AlertDialog? = null
        val snapshot = pages.toList()
        val thumbs = IdentityHashMap<Page, Bitmap>()

        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = snapshot.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val page = snapshot[position]
                val v = holder.itemView
                val img = v.findViewById<ImageView>(R.id.thumb)
                v.findViewById<TextView>(R.id.number).text = (position + 1).toString()
                img.isSelected = position == current
                img.tag = page
                val bmp = thumbs[page]
                if (bmp != null) {
                    img.setImageBitmap(bmp)
                } else {
                    img.setImageDrawable(null)
                    worker.requestThumbnail(page, activity.dpi(150)) { b ->
                        thumbs[page] = b
                        if (img.tag === page) img.setImageBitmap(b)
                    }
                }
                v.setOnClickListener {
                    dialog?.dismiss()
                    onOpen(position)
                }
            }
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Páginas (${snapshot.size})")
            .setView(list)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Nova página") { _, _ -> onAdd() }
            .show()
        if (current in snapshot.indices) list.scrollToPosition(current)
    }
}
