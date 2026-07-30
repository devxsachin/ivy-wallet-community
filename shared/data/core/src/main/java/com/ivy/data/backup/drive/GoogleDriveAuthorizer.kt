package com.ivy.data.backup.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import arrow.core.Either
import arrow.core.raise.catch
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Authorizes access to the user's Google Drive via Google Identity Services.
 *
 * Requests only the "drive.file" scope, which limits the app's access
 * to files that the app itself has created.
 */
class GoogleDriveAuthorizer @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {
    companion object {
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    /**
     * Returns an access token if the user has already granted access, or a
     * [GoogleDriveAuthResult.ConsentRequired] whose [PendingIntent] must be
     * launched from the UI to ask for the user's consent.
     */
    suspend fun authorize(): Either<String, GoogleDriveAuthResult> = catch({
        val result = Identity.getAuthorizationClient(context)
            .authorize(authorizationRequest())
            .await()
        result.toAuthResult()
    }) { e ->
        Either.Left(e.message ?: "Google Drive authorization failed.")
    }

    /**
     * Extracts the authorization outcome from the [Intent] returned by
     * the consent screen launched via [GoogleDriveAuthResult.ConsentRequired].
     */
    fun authResultFromIntent(consentIntent: Intent): Either<String, GoogleDriveAuthResult> =
        catch({
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(consentIntent)
                .toAuthResult()
        }) { e ->
            Either.Left(e.message ?: "Google Drive authorization was declined.")
        }

    private fun authorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
    }

    private fun AuthorizationResult.toAuthResult(): Either<String, GoogleDriveAuthResult> {
        val consent = pendingIntent
        val token = accessToken
        return when {
            hasResolution() && consent != null ->
                Either.Right(GoogleDriveAuthResult.ConsentRequired(consent))

            token != null -> Either.Right(GoogleDriveAuthResult.Authorized(token))

            else -> Either.Left("Google Drive authorization didn't return an access token.")
        }
    }
}

sealed interface GoogleDriveAuthResult {
    data class Authorized(val accessToken: String) : GoogleDriveAuthResult
    data class ConsentRequired(val pendingIntent: PendingIntent) : GoogleDriveAuthResult
}
