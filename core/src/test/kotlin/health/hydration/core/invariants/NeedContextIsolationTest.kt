package health.hydration.core.invariants

import health.hydration.core.contracts.NeedContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Invariant I1 — the need estimator never sees behaviour.**
 *
 * This is the enforcement mechanism for the project's central structural claim (C2): a
 * learned receptivity model must be incapable of influencing whether a physiological need
 * window opens. Prose in a design document cannot enforce that. Two checks can.
 */
class NeedContextIsolationTest {

    /**
     * Semantic half: the exact field set of [NeedContext].
     *
     * Adding a behavioural field — time of day, prompt history, response history, bottle
     * availability — fails here. If a field genuinely belongs in need, this list is where
     * that argument has to be made and recorded.
     */
    @Test
    fun `NeedContext carries physiology, environment and deficit only`() {
        val allowed = setOf(
            "deficitMl",
            "sweatRateLh",
            "insensibleRateLh",
            "activityMet",
            "ambientTempC",
            "relativeHumidityPct",
            "bodyMassKg",
            "coverage",
        )
        val actual = NeedContext::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            allowed, actual,
            "NeedContext's field set changed. Any addition here must be justified as " +
                "physiological, environmental, or deficit state — never behavioural (I1)."
        )
    }

    /**
     * Structural half: no import edge from `need/` to anything behavioural.
     *
     * The semantic check above can be defeated by reaching around the context type — calling
     * the receptivity model directly, or reading policy state. This check cannot.
     */
    @Test
    fun `need module does not depend on receptivity, policy or randomization`() {
        val needDir = sourceDir("need")
        val sources = needDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "found no sources under $needDir")

        val forbidden = listOf("receptivity", "policy", "randomizer", "ReceptivityContext", "DecisionRecord")
        val violations = mutableListOf<String>()

        for (file in sources) {
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//").trim()
                if (!code.startsWith("import ")) return@forEachIndexed
                forbidden.firstOrNull { code.contains(it, ignoreCase = false) }?.let {
                    violations += "${file.name}:${index + 1}  $code   [matched '$it']"
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "L2 must not be able to observe behaviour (I1). Offending imports:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    private fun sourceDir(layer: String): File {
        // Gradle runs tests with the module directory as the working directory.
        val dir = File("src/main/kotlin/health/hydration/core/$layer")
        assertTrue(dir.isDirectory, "expected source directory at ${dir.absolutePath}")
        return dir
    }
}
