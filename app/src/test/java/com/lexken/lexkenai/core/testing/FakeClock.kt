package com.lexken.lexkenai.core.testing

import com.lexken.lexkenai.core.common.Clock

/** A clock the test moves by hand, so expiry is asserted rather than waited for. */
class FakeClock(start: Long = 1_700_000_000_000L) : Clock {
    @Volatile var now: Long = start

    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}
