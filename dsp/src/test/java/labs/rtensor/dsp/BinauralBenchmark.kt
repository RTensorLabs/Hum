package labs.rtensor.dsp

import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reproducible Spatial (binaural) render benchmark. Not a unit test and not part of the app:
 * run it by hand on the test classpath, e.g.
 *
 *   java -cp <dsp test+main classes and resources> labs.rtensor.dsp.BinauralBenchmark [current|baseline|both] [layout]
 *
 * "baseline" is the frozen 0.3.17 renderer (BaselineBinauralRenderer.kt); "current" is BinauralRenderer.
 * Input is deterministic synthetic noise. Times are JVM wall-clock on the host it runs on and do
 * not represent an Android phone (ART, a different CPU and thermal limits).
 */
object BinauralBenchmark {
    private interface Subject { fun render(input: ByteBuffer, output: ByteBuffer, frames: Int); fun reset() }
    private fun subject(kind: String, rate: Int, positions: List<SpeakerPosition>, maxFrames: Int): Subject = when (kind) {
        "baseline" -> BaselineBinauralRenderer(rate, positions, maxFrames, 1f).let { r -> object : Subject {
            override fun render(input: ByteBuffer, output: ByteBuffer, frames: Int) = r.render(input, output, frames)
            override fun reset() = r.reset() } }
        else -> BinauralRenderer(rate, positions, maxFrames, 1f).let { r -> object : Subject {
            override fun render(input: ByteBuffer, output: ByteBuffer, frames: Int) = r.render(input, output, frames)
            override fun reset() = r.reset() } }
    }

    val LAYOUTS = linkedMapOf(
        "5.1" to listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE, SpeakerPosition.SL, SpeakerPosition.SR),
        "7.1" to listOf(SpeakerPosition.FL, SpeakerPosition.FR, SpeakerPosition.FC, SpeakerPosition.LFE,
            SpeakerPosition.BL, SpeakerPosition.BR, SpeakerPosition.SL, SpeakerPosition.SR),
        "9.1.6 field" to SpeakerPosition.entries.sortedBy { it.id },
    )
    val RATES = listOf(48000, 96000, 192000)
    const val MAX_FRAMES = 512
    /** Includes 1-frame, odd, power-of-two-boundary and full blocks. */
    val RAGGED = intArrayOf(512, 37, 1, 255, 256, 257, 511, 128, 480, 3, 512, 441)

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun allocated() = threads.getThreadAllocatedBytes(Thread.currentThread().id)
    /** Bytes that two back-to-back allocated() calls report by themselves; subtracted from every reading. */
    private val probeCost: Long by lazy { LongArray(101) { val a = allocated(); allocated() - a }.sorted()[50] }

    /** Deterministic noise, about -20 dBFS per channel, with occasional loud peaks for the limiter. */
    fun noise(frames: Int, channels: Int, seed: Long = 12345): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.nativeOrder())
        var s = seed
        repeat(frames * channels) { i ->
            s = s * 6364136223846793005L + 1442695040888963407L
            val u = ((s ushr 40).toInt() and 0xFFFFFF) / 16777216f * 2f - 1f
            b.putFloat(if (i % 9973 == 0) u * 1.5f else u * .17f)
        }
        return b.flip() as ByteBuffer
    }

    class Result(val label: String, val initMedianUs: Double, val blockUs: DoubleArray, val msPerAudioSecond: List<Double>,
                 val firstSizeAlloc: Map<Int, Long>, val steadyAllocPerBlock: Double)

    private fun pct(sorted: DoubleArray, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    fun run(kind: String, layout: String, rate: Int, ragged: Boolean, seconds: Int = 6, trials: Int = 5): Result {
        val positions = LAYOUTS.getValue(layout)
        val ch = positions.size
        // Initialisation (HRIR cache already warm after the first construction at this rate).
        val inits = DoubleArray(7) { val t = System.nanoTime(); subject(kind, rate, positions, MAX_FRAMES); (System.nanoTime() - t) / 1e3 }
        val source = noise(rate, ch) // one second, looped
        val out = ByteBuffer.allocateDirect(MAX_FRAMES * 8).order(ByteOrder.nativeOrder())
        val sizes = if (ragged) RAGGED else intArrayOf(MAX_FRAMES)
        // Allocation on the first occurrence of each block size, on a fresh renderer.
        val firstAlloc = LinkedHashMap<Int, Long>()
        run {
            val r = subject(kind, rate, positions, MAX_FRAMES)
            for (f in sizes.distinct()) {
                val input = source.duplicate().order(ByteOrder.nativeOrder()); input.limit(f * ch * 4)
                out.clear(); val a = allocated(); r.render(input, out, f); firstAlloc[f] = allocated() - a - probeCost
            }
        }
        val r = subject(kind, rate, positions, MAX_FRAMES)
        val times = ArrayList<Double>(); val perSecond = ArrayList<Double>()
        var steadyAlloc = 0L; var steadyBlocks = 0
        for (trial in -1 until trials) { // trial -1 is warm-up
            r.reset()
            val total = rate * seconds; var done = 0; var i = 0; var elapsed = 0L
            val input = source.duplicate().order(ByteOrder.nativeOrder())
            while (done < total) {
                val f = minOf(sizes[i++ % sizes.size], total - done) // final block may be short
                if (input.remaining() < f * ch * 4) input.position(0)
                val view = input.slice().order(ByteOrder.nativeOrder()); view.limit(f * ch * 4)
                out.clear()
                val a = allocated()
                val t = System.nanoTime(); r.render(view, out, f); val dt = System.nanoTime() - t
                val bytes = allocated() - a - probeCost
                input.position(input.position() + f * ch * 4)
                elapsed += dt; done += f
                if (trial >= 0) { times += dt / 1e3; steadyBlocks++; steadyAlloc += bytes }
            }
            if (trial >= 0) perSecond += elapsed / 1e6 / seconds
        }
        val sorted = times.toDoubleArray().also { it.sort() }
        return Result("$kind ${layout.padEnd(11)} ${rate / 1000} kHz ${if (ragged) "ragged" else "512   "}",
            inits.sorted()[3], sorted, perSecond.sorted(), firstAlloc, steadyAlloc.toDouble() / steadyBlocks)
    }

    @JvmStatic fun main(args: Array<String>) {
        val kinds = when (args.firstOrNull() ?: "both") { "both" -> listOf("baseline", "current"); else -> listOf(args[0]) }
        val rt = Runtime.getRuntime()
        println("# allocation probe overhead subtracted: $probeCost B")
        println("# ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, ${System.getProperty("os.arch")}, ${rt.availableProcessors()} CPUs")
        // Cold HRIR load and upsampling, once per process (the first renderer at each rate pays it).
        for (rate in RATES) {
            val t = System.nanoTime(); Hrir.forRate(rate); BaselineHrir.forRate(rate)
            println("# cold HRIR load/upsample at ${rate / 1000} kHz (both copies): ${"%.1f".format((System.nanoTime() - t) / 1e6)} ms")
        }
        println("| renderer / layout / rate / blocks | init median µs | block median µs | p95 | p99 | max | ms per audio s (median of trials) | first-use alloc per block size (bytes) | steady alloc B/block |")
        println("|---|---|---|---|---|---|---|---|---|")
        val only = args.getOrNull(1) // optional layout filter, e.g. "9.1.6 field"
        for (layout in LAYOUTS.keys.filter { only == null || it == only }) for (rate in RATES) for (ragged in listOf(false, true)) for (kind in kinds) {
            val r = run(kind, layout, rate, ragged)
            val b = r.blockUs
            println("| ${r.label} | ${"%.0f".format(r.initMedianUs)} | ${"%.1f".format(pct(b, .5))} | ${"%.1f".format(pct(b, .95))} | ${"%.1f".format(pct(b, .99))} | ${"%.0f".format(b.last())} | " +
                "${"%.2f".format(r.msPerAudioSecond[r.msPerAudioSecond.size / 2])} | ${r.firstSizeAlloc.entries.joinToString(" ") { "${it.key}:${it.value}" }} | ${"%.1f".format(r.steadyAllocPerBlock)} |")
        }
    }
}
