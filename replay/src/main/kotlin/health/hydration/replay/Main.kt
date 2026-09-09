package health.hydration.replay

import health.hydration.core.contracts.*
import health.hydration.core.fixtures.Fixtures
import health.hydration.core.fixtures.SyntheticSubject
import health.hydration.core.policy.LapseContingentPolicy
import health.hydration.core.randomizer.Randomizer
import health.hydration.core.sim.RunResult
import health.hydration.core.sim.StackRunner
import health.hydration.core.sim.World

/**
 * The replay CLI (Architecture §7).
 *
 * Its production job is to take a subject's logged epochs and re-derive every decision, so
 * that `replay(log).decisions == log.decisions` can be asserted nightly during deployment.
 * Until logs exist, it runs the same core against a synthetic subject, which is what makes
 * the four-layer stack inspectable end to end before any hardware is built.
 *
 * Usage:  ./gradlew :replay:run --args="--days 14 --variant FULL"
 */
fun main(args: Array<String>) {
    val opts = Options.parse(args)

    val cfg = Fixtures.config(variant = opts.variant, shadowReceptivity = !opts.liveReceptivity)
    val seed = Fixtures.seed()

    println(header(cfg, seed.commitment(cfg.subjectId), opts))

    val subject = SyntheticSubject(salt = opts.salt)
    val result = StackRunner(cfg, Randomizer(seed), Fixtures.receptivity(), Fixtures.versions())
        .run(subject, startMinute = 0, endMinute = opts.days * 1440L)

    if (opts.traceDay != null) printDayTrace(result, opts.traceDay)
    printSummary(result, cfg, opts.days)
    printRq4Comparison(cfg, seed, opts)
    if (opts.sweep) printThresholdSweep(opts)
    printReplayCheck(cfg, seed, opts)
}

/**
 * The window threshold is the parameter with the most leverage over the trial.
 *
 * PD §7.3 builds its power calculation on roughly 14 eligible decision points per subject-day.
 * That number is an assumption, not a measurement, and it follows directly from where the
 * window opens. This sweep makes the dependency visible before the threshold is written into
 * a pre-registration.
 */
private fun printThresholdSweep(o: Options) {
    println()
    println("─".repeat(96))
    println("  WINDOW THRESHOLD SWEEP — eligible decision points vs. the PD §7.3 assumption")
    println("─".repeat(96))
    println("  window%BM   eligible/day   hard floors   peak %BM   min/day in deficit")

    listOf(0.2, 0.3, 0.4, 0.5, 0.7, 1.0, 1.3).forEach { threshold ->
        val cfg = Fixtures.config(
            policy = PolicyConstants(windowOpenPctBm = threshold, needFloorPctBm = threshold / 2)
        )
        val r = StackRunner(cfg, Randomizer(Fixtures.seed()), Fixtures.receptivity(), Fixtures.versions())
            .run(SyntheticSubject(salt = o.salt), 0, o.days * 1440L)
        val eligible = r.decisions.count { it.randomized }.toDouble() / o.days
        val hardFloors = r.decisions.count { it.trigger == Trigger.HARD_FLOOR }
        val peak = r.trace.maxOf { it.deficit.deficitPctBm(cfg.bodyMassKg) }
        val inDeficit = r.trace.count { it.deficit.deficitPctBm(cfg.bodyMassKg) >= threshold } *
            cfg.policy.slotMinutes / o.days
        val flag = if (eligible >= 12.0) "   <- meets the PD §7.3 assumption" else ""
        println("  %8.1f   %12.1f   %11d   %8.2f   %18d%s"
            .format(threshold, eligible, hardFloors, peak, inDeficit, flag))
    }
    println()
    println("  Yield is a property of the cohort's water balance, not just the threshold. A")
    println("  subject in near-neutral balance produces few eligible points at any threshold,")
    println("  which is why the power calculation PD §7.3 defers cannot be asserted from the")
    println("  slot arithmetic alone.")
}

// ---------------------------------------------------------------------------

private fun header(cfg: StudyConfig, commitment: String, o: Options) = buildString {
    appendLine("=".repeat(96))
    appendLine("  HYDRATION STACK — simulated deployment")
    appendLine("  Physiologically-Triggered, Receptivity-Aware Hydration Prompting")
    appendLine("=".repeat(96))
    appendLine("  subject          ${cfg.subjectId}   ${cfg.bodyMassKg} kg, ${cfg.heightM} m")
    appendLine("  ablation variant ${cfg.variant}")
    appendLine("  receptivity      ${if (cfg.shadowReceptivity) "SHADOW — scored and logged, gates nothing (Arch §5)"
                                     else "LIVE — gating at theta = ${cfg.policy.theta}"}")
    appendLine("  hard floor       ${cfg.policy.hardFloorPctBm}% body mass, unconditional (I2)")
    appendLine("  max deferral     ${cfg.policy.maxDeferMinutes} min (I3)")
    appendLine("  seed commitment  ${commitment.take(16)}…   published before day 1 (Arch §8)")
    appendLine("  duration         ${o.days} days, ${cfg.policy.slotMinutes}-minute decision slots")
    appendLine()
    append("  NOTE: synthetic physiology. This exercises the stack; it validates nothing.")
    appendLine(" Only the")
    appendLine("        scale method against real subjects can do that (PD §7.2, weeks 8–11).")
}

private fun printDayTrace(result: RunResult, day: Int) {
    println()
    println("─".repeat(96))
    println("  DAY $day DECISION TRACE   (one row per slot — every slot writes a row, I6)")
    println("─".repeat(96))
    println("  slot   time   deficit   %BM    need  win  A   recept  outcome")

    result.trace.filter { it.epoch.dayIndex == day }.forEach { t ->
        val d = t.decision
        val clock = "%02d:%02d".format(t.epoch.localMinuteOfDay / 60, t.epoch.localMinuteOfDay % 60)
        val outcome = when (d.action) {
            Action.PROMPT -> when (d.trigger) {
                Trigger.HARD_FLOOR -> "PROMPT  ** HARD FLOOR, escalated **"
                Trigger.MAX_DEFER -> "PROMPT  (deferral bound reached)"
                else -> "PROMPT  (randomized)"
            }
            Action.NO_PROMPT -> if (d.randomized) "control (randomized, A=0)" else "deferred on receptivity"
            Action.INELIGIBLE -> "ineligible  ${d.ineligibleReason}"
        }
        println(
            "  %4d  %5s  %6.0f mL  %4.2f  %5.2f   %s   %d   %5.2f  %s".format(
                d.slotIndex, clock, d.deficitMl, d.deficitPctBm, d.need,
                if (d.windowOpen) "Y" else "·", if (d.assignment) 1 else 0,
                d.receptivityScore ?: -1.0, outcome,
            )
        )
    }
}

private fun printSummary(result: RunResult, cfg: StudyConfig, days: Int) {
    val d = result.decisions
    println()
    println("─".repeat(96))
    println("  DECISION POINT LEDGER   ($days days)")
    println("─".repeat(96))

    val randomized = d.filter { it.randomized }
    val treated = randomized.count { it.action == Action.PROMPT }
    val hardFloor = d.count { it.trigger == Trigger.HARD_FLOOR }
    val maxDefer = d.count { it.trigger == Trigger.MAX_DEFER }

    println("  slots elapsed                    ${d.size}")
    println("  randomized (the trial, RQ3)      ${randomized.size}   treated $treated / control ${randomized.size - treated}")
    println("  hard-floor overrides             $hardFloor   excluded from RQ3 by randomized=false (I5)")
    println("  deferral-bound prompts           $maxDefer   excluded from RQ3 (I5)")
    println()
    println("  ineligible slots by reason:")
    d.filter { it.action == Action.INELIGIBLE }
        .groupingBy { it.ineligibleReason!! }.eachCount()
        .toList().sortedByDescending { it.second }
        .forEach { (reason, n) ->
            val flag = if (reason.blocksOverride) "  <- also suspends the hard floor" else ""
            println("    %-20s %5d  (%4.1f%%)%s".format(reason, n, 100.0 * n / d.size, flag))
        }

    if (randomized.isNotEmpty()) {
        val p = treated.toDouble() / randomized.size
        println()
        println("  randomization balance            p = %.4f  (target 0.5000)".format(p))
    }

    // RQ4's primary measure. Reported here so the number the study turns on is visible from
    // the first day of engineering, not first computed in week 15.
    val threshold = cfg.policy.windowOpenPctBm
    val minutesInDeficit = result.trace.count { it.deficit.deficitPctBm(cfg.bodyMassKg) >= threshold } *
        cfg.policy.slotMinutes
    println("  time above %.1f%% body mass       %d min/day  (RQ4 primary measure)"
        .format(threshold, minutesInDeficit / days))
    println("  peak deficit                     %.2f%% body mass"
        .format(result.trace.maxOf { it.deficit.deficitPctBm(cfg.bodyMassKg) }))

    // Water balance, without which the ledger above cannot be read: a starved trial and a
    // runaway trial look identical in the counts but have opposite causes.
    val lastOfDay = result.trace.groupBy { it.epoch.dayIndex }.mapValues { (_, v) -> v.last().deficit }
    val avgLoss = lastOfDay.values.map { it.lossMlToday }.average()
    val avgIntake = lastOfDay.values.map { it.intakeMlToday }.average()
    println()
    println("  daily water balance              loss %.0f mL   intake %.0f mL   net %+.0f mL (%.2f%% BM)"
        .format(avgLoss, avgIntake, avgLoss - avgIntake, (avgLoss - avgIntake) / (cfg.bodyMassKg * 1000) * 100))
    println("  eligible points per day          %.1f   (PD §7.3 power calculation assumes ~14)"
        .format(randomized.size.toDouble() / days))
}

/**
 * RQ4 in miniature: the adaptive policy against the lapse-contingent baseline the field
 * currently deploys, on a subject with identical physiology and identical responsiveness.
 */
private fun printRq4Comparison(cfg: StudyConfig, seed: health.hydration.core.randomizer.SealedSeed, o: Options) {
    println()
    println("─".repeat(96))
    println("  RQ4 SHAPE — deployed deficit-triggered vs. lapse-contingent, identical subject")
    println("─".repeat(96))

    // The deployed policy, not the trial arm. During the MRT half of all needed prompts are
    // deliberately withheld to create the control condition, so comparing that arm against an
    // always-on baseline measures the randomization rather than the policy.
    val deployedCfg = Fixtures.config(
        variant = cfg.variant,
        shadowReceptivity = cfg.shadowReceptivity,
        mrtMode = MrtMode.DEPLOYED,
    )
    val adaptive = StackRunner(deployedCfg, Randomizer(seed), Fixtures.receptivity(), Fixtures.versions())
        .run(SyntheticSubject(salt = o.salt), 0, o.days * 1440L)

    val baselineSubject = SyntheticSubject(salt = o.salt)
    val baseline = LapseContingentPolicy()
    var baselinePrompts = 0
    val deficits = DoubleArray(o.days * 1440)
    run {
        val tracker = health.hydration.core.deficit.DeficitTracker(cfg)
        var state = tracker.initialState(0)
        for (minute in 0 until o.days * 1440L) {
            val epoch = baselineSubject.epochAt(minute)
            val intake = baselineSubject.intakeAt(minute)
            baseline.observe(intake)
            state = tracker.fold(state, epoch, intake)
            deficits[minute.toInt()] = state.deficitPctBm(cfg.bodyMassKg)
            if (minute % cfg.policy.slotMinutes == 0L &&
                baseline.shouldPrompt(minute, epoch.localMinuteOfDay)
            ) {
                baselinePrompts++
                baselineSubject.onPrompt(minute, Modality.ORDINARY)
            }
        }
    }

    val threshold = cfg.policy.windowOpenPctBm
    val adaptiveMinutes = adaptive.trace.count { it.deficit.deficitPctBm(cfg.bodyMassKg) >= threshold } *
        cfg.policy.slotMinutes / o.days
    val baselineMinutes = deficits.count { it >= threshold } / o.days
    val adaptivePrompts = adaptive.decisions.count { it.action == Action.PROMPT }

    println("                              deficit-triggered     lapse-contingent")
    println("  prompts delivered           %17d %20d".format(adaptivePrompts, baselinePrompts))
    println("  prompts per day             %17.1f %20.1f"
        .format(adaptivePrompts.toDouble() / o.days, baselinePrompts.toDouble() / o.days))
    println("  min/day above %.1f%% BM      %17d %20d".format(threshold, adaptiveMinutes, baselineMinutes))
    println("  peak deficit  (%% BM)        %17.2f %20.2f"
        .format(adaptive.trace.maxOf { it.deficit.deficitPctBm(cfg.bodyMassKg) }, deficits.max()))
    println()
    println("  These numbers are a plumbing check, not a result. The comparison is only")
    println("  meaningful on randomized field data with a scale-validated deficit trace.")
}

/** The nightly integrity check of weeks 12–13 (PD §7.5), run against the synthetic day. */
private fun printReplayCheck(cfg: StudyConfig, seed: health.hydration.core.randomizer.SealedSeed, o: Options) {
    fun once() = StackRunner(cfg, Randomizer(seed), Fixtures.receptivity(), Fixtures.versions())
        .run(SyntheticSubject(salt = o.salt), 0, o.days * 1440L).decisions

    val a = once()
    val b = once()
    val mismatches = a.zip(b).count { (x, y) -> x != y }
    println()
    println("─".repeat(96))
    println("  REPLAY DETERMINISM   replay(log).decisions == log.decisions")
    println("─".repeat(96))
    println("  decisions compared  ${a.size}")
    println("  mismatches          $mismatches")
    println(if (mismatches == 0) "  PASS — the deployed decision and the replayed decision are the same computation."
            else "  FAIL — investigate before any analysis is run.")
    println()
}

// ---------------------------------------------------------------------------

private data class Options(
    val days: Int = 14,
    val variant: Variant = Variant.FULL,
    val liveReceptivity: Boolean = false,
    val traceDay: Int? = 1,
    val salt: Long = 7L,
    val sweep: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var o = Options()
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--days" -> o = o.copy(days = args[++i].toInt())
                    "--variant" -> o = o.copy(variant = Variant.valueOf(args[++i]))
                    "--live-receptivity" -> o = o.copy(liveReceptivity = true)
                    "--trace-day" -> o = o.copy(traceDay = args[++i].toIntOrNull())
                    "--no-trace" -> o = o.copy(traceDay = null)
                    "--salt" -> o = o.copy(salt = args[++i].toLong())
                    "--sweep" -> o = o.copy(sweep = true)
                    else -> error("unknown argument: ${args[i]}")
                }
                i++
            }
            return o
        }
    }
}
