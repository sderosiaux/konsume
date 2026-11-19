package konsume.classfile;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.avro.Schema;

/** Same generated predicate/caller with isolated generator and reader interventions. */
public final class OptimizationFilters {
    private OptimizationFilters() { }

    public static AvroFilter create(String variant, Schema schema) {
        Filter filter = Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "FR"));
        if (variant.equals("optimized")) return AvroFilterCompiler.compile(schema, filter);
        if (variant.equals("restored")) return RestoredAvroFilterCompiler.compile(schema, filter);
        byte[] bytes;
        byte[][] literals;
        Schema[] schemas;
        if (variant.equals("literalOnly") || variant.equals("combined")) {
            var g = AvroFilterCompiler.generate(schema, filter);
            bytes = replaceCursor(g.bytecode(), AvroCursor.class,
                variant.equals("combined") ? EarlyReturnAvroCursor.class : RestoredAvroCursor.class);
            literals = g.literals(); schemas = g.schemas();
        } else if (variant.equals("cursorOnly")) {
            var g = RestoredAvroFilterCompiler.generate(schema, filter);
            bytes = replaceCursor(g.bytecode(), RestoredAvroCursor.class, EarlyReturnAvroCursor.class);
            literals = g.literals(); schemas = g.schemas();
        } else throw new IllegalArgumentException(variant);
        return load(bytes, literals, schemas);
    }

    private static byte[] replaceCursor(byte[] bytes, Class<?> before, Class<?> after) {
        var source = ClassDesc.of(before.getName());
        var target = ClassDesc.of(after.getName());
        var cf = ClassFile.of();
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies((c, e) -> {
            if (e instanceof NewObjectInstruction n && n.className().asSymbol().equals(source)) c.new_(target);
            else if (e instanceof InvokeInstruction i && i.owner().asSymbol().equals(source))
                c.invoke(i.opcode(), target, i.name().stringValue(), i.typeSymbol(), i.isInterface());
            else c.with(e);
        }));
    }

    private static AvroFilter load(byte[] bytes, byte[][] literals, Schema[] schemas) {
        try {
            var lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
            return (AvroFilter) lookup.findConstructor(lookup.lookupClass(),
                MethodType.methodType(void.class, byte[][].class, Schema[].class)).invoke(literals, schemas);
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException(e); }
    }

    public static void dump(Schema schema, Path directory) throws Exception {
        Files.createDirectories(directory);
        var filter = Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "FR"));
        Files.write(directory.resolve("restored.class"), RestoredAvroFilterCompiler.generate(schema, filter).bytecode());
        Files.write(directory.resolve("optimized.class"), AvroFilterCompiler.generate(schema, filter).bytecode());
    }
}
