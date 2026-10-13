package com.lexken.lexkenai.core.network

import com.lexken.lexkenai.core.common.Clock
import com.lexken.lexkenai.core.network.aisession.AiSessionApi
import com.lexken.lexkenai.core.network.aisession.AiSessionCache
import com.lexken.lexkenai.core.network.aisession.AiSessionInterceptor
import com.lexken.lexkenai.core.network.aisession.AiSessionMinter
import com.lexken.lexkenai.core.network.aisession.RetrofitAiSessionMinter
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/** Qualifiers for values the app module supplies from BuildConfig. */
object NetworkQualifiers {
    val BASE_URL = named("api.baseUrl")
    val DEBUG_LOGGING = named("api.debugLogging")

    /** No bearer, no authenticator: the identity host and the exchange, so renewal never recurses. */
    val PLAIN_CLIENT = named("network.plainClient")

    /** The authed client plus WebSocket keep-alive pings (control frames, never JSON). */
    val SOCKET_CLIENT = named("network.socketClient")

    /** Retrofit on the plain client against the tenant base — the AI-session mint and the exchange. */
    val PLAIN_RETROFIT = named("network.plainRetrofit")
}

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * The tenant-host client stack. Every request carries OkHttp's own `User-Agent`
 * (`okhttp/<version>`): Cloudflare answers `error code: 1010` to generic agents on both hosts,
 * and the OkHttp default is the one the backends have been probed with — do not override it.
 *
 * Requires `String` under [NetworkQualifiers.BASE_URL], `Boolean` under
 * [NetworkQualifiers.DEBUG_LOGGING], a `TokenStore` and a `SessionRenewer` (the auth module),
 * and a `DeviceIdStore` (the app's platform module).
 */
val networkModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }

    single<Clock> { Clock.SYSTEM }

    single { AuthInterceptor(get()) }

    single(NetworkQualifiers.PLAIN_CLIENT) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    single(NetworkQualifiers.PLAIN_RETROFIT) {
        Retrofit.Builder()
            .baseUrl(get<String>(NetworkQualifiers.BASE_URL))
            .client(get<OkHttpClient>(NetworkQualifiers.PLAIN_CLIENT))
            .addConverterFactory(get<Json>().asConverterFactory(JSON_MEDIA_TYPE))
            .build()
    }

    // The AI-session rail (§0.3 of the parity plan): mint on the plain client, attach + replay on the authed one.
    single<AiSessionApi> { get<Retrofit>(NetworkQualifiers.PLAIN_RETROFIT).create() }
    single<AiSessionMinter> { RetrofitAiSessionMinter(api = get(), tokenStore = get(), clock = get()) }
    single { AiSessionCache(minter = get(), clock = get()) }
    single { AiSessionInterceptor(cache = get(), deviceIds = get()) }

    single {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(get<AuthInterceptor>())
            .addInterceptor(get<AiSessionInterceptor>())
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
