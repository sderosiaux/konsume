package konsume.bench

import konsume.Record
import konsume.classfile.InvestigationFilters
import konsume.format.AvroStaged
import konsume.jq.Parser
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer

/** One backend per fresh JVM: identical call contract, batch loop and output collection. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 8, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class InvestigationBench:
  @Param(Array("scalaStaged", "originalHidden", "originalNamed", "originalJava", "fastHidden", "scalaCursorHidden", "scalaCursorJava"))
  var variant: String = scala.compiletime.uninitialized

  private var batch: Vector[Record[String, Array[Byte]]] = scala.compiletime.uninitialized
  private var function: AvroStaged.Fn = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    batch = BenchData.avroBytes
    if variant == "scalaStaged" then
      val ast = Parser.parse("""select(.user.age > 30 and .user.country == "FR")""").fold(throw _, identity)
      function = AvroStaged.compile(ast, BenchData.schema)
    else
      val predicate = InvestigationFilters.create(variant, BenchData.schema)
      function = (r, out) =>
        if predicate.test(r.value, 5, r.value.length - 5) then out += r
        ()
    val expected = BenchData.events.zipWithIndex.collect { case (e, i) if e.age > 30 && e.country == "FR" => i.toLong }
    val actual = filterBatch().toVector
    require(actual.map(_.offset) == expected)
    actual.foreach(r => require(r eq batch(r.offset.toInt)))

  @Benchmark def filterBatch(): Iterable[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do function(it.next(), out)
    out
