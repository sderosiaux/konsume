package konsume.bench

import konsume.{ConsumeConfig, Engine, PayloadFormat, Pipeline}
import konsume.classfile.{AvroFilterCompiler, Filter}
import konsume.format.AvroStaged
import konsume.jq.Parser

class ClassfileDifferentialSuite extends munit.FunSuite:
  for (limit, country) <- List((30, "FR"), (0, "US"), (79, "DE"), (18, "ES"), (-1, "FR")) do
    test(s"classfile == Scala staging == interpreter: age > $limit and country == $country") {
      val source = s"""select(.user.age > $limit and .user.country == "$country")"""
      val compiled = AvroFilterCompiler.compile(BenchData.schema, Filter.and(Filter.gt("user.age", limit), Filter.eq("user.country", country)))
      val javaOut = BenchData.avroBytes.filter(r => compiled.testConfluent(r.value, BenchData.SchemaId))
      for engine <- Engine.values do
        val pipeline = Pipeline.forBytes[String](ConsumeConfig(Seq("topic"), PayloadFormat.Avro, Some(source), engine), Map(BenchData.SchemaId -> BenchData.schema).get)
          .fold(throw _, identity)
        val scalaOut = pipeline(BenchData.avroBytes).toVector
        assertEquals(javaOut.map(_.offset), scalaOut.map(_.offset))
        scalaOut.zip(javaOut).foreach((s, j) => assert(s eq j))
    }

  test("compilation benchmarks bypass the Scala cache") {
    val ast = Parser.parse("select(.user.age > 30)").toOption.get
    val a = AvroStaged.compileUncached(ast, BenchData.schema)
    val b = AvroStaged.compileUncached(ast, BenchData.schema)
    assert(a ne b)
  }
