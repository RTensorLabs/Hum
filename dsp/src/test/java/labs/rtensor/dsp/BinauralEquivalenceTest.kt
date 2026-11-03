package labs.rtensor.dsp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * The optimised BinauralRenderer against the frozen 0.3.17 renderer (BaselineBinauralRenderer.kt)
 * on deterministic input: every layout and rate, regular and ragged blocks, convolution tails,
 * reset/seek, non-finite and clipping input, left/right orientation and output length.
 */
class BinauralEquivalenceTest {
    private fun direct(floats: Int) = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    private class Run(val left: FloatArray, val right: FloatArray)

    /** Renders [input] (interleaved, [channels]) through both renderers with the same block sequence. */
    private fun both(rate: Int, positions: List<SpeakerPosition>, input: FloatArray, blocks: IntArray,
                     gain: Float = 1f, resetAt: Int = -1): Pair<Run, Run> {
        val ch = positions.size; val frames = input.size / ch
        val current = BinauralRenderer(rate, positions, 512, gain)
        val baseline = BaselineBinauralRenderer(rate, positions, 512, gain)
        val runs = List(2) { Run(FloatArray(frames), FloatArray(frames)) }
        var at = 0; var i = 0
        while (at < frames) {
            val f = minOf(blocks[i++ % blocks.size], frames - at)
            if (resetAt in at until at + f) { current.reset(); baseline.reset() }
            for ((which, run) in runs.withIndex()) {
                val inBuf = direct(f * ch).also { it.asFloatBuffer().put(input, at * ch, f * ch) }
                val outBuf = direct(f * 2)
                if (which == 0) current.render(inBuf, outBuf, f) else baseline.render(inBuf, outBuf, f)
                assertEquals("input fully consumed", f * ch * 4, inBuf.position())
                assertEquals("output length", f * 8, outBuf.position())
                outBuf.flip()
                repeat(f) { t -> run.left[at + t] = outBuf.float; run.right[at + t] = outBuf.float }
            }
            at += f
        }
        return runs[0] to runs[1]
    }

    private fun maxError(a: Run, b: Run): Float {
        var e = 0f
        for (t in a.left.indices) { e = max(e, abs(a.left[t] - b.left[t])); e = max(e, abs(a.right[t] - b.right[t])) }
        return e
    }

    private fun assertMatches(what: String, a: Run, b: Run) {
        a.left.forEach { assertTrue("$what finite", it.isFinite()) }
        val e = maxError(a, b)
        val key = what.substringBefore(' ') + " " + what.split(' ')[1]
        worst[key] = max(worst[key] ?: 0f, e)
        assertTrue("$what: max |current - baseline| = $e > $TOLERANCE", e <= TOLERANCE)
        // The 16-speaker field feeds every harmonic, so its arithmetic is unchanged: bit-exact.
        if (what.startsWith("9.1.6")) assertEquals("$what must be bit-exact", 0f, e, 0f)
    }

    private fun noise(frames: Int, channels: Int, level: Float, seed: Long): FloatArray {
        val b = BinauralBenchmark.noise(frames, channels, seed); return FloatArray(frames * channels) { b.float * level / .17f }
    }

    @Test fun matchesTheFrozenRendererForEveryLayoutRateAndBlockPattern() {
        for ((name, positions) in BinauralBenchmark.LAYOUTS) for (rate in BinauralBenchmark.RATES) {
            val frames = rate / 4
            val input = noise(frames, positions.size, .17f, 7L + rate)
            for (blocks in listOf(intArrayOf(512), BinauralBenchmark.RAGGED, intArrayOf(1, 2, 3, 500), intArrayOf(7))) {
                val (c, b) = both(rate, positions, input, blocks)
                assertMatches("$name $rate ${blocks.toList()}", c, b)
            }
        }
    }

    @Test fun convolutionTailsCarryAcrossBlocksAndSilence() {
        for ((name, positions) in BinauralBenchmark.LAYOUTS) for (rate in BinauralBenchmark.RATES) {
            // A burst, then silence longer than the HRIR, rendered in small blocks so the tail spans many calls.
            val frames = 4096 * (rate / 48000) + 600
            val input = FloatArray(frames * positions.size)
            for (t in 0 until 40) for (ch in positions.indices) input[t * positions.size + ch] = if ((t + ch) % 3 == 0) .4f else -.3f
            val (c, b) = both(rate, positions, input, intArrayOf(5, 64, 1, 300))
            assertMatches("$name $rate tail", c, b)
            // The tail really is there and then decays to silence.
            assertTrue(abs(c.left[200]) > 0f)
            assertEquals(0f, c.left.last(), 1e-6f)
        }
    }

    @Test fun resetBehavesLikeTheBaselineAfterASeek() {
        for ((name, positions) in BinauralBenchmark.LAYOUTS) for (rate in BinauralBenchmark.RATES) {
            val input = noise(rate / 8, positions.size, .2f, 99L)
            val (c, b) = both(rate, positions, input, BinauralBenchmark.RAGGED, resetAt = rate / 16)
            assertMatches("$name $rate reset", c, b)
        }
    }

    @Test fun nonFiniteAndClippingInputMatchAndStayUnderTheCeiling() {
        for ((name, positions) in BinauralBenchmark.LAYOUTS) for (rate in BinauralBenchmark.RATES) {
            val input = noise(rate / 10, positions.size, 3f, 5L)
            input[17] = Float.NaN; input[400] = Float.POSITIVE_INFINITY; input[2001] = Float.NEGATIVE_INFINITY
            val (c, b) = both(rate, positions, input, BinauralBenchmark.RAGGED, gain = 2f)
            assertMatches("$name $rate clip", c, b)
            (c.left + c.right).forEach { assertTrue(abs(it) <= .9801f) }
        }
    }

    @Test fun leftAndRightKeepTheirOrientation() {
        for ((name, positions) in BinauralBenchmark.LAYOUTS) for (rate in BinauralBenchmark.RATES) {
            val sl = positions.indexOf(SpeakerPosition.SL)
            val input = FloatArray(2048 * positions.size).also { it[sl] = .5f }
            val (c, b) = both(rate, positions, input, intArrayOf(512))
            assertMatches("$name $rate SL", c, b)
            val l = c.left.sumOf { (it * it).toDouble() }; val r = c.right.sumOf { (it * it).toDouble() }
            assertTrue("$name $rate: SL must be louder on the left", l > 3 * r)
        }
    }

    @Test fun sparseLayoutsMatch() {
        // One speaker (6 or 10 active harmonics), the LFE alone (none) and centre plus LFE.
        val layouts = SpeakerPosition.entries.map { listOf(it) } + listOf(listOf(SpeakerPosition.FC, SpeakerPosition.LFE))
        for (positions in layouts) for (rate in listOf(48000, 192000)) {
            val input = noise(rate / 20, positions.size, .3f, 3L)
            val (c, b) = both(rate, positions, input, BinauralBenchmark.RAGGED)
            assertMatches("sparse $rate $positions", c, b)
        }
    }

    companion object {
        /**
         * The 1e-5 tolerance BinauralRendererTest already uses. Horizontal layouts skip their silent
         * height harmonics, which re-pairs the FFTs and changes float rounding only; the measured
         * worst case is recorded in dsp/SPATIAL_PERFORMANCE.md (about -110 dBFS).
         */
        const val TOLERANCE = 1e-5f
        private val worst = sortedMapOf<String, Float>()
        @org.junit.AfterClass @JvmStatic fun report() {
            worst.forEach { (k, v) -> println("BinauralEquivalenceTest worst |current - baseline| $k: $v") }
        }
    }
}
