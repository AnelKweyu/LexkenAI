package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * Runs a network [block] and converts every expected failure into a typed [DataError.Network].
 * [CancellationException] is always rethrown so structured concurrency is preserved.
 */
suspend inline fun <T> safeCall(crossinline block: suspend () -> T): Result<T, DataError.Network> {
    return try {
        Result.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        coroutineContext.ensureActive()
        Result.Failure(e.toNetworkError())
    }
}

fun Throwable.toNetworkError(): DataError.Network = when (this) {
    is HttpException -> code().toNetworkError()
    is SocketTimeoutException -> DataError.Network.REQUEST_TIMEOUT
    is UnknownHostException -> DataError.Network.NO_INTERNET
    is SerializationException -> DataError.Network.SERIALIZATION
    is IOException -> DataError.Network.NO_INTERNET
    else -> DataError.Network.UNKNOWN
}

fun Int.toNetworkError(): DataError.Network = when (this) {
    401 -> DataError.Network.UNAUTHORIZED
    403 -> DataError.Network.FORBIDDEN
    404 -> DataError.Network.NOT_FOUND
    408 -> DataError.Network.REQUEST_TIMEOUT
    409 -> DataError.Network.CONFLICT
    413 -> DataError.Network.PAYLOAD_TOO_LARGE
    429 -> DataError.Network.TOO_MANY_REQUESTS
    in 500..599 -> DataError.Network.SERVER_ERROR
    else -> DataError.Network.UNKNOWN
}
