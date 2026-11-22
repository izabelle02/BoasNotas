package com.boasnotas.data

import android.content.Context
import com.boasnotas.render.PageWorker
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Salvamento no Google Drive. Para cada caderno, numa pasta "BoasNotas" do seu Drive:
 *   "<título>.pdf"        o caderno com a sua letra, do jeitinho que aparece na tela
 *   "Cópias de segurança (não apague)/<título>.boasnotas"
 *                         tudo do caderno, para restaurar no app (ex.: em outro tablet)
 *
 * Usa a permissão "drive.file": o app só enxerga os arquivos que ele mesmo criou.
 */
object DriveSync {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val MAIN_FOLDER = "BoasNotas"
    private const val BACKUP_FOLDER = "Cópias de segurança (não apague)"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

    class NeedsUserConsent : Exception("Toque na nuvem ☁ na tela inicial e conecte o Google Drive de novo.")

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val lock = Any()

    fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Pega um acesso ao Drive sem mostrar nada na tela (em segundo plano). */
    private fun fetchToken(context: Context): String {
        val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(authRequest()), 60, TimeUnit.SECONDS)
        if (r.hasResolution()) throw NeedsUserConsent()
        return r.accessToken ?: throw NeedsUserConsent()
    }

    /** Uma conversa com a API do Drive, renovando o acesso se ele vencer. */
    private class Api(val context: Context) {
        var token: String = fetchToken(context)

        fun call(build: (Request.Builder) -> Request.Builder): JSONObject {
            execute(build).use { resp -> return JSONObject(resp.body?.string().orEmpty().ifEmpty { "{}" }) }
        }

        fun execute(build: (Request.Builder) -> Request.Builder): Response {
            var resp = http.newCall(build(Request.Builder()).header("Authorization", "Bearer $token").build()).execute()
            if (resp.code == 401) {
                resp.close()
                runCatching { GoogleAuthUtil.clearToken(context, token) }
                token = fetchToken(context)
                resp = http.newCall(build(Request.Builder()).header("Authorization", "Bearer $token").build()).execute()
            }
            if (!resp.isSuccessful) {
                val code = resp.code
                val msg = resp.body?.string().orEmpty()
                resp.close()
                throw HttpError(code, msg)
            }
            return resp
        }
    }

    class HttpError(val code: Int, body: String) : IOException("Drive respondeu $code: ${body.take(300)}")

    private fun q(api: Api, query: String): JSONArray {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("fields", "files(id,name,appProperties,trashed)")
            .build()
        return api.call { it.url(url).get() }.optJSONArray("files") ?: JSONArray()
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun isAlive(api: Api, id: String?): Boolean {
        if (id.isNullOrEmpty()) return false
        return try {
            val o = api.call { it.url("$API/$id?fields=id,trashed").get() }
            !o.optBoolean("trashed", false)
        } catch (e: HttpError) {
            if (e.code == 404) false else throw e
        }
    }

    private fun folder(api: Api, settings: AppSettings, key: String, name: String, parent: String?): String {
        settings.getString(key)?.let { if (isAlive(api, it)) return it }
        val where = "mimeType=${quote(FOLDER_MIME)} and name=${quote(name)} and trashed=false" +
            (if (parent != null) " and ${quote(parent)} in parents" else "")
        val found = q(api, where)
        val id = if (found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
            if (parent != null) meta.put("parents", JSONArray().put(parent))
            api.call {
                it.url("$API?fields=id").post(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            }.getString("id")
        }
        settings.putString(key, id)
        return id
    }

    /** Envia (ou atualiza) um arquivo. Devolve o id dele no Drive. */
    private fun upload(
        api: Api, existingId: String?, name: String, mime: String, parent: String,
        props: Map<String, String>, file: File,
    ): String {
        val meta = JSONObject().put("name", name)
        val appProps = JSONObject()
        props.forEach { (k, v) -> appProps.put(k, v) }
        meta.put("appProperties", appProps)
        fun body(m: JSONObject): RequestBody = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(m.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(file.asRequestBody(mime.toMediaType()))
            .build()
        if (!existingId.isNullOrEmpty()) {
            try {
                return api.call {
                    it.url("$UPLOAD/$existingId?uploadType=multipart&fields=id").patch(body(meta))
                }.getString("id")
            } catch (e: HttpError) {
                if (e.code != 404) throw e
            }
        }
        meta.put("parents", JSONArray().put(parent))
        return api.call { it.url("$UPLOAD?uploadType=multipart&fields=id").post(body(meta)) }.getString("id")
    }

    private fun safeName(title: String) =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Caderno" }

    private fun driveInfoFile(store: NotebookStore, id: String) = File(store.notebookDir(id), "drive.json")

    /** Salva um caderno no Drive (PDF + cópia de segurança). Rode fora da thread principal. */
    fun syncNotebook(context: Context, id: String) = synchronized(lock) {
        val store = NotebookStore(context)
        if (!store.exists(id)) return@synchronized
        val settings = AppSettings(context)
        val nb = store.load(id)
        val api = Api(context)
        val main = folder(api, settings, "drive_folder", MAIN_FOLDER, null)
        val backup = folder(api, settings, "drive_backup_folder", BACKUP_FOLDER, main)

        val infoFile = driveInfoFile(store, id)
        val info = runCatching { JSONObject(infoFile.readText()) }.getOrDefault(JSONObject())
        val tmp = File(context.cacheDir, "sync").apply { mkdirs() }
        val pdf = File(tmp, "$id.pdf")
        val zip = File(tmp, "$id.zip")
        try {
            // 1) PDF com a letra
            val worker = PageWorker(if (nb.hasPdf) store.pdfFile(id) else null, store.assetsDir(id))
            try {
                worker.exportPdfBlocking(nb.pages, pdf)
            } finally {
                worker.close()
            }
            val name = safeName(nb.title)
            info.put(
                "pdf",
                upload(api, info.optString("pdf"), "$name.pdf", "application/pdf", main,
                    mapOf("boasnotasId" to id, "kind" to "pdf"), pdf),
            )
            // 2) cópia de segurança
            zipNotebook(store.notebookDir(id), zip)
            info.put(
                "backup",
                upload(api, info.optString("backup"), "$name.boasnotas", "application/zip", backup,
                    mapOf("boasnotasId" to id, "kind" to "backup"), zip),
            )
            infoFile.writeText(info.toString())
        } finally {
            pdf.delete()
            zip.delete()
        }
        val hora = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
        settings.driveStatus = "Último salvamento no Drive: \"${nb.title}\" em $hora"
    }

    /** Baixa do Drive os cadernos que não estão neste tablet. Devolve quantos foram restaurados. */
    fun restoreMissing(context: Context): Int = synchronized(lock) {
        val store = NotebookStore(context)
        val settings = AppSettings(context)
        val api = Api(context)
        val main = folder(api, settings, "drive_folder", MAIN_FOLDER, null)
        val backup = folder(api, settings, "drive_backup_folder", BACKUP_FOLDER, main)
        val files = q(api, "${quote(backup)} in parents and trashed=false")
        var restored = 0
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val nbId = f.optJSONObject("appProperties")?.optString("boasnotasId").orEmpty()
            if (nbId.isEmpty() || !nbId.matches(Regex("[A-Za-z0-9-]{8,64}")) || store.exists(nbId)) continue
            val tmp = File(context.cacheDir, "restore-$nbId.zip")
            try {
                api.execute { it.url("$API/${f.getString("id")}?alt=media").get() }.use { resp ->
                    FileOutputStream(tmp).use { out -> resp.body!!.byteStream().copyTo(out) }
                }
                val dir = store.notebookDir(nbId)
                unzip(tmp, dir)
                if (!store.exists(nbId)) {
                    dir.deleteRecursively()
                    continue
                }
                val info = runCatching { JSONObject(File(dir, "drive.json").readText()) }.getOrDefault(JSONObject())
                info.put("backup", f.getString("id"))
                File(dir, "drive.json").writeText(info.toString())
                restored++
            } finally {
                tmp.delete()
            }
        }
        restored
    }

    private fun zipNotebook(dir: File, out: File) {
        ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
            dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun unzip(zip: File, dir: File) {
        dir.mkdirs()
        val base = dir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val target = File(dir, e.name)
                // proteção: nada pode ser escrito fora da pasta do caderno
                if (!target.canonicalPath.startsWith(base)) throw IOException("Arquivo de backup inválido")
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it) }
                }
            }
        }
    }
}
