package konsume.bench

import konsume.Record
import konsume.classfile.{AvroFilter, OptimizationFilters}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer

/** Distribution guards; kept separate from the unchanged primary OptimizationBench. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 3, jvmArgsAppend = Array("-Xms2g", "-Xmx2g"))
class DistributionBench:
  @Param(Array("mixed-lengths", "all-country-match", "no-age-match"))
  var shape: String = scala.compiletime.uninitialized

  private val size = 10000
  private val recordsPerInvocation = 300_000_000L
  private var batch: Vector[Record[String, Array[Byte]]] = scala.compiletime.uninitialized
  private var predicate: AvroFilter = scala.compiletime.uninitialized
  private var expectedCount = 0

  @Setup(Level.Trial)
  def setup(): Unit =
    val events = BenchData.eventIterator()
    val records = Vector.newBuilder[Record[String, Array[Byte]]]
    val expected = Vector.newBuilder[Long]
    val padding = "x" * 128
    for i <- 0 until size do
      val original = events.next()
      val event = shape match
        case "mixed-lengths" => original.copy(
          name = if (i & 1) == 0 then original.name + padding else original.name,
          country = if original.country == "FR" then "FR" else original.country + padding)
        case "all-country-match" => original.copy(country = "FR")
        case "no-age-match" => original.copy(age = 0)
        case other => throw IllegalArgumentException(other)
      records += Record("topic", 0, i.toLong, 0L, s"key-$i", BenchData.avro(event))
      if event.age > 30 && event.country == "FR" then expected += i.toLong
    batch = records.result()
    val offsets = expected.result()
    expectedCount = offsets.size
    predicate = OptimizationFilters.create("optimized", BenchData.schema)
    val result = filterBatch()
    require(result.iterator.map(_.offset).sameElements(offsets))
    result.foreach(r => require(r eq batch(r.offset.toInt)))
    System.err.println(s"Distribution: $shape, $size records, $expectedCount retained/pass")

  private def filterBatch(): ArrayBuffer[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do
      val record = it.next()
      if predicate.test(record.value, 5, record.value.length - 5) then out += record
    out

  @Benchmark def process(bh: Blackhole): Long =
    var count = 0L
    var pass = 0
    val passes = (recordsPerInvocation / size).toInt
    while pass < passes do
      val result = filterBatch()
      count += result.size
      bh.consume(result)
      pass += 1
    require(count == expectedCount.toLong * passes)
    count
