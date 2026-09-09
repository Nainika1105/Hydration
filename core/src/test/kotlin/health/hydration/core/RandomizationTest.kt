package health.hydration.core

import health.hydration.core.fixtures.Fixtures
import health.hydration.core.randomizer.Randomizer
import health.hydration.core.randomizer.SealedSeed
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The randomization properties week 13's "clean log" gate depends on (Architecture §8). */
class RandomizationTest {

    private val randomizer = Randomizer(Fixtures.seed())

    @Test
    fun `an assignment is a pure function of seed and slot, so a restart cannot re-roll it`() {
        val first = (0L until 2000L).map { randomizer.assignment(it) }
        // A fresh Randomizer stands in for a crash, a reinstall, or a new install on a
        // replacement handset. There is no persisted RNG state, so there is nothing to lose.
        val afterRestart = Randomizer(Fixtures.seed()).let { r -> (0L until 2000L).map { r.assignment(it) } }
        assertEquals(first, afterRestart)
    }

    @Test
    fun `assignment is balanced at p equals one half`() {
        val n = 200_000
        val ones = (0L until n).count { randomizer.assignment(it) }
        val p = ones.toDouble() / n
        // 5 sigma on a fair coin at n = 200k is about 0.0056.
        assertTrue(abs(p - 0.5) < 0.006, "assignment is not balanced: p = $p over $n slots")
    }

    @Test
    fun `a different seed yields a different sequence`() {
        val other = Randomizer(SealedSeed.fromHex("ffeeddccbbaa99887766554433221100"))
        val a = (0L until 500L).map { randomizer.assignment(it) }
        val b = (0L until 500L).map { other.assignment(it) }
        assertNotEquals(a, b)
        assertTrue(a.zip(b).count { (x, y) -> x != y } > 180, "sequences are suspiciously similar")
    }

    @Test
    fun `the seed commitment is stable, subject-specific, and does not reveal the seed`() {
        val seed = Fixtures.seed()
        val commitment = seed.commitment("S-TEST-01")
        assertEquals(commitment, seed.commitment("S-TEST-01"), "commitment must be reproducible")
        assertNotEquals(commitment, seed.commitment("S-TEST-02"), "commitment must bind the subject")
        assertEquals(64, commitment.length, "expected a SHA-256 hex digest")
        assertTrue(commitment.all { it in "0123456789abcdef" })
    }

    @Test
    fun `a seed shorter than 128 bits is rejected`() {
        val tooShort = runCatching { SealedSeed(ByteArray(8)) }
        assertTrue(tooShort.isFailure, "an under-strength seed must not be accepted")
    }
}
