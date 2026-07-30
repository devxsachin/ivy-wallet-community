package com.ivy.data.backup.drive

import android.app.PendingIntent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import arrow.core.Either
import com.ivy.base.TestDispatchersProvider
import com.ivy.base.time.TimeProvider
import com.ivy.data.backup.BackupDataUseCase
import com.ivy.data.datastore.DatastoreKeys
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class GoogleDriveBackupTest {
    private val preferencesState = MutableStateFlow(emptyPreferences())
    private val dataStore = mockk<DataStore<Preferences>> {
        every { data } returns preferencesState
        coEvery { updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            val updated = transform(preferencesState.value)
            preferencesState.value = updated
            updated
        }
    }

    private val authorizer = mockk<GoogleDriveAuthorizer>()
    private val client = mockk<GoogleDriveClient>()
    private val backupDataUseCase = mockk<BackupDataUseCase>()
    private val scheduler = mockk<GoogleDriveAutoBackupScheduler>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>()

    private val driveBackup = GoogleDriveBackup(
        authorizer = authorizer,
        client = client,
        backupDataUseCase = backupDataUseCase,
        scheduler = scheduler,
        dataStore = dataStore,
        dispatchersProvider = TestDispatchersProvider,
        timeProvider = timeProvider,
    )

    @Test
    fun `backupNow - uploads the backup and stores the last backup time`() = runTest {
        // given
        coEvery {
            authorizer.authorize()
        } returns Either.Right(GoogleDriveAuthResult.Authorized("token"))
        coEvery { backupDataUseCase.generateJsonBackup() } returns "{}"
        coEvery {
            client.uploadFile(
                accessToken = "token",
                fileName = GoogleDriveBackup.BACKUP_FILE_NAME,
                content = "{}",
            )
        } returns Either.Right(Unit)
        every { timeProvider.utcNow() } returns Instant.ofEpochSecond(42)

        // when
        val result = driveBackup.backupNow()

        // then
        result shouldBe Either.Right(Unit)
        preferencesState.value[DatastoreKeys.DRIVE_LAST_BACKUP_EPOCH_SEC] shouldBe 42L
        driveBackup.lastBackupTime.first() shouldBe Instant.ofEpochSecond(42)
    }

    @Test
    fun `backupNow - fails when the user hasn't granted Drive access`() = runTest {
        // given
        coEvery {
            authorizer.authorize()
        } returns Either.Right(GoogleDriveAuthResult.ConsentRequired(mockk()))

        // when
        val result = driveBackup.backupNow()

        // then
        result shouldBe Either.Left(GoogleDriveBackup.Error.NotAuthorized)
        coVerify(exactly = 0) { client.uploadFile(any(), any(), any()) }
        preferencesState.value[DatastoreKeys.DRIVE_LAST_BACKUP_EPOCH_SEC] shouldBe null
    }

    @Test
    fun `backupNow - fails when the upload fails`() = runTest {
        // given
        coEvery {
            authorizer.authorize()
        } returns Either.Right(GoogleDriveAuthResult.Authorized("token"))
        coEvery { backupDataUseCase.generateJsonBackup() } returns "{}"
        coEvery {
            client.uploadFile(any(), any(), any())
        } returns Either.Left("HTTP 500")

        // when
        val result = driveBackup.backupNow()

        // then
        result shouldBe Either.Left(GoogleDriveBackup.Error.Upload("HTTP 500"))
        preferencesState.value[DatastoreKeys.DRIVE_LAST_BACKUP_EPOCH_SEC] shouldBe null
    }

    @Test
    fun `enable - schedules auto-backups when already authorized`() = runTest {
        // given
        coEvery {
            authorizer.authorize()
        } returns Either.Right(GoogleDriveAuthResult.Authorized("token"))

        // when
        val result = driveBackup.enable()

        // then
        result shouldBe GoogleDriveBackup.EnableResult.Enabled
        driveBackup.enabled.first() shouldBe true
        verify(exactly = 1) { scheduler.scheduleAutoBackups() }
    }

    @Test
    fun `enable - requires consent when Drive access isn't granted yet`() = runTest {
        // given
        val consent = mockk<PendingIntent>()
        coEvery {
            authorizer.authorize()
        } returns Either.Right(GoogleDriveAuthResult.ConsentRequired(consent))

        // when
        val result = driveBackup.enable()

        // then
        result shouldBe GoogleDriveBackup.EnableResult.ConsentRequired(consent)
        driveBackup.enabled.first() shouldBe false
        verify(exactly = 0) { scheduler.scheduleAutoBackups() }
    }

    @Test
    fun `enable - fails when authorization fails`() = runTest {
        // given
        coEvery { authorizer.authorize() } returns Either.Left("No Google account")

        // when
        val result = driveBackup.enable()

        // then
        result shouldBe GoogleDriveBackup.EnableResult.Failed("No Google account")
        driveBackup.enabled.first() shouldBe false
        verify(exactly = 0) { scheduler.scheduleAutoBackups() }
    }

    @Test
    fun `disable - cancels auto-backups`() = runTest {
        // given
        coEvery {
            authorizer.authorize()
        } returns Either.Right(GoogleDriveAuthResult.Authorized("token"))
        driveBackup.enable()

        // when
        driveBackup.disable()

        // then
        driveBackup.enabled.first() shouldBe false
        verify(exactly = 1) { scheduler.cancelAutoBackups() }
    }
}
