package health.hydration.core.need

import health.hydration.core.contracts.NeedContext
import health.hydration.core.contracts.PolicyConstants

/**
 * L2 — the need estimator (PD §6.2).
 *
 * Projects the deficit forward 30–60 minutes by persisting current activity and environment,
 * and emits a need score and a binary window flag.
 *
 * **Invariant I1 is enforced by this file's import list.** Nothing here imports the
 * receptivity model, the policy, or any behavioural feature source, and [NeedContext] carries
 * no behavioural field. That is the whole mechanism: need cannot be influenced by how the
 * user has responded to prompts, because the information never arrives.
 *
 * `NeedContextIsolationTest` asserts both halves — the field set and the absence of the
 * import edge — and fails the build rather than a review.
 */
class NeedEstimator(private val c: PolicyConstants) {

    fun estimate(ctx: NeedContext): NeedOutput {
        val horizonHours = c.projectionHorizonMinutes / 60.0
        val projectedLossMl = (ctx.sweatRateLh + ctx.insensibleRateLh) * 1000.0 * horizonHours
        val projectedMl = ctx.deficitMl + projectedLossMl

        val bodyMassG = ctx.bodyMassKg * 1000.0
        val currentPct = ctx.deficitMl / bodyMassG * 100.0
        val projectedPct = projectedMl / bodyMassG * 100.0

        // Need ramps linearly from the floor to the hard floor, so it is comparable across
        // subjects of different mass and reads as "fraction of the way to the override".
        val span = (c.hardFloorPctBm - c.needFloorPctBm).coerceAtLeast(1e-9)
        val need = ((projectedPct - c.needFloorPctBm) / span).coerceIn(0.0, 1.0)

        return NeedOutput(
            need = need,
            windowOpen = projectedPct >= c.windowOpenPctBm,
            projectedDeficitMl = projectedMl,
            projectedDeficitPctBm = projectedPct,
            currentDeficitPctBm = currentPct,
            basis = ProjectionBasis(
                horizonMinutes = c.projectionHorizonMinutes,
                persistedMet = ctx.activityMet,
                persistedAmbientC = ctx.ambientTempC,
                persistedSweatRateLh = ctx.sweatRateLh,
                coverage = ctx.coverage,
            ),
        )
    }
}

data class NeedOutput(
    val need: Double,
    val windowOpen: Boolean,
    val projectedDeficitMl: Double,
    val projectedDeficitPctBm: Double,
    val currentDeficitPctBm: Double,
    val basis: ProjectionBasis,
)

/**
 * What the projection assumed.
 *
 * Recorded so a reviewer can tell whether a window opened because the subject was working
 * hard or because the model assumed they would keep working hard for another 45 minutes.
 */
data class ProjectionBasis(
    val horizonMinutes: Int,
    val persistedMet: Double,
    val persistedAmbientC: Double,
    val persistedSweatRateLh: Double,
    val coverage: Double,
)
