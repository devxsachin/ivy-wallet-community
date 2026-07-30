package com.ivy.data.backup.drive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

@HiltWorker
class GoogleDriveBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val googleDriveBackup: GoogleDriveBackup,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val MAX_RETRIES = 10
    }

    override suspend fun doWork(): Result {
        if (!googleDriveBackup.enabled.first()) {
            return Result.success()
        }

        return googleDriveBackup.backupNow().fold(
            ifLeft = { error ->
                Timber.e("Google Drive auto-backup failed: ${error.humanReadable}")
                when {
                    error is GoogleDriveBackup.Error.NotAuthorized -> Result.failure()
                    runAttemptCount <= MAX_RETRIES -> Result.retry()
                    else -> Result.failure()
                }
            },
            ifRight = { Result.success() },
        )
    }
}
