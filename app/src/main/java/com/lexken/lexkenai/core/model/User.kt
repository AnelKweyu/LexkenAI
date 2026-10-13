package com.lexken.lexkenai.core.model

@JvmInline
value class UserId(val value: String)

/**
 * `role_name` from `users/profile/nav-info/`. [PUBLIC] is the self-serve consumer account the web
 * calls `public` — the role the app registers and the one `/pay` elevates before a firm plan.
 */
enum class UserRole { ADMIN, ADVOCATE, STAFF, PUBLIC }

data class User(
    val id: UserId,
    val fullName: String,
    val email: String,
    val role: UserRole,
    val firmName: String?,
) {
    /** "Oduor Otieno Evans" -> "OE" — first and last initials, as the avatar shows. */
    val initials: String
        get() {
            val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when (parts.size) {
                0 -> ""
                1 -> parts[0].take(1)
                else -> parts.first().take(1) + parts.last().take(1)
            }.uppercase()
        }
}

/** AI credits balance shown in the top bar of every workspace. */
@JvmInline
value class Credits(val value: Int) {
    init {
        require(value >= 0) { "Credits cannot be negative: $value" }
    }

    operator fun plus(other: Credits) = Credits(value + other.value)
    operator fun minus(other: Credits) = Credits((value - other.value).coerceAtLeast(0))
}
