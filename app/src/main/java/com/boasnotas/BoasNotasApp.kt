package com.boasnotas

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Se o app fechar por um erro, guarda a mensagem técnica num arquivo. Na próxima vez que o
 * app abrir, a tela inicial mostra o erro com um botão para copiar (e mandar para quem cuida do app).
 */
class BoasNotasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val info = packageManager.getPackageInfo(packageName, 0)
                crashFile(this).writeText(
                    "BoasNotas ${info.versionName} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n$sw",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }
}
