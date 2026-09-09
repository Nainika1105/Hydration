package health.hydration.core.policy

import health.hydration.core.contracts.Action
import health.hydration.core.contracts.DecisionRecord
import health.hydration.core.contracts.IneligibleReason
import health.hydration.core.contracts.Modality
import health.hydration.core.contracts.MrtMode
import health.hydration.core.contracts.PolicyConstants
import health.hydration.core.contracts.Trigger
import health.hydration.core.contracts.Versions
import health.hydration.core.need.NeedOutput
import health.hydration.core.randomizer.Randomizer
import java.security.MessageDigest

/**
 * L4 — the policy (PD §6.2).
 *
 * **The ordering is the safety mechanism.** Each branch returns, so no later condition can
 * revisit an earlier decision, and the hard-floor branch is unreachable-from-below by
 * construction rather than by discipline:
 *
 *     0. data-integrity gates   — the only reasons that can suppress the override
 *     1. hard floor             — unconditional (I2)
 *     2. bounded deferral       — (I3)
 *     3. protocol gates         — cooldown, budget, quiet hours, training hours
 *     4. randomized branch      — the trial
 *     5. window closed
 *
 * The design invariant of PD §6.2 stated as code: low receptivity may delay a need-driven
 * prompt, it may never cancel one. Note what `receptivity` does in [decide] — it is scored,
 * carried, and logged, and read only when shadow mode is off (Architecture §5).
 */
class Policy(
    private val c: PolicyConstants,
    private val randomizer: Randomizer,
    private val shadowReceptivity: Boolean,
    private val versions: Versions,
    private val mrtMode: MrtMode = MrtMode.RANDOMIZED,
) {

    fun decide(
        slotIndex: Long,
        slotMinute: Long,
        need: NeedOutput,
        receptivity: Double?,
        state: PolicyState,
        deficitMl: Double,
        deficitPctBm: Double,
        coverage: Double,
    ): DecisionRecord {

        // The assignment is drawn for every slot and recorded even when unused, so the
        // denominator of the trial stays complete (I6).
        val assignment = randomizer.assignment(slotIndex)

        fun record(
            action: Action,
            trigger: Trigger,
            modality: Modality?,
            randomized: Boolean,
            reason: IneligibleReason?,
        ) = DecisionRecord(
            slotIndex = slotIndex,
            slotMinute = slotMinute,
            action = action,
            trigger = trigger,
            modality = modality,
            randomized = randomized,
            assignment = assignment,
            ineligibleReason = reason,
            need = need.need,
            windowOpen = need.windowOpen,
            windowOpenMinutes = state.windowOpenMinutes,
            deficitMl = deficitMl,
            deficitPctBm = deficitPctBm,
            projectedDeficitPctBm = need.projectedDeficitPctBm,
            receptivityScore = receptivity,
            receptivityShadowed = shadowReceptivity,
            coverage = coverage,
            versions = versions,
            inputHash = inputHash(slotIndex, need, receptivity, state, deficitMl, coverage),
        )

        // 0 — Data-integrity gates. These are the ONLY reasons that suppress the override,
        //     because each one means the deficit estimate itself cannot be trusted. Burden,
        //     timing and protocol convenience are handled at step 3 and never reach here.
        val blocking = state.blockingReason()
        if (blocking != null) {
            return record(Action.INELIGIBLE, Trigger.NONE, null, randomized = false, reason = blocking)
        }

        // 1 — Hard floor. Unconditional (I2). Returns before receptivity, cooldown, the daily
        //     budget, quiet hours, training hours, or the randomizer is reachable.
        if (need.projectedDeficitPctBm >= c.hardFloorPctBm) {
            return record(Action.PROMPT, Trigger.HARD_FLOOR, Modality.ESCALATED, randomized = false, reason = null)
        }

        // 2 — Bounded deferral (I3). Receptivity may delay a need-driven prompt, never cancel
        //     it. This counts minutes DEFERRED, not minutes the window has been open: under
        //     the MRT the randomizer routinely holds an open window at control, and that is
        //     the trial, not a deferral to escape from.
        if (need.windowOpen && state.deferredMinutes >= c.maxDeferMinutes) {
            return record(Action.PROMPT, Trigger.MAX_DEFER, Modality.ORDINARY, randomized = false, reason = null)
        }

        // 3 — Protocol gates. Eligibility, not safety.
        val gate = state.protocolReason(c)
        if (gate != null) {
            return record(Action.INELIGIBLE, Trigger.NONE, null, randomized = false, reason = gate)
        }

        // 4 — The randomized branch. This is the trial.
        if (need.windowOpen) {
            // Shadow mode: during the MRT, receptivity is scored and logged but does not gate
            // delivery, so treatment is not entangled with the score (Architecture §5).
            val receptivityAllows =
                shadowReceptivity || (receptivity != null && receptivity >= c.theta)

            if (!receptivityAllows) {
                // Deferral, not cancellation. Step 2 will fire once the window has been open
                // for maxDeferMinutes, which is what makes this a delay and not a veto.
                return record(Action.NO_PROMPT, Trigger.NONE, null, randomized = false, reason = null)
            }

            // Under DEPLOYED the assignment is still drawn and logged, but delivery follows
            // need rather than the draw. Keeping the draw in the record means a deployed run
            // and a trial run remain directly comparable slot by slot.
            if (mrtMode == MrtMode.DEPLOYED) {
                return record(Action.PROMPT, Trigger.RANDOMIZED, Modality.ORDINARY, randomized = false, reason = null)
            }

            return if (assignment) {
                record(Action.PROMPT, Trigger.RANDOMIZED, Modality.ORDINARY, randomized = true, reason = null)
            } else {
                record(Action.NO_PROMPT, Trigger.RANDOMIZED, null, randomized = true, reason = null)
            }
        }

        // 5 — Nothing to do.
        return record(Action.INELIGIBLE, Trigger.NONE, null, randomized = false, reason = IneligibleReason.WINDOW_CLOSED)
    }

    /**
     * SHA-256 over the canonical decision inputs.
     *
     * What makes a replay mismatch localizable: if `replay(log) != log`, this says whether
     * the inputs differed or only the outputs did (Architecture §7).
     */
    private fun inputHash(
        slotIndex: Long,
        need: NeedOutput,
        receptivity: Double?,
        state: PolicyState,
        deficitMl: Double,
        coverage: Double,
    ): String {
        val canonical = buildString {
            append(slotIndex); append('|')
            append(fmt(deficitMl)); append('|')
            append(fmt(need.projectedDeficitPctBm)); append('|')
            append(need.windowOpen); append('|')
            append(state.windowOpenMinutes); append('|')
            append(state.promptsToday); append('|')
            append(state.minutesSinceLastPrompt ?: -1); append('|')
            append(fmt(coverage)); append('|')
            append(receptivity?.let { fmt(it) } ?: "null"); append('|')
            append(state.deviceUp); append('|')
            append(state.intakeObserved); append('|')
            append(state.minuteOfDay)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Fixed precision so a float printed on two platforms hashes the same. */
    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.6f", v)
}
