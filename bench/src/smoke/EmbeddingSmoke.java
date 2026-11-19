import konsume.classfile.AvroFilterCompiler;
import konsume.classfile.Filter;
import org.apache.avro.Schema;

/** Compiled separately against the packaged library and production dependencies only. */
public class EmbeddingSmoke {
    public static void main(String[] args) throws Exception {
        var schema = new Schema.Parser().parse("""
            {"type":"record","name":"R","fields":[
              {"name":"age","type":"int"},{"name":"country","type":"string"}]}
            """);
        var filter = AvroFilterCompiler.compile(schema,
            Filter.and(Filter.gt("age", 30), Filter.eq("country", "FR")));
        if (!filter.test(new byte[] {84, 4, 70, 82})) throw new AssertionError("Expected match");
        if (filter.test(new byte[] {40, 4, 70, 82})) throw new AssertionError("Expected rejection");
        if (!filter.getClass().isHidden()) throw new AssertionError("Expected unloadable hidden class");
        for (String name : new String[] {"scala.Option", "scala.quoted.staging.Compiler",
                "org.openjdk.jmh.annotations.Benchmark", "konsume.classfile.RestoredAvroFilterCompiler"}) {
            try { Class.forName(name); throw new AssertionError("Unexpected runtime dependency: " + name); }
            catch (ClassNotFoundException expected) { }
        }
        System.out.println("Standalone Java consumer: PASS; no Scala, staging compiler, JMH or benchmark controls");
    }
}
