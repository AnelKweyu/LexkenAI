package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.model.FileInput
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A picked file as the multipart part the backend's serializers read by [name] (`avatar`, `logo`, `document`…). */
fun FileInput.toPart(name: String): MultipartBody.Part =
    MultipartBody.Part.createFormData(name, this.name, bytes.toRequestBody(mimeType?.toMediaTypeOrNull()))

/** A plain text field of a multipart body. */
fun String.toTextPart(): RequestBody = toRequestBody("text/plain".toMediaTypeOrNull())
