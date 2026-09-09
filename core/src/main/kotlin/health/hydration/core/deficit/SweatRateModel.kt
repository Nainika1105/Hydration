package health.hydration.core.deficit

import health.hydration.core.contracts.Epoch
import health.hydration.core.contracts.StudyConfig
import health.hydration.core.contracts.Variant

/**
 * Resolves an [Epoch] into heat-balance inputs according to the configured ablation variant,
 * then applies the per-user scale and intercept.
 *
 * The four variants share one code path and differ only in an input mask (Architecture §6,
 * driver D5). Variant D is the honest baseline and shares everything except the model call
 * itself, so a difference between C and D cannot be an artefact of two different pipelines.
 *
 * [SweatEstimate.substituted] names every input that was filled from a default or an
 * estimator rather than measured. That field is what makes the RQ1 ablation legible: an
 * improvement of C over A means nothing if half of A's inputs were quietly imputed.
 */
object SweatRateModel {

    fun estimate(epoch: Epoch, cfg: StudyConfig): SweatEstimate {
        val c = cfg.coefficients
        val env = cfg.environment
        val substituted = mutableSetOf<String>()

        fun ambient(): Double = when (cfg.variant) {
            // B excludes environment by design, so the assumed value is part of the variant,
            // not a gap. It is still recorded as substituted.
            Variant.PHYSIOLOGY_ONLY -> {
                substituted += "ambientTempC"; env.assumedAmbientTempC
            }
            else -> epoch.ambientTempC ?: run {
                substituted += "ambientTempC"; env.assumedAmbientTempC
            }
        }

        fun humidity(): Double = when (cfg.variant) {
            Variant.PHYSIOLOGY_ONLY -> {
                substituted += "relativeHumidityPct"; env.assumedRelativeHumidityPct
            }
            else -> epoch.relativeHumidityPct ?: run {
                substituted += "relativeHumidityPct"; env.assumedRelativeHumidityPct
            }
        }

        fun activityMet(): Double = when (cfg.variant) {
            // B infers activity from heart rate, which is the point of the variant.
            Variant.PHYSIOLOGY_ONLY -> {
                substituted += "activityMet"
                metFromHeartRate(epoch.heartRateBpm, c.restingHrBpm, c.hrBpmPerMet)
            }
            else -> epoch.activityMet ?: run {
                substituted += "activityMet"
                metFromHeartRate(epoch.heartRateBpm, c.restingHrBpm, c.hrBpmPerMet)
            }
        }

        fun skinTemp(met: Double, ambientC: Double): Double = when (cfg.variant) {
            // A has no skin sensor by design; the estimator is part of the variant.
            Variant.CONTEXT_ONLY -> {
                substituted += "skinTempC"
                estimateSkinTemp(met, ambientC, c.skinTempBaseC, c.skinTempPerMet, c.skinTempPerAmbientC)
            }
            else -> epoch.skinTempC ?: run {
                substituted += "skinTempC"
                estimateSkinTemp(met, ambientC, c.skinTempBaseC, c.skinTempPerMet, c.skinTempPerAmbientC)
            }
        }

        val ambientC = ambient()
        val rh = humidity()
        val met = activityMet()
        val skinC = skinTemp(met, ambientC)

        if (cfg.variant == Variant.FIXED_RATE) {
            // D: one per-person rate from a single session, scaled by ambient temperature only.
            val raw = c.fixedRateLh * (1.0 + c.fixedRatePerDegC * (ambientC - c.fixedRateRefTempC))
            return SweatEstimate(
                litresPerHour = fit(raw, c.scale, c.intercept),
                rawLitresPerHour = raw.coerceAtLeast(0.0),
                activityMet = met,
                ambientTempC = ambientC,
                skinTempC = skinC,
                substituted = substituted + setOf("heatBalance"),
                detail = null,
            )
        }

        val velocity = if (met >= ACTIVE_MET_THRESHOLD) cfg.environment.airVelocityActiveMs
                       else cfg.environment.airVelocityMs

        val result = HeatBalance.sweatRate(
            HeatBalanceInputs(
                activityMet = met,
                ambientTempC = ambientC,
                relativeHumidityPct = rh,
                skinTempC = skinC,
                airVelocityMs = velocity,
                bodyMassKg = cfg.bodyMassKg,
                heightM = cfg.heightM,
                mechanicalEfficiency = c.mechanicalEfficiency,
                clothingFactor = c.clothingFactor,
            )
        )

        return SweatEstimate(
            litresPerHour = fit(result.litresPerHour, c.scale, c.intercept),
            rawLitresPerHour = result.litresPerHour,
            activityMet = met,
            ambientTempC = ambientC,
            skinTempC = skinC,
            substituted = substituted,
            detail = result,
        )
    }

    /** Per-user fit (PD §6.2). Identity until at least three calibration sessions exist. */
    private fun fit(raw: Double, scale: Double, intercept: Double): Double =
        (raw * scale + intercept).coerceAtLeast(0.0)

    private const val ACTIVE_MET_THRESHOLD = 4.0

    fun metFromHeartRate(hr: Double?, restingHr: Double, hrPerMet: Double): Double {
        if (hr == null) return 1.0
        return (1.0 + (hr - restingHr) / hrPerMet).coerceIn(0.8, 20.0)
    }

    fun estimateSkinTemp(
        met: Double,
        ambientC: Double,
        base: Double,
        perMet: Double,
        perAmbient: Double,
    ): Double = (base + perMet * (met - 1.0) + perAmbient * (ambientC - 25.0)).coerceIn(28.0, 38.0)
}

data class SweatEstimate(
    val litresPerHour: Double,
    val rawLitresPerHour: Double,
    val activityMet: Double,
    val ambientTempC: Double,
    val skinTempC: Double,
    /** Inputs filled from a default or estimator rather than measured. */
    val substituted: Set<String>,
    val detail: HeatBalanceResult?,
)
