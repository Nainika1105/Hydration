package health.hydration.core.deficit

import kotlin.math.exp
import kotlin.math.pow

/**
 * Whole-body sweat rate from the human heat balance equation, rearranged to solve for
 * required evaporative heat loss (PD §2.2, refs [3][4]).
 *
 * This is standard partitional calorimetry, not a stand-in:
 *
 *     M − W = (C + R) + E + S          heat balance, W/m²
 *     E_req = (M − W) − (C + R) − S    required evaporation
 *     η     = 1 − w²/2                 evaporative efficiency at wettedness w = E_req/E_max
 *     ṡweat = E_req / (η · λ)          sweat production exceeds evaporation as air saturates
 *
 * Two honest caveats, both of which belong in the paper rather than in a comment:
 *
 *  1. The *coefficients* here are standard textbook values (Lewis relation, DuBois surface
 *     area, Buck vapour pressure, ASHRAE convective form). The published piecewise
 *     coefficients of Gonzalez et al. [2] and the JAPPL 2024 equations [3][4] must be pulled
 *     from the papers and checked against this implementation before any result is reported.
 *     Open item PD §12.2 covers exactly this.
 *  2. Heat storage S is not modelled. During steady-state exercise S ≈ 0; during onset and
 *     recovery it is not, and this model will over-predict at onset. That is a known limit
 *     of the equation form, not of this code.
 *
 * The project's contribution at this layer is the per-user scale and intercept fitted on
 * top of this model (PD §4.3), not the model itself.
 */
object HeatBalance {

    /** Latent heat of vaporization of sweat at skin temperature, J/g. */
    const val LATENT_HEAT_J_PER_G = 2426.0

    /** Oxygen cost of one MET, mL O₂ per kg per minute. */
    const val VO2_ML_PER_KG_MIN_PER_MET = 3.5

    /** Energy equivalent of oxygen at a mixed respiratory quotient, J per mL O₂. */
    const val ENERGY_J_PER_ML_O2 = 20.9

    /** Linear radiative heat transfer coefficient at ordinary skin and wall temperatures, W/m²/K. */
    const val H_RADIATIVE = 4.7

    /** Lewis relation: evaporative coefficient per unit convective coefficient, K/kPa. */
    const val LEWIS_RATIO = 16.5

    /**
     * DuBois body surface area, m². Height is supplied in metres and converted, because
     * the published form takes centimetres and that is a classic silent factor-of-ten bug.
     */
    fun bodySurfaceAreaM2(massKg: Double, heightM: Double): Double =
        0.007184 * massKg.pow(0.425) * (heightM * 100.0).pow(0.725)

    /** Metabolic rate in watts, from activity intensity and body mass. */
    fun metabolicRateW(met: Double, massKg: Double): Double =
        met * VO2_ML_PER_KG_MIN_PER_MET * massKg * ENERGY_J_PER_ML_O2 / 60.0

    /** Buck equation for saturation vapour pressure over water, kPa. */
    fun saturationVapourPressureKpa(tempC: Double): Double =
        0.61121 * exp((18.678 - tempC / 234.5) * (tempC / (257.14 + tempC)))

    /** Forced convective heat transfer coefficient, W/m²/K, for relative air velocity in m/s. */
    fun convectiveCoefficient(airVelocityMs: Double): Double =
        8.3 * airVelocityMs.coerceAtLeast(0.1).pow(0.6)

    /**
     * Whole-body sweat rate in litres per hour, before per-user scaling.
     *
     * Returns the intermediate terms as well, because a deficit trace that cannot be
     * explained is a deficit trace nobody will trust.
     */
    fun sweatRate(i: HeatBalanceInputs): HeatBalanceResult {
        val area = bodySurfaceAreaM2(i.bodyMassKg, i.heightM)
        val metabolicWm2 = metabolicRateW(i.activityMet, i.bodyMassKg) / area
        val externalWorkWm2 = metabolicWm2 * i.mechanicalEfficiency

        val hConvective = convectiveCoefficient(i.airVelocityMs)
        val hDry = (hConvective + H_RADIATIVE) * i.clothingFactor
        val dryExchangeWm2 = hDry * (i.skinTempC - i.ambientTempC)

        val requiredEvaporationWm2 =
            (metabolicWm2 - externalWorkWm2) - dryExchangeWm2 - i.storageWm2

        val hEvaporative = LEWIS_RATIO * hConvective * i.clothingFactor
        val skinVapourKpa = saturationVapourPressureKpa(i.skinTempC)
        val ambientVapourKpa =
            (i.relativeHumidityPct / 100.0) * saturationVapourPressureKpa(i.ambientTempC)
        val maxEvaporationWm2 =
            (hEvaporative * (skinVapourKpa - ambientVapourKpa)).coerceAtLeast(1.0)

        if (requiredEvaporationWm2 <= 0.0) {
            return HeatBalanceResult(
                litresPerHour = 0.0,
                metabolicWm2 = metabolicWm2,
                dryExchangeWm2 = dryExchangeWm2,
                requiredEvaporationWm2 = requiredEvaporationWm2,
                maxEvaporationWm2 = maxEvaporationWm2,
                skinWettedness = 0.0,
                evaporativeEfficiency = 1.0,
            )
        }

        // Skin wettedness. Above 1.0 the body cannot evaporate what it needs to; sweat still
        // pours off but stops cooling, so the ratio is capped and the deficit keeps accruing.
        val wettedness = (requiredEvaporationWm2 / maxEvaporationWm2).coerceIn(0.0, 1.0)
        val efficiency = 1.0 - wettedness * wettedness / 2.0

        val sweatHeatWm2 = requiredEvaporationWm2 / efficiency
        val gramsPerHour = sweatHeatWm2 / LATENT_HEAT_J_PER_G * 3600.0 * area

        return HeatBalanceResult(
            litresPerHour = gramsPerHour / 1000.0,
            metabolicWm2 = metabolicWm2,
            dryExchangeWm2 = dryExchangeWm2,
            requiredEvaporationWm2 = requiredEvaporationWm2,
            maxEvaporationWm2 = maxEvaporationWm2,
            skinWettedness = wettedness,
            evaporativeEfficiency = efficiency,
        )
    }
}

data class HeatBalanceInputs(
    val activityMet: Double,
    val ambientTempC: Double,
    val relativeHumidityPct: Double,
    val skinTempC: Double,
    val airVelocityMs: Double,
    val bodyMassKg: Double,
    val heightM: Double,
    val mechanicalEfficiency: Double,
    val clothingFactor: Double,
    val storageWm2: Double = 0.0,
)

data class HeatBalanceResult(
    val litresPerHour: Double,
    val metabolicWm2: Double,
    val dryExchangeWm2: Double,
    val requiredEvaporationWm2: Double,
    val maxEvaporationWm2: Double,
    val skinWettedness: Double,
    val evaporativeEfficiency: Double,
)
