package konsume.bench

import konsume.classfile.{AvroFilter, AvroFilterCompiler, Filter, MalformedAvroException, OptimizationFilters, RestoredAvroFilterCompiler}
import java.util.Random

class OptimizationSuite extends munit.FunSuite:
  for variant <- List("restored", "optimized", "literalOnly", "cursorOnly", "combined") do
    test(s"$variant retains the expected offsets and original record identities") {
      val filter = OptimizationFilters.create(variant, BenchData.schema)
      val actual = BenchData.avroBytes.filter(r => filter.testConfluent(r.value, BenchData.SchemaId))
      val expected = BenchData.events.zipWithIndex.collect { case (e, i) if e.age > 30 && e.country == "FR" => i.toLong }
      assertEquals(actual.map(_.offset), expected)
      actual.foreach(r => assert(r eq BenchData.avroBytes(r.offset.toInt)))
    }

  test("optimized and restored filters preserve bounded-slice outcomes on random malformed input") {
    val random = Random(20261118)
    val description = Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "FR"))
    val before = RestoredAvroFilterCompiler.compile(BenchData.schema, description)
    val after = AvroFilterCompiler.compile(BenchData.schema, description)
    def result(filter: AvroFilter, bytes: Array[Byte], start: Int, length: Int): Either[String, Boolean] =
      try Right(filter.test(bytes, start, length))
      catch case _: MalformedAvroException => Left("malformed")
    for _ <- 0 until 20000 do
      val bytes = new Array[Byte](random.nextInt(64) + 1)
      random.nextBytes(bytes)
      val start = random.nextInt(bytes.length)
      val length = random.nextInt(bytes.length - start + 1)
      assertEquals(result(after, bytes, start, length), result(before, bytes, start, length))
  }
