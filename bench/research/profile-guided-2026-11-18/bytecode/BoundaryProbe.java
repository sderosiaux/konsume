package konsume.classfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Diagnostic artifact: verify optimization scope without changing the benchmark. */
public final class BoundaryProbe {
    public static void main(String[] args) throws Exception {
        var output = Path.of(args[0]);
        Files.createDirectories(output);
        var schema = konsume.bench.BenchData$.MODULE$.schema();
        var filters = new LinkedHashMap<String, Filter>();
        filters.put("primary", Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "FR")));
        filters.put("numeric-equality", Filter.and(Filter.eq("user.age", 30), Filter.eq("user.country", "FR")));
        filters.put("numeric-inequality", Filter.and(Filter.ne("user.age", 30), Filter.eq("user.country", "FR")));
        filters.put("long-literal", Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "x".repeat(200))));
        filters.put("utf8-nine-bytes", Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "ééééa")));
        filters.put("already-ordered", Filter.and(Filter.eq("user.country", "FR"), Filter.gt("user.age", 30)));
        for (var entry : filters.entrySet()) {
            var generated = AvroFilterCompiler.generate(schema, entry.getValue());
            Files.write(output.resolve(entry.getKey() + ".class"), generated.bytecode());
        }
    }
}
