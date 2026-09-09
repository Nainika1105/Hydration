package health.hydration.core.deficit

import health.hydration.core.contracts.Epoch
import health.hydration.core.contracts.IntakeEvent
import health.hydration.core.contracts.IntakeKind
import health.hydration.core.contracts.StudyConfig

/**
 * L1 — the grey-box deficit tracker (PD §6.2).
 *
 *     deficit(t) = ∫[ sweat_rate(t) + insensible_rate ] dt − ∫ intake(t) dt
 *
 * A pure reducer over the epoch stream. The whole design turns on one property: **this is
 * an integral, so a gap is not a missing value, it is a wrong one that persists**. Coverage
 * and imputed time are therefore state, not diagnostics, and every downstream consumer can
 * see how much of a deficit figure was actually observed (Architecture §9, driver D1).
 */
class DeficitTracker(private val cfg: StudyConfig) {

    fun initialState(dayIndex: Int): DeficitState = DeficitState(
        slotMinute = -1,
        dayIndex = dayIndex,
        deficitMl = 0.0,
        lastSweatRateLh = 0.0,
        observedMinutes = 0.0,
        imputedMinutes = 0.0,
        intakeMlToday = 0.0,
        lossMlToday = 0.0,
        excretedMlToday = 0.0,
        overnightAccrualMl = 0.0,
        anchoredOnDay = dayIndex,
        lastEstimate = null,
    )

    /**
     * Folds one minute of sensing plus any intake settled in that minute.
     *
     * Intake is subtracted regardless of coverage: a bottle event is a discrete measurement,
     * not a sampled signal, so a sensor gap on the wearable does not make a recorded drink
     * less real.
     */
    fun fold(state: DeficitState, epoch: Epoch, intake: List<IntakeEvent> = emptyList()): DeficitState {
        val reanchored = maybeReanchor(state, epoch)

        val observedFraction = epoch.coverage
        val imputedFraction = 1.0 - observedFraction

        val estimate = if (observedFraction > 0.0) SweatRateModel.estimate(epoch, cfg) else null

        // Imputation policy, declared once and logged: hold the last valid sweat rate across
        // uncovered time. Anything cleverer is unverifiable against a scale-method reference.
        val observedRateLh = estimate?.litresPerHour ?: reanchored.lastSweatRateLh
        val imputedRateLh = reanchored.lastSweatRateLh

        val insensible = cfg.insensibleRateLh
        val lossMl =
            (observedRateLh + insensible) * 1000.0 * (observedFraction / 60.0) +
            (imputedRateLh + insensible) * 1000.0 * (imputedFraction / 60.0)

        val drunkMl = intake.filter { it.kind == IntakeKind.DRINK }.sumOf { it.volumeMl }

        // Surplus is excreted, not banked. See StudyConfig.surplusFloorPctBm.
        val rawDeficit = reanchored.deficitMl + lossMl - drunkMl
        val floorMl = cfg.surplusFloorPctBm / 100.0 * cfg.bodyMassKg * 1000.0
        val excretedMl = (floorMl - rawDeficit).coerceAtLeast(0.0)

        return reanchored.copy(
            slotMinute = epoch.slotMinute,
            dayIndex = epoch.dayIndex,
            deficitMl = rawDeficit + excretedMl,
            lastSweatRateLh = observedRateLh,
            observedMinutes = reanchored.observedMinutes + observedFraction,
            imputedMinutes = reanchored.imputedMinutes + imputedFraction,
            intakeMlToday = reanchored.intakeMlToday + drunkMl,
            lossMlToday = reanchored.lossMlToday + lossMl,
            excretedMlToday = reanchored.excretedMlToday + excretedMl,
            lastEstimate = estimate ?: reanchored.lastEstimate,
        )
    }

    /**
     * Re-anchor the integral at the configured wake time (open question Q2).
     *
     * Overnight accrual is carried out of the running total and reported separately rather
     * than folded in, because a deficit that survives a night of sleep is an artefact of the
     * insensible-loss constant, not a measurement.
     */
    private fun maybeReanchor(state: DeficitState, epoch: Epoch): DeficitState {
        val newDay = epoch.dayIndex > state.anchoredOnDay
        val pastAnchor = epoch.localMinuteOfDay >= cfg.wakeAnchorMinute
        if (!newDay || !pastAnchor) return state

        return state.copy(
            overnightAccrualMl = state.deficitMl,
            deficitMl = 0.0,
            intakeMlToday = 0.0,
            lossMlToday = 0.0,
            excretedMlToday = 0.0,
            observedMinutes = 0.0,
            imputedMinutes = 0.0,
            anchoredOnDay = epoch.dayIndex,
        )
    }
}

data class DeficitState(
    val slotMinute: Long,
    val dayIndex: Int,
    val deficitMl: Double,
    val lastSweatRateLh: Double,
    val observedMinutes: Double,
    val imputedMinutes: Double,
    val intakeMlToday: Double,
    val lossMlToday: Double,
    /** Intake above the surplus floor, assumed excreted. Keeps the daily balance closing. */
    val excretedMlToday: Double,
    val overnightAccrualMl: Double,
    val anchoredOnDay: Int,
    val lastEstimate: SweatEstimate?,
) {
    fun deficitPctBm(bodyMassKg: Double): Double = deficitMl / (bodyMassKg * 1000.0) * 100.0

    /** Fraction of the day's elapsed minutes that were actually observed. */
    val coverageToday: Double
        get() {
            val total = observedMinutes + imputedMinutes
            return if (total <= 0.0) 0.0 else observedMinutes / total
        }
}
