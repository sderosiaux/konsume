package konsume.bench

import konsume.Record
import konsume.classfile.{AvroFilter, InvestigationFilters}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer

/** Direct predicate calls: no Scala Function2 adapter, only one backend is exercised in setup. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 8, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class PredicateInvestigationBench:
  @Param(Array("originalHidden", "peeledHidden", "scalaCursorHidden", "fastHidden"))
  var variant: String = scala.compiletime.uninitialized

  private var batch: Vector[Record[String, Array[Byte]]] = scala.compiletime.uninitialized
  private var predicate: AvroFilter = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    batch = BenchData.avroBytes
    predicate = InvestigationFilters.create(variant, BenchData.schema)
    val expected = BenchData.events.zipWithIndex.collect { case (e, i) if e.age > 30 && e.country == "FR" => i.toLong }
    val actual = filterBatch().toVector
    require(actual.map(_.offset) == expected)
    actual.foreach(r => require(r eq batch(r.offset.toInt)))

  @Benchmark def filterBatch(): Iterable[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do
      val r = it.next()
      if predicate.test(r.value, 5, r.value.length - 5) then out += r
    out
