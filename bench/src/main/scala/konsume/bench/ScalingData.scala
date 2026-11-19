package konsume.bench

import konsume.Record

/** Distinct record objects and byte arrays, not repetitions of the original 10k references. */
object ScalingData:
  final case class Dataset(
      records: Vector[Record[String, Array[Byte]]],
      expectedOffsets: Vector[Long],
      payloadBytes: Long
  )

  def generate(size: Int): Dataset =
    require(size > 0)
    val events = BenchData.eventIterator()
    val records = Vector.newBuilder[Record[String, Array[Byte]]]
    val expected = Vector.newBuilder[Long]
    records.sizeHint(size)
    var payloadBytes = 0L
    var i = 0
    while i < size do
      val event = events.next()
      val bytes = BenchData.avro(event)
      records += Record("topic", 0, i.toLong, 0L, s"key-$i", bytes)
      payloadBytes += bytes.length
      if event.age > 30 && event.country == "FR" then expected += i.toLong
      i += 1
    Dataset(records.result(), expected.result(), payloadBytes)
