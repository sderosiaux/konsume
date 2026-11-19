# Java Class-File API prototype

`konsume-classfile` is an embeddable Java library that compiles an Avro filter directly to JVM bytecode with `java.lang.classfile`. It requires **JDK 24 or later**, without preview flags. It does not invoke `javac`, generate Java source, or depend on Scala, the Scala staging compiler, Kafka, or JMH.

Apache Avro is its only direct production dependency. Avro's own transitive dependencies are still needed. The artifact is a regular dependency JAR, not a shaded JAR or an executable application.

## API

```java
import konsume.classfile.AvroFilter;
import konsume.classfile.AvroFilterCompiler;
import konsume.classfile.Filter;
import org.apache.avro.Schema;

// writerSchema is supplied by the host application, e.g. from its schema registry.
Schema writerSchema = ...;

AvroFilter compiled = AvroFilterCompiler.compile(
    writerSchema,
    Filter.and(
        Filter.gt("user.age", 30),
        Filter.eq("user.country", "FR")
    )
);

// Raw Avro, a raw Avro slice, or a Confluent-framed value:
boolean keep = compiled.test(rawAvroBytes);
boolean keepSlice = compiled.test(buffer, offset, length);
boolean keepFramed = compiled.testConfluent(kafkaValue, expectedWriterSchemaId);
```

This filter corresponds to `select(.user.age > 30 and .user.country == "FR")` for a schema with non-nullable integer `user.age` and string `user.country`. Paths, literals, boolean operators, and schema field order are configuration, not hardcoded in the generated class. The Java API builds an immutable filter description; it does **not** parse jq text.

Compile once for each writer-schema/filter combination and reuse the returned predicate. Compiled instances can be shared between threads. The library starts no threads, opens no network connections and owns no Kafka consumer, registry client, output buffers or process lifecycle. Keep the original message if the predicate returns true.

The host application owns caching, eviction, schema-id dispatch and malformed-message policy. There is no global compilation cache. Each compilation loads a weak hidden class, allowing the class to be unloaded after the application releases its filter. No explicit `close()` is needed. Do not mutate the supplied Avro schema after compilation, or mutate an input byte array during evaluation.

## Current scope

- Filters over nested, non-nullable record paths to `int`/`long` and `string` fields.
- Numeric `eq`, `ne`, `lt`, `le`, `gt`, `ge`; string equality; `and`, `or`, `not`.
- Numeric comparisons convert integers to doubles, matching the existing jq engine, including rounding above 2^53. This is not an exact int64 comparison API.
- Needed fields are read once, in writer-schema order, into primitive locals. String equality compares UTF-8 bytes without creating Java strings.
- Equality against literals of at most eight UTF-8 bytes emits constant length and byte comparisons. Longer literals retain the general array comparison. The reading plan and malformed-input checks are unchanged.
- Unused fields before a needed field are skipped. Common scalar skips are emitted directly; complex unused values use a schema-driven skip helper (maximum nesting depth 128).
- Missing paths, nullable/union paths and other referenced scalar types are rejected during compilation. No projection, transformation, JSON input, regex or general jq support is implemented in this prototype.

All demanded fields are read before the boolean expression is evaluated. Reading stops after the last needed field, as in the Scala staged Avro engine. Corruption in the unread suffix is therefore not detected; strings are compared as bytes without validating UTF-8. This predicate is not a full Avro validator. Malformed required prefixes throw `MalformedAvroException`; invalid slice arguments throw `IndexOutOfBoundsException`.

## Build and integration

From the repository root, using JDK 24+:

```sh
sbt 'classfile/test' 'classfile/packageBin' 'classfile/packageSrc' 'classfile/makePom'
```

Artifacts are in `classfile/target/`:

- `konsume-classfile-0.1.0-SNAPSHOT.jar`
- `konsume-classfile-0.1.0-SNAPSHOT-sources.jar`
- `konsume-classfile-0.1.0-SNAPSHOT.pom`

For a local Maven/Gradle integration, `sbt 'classfile/publishM2'` installs the artifact and its POM into the local Maven repository. It is not published remotely.

```xml
<dependency>
  <groupId>io.konsume</groupId>
  <artifactId>konsume-classfile</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

There is no `_3` suffix: this is a Java artifact. JMH and test frameworks are confined to the benchmark/test configurations.

## Verification and measurement

```sh
sbt 'classfile/test' 'core/test' 'bench/test'
sbt 'bench/Jmh/run -prof gc konsume.bench.ClassfileBench.*'
sbt 'bench/Jmh/run -prof gc konsume.bench.WarmCompilationBench.*'
sbt 'bench/Jmh/run konsume.bench.ColdCompilationBench.*'
```

The Java tests compare against materialized Avro values across shuffled schema layouts, boolean expressions, Unicode strings, integer boundaries, slices, framing, truncation and concurrent reuse. The benchmark module also compares the generated Java predicate with both existing Scala engines on 10,000-record datasets.

The benchmark module alone depends on both Java and Scala implementations. Compilation benchmarks deliberately bypass the Scala cache so that they measure real generation, class loading and instantiation. Input parsing and schema construction are outside the timed region. Runtime benchmarks retain the exact same records into the same output buffer type.

See the [restoration and optimization results](../bench/results/optimization/README.md) for the current
Java implementation, controlled before/after measurements, JIT evidence and reproduction commands.

The [historical runtime investigation](../bench/results/investigation/README.md) records the earlier
loading, warmup, decoder and JIT controls. The original Java decoder was slower; changing its varint
loop while keeping validation substantially reduced its cost. The remaining execution gap depends on
the dataset and caller; the [sustained benchmark](../bench/results/scaling/README.md) extends the
comparison to 100,000 and 1,000,000 distinct records, with measured invocations lasting at least one second.
The [initial experiment](../bench/results/CLASSFILE_RESULTS.md) retains the earlier compilation and runtime measurements.
Those historical reports were recovered from the conversation; their lost raw JSON files are not represented as recovered evidence.
