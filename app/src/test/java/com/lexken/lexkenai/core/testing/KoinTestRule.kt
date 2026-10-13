package com.lexken.lexkenai.core.testing

import com.lexken.lexkenai.core.auth.AuthTokens
import com.lexken.lexkenai.core.auth.InMemoryTokenStore
import com.lexken.lexkenai.core.auth.TokenStore
import com.lexken.lexkenai.core.auth.VfConfig
import com.lexken.lexkenai.core.auth.authModule
import com.lexken.lexkenai.core.common.Clock
import com.lexken.lexkenai.core.network.NetworkQualifiers
import com.lexken.lexkenai.core.network.aisession.DeviceIdStore
import com.lexken.lexkenai.core.network.aisession.InMemoryDeviceIdStore
import com.lexken.lexkenai.core.network.networkModule
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The real Koin graph (`networkModule` + `authModule` + the feature modules under test) pointed
 * at a [ContractServer], with the platform-bound pieces swapped for their in-memory twins:
 * tokens, the device id and the clock. Hermetic end-to-end journeys (rung T8 of the parity
 * plan) resolve use cases from here exactly as the app does, so wiring mistakes fail in CI.
 *
 * ```
 * private val server = ContractServer()
 * @RegisterExtension val koin = KoinTestRule(server)
 * ```
 * The server is started and stopped with each test; register its fixtures inside the test.
 */
class KoinTestRule(
    private val server: ContractServer,
    private val modules: List<Module> = emptyList(),
    initialTokens: AuthTokens? = null,
    val deviceId: String = "device-7f3a",
) : BeforeEachCallback, AfterEachCallback {
    val tokenStore: TokenStore = InMemoryTokenStore(initialTokens)
    val clock = FakeClock()
    val vfConfig = VfConfig(
        baseUrl = "set at start",
        clientId = "client-id",
        clientSecret = "client-secret",
        bypassKey = "bypass-key",
        bundleId = "com.lexken.mobile",
    )

    private var application: KoinApplication? = null

    val koin: Koin get() = application?.koin ?: error("Koin is only available inside a test")

    inline fun <reified T : Any> get(): T = koin.get()

    override fun beforeEach(context: ExtensionContext) {
        server.start()
        val overrides = module {
            single(NetworkQualifiers.BASE_URL) { server.lawrisBaseUrl }
            single(NetworkQualifiers.DEBUG_LOGGING) { false }
            single { vfConfig.copy(baseUrl = server.identityBaseUrl) }
            single<TokenStore> { tokenStore }
            single<DeviceIdStore> { InMemoryDeviceIdStore(deviceId) }
            single<Clock> { clock }
        }
        // Later definitions win, so the overrides come after the production modules.
        application = koinApplication {
            modules(networkModule, authModule, overrides, *modules.toTypedArray())
        }
    }

    override fun afterEach(context: ExtensionContext) {
        application?.close()
        application = null
        server.close()
    }
}
