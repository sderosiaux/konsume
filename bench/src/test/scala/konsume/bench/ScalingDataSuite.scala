package konsume.bench

import java.util.{Arrays, IdentityHashMap}

class ScalingDataSuite extends munit.FunSuite:
  test("extended datasets preserve the original 10k fixture and its expected results") {
    val data = ScalingData.generate(10_001)
    assertEquals(data.records.size, 10_001)
    assertEquals(data.expectedOffsets.count(_ < 10_000), 1521)
    BenchData.avroBytes.zip(data.records).foreach { (old, fresh) =>
      assertEquals(fresh.offset, old.offset)
      assert(Arrays.equals(fresh.value, old.value))
      assert(fresh ne old)
      assert(fresh.value ne old.value)
    }
    assertEquals(data.records.last.offset, 10_000L)
    assertEquals(data.payloadBytes, data.records.iterator.map(_.value.length.toLong).sum)
  }

  test("each record owns a separate payload array and generations are deterministic") {
    val first = ScalingData.generate(1000)
    val second = ScalingData.generate(1000)
    val identities = IdentityHashMap[Array[Byte], java.lang.Boolean]()
    first.records.foreach(r => assert(identities.put(r.value, java.lang.Boolean.TRUE) == null))
    first.records.zip(second.records).foreach { (a, b) =>
      assert(Arrays.equals(a.value, b.value))
      assert(a.value ne b.value)
    }
    assertEquals(first.expectedOffsets, second.expectedOffsets)
  }
