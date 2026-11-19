package konsume.classfile;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.avro.Schema;

import static java.lang.constant.ConstantDescs.*;

/** Causal controls, confined to benchmarks. No alternative loading mode enters the public API. */
public final class InvestigationFilters {
    private static final ClassDesc ORIGINAL = ClassDesc.of("konsume.classfile.RestoredAvroCursor");
    private static final ClassDesc CURRENT = ClassDesc.of("konsume.classfile.AvroCursor");
    private static final ClassDesc BASELINE = ClassDesc.of("konsume.classfile.BaselineAvroCursor");
    private static final ClassDesc FAST = ClassDesc.of("konsume.classfile.FastAvroCursor");
    private static final ClassDesc PEELED = ClassDesc.of("konsume.classfile.PeeledAvroCursor");
    private static final ClassDesc ARRAY_BOUNDED = ClassDesc.of("konsume.classfile.ArrayBoundedCursor");
    private static final ClassDesc SCALA = ClassDesc.of("konsume.format.AvroCursor");

    private InvestigationFilters() { }

    public static AvroFilter create(String variant, Schema schema) {
        if (variant.equals("originalJava")) return new BaselinePredicate();
        if (variant.equals("scalaCursorJava")) return new ScalaCursorPredicate();
        var generated = RestoredAvroFilterCompiler.generate(schema,
            Filter.and(Filter.gt("user.age", 30), Filter.eq("user.country", "FR")));
        byte[] bytes = switch (variant) {
            case "originalHidden", "originalNamed" -> replaceCursor(generated.bytecode(), BASELINE);
            case "currentHidden" -> replaceCursor(generated.bytecode(), CURRENT);
            case "fastHidden" -> replaceCursor(generated.bytecode(), FAST);
            case "peeledHidden" -> replaceCursor(generated.bytecode(), PEELED);
            case "jvmBoundsHidden" -> replaceCursor(generated.bytecode(), ARRAY_BOUNDED);
            case "scalaCursorHidden" -> replaceCursor(generated.bytecode(), SCALA);
            case "flatOriginalHidden" -> flattenLiteral(replaceCursor(generated.bytecode(), BASELINE));
            case "flatPeeledHidden" -> flattenLiteral(replaceCursor(generated.bytecode(), PEELED));
            case "flatScalaHidden" -> flattenLiteral(replaceCursor(generated.bytecode(), SCALA));
            default -> throw new IllegalArgumentException(variant);
        };
        String dump = System.getProperty("konsume.dumpBytecode");
        if (dump != null) {
            try { Files.write(Path.of(dump + "-" + variant + ".class"), bytes); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }
        try {
            var lookup = MethodHandles.lookup();
            Class<?> type;
            if (variant.equals("originalNamed")) {
                type = lookup.defineClass(bytes);
            } else {
                lookup = lookup.defineHiddenClass(bytes, true);
                type = lookup.lookupClass();
            }
            var constructor = lookup.findConstructor(type, MethodType.methodType(void.class, byte[][].class, Schema[].class));
            return (AvroFilter) constructor.invoke(generated.literals(), generated.schemas());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    // This benchmark has exactly one string literal. Replace literals[0] with a final byte[]
    // field without changing the comparison, decoder, loading mode, or public method shape.
    private static byte[] flattenLiteral(byte[] bytes) {
        var cf = ClassFile.of();
        var self = cf.parse(bytes).thisClass().asSymbol();
        var byteArray = CD_byte.arrayType();
        var initialize = ClassTransform.transformingMethodBodies(m -> m.methodName().equalsString("<init>"), (c, e) -> {
            if (e instanceof ReturnInstruction) {
                c.aload(0).aload(1).iconst_0().aaload().putfield(self, "literal0", byteArray);
            }
            c.with(e);
        });
        var replaceRead = ClassTransform.transformingMethodBodies(m -> m.methodName().equalsString("test"),
            CodeTransform.ofStateful(() -> new CodeTransform() {
                private int skip;
                @Override public void accept(CodeBuilder c, CodeElement e) {
                    if (skip != 0 && e instanceof Instruction i) {
                        var expected = skip == 2 ? Opcode.ICONST_0 : Opcode.AALOAD;
                        if (i.opcode() != expected) throw new IllegalStateException("Unexpected literal access: " + i);
                        skip--;
                    } else if (e instanceof FieldInstruction f && f.opcode() == Opcode.GETFIELD && f.name().equalsString("literals")) {
                        c.getfield(self, "literal0", byteArray);
                        skip = 2;
                    } else c.with(e);
                }
            }));
        return cf.transformClass(cf.parse(bytes), initialize.andThen(replaceRead).andThen(
            ClassTransform.endHandler(c -> c.withField("literal0", byteArray, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL))));
    }

    private static byte[] replaceCursor(byte[] bytes, ClassDesc replacement) {
        var cf = ClassFile.of();
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies((c, element) -> {
            if (element instanceof NewObjectInstruction n && n.className().asSymbol().equals(ORIGINAL)) {
                c.new_(replacement);
            } else if (element instanceof InvokeInstruction i && i.owner().asSymbol().equals(ORIGINAL)
                    && i.opcode() != Opcode.INVOKESTATIC) {
                String name = i.name().stringValue();
                MethodTypeDesc type = i.typeSymbol();
                if (replacement.equals(SCALA)) {
                    if (name.equals("<init>")) {
                        c.pop(); // Scala's cursor is bounded by the array, not an explicit slice length.
                        type = MethodTypeDesc.of(CD_void, CD_byte.arrayType(), CD_int);
                    } else if (name.equals("position")) name = "pos";
                }
                c.invoke(i.opcode(), replacement, name, type, false);
            } else c.with(element);
        }));
    }

    private static final class ScalaCursorPredicate implements AvroFilter {
        private final byte[] france = {70, 82};

        @Override public boolean test(byte[] bytes, int offset, int length) {
            var cursor = new konsume.format.AvroCursor(bytes, offset);
            cursor.skip(cursor.readLength());
            long age = cursor.readLong();
            int countryLength = cursor.readLength();
            int countryOffset = cursor.pos();
            cursor.skip(countryLength);
            return (double) age > 30 && AvroCursor.utf8Equals(bytes, countryOffset, countryLength, france);
        }
    }

    private static final class BaselinePredicate implements AvroFilter {
        private final byte[] france = {70, 82};

        @Override public boolean test(byte[] bytes, int offset, int length) {
            var cursor = new BaselineAvroCursor(bytes, offset, length);
            cursor.skip(cursor.readLength());
            long age = cursor.readLong();
            int countryLength = cursor.readLength();
            int countryOffset = cursor.position();
            cursor.skip(countryLength);
            return (double) age > 30 && AvroCursor.utf8Equals(bytes, countryOffset, countryLength, france);
        }
    }
}
