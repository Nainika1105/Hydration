package health.hydration.core

import health.hydration.core.deficit.HeatBalance
import health.hydration.core.deficit.HeatBalanceInputs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sweat model against textbook reference values and against published field ranges.
 *
 * This does NOT validate the model against a subject — only the scale method can do that
 * (PD §7.2), and doing so is the whole point of weeks 8–11. What it does is pin the physics
 * so that a refactor cannot quietly move a coefficient, and catch the classic unit errors
 * (height in metres where centimetres were meant, kPa where Pa were meant).
 */
class HeatBalanceTest {

    private fun athlete(
        met: Double, ambientC: Double, rh: Double, skinC: Double, velocity: Double,
    ) = HeatBalanceInputs(
        activityMet = met, ambientTempC = ambientC, relativeHumidityPct = rh,
        skinTempC = skinC, airVelocityMs = velocity,
        bodyMassKg = 70.0, heightM = 1.78,
        mechanicalEfficiency = 0.0, clothingFactor = 0.9,
    )

    @Test
    fun `DuBois surface area matches the published value for a 70 kg, 178 cm adult`() {
        val area = HeatBalance.bodySurfaceAreaM2(70.0, 1.78)
        assertTrue(abs(area - 1.87) < 0.02, "expected ~1.87 m², got $area")
    }

    @Test
    fun `saturation vapour pressure matches steam table values`() {
        // 35 °C -> 5.63 kPa, 30 °C -> 4.25 kPa, 20 °C -> 2.34 kPa
        assertTrue(abs(HeatBalance.saturationVapourPressureKpa(35.0) - 5.63) < 0.05)
        assertTrue(abs(HeatBalance.saturationVapourPressureKpa(30.0) - 4.25) < 0.05)
        assertTrue(abs(HeatBalance.saturationVapourPressureKpa(20.0) - 2.34) < 0.05)
    }

    @Test
    fun `metabolic rate matches the oxygen-cost calculation`() {
        // 10 MET, 70 kg -> 2.45 L O2/min -> ~853 W
        val watts = HeatBalance.metabolicRateW(10.0, 70.0)
        assertTrue(abs(watts - 853.0) < 5.0, "expected ~853 W, got $watts")
    }

    @Test
    fun `a runner in the heat sweats within the published range`() {
        // 10 MET, 30 °C, 50% RH: field studies report roughly 1.0-1.8 L/h for this load.
        val rate = HeatBalance.sweatRate(athlete(10.0, 30.0, 50.0, 35.0, 2.0)).litresPerHour
        assertTrue(rate in 1.0..1.8, "expected 1.0-1.8 L/h for a hard run at 30 °C, got $rate")
    }

    @Test
    fun `a resting adult in a temperate room does not sweat`() {
        val rate = HeatBalance.sweatRate(athlete(1.3, 24.0, 50.0, 33.0, 0.2)).litresPerHour
        assertTrue(rate < 0.05, "expected ~0 L/h at rest in 24 °C, got $rate")
    }

    @Test
    fun `sweat rate rises with heat, with workload, and with humidity at fixed load`() {
        val cool = HeatBalance.sweatRate(athlete(8.0, 22.0, 50.0, 34.0, 2.0)).litresPerHour
        val hot = HeatBalance.sweatRate(athlete(8.0, 36.0, 50.0, 35.5, 2.0)).litresPerHour
        assertTrue(hot > cool, "hotter should sweat more: $hot vs $cool")

        val easy = HeatBalance.sweatRate(athlete(4.0, 30.0, 50.0, 34.0, 2.0)).litresPerHour
        val hard = HeatBalance.sweatRate(athlete(11.0, 30.0, 50.0, 35.5, 2.0)).litresPerHour
        assertTrue(hard > easy, "harder should sweat more: $hard vs $easy")

        // Humid air evaporates sweat poorly, so more sweat must be produced for the same
        // cooling. This is the wettedness term, and it is why 30 °C / 81% RH is worse than
        // 40 °C / 36% RH for fluid loss (PD §2.2, ref [8]).
        val dry = HeatBalance.sweatRate(athlete(9.0, 32.0, 30.0, 35.0, 2.0))
        val humid = HeatBalance.sweatRate(athlete(9.0, 32.0, 85.0, 35.0, 2.0))
        assertTrue(
            humid.litresPerHour > dry.litresPerHour,
            "humid should require more sweat production: ${humid.litresPerHour} vs ${dry.litresPerHour}"
        )
        assertTrue(humid.skinWettedness > dry.skinWettedness)
        assertTrue(humid.evaporativeEfficiency < dry.evaporativeEfficiency)
    }

    @Test
    fun `evaporative efficiency stays inside its physical bounds`() {
        for (rh in 5..95 step 5) {
            for (met in 1..15) {
                val r = HeatBalance.sweatRate(athlete(met.toDouble(), 34.0, rh.toDouble(), 35.0, 1.5))
                assertTrue(r.evaporativeEfficiency in 0.5..1.0, "efficiency out of bounds: $r")
                assertTrue(r.skinWettedness in 0.0..1.0, "wettedness out of bounds: $r")
                assertTrue(r.litresPerHour >= 0.0 && r.litresPerHour < 5.0, "implausible rate: $r")
            }
        }
    }
}
