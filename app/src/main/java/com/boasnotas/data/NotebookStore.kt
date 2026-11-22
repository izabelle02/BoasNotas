package com.boasnotas.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.boasnotas.model.Notebook
import com.boasnotas.model.NotebookCodec
import com.boasnotas.model.Page
import com.boasnotas.model.PaperTemplate
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Resumo de um caderno, usado na tela inicial (sem carregar os traços). */
data class NotebookInfo(
    val id: String,
    val title: String,
    val coverColor: Int,
    val modified: Long,
    val pageCount: Int,
    val thumbnail: File,
)

/**
 * Guarda os cadernos na memória interna do app:
 *   files/notebooks/<id>/meta.json   título, cor, datas
 *   files/notebooks/<id>/pages.bin   páginas e traços
 *   files/notebooks/<id>/source.pdf  PDF importado (se houver)
 *   files/notebooks/<id>/thumb.png   miniatura da 1ª página
 */
class NotebookStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "notebooks").apply { mkdirs() }

    private fun dir(id: String) = File(root, id)
    fun notebookDir(id: String) = dir(id)
    fun assetsDir(id: String) = File(dir(id), "assets")
    fun exists(id: String) = File(dir(id), "meta.json").exists()
    val rootDir: File get() = root
    fun pdfFile(id: String) = File(dir(id), "source.pdf")
    fun thumbFile(id: String) = File(dir(id), "thumb.png")

    @Synchronized
    fun list(): List<NotebookInfo> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            try {
                val m = JSONObject(File(d, "meta.json").readText())
                NotebookInfo(
                    id = d.name,
                    title = m.optString("title", "Caderno"),
                    coverColor = m.optInt("coverColor", DEFAULT_COVER),
                    modified = m.optLong("modified", d.lastModified()),
                    pageCount = m.optInt("pageCount", 0),
                    thumbnail = File(d, "thumb.png"),
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.modified }
    }

    fun load(id: String): Notebook {
        val d = dir(id)
        val m = JSONObject(File(d, "meta.json").readText())
        val pages = File(d, "pages.bin").inputStream().use { NotebookCodec.read(it) }
        if (pages.isEmpty()) pages.add(newPage(PaperTemplate.LINED))
        return Notebook(
            id = id,
            title = m.optString("title", "Caderno"),
            coverColor = m.optInt("coverColor", DEFAULT_COVER),
            created = m.optLong("created", System.currentTimeMillis()),
            modified = m.optLong("modified", System.currentTimeMillis()),
            defaultTemplate = PaperTemplate.fromOrdinal(m.optInt("template", PaperTemplate.LINED.ordinal)),
            hasPdf = m.optBoolean("hasPdf", false),
            pages = pages,
        )
    }

    /** Salva tudo. [pages] deve ser uma cópia (veja Page.snapshot) — pode rodar em outra thread. */
    @Synchronized
    fun save(nb: NotebookMeta, pages: List<Page>) {
        val d = dir(nb.id).apply { mkdirs() }
        val tmp = File(d, "pages.bin.tmp")
        FileOutputStream(tmp).use { out ->
            NotebookCodec.write(out, pages)
            out.fd.sync()
        }
        if (!tmp.renameTo(File(d, "pages.bin"))) {
            File(d, "pages.bin").delete()
            tmp.renameTo(File(d, "pages.bin"))
        }
        writeMeta(nb, pages.size)
    }

    @Synchronized
    fun writeMeta(nb: NotebookMeta, pageCount: Int) {
        val d = dir(nb.id).apply { mkdirs() }
        val m = JSONObject()
            .put("title", nb.title)
            .put("coverColor", nb.coverColor)
            .put("created", nb.created)
            .put("modified", nb.modified)
            .put("template", nb.defaultTemplate.ordinal)
            .put("hasPdf", nb.hasPdf)
            .put("pageCount", pageCount)
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(m.toString())
        if (!tmp.renameTo(File(d, "meta.json"))) {
            File(d, "meta.json").delete()
            tmp.renameTo(File(d, "meta.json"))
        }
    }

    @Synchronized
    fun saveThumbnail(id: String, bitmap: Bitmap) {
        val f = thumbFile(id)
        val tmp = File(f.parentFile, "thumb.png.tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
    }

    fun rename(id: String, title: String) = updateMeta(id) { it.put("title", title) }

    fun setCoverColor(id: String, color: Int) = updateMeta(id) { it.put("coverColor", color) }

    private fun updateMeta(id: String, change: (JSONObject) -> Unit) {
        val f = File(dir(id), "meta.json")
        val m = JSONObject(f.readText())
        change(m)
        f.writeText(m.toString())
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun create(title: String, coverColor: Int, template: PaperTemplate): Notebook {
        val now = System.currentTimeMillis()
        val nb = Notebook(
            id = UUID.randomUUID().toString(),
            title = title,
            coverColor = coverColor,
            created = now,
            modified = now,
            defaultTemplate = template,
            hasPdf = false,
            pages = mutableListOf(newPage(template)),
        )
        save(NotebookMeta.of(nb), nb.pages.map { it.snapshot() })
        return nb
    }

    /** Copia o PDF para dentro do app e cria um caderno com uma página por página do PDF. */
    fun importPdf(uri: Uri, coverColor: Int): Notebook {
        val id = UUID.randomUUID().toString()
        val d = dir(id).apply { mkdirs() }
        val pdf = pdfFile(id)
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Não foi possível abrir o arquivo")
            input.use { inp -> FileOutputStream(pdf).use { inp.copyTo(it) } }

            val pages = ArrayList<Page>()
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { p ->
                            pages.add(
                                Page(
                                    newId(), PaperTemplate.BLANK,
                                    p.width.toFloat(), p.height.toFloat(), pdfPageIndex = i,
                                ),
                            )
                        }
                    }
                }
            }
            if (pages.isEmpty()) throw IllegalStateException("O PDF não tem páginas")
            val now = System.currentTimeMillis()
            val nb = Notebook(
                id = id,
                title = displayName(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "PDF importado",
                coverColor = coverColor,
                created = now,
                modified = now,
                defaultTemplate = PaperTemplate.LINED,
                hasPdf = true,
                pages = pages,
            )
            save(NotebookMeta.of(nb), pages)
            return nb
        } catch (e: Exception) {
            d.deleteRecursively()
            throw e
        }
    }

    private fun displayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_COVER = 0xFF3F51B5.toInt()

        /** Uma única fila para ler/gravar arquivos: garante a ordem (salvar antes de listar). */
        val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "boasnotas-io") }

        private val main = Handler(Looper.getMainLooper())

        /** Avisado (na thread principal) quando algum caderno é salvo. */
        var onChanged: (() -> Unit)? = null

        fun notifyChanged() {
            main.post { onChanged?.invoke() }
        }

        fun newId(): String = UUID.randomUUID().toString()

        fun newPage(template: PaperTemplate) =
            Page(newId(), template, Page.A4_WIDTH, Page.A4_HEIGHT)
    }
}

/** Os dados do caderno que vão para o meta.json (cópia segura para salvar em segundo plano). */
data class NotebookMeta(
    val id: String,
    val title: String,
    val coverColor: Int,
    val created: Long,
    val modified: Long,
    val defaultTemplate: PaperTemplate,
    val hasPdf: Boolean,
) {
    companion object {
        fun of(nb: Notebook) =
            NotebookMeta(nb.id, nb.title, nb.coverColor, nb.created, nb.modified, nb.defaultTemplate, nb.hasPdf)
    }
}
