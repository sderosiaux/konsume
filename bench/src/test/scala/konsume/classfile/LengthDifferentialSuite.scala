package konsume.classfile

import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream
import java.util.Random

class LengthDifferentialSuite extends munit.FunSuite:
  private def encode(value: Long): Array[Byte] =
    val out = ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    encoder.writeLong(value)
    encoder.flush()
    out.toByteArray

  private def compare(bytes: Array[Byte], offset: Int, length: Int): Unit =
    def result(read: => (Int, Int)): Either[String, (Int, Int)] =
      try Right(read)
      catch case _: MalformedAvroException => Left("malformed")
    val reference = result {
      val c = RestoredAvroCursor(bytes, offset, length)
      val n = c.readLength()
      c.skip(n)
      (n, c.position())
    }
    val actual = result {
      val c = AvroCursor(bytes, offset, length)
      val n = c.readLength()
      c.skip(n)
      (n, c.position())
    }
    assertEquals(actual, reference)

  test("length decoding preserves canonical and overlong encodings, boundaries and slices") {
    for n <- List(0, 1, 2, 63, 64, 127, 128, 8191, 8192, 16384) do
      val canonical = encode(n.toLong)
      for encodedSize <- canonical.length to 10 do
        val header = canonical.clone().toBuffer
        while header.size < encodedSize do
          header(header.size - 1) = (header.last | 128).toByte
          header += 0.toByte
        val bytes = Array.fill[Byte](7)(99) ++ header.toArray ++ Array.fill[Byte](n + 9)(42)
        compare(bytes, 7, header.size + n)
        if n > 0 then compare(bytes, 7, header.size + n - 1)
        for truncatedHeader <- 0 until header.size do compare(bytes, 7, truncatedHeader)
    for invalid <- List(-1L, -64L, Long.MinValue, Long.MaxValue, Int.MaxValue.toLong + 1) do
      val bytes = encode(invalid) ++ Array.fill[Byte](32)(0)
      compare(bytes, 0, bytes.length)
  }

  test("length decoding agrees with the frozen reader on random malformed slices") {
    val random = Random(91371)
    for _ <- 0 until 20000 do
      val bytes = new Array[Byte](random.nextInt(96) + 1)
      random.nextBytes(bytes)
      val offset = random.nextInt(bytes.length)
      compare(bytes, offset, random.nextInt(bytes.length - offset + 1))
  }
