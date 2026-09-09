package health.hydration.core.fixtures

import health.hydration.core.contracts.*
import health.hydration.core.randomizer.SealedSeed
import health.hydration.core.receptivity.ReceptivityModel
import health.hydration.core.receptivity.ReceptivityModelCard
import health.hydration.core.sim.World
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A synthetic athlete for exercising the stack without hardware.
 *
 * Environmental and physiological signals are pure functions of the minute, so a run is
 * reproducible regardless of call order. Only the drinking response is stateful, because it
 * genuinely depends on what the policy did — which is the point.
 *
 * This is a test fixture, not a validation instrument. It cannot tell us whether the sweat
 * model is right; only the scale method can do that (PD §7.2). What it can do is exercise
 * every branch of the policy against plausible physiology.
 */
class SyntheticSubject(
    private val salt: Long = 7L,
    private val bodyMassKg: Double = 70.0,
    private val trainStartMinute: Int = 17 * 60,
    private val trainLengthMinutes: Int = 90,
    /** Outdoor temperature during the training session. The heat-exposed part of the day. */
    private val peakTempC: Double = 33.0,
    /**
     * Indoor ambient for the rest of the day. A university athlete is outdoors for training
     * and indoors otherwise; modelling the whole day at the outdoor peak produces roughly
     * 5.5 L/day of loss, which is a desert expedition rather than a campus.
     */
    private val indoorTempC: Double = 26.0,
    private val humidityPct: Double = 62.0,
    private val responsiveness: Double = 0.60,
    /**
     * Unprompted intake per day, in mL: meals, habit, and coach-enforced drinking during
     * supervised training (PD §6.3 — "during supervised training, coaches enforce drinking").
     *
     * Deliberately set below typical daily loss. That gap is the study's entire premise: a
     * chronic mild deficit the prompting system exists to close. A subject who drank nothing
     * unprompted would dehydrate to clinically implausible levels and starve the trial of
     * randomized decision points, because the hard-floor override would eat every slot.
     */
    private val spontaneousDailyMl: Double = 1000.0,
    private val wearableGap: LongRange? = null,
    private val bottleGap: LongRange? = null,
) : World {

    private val scheduledDrinks = sortedMapOf<Long, MutableList<IntakeEvent>>()
    private var bottleMassG = 750.0

    override fun epochAt(minute: Long): Epoch {
        val minuteOfDay = (minute % 1440L).toInt()
        val dayIndex = (minute / 1440L).toInt()
        val inTraining = minuteOfDay >= trainStartMinute &&
            minuteOfDay < trainStartMinute + trainLengthMinutes

        val met = when {
            inTraining -> {
                val frac = (minuteOfDay - trainStartMinute).toDouble() / trainLengthMinutes
                6.8 + 2.4 * sin(PI * frac) + 0.5 * noise(minute, 1)
            }
            minuteOfDay in (trainStartMinute + trainLengthMinutes)..(trainStartMinute + trainLengthMinutes + 40) ->
                2.2 + 0.4 * noise(minute, 2)
            minuteOfDay < 6 * 60 || minuteOfDay >= 23 * 60 -> 0.85 + 0.08 * noise(minute, 3)
            else -> 1.35 + 0.25 * sin((minuteOfDay - 360) / 90.0) + 0.2 * noise(minute, 4)
        }

        val diurnal = -cos((minuteOfDay - 300) / 1440.0 * 2 * PI)
        val ambientC =
            if (inTraining) peakTempC + 2.0 * diurnal
            else indoorTempC + 2.0 * diurnal - (if (minuteOfDay < 6 * 60) 1.5 else 0.0)
        val rh = humidityPct - 6 * diurnal
        val skinC = 32.4 + 0.34 * (met - 1) + 0.11 * (ambientC - 25) + 0.15 * noise(minute, 5)
        val hr = 58 + 11.5 * (met - 1) + 0.9 * maxOf(0.0, ambientC - 28) + 2.0 * noise(minute, 6)

        val wearableDown = wearableGap?.contains(minute) == true
        val bottleDown = bottleGap?.contains(minute) == true

        // PPG degrades badly during running — the artifact problem PD §7.6 flags as a
        // high-likelihood risk. Skin temperature carries signal when HR does not.
        val ppgValid = if (inTraining) 0.25 else 0.9

        return Epoch(
            slotMinute = minute,
            dayIndex = dayIndex,
            localMinuteOfDay = minuteOfDay,
            coverage = if (wearableDown) 0.0 else 1.0,
            activityMet = if (wearableDown) null else met,
            heartRateBpm = if (wearableDown || ppgValid < 0.5) null else hr,
            hrValidFraction = if (wearableDown) 0.0 else ppgValid,
            skinTempC = if (wearableDown) null else skinC,
            ambientTempC = if (wearableDown) null else ambientC,
            relativeHumidityPct = if (wearableDown) null else rh,
            bottleMassG = if (bottleDown) null else bottleMassG,
            flags = buildSet {
                if (wearableDown) add(EpochFlag.WEARABLE_GAP)
                if (bottleDown) add(EpochFlag.BOTTLE_GAP)
                if (ppgValid < 0.5) add(EpochFlag.PPG_ARTIFACT)
            },
        )
    }

    override fun intakeAt(minute: Long): List<IntakeEvent> {
        val prompted = scheduledDrinks.remove(minute) ?: mutableListOf()
        val all = prompted + spontaneousAt(minute)
        all.forEach { bottleMassG = (bottleMassG - it.volumeMl).coerceAtLeast(60.0) }
        return all
    }

    /**
     * Unprompted drinking, a pure function of the minute.
     *
     * Three sources, because a subject with only one of them misrepresents the problem:
     * meals anchor most daily intake, habitual sips fill the gaps, and training sessions are
     * supervised so drinking there is enforced rather than voluntary.
     */
    private fun spontaneousAt(minute: Long): List<IntakeEvent> {
        val minuteOfDay = (minute % 1440L).toInt()
        val inTraining = minuteOfDay >= trainStartMinute &&
            minuteOfDay < trainStartMinute + trainLengthMinutes

        fun event(volume: Double) =
            listOf(IntakeEvent(minute, volume, offBaseMinutes = 1.0 + noise01(minute, 21) * 3.0))

        // Coach-enforced drinking every 20 minutes through a supervised session. This is why
        // PD §7.2 puts MRT decision points outside training hours: the effect ceiling there
        // is near zero because the behaviour is already happening.
        if (inTraining && (minuteOfDay - trainStartMinute) % 20 == 0) {
            return event(180.0 + noise01(minute, 22) * 120.0)
        }

        val mealShare = spontaneousDailyMl * 0.45 / 3.0
        if (minuteOfDay in setOf(7 * 60 + 30, 13 * 60, 20 * 60)) return event(mealShare)

        // Habitual sips across waking hours, carrying the remaining share.
        val sipMinutes = listOf(9, 10, 11, 12, 14, 15, 16, 21, 22).map { it * 60 + 20 }
        if (minuteOfDay in sipMinutes && noise01(minute, 23) < 0.75) {
            return event(spontaneousDailyMl * 0.55 / 7.0)
        }
        return emptyList()
    }

    override fun onPrompt(minute: Long, modality: Modality) {
        val minuteOfDay = (minute % 1440L).toInt()
        val inTraining = minuteOfDay >= trainStartMinute &&
            minuteOfDay < trainStartMinute + trainLengthMinutes

        // Escalated prompts land harder; mid-training prompts land softer. This is the
        // receptivity structure the study exists to measure, so the fixture only asserts
        // that such a structure is plausible, never what its parameters are.
        val p = responsiveness * (if (modality == Modality.ESCALATED) 1.35 else 1.0) *
            (if (inTraining) 0.45 else 1.0)

        if (noise01(minute, 11) < p) {
            val latency = 3 + (noise01(minute, 12) * 22).toInt()
            val volume = 140.0 + noise01(minute, 13) * 220.0
            scheduledDrinks.getOrPut(minute + latency) { mutableListOf() }
                .add(IntakeEvent(minute + latency, volume, offBaseMinutes = latency.toDouble()))
        }
    }

    /** Deterministic per-minute noise in [-1, 1]; pure in (minute, channel). */
    private fun noise(minute: Long, channel: Int): Double = noise01(minute, channel) * 2 - 1

    private fun noise01(minute: Long, channel: Int): Double {
        var h = minute * 0x9E3779B97F4A7C15uL.toLong() + channel * 0x632BE59BD9B4E019L + salt
        h = h xor (h ushr 33); h *= -0xae502812aa7333L
        h = h xor (h ushr 29); h *= -0x3b314601e57a13adL
        h = h xor (h ushr 32)
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(0.0, 1.0)
    }
}

/** Standard config used across tests, so a change in defaults surfaces in one place. */
object Fixtures {

    fun config(
        variant: Variant = Variant.FULL,
        shadowReceptivity: Boolean = true,
        policy: PolicyConstants = PolicyConstants(),
        trainingWindows: List<MinuteWindow> = listOf(MinuteWindow(17 * 60, 19 * 60)),
        mrtMode: MrtMode = MrtMode.RANDOMIZED,
    ) = StudyConfig(
        subjectId = "S-TEST-01",
        configVersion = "cfg-test-1",
        bodyMassKg = 70.0,
        heightM = 1.78,
        variant = variant,
        coefficients = SweatCoefficients(version = "coeff-population-0"),
        policy = policy,
        trainingWindows = trainingWindows,
        shadowReceptivity = shadowReceptivity,
        mrtMode = mrtMode,
        seedCommitment = "test-commitment",
    )

    fun seed(): SealedSeed = SealedSeed.fromHex("00112233445566778899aabbccddeeff")

    fun receptivity(): ReceptivityModel = ReceptivityModel(ReceptivityModelCard.populationPrior())

    fun versions() = Versions(
        firmware = "fw-test", app = "app-test", core = health.hydration.core.CoreVersion.VALUE,
        config = "cfg-test-1", coefficients = "coeff-population-0", receptivityModel = "prior-0",
    )
}
