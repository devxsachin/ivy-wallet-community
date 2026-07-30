package com.ivy.data.backup.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GoogleDriveAutoBackupScheduler @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {
    companion object {
        const val UNIQUE_WORK_NAME = "DRIVE_AUTO_BACKUP_WORK"
        private const val BACKUP_INTERVAL_HOURS = 6L
        private const val INITIAL_DELAY_MINUTES = 30L
        private const val BACKOFF_DELAY_SECONDS = 60L
    }

    fun scheduleAutoBackups() {
        val backupWorkRequest = PeriodicWorkRequestBuilder<GoogleDriveBackupWorker>(
            BACKUP_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            backupWorkRequest,
        )
    }

    fun cancelAutoBackups() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
