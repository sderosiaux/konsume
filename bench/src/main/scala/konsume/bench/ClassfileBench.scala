package konsume.bench

import konsume.{ConsumeConfig, PayloadFormat, Pipeline, Record}
import konsume.classfile.{AvroFilter, AvroFilterCompiler, Filter, HandwrittenAvroFilter}
import konsume.format.{AvroStaged, Confluent}
import konsume.jq.Parser
import org.openjdk.jmh.annotations.*

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.collection.mutable.ArrayBuffer

/** Same records, same filter, same output buffer and retained record identities for every engine. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
class ClassfileBench:
  private var batch: Vector[Record[String, Array[Byte]]] = scala.compiletime.uninitialized
  private var staged: AvroStaged.Fn = scala.compiletime.uninitialized
  private var generated: AvroFilter = scala.compiletime.uninitialized
  private var handwritten: AvroFilter = scala.compiletime.uninitialized
  private var pipeline: Pipeline[String, Array[Byte], String, Array[Byte]] = scala.compiletime.uninitialized
  private val bySchemaId = ConcurrentHashMap[Int, AvroFilter]()

  @Setup(Level.Trial)
  def setup(): Unit =
    batch = BenchData.avroBytes
    val source = """select(.user.age > 30 and .user.country == "FR")"""
    val ast = Parser.parse(source).fold(throw _, identity)
    staged = AvroStaged.compile(ast, BenchData.schema)
    generated = AvroFilterCompiler.compile(BenchData.schema, Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "FR")))
    handwritten = HandwrittenAvroFilter()
    bySchemaId.put(BenchData.SchemaId, generated)
    pipeline = Pipeline.forBytes[String](ConsumeConfig(Seq("topic"), PayloadFormat.Avro, Some(source)), Map(BenchData.SchemaId -> BenchData.schema).get)
      .fold(throw _, identity)
    val expected = BenchData.events.zipWithIndex.collect { case (e, i) if e.age > 30 && e.country == "FR" => i.toLong }
    val outputs = List(scalaStagedDirect(), javaClassfileDirect(), javaHandwrittenDirect(), scalaPipeline(), javaClassfileDispatch())
    outputs.foreach { out =>
      require(out.map(_.offset).toVector == expected)
      out.foreach(r => require(r eq batch(r.offset.toInt)))
    }
    System.err.println(s"Classfile benchmark: ${expected.size}/${batch.size} identical retained records in all five variants")

  @Benchmark def scalaStagedDirect(): Iterable[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do staged(it.next(), out)
    out

  private def runJava(filter: AvroFilter): Iterable[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do
      val r = it.next()
      if filter.test(r.value, Confluent.HeaderSize, r.value.length - Confluent.HeaderSize) then out += r
    out

  @Benchmark def javaClassfileDirect(): Iterable[Record[Any, Array[Byte]]] = runJava(generated)
  @Benchmark def javaHandwrittenDirect(): Iterable[Record[Any, Array[Byte]]] = runJava(handwritten)
  @Benchmark def scalaPipeline(): Iterable[Record[Any, Array[Byte]]] = pipeline(batch)

  /** Includes the same Confluent header parsing and schema-id map lookup as Pipeline.forBytes. */
  @Benchmark def javaClassfileDispatch(): Iterable[Record[Any, Array[Byte]]] =
    val out = ArrayBuffer.empty[Record[Any, Array[Byte]]]
    val it = batch.iterator
    while it.hasNext do
      val r = it.next()
      val filter = bySchemaId.get(Confluent.schemaId(r.value))
      if filter.test(r.value, Confluent.HeaderSize, r.value.length - Confluent.HeaderSize) then out += r
    out
