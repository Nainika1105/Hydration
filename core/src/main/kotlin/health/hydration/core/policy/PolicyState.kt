package health.hydration.core.policy

import health.hydration.core.contracts.IneligibleReason
import health.hydration.core.contracts.MinuteWindow
import health.hydration.core.contracts.PolicyConstants

/**
 * Everything the policy needs to know about the run so far.
 *
 * The split between [blockingReason] and [protocolReason] is the most consequential
 * distinction in this file, and it is the code form of the note in Architecture §10:
 *
 *  - **Blocking** reasons mean the deficit estimate cannot be trusted. The override is
 *    suspended, because firing on a deficit whose intake term is missing generates false
 *    escalated prompts, which burdens the participant and corrupts the evidence C1 rests on.
 *  - **Protocol** reasons are burden, timing and convenience. They gate the randomized
 *    branch and never the override, because a genuine 2%-body-mass deficit at 23:00, or
 *    twenty minutes after the last prompt, is still a 2% deficit.
 */
data class PolicyState(
    val slotIndex: Long,
    val minuteOfDay: Int,
    val dayIndex: Int,
    val windowOpenMinutes: Int,
    /**
     * Minutes since the receptivity model first deferred a prompt inside the current window.
     * Zero when nothing is being deferred.
     *
     * Deliberately NOT the same as [windowOpenMinutes]. A window that stays open while the
     * randomizer draws control is the trial working as designed; forcing a prompt there would
     * destroy the control condition and with it RQ3. Only a receptivity-driven delay is
     * bounded by I3.
     */
    val deferredMinutes: Int = 0,
    val minutesSinceLastPrompt: Int?,
    val promptsToday: Int,
    val deviceUp: Boolean,
    val intakeObserved: Boolean,
    val trailingCoverage: Double,
    val trainingWindows: List<MinuteWindow>,
    val minCoverage: Double,
) {

    /** Reasons that suspend the hard-floor override. Data integrity only. */
    fun blockingReason(): IneligibleReason? = when {
        !deviceUp -> IneligibleReason.DEVICE_DOWN
        !intakeObserved -> IneligibleReason.INTAKE_UNOBSERVED
        trailingCoverage < minCoverage -> IneligibleReason.LOW_COVERAGE
        else -> null
    }

    /** Reasons that gate the randomized branch. Never the override. */
    fun protocolReason(c: PolicyConstants): IneligibleReason? = when {
        trainingWindows.any { it.contains(minuteOfDay) } -> IneligibleReason.TRAINING_HOURS
        c.quietHours.contains(minuteOfDay) -> IneligibleReason.QUIET_HOURS
        minutesSinceLastPrompt != null && minutesSinceLastPrompt < c.cooldownMinutes ->
            IneligibleReason.COOLDOWN
        promptsToday >= c.maxPromptsPerDay -> IneligibleReason.BUDGET_EXHAUSTED
        else -> null
    }

    init {
        require(blockingReason()?.blocksOverride != false) {
            "a blocking reason must have blocksOverride = true"
        }
    }
}
