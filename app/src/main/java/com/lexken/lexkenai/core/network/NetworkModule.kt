package com.lexken.lexkenai.core.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Qualifiers for values the app module supplies from BuildConfig. */
object NetworkQualifiers {
    val BASE_URL = named("api.baseUrl")
    val DEBUG_LOGGING = named("api.debugLogging")

    /** No bearer, no authenticator: the identity host and the exchange, so renewal never recurses. */
    val PLAIN_CLIENT = named("network.plainClient")

    /** The authed client plus WebSocket keep-alive pings (control frames, never JSON). */
    val SOCKET_CLIENT = named("network.socketClient")
}

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * The tenant-host client stack. Every request carries OkHttp's own `User-Agent`
 * (`okhttp/<version>`): Cloudflare answers `error code: 1010` to generic agents on both hosts,
 * and the OkHttp default is the one the backends have been probed with — do not override it.
 *
 * Requires `String` under [NetworkQualifiers.BASE_URL], `Boolean` under
 * [NetworkQualifiers.DEBUG_LOGGING], a `TokenStore` and a `SessionRenewer` (the auth module).
 */
val networkModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }

    single { AuthInterceptor(get()) }

    single(NetworkQualifiers.PLAIN_CLIENT) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    single {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(get<AuthInterceptor>())
            .authenticator(TokenAuthenticator(get(), get()))
        if (get<Boolean>(NetworkQualifiers.DEBUG_LOGGING)) {
            builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        builder.build()
    }

    single(NetworkQualifiers.SOCKET_CLIENT) {
        get<OkHttpClient>().newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    single {
        Retrofit.Builder()
            .baseUrl(get<String>(NetworkQualifiers.BASE_URL))
            .client(get())
            .addConverterFactory(get<Json>().asConverterFactory(JSON_MEDIA_TYPE))
            .build()
    }

    single { PollingEngine() }
    single<SocketClient> { OkHttpSocketClient(get(NetworkQualifiers.SOCKET_CLIENT)) }
    single { JobTracker(get(), get()) }
}
