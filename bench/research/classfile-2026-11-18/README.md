# Java Classfile experiments — 2026-11-18

Neither new candidate produced a resolved improvement on the current Java baseline.
The production implementation is retained unchanged. Both experiments remain committed
on separate branches, with their patches and all measurements here.

This campaign uses the statistical evaluator from
[sderosiaux/claudecode-autoresearch](https://github.com/sderosiaux/claudecode-autoresearch/tree/c5fca23f7beab20d4eed65d796747116e458c51c),
specifically `product/autoresearch/measurement.py`. It follows its independent-run,
protected-benchmark and fresh-confirmation discipline. It does not run the native
workbench coordinator or the Claude plugin's continuous loop.

## Results and decision

The workload remains `select(.user.age > 30 and .user.country == "FR")`, including
iteration and construction of the result buffer. Each timed invocation processes
300 million records by replaying a pool of 10,000 distinct records.

| Implementation | Mean seconds / 300M records | Change in time vs baseline | Autoresearch verdict |
|---|---:|---:|---|
| Current optimized Java | 1.422280 | — | reference |
| Integer threshold comparisons | 1.422163 | −0.0083% | inconclusive |
| Packed UTF-8 comparisons | 1.444261 | +1.5454% | inconclusive |

Lower time is better. These are exploratory measurements, not confirmed speedups
or proof of equivalence. The conservative 95% intervals for saved time are
**[−0.077542, +0.077778] seconds** for integers and
**[−0.128059, +0.084097] seconds** for packed UTF-8. Neither resolves the
predeclared minimum useful improvement of 2%; neither resolves a regression either.

There are **three independent JVMs per implementation**, with six warmup invocations
and five measured invocations in each JVM. The evaluator receives one mean per JVM,
not five supposedly independent samples. The campaign contains 45 timed invocations;
their durations range from 1.376597 to 1.497336 seconds. Version order rotates across rounds.
Fork-mean coefficients of variation are 1.45%, 0.75% and 1.53%, respectively.

Per-JVM means in execution rounds 1–3:

| Implementation | Round 1 | Round 2 | Round 3 |
|---|---:|---:|---:|
| Baseline | 1.425225 | 1.441274 | 1.400342 |
| Integers | 1.425062 | 1.431048 | 1.410378 |
| Packed UTF-8 | 1.435410 | 1.469387 | 1.427986 |

The [protocol](protocol.json) was committed before either candidate. Only a promising
screen result could enter a new confirmation campaign at 10k, 100k and 1M records.
Since neither qualified, no confirmation or combined candidate was run. There are
**no new performance conclusions for larger pools or other filters** in this campaign.
The older [restoration/optimization campaign](../../results/optimization/README.md)
has a different baseline; its measurements are not pooled with these.

## Candidate implementations

| Role | Commit | Branch | Reviewable patch |
|---|---|---|---|
| Restored and optimized source | `465d64a` | ancestor of all branches | — |
| Frozen tests and protocol | `57f0044` | `autoresearch/classfile-2026-11-18` | — |
| Integer comparisons | `61fea99` | `autoresearch/classfile-numeric-2026-11-18` | [numeric.patch](numeric.patch) |
| Packed UTF-8 | `361f15a` | `autoresearch/classfile-packed-2026-11-18` | [packed.patch](packed.patch) |

The integer variant emits `lcmp` instead of `l2d` / `dcmpg` for integral thresholds
strictly inside ±2^53. Fractional thresholds and boundary values retain double
comparison, preserving jq's rounding semantics for every long input.

The UTF-8 variant uses plain, unaligned `VarHandle` reads of 2, 4 or 8 bytes for
literals of at most eight UTF-8 bytes, after checking the encoded string length.
It compares any remaining byte separately and retains the existing generic helper
for longer literals. The reading plan still validates all referenced fields before
evaluating the expression, including when the numeric condition rejects the record.

## Correctness and JIT evidence

**13 Java library tests and 30 benchmark tests passed on each of the three versions.**
The two new tests were committed before candidate development. They compare all six
numeric operators with an independent materialized-Avro reference, exercise rounding
near ±2^53 and long extremes, and check short/long Unicode strings across 16 alignments,
every byte mismatch and every truncated slice. Matching bytes beyond a slice must not
turn a truncated value into a valid one. Existing randomized malformed-input tests pass.

Validation logs: [baseline](validation/baseline.log), [integers](validation/numeric.log),
[packed UTF-8](validation/packed.log). No builds, tests or competing benchmark processes
were launched during the timing campaign.

Separate diagnostic runs use `LogCompilation` and are **excluded from the statistics**.
The [C2 inlining summary](jit/inlining.txt) shows that all three versions inline the
generated predicate and cursor readers into `OptimizationBench.javaBatch`, and eliminate
the cursor allocation. The packed helper is also inlined; its
[`_getShortUnaligned` intrinsic](jit/intrinsics.json) confirms the grouped read is recognized.
JMH reports the same allocation rate, about **1.655 bytes per processed record**, for all
three variants, including result-buffer allocations.

Thus the proposed bytecode changes are real and the packed helper is optimized by C2,
but neither produces a useful resolved end-to-end benefit on this workload. These logs
do not establish which remaining cost dominates. A sensible next investigation would
profile larger pools and separate predicate cost from iteration and result construction
before choosing another production change.

Generated bytecode: [baseline](bytecode/baseline/optimized.javap.txt),
[integers](bytecode/numeric/optimized.javap.txt), [packed UTF-8](bytecode/packed/optimized.javap.txt).
Full compressed JIT logs and exact diagnostic commands are in [jit/](jit/).

## Reproduce and inspect

Environment: Apple M4 Max, 64 GiB RAM, macOS 26.6.2 arm64, Temurin 25.0.3+9,
JMH 1.37, one benchmark thread and a 2 GiB heap. The 10k pool contains 193,331 payload
bytes and retains 1,521 records per pass. This is a local desktop measurement;
OS activity and scheduling are not isolated. Results are throughput timings, not
request-latency percentiles.

The worktrees are outside `/tmp`, under `~/code/personal/konsume` and
`~/code/personal/konsume-experiments/{numeric,packed}`. Nothing was pushed remotely.

Build and test each checkout serially before measuring:

```sh
sbt 'classfile/test' 'bench/test' 'bench/Jmh/compile'
```

From the baseline checkout, with the autoresearch repository at the pinned commit:

```sh
python3 bench/scripts/run_research.py \
  --version baseline=/absolute/path/to/baseline \
  --version numeric=/absolute/path/to/numeric \
  --version packed=/absolute/path/to/packed \
  --phase screen --output /absolute/path/to/new-empty-results \
  --autoresearch /absolute/path/to/claudecode-autoresearch
```

The runner checks that benchmark, core, build configuration and correctness tests
match across versions, records source hashes and commit IDs, executes JVMs serially,
and calls the pinned autoresearch evaluator. Each invocation verifies the output count;
trial setup verifies output identities and offsets against the independent fixture.

Evidence: [manifest and commands](screen/manifest.json), [raw JMH results](screen/results.json),
[autoresearch comparisons](screen/comparison.json), [runner](../../scripts/run_research.py).
The JSON includes every sample; individual fork logs and results are retained alongside it.
