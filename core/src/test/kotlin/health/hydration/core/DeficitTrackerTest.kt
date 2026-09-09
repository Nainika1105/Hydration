package health.hydration.core

import health.hydration.core.contracts.Epoch
import health.hydration.core.contracts.IntakeEvent
import health.hydration.core.contracts.IntakeKind
import health.hydration.core.contracts.Variant
import health.hydration.core.deficit.DeficitTracker
import health.hydration.core.fixtures.Fixtures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L1's accounting.
 *
 * The single property that matters here: a gap is not a missing value, it is a wrong one
 * that persists for the rest of the day (Architecture driver D1). Coverage and imputed time
 * must therefore be visible in the state, not inferred later.
 */
class DeficitTrackerTest {

    private val cfg = Fixtures.config()
    private val tracker = DeficitTracker(cfg)

    private fun epoch(
        minute: Long, met: Double = 1.3, coverage: Double = 1.0,
        ambientC: Double = 24.0, dayIndex: Int = 0, minuteOfDay: Int = (minute % 1440).toInt(),
        // How far this individual's measured skin temperature sits from the population
        // regression variant A has to fall back on. Zero means A and C see the same value,
        // which is the degenerate case the ablation cannot learn anything from.
        skinOffsetC: Double = 0.0,
    ) = Epoch(
        slotMinute = minute, dayIndex = dayIndex, localMinuteOfDay = minuteOfDay,
        coverage = coverage,
        activityMet = if (coverage > 0) met else null,
        heartRateBpm = if (coverage > 0) 58 + 11.5 * (met - 1) else null,
        hrValidFraction = coverage,
        skinTempC = if (coverage > 0) 32.4 + 0.34 * (met - 1) + 0.11 * (ambientC - 25) + skinOffsetC else null,
        ambientTempC = if (coverage > 0) ambientC else null,
        relativeHumidityPct = if (coverage > 0) 55.0 else null,
        bottleMassG = 700.0,
    )

    @Test
    fun `at rest the deficit accrues at the insensible rate alone`() {
        var s = tracker.initialState(0)
        repeat(60) { s = tracker.fold(s, epoch(it.toLong())) }
        // 0.035 L/h of insensible loss over one hour, and no sweat at 24 C.
        assertTrue(abs(s.deficitMl - 35.0) < 2.0, "expected ~35 mL after an hour at rest, got ${s.deficitMl}")
        assertEquals(60.0, s.observedMinutes)
        assertEquals(0.0, s.imputedMinutes)
    }

    @Test
    fun `intake is subtracted and tracked separately`() {
        var s = tracker.initialState(0)
        // Accrue a real deficit first, so a 250 mL drink does not simply hit the surplus floor.
        repeat(60) { s = tracker.fold(s, epoch(it.toLong(), met = 8.0, ambientC = 33.0)) }
        val before = s.deficitMl
        assertTrue(before > 400.0, "setup should accrue a real deficit, got $before")

        s = tracker.fold(s, epoch(60, met = 8.0, ambientC = 33.0),
            listOf(IntakeEvent(60, 250.0, offBaseMinutes = 4.0)))
        assertTrue(
            abs((before - s.deficitMl) - 250.0) < 30.0,
            "a 250 mL drink must reduce the deficit by ~250 mL (less the minute's loss); " +
                "before=$before after=${s.deficitMl}"
        )
        assertEquals(250.0, s.intakeMlToday)
        assertEquals(0.0, s.excretedMlToday, "nothing should be excreted while in deficit")
    }

    @Test
    fun `a refill is not counted as negative intake`() {
        var s = tracker.initialState(0)
        s = tracker.fold(s, epoch(0), listOf(IntakeEvent(0, 600.0, 1.0, kind = IntakeKind.REFILL)))
        assertEquals(0.0, s.intakeMlToday, "a refill is a mass increase, not a drink")
    }

    @Test
    fun `uncovered minutes are imputed from the last valid rate and counted as imputed`() {
        var s = tracker.initialState(0)
        // 30 min of hard work in the heat establishes a high sweat rate.
        repeat(30) { s = tracker.fold(s, epoch(it.toLong(), met = 9.0, ambientC = 34.0)) }
        val rateAtGapStart = s.lastSweatRateLh
        assertTrue(rateAtGapStart > 0.7, "setup should establish a real sweat rate, got $rateAtGapStart")

        val beforeGap = s.deficitMl
        repeat(40) { s = tracker.fold(s, epoch(30L + it, coverage = 0.0)) }

        assertEquals(40.0, s.imputedMinutes, "every uncovered minute must be counted")
        assertEquals(30.0, s.observedMinutes)
        assertTrue(abs(s.coverageToday - 30.0 / 70.0) < 1e-9)

        // The integral does not pause across the gap; it continues at the held rate, which is
        // exactly why the imputed fraction has to travel with the number.
        val accrued = s.deficitMl - beforeGap
        val expected = (rateAtGapStart + cfg.insensibleRateLh) * 1000.0 * (40.0 / 60.0)
        assertTrue(abs(accrued - expected) < 1.0, "expected ~$expected mL over the gap, got $accrued")
    }

    @Test
    fun `the integral re-anchors at the wake time and reports overnight accrual separately`() {
        var s = tracker.initialState(0)
        repeat(600) { s = tracker.fold(s, epoch(it.toLong(), met = 2.0, ambientC = 30.0)) }
        val endOfDay = s.deficitMl
        assertTrue(endOfDay > 100.0, "setup should accrue a real deficit, got $endOfDay")

        // Next day, before the wake anchor: still carrying yesterday's total.
        s = tracker.fold(s, epoch(1440L + 300, dayIndex = 1, minuteOfDay = 300))
        assertTrue(s.deficitMl > endOfDay, "before the anchor the integral keeps running")

        // Next day, past the wake anchor (06:00): re-anchored to zero.
        s = tracker.fold(s, epoch(1440L + 361, dayIndex = 1, minuteOfDay = 361))
        assertTrue(s.deficitMl < 5.0, "expected a re-anchored deficit, got ${s.deficitMl}")
        assertTrue(s.overnightAccrualMl > endOfDay, "overnight accrual must be preserved, not discarded")
        assertEquals(0.0, s.intakeMlToday)
    }

    @Test
    fun `every ablation variant produces a plausible trace on the same epochs`() {
        val rates = Variant.entries.associateWith { variant ->
            val t = DeficitTracker(Fixtures.config(variant = variant))
            var s = t.initialState(0)
            repeat(60) { s = t.fold(s, epoch(it.toLong(), met = 8.0, ambientC = 32.0, skinOffsetC = 0.9)) }
            s.lastSweatRateLh
        }
        rates.forEach { (variant, rate) ->
            assertTrue(rate in 0.2..3.0, "$variant produced an implausible sweat rate: $rate L/h")
        }
        // A and C differ only in whether skin temperature was measured. Here the subject runs
        // 0.9 C hotter than the population regression predicts, which is exactly the
        // individual variation RQ1 asks about. If the two variants returned the same number
        // under that offset, the ablation would be measuring nothing.
        val a = rates[Variant.CONTEXT_ONLY]!!
        val c = rates[Variant.FULL]!!
        assertTrue(a != c, "variants A and C must not collapse to the same computation")

        // Direction matters and is easy to get backwards. At a FIXED workload and ambient
        // temperature, a hotter skin loses more heat by convection and radiation and faces a
        // wider skin-to-air vapour pressure gradient, so it needs LESS sweat, not more. In
        // field data skin temperature and sweat rate correlate positively because heat strain
        // drives both; the model's partial derivative at fixed inputs runs the other way.
        // Anyone reading the RQ1 ablation needs to hold both facts at once.
        assertTrue(
            c < a,
            "at fixed load and ambient, measured-hot skin should require less sweat than the " +
                "population estimate: C=$c A=$a"
        )
    }

    @Test
    fun `substituted inputs are named, so the ablation cannot be read as measured`() {
        val t = DeficitTracker(Fixtures.config(variant = Variant.CONTEXT_ONLY))
        var s = t.initialState(0)
        repeat(5) { s = t.fold(s, epoch(it.toLong(), met = 6.0)) }
        assertTrue(
            "skinTempC" in s.lastEstimate!!.substituted,
            "variant A estimates skin temperature and must say so"
        )

        val tb = DeficitTracker(Fixtures.config(variant = Variant.PHYSIOLOGY_ONLY))
        var sb = tb.initialState(0)
        repeat(5) { sb = tb.fold(sb, epoch(it.toLong(), met = 6.0)) }
        assertTrue(
            sb.lastEstimate!!.substituted.containsAll(setOf("ambientTempC", "activityMet")),
            "variant B substitutes environment and infers activity from HR; both must be named"
        )
    }

    @Test
    fun `surplus intake is excreted, not banked against later loss`() {
        var s = tracker.initialState(0)
        // Drink a litre first thing, then do nothing for two hours.
        s = tracker.fold(s, epoch(0), listOf(IntakeEvent(0, 1000.0, offBaseMinutes = 2.0)))
        assertEquals(0.0, s.deficitMl, "the deficit must not run negative under the default floor")
        assertTrue(s.excretedMlToday > 900.0, "the surplus must be accounted for, not discarded silently")

        repeat(120) { s = tracker.fold(s, epoch(1L + it)) }
        // Without the floor, a litre of morning credit would mask two hours of subsequent loss.
        assertTrue(s.deficitMl > 50.0, "later loss must accrue normally, got ${s.deficitMl}")
    }

    @Test
    fun `a configured buffer allows a bounded surplus`() {
        val buffered = DeficitTracker(
            Fixtures.config().copy(surplusFloorPctBm = -0.3)
        )
        var s = buffered.initialState(0)
        s = buffered.fold(s, epoch(0), listOf(IntakeEvent(0, 1000.0, offBaseMinutes = 2.0)))
        // -0.3% of 70 kg is -210 mL.
        assertTrue(abs(s.deficitMl - -210.0) < 1.0, "expected a -210 mL buffer, got ${s.deficitMl}")
    }
}
