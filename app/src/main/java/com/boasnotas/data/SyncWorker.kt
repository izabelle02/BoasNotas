package com.boasnotas.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Envia um caderno para o Google Drive quando houver internet (o Android cuida das novas tentativas). */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val settings = AppSettings(applicationContext)
        if (!settings.driveEnabled) return Result.success()
        return try {
            DriveSync.syncNotebook(applicationContext, id)
            NotebookStore.notifyChanged()
            Result.success()
        } catch (e: DriveSync.NeedsUserConsent) {
            settings.driveStatus = "Drive desconectado. ${e.message}"
            NotebookStore.notifyChanged()
            Result.failure()
        } catch (e: Exception) {
            Log.w("SyncWorker", "Falha ao salvar no Drive", e)
            settings.driveStatus = "Não deu para salvar no Drive agora (${e.message?.take(120)}). Vou tentar de novo."
            NotebookStore.notifyChanged()
            if (runAttemptCount < 6) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_ID = "id"

        /** Agenda o envio de um caderno (substitui um envio pendente do mesmo caderno). */
        fun schedule(context: Context, notebookId: String, delaySeconds: Long = 15) {
            if (!AppSettings(context).driveEnabled) return
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_ID to notebookId))
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-$notebookId", ExistingWorkPolicy.REPLACE, req)
        }

        fun scheduleAll(context: Context) {
            val store = NotebookStore(context)
            NotebookStore.io.execute {
                store.list().forEach { schedule(context, it.id, 0) }
            }
        }
    }
}
