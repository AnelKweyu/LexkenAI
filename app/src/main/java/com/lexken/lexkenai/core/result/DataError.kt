package com.lexken.lexkenai.core.result

/** Errors that originate in the data layer. Features add their own [Error] types for domain rules. */
sealed interface DataError : Error {
    enum class Network : DataError {
        NO_INTERNET,
        REQUEST_TIMEOUT,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        TOO_MANY_REQUESTS,
        PAYLOAD_TOO_LARGE,
        SERVER_ERROR,
        SERIALIZATION,
        UNKNOWN,
    }

    enum class Local : DataError {
        DISK_FULL,
        NOT_FOUND,
        UNKNOWN,
    }

    /**
     * Machine codes the backend attaches to 401/402/403 bodies (`error_code` / `code`).
     * These are terminal for the operation that raised them — the UI offers a decision
     * (top up, view plans, sign in on the web), never an automatic retry.
     */
    enum class Api : DataError {
        INSUFFICIENT_CREDITS,
        NO_SUBSCRIPTION,
        PLAN_RESTRICTED,
        /**
         * `ai_session_required` / `turnstile_required`: the route wants an `X-AI-Session` minted
         * behind a browser-only Cloudflare challenge (`security/permissions.py`). A native mint for
         * mobile principals is the open backend ask; until it lands this is terminal.
         */
        SESSION_CHALLENGE,
    }
}
