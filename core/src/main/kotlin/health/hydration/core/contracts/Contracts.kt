package health.hydration.core.contracts

/**
 * Sensing and decision contracts.
 *
 * Everything in this file is a plain value. The core holds no clock, no I/O and no
 * mutable global state: time arrives as [Epoch.slotMinute], randomness arrives as a
 * sealed seed. That is what lets the deployed app and the offline replay run the same
 * code and produce the same decisions (Architecture §7).
 */

// ---------------------------------------------------------------------------
// Sensing
// ---------------------------------------------------------------------------

enum class EpochFlag {
    /** PPG rejected by the accelerometer-referenced gate. */
    PPG_ARTIFACT,

    /** Wearable link was down for part or all of the minute; samples came from backfill or not at all. */
    WEARABLE_GAP,

    /** Bottle was off-base or unreachable for the whole minute. */
    BOTTLE_GAP,

    /** Skin temperature sensor reported out of plausible range. */
    SKIN_TEMP_SUSPECT,
}

/**
 * One minute of sensing: the analysis-grade record (Architecture §9).
 *
 * [slotMinute] is monotonic minutes since the study anchor, derived from device uptime and
 * never from a wall clock that can step backwards. [dayIndex] and [localMinuteOfDay] are
 * supplied by the runtime, which owns timezone handling so the core does not have to.
 *
 * [coverage] is the fraction of the minute actually observed. A gap is not a missing value,
 * it is a wrong one that persists for the rest of the day, so coverage travels with the data.
 */
data class Epoch(
    val slotMinute: Long,
    val dayIndex: Int,
    val localMinuteOfDay: Int,
    val coverage: Double,
    val activityMet: Double? = null,
    val heartRateBpm: Double? = null,
    val hrValidFraction: Double = 0.0,
    val skinTempC: Double? = null,
    val ambientTempC: Double? = null,
    val relativeHumidityPct: Double? = null,
    val bottleMassG: Double? = null,
    val flags: Set<EpochFlag> = emptySet(),
) {
    init {
        require(coverage in 0.0..1.0) { "coverage out of range: $coverage" }
        require(hrValidFraction in 0.0..1.0) { "hrValidFraction out of range: $hrValidFraction" }
    }
}

enum class IntakeKind { DRINK, REFILL }

enum class IntakeSource { BOTTLE, MANUAL }

/**
 * A settled-to-settled mass transition on the bottle base.
 *
 * The load cell sits in the base, so intake is timestamped at return-to-base and
 * [offBaseMinutes] bounds the timing error. Harmless for the cumulative volume L1 needs;
 * material for RQ3's 30-minute outcome window (Architecture §9, open question Q3).
 */
data class IntakeEvent(
    val slotMinute: Long,
    val volumeMl: Double,
    val offBaseMinutes: Double,
    val kind: IntakeKind = IntakeKind.DRINK,
    val source: IntakeSource = IntakeSource.BOTTLE,
)

// ---------------------------------------------------------------------------
// Layer inputs
// ---------------------------------------------------------------------------

/**
 * Input to L2, the need estimator. **This type is the enforcement point for invariant I1.**
 *
 * It carries physiology, environment and accumulated deficit, and nothing else. Adding a
 * behavioural field here — time of day, prompt history, response history, bottle
 * availability — silently converts need into something a learned policy can influence,
 * which is precisely the failure mode C2 exists to rule out.
 *
 * `NeedContextIsolationTest` asserts the exact field set and fails the build on any addition.
 * Behavioural features live on [ReceptivityContext] and reach L3 only.
 */
data class NeedContext(
    val deficitMl: Double,
    val sweatRateLh: Double,
    val insensibleRateLh: Double,
    val activityMet: Double,
    val ambientTempC: Double,
    val relativeHumidityPct: Double,
    val bodyMassKg: Double,
    val coverage: Double,
)

enum class ActivityState { SEDENTARY, LIGHT, MODERATE, VIGOROUS }

/** Input to L3. The six features named in PD §6.2, and nothing beyond them. */
data class ReceptivityContext(
    val minutesSinceMidnight: Int,
    val activityState: ActivityState,
    val minutesSinceLastPrompt: Int?,
    val promptCountToday: Int,
    val bottleMassG: Double?,
    val dayOfWeek: Int,
)

// ---------------------------------------------------------------------------
// Decision output
// ---------------------------------------------------------------------------

enum class Action { PROMPT, NO_PROMPT, INELIGIBLE }

enum class Trigger {
    /** Unconditional physiological override (I2). Never randomized, excluded from RQ3. */
    HARD_FLOOR,

    /** Bounded deferral expired (I3). Never randomized, excluded from RQ3. */
    MAX_DEFER,

    /** The trial. Delivery follows the pre-drawn assignment for this slot. */
    RANDOMIZED,

    /** No decision was taken: the slot was ineligible. */
    NONE,
}

enum class Modality { ORDINARY, ESCALATED }

/**
 * Why a slot produced no randomized decision.
 *
 * [blocksOverride] is the single most consequential flag in the codebase. It is true only
 * for the three reasons that mean *the deficit estimate itself cannot be trusted*. It is
 * deliberately false for burden, timing and protocol convenience — cooldown, budget, quiet
 * hours, training hours — because suppressing a genuine 2%-body-mass prompt for any of
 * those reasons is exactly the failure C2 exists to prevent.
 */
enum class IneligibleReason(val blocksOverride: Boolean) {
    /** No wearable data at all. There is no deficit estimate to act on. */
    DEVICE_DOWN(blocksOverride = true),

    /** Trailing coverage below the configured minimum. The estimate is untrustworthy. */
    LOW_COVERAGE(blocksOverride = true),

    /** Bottle unreachable: the intake term is missing, so deficit is one-sided (Architecture §10). */
    INTAKE_UNOBSERVED(blocksOverride = true),

    /** Phone was not running. Recorded on restart so the denominator stays complete (I6). */
    PHONE_DOWN(blocksOverride = true),

    /** Supervised training: coaches enforce drinking, so the effect ceiling is near zero (PD §6.3). */
    TRAINING_HOURS(blocksOverride = false),

    QUIET_HOURS(blocksOverride = false),
    COOLDOWN(blocksOverride = false),
    BUDGET_EXHAUSTED(blocksOverride = false),

    /** Projected deficit below the window threshold. The ordinary "nothing to do" outcome. */
    WINDOW_CLOSED(blocksOverride = false),
}

/** The six version fields carried on every row (Architecture §11). */
data class Versions(
    val firmware: String,
    val app: String,
    val core: String,
    val config: String,
    val coefficients: String,
    val receptivityModel: String,
)

/**
 * Exactly one of these is written per 30-minute slot, including ineligible slots (I6).
 *
 * [assignment] is recorded even when the slot never became eligible, because the
 * denominator of every micro-randomized estimate is only trustworthy if the slots that
 * were drawn but not used are counted.
 */
data class DecisionRecord(
    val slotIndex: Long,
    val slotMinute: Long,
    val action: Action,
    val trigger: Trigger,
    val modality: Modality?,
    val randomized: Boolean,
    val assignment: Boolean,
    val ineligibleReason: IneligibleReason?,
    val need: Double,
    val windowOpen: Boolean,
    val windowOpenMinutes: Int,
    val deficitMl: Double,
    val deficitPctBm: Double,
    val projectedDeficitPctBm: Double,
    val receptivityScore: Double?,
    val receptivityShadowed: Boolean,
    val coverage: Double,
    val versions: Versions,
    val inputHash: String,
)
