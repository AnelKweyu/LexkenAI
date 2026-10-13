package com.lexken.lexkenai.core.common

/**
 * Wall-clock time for anything that expires (the AI session, cached snapshots). Production
 * uses [SYSTEM]; tests inject a clock they can move so expiry is asserted, not waited for.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
