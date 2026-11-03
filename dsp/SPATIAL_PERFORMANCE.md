# Spatial (binaural) renderer performance

Scope: `BinauralRenderer.render()` only. The binaural output, HRIR asset, gains, limiter, LFE path,
speaker directions, normalisation and supported rates (48/96/192 kHz) are unchanged. Stereo bypass,
Original and Processed are not touched by this change.

## What changed

1. **No allocation on the audio thread.** The baseline built the FFT and the 16 HRIR spectra for a
   transform size lazily, inside `render()`, the first time a block of that size class arrived
   (for example a short final block, or the first block after a seek). This allocated 68–271 KB on
   the playback thread and ran up to 16 FFTs in that call. Every size a renderer can use (a 1-frame
   block up to `maxFrames`) is now built in the constructor. That is 2–3 sizes. It is a table lookup
   at render time, and the arithmetic is identical.
2. **Silent harmonics are skipped.** In a horizontal layout (5.1, 7.1) no channel feeds the height
   harmonics (ACN 2, 5, 7, 10, 12, 14). The baseline still transformed and convolved them every
   block. Only harmonics that some channel feeds are now paired into the complex FFTs: 5 forward FFTs
   instead of 8 for 5.1/7.1. The 16-speaker field feeds all 16 harmonics, so it keeps exactly the
   same pairs and arithmetic, and its output is bit-identical.
3. Minor: the tail overlap-add loop no longer iterates `frames + tail` times to touch `tail` samples.

These areas were tried and left as they were. Each measured slower for the field or more
sensitive to JIT profiles:
- inlining the per-bin accumulate;
- a separate encode loop over only the active harmonics;
- a special path for an unpaired harmonic.

An odd harmonic out is paired with a silent one, which is what the baseline did for every pair.
No real speaker position set produces an odd count: they all give 6, 10 or 16 active harmonics.

## Equivalence with the frozen baseline

`dsp/src/test/java/labs/rtensor/dsp/BaselineBinauralRenderer.kt` is a frozen copy of the 0.3.17
renderer (7d0a6af), with its classes renamed. `BinauralEquivalenceTest` renders the same deterministic
input through both, on the same block sequence, for every layout and rate. It covers:
- 512-frame and ragged blocks (1, 3, 37, 255–257, 441, 480, 511 and 512 frames, plus a short
  final block);
- convolution tails across silence;
- `reset()` part-way through, as a seek does;
- NaN and ±Inf input, and 6× overdriven input through the limiter;
- left/right orientation;
- input consumed and output length on every call;
- single-speaker layouts, the LFE alone, and centre plus LFE.

Measured worst |current − baseline| per output sample (the output is limited to ±0.98):

| layout | 48 kHz | 96 kHz | 192 kHz |
|---|---|---|---|
| 9.1.6 field | 0 (bit-exact, asserted) | 0 | 0 |
| 5.1 | 2.8e-6 | 1.8e-6 | 1.1e-6 |
| 7.1 | 2.1e-6 | 2.5e-6 | 8.6e-7 |
| single speaker / LFE / FC+LFE | 1.8e-7 | – | 8.9e-8 |

The differences come only from float rounding, because different harmonics now share an FFT. The
worst case is about −111 dBFS, in the overdriven limiter case. The test uses the 1e-5 tolerance that
`BinauralRendererTest` already uses for block-size independence.

## Benchmark

**Measured on a cloud VM, not a phone:**
- OpenJDK 64-Bit Server VM 21.0.12.1, amd64;
- 4 vCPUs, Intel Xeon @ 2.80 GHz, shared with other tenants.

These are JVM (HotSpot C2) wall-clock times. Android ART, a phone CPU and thermal limits will
give different absolute numbers. Only the ratios and the allocation counts carry over with any
confidence, and even the ratios are an estimate for ART, not a measurement.

**Method** (`BinauralBenchmark`, test sources):
- The real `BinauralRenderer` and bundled HRIR asset, with `maxFrames` 512 as `DownmixAudioSink` uses.
- Deterministic LCG noise at about −20 dBFS, with occasional 1.5× peaks.
- Per configuration: 7 constructions for the init time; a fresh renderer fed each block size once for
  first-use allocation; one warm-up pass, then 5 trials of 6 s of audio.
- Per-block `nanoTime` and per-thread allocated bytes are measured around `render()` only.
- "ragged" cycles the block sizes listed above.
- Baseline and current run in the same process, interleaved per configuration.

| layout / rate / blocks | init µs before → after | block median µs before → after | p99 µs before → after | ms per audio second before → after | change |
|---|---|---|---|---|---|
| 5.1 48 kHz 512 | 2244 → 1521 | 229.4 → 170.9 | 578.3 → 495.0 | 27.12 → 18.23 | -33% |
| 5.1 48 kHz ragged | 454 → 689 | 127.1 → 97.0 | 471.0 → 291.6 | 27.91 → 20.43 | -27% |
| 5.1 96 kHz 512 | 426 → 1113 | 230.6 → 170.9 | 524.1 → 467.7 | 46.35 → 36.49 | -21% |
| 5.1 96 kHz ragged | 428 → 513 | 224.0 → 164.3 | 571.8 → 373.3 | 78.20 → 58.65 | -25% |
| 5.1 192 kHz 512 | 1094 → 1674 | 452.6 → 316.2 | 1037.4 → 720.1 | 184.69 → 130.54 | -29% |
| 5.1 192 kHz ragged | 929 → 1087 | 433.7 → 303.3 | 977.0 → 657.7 | 313.42 → 209.06 | -33% |
| 7.1 48 kHz 512 | 393 → 519 | 259.2 → 185.7 | 620.5 → 582.2 | 26.63 → 20.00 | -25% |
| 7.1 48 kHz ragged | 538 → 615 | 206.8 → 109.1 | 605.0 → 385.7 | 33.97 → 23.49 | -31% |
| 7.1 96 kHz 512 | 750 → 668 | 249.2 → 186.9 | 689.4 → 440.9 | 54.21 → 41.50 | -23% |
| 7.1 96 kHz ragged | 544 → 706 | 232.0 → 159.5 | 607.6 → 323.7 | 75.60 → 53.27 | -30% |
| 7.1 192 kHz 512 | 1057 → 1116 | 468.0 → 338.0 | 1253.1 → 780.7 | 184.56 → 134.59 | -27% |
| 7.1 192 kHz ragged | 1099 → 1114 | 449.5 → 316.7 | 1075.7 → 696.0 | 330.18 → 218.61 | -34% |
| 9.1.6 field 48 kHz 512 | 501 → 951 | 323.2 → 321.7 | 892.2 → 756.4 | 36.66 → 31.83 | -13% |
| 9.1.6 field 48 kHz ragged | 499 → 888 | 164.2 → 179.7 | 663.2 → 703.6 | 35.27 → 38.51 | +9% |
| 9.1.6 field 96 kHz 512 | 493 → 870 | 317.4 → 324.6 | 813.9 → 816.3 | 66.91 → 70.05 | +5% |
| 9.1.6 field 96 kHz ragged | 563 → 775 | 277.1 → 280.5 | 685.2 → 602.9 | 91.72 → 96.62 | +5% |
| 9.1.6 field 192 kHz 512 | 1040 → 1420 | 539.7 → 536.2 | 1167.2 → 1266.8 | 213.80 → 222.88 | +4% |
| 9.1.6 field 192 kHz ragged | 868 → 1412 | 489.9 → 490.9 | 1109.0 → 969.5 | 333.58 → 331.96 | -0% |

**Run-to-run noise.** On this shared VM it is about ±10% on medians and much more on p99/max. Treat
the field rows as unchanged: the field runs the same arithmetic as before. In two further field-only
runs (`both "9.1.6 field"`), current against baseline ranged from −11% to +12% per row, with
no consistent direction. The 5.1/7.1 improvement was 21–34% in the run above. Earlier runs of
intermediate versions with the same FFT changes showed 18–35%.

**Allocation** (per-thread allocated bytes around `render()`):

| | baseline | current |
|---|---|---|
| first block of a new FFT size, 48 kHz | 68,624 B (1 frame), 136,208 B (37 frames) | 0 B |
| first block of a new FFT size, 96 kHz | 136,208 B (1 frame) | 0 B |
| first block of a new FFT size, 192 kHz | 271,376 B (1 frame) | 0 B |
| steady state, per block | 32 B (the `HashMap` boxed `Int` key in `getOrPut`) | 0 B |

The current renderer still shows 16 B on the first call of some block sizes and 0 B on others. This
is not explained, and steady state is 0 B.

**Init cost moved.** Construction now builds 2–3 FFT sizes instead of 1, adding about 0.1–0.6 ms
on this host. The renderer is constructed when the sink is configured, not per block. Before this
change the same work was paid inside the first `render()` that needed it. Memory for the spectra
grows with `maxFrames`:
- About 0.4 MB at 192 kHz with the sink's 512.
- About 4 MB at the allowed maximum of 8192 frames, which the app does not use.

**Cold HRIR load and upsampling** (first renderer at each rate in a process): about 6–16 ms. This is
unchanged and not part of this change.

## Findings left for later (not changed)

- **Small blocks at high rates are expensive per second of audio.** The zero-latency design sizes
  the FFT by the HRIR length (1024 taps at 192 kHz), not by the block. A 1-frame block costs almost
  as much as a 512-frame block, which is why ragged 192 kHz costs about 1.6× the 512-frame figure.
  Fixing it would mean partitioned convolution or added latency, a DSP redesign that is out of scope.
- **The 16-speaker field is the heaviest case**, at about 22% of one host core at 192 kHz. Its cost
  is the 8 forward FFTs plus the spectral accumulate. Further gains (for example exploiting
  Hermitian symmetry in the accumulate, which halves it) change float rounding slightly for the
  field as well, so they were not taken without a phone measurement to justify them.
- **No phone measurement was made.** Profiling on a real device would be needed before relying on
  these ratios for ART, for example with a Perfetto trace of the playback thread or a
  `Debug.threadCpuTimeNanos()` probe.

## Reproduce

These commands use Kotlin 2 / JUnit 4 on a host JVM, outside Gradle.

Benchmark:
- Put the dsp main and test sources on a classpath with the `dsp/src/main/resources` directory.
- Run `java -cp <classes> labs.rtensor.dsp.BinauralBenchmark both`.
- Optionally pass a layout name as a second argument, e.g. `"9.1.6 field"`.

Tests:
- Run `java -cp <classes> org.junit.runner.JUnitCore labs.rtensor.dsp.BinauralEquivalenceTest`.
- Under Gradle, use `./gradlew :dsp:testDebugUnitTest --tests 'labs.rtensor.dsp.Binaural*'`. This
  was not run for this change; see the validation notes in the commit.
