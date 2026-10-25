package app.mirror.core.signaling

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Exponential backoff with symmetric jitter. [maxAttempts] = null retries forever. */
data class ReconnectPolicy(
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 15_000,
    val multiplier: Double = 2.0,
    val jitter: Double = 0.2,
    val maxAttempts: Int? = null,
) {
    init {
        require(initialDelayMs > 0 && maxDelayMs >= initialDelayMs)
        require(multiplier >= 1.0)
        require(jitter in 0.0..1.0)
    }

    /** [attempt] is 1 for the first retry. */
    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val base = min(maxDelayMs.toDouble(), initialDelayMs * multiplier.pow(attempt - 1))
        val factor = 1.0 + (random.nextDouble() * 2 - 1) * jitter
        return (base * factor).toLong().coerceAtLeast(1)
    }

    fun exhausted(attempt: Int): Boolean = maxAttempts != null && attempt > maxAttempts

    companion object {
        val NONE = ReconnectPolicy(maxAttempts = 0)
    }
}
