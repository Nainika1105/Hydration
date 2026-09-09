package health.hydration.core.receptivity

import health.hydration.core.contracts.ActivityState
import health.hydration.core.contracts.ReceptivityContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * L3 — receptivity: P(drink ≤30 min | prompt, context) (PD §6.2).
 *
 * Scoring only. **Fitting happens in Python and never here** (Architecture §7): the analysis
 * owns every statistic in the plan, the core owns every decision, and a versioned model card
 * is the only thing that crosses between them. That is what makes the deployed decision and
 * the replayed decision the same computation rather than two implementations that agree
 * until they don't.
 *
 * On capacity: ~1,900 decision points across ten subjects is ~190 each, of which only the
 * eligible ones carry an outcome. A per-subject model is not supportable at that n. The
 * defensible structure is pooled coefficients with a per-subject intercept, which is what
 * [ReceptivityModelCard.subjectIntercept] carries (open question Q6).
 */
class ReceptivityModel(val card: ReceptivityModelCard) {

    fun score(ctx: ReceptivityContext): Double {
        var z = card.intercept + card.subjectIntercept
        // Ordered list, not a map traversal: iteration order must not affect the result
        // (Architecture §7, determinism rules).
        for (i in card.features.indices) {
            val raw = feature(card.features[i], ctx)
            val scale = card.scales[i].let { if (it == 0.0) 1.0 else it }
            z += card.coefficients[i] * ((raw - card.means[i]) / scale)
        }
        return 1.0 / (1.0 + exp(-z))
    }

    companion object {
        /**
         * The six features of PD §6.2. Time of day is encoded as a sine/cosine pair so that
         * 23:50 and 00:10 are close, which a raw minute count gets badly wrong.
         */
        val FEATURES = listOf(
            "timeOfDaySin",
            "timeOfDayCos",
            "activityState",
            "minutesSinceLastPrompt",
            "promptCountToday",
            "bottleMassG",
            "isWeekend",
        )

        /** Availability proxy when the bottle has not reported: assume unavailable. */
        const val BOTTLE_MASS_UNKNOWN_G = 0.0

        /** Ceiling for "no prompt yet today", so the feature stays bounded. */
        const val NO_PRIOR_PROMPT_MINUTES = 480.0

        fun feature(name: String, ctx: ReceptivityContext): Double = when (name) {
            "timeOfDaySin" -> sin(2 * PI * ctx.minutesSinceMidnight / 1440.0)
            "timeOfDayCos" -> cos(2 * PI * ctx.minutesSinceMidnight / 1440.0)
            "activityState" -> when (ctx.activityState) {
                ActivityState.SEDENTARY -> 0.0
                ActivityState.LIGHT -> 1.0
                ActivityState.MODERATE -> 2.0
                ActivityState.VIGOROUS -> 3.0
            }
            "minutesSinceLastPrompt" ->
                (ctx.minutesSinceLastPrompt?.toDouble() ?: NO_PRIOR_PROMPT_MINUTES)
                    .coerceAtMost(NO_PRIOR_PROMPT_MINUTES)
            "promptCountToday" -> ctx.promptCountToday.toDouble()
            "bottleMassG" -> ctx.bottleMassG ?: BOTTLE_MASS_UNKNOWN_G
            "isWeekend" -> if (ctx.dayOfWeek >= 6) 1.0 else 0.0
            else -> error("unknown receptivity feature: $name")
        }
    }
}

/**
 * A fitted logistic regression, exported by the analysis tier.
 *
 * Everything needed to reproduce a score, plus the provenance needed to judge whether the
 * score is worth anything: how much data it saw and how it performed held out.
 */
data class ReceptivityModelCard(
    val version: String,
    val features: List<String>,
    val means: List<Double>,
    val scales: List<Double>,
    val coefficients: List<Double>,
    val intercept: Double,
    val subjectIntercept: Double = 0.0,
    val trainedRows: Int = 0,
    val heldOutAuc: Double? = null,
) {
    init {
        require(features.size == means.size && features.size == scales.size && features.size == coefficients.size) {
            "model card is ragged: ${features.size} features, ${means.size} means, " +
                "${scales.size} scales, ${coefficients.size} coefficients"
        }
    }

    companion object {
        /**
         * Cold-start prior, shipped with the config and used until enough outcomes exist
         * (PD §6.2). Deliberately flat: it encodes only that a prompt shortly after another
         * prompt is less likely to be acted on, and that an unavailable bottle is worse.
         * It is a placeholder for a fitted card, and its version says so.
         */
        fun populationPrior(): ReceptivityModelCard = ReceptivityModelCard(
            version = "prior-0",
            features = ReceptivityModel.FEATURES,
            means = listOf(0.0, 0.0, 1.0, 240.0, 3.0, 400.0, 0.0),
            scales = listOf(1.0, 1.0, 1.0, 120.0, 2.0, 250.0, 1.0),
            coefficients = listOf(0.0, -0.15, -0.35, 0.45, -0.30, 0.40, 0.0),
            intercept = -0.20,
            trainedRows = 0,
            heldOutAuc = null,
        )
    }
}
