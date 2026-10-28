package com.controlresell.sdk

import com.controlresell.sdk.generated.models.FileUpload
import com.controlresell.sdk.generated.models.FileUploadRequest
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.uuid.Uuid

/**
 * Registers a file (jpeg, png, webp, heic or heif, at most 20 MiB) and sends its bytes: an HTTP `PUT` of [data] to the
 * returned address, with the same [contentType] and no API key, within the 30 minutes the address is valid. Returns the
 * registration: put its `id` in `photoIds` of an item. The `PUT` is retried like any call; a refused one is thrown as a
 * [ControlResellError] (the file stays `PENDING`). [idempotencyKey] is the key of the registration, kept across its retries.
 */
suspend fun ControlResell.uploadFile(
    data: ByteArray,
    filename: String,
    contentType: String,
    idempotencyKey: String = Uuid.random().toString(),
): FileUpload {
    val upload = files.uploadFile(idempotencyKey, FileUploadRequest(filename = filename, contentType = contentType, propertySize = data.size.toLong())).body()
    require(upload.uploadUrl.isNotBlank()) { "The API returned no upload address for file ${upload.id}." }
    httpClient.put(upload.uploadUrl) {
        contentType(ContentType.parse(contentType))
        setBody(data)
    }
    return upload
}
