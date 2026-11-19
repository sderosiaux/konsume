package konsume.bench

import konsume.classfile.{AvroFilterCompiler, Filter}
import konsume.format.AvroStaged
import konsume.jq.{Jq, Parser}
import org.apache.avro.Schema
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Input construction/parsing is outside the timing; generation, loading and instantiation are inside. */
@State(Scope.Thread)
class CompilationInputs:
  var schema: Schema = scala.compiletime.uninitialized
  var jq: Jq = scala.compiletime.uninitialized
  var filter: Filter = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    schema = BenchData.schema
    jq = Parser.parse("""select(.user.age > 30 and .user.country == "FR")""").fold(throw _, identity)
    filter = Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "FR"))

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 8, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class WarmCompilationBench:
  @Benchmark def javaClassfile(in: CompilationInputs, bh: Blackhole): Unit =
    bh.consume(AvroFilterCompiler.compile(in.schema, in.filter))

  @Benchmark def scalaStaging(in: CompilationInputs, bh: Blackhole): Unit =
    bh.consume(AvroStaged.compileUncached(in.jq, in.schema))

/** One actual first compilation per fresh JVM. No cache lookups and no JIT warmup. */
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1, batchSize = 1)
@Fork(5)
class ColdCompilationBench:
  @Benchmark def javaClassfile(in: CompilationInputs, bh: Blackhole): Unit =
    bh.consume(AvroFilterCompiler.compile(in.schema, in.filter))

  @Benchmark def scalaStaging(in: CompilationInputs, bh: Blackhole): Unit =
    bh.consume(AvroStaged.compileUncached(in.jq, in.schema))
