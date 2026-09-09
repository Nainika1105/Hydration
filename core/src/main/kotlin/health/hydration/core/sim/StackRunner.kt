package health.hydration.core.sim

import health.hydration.core.contracts.Action
import health.hydration.core.contracts.ActivityState
import health.hydration.core.contracts.DecisionRecord
import health.hydration.core.contracts.Epoch
import health.hydration.core.contracts.EpochFlag
import health.hydration.core.contracts.IntakeEvent
import health.hydration.core.contracts.Modality
import health.hydration.core.contracts.NeedContext
import health.hydration.core.contracts.ReceptivityContext
import health.hydration.core.contracts.StudyConfig
import health.hydration.core.contracts.Trigger
import health.hydration.core.contracts.Versions
import health.hydration.core.deficit.DeficitState
import health.hydration.core.deficit.DeficitTracker
import health.hydration.core.need.NeedEstimator
import health.hydration.core.policy.Policy
import health.hydration.core.policy.PolicyState
import health.hydration.core.randomizer.Randomizer
import health.hydration.core.receptivity.ReceptivityModel

/**
 * Drives L1 → L2 → L3 → L4 over an epoch stream.
 *
 * This is the single implementation that both runtimes call (Architecture §7): the Android
 * app feeds it live epochs, the replay CLI feeds it logged ones, and because the core holds
 * no clock and no randomness of its own, the two produce identical [DecisionRecord]s. A
 * replay mismatch is then a defect with a stack trace rather than an unexplained difference
 * in a results table.
 */
class StackRunner(
    private val cfg: StudyConfig,
    randomizer: Randomizer,
    private val receptivity: ReceptivityModel,
    private val versions: Versions,
) {
    private val tracker = DeficitTracker(cfg)
    private val need = NeedEstimator(cfg.policy)
    private val policy = Policy(cfg.policy, randomizer, cfg.shadowReceptivity, versions, cfg.mrtMode)

    fun run(world: World, startMinute: Long, endMinute: Long, startDay: Int = 0): RunResult {
        var deficit = tracker.initialState(startDay)
        val decisions = mutableListOf<DecisionRecord>()
        val trace = mutableListOf<TracePoint>()

        var windowOpenSince: Long? = null
        var deferredSince: Long? = null
        var lastPromptMinute: Long? = null
        var promptsToday = 0
        var currentDay = startDay
        var minutesSinceBottleSeen = 0
        val coverageWindow = ArrayDeque<Double>()

        for (minute in startMinute until endMinute) {
            val epoch = world.epochAt(minute)

            if (epoch.dayIndex != currentDay && epoch.localMinuteOfDay >= cfg.wakeAnchorMinute) {
                currentDay = epoch.dayIndex
                promptsToday = 0
            }

            deficit = tracker.fold(deficit, epoch, world.intakeAt(minute))

            minutesSinceBottleSeen =
                if (epoch.bottleMassG != null && EpochFlag.BOTTLE_GAP !in epoch.flags) 0
                else minutesSinceBottleSeen + 1

            coverageWindow.addLast(epoch.coverage)
            while (coverageWindow.size > cfg.policy.coverageWindowMinutes) coverageWindow.removeFirst()

            val isDecisionPoint = (minute - startMinute) % cfg.policy.slotMinutes == 0L
            if (!isDecisionPoint) continue

            val slotIndex = (minute - startMinute) / cfg.policy.slotMinutes
            val trailingCoverage =
                if (coverageWindow.isEmpty()) 0.0 else coverageWindow.average()

            val sweatRateLh = deficit.lastSweatRateLh
            val estimate = deficit.lastEstimate

            // L2. NeedContext carries physiology, environment and deficit — and nothing else (I1).
            val needOut = need.estimate(
                NeedContext(
                    deficitMl = deficit.deficitMl,
                    sweatRateLh = sweatRateLh,
                    insensibleRateLh = cfg.insensibleRateLh,
                    activityMet = estimate?.activityMet ?: 1.0,
                    ambientTempC = estimate?.ambientTempC ?: cfg.environment.assumedAmbientTempC,
                    relativeHumidityPct = epoch.relativeHumidityPct
                        ?: cfg.environment.assumedRelativeHumidityPct,
                    bodyMassKg = cfg.bodyMassKg,
                    coverage = trailingCoverage,
                )
            )

            windowOpenSince = when {
                needOut.windowOpen && windowOpenSince == null -> minute
                !needOut.windowOpen -> null
                else -> windowOpenSince
            }
            // A closed window ends any deferral: there is nothing left to defer.
            if (!needOut.windowOpen) deferredSince = null

            // L3. Scored on every decision point and logged, whether or not it is read (§5).
            val receptivityScore = receptivity.score(
                ReceptivityContext(
                    minutesSinceMidnight = epoch.localMinuteOfDay,
                    activityState = activityStateOf(estimate?.activityMet ?: 1.0),
                    minutesSinceLastPrompt = lastPromptMinute?.let { (minute - it).toInt() },
                    promptCountToday = promptsToday,
                    bottleMassG = epoch.bottleMassG,
                    dayOfWeek = (epoch.dayIndex % 7) + 1,
                )
            )

            val state = PolicyState(
                slotIndex = slotIndex,
                minuteOfDay = epoch.localMinuteOfDay,
                dayIndex = epoch.dayIndex,
                windowOpenMinutes = windowOpenSince?.let { (minute - it).toInt() } ?: 0,
                deferredMinutes = deferredSince?.let { (minute - it).toInt() } ?: 0,
                minutesSinceLastPrompt = lastPromptMinute?.let { (minute - it).toInt() },
                promptsToday = promptsToday,
                deviceUp = coverageWindow.takeLast(15).any { it > 0.0 },
                intakeObserved = minutesSinceBottleSeen <= INTAKE_UNOBSERVED_MINUTES,
                trailingCoverage = trailingCoverage,
                trainingWindows = cfg.trainingWindows,
                minCoverage = cfg.policy.minCoverage,
            )

            val decision = policy.decide(
                slotIndex = slotIndex,
                slotMinute = minute,
                need = needOut,
                receptivity = receptivityScore,
                state = state,
                deficitMl = deficit.deficitMl,
                deficitPctBm = deficit.deficitPctBm(cfg.bodyMassKg),
                coverage = trailingCoverage,
            )
            decisions += decision

            when {
                decision.action == Action.PROMPT -> {
                    lastPromptMinute = minute
                    promptsToday += 1
                    deferredSince = null
                    world.onPrompt(minute, decision.modality ?: Modality.ORDINARY)
                }
                // Receptivity declined this slot. Start the deferral clock if it is not running.
                decision.action == Action.NO_PROMPT && decision.trigger == Trigger.NONE ->
                    if (deferredSince == null) deferredSince = minute
            }

            trace += TracePoint(minute, epoch, deficit, needOut, decision)
        }

        return RunResult(decisions, trace, deficit)
    }

    companion object {
        /** Architecture §10: beyond this, the intake term is missing and the override suspends. */
        const val INTAKE_UNOBSERVED_MINUTES = 90

        fun activityStateOf(met: Double): ActivityState = when {
            met < 1.6 -> ActivityState.SEDENTARY
            met < 3.0 -> ActivityState.LIGHT
            met < 6.0 -> ActivityState.MODERATE
            else -> ActivityState.VIGOROUS
        }
    }
}

/**
 * The source of epochs and intake.
 *
 * Replay implements this from the log and ignores [onPrompt]; a simulated subject implements
 * it by reacting. Keeping it an interface is what lets the core stay free of I/O.
 */
interface World {
    fun epochAt(minute: Long): Epoch
    fun intakeAt(minute: Long): List<IntakeEvent> = emptyList()
    fun onPrompt(minute: Long, modality: Modality) {}
}

data class TracePoint(
    val minute: Long,
    val epoch: Epoch,
    val deficit: DeficitState,
    val need: health.hydration.core.need.NeedOutput,
    val decision: DecisionRecord,
)

data class RunResult(
    val decisions: List<DecisionRecord>,
    val trace: List<TracePoint>,
    val finalDeficit: DeficitState,
)
