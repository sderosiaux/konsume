package konsume.bench

import konsume.Record
import konsume.classfile.{AvroFilter, OptimizationFilters}
import konsume.format.AvroStaged
import konsume.jq.Parser
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer

/**
 * One timed invocation processes exactly 300 million records, replaying a distinct-data pool.
 * The long invocation removes per-small-batch timer concerns; the pool size changes locality.
 * Setup and generation/compilation are excluded. Check raw sample durations remain >= 1 s.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 6, batchSize = 1)
@Measurement(iterations = 5, batchSize = 1)
@Fork(value = 3, jvmArgsAppend = Array("-Xms2g", "-Xmx2g"))
class OptimizationBench:
  @Param(Array("10000", "100000", "1000000"))
  var batchSize: Int = 0

  @Param(Array("scala", "restored", "optimized"))
  var engine: String = scala.compiletime.uninitialized

  private val recordsPerInvocation = 300_000_000L
  private var batch: Vector[Record[String, Array[Byte]]] = scala.compiletime.uninitialized
  private var scalaFilter: AvroStaged.Fn = scala.compiletime.uninitialized
  private var javaFilter: AvroFilter = scala.compiletime.uninitialized
  private var passes = 0
  private var expectedPerPass = 0

  @Setup(Level.Trial)
  def setup(): Unit =
    require(recordsPerInvocation % batchSize == 0)
    passes = (recordsPerInvocation / batchSize).toInt
    val data = ScalingData.generate(batchSize)
    batch = data.records
    expectedPerPass = data.expectedOffsets.size
    engine match
      case "scala" =>
        val jq = Parser.parse("""select(.user.age > 30 and .user.country == "FR")""").fold(throw _, identity)
        scalaFilter = AvroStaged.compile(jq, BenchData.schema)
      case "restored" | "optimized" | "literalOnly" | "cursorOnly" | "combined" =>
        javaFilter = OptimizationFilters.create(engine, BenchData.schema)
      case other => throw IllegalArgumentException(other)
    val output = if engine == "scala" then scalaBatch(scalaFilter) else javaBatch(javaFilter)
    require(output.iterator.map(_.offset).sameElements(data.expectedOffsets))
    output.foreach(r => require(r eq batch(r.offset.toInt)))
    System.err.println(s"Optimization: $engine, $batchSize distinct records, ${data.payloadBytes} payload bytes, " +
      s"$expectedPerPass retained/pass, $passes passes = $recordsPerInvocation records/invocation")

  private def scalaBatch(filter: AvroStaged.Fn): ArrayBuffer[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do filter(it.next(), out)
    out

  private def javaBatch(filter: AvroFilter): ArrayBuffer[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do
      val record = it.next()
      if filter.test(record.value, 5, record.value.length - 5) then out += record
    out

  @Benchmark def process(bh: Blackhole): Long =
    var matched = 0L
    var pass = 0
    if engine == "scala" then
      val filter = scalaFilter
      while pass < passes do
        val output = scalaBatch(filter)
        matched += output.size
        bh.consume(output)
        pass += 1
    else
      val filter = javaFilter
      while pass < passes do
        val output = javaBatch(filter)
        matched += output.size
        bh.consume(output)
        pass += 1
    if matched != expectedPerPass.toLong * passes then throw IllegalStateException("Incorrect output count")
    matched
