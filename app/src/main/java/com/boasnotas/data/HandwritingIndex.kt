package com.boasnotas.data

import android.content.Context
import android.util.Log
import com.boasnotas.model.InkLines
import com.boasnotas.model.Page
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** O texto de uma página: o que foi reconhecido da letra + as caixas de texto. */
data class PageText(val pageId: String, val hash: Int, val ink: String, val typed: String)

/**
 * Lê a letra das páginas (reconhecimento do Google ML Kit, roda no próprio tablet depois de
 * baixar o modelo de português uma vez, ~20 MB) e guarda o texto em text.json, para a busca.
 */
object HandwritingIndex {
    private const val TAG = "HandwritingIndex"
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "boasnotas-ink").apply { priority = Thread.MIN_PRIORITY } }

    @Volatile
    private var model: DigitalInkRecognitionModel? = null

    fun textFile(store: NotebookStore, id: String) = File(store.notebookDir(id), "text.json")

    /** Reindexa o caderno em segundo plano (só as páginas que mudaram). */
    fun requestIndex(context: Context, notebookId: String) {
        val app = context.applicationContext
        executor.execute {
            try {
                indexNotebook(app, notebookId)
            } catch (e: Throwable) {
                Log.w(TAG, "Falha ao indexar", e)
            }
        }
    }

    /** Baixa o modelo de letra em segundo plano, se ainda não estiver no tablet. */
    fun prepare(context: Context) {
        if (!AppSettings(context).handwritingSearch) return
        executor.execute { runCatching { ensureModel() } }
    }

    private fun ensureModel(): DigitalInkRecognitionModel? {
        model?.let { return it }
        val id = listOf("pt-BR", "pt-PT", "pt").firstNotNullOfOrNull { tag ->
            runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull()
        } ?: return null
        val m = DigitalInkRecognitionModel.builder(id).build()
        val manager = RemoteModelManager.getInstance()
        val downloaded = Tasks.await(manager.isModelDownloaded(m), 30, TimeUnit.SECONDS)
        if (!downloaded) {
            Tasks.await(manager.download(m, DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
        }
        model = m
        return m
    }

    private fun pageHash(page: Page): Int {
        var h = 17
        for (s in page.strokes) h = h * 31 + s.contentHash()
        return h
    }

    fun read(file: File): List<PageText> = try {
        val arr = JSONObject(file.readText()).getJSONArray("pages")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PageText(o.getString("id"), o.optInt("hash", 0), o.optString("ink", ""), o.optString("typed", ""))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun indexNotebook(context: Context, id: String) {
        val store = NotebookStore(context)
        val nb = runCatching { store.load(id) }.getOrNull() ?: return
        val file = textFile(store, id)
        val old = read(file).associateBy { it.pageId }
        val enabled = AppSettings(context).handwritingSearch
        var recognizer: DigitalInkRecognizer? = null
        val result = ArrayList<PageText>()
        try {
            for (page in nb.pages) {
                val typed = page.texts.joinToString("\n") { it.text }
                val hash = pageHash(page)
                val prev = old[page.id]
                if (prev != null && prev.hash == hash && hash != 0) {
                    result.add(prev.copy(typed = typed))
                    continue
                }
                if (page.strokes.isEmpty()) {
                    result.add(PageText(page.id, hash, "", typed))
                    continue
                }
                if (!enabled) {
                    result.add(PageText(page.id, 0, prev?.ink ?: "", typed))
                    continue
                }
                if (recognizer == null) {
                    val m = runCatching { ensureModel() }.getOrNull()
                    if (m == null) {
                        // sem internet para baixar o modelo: tenta de novo da próxima vez
                        result.add(PageText(page.id, 0, prev?.ink ?: "", typed))
                        continue
                    }
                    recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
                }
                val ink = recognizePage(recognizer, page)
                result.add(PageText(page.id, if (ink == null) 0 else hash, ink ?: prev?.ink ?: "", typed))
            }
        } finally {
            recognizer?.close()
        }
        val arr = JSONArray()
        for (p in result) {
            arr.put(JSONObject().put("id", p.pageId).put("hash", p.hash).put("ink", p.ink).put("typed", p.typed))
        }
        val tmp = File(file.parentFile, "text.json.tmp")
        tmp.writeText(JSONObject().put("pages", arr).toString())
        tmp.renameTo(file)
    }

    private fun recognizePage(recognizer: DigitalInkRecognizer, page: Page): String? {
        val lines = InkLines.group(page.strokes)
        val out = StringBuilder()
        for (line in lines) {
            val ink = Ink.builder()
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (s in line) {
                val sb = Ink.Stroke.builder()
                for (i in 0 until s.pointCount) {
                    sb.addPoint(Ink.Point.create(s.x(i), s.y(i)))
                }
                ink.addStroke(sb.build())
                val b = s.bounds
                minX = minOf(minX, b[0]); maxX = maxOf(maxX, b[2])
                minY = minOf(minY, b[1]); maxY = maxOf(maxY, b[3])
            }
            val ctx = RecognitionContext.builder()
                .setPreContext(out.takeLast(20).toString())
                .setWritingArea(WritingArea(maxOf(1f, maxX - minX), maxOf(1f, maxY - minY)))
                .build()
            val text = try {
                Tasks.await(recognizer.recognize(ink.build(), ctx), 20, TimeUnit.SECONDS).candidates.firstOrNull()?.text
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao reconhecer linha", e)
                return null
            }
            if (!text.isNullOrBlank()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(text)
            }
        }
        return out.toString()
    }

    /** Minúsculas e sem acentos, para "Ação" achar "acao". */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
