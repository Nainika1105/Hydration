package health.hydration.core.invariants

import health.hydration.core.contracts.*
import health.hydration.core.fixtures.Fixtures
import health.hydration.core.fixtures.SyntheticSubject
import health.hydration.core.randomizer.Randomizer
import health.hydration.core.receptivity.ReceptivityModel
import health.hydration.core.receptivity.ReceptivityModelCard
import health.hydration.core.sim.StackRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Invariants I3, I4, I5 and I6, exercised over a simulated fourteen-day deployment. */
class PolicyStructureTest {

    private val cfg = Fixtures.config()

    private fun run(
        config: StudyConfig = cfg,
        receptivity: ReceptivityModel = Fixtures.receptivity(),
        subject: SyntheticSubject = SyntheticSubject(),
        days: Int = 14,
    ) = StackRunner(config, Randomizer(Fixtures.seed()), receptivity, Fixtures.versions())
        .run(subject, startMinute = 0, endMinute = days * 1440L)

    /**
     * **I3 — deferral is bounded.** No window stays open past `maxDeferMinutes` without a
     * delivery. This is what turns "receptivity may delay a prompt" into a bounded promise
     * rather than an open-ended one.
     */
    @Test
    fun `receptivity cannot defer a needed prompt past the bound`() {
        // Deferral only occurs when receptivity gates, so shadow mode must be off for this
        // invariant to have anything to say.
        val live = Fixtures.config(shadowReceptivity = false)
        val result = run(config = live)

        val deferrals = result.decisions.filter {
            it.action == Action.NO_PROMPT && it.trigger == Trigger.NONE
        }
        assertTrue(deferrals.isNotEmpty(), "test is vacuous: receptivity never deferred a prompt")

        val escapes = result.decisions.filter { it.trigger == Trigger.MAX_DEFER }
        assertTrue(escapes.isNotEmpty(), "test is vacuous: the deferral bound never fired")

        // Walk the run and check no deferral streak outlasts the bound.
        var deferredFor = 0
        result.decisions.forEach { d ->
            if (d.action == Action.PROMPT || !d.windowOpen) {
                deferredFor = 0
            } else if (d.action == Action.NO_PROMPT && d.trigger == Trigger.NONE) {
                deferredFor += live.policy.slotMinutes
                assertTrue(
                    deferredFor <= live.policy.maxDeferMinutes,
                    "I3 violated: a need-driven prompt was deferred $deferredFor min at slot " +
                        "${d.slotIndex}, past the ${live.policy.maxDeferMinutes} min bound"
                )
            }
        }
    }

    @Test
    fun `a window held at control by the randomizer is not treated as a deferral`() {
        // The control condition is half the estimator. If an open window that the randomizer
        // declined were escalated to a MAX_DEFER prompt, RQ3 would have no untreated
        // observations at persistent need, which is exactly where the effect lives.
        val result = run()
        val controlsAtOpenWindow = result.decisions.count {
            it.randomized && it.action == Action.NO_PROMPT && it.windowOpen
        }
        assertTrue(
            controlsAtOpenWindow > 0,
            "the trial produced no untreated observations at an open window; the deferral " +
                "bound is probably firing on window age instead of deferral length"
        )
    }

    /**
     * **I4 — learned parameters cannot reach policy constants.**
     *
     * The receptivity model is swapped for a differently-fitted one on every simulated day,
     * as it would be under online refitting. The constants object must be untouched.
     */
    @Test
    fun `refitting receptivity cannot move a policy constant`() {
        val before = cfg.policy.copy()
        var model = Fixtures.receptivity()

        repeat(14) { day ->
            // Stand-in for an offline refit landing as a new model card.
            model = ReceptivityModel(
                model.card.copy(
                    version = "fitted-$day",
                    coefficients = model.card.coefficients.map { it * 1.1 + 0.05 },
                    intercept = model.card.intercept - 0.03,
                    trainedRows = (day + 1) * 130,
                )
            )
            run(receptivity = model, days = 1)
        }

        assertEquals(
            before, cfg.policy,
            "I4 violated: policy constants differ after a simulated fortnight of refitting. " +
                "HARD_FLOOR, MAX_DEFER and theta are sealed config, never learned (PD §6.2)."
        )
        assertTrue(model.card.version == "fitted-13", "the model itself should have moved")
    }

    /**
     * **I5 — randomization applies only to the receptivity branch.**
     *
     * Override deliveries carry `randomized = false`, so the RQ3 analysis excludes them by a
     * query predicate rather than by a manual cleaning step (PD §7.2).
     */
    @Test
    fun `override prompts are never marked as trial data`() {
        val result = run()
        val overrides = result.decisions.filter {
            it.trigger == Trigger.HARD_FLOOR || it.trigger == Trigger.MAX_DEFER
        }
        assertTrue(overrides.isNotEmpty(), "simulation produced no override prompts to check")
        assertTrue(
            overrides.none { it.randomized },
            "I5 violated: ${overrides.count { it.randomized }} override prompts were flagged randomized"
        )

        val trial = result.decisions.filter { it.randomized }
        assertTrue(trial.isNotEmpty(), "simulation produced no randomized decision points")
        assertTrue(
            trial.all { it.trigger == Trigger.RANDOMIZED },
            "only the randomized branch may produce trial data"
        )
        // Delivery in the trial follows the pre-drawn assignment, and nothing else.
        assertTrue(
            trial.all { (it.action == Action.PROMPT) == it.assignment },
            "I5 violated: a randomized decision diverged from its pre-drawn assignment"
        )
    }

    /**
     * **I6 — every slot produces a row, including the ineligible ones.**
     *
     * The denominator of every micro-randomized estimate is the set of eligible decision
     * points. If the system is silent when it decides not to act, that set is unknowable
     * afterwards and the availability process cannot be described in the paper.
     */
    @Test
    fun `every elapsed slot writes exactly one record, with a reason when ineligible`() {
        val days = 14
        val result = run(days = days)
        val expected = days * 1440L / cfg.policy.slotMinutes

        assertEquals(
            expected, result.decisions.size.toLong(),
            "I6 violated: $days days at ${cfg.policy.slotMinutes}-minute slots is $expected " +
                "decision points, but ${result.decisions.size} rows were written"
        )
        assertEquals(
            (0 until expected).toList(), result.decisions.map { it.slotIndex },
            "slot indices must be contiguous and gap-free"
        )
        result.decisions.filter { it.action == Action.INELIGIBLE }.forEach {
            assertNotNull(it.ineligibleReason, "ineligible slot ${it.slotIndex} carries no reason")
        }
        // Assignments are recorded even for slots that never became eligible (Figure 4, k+2).
        assertTrue(
            result.decisions.filter { it.action == Action.INELIGIBLE }.any { it.assignment },
            "unused slots should still carry their pre-drawn assignment"
        )
    }

    /**
     * Architecture §5: during the MRT, receptivity is scored and logged on every decision
     * point but does not gate delivery. Flipping shadow mode off must change decisions —
     * if it does not, the shadow flag is not wired to anything.
     */
    @Test
    fun `shadow mode logs receptivity everywhere and gates nothing`() {
        val shadowed = run()
        assertTrue(
            shadowed.decisions.all { it.receptivityScore != null && it.receptivityShadowed },
            "receptivity must be scored and logged at every decision point, shadowed or not"
        )

        val live = run(config = Fixtures.config(shadowReceptivity = false))
        val deferredByReceptivity = live.decisions.count {
            it.action == Action.NO_PROMPT && it.trigger == Trigger.NONE
        }
        assertTrue(
            deferredByReceptivity > 0,
            "with shadow mode off, a low receptivity score should defer at least one prompt; " +
                "otherwise the flag is inert"
        )
        assertEquals(
            0, shadowed.decisions.count { it.action == Action.NO_PROMPT && it.trigger == Trigger.NONE },
            "in shadow mode nothing may be deferred on receptivity grounds"
        )
    }
}
