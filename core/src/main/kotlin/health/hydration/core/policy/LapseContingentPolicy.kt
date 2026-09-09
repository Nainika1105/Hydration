package health.hydration.core.policy

import health.hydration.core.contracts.IntakeEvent
import health.hydration.core.contracts.IntakeKind

/**
 * The baseline every deployed hydration reminder implements: prompt when a fixed hourly
 * intake target has not been met (PD §2.4, sipIT [11]; commercial bottles [9]).
 *
 * It exists here for one reason: RQ4 compares receptivity-aware timing against a simulated
 * lapse-contingent policy **on identical logged data** (PD §7.4). Both arms of that
 * comparison are simulated, so this has to be a first-class policy in the core rather than a
 * spreadsheet done afterwards.
 *
 * Note what it does not consult: physiology, environment, or anything about whether the user
 * can act. That is the entire point of the comparison — it is what the field currently does.
 */
class LapseContingentPolicy(
    private val hourlyTargetMl: Double = 200.0,
    private val cooldownMinutes: Int = 60,
    private val quietHoursStart: Int = 22 * 60,
    private val quietHoursEnd: Int = 6 * 60,
) {
    private val recent = ArrayDeque<IntakeEvent>()
    private var lastPromptMinute: Long? = null

    fun observe(intake: List<IntakeEvent>) {
        recent += intake.filter { it.kind == IntakeKind.DRINK }
    }

    fun shouldPrompt(minute: Long, minuteOfDay: Int): Boolean {
        while (recent.isNotEmpty() && minute - recent.first().slotMinute > 60) recent.removeFirst()

        val inQuietHours =
            if (quietHoursStart <= quietHoursEnd) minuteOfDay in quietHoursStart until quietHoursEnd
            else minuteOfDay >= quietHoursStart || minuteOfDay < quietHoursEnd
        if (inQuietHours) return false

        val cooling = lastPromptMinute?.let { minute - it < cooldownMinutes } ?: false
        if (cooling) return false

        val lastHourMl = recent.sumOf { it.volumeMl }
        if (lastHourMl >= hourlyTargetMl) return false

        lastPromptMinute = minute
        return true
    }
}
