package health.hydration.core

import health.hydration.core.fixtures.Fixtures
import health.hydration.core.fixtures.SyntheticSubject
import health.hydration.core.randomizer.Randomizer
import health.hydration.core.sim.StackRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay guarantee (Architecture §7).
 *
 * `replay(log).decisions == log.decisions` is the concrete form of the daily data-integrity
 * check PD §7.5 requires in weeks 12–13. It only means anything if the core is deterministic,
 * so that is what this pins: same inputs, same decisions, same input hashes, every time.
 */
class DeterminismTest {

    private val cfg = Fixtures.config()

    private fun runOnce() = StackRunner(
        cfg, Randomizer(Fixtures.seed()), Fixtures.receptivity(), Fixtures.versions()
    ).run(SyntheticSubject(), startMinute = 0, endMinute = 14 * 1440L)

    @Test
    fun `two runs over identical inputs produce identical decision records`() {
        val a = runOnce()
        val b = runOnce()
        assertEquals(a.decisions.size, b.decisions.size)
        a.decisions.zip(b.decisions).forEach { (x, y) ->
            assertEquals(x, y, "decision at slot ${x.slotIndex} differs between runs")
        }
    }

    @Test
    fun `the input hash separates an input change from an output change`() {
        val a = runOnce()
        val b = runOnce()
        assertEquals(a.decisions.map { it.inputHash }, b.decisions.map { it.inputHash })

        // A different subject changes the inputs, and the hash must say so. Without this,
        // a replay mismatch is an unexplained difference rather than a localizable defect.
        val other = StackRunner(cfg, Randomizer(Fixtures.seed()), Fixtures.receptivity(), Fixtures.versions())
            .run(SyntheticSubject(salt = 99L), startMinute = 0, endMinute = 14 * 1440L)
        assertTrue(
            other.decisions.map { it.inputHash } != a.decisions.map { it.inputHash },
            "different physiology must produce different input hashes"
        )
    }

    @Test
    fun `every decision carries the six version fields`() {
        val v = runOnce().decisions.first().versions
        listOf(v.firmware, v.app, v.core, v.config, v.coefficients, v.receptivityModel)
            .forEachIndexed { i, s -> assertTrue(s.isNotBlank(), "version field $i is blank") }
    }
}
