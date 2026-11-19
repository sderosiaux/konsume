# konsume

Scala 3 library to consume Kafka topics, with record processing configured at runtime
(a jq program for now) and compiled to bytecode at runtime with
[multi-stage programming](https://docs.scala-lang.org/scala3/reference/metaprogramming/staging.html).

For now only the pure part exists: `Pipeline[K, V, K2, V2]`, a function from a batch of records
(`Iterable[Record[K, V]]`, what a `poll` returns) to the records to emit.

An independent [Java Class-File API prototype](classfile/README.md) implements the Avro scalar-filter
use case as an embeddable Java 24+ library. Its JAR has no Scala or JMH dependency; the separate
benchmark module compares it with the staged Scala implementation.

The optimized Java implementation is on this fork's `main` branch. Its additions and
measured gains are summarized below; the original Scala API remains available.

## Java additions and measured gains

- **Embeddable Java library:** a typed filter API for nested Avro integer and string fields,
  compiled directly to JVM bytecode. Reuse a compiled filter on raw Avro bytes, bounded slices
  or Confluent-framed values. Java 24+ is required; Avro is the only direct production dependency.
  See the [API, supported semantics and integration guide](classfile/README.md).
- **Three retained optimizations:** constant byte comparisons for short string literals,
  specialized decoding of one-byte lengths, and ordering short string equality before a numeric
  range comparison. Required fields are still decoded and validated before evaluating the filter.
- **Reproducible evidence:** isolated experiment branches, raw JMH measurements, CPU/allocation
  flame graphs and JIT logs. The retained source passed **249 tests** and a separate Java
  consumer test against the packaged JAR, without Scala or JMH on its runtime classpath.

### Gains from individual changes

The workload is `select(.user.age > 30 and .user.country == "FR")`. Each invocation processes
**300 million records by replaying a pool of distinct records**, including iteration and result
buffer construction. The percentages below mean **less elapsed time**, relative to the baseline
of each experiment.

| Retained change | 10k pool | 100k pool | 1M pool | Evidence |
|---|---:|---:|---:|---|
| Emit constant length/byte comparisons for literals of at most eight UTF-8 bytes, replacing the general array comparison | **17.9%** | No convincing gain | **5.3%** | [Earlier campaign: 3 JVMs per version and size](bench/results/optimization/README.md#confirmed-measurements) |
| Decode one-byte nonnegative lengths directly; retain the full decoder for longer encodings | **11.7%** | Not measured alone | **10.6%** | [Isolated screen: 3 JVMs per version and size](bench/research/profile-guided-2026-11-18/screen-1/comparison.json) |
| Evaluate short string equality before the numeric range comparison, after reading all required fields | Inconclusive | Not measured alone | **26.0%** | [Isolated screen: 3 JVMs per version and size](bench/research/profile-guided-2026-11-18/screen-1/comparison.json) |

The first row compares against the restored Java implementation. The last two compare against
a baseline that **already includes short-literal specialization**. Their isolated results are
screening evidence; the independent confirmation below validates their combination. The final
ordering rule is limited to literals of at most eight UTF-8 bytes and numeric range comparisons;
numeric `EQ`/`NE` preserve the caller's order.

These percentages are **not additive**. Separate campaigns and interacting changes do not
establish one cumulative gain against the earliest implementation.

### Independently confirmed result

The latest confirmation compares the previous Java implementation, with short literals already
specialized, against the retained **length-decoder + predicate-ordering** combination.
Throughput increase is derived as `before / after - 1`; time reduction is `1 - after / before`.

| Distinct records in the pool | Before, s / 300M records | After, s / 300M records | Less time | More records/second |
|---|---:|---:|---:|---:|
| 10,000 | 1.423970 | 1.262957 | **11.3%** | **+12.7%** |
| 100,000 | 3.171451 | 2.185744 | **31.1%** | **+45.1%** |
| 1,000,000 | 4.162269 | 2.791413 | **32.9%** | **+49.1%** |

This is a fresh campaign of **30 JVMs and 150 measured invocations**: five JVMs per version and
size, each with six warmups and five measurements. All three gains clear the predeclared 2%
useful-improvement threshold using conservative 95% intervals from
[the autoresearch evaluator](https://github.com/sderosiaux/claudecode-autoresearch/tree/c5fca23f7beab20d4eed65d796747116e458c51c).
Allocation per record is unchanged. Profiling runs are separate from these measurements.

Measurements use an Apple M4 Max, Temurin 25.0.3, one thread and a 2 GiB heap. They measure this
in-memory filter and caller, not Kafka end-to-end throughput. In a separate guard with mixed
short and long strings, the gain remains inconclusive. Integer-only comparisons, packed UTF-8
loads and fused length/skip variants did not establish enough benefit to retain; the more
complex combined variant did not establish an additional gain over the selected implementation.

Earlier preparation measurements also found the Java compiler substantially cheaper than Scala
staging for this filter: **4.32 ms vs 935.9 ms** for the first compilation in a fresh JVM. This
comparison used the earlier short-literal version, before the latest two optimizations; it is
not part of the runtime gain above. See [preparation measurements and limits](bench/results/optimization/README.md#filter-preparation-recheck).

See the [full optimization report](bench/research/profile-guided-2026-11-18/README.md),
[confirmation data and intervals](bench/research/profile-guided-2026-11-18/confirmation/comparison.json)
and [validation logs](bench/research/profile-guided-2026-11-18/validation/).
CPU flame graphs for the 1M pool: [before](bench/research/profile-guided-2026-11-18/profiles/baseline/1000000/cpu.html),
[after](bench/research/profile-guided-2026-11-18/profiles/selected/1000000/cpu.html),
[difference](bench/research/profile-guided-2026-11-18/profiles/diff/1000000.html).
Download the HTML files and open them in a browser for interactive inspection.

## Scala usage

```scala
val pipeline = Pipeline
  .fromConfig[String](ConsumeConfig(Seq("users"), jq = Some("""select(.age > 30 and .country == "FR")""")))
  .fold(throw _, identity)

val out: Iterable[Record[String, Json]] = pipeline(batch)
```

On raw Kafka values (`Array[Byte]`), JSON or Avro (Confluent wire format, writer schemas from a
registry):

```scala
val pipeline = Pipeline
  .forBytes[String](
    ConsumeConfig(Seq("users"), PayloadFormat.Avro, jq = Some("""select(.age > 30)""")),
    schemas = registrySnapshot.get // Int => Option[Schema]; fetching is the consumer's job (Confluent.schemaIds)
  )
  .fold(throw _, identity)
```

A record kept unchanged (a filter) is emitted with its original bytes; a transformed value is
emitted as JSON. Values that cannot be decoded are skipped.

Each jq output gives one record (same metadata and key, value = the output): `select(...)` filters,
`.user` projects, `.items[]` splits a record into several.

## Supported jq

`.`, `.a`, `."a"`, `.["a"]`, `.[0]`, `.[-1]`, `.[]`, `|`, `and`, `or`, `not`, `==`, `!=`, `<`, `<=`,
`>`, `>=`, `select`, `empty`, `length`, `test`, `startswith`, `endswith`, literals
(numbers, strings, `true`, `false`, `null`).

Semantics follow jq 1.7 (type order, `null` propagation, errors, order of outputs for operators that
yield several values); the tests check it against the `jq` binary when it is installed. If the program
fails on a record, that record's remaining outputs are dropped. `test` uses `java.util.regex`, not Oniguruma.

## JSON view of Avro values

jq programs see Avro values as JSON (`format/AvroJson`): unions unwrapped (`null` or `"x"`, not
`{"string":"x"}`), enums as strings, maps and records as objects, `bytes`/`fixed` as base64 strings,
`decimal` as numbers, other logical types as their underlying value (`timestamp-millis`: a number of
milliseconds), NaN and infinities as `null`. Values are read with their writer schema: a field missing
from an older schema is `null`.

## How it works

- `jq/Parser`: jq subset → `Jq` AST.
- `jq/Interpreter`: tree-walking interpreter, the reference semantics (`Engine.Interpreted`).
- `jq/Staged`: generates the Scala code for this very program, compiles it with the staging
  compiler and loads it (`Engine.Staged`, the default). Continuations are inlined at staging time
  (CPS), so the program is fused into the loop over the batch: no AST, no closures, comparisons
  specialised on the literal type, booleans never boxed into `Json`, literals and regexes built
  once, constant folding, records kept by a filter are not copied.
- `jq/Runtime`: jq semantics shared by both.
- `format/AvroStaged`: for Avro, the staged code reads the bytes directly, for each writer schema.
  The program is first generated in recording mode to find the paths it uses (`.user.age`); a
  reading plan is derived from the writer schema (fields in schema order, the needed ones into
  typed local variables, the others skipped without being decoded, stop after the last needed
  one); then the program is generated on these variables. Strings are compared as UTF-8 bytes in
  place, union branches that cannot match are folded away. Values used as a whole (records,
  arrays, maps, bytes) are read into their JSON view. One compilation per (program, schema id),
  on first sight of the id.
- `format/JsonStaged`: the same for JSON. Keys come in any order, so each object of interest is
  scanned and its keys compared as bytes with the expected names; the values of the others are
  skipped without being decoded. Types are only known at runtime: a used value is kept as its kind
  plus a double, or its position in the payload, and comparisons are generated for every kind
  (most fold to constants by jq's type order). The whole payload is read and validated (the tests
  check it is rejected exactly when circe rejects it); duplicate keys: the last one wins, as in jq.

See the generated code: `sbt 'core/Test/runMain konsume.jq.showStaged "select(.age > 30)"'`, or
for Avro `sbt 'core/Test/runMain konsume.format.showAvroStaged schema.avsc "select(.age > 30)"'`.

For JSON: `sbt 'core/Test/runMain konsume.format.showJsonStaged "select(.age > 30)"'`.

Known limits of the staged decoders:
- Avro: reading stops after the last field the program needs, so a payload corrupted or
  truncated after that point is accepted (the reference decoder rejects it);
- JSON: invalid UTF-8 is not detected, and strings are compared as bytes when they have no
  escapes, so the result on invalid UTF-8 may differ from the reference;
- the first record of a new schema id waits for its compilation (tens of ms, ~1s for the very
  first one); compiling in the background meanwhile is not done yet;
- very large schemas could generate methods over the JIT's size limit (8000 bytes of bytecode),
  which would then stay interpreted: not handled yet.

The first staging compilation takes about a second (the compiler warms up), later ones tens of
milliseconds; compiled programs are cached. Anything running staged code must fork the JVM
(`fork := true`), because the staging compiler reads its classpath from `java.class.path`.

## Benchmark

`sbt 'bench/Jmh/run -prof gc'`: 10k records already deserialised, JDK 25, µs per batch (lower is better).

| program                                            | hand-written | staged | interpreted |
|----------------------------------------------------|-------------:|-------:|------------:|
| `select(.user.age > 30 and .user.country == "FR")` |          238 |    286 |         846 |
| `select(.name \| test("^jo"))`                     |          268 |    272 |         865 |
| `.items[] \| select(. > 50)`                       |          456 |    379 |         908 |

`sbt 'bench/Jmh/run -prof gc konsume.bench.DecodeBench'`: from Kafka bytes, same filter,
10k records (~65 bytes in JSON, ~19 in Avro), µs per batch (allocation per batch).

| bytes => filtered records                 |            JSON |            Avro |
|--------------------------------------------|----------------:|----------------:|
| decoded to JSON first, then staged filter  | 3 060 (17.4 MB) | 3 166 (20.7 MB) |
| staged decoder on the bytes                | **1 053 (416 KB)** | **127 (16 KB)** |

For reference: decoding alone to JSON takes ~2 700 µs, Avro to `GenericRecord` alone 843 µs, the
staged filter alone on decoded JSON ~260 µs. JSON is slower than Avro by nature: every byte must
be scanned and validated, keys must be compared, types checked at runtime.
