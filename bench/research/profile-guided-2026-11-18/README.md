# Profile-guided Java Classfile optimization

The selected candidate passed independent confirmation: elapsed time fell by **11.3%
at 10k, 31.1% at 100k and 32.9% at 1M distinct records**. The primary benchmark and
original input fixture remain unchanged. All work and evidence live outside `/tmp`;
no remote branch has been published.

The baseline is the Java implementation retained after the
[first autoresearch experiments](../classfile-2026-11-18/README.md). The previous short-literal
optimization is already included in this baseline. Its earlier gains are not attributed
to the experiments below.

## What the profiles localized

Async-profiler 4.6 sampled CPU every 1 ms and allocations every 1 MiB, with
`DebugNonSafepoints` enabled to locate inlined Java frames. JMH discarded warmup
recording and restarted recording for the five measured invocations after six warmups.
Profiling timings are excluded from all performance comparisons.

| Baseline profile | 10k pool | 1M pool |
|---|---:|---:|
| Total CPU samples | 7,323 | 20,621 |
| Samples in the batch worker | 7,296 | 20,497 |
| Generated predicate, including cursor readers | 70.8% | 89.8% |
| `readLength`, including its reader | 28.3% | 8.1% |
| `skip` | 17.5% | 5.4% |
| `ArrayBuffer.addOne`, including its callees | 4.1% | 1.5% |

Percentages use batch-worker samples. These are **inclusive and overlapping**:
the generated predicate contains the cursor methods. They must not be added.
On the large pool, bytecode-index attribution also concentrates samples around
predicate branches and the false-return path. Sampling identifies useful intervention
sites; it does not measure hardware branch misses or establish an instruction-level
cost breakdown.

The allocation profiles point overwhelmingly to the output `Object[]` arrays.
Unprofiled JMH GC measurements remain about 1.655 bytes/record at 10k and
2.097 bytes/record at 1M for all tested implementations. The observed CPU improvements
do not come from omitting result construction or reducing output allocations.

Interactive artifacts:

- Baseline CPU: [10k](profiles/baseline/10000/cpu.html), [1M](profiles/baseline/1000000/cpu.html).
- Selected CPU: [10k](profiles/selected/10000/cpu.html), [1M](profiles/selected/1000000/cpu.html).
- Differential CPU, baseline to selected: [10k](profiles/diff/10000.html), [1M](profiles/diff/1000000.html).
- Baseline allocations: [10k](profiles/baseline/10000/alloc.html), [1M](profiles/baseline/1000000/alloc.html).
- Selected allocations: [10k](profiles/selected/10000/alloc.html), [1M](profiles/selected/1000000/alloc.html).
- [Baseline capture and conversion commands](profiles/baseline/manifest.json).
- [Selected capture and conversion commands](profiles/selected/manifest.json);
  [differential conversion commands](profiles/diff/manifest.json).
- Raw JFR, collapsed stacks, source-line and bytecode-index attribution are retained
  alongside the HTML files. The HTML JavaScript parses successfully; the integrated
  Browser preview could not connect because its plugin service module was missing.

The selected profiles were captured after confirmation, using the same warmup,
measurement and profiler configuration. At 10k, `readLong`'s inclusive share drops
from 28.2% to 14.4% of worker samples, and `skip` from 17.5% to 4.6%. `readLength`
itself rises from 28.3% to 31.7%: it now owns the specialized decoding work, and the
denominator is smaller. Source attribution moves when C2 inlines and rearranges
code; these shares are not independent savings to add together.

At 1M, the generated predicate still dominates (85.3% inclusive, previously 89.8%).
The selected worker has 13,753 CPU samples versus 20,497 in the baseline recording,
for the same 1.5 billion processed records. Output-array allocations still dominate
both allocation profiles. Sampling supports the localization; the separate unprofiled
confirmation is the source of the reported performance gains. Differential graphs
omit source line numbers because the source edits move lines between versions.

A separate [C2 compilation diagnostic](jit/summary.json) confirms that the selected
generated predicate, length decoder, long decoder and cursor skips inline into
`javaBatch`, and C2 eliminates the `AvroCursor` allocation. This was also true for
the baseline; removing a cursor allocation is not the new optimization.
The [command and source commit](jit/manifest.json) and compressed XML are retained.

## Controlled experiments

Each screen used three fresh JVMs per version and pool, six warmup invocations and
five measured invocations per JVM. Every invocation processes 300 million records,
replaying a pool of distinct records. All values below are **reductions in elapsed
time**, not percentages of throughput increase.

First screen, against a newly measured common baseline:

| Candidate | 10k time reduction | 1M time reduction | Outcome |
|---|---:|---:|---|
| Fuse length validation and cursor advancement | 7.12% | 5.14% | Inconclusive at the required 2% useful-gain threshold |
| Specialized one-byte length decoding | 11.67% | 10.58% | Promising at both sizes |
| String equality before numeric comparison | −1.94% | 26.00% | Promising at 1M; small-pool result inconclusive |

The fused cursor experiment initially exposed coupling in historical benchmark controls:
they replaced the cursor behind the changing production generator, which now called a
new helper absent from the frozen cursor. The literal-only generator was frozen as a
benchmark-only class and the same fix applied to every version before timing.
The failure log is preserved; all 45 targeted tests then passed in every candidate.
The measured `engine=optimized` path and primary batch loop were unchanged.

Second screen, with another fresh common baseline:

| Candidate | 10k time reduction | 1M time reduction | Outcome |
|---|---:|---:|---|
| Small-length decoder + predicate ordering | 10.90% | 31.09% | Promising at both sizes |
| Small-length decoder + packed loads + integer boolean AND | 8.79% | 32.17% | Promising at 1M; small-pool result inconclusive |

The second candidate exposes independent boolean values to C2; the branch name
`branchless` describes its hypothesis, not a claim that the resulting machine code
has no branches. A [direct comparison of the two combined candidates](combined-candidate-choice.json)
does not resolve an additional useful gain for the more complex one at either size.
The simpler decoder + ordering candidate was therefore selected for confirmation.

Raw comparisons: [first screen](screen-1/comparison.json),
[second screen](screen-2/comparison.json). Both directories preserve all individual
JMH logs, raw samples, commands, source hashes and commit IDs.

## Different input distributions

A separate, protected [DistributionBench](../../src/main/scala/konsume/bench/DistributionBench.scala)
checks whether the changes rely excessively on the original data. It preserves the
same filter semantics, output identities and per-invocation processing count.
It uses three independent JVMs, three warmup and three measurement iterations, with
each AverageTime iteration running for at least one second. Its timings are never
pooled with the primary SingleShotTime benchmark.

| Distribution | Simple combination: time reduction | Verdict |
|---|---:|---|
| Mixed short/long names and countries | 0.13% | Inconclusive |
| All countries match `FR` | 8.83% | Promising |
| All ages fail the numeric condition | 15.96% | Promising |

Neither combined candidate has a resolved regression in these guards. The long-string
case does not establish equivalence; its intervals remain wide. The lack of a useful
resolved gain there is consistent with the decoder optimization targeting one-byte lengths.
These are guards on a 10k pool, not a claim about every schema, literal or distribution.

Evidence: [protocol](distribution-protocol.json), [results](distributions/results.json),
[comparisons](distributions/comparison.json).

## Selected implementation and scope

The selected implementation has two changes:

1. `AvroCursor.readLength` decodes a one-byte nonnegative length directly with integer
   operations. Multi-byte lengths still use the complete existing long decoder.
   Truncation, overflow, negative lengths and slice checks are preserved.
2. The generator tries short string equality before a numeric **range** comparison
   in a conjunction. Numeric `EQ`/`NE` operators and literals longer than
   eight UTF-8 bytes preserve the caller's order. All referenced fields are decoded
   and validated before predicate evaluation, including records rejected by the age test.

The final narrowing of the ordering rule does not change generated bytecode for the
primary or distribution workloads: it is byte-for-byte identical to the screened
candidate. For numeric `EQ`/`NE` and long-string cases, the generator emits
the same bytes as the original baseline. The reader optimization still applies there.
See the [scope verification](bytecode/scope-verification.json) and the
[selected primary bytecode](bytecode/selected/primary.javap.txt).

Frozen baseline checkout: `../konsume-experiments/profile-baseline`, commit `1ec8400`.
Selected candidate checkout: `../konsume-experiments/selected`, commit `f6a9a26`.
The individual candidates remain on their own `autoresearch/classfile-*` branches.

The selected production sources are integrated on
`autoresearch/classfile-profiling-2026-11-18` through commit `2e6c6dd` (length decoder
`3a45c2b`, ordering `557935d`, scope limit `2e6c6dd`). Their hashes exactly match the
confirmed checkout. The [retention decision](decision.json) records the source,
evaluator, confirmation criteria and artifact hash.

## Independent confirmation

The [frozen protocol](protocol.json) requires five new JVMs per version at each of
10k, 100k and 1M records, with six warmups and five timed invocations per JVM.
Screen and guard samples are excluded. One JVM mean is one independent observation.
The evaluator is the actual `measurement.py` module from
[claudecode-autoresearch at c5fca23](https://github.com/sderosiaux/claudecode-autoresearch/tree/c5fca23f7beab20d4eed65d796747116e458c51c),
using conservative Student-t 95% intervals and a 2% useful-improvement threshold.

Confirmation completed on 2026-11-19: 30 fresh JVMs, 150 measured invocations,
five independent JVM means per version and pool. The shortest measured invocation
lasted 1.227 seconds. Each row below compares the two versions from this confirmation
only; no screen, guard or profiling observations enter these values.

| Distinct records in the pool | Baseline, s / 300M records | Selected, s / 300M records | Time reduction | Conservative 95% interval for seconds saved |
|---|---:|---:|---:|---:|
| 10,000 | 1.423970 | 1.262957 | **11.31%** | [0.125422, 0.196603] |
| 100,000 | 3.171451 | 2.185744 | **31.08%** | [0.757774, 1.213641] |
| 1,000,000 | 4.162269 | 2.791413 | **32.94%** | [1.245857, 1.495855] |

All three gains clear the predeclared 2% useful-improvement threshold using the
autoresearch evaluator. Allocation per record is identical between versions:
1.655223 bytes at 10k, 1.312903 at 100k and 2.097455 at 1M. Allocation differs across
pool sizes because output-buffer capacity and the number of pool repetitions differ.

The module returns `promising` and `confirmation_required: true` whenever a comparison
resolves a useful gain: it does not track experiment phases. The independent second
phase and retention decision are implemented by this workflow, not by pretending
that the native autoresearch coordinator ran. Both versions have five fresh
observations here, separate from candidate selection.

Evidence: [comparison and intervals](confirmation/comparison.json),
[all samples](confirmation/results.json), [source hashes and commands](confirmation/manifest.json).

## Final validation

All **249 tests pass**: 204 core tests, 13 Java library tests and 32 benchmark-module
tests. The new length-decoder differential checks cover canonical and overlong
encodings, invalid lengths, truncated slices and 20,000 random malformed slices.
Existing tests retain numeric double semantics, schema-layout variations, boolean
expressions, Unicode comparisons, record identities and malformed required fields.

The binary JAR, sources JAR and POM were rebuilt. A separate Java consumer successfully
compiled and ran against the packaged 26,090-byte JAR with its production dependencies.
Class-file version remains 68 (Java 24), with Avro as the only direct production
dependency and no Scala, JMH, benchmark controls or native profiler dependency.
See [test/package log](validation/final-tests-and-package.log),
[standalone-consumer log](validation/final-embedding-smoke.log) and
[flame-graph artifact checks](validation/flamegraph-validation.json).

## Reproduction

Environment: Apple M4 Max, 64 GiB RAM, macOS 26.6.2 arm64, Temurin 25.0.3+9,
JMH 1.37, one worker thread and a 2 GiB heap. This is a local desktop measurement,
with no CPU affinity or isolated hardware-counter collection. No build, test or
other performance run was launched concurrently with the timing campaigns.

Build each checkout before any measurement:

```sh
sbt 'classfile/test' 'bench/test' 'bench/Jmh/compile'
```

Run primary confirmation from the orchestrating repository:

```sh
python3 bench/scripts/run_research.py \
  --protocol bench/research/profile-guided-2026-11-18/protocol.json \
  --version baseline=/absolute/path/to/profile-baseline \
  --version selected=/absolute/path/to/selected \
  --phase confirmation --output /absolute/path/to/new-empty-results \
  --autoresearch /absolute/path/to/claudecode-autoresearch
```

Capture CPU/allocation profiles separately:

```sh
python3 bench/scripts/profile_research.py \
  --checkout /absolute/path/to/checkout \
  --profiler /absolute/path/to/async-profiler-4.6-macos \
  --output /absolute/path/to/new-empty-profile-directory
```

The official [async-profiler 4.6 macOS archive](https://github.com/async-profiler/async-profiler/releases/download/v4.6/async-profiler-4.6-macos.zip)
was verified against SHA-256
`646540c3c852d7fa7e43bbc4f090a193b7bfaf62d0954b42e98eae09edf36113` before execution.
Profiling and measurement scripts, raw evidence and rejected experiments remain available
for reproduction. No native autoresearch coordinator, Claude plugin hooks or remote
publication were used; the hypothesis loop uses the user's statistical evaluator directly.
