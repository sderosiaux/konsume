package konsume.bench

import konsume.classfile.OptimizationFilters
import konsume.format.AvroStaged
import konsume.jq.Parser
import java.nio.file.{Files, Path}

@main def inspectOptimization(directory: String): Unit =
  val output = Path.of(directory)
  OptimizationFilters.dump(BenchData.schema, output)
  val jq = Parser.parse("""select(.user.age > 30 and .user.country == "FR")""").fold(throw _, identity)
  Files.writeString(output.resolve("scala-generated.txt"), AvroStaged.show(jq, BenchData.schema))
