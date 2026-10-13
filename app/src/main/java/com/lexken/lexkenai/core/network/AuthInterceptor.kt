package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/** Adds `Authorization: Bearer …` when a token exists. Endpoints opt out with the [NO_AUTH_HEADER] header. */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(NO_AUTH_HEADER) != null) {
            return chain.proceed(original.newBuilder().removeHeader(NO_AUTH_HEADER).build())
        }
        val token = runBlocking { tokenStore.current() }?.accessToken
            ?: return chain.proceed(original)
        return chain.proceed(
            original.newBuilder().header("Authorization", "Bearer $token").build(),
        )
    }

    companion object {
        const val NO_AUTH_HEADER = "X-LexKen-No-Auth"
    }
}
