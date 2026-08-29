package com.veilkeepers.app.data

/**
 * Typed API failures, mapping the 7 backend error codes
 * (backend/internal/server/auth.go, `{"error":code,"message":...}` envelope)
 * plus client-side transport errors. Every variant carries a user-facing
 * message; no secrets or stack traces are ever included.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 401 invalid_credentials — single generic login failure. */
    object InvalidCredentials : ApiError("Username or password is incorrect.")

    /** 409 username_taken. */
    object UsernameTaken : ApiError("That username is not available.")

    /** 429 rate_limited — per-IP token bucket on the /api/v1/auth routes. */
    object RateLimited :
        ApiError("Too many attempts. Please wait about a minute, then try again.")

    /** 403 registration_closed. */
    object RegistrationClosed : ApiError("Registration is not open on this server.")

    /** 400 invalid_input. */
    object InvalidInput : ApiError("The request is malformed. Please try again.")

    /** 404 not_found (e.g. unknown username on the kdf lookup). */
    object NotFound : ApiError("Account not found.")

    /** 500 internal_error (or any unrecognized error code). */
    object Internal : ApiError("Something went wrong on the server. Please try again.")

    /** Client-side transport failure (timeout, DNS, refused, reset…). */
    class Network(cause: Throwable? = null) :
        ApiError("Cannot reach the server. Check the server URL and your connection.", cause)

    companion object {
        /** Maps a backend `error` code to its [ApiError]; unknown codes → [Internal]. */
        fun fromCode(code: String): ApiError = when (code) {
            "invalid_credentials" -> InvalidCredentials
            "username_taken" -> UsernameTaken
            "rate_limited" -> RateLimited
            "registration_closed" -> RegistrationClosed
            "invalid_input" -> InvalidInput
            "not_found" -> NotFound
            "internal_error" -> Internal
            else -> Internal
        }
    }
}
