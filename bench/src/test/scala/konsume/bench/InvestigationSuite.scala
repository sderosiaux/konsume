package konsume.bench

import konsume.classfile.{InvestigationFilters, MalformedAvroException}

class InvestigationSuite extends munit.FunSuite:
  for variant <- List("originalHidden", "originalNamed", "originalJava", "currentHidden", "fastHidden", "peeledHidden", "jvmBoundsHidden", "scalaCursorHidden", "scalaCursorJava", "flatOriginalHidden", "flatPeeledHidden", "flatScalaHidden") do
    test(s"investigation control $variant keeps exactly the expected records") {
      val filter = InvestigationFilters.create(variant, BenchData.schema)
      val actual = BenchData.avroBytes.filter(r => filter.testConfluent(r.value, BenchData.SchemaId)).map(_.offset)
      val expected = BenchData.events.zipWithIndex.collect { case (e, i) if e.age > 30 && e.country == "FR" => i.toLong }
      assertEquals(actual, expected)
      if variant == "originalHidden" then assert(filter.getClass.isHidden)
      if variant == "originalNamed" then assert(!filter.getClass.isHidden)
    }

  test("fast cursor preserves bounded-slice rejection") {
    val filter = InvestigationFilters.create("fastHidden", BenchData.schema)
    val bytes = BenchData.avroBytes.head.value
    intercept[MalformedAvroException](filter.test(bytes, 5, 0))
    intercept[MalformedAvroException](filter.test(bytes, 5, 1))
    intercept[MalformedAvroException](filter.test(Array.fill[Byte](10)(0xff.toByte)))
  }
