> Historical report recovered from the conversation before the reboot. Referenced raw measurements and generated artifacts were lost; links to missing files are retained as plain paths. For new measurements see [the optimization report](../optimization/README.md).

# Sustained filtering and larger datasets

This experiment extends the [decoder investigation](../investigation/README.md) by varying the distinct input dataset and making each timed invocation last at least one second. The previous JMH measurements already repeated short batches for one second per measurement iteration: approximately 60 µs was the average time per 10,000-record batch, not the duration of the entire measurement.

## Results

Measured on 2026-11-18. **All 90 measured invocations lasted at least one second: minimum 1.572842 s, maximum 4.792749 s.** Each row below summarizes 15 measurements from three JVM forks. Times cover 300 million record treatments, regardless of dataset size. Lower is better; ± is JMH's reported 99.9% confidence interval half-width.

| Distinct records in dataset | Passes / invocation | Scala, seconds / invocation | Java Classfile, seconds / invocation | Java extra time |
|---:|---:|---:|---:|---:|
| 10,000 | 30,000 | 1.6281 ± 0.0314 | 1.8093 ± 0.0150 | 11.13% |
| 100,000 | 3,000 | 3.4862 ± 0.1754 | 3.8174 ± 0.0548 | 9.50% |
| 1,000,000 | 300 | 4.5734 ± 0.0721 | 4.6650 ± 0.0372 | 2.00% |

Normalized to **one pass over the dataset**, rather than the whole timed invocation:

| Records / pass | Scala | Java Classfile | Scala ns / record | Java ns / record |
|---:|---:|---:|---:|---:|
| 10,000 | 54.269 µs | 60.311 µs | 5.427 | 6.031 |
| 100,000 | 1.162 ms | 1.272 ms | 11.621 | 12.725 |
| 1,000,000 | 15.245 ms | 15.550 ms | 15.245 | 15.550 |

Long invocations do not make Java faster in this workload. The 10,000- and 100,000-record results still favor Scala after warmup; their reported confidence intervals do not overlap. At one million records, the mean difference is only 2% and the intervals overlap. This does not prove equal performance, and the small residual should not be generalized without further workloads and runs.

This is a different harness from `ClassfileBench`: it has an outer replay loop, exercises only the selected engine in each fork, constructs fresh input objects and fixes the heap at 2 GiB. The old roughly 2% gap and the new 11% small-dataset gap are therefore **not an isolated experiment on measurement duration**. In particular, the 10,000-record Java time is almost unchanged from the earlier 60.375 µs result, while Scala is faster in this calling context. The earlier investigation already showed that caller shape affects these results.

The time per record increases strongly with the distinct dataset size for both engines. This is consistent with working-set/locality effects, but this experiment does not collect hardware cache-miss counters and does not isolate cache behavior from iteration, branch prediction or output-buffer effects. The retained-output fraction remains similar across datasets.

| Dataset records | Input bytes, including Confluent headers | Retained records / pass | Allocated bytes / record, both engines | Reported GC time, Scala / Java, across 15 measurements |
|---:|---:|---:|---:|---:|
| 10,000 | 193,331 | 1,521 | 1.6552 | 30 / 10 ms |
| 100,000 | 1,937,709 | 15,279 | 1.3129 | 20 / 28 ms |
| 1,000,000 | 19,392,321 | 152,690 | 2.0975 | 394 / 388 ms |

Input byte counts exclude record objects, byte-array headers, keys and collection structure. The messages are tiny: approximately 19.4 encoded bytes including framing. These are in-memory filtering numbers, not achievable Kafka end-to-end throughput claims.

Allocations are essentially identical between the two engines for each dataset. Larger output buffers grow in powers of two, so allocation per record is not constant across dataset sizes. The GC profiler's collection-time totals represent less than 0.6% of total measured invocation time in every row; they do not explain the observed runtime differences. This metric is not a separate tracing-based pause-time analysis, and allocation/cache costs can exist outside reported GC collection time.

Warmup remains relevant: the first 100,000-record Java warmup invocation took about 14–15 seconds in each fork, then subsequent warmup and measured invocations settled near 3.8 seconds. Those initial execution/JIT warmup durations are excluded from the table; they are not measurements of filter generation or class loading. See the warmup and sample log (`warmup-and-samples.txt`).

The longer experiment establishes execution behavior after warmup, but does not identify the machine instructions responsible for every remaining nanosecond. The earlier controlled substitutions localized the main original gap to decoder/support code and caller context. The Class-File API accelerates code generation; it does not inherently produce better optimized machine code than Scala staging. No new decoder change was made for this experiment.

Raw evidence: JMH JSON (`sustained.json`), dataset metadata, warmups and measured samples (`warmup-and-samples.txt`). The whole JMH run took 13 minutes 26 seconds, including setup, warmup and all forks.

## Protocol

- Apple M4 Max, macOS 26.6.2, Temurin 25.0.3, Scala 3.8.4, JMH 1.37; one benchmark thread, fixed 2 GiB heap (`-Xms2g -Xmx2g`).
- Same Avro schema and `select(.user.age > 30 and .user.country == "FR")` filter. Both engines retain the original records in a fresh `ArrayBuffer` on every pass. No Kafka, network, disk, schema lookup or full record materialization is timed.
- Three dataset sizes: 10,000, 100,000 and 1,000,000 records. Each record has its own payload byte array. All datasets use the same seeded event stream; larger datasets extend the smaller datasets. This is not a large vector of references to the original 10,000 records.
- One invocation processes exactly **300,000,000 records**, by replaying the dataset 30,000, 3,000 or 300 times. These are repeated treatments of an in-memory dataset, not 300 million distinct messages. The larger datasets change the working set; the replay count ensures long timing samples without allocating billions of input objects.
- `SingleShotTime`, six warmup invocations, five measured invocations, three independent JVM forks per engine and dataset size. The GC profiler is enabled. The JSON score is seconds per whole invocation, with no `@OperationsPerInvocation` normalization.
- Data generation, schema/filter parsing, compilation, class loading and initial validation occur in trial setup, outside the timed region. Only the selected engine is exercised in each fork.
- Setup verifies all matching offsets and retained record identities against the event data. Each timed invocation consumes every output buffer through JMH's blackhole and checks the total number of matches. No clock is read in the per-record loop.
- Reported per-pass and per-record times are derived from the long invocation, including iteration and output-buffer construction. They are not separately timed short samples. Check the raw minimum rather than assuming that a fixed record count guarantees one second on every future machine.

The machine is an interactive workstation, with no CPU pinning or exclusive host reservation. These measurements describe this filter, payload distribution and caller on this machine. Differences in workload, calling code and JIT decisions can change the result; Class-File API usage alone does not promise faster execution.

## Reproduce

From the repository root on JDK 24+:

```sh
mkdir -p bench/results/scaling
sbt 'bench/test' 'bench/Jmh/run -prof gc -rf json -rff bench/results/scaling/sustained.json konsume.bench.SustainedBench.*'
python3 bench/scripts/summarize_sustained.py
```

The summary command fails if any measured invocation is shorter than one second. If testing on faster hardware, increase `recordsPerInvocation` in the benchmark and supply the matching `--records` value to the summary command.

Sources: [benchmark](../../src/main/scala/konsume/bench/SustainedBench.scala), [dataset generator](../../src/main/scala/konsume/bench/ScalingData.scala), [fixture checks](../../src/test/scala/konsume/bench/ScalingDataSuite.scala), [summary script](../../scripts/summarize_sustained.py). The 24 benchmark/differential tests passed before the measurement run.
