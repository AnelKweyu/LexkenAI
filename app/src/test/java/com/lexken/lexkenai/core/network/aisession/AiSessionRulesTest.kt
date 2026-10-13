package com.lexken.lexkenai.core.network.aisession

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** httpService.jsx rules 10 and 11 of the auth appendix, clause by clause. */
class AiSessionRulesTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "/ai/lawris-ai/",
            "/api/ai/lawris-ai/",
            "ai/lawris-ai",
            "/ai/lawris-ai/?stream=1",
            "/ai/generate-documents/",
            "/ai/submission-analyzer/case-selection/",
            "/api/ai/contractual-analysis/8c2f/patches/1/accept/",
        ],
    )
    fun `r11 the four prefixes protect with or without the api prefix, slash or query`(path: String) {
        assertThat(AiSessionRules.isProtected(path)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/ai/session/",
            "/ai/legal-options/",
            "/ai/lawris-ai-v2/",
            "/api/api/playbooks/",
            "/users/profile/nav-info/",
            "/ai/case-brief/lookup/",
        ],
    )
    fun `r11 everything else is unprotected`(path: String) {
        assertThat(AiSessionRules.isProtected(path)).isFalse()
    }

    @Test
    fun `r11 normalise keeps the doubled api prefix family intact`() {
        assertThat(AiSessionRules.normalise("/api/api/playbooks/")).isEqualTo("/api/playbooks/")
        assertThat(AiSessionRules.normalise("ai/lawris-ai")).isEqualTo("/ai/lawris-ai/")
        assertThat(AiSessionRules.normalise("/api")).isEqualTo("/")
    }

    @Test
    fun `r10 a challenge code in code, error_code or detail-code marks a 401 or 403`() {
        assertThat(AiSessionRules.isChallenge(403, """{"code":"ai_session_required","message":"Authentication failed."}""")).isTrue()
        assertThat(AiSessionRules.isChallenge(401, """{"error_code":"turnstile_required"}""")).isTrue()
        assertThat(AiSessionRules.isChallenge(403, """{"detail":{"code":"ai_session_required"}}""")).isTrue()
    }

    @Test
    fun `r10 the three phrases in message, detail or error mark a challenge too`() {
        assertThat(AiSessionRules.isChallenge(403, """{"message":"Turnstile verification failed"}""")).isTrue()
        assertThat(AiSessionRules.isChallenge(403, """{"detail":"AI session expired"}""")).isTrue()
        assertThat(AiSessionRules.isChallenge(401, """{"error":"authentication failed."}""")).isTrue()
    }

    @Test
    fun `r10 a credits 403, a plain 401, a 400 and a non-JSON body are not challenges`() {
        assertThat(AiSessionRules.isChallenge(403, """{"error_code":"INSUFFICIENT_AI_CREDITS","detail":"Insufficient credits"}""")).isFalse()
        assertThat(AiSessionRules.isChallenge(401, """{"detail":"Given token not valid for any token type"}""")).isFalse()
        assertThat(AiSessionRules.isChallenge(400, """{"code":"ai_session_required"}""")).isFalse()
        assertThat(AiSessionRules.isChallenge(403, "<html>Cloudflare</html>")).isFalse()
        assertThat(AiSessionRules.isChallenge(403, null)).isFalse()
    }

    @Test
    fun `r11 expiry is now plus expires_in minus five seconds, ten minutes without expires_in`() {
        assertThat(AiSessionRules.expiresAt(1_000_000L, 600)).isEqualTo(1_000_000L + 600_000L - 5_000L)
        assertThat(AiSessionRules.expiresAt(1_000_000L, null)).isEqualTo(1_000_000L + 10 * 60_000L - 5_000L)
        assertThat(AiSessionRules.expiresAt(1_000_000L, 0)).isEqualTo(1_000_000L + 10 * 60_000L - 5_000L)
    }

    @Test
    fun `r11 the mint backs off three hundred milliseconds per attempt, three attempts at most`() {
        assertThat(AiSessionRules.mintRetryDelayMillis(1)).isEqualTo(300)
        assertThat(AiSessionRules.mintRetryDelayMillis(3)).isEqualTo(900)
        assertThat(AiSessionRules.MAX_RETRIES).isEqualTo(3)
    }
}
