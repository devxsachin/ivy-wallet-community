package com.ivy.data.backup.drive

import androidx.annotation.Keep
import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/**
 * A minimal Google Drive REST v3 client covering only what backups need:
 * finding a file by name and creating/updating its content.
 */
class GoogleDriveClient @Inject constructor(
    private val ktorClient: dagger.Lazy<HttpClient>,
    private val json: Json,
) {
    companion object {
        private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val JSON_MIME_TYPE = "application/json"
        private const val CRLF = "\r\n"
        private const val MAX_ERROR_BODY_LENGTH = 200
    }

    /**
     * Creates [fileName] in the user's Google Drive with [content],
     * or overwrites its content if the file already exists.
     */
    suspend fun uploadFile(
        accessToken: String,
        fileName: String,
        content: String,
    ): Either<String, Unit> = either {
        val existingFileId = findFileId(accessToken, fileName).bind()
        if (existingFileId != null) {
            updateFile(accessToken, existingFileId, content).bind()
        } else {
            createFile(accessToken, fileName, content).bind()
        }
    }

    private suspend fun findFileId(
        accessToken: String,
        fileName: String,
    ): Either<String, String?> = catch({
        val response = ktorClient.get().get(DRIVE_FILES_URL) {
            bearerAuth(accessToken)
            parameter("q", "name = '$fileName' and trashed = false")
            parameter("spaces", "drive")
            parameter("fields", "files(id)")
        }
        if (response.status.isSuccess()) {
            Either.Right(response.body<GoogleDriveFilesResponse>().files.firstOrNull()?.fileId)
        } else {
            Either.Left(response.errorMessage("Searching for the backup file failed"))
        }
    }) { e ->
        Either.Left(e.message ?: "Searching for the backup file failed.")
    }

    private suspend fun createFile(
        accessToken: String,
        fileName: String,
        content: String,
    ): Either<String, Unit> = catch({
        val boundary = "ivy-wallet-${UUID.randomUUID()}"
        val metadataJson = json.encodeToString(
            GoogleDriveFileMetadata(name = fileName, mimeType = JSON_MIME_TYPE)
        )
        val multipartBody = buildString {
            append("--").append(boundary).append(CRLF)
            append("Content-Type: $JSON_MIME_TYPE; charset=UTF-8").append(CRLF).append(CRLF)
            append(metadataJson).append(CRLF)
            append("--").append(boundary).append(CRLF)
            append("Content-Type: $JSON_MIME_TYPE").append(CRLF).append(CRLF)
            append(content).append(CRLF)
            append("--").append(boundary).append("--")
        }
        val response = ktorClient.get().post(DRIVE_UPLOAD_URL) {
            parameter("uploadType", "multipart")
            bearerAuth(accessToken)
            setBody(
                TextContent(
                    text = multipartBody,
                    contentType = ContentType("multipart", "related")
                        .withParameter("boundary", boundary),
                )
            )
        }
        response.toUploadResult("Creating the backup file failed")
    }) { e ->
        Either.Left(e.message ?: "Creating the backup file failed.")
    }

    private suspend fun updateFile(
        accessToken: String,
        fileId: String,
        content: String,
    ): Either<String, Unit> = catch({
        val response = ktorClient.get().patch("$DRIVE_UPLOAD_URL/$fileId") {
            parameter("uploadType", "media")
            bearerAuth(accessToken)
            setBody(
                TextContent(
                    text = content,
                    contentType = ContentType.Application.Json,
                )
            )
        }
        response.toUploadResult("Updating the backup file failed")
    }) { e ->
        Either.Left(e.message ?: "Updating the backup file failed.")
    }

    private suspend fun HttpResponse.toUploadResult(operation: String): Either<String, Unit> {
        return if (status.isSuccess()) {
            Either.Right(Unit)
        } else {
            Either.Left(errorMessage(operation))
        }
    }

    private suspend fun HttpResponse.errorMessage(operation: String): String {
        val errorBody = bodyAsText().take(MAX_ERROR_BODY_LENGTH)
        return "$operation: HTTP ${status.value} $errorBody"
    }
}

@Keep
@Serializable
class GoogleDriveFilesResponse(
    val files: List<GoogleDriveFile>
)

@Keep
@Serializable
class GoogleDriveFile(
    @SerialName("id")
    val fileId: String
)

@Keep
@Serializable
class GoogleDriveFileMetadata(
    val name: String,
    val mimeType: String
)
