package konsume.bench

import io.circe.Json
import konsume.Record
import konsume.format.Confluent
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumWriter, GenericRecord, GenericRecordBuilder}
import org.apache.avro.io.EncoderFactory

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** 10k events, as JSON values, JSON bytes and Confluent-framed Avro bytes. */
object BenchData:
  val SchemaId = 1

  val schema: Schema = Schema.Parser().parse("""
    {"type": "record", "name": "Event", "fields": [
      {"name": "name", "type": "string"},
      {"name": "user", "type": {"type": "record", "name": "User", "fields": [
        {"name": "age",     "type": "int"},
        {"name": "country", "type": "string"}
      ]}},
      {"name": "items", "type": {"type": "array", "items": "int"}}
    ]}""")

  final case class Event(name: String, age: Int, country: String, items: Vector[Int])

  /** Same deterministic stream for every batch size; larger datasets extend the 10k fixture. */
  private[bench] def eventIterator(): Iterator[Event] =
    val rnd       = Random(42)
    val countries = Vector("FR", "US", "DE", "ES")
    val names     = Vector("john", "jane", "joe", "alice", "bob", "josephine")
    Iterator.continually(
      Event(
        names(rnd.nextInt(names.size)),
        rnd.nextInt(80),
        countries(rnd.nextInt(countries.size)),
        Vector.fill(rnd.nextInt(5))(rnd.nextInt(100))
      )
    )

  val events: Vector[Event] = eventIterator().take(10_000).toVector

  def json(e: Event): Json = Json.obj(
    "name"  -> Json.fromString(e.name),
    "user"  -> Json.obj("age" -> Json.fromInt(e.age), "country" -> Json.fromString(e.country)),
    "items" -> Json.fromValues(e.items.map(Json.fromInt))
  )

  def avro(e: Event): Array[Byte] =
    val user = GenericRecordBuilder(schema.getField("user").schema)
      .set("age", e.age)
      .set("country", e.country)
      .build()
    val record = GenericRecordBuilder(schema)
      .set("name", e.name)
      .set("user", user)
      .set("items", e.items.map(Integer.valueOf).asJava)
      .build()
    val out     = ByteArrayOutputStream()
    val encoder = EncoderFactory.get.binaryEncoder(out, null)
    GenericDatumWriter[GenericRecord](schema).write(record, encoder)
    encoder.flush()
    Confluent.frame(SchemaId, out.toByteArray)

  def records[V](f: Event => V): Vector[Record[String, V]] =
    events.zipWithIndex.map((e, i) => Record("topic", 0, i.toLong, 0L, s"key-$i", f(e)))

  lazy val jsonValues: Vector[Record[String, Json]]        = records(json)
  lazy val jsonBytes: Vector[Record[String, Array[Byte]]]  = records(e => json(e).noSpaces.getBytes(UTF_8))
  lazy val avroBytes: Vector[Record[String, Array[Byte]]]  = records(avro)
