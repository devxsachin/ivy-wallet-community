package com.ivy.data.backup.drive

import android.app.PendingIntent
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import arrow.core.Either
import arrow.core.raise.either
import com.ivy.base.threading.DispatchersProvider
import com.ivy.base.time.TimeProvider
import com.ivy.data.backup.BackupDataUseCase
import com.ivy.data.datastore.DatastoreKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

/**
 * Automatic backups of the user's data to their Google Drive.
 *
 * While enabled, [GoogleDriveBackupWorker] periodically uploads the full
 * data backup JSON to a single [BACKUP_FILE_NAME] file in the user's Drive,
 * overwriting the previous backup.
 */
class GoogleDriveBackup @Inject constructor(
    private val authorizer: GoogleDriveAuthorizer,
    private val client: GoogleDriveClient,
    private val backupDataUseCase: BackupDataUseCase,
    private val scheduler: GoogleDriveAutoBackupScheduler,
    private val dataStore: DataStore<Preferences>,
    private val dispatchersProvider: DispatchersProvider,
    private val timeProvider: TimeProvider,
) {
    companion object {
        const val BACKUP_FILE_NAME = "Ivy-Wallet-Backup.json"
    }

    val enabled: Flow<Boolean> = dataStore.data
        .map { it[DatastoreKeys.DRIVE_AUTO_BACKUP_ENABLED] ?: false }
        .distinctUntilChanged()

    val lastBackupTime: Flow<Instant?> = dataStore.data
        .map { preferences ->
            preferences[DatastoreKeys.DRIVE_LAST_BACKUP_EPOCH_SEC]
                ?.let(Instant::ofEpochSecond)
        }
        .distinctUntilChanged()

    /**
     * Turns auto-backups on. If the user hasn't granted Google Drive access yet,
     * returns [EnableResult.ConsentRequired] - launch its [PendingIntent] and pass
     * the resulting [Intent] to [finishEnable] to complete the process.
     */
    suspend fun enable(): EnableResult {
        return authorizer.authorize().fold(
            ifLeft = { EnableResult.Failed(it) },
            ifRight = { auth ->
                when (auth) {
                    is GoogleDriveAuthResult.Authorized -> {
                        activate()
                        EnableResult.Enabled
                    }

                    is GoogleDriveAuthResult.ConsentRequired ->
                        EnableResult.ConsentRequired(auth.pendingIntent)
                }
            }
        )
    }

    suspend fun finishEnable(consentIntent: Intent): EnableResult {
        return authorizer.authResultFromIntent(consentIntent).fold(
            ifLeft = { EnableResult.Failed(it) },
            ifRight = { auth ->
                when (auth) {
                    is GoogleDriveAuthResult.Authorized -> {
                        activate()
                        EnableResult.Enabled
                    }

                    is GoogleDriveAuthResult.ConsentRequired ->
                        EnableResult.Failed("Google Drive authorization was not granted.")
                }
            }
        )
    }

    suspend fun disable() {
        scheduler.cancelAutoBackups()
        dataStore.edit { it[DatastoreKeys.DRIVE_AUTO_BACKUP_ENABLED] = false }
    }

    suspend fun backupNow(): Either<Error, Unit> = withContext(dispatchersProvider.io) {
        either {
            val token = when (val auth = authorizer.authorize().mapLeft(Error::Auth).bind()) {
                is GoogleDriveAuthResult.Authorized -> auth.accessToken
                is GoogleDriveAuthResult.ConsentRequired -> raise(Error.NotAuthorized)
            }
            val backupJson = backupDataUseCase.generateJsonBackup()
            client.uploadFile(
                accessToken = token,
                fileName = BACKUP_FILE_NAME,
                content = backupJson,
            ).mapLeft(Error::Upload).bind()
            dataStore.edit {
                it[DatastoreKeys.DRIVE_LAST_BACKUP_EPOCH_SEC] = timeProvider.utcNow().epochSecond
            }
        }
    }

    private suspend fun activate() {
        dataStore.edit { it[DatastoreKeys.DRIVE_AUTO_BACKUP_ENABLED] = true }
        scheduler.scheduleAutoBackups()
    }

    sealed interface EnableResult {
        data object Enabled : EnableResult
        data class ConsentRequired(val pendingIntent: PendingIntent) : EnableResult
        data class Failed(val reason: String) : EnableResult
    }

    sealed interface Error {
        val humanReadable: String

        data object NotAuthorized : Error {
            override val humanReadable: String
                get() = "Google Drive authorization is required."
        }

        data class Auth(val error: String) : Error {
            override val humanReadable: String
                get() = "Google Drive authorization failed: $error"
        }

        data class Upload(val error: String) : Error {
            override val humanReadable: String
                get() = "Uploading to Google Drive failed: $error"
        }
    }
}
