> Historical report recovered from the conversation before the reboot. Referenced raw measurements and generated artifacts were lost; links to missing files are retained as plain paths. For new measurements see [the optimization report](optimization/README.md).

# Class-File API experiment — 2026-11-18

The direct Java bytecode generator greatly reduces filter preparation time in this experiment. It does not improve the direct steady-state filtering time: the existing Scala-generated function is faster on this dataset. The Java-generated predicate performs about the same as the handwritten Java predicate using the same Java cursor.

## Environment and workload

- Apple M4 Max, arm64, 64 GiB RAM, macOS 26.6.2.
- Eclipse Temurin JDK 25.0.3+9-LTS; Scala 3.8.4; JMH 1.37; default JVM options, one benchmark thread.
- Baseline repository revision: `40cb154`, plus the Java library and benchmarks in this checkout.
- The existing `BenchData` dataset: 10,000 Confluent-framed Avro records, seed 42, one writer schema.
- Filter: `select(.user.age > 30 and .user.country == "FR")`; 1,521 records retained.
- Every runtime variant checks the exact retained offsets and original record identities before timing.
- All timings below are averages; ± is the JMH-reported 99.9% confidence interval half-width. This was a local workstation run, not a dedicated production load test.

## Filter execution

Each operation filters the entire 10,000-record batch and builds the same Scala `ArrayBuffer` output. Compiled filters are reused. Runtime measurements use 2 fresh JVM forks, 3 × 1 s warmup and 5 × 1 s measurement per fork, with the JMH GC profiler. Lower is better.

| Variant | µs / 10,000 records | Allocated bytes / batch |
|---|---:|---:|
| Scala staged function, direct call | 57.027 ± 1.850 | 16,552 |
| Java Classfile predicate, direct call | 68.902 ± 0.723 | 16,552 |
| Handwritten Java predicate, same Java cursor | 68.408 ± 1.142 | 16,552 |
| Original Scala Pipeline.forBytes | 112.253 ± 6.754 | 16,505 |
| Java predicate with Confluent/schema-id dispatch | 81.464 ± 3.480 | 16,553 |

The Java-generated direct variant takes **20.8% more time** than the Scala direct variant. The handwritten Java result is within the generated Java result's uncertainty. There is no evidence here of an execution overhead unique to Classfile generation, or of a throughput advantage over Scala staging.

The Java dispatch variant is faster than the original complete Scala pipeline, but this is an application-wrapper comparison. The complete Scala pipeline includes its own iteration, dispatch and error-handling structure. The direct-function comparison is the more useful check when asking whether replacing the generator itself speeds up execution.

The Java and Scala variants have equivalent results for the measured filter but different cursor/helper implementations and generated method shapes. These measurements do not isolate a language or compiler effect. In particular, Java cursor bounds/varint validation and the placement of the output append differ. No machine-code profiling was performed to attribute the direct-call difference to a particular instruction.

## Filter generation and loading

The timed operation includes reading-plan construction, code generation/compilation, class loading and function instantiation. Schema construction, jq parsing and Java filter-description construction happen before timing. Both backends compile on every invocation: no cache hits, no growing cache of unique benchmark queries. The Scala benchmark uses the package-private `AvroStaged.compileUncached` entry point; normal application caching is unchanged.

| Measurement | Scala staging, ms / filter | Java Classfile, ms / filter | Scala time / Java time |
|---|---:|---:|---:|
| First compilation in a fresh JVM | 969.196 ± 162.780 | 3.674 ± 1.039 | 263.8× |
| Repeated compilation after warmup | 2.121361 ± 0.162321 | 0.018103 ± 0.004651 | 117.2× |

- First compilation: single-shot mode, no warmup, one invocation per fork, 5 fresh JVM forks per backend. This is the first filter compilation after fixture setup, not total JVM/application startup time.
- Repeated compilation: 2 JVM forks, 8 × 1 s warmup and 5 × 1 s measurement per fork, with the GC profiler.
- The first exploratory run used only 3 warmup iterations. Scala compilation was still accelerating during measurement, so the reported repeated-compilation result uses the longer warmup. The exploratory JSON is retained separately.
- Allocation per repeated compilation: Java **34,249 bytes**, Scala **3,037,843 bytes**. These are allocated bytes per operation, not resident memory or JAR sizes.

## Library validation and scope

- `classfile/test`: 9 tests, including 6,000 materialized-Avro differential cases across 12 shuffled field layouts; numeric boundaries; Unicode; nested/skipped fields; malformed input; framing; concurrent reuse.
- `core/test`: all 204 original tests passed.
- `bench/test`: 6 tests passed, including 5 filter configurations over the 10,000-record dataset compared against both the Scala interpreter and staged engine.
- Total: **219 tests passed**.
- A separate consumer was compiled with `javac --release 24` and run from the packaged JAR plus only its production dependencies. Filtering passed; Scala, the staging compiler and JMH were absent from that classpath. The JAR class-file version is 68 (Java 24).
- Artifact: `classfile/target/konsume-classfile-0.1.0-SNAPSHOT.jar` (24,589 bytes in this build), with source JAR and Maven POM alongside it. Apache Avro is the only direct production dependency.

This is a scalar Avro-filter prototype, not a complete jq/JSON backend. Referenced fields must be non-nullable int/long or string paths. Unsupported paths/types fail at compilation. The application owns its writer-schema/filter cache and schema registry integration. Both staged Avro approaches stop after the last needed field; unread suffix corruption is not validated. See [the library API and limits](../../classfile/README.md).

The timings cover one small filter, one schema, in-memory records and monomorphic repeated calls. They do not measure Kafka I/O, mixed-schema dispatch at scale, large generated methods, or a running host application. The Java generator supports a much smaller language than the existing Scala backend.

## Reproduce

Run from the repository root:

```sh
sbt 'classfile/test' 'core/test' 'bench/test'
sbt 'bench/Jmh/run -prof gc -rf json -rff bench/results/classfile-throughput.json konsume.bench.ClassfileBench.*'
sbt 'bench/Jmh/run -prof gc -rf json -rff bench/results/classfile-warm-compilation.json konsume.bench.WarmCompilationBench.*'
sbt 'bench/Jmh/run -rf json -rff bench/results/classfile-cold-compilation.json konsume.bench.ColdCompilationBench.*'
sbt 'classfile/packageBin' 'classfile/packageSrc' 'classfile/makePom'
```

Raw JMH results:

- Runtime (`classfile-throughput.json`)
- Repeated compilation, longer warmup (`classfile-warm-compilation.json`)
- First compilation (`classfile-cold-compilation.json`)
- Exploratory repeated compilation, shorter warmup (`classfile-warm-compilation-short-warmup.json`)
