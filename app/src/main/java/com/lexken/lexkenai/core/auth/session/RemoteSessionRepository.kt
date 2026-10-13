package com.lexken.lexkenai.core.auth.session

import com.lexken.lexkenai.core.auth.NoOpSessionRevoker
import com.lexken.lexkenai.core.auth.SessionRevoker
import com.lexken.lexkenai.core.auth.TokenStore
import com.lexken.lexkenai.core.model.Credits
import com.lexken.lexkenai.core.model.User
import com.lexken.lexkenai.core.model.UserId
import com.lexken.lexkenai.core.model.UserRole
import com.lexken.lexkenai.core.network.safeCall
import com.lexken.lexkenai.core.result.DataError
import com.lexken.lexkenai.core.result.EmptyResult
import com.lexken.lexkenai.core.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/** The tenant-host reads that define a signed-in session. Confirmed live for mobile principals. */
interface SessionApi {
    @GET("users/profile/nav-info/")
    suspend fun navInfo(): NavInfoDto

    @GET("users/lawfirm-profile/")
    suspend fun lawfirmProfile(): LawfirmProfileDto

    @GET("sub/ai-credits-balance/")
    suspend fun creditsBalance(): CreditsBalanceDto

    /** Only `subscription.is_expired` is read here; the billing feature owns the rest. */
    @GET("sub/current/")
    suspend fun subscription(): SubscriptionEnvelopeDto
}

@Serializable
data class SubscriptionEnvelopeDto(val subscription: SubscriptionFlagsDto? = null)

@Serializable
data class SubscriptionFlagsDto(
    @SerialName("is_expired") val isExpired: Boolean? = null,
)

@Serializable
data class LawfirmProfileDto(
    @SerialName("lawfirm_name") val lawfirmName: String? = null,
)

@Serializable
data class NavInfoDto(
    @SerialName("full_name") val fullName: String,
    val email: String,
    @SerialName("role_name") val roleName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class CreditsBalanceDto(
    @SerialName("personal_ai_credits_balance") val personal: Int = 0,
    @SerialName("subscription_ai_credits_balance") val subscription: Int = 0,
    @SerialName("subscription_plan") val planName: String? = null,
)

/**
 * Session backed by the tenant host: nav-info + credits balance define SignedIn.
 * A rejected token (after the authenticator's one renewal) is a state, not an error —
 * the store is cleared and the app roots to sign-in.
 */
class RemoteSessionRepository(
    private val api: SessionApi,
    private val tokenStore: TokenStore,
    private val revoker: SessionRevoker = NoOpSessionRevoker,
    /**
     * Sign-out's server call outlives the screen that asked for it: the profile leaves
     * composition the moment the session flips, and a cancelled call would leave the tokens
     * live on the server. Tests pass their own scope.
     */
    private val revokeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionRepository {
    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)

    override val state: Flow<SessionState> = _state.onStart {
        if (_state.value == SessionState.Unknown) refresh()
    }

    override suspend fun refresh(): Result<SessionState, DataError> {
        if (tokenStore.current() == null) {
            return Result.Success(SessionState.SignedOut.also { _state.value = it })
        }
        val fetched = safeCall {
            coroutineScope {
                val nav = async { api.navInfo() }
                val credits = async { api.creditsBalance() }
                // The firm name is decoration, not identity — its failure never fails the session.
                val firm = async { runCatching { api.lawfirmProfile().lawfirmName }.getOrNull() }
                // Likewise the expiry flag: a 404 or a slow billing read must not sign the user out.
                val expired = async { runCatching { api.subscription().subscription?.isExpired == true }.getOrDefault(false) }
                SessionReads(nav.await(), credits.await(), firm.await(), expired.await())
            }
        }
        return when (fetched) {
            is Result.Success -> {
                val (nav, credits, firmName, expired) = fetched.data
                val signedIn = SessionState.SignedIn(
                    user = User(
                        id = UserId(nav.email),
                        fullName = nav.fullName,
                        email = nav.email,
                        role = nav.roleName.toUserRole(),
                        firmName = firmName?.takeIf { it.isNotBlank() },
                    ),
                    credits = Credits((credits.personal + credits.subscription).coerceAtLeast(0)),
                    subscriptionExpired = expired,
                )
                _state.value = signedIn
                Result.Success(signedIn)
            }

            is Result.Failure -> {
                when (fetched.error) {
                    DataError.Network.UNAUTHORIZED -> {
                        tokenStore.clear()
                        SessionExpiryNotice.arm()
                        Result.Success(SessionState.SignedOut.also { _state.value = it })
                    }

                    else -> {
                        Result.Failure(fetched.error)
                    }
                }
            }
        }
    }

    override suspend fun signOut(): EmptyResult<DataError> {
        // Local wipe first: the session flips and the app re-roots before any network happens,
        // so a slow or refused revocation never holds the user on a signed-in screen. The
        // revoker then invalidates the tokens on the server, best-effort, off this call's scope.
        // A deliberate sign-out never arms the expiry notice.
        val tokens = tokenStore.current()
        tokenStore.clear()
        _state.value = SessionState.SignedOut
        if (tokens != null) {
            revokeScope.launch { runCatching { revoker.revoke(tokens) } }
        }
        return Result.Success(Unit)
    }
}

private data class SessionReads(val nav: NavInfoDto, val credits: CreditsBalanceDto, val firmName: String?, val expired: Boolean)

private fun String?.toUserRole(): UserRole = when (this?.lowercase()) {
    "admin" -> UserRole.ADMIN
    "advocate", "lawyer" -> UserRole.ADVOCATE
    "public" -> UserRole.PUBLIC
    else -> UserRole.STAFF
}
