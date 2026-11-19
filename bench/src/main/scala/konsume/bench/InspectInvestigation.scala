package konsume.bench

import konsume.classfile.InvestigationFilters
import konsume.format.AvroStaged
import konsume.jq.Parser
import java.nio.file.{Files, Path}

/** Diagnostic utility in the benchmark module; never part of the Java library artifact. */
@main def inspectInvestigation(directory: String): Unit =
  val output = Path.of(directory)
  Files.createDirectories(output)
  System.setProperty("konsume.dumpBytecode", output.resolve("generated").toString)
  for variant <- List("originalHidden", "originalNamed", "fastHidden", "scalaCursorHidden") do
    InvestigationFilters.create(variant, BenchData.schema)
  val ast = Parser.parse("""select(.user.age > 30 and .user.country == "FR")""").fold(throw _, identity)
  Files.writeString(output.resolve("scala-generated.txt"), AvroStaged.show(ast, BenchData.schema))
  val oneByteAges = BenchData.events.count(e => e.age < 64)
  val oneByteReads = 2 * BenchData.events.size + oneByteAges
  val totalReads = 3 * BenchData.events.size
  val summary = s"${BenchData.events.size} records; $oneByteAges one-byte ages; $oneByteReads/$totalReads one-byte varints (name length, age, country length)\n"
  Files.writeString(output.resolve("dataset.txt"), summary)
  println(summary)
