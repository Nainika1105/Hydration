package health.hydration.core.invariants

import health.hydration.core.contracts.*
import health.hydration.core.fixtures.Fixtures
import health.hydration.core.need.NeedEstimator
import health.hydration.core.policy.Policy
import health.hydration.core.policy.PolicyState
import health.hydration.core.randomizer.Randomizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Invariant I2 — the hard floor is unconditional.**
 *
 * Property test over randomly generated states: whenever the projected deficit reaches the
 * hard floor and the deficit estimate is trustworthy, a prompt is delivered — regardless of
 * receptivity, cooldown, daily budget, quiet hours, training hours, or the randomizer's draw.
 *
 * This is the design invariant of PD §6.2 as an executable assertion: low receptivity may
 * delay a need-driven prompt, it may never cancel one.
 */
class HardFloorInvariantTest {

    private val cfg = Fixtures.config()
    private val c = cfg.policy
    private val policy = Policy(c, Randomizer(Fixtures.seed()), cfg.shadowReceptivity, Fixtures.versions())
    private val need = NeedEstimator(c)

    @Test
    fun `a trustworthy hard-floor deficit always prompts, whatever else is true`() {
        val rng = Random(20260905)
        var hardFloorCases = 0

        repeat(50_000) { i ->
            val state = randomState(rng)
            val deficitPct = rng.nextDouble(0.0, 4.0)
            val deficitMl = deficitPct / 100.0 * cfg.bodyMassKg * 1000.0

            val needOut = need.estimate(
                NeedContext(
                    deficitMl = deficitMl,
                    sweatRateLh = rng.nextDouble(0.0, 2.5),
                    insensibleRateLh = cfg.insensibleRateLh,
                    activityMet = rng.nextDouble(0.9, 12.0),
                    ambientTempC = rng.nextDouble(12.0, 44.0),
                    relativeHumidityPct = rng.nextDouble(10.0, 95.0),
                    bodyMassKg = cfg.bodyMassKg,
                    coverage = state.trailingCoverage,
                )
            )

            val decision = policy.decide(
                slotIndex = i.toLong(),
                slotMinute = i * 30L,
                need = needOut,
                // Adversarial: receptivity is pinned at zero, the worst case for delivery.
                receptivity = 0.0,
                state = state,
                deficitMl = deficitMl,
                deficitPctBm = deficitPct,
                coverage = state.trailingCoverage,
            )

            val atHardFloor = needOut.projectedDeficitPctBm >= c.hardFloorPctBm
            val estimateTrusted = state.blockingReason() == null

            if (atHardFloor && estimateTrusted) {
                hardFloorCases++
                assertEquals(
                    Action.PROMPT, decision.action,
                    "I2 violated at slot $i: projected ${needOut.projectedDeficitPctBm}%BM " +
                        ">= hard floor ${c.hardFloorPctBm}%BM with a trustworthy estimate, " +
                        "but action was ${decision.action} (${decision.ineligibleReason})"
                )
                assertEquals(Trigger.HARD_FLOOR, decision.trigger)
                assertEquals(Modality.ESCALATED, decision.modality)
                assertFalse(decision.randomized, "override prompts are not trial data (I5)")
            }
        }

        assertTrue(hardFloorCases > 1_000, "property test was vacuous: only $hardFloorCases hard-floor cases")
    }

    /**
     * The override is suspended only when the deficit estimate cannot be trusted, never for
     * burden or timing. This pins the [IneligibleReason.blocksOverride] split (Architecture §10).
     */
    @Test
    fun `only data-integrity reasons can suppress the override`() {
        val suppressing = IneligibleReason.entries.filter { it.blocksOverride }.toSet()
        assertEquals(
            setOf(
                IneligibleReason.DEVICE_DOWN,
                IneligibleReason.LOW_COVERAGE,
                IneligibleReason.INTAKE_UNOBSERVED,
                IneligibleReason.PHONE_DOWN,
            ),
            suppressing,
            "The set of reasons that suspend the hard floor changed. Every member must mean " +
                "'the deficit estimate itself is untrustworthy'. Burden, timing and protocol " +
                "convenience must never appear here (Architecture §10)."
        )
    }

    /** The specific case the invariant exists for: an unresponsive user in genuine need. */
    @Test
    fun `cooldown, an exhausted budget and zero receptivity together cannot cancel a prompt`() {
        val state = baseState().copy(
            minutesSinceLastPrompt = 1,
            promptsToday = 999,
        )
        val deficitPct = 2.4
        val needOut = need.estimate(
            NeedContext(
                deficitMl = deficitPct / 100.0 * cfg.bodyMassKg * 1000.0,
                sweatRateLh = 1.4, insensibleRateLh = cfg.insensibleRateLh,
                activityMet = 9.0, ambientTempC = 36.0, relativeHumidityPct = 70.0,
                bodyMassKg = cfg.bodyMassKg, coverage = 1.0,
            )
        )
        assertTrue(state.protocolReason(c) != null, "test setup should trip a protocol gate")

        val decision = policy.decide(
            1L, 30L, needOut, receptivity = 0.0, state = state,
            deficitMl = needOut.projectedDeficitMl, deficitPctBm = deficitPct, coverage = 1.0,
        )
        assertEquals(Action.PROMPT, decision.action)
        assertEquals(Trigger.HARD_FLOOR, decision.trigger)
    }

    private fun baseState() = PolicyState(
        slotIndex = 0, minuteOfDay = 14 * 60, dayIndex = 0,
        windowOpenMinutes = 0, minutesSinceLastPrompt = null, promptsToday = 0,
        deviceUp = true, intakeObserved = true, trailingCoverage = 1.0,
        trainingWindows = cfg.trainingWindows, minCoverage = c.minCoverage,
    )

    private fun randomState(rng: Random) = PolicyState(
        slotIndex = rng.nextLong(0, 1000),
        minuteOfDay = rng.nextInt(0, 1440),
        dayIndex = rng.nextInt(0, 14),
        windowOpenMinutes = rng.nextInt(0, 180),
        minutesSinceLastPrompt = if (rng.nextBoolean()) rng.nextInt(0, 300) else null,
        promptsToday = rng.nextInt(0, 20),
        deviceUp = rng.nextDouble() > 0.1,
        intakeObserved = rng.nextDouble() > 0.1,
        trailingCoverage = rng.nextDouble(0.0, 1.0),
        trainingWindows = cfg.trainingWindows,
        minCoverage = c.minCoverage,
    )
}
