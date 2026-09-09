package health.hydration.core.contracts

/**
 * Sealed per-subject configuration (Architecture §11).
 *
 * One signed document per subject, immutable for the duration of the deployment. Everything
 * that can change a decision lives here, so a mid-study parameter change is a visible
 * `config_version` bump and a protocol-deviation row rather than an invisible edit.
 */

/** RQ1 ablation variants, selectable by flag (PD §6.2). D is the honest baseline. */
enum class Variant {
    /** A — context only: activity intensity, ambient T, RH. Skin temperature is estimated. */
    CONTEXT_ONLY,

    /** B — physiology only: HR and skin temperature. Activity is inferred from HR. */
    PHYSIOLOGY_ONLY,

    /** C — full: everything measured. */
    FULL,

    /** D — a single per-person sweat rate from one session, temperature-scaled. */
    FIXED_RATE,
}

/**
 * Per-user coefficients.
 *
 * The heat-balance structure is settled physiology; what is individualized is the scaling
 * and intercept fitted from calibration sessions (PD §6.2, gap G1). Before three sessions
 * exist, [scale] = 1.0 and [intercept] = 0.0 leave the population model untouched.
 */
data class SweatCoefficients(
    val version: String,
    val scale: Double = 1.0,
    val intercept: Double = 0.0,

    /** Mechanical efficiency: ~0 for level running, ~0.20–0.23 for cycle ergometry. */
    val mechanicalEfficiency: Double = 0.0,

    /** Combined clothing correction on dry and evaporative heat exchange. 1.0 is nude. */
    val clothingFactor: Double = 0.9,

    /** Skin-temperature estimator used by variant A, where skin T is not measured. */
    val skinTempBaseC: Double = 32.4,
    val skinTempPerMet: Double = 0.34,
    val skinTempPerAmbientC: Double = 0.11,

    /** HR-to-MET inversion used by variant B, where activity is not measured. */
    val restingHrBpm: Double = 58.0,
    val hrBpmPerMet: Double = 11.5,

    /** Variant D only: the fixed per-person rate and its temperature scaling. */
    val fixedRateLh: Double = 0.8,
    val fixedRateRefTempC: Double = 25.0,
    val fixedRatePerDegC: Double = 0.06,
) {
    /** Fitted coefficients exist only after enough calibration sessions (PD §6.2: ≥3). */
    val isIndividualized: Boolean get() = scale != 1.0 || intercept != 0.0
}

/** Environmental values the wearable does not measure; supplied as sealed defaults. */
data class EnvironmentDefaults(
    /** Relative air velocity in m/s. Running generates its own airflow; resting does not. */
    val airVelocityMs: Double = 0.6,
    val airVelocityActiveMs: Double = 2.0,

    /** Substituted for ambient T and RH in variant B, where they are excluded by design. */
    val assumedAmbientTempC: Double = 25.0,
    val assumedRelativeHumidityPct: Double = 55.0,
)

/** A minute-of-day window. Wraps midnight when [startMinute] > [endMinute]. */
data class MinuteWindow(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute
}

/**
 * Policy constants. **None of these is learned (I4).**
 *
 * They are constructed once from the sealed config and the receptivity learner holds no
 * reference to this object. `PolicyConstantsImmutableTest` asserts they are byte-identical
 * before and after a simulated fourteen-day run.
 */
data class PolicyConstants(
    /** ~2% body mass. The unconditional override threshold (PD §6.2). */
    val hardFloorPctBm: Double = 2.0,

    /**
     * Projected deficit at which an intervention window opens.
     *
     * PD §6.2 specifies the 2% hard floor but leaves this threshold open, and it is the
     * single parameter with the most leverage over the trial: it sets how many decision
     * points become eligible, and therefore the power calculation PD §7.3 requires. 0.5%
     * sits well below the ~2% level at which performance decrements are documented
     * (PD §1.1), so a window opens long before the deficit matters. Sweep it with
     * `:replay:run --args="--sweep"` before fixing it in the pre-registration.
     */
    val windowOpenPctBm: Double = 0.5,

    /** Below this, need is reported as zero. */
    val needFloorPctBm: Double = 0.2,

    /** An open window may be deferred no longer than this (I3). */
    val maxDeferMinutes: Int = 60,

    val cooldownMinutes: Int = 45,

    /** Receptivity gate. Read only when shadow mode is off (Architecture §5, open question Q1). */
    val theta: Double = 0.35,

    val minCoverage: Double = 0.7,
    val coverageWindowMinutes: Int = 60,
    val quietHours: MinuteWindow = MinuteWindow(22 * 60, 6 * 60),
    val slotMinutes: Int = 30,
    val maxPromptsPerDay: Int = 12,

    /** Forward projection horizon for L2 (PD §6.2: 30–60 min). */
    val projectionHorizonMinutes: Int = 45,
)

/**
 * Whether the policy is running the trial or running as it would be deployed.
 *
 * [RANDOMIZED] is the 14-day MRT: eligible decision points follow the pre-drawn assignment,
 * so roughly half of all needed prompts are deliberately withheld to create the control
 * condition. [DEPLOYED] is the policy as it would actually run afterwards, and is the arm
 * RQ4 compares against the lapse-contingent baseline (PD §7.4). Comparing a randomized arm
 * to an always-on baseline measures the randomization, not the policy.
 */
enum class MrtMode { RANDOMIZED, DEPLOYED }

data class StudyConfig(
    val subjectId: String,
    val configVersion: String,
    val bodyMassKg: Double,
    val heightM: Double,
    val variant: Variant,
    val coefficients: SweatCoefficients,
    val policy: PolicyConstants = PolicyConstants(),
    val environment: EnvironmentDefaults = EnvironmentDefaults(),

    /**
     * Minutes since local midnight at which the deficit integral re-anchors to zero.
     *
     * Open question Q2: PD §6.2 does not define this, and insensible loss continues
     * overnight with no intake, so an integral that never re-anchors grows without bound.
     * Overnight accrual is reported separately rather than folded in. The rule is a sealed
     * constant because it changes every deficit trace in the dataset.
     */
    val wakeAnchorMinute: Int = 6 * 60,

    val trainingWindows: List<MinuteWindow> = emptyList(),

    /**
     * Architecture §5. During the MRT, L3 scores every decision point and the score is
     * logged, but it does not gate delivery: live behaviour reduces to eligibility, the
     * pre-drawn assignment, and the hard-floor override. Setting this false makes
     * receptivity live, which requires [PolicyConstants.theta] to be pre-registered.
     */
    val shadowReceptivity: Boolean = true,

    /** See [MrtMode]. The deployment runs RANDOMIZED; RQ4 simulations run DEPLOYED. */
    val mrtMode: MrtMode = MrtMode.RANDOMIZED,

    /** Fixed literature constant; not modelled (PD §6.2). */
    val insensibleRateLh: Double = 0.035,

    /**
     * Floor on the deficit integral, as % body mass. Surplus below this is treated as
     * excreted rather than banked.
     *
     * PD §6.2 writes the balance as ∫loss − ∫intake with no renal term, so as specified the
     * integral can run arbitrarily negative and a subject who over-drank at lunch would carry
     * that credit into evening training and never open a window. Bodies do not bank fluid
     * that way: intake above euhydration is largely excreted within an hour or two.
     *
     * Flooring at zero is the conservative simplification — it discards the genuine short
     * buffer a pre-session drink provides. Set a small negative value (e.g. -0.3) to allow
     * one. Either way the excreted volume is tracked so the daily water balance still closes.
     * Modelling excretion properly is scope the project document does not currently carry.
     */
    val surplusFloorPctBm: Double = 0.0,

    /** Published SHA-256 commitment of the randomization seed (Architecture §8). */
    val seedCommitment: String = "",
) {
    init {
        require(bodyMassKg > 0) { "bodyMassKg must be positive" }
        require(heightM > 0) { "heightM must be positive" }
        require(policy.windowOpenPctBm < policy.hardFloorPctBm) {
            "window threshold must sit below the hard floor, else the ordinary branch is unreachable"
        }
    }
}
