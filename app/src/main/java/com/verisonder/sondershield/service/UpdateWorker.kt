package com.verisonder.sondershield.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.verisonder.sondershield.core.Phase
import com.verisonder.sondershield.core.ShieldState
import com.verisonder.sondershield.data.ListStore
import com.verisonder.sondershield.data.Prefs
import java.util.concurrent.TimeUnit

/** Refreshes the enabled block lists on the schedule picked in Settings. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ShieldState.init(applicationContext)
        val changed = ListStore(applicationContext).updateEnabled()
        if (changed) {
            ShieldState.listsVersion.value++
            if (ShieldState.phase.value == Phase.ON) ShieldState.applyConfig()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "list-updates"

        fun schedule(context: Context) {
            val hours = Prefs(context).updateHours
            val wm = WorkManager.getInstance(context)
            if (hours <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .build()
            // UPDATE keeps the timer when nothing changed and applies a new interval.
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
