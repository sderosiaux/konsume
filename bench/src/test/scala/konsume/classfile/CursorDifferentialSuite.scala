package konsume.classfile

import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream
import java.util.Random

class CursorDifferentialSuite extends munit.FunSuite:
  private def encode(value: Long): Array[Byte] =
    val out = ByteArrayOutputStream()
    val encoder = EncoderFactory.get.binaryEncoder(out, null)
    encoder.writeLong(value)
    encoder.flush()
    out.toByteArray

  private def results(bytes: Array[Byte], length: Int): List[Either[String, (Long, Int)]] =
    def result(read: => (Long, Int)): Either[String, (Long, Int)] =
      try Right(read)
      catch case _: MalformedAvroException => Left("malformed")
    List(
      result { val c = BaselineAvroCursor(bytes, 0, length); (c.readLong(), c.position()) },
      result { val c = FastAvroCursor(bytes, 0, length); (c.readLong(), c.position()) },
      result { val c = PeeledAvroCursor(bytes, 0, length); (c.readLong(), c.position()) },
      result { val c = AvroCursor(bytes, 0, length); (c.readLong(), c.position()) },
      result { val c = EarlyReturnAvroCursor(bytes, 0, length); (c.readLong(), c.position()) }
    )

  test("all cursor variants decode every int64 boundary and random Avro longs identically") {
    val random = Random(812)
    val values = List(Long.MinValue, Long.MaxValue, -65L, -64L, -1L, 0L, 1L, 63L, 64L, 127L, 128L) ++
      List.fill(10000)(random.nextLong())
    for value <- values do
      val bytes = encode(value)
      results(bytes, bytes.length).foreach(r => assertEquals(r, Right((value, bytes.length))))
  }

  test("all cursor variants reject truncated and overflowing varints identically") {
    for value <- List(Long.MinValue, Long.MaxValue, 0L, 63L, 64L, 128L) do
      val bytes = encode(value)
      for length <- 0 until bytes.length do
        results(bytes, length).foreach(r => assertEquals(r, Left("malformed")))
    for last <- List(2, 127, 128, 255) do
      val bytes = Array.fill[Byte](10)(0xff.toByte)
      bytes(9) = last.toByte
      results(bytes, bytes.length).foreach(r => assertEquals(r, Left("malformed")))
  }

  test("all cursor variants agree on random malformed byte slices") {
    val random = Random(813)
    for _ <- 0 until 10000 do
      val bytes = new Array[Byte](random.nextInt(14))
      random.nextBytes(bytes)
      val actual = results(bytes, bytes.length)
      assertEquals(actual(1), actual.head)
      assertEquals(actual(2), actual.head)
      assertEquals(actual(3), actual.head)
      assertEquals(actual(4), actual.head)
  }
