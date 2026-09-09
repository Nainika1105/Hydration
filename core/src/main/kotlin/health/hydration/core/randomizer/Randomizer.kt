package health.hydration.core.randomizer

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Micro-randomization (Architecture §8).
 *
 * The assignment for a slot is a pure function of a sealed per-subject seed and the slot
 * index, which buys three things a random number generator cannot:
 *
 *  - **No re-rolling.** A crash, a reinstall or a clock correction cannot redraw a slot,
 *    because there is no persisted RNG state to lose or replay.
 *  - **Auditability.** At dataset freeze the analyst regenerates the entire sequence from
 *    the revealed seed and diffs it against the log. A mismatch is a protocol deviation with
 *    a row, not a discussion.
 *  - **Pre-commitment.** The seed's SHA-256 commitment is published before the subject's
 *    day 1, so the sequence cannot have been chosen after seeing any data.
 *
 * Eligibility is path-dependent — a prompt at k−1 may close the window at k — but the
 * assignment is not, which is what keeps the micro-randomized estimator valid.
 */
class Randomizer(private val seed: SealedSeed) {

    /** True means "prompt if eligible". Exactly p = 0.5. */
    fun assignment(slotIndex: Long): Boolean {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(seed.bytes, HMAC))
        val out = mac.doFinal("assign:$slotIndex".toByteArray(Charsets.UTF_8))
        return (out[0].toInt() and 1) == 1
    }

    companion object {
        private const val HMAC = "HmacSHA256"
    }
}

/**
 * A per-subject randomization seed. Drawn from a CSPRNG at enrollment and held sealed;
 * only its [commitment] is published before deployment.
 */
class SealedSeed(bytes: ByteArray) {
    init { require(bytes.size >= 16) { "seed must be at least 128 bits" } }

    internal val bytes: ByteArray = bytes.copyOf()

    /** SHA-256 over seed ‖ subjectId, published in the study log before day 1. */
    fun commitment(subjectId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.update(subjectId.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun fromHex(hex: String): SealedSeed =
            SealedSeed(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }
}
