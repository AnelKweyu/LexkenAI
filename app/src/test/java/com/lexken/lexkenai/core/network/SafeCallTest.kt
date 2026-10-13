package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SafeCallTest {

    @Test
    fun `wraps success`() = runTest {
        assertThat(safeCall { 42 }).isEqualTo(Result.Success(42))
    }

    @Test
    fun `rethrows cancellation`() = runTest {
        assertThrows<CancellationException> { safeCall<Int> { throw CancellationException("cancelled") } }
    }

    @ParameterizedTest
    @CsvSource(
        "401, UNAUTHORIZED",
        "403, FORBIDDEN",
        "404, NOT_FOUND",
        "408, REQUEST_TIMEOUT",
        "409, CONFLICT",
        "413, PAYLOAD_TOO_LARGE",
        "429, TOO_MANY_REQUESTS",
        "500, SERVER_ERROR",
        "503, SERVER_ERROR",
        "418, UNKNOWN",
    )
    fun `maps http codes`(code: Int, expected: DataError.Network) = runTest {
        val result = safeCall<Unit> {
            throw HttpException(Response.error<Unit>(code, "".toResponseBody()))
        }
        assertThat(result).isEqualTo(Result.Failure(expected))
    }

    @Test
    fun `maps io and serialization failures`() = runTest {
        assertThat(safeCall<Unit> { throw SocketTimeoutException() })
            .isEqualTo(Result.Failure(DataError.Network.REQUEST_TIMEOUT))
        assertThat(safeCall<Unit> { throw UnknownHostException() })
            .isEqualTo(Result.Failure(DataError.Network.NO_INTERNET))
        assertThat(safeCall<Unit> { throw IOException("reset") })
            .isEqualTo(Result.Failure(DataError.Network.NO_INTERNET))
        assertThat(safeCall<Unit> { throw SerializationException("bad json") })
            .isEqualTo(Result.Failure(DataError.Network.SERIALIZATION))
        assertThat(safeCall<Unit> { throw IllegalStateException() })
            .isEqualTo(Result.Failure(DataError.Network.UNKNOWN))
    }
}
