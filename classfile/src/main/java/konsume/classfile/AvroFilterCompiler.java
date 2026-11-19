package konsume.classfile;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.avro.Schema;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

/**
 * Generates JVM bytecode directly with the Java 24 Class-File API: no source compiler or
 * Scala runtime. Compilation has no global cache; applications own caching and eviction.
 * Generated classes are weak hidden classes and can unload after their filters are released.
 */
public final class AvroFilterCompiler {
    private static final ClassDesc GENERATED = ClassDesc.of("konsume.classfile.CompiledAvroFilter");
    private static final ClassDesc FILTER = ClassDesc.of(AvroFilter.class.getName());
    private static final ClassDesc CURSOR = ClassDesc.of(AvroCursor.class.getName());
    private static final ClassDesc SCHEMA = ClassDesc.of(Schema.class.getName());
    private static final ClassDesc BYTES = CD_byte.arrayType();
    private static final ClassDesc LITERALS = BYTES.arrayType();
    private static final ClassDesc SCHEMAS = SCHEMA.arrayType();

    private AvroFilterCompiler() { }

    /**
     * Compiles an immutable predicate for the supplied writer schema. Referenced paths must
     * traverse records to non-nullable int/long or string fields. Other paths/types fail here,
     * before processing any messages. Do not mutate the Schema after compilation.
     */
    public static AvroFilter compile(Schema writerSchema, Filter filter) {
        return instantiate(generate(writerSchema, filter));
    }

    // Package-private inspection seam: benchmarks can compare exactly the same bytes under
    // different loading modes. The public library always uses unloadable hidden classes.
    record Generated(byte[] bytecode, byte[][] literals, Schema[] schemas) { }

    static Generated generate(Schema writerSchema, Filter filter) {
        Objects.requireNonNull(writerSchema, "writerSchema");
        Objects.requireNonNull(filter, "filter");
        Node root = new Node();
        collect(writerSchema, filter, root);
        List<Step> steps = new ArrayList<>();
        plan(writerSchema, root, "", false, steps);
        List<byte[]> literals = new ArrayList<>();
        List<Schema> skippedSchemas = new ArrayList<>();

        byte[] bytecode = ClassFile.of().build(GENERATED, cl -> {
            cl.withVersion(JAVA_24_VERSION, 0)
                .withFlags(ACC_FINAL | ACC_SUPER)
                .withInterfaceSymbols(FILTER)
                .withField("literals", LITERALS, ACC_PRIVATE | ACC_FINAL)
                .withField("schemas", SCHEMAS, ACC_PRIVATE | ACC_FINAL)
                .withMethodBody("<init>", MethodTypeDesc.of(CD_void, LITERALS, SCHEMAS), ACC_PUBLIC, c ->
                    c.aload(0).invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void))
                        .aload(0).aload(1).putfield(GENERATED, "literals", LITERALS)
                        .aload(0).aload(2).putfield(GENERATED, "schemas", SCHEMAS).return_())
                .withMethodBody("test", MethodTypeDesc.of(CD_boolean, BYTES, CD_int, CD_int), ACC_PUBLIC | ACC_FINAL, c -> {
                    int cursor = c.allocateLocal(TypeKind.REFERENCE);
                    c.new_(CURSOR).dup().aload(1).iload(2).iload(3)
                        .invokespecial(CURSOR, "<init>", MethodTypeDesc.of(CD_void, BYTES, CD_int, CD_int)).astore(cursor);
                    Map<String, Local> values = new LinkedHashMap<>();
                    for (Step step : steps) {
                        switch (step) {
                            case ReadNumber read -> {
                                int slot = c.allocateLocal(TypeKind.LONG);
                                c.aload(cursor).invokevirtual(CURSOR, "readLong", MethodTypeDesc.of(CD_long)).lstore(slot);
                                values.put(read.path(), new Local(slot, -1));
                            }
                            case ReadString read -> {
                                int length = c.allocateLocal(TypeKind.INT);
                                int offset = c.allocateLocal(TypeKind.INT);
                                c.aload(cursor).invokevirtual(CURSOR, "readLength", MethodTypeDesc.of(CD_int)).istore(length)
                                    .aload(cursor).invokevirtual(CURSOR, "position", MethodTypeDesc.of(CD_int)).istore(offset)
                                    .aload(cursor).iload(length).invokevirtual(CURSOR, "skip", MethodTypeDesc.of(CD_void, CD_int));
                                values.put(read.path(), new Local(offset, length));
                            }
                            case Skip skip -> emitSkip(c, cursor, skip.schema(), skippedSchemas);
                        }
                    }
                    Label yes = c.newLabel();
                    Label no = c.newLabel();
                    emitFilter(c, filter, values, literals, yes, no);
                    c.labelBinding(yes).iconst_1().ireturn().labelBinding(no).iconst_0().ireturn();
                });
        });

        return new Generated(bytecode, literals.toArray(byte[][]::new), skippedSchemas.toArray(Schema[]::new));
    }

    private static AvroFilter instantiate(Generated generated) {
        try {
            var lookup = MethodHandles.lookup().defineHiddenClass(generated.bytecode(), true);
            var constructor = lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(void.class, byte[][].class, Schema[].class));
            return (AvroFilter) constructor.invoke(generated.literals(), generated.schemas());
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot load generated Avro filter", e);
        }
    }

    private enum Kind { NUMBER, STRING }
    private static final class Node {
        final Map<String, Node> children = new LinkedHashMap<>();
        Kind kind;
    }
    private sealed interface Step { }
    private record ReadNumber(String path) implements Step { }
    private record ReadString(String path) implements Step { }
    private record Skip(Schema schema) implements Step { }
    private record Local(int first, int second) { }

    private static void collect(Schema schema, Filter filter, Node root) {
        switch (filter) {
            case Filter.NumberComparison f -> demand(schema, root, f.path(), Kind.NUMBER);
            case Filter.StringEquality f -> demand(schema, root, f.path(), Kind.STRING);
            case Filter.And f -> { collect(schema, f.left(), root); collect(schema, f.right(), root); }
            case Filter.Or f -> { collect(schema, f.left(), root); collect(schema, f.right(), root); }
            case Filter.Not f -> collect(schema, f.operand(), root);
        }
    }

    private static void demand(Schema schema, Node node, String path, Kind kind) {
        for (String name : path.split("\\.")) {
            if (schema.getType() != Schema.Type.RECORD || schema.getField(name) == null) {
                throw new IllegalArgumentException("Unsupported or missing record path: " + path);
            }
            schema = schema.getField(name).schema();
            node = node.children.computeIfAbsent(name, ignored -> new Node());
        }
        boolean supported = kind == Kind.NUMBER
            ? schema.getType() == Schema.Type.INT || schema.getType() == Schema.Type.LONG
            : schema.getType() == Schema.Type.STRING;
        if (!supported) throw new IllegalArgumentException("Unsupported " + kind + " field " + path + ": " + schema.getType());
        node.kind = kind;
    }

    // Field order comes from the writer schema, never from the filter expression.
    private static void plan(Schema schema, Node node, String prefix, boolean needEnd, List<Step> steps) {
        if (node.kind != null) {
            steps.add(node.kind == Kind.NUMBER ? new ReadNumber(prefix) : new ReadString(prefix));
            return;
        }
        List<Schema.Field> fields = schema.getFields();
        int last = fields.size() - 1;
        if (!needEnd) {
            while (last >= 0 && !node.children.containsKey(fields.get(last).name())) last--;
        }
        for (int i = 0; i <= last; i++) {
            Schema.Field field = fields.get(i);
            Node child = node.children.get(field.name());
            if (child == null) steps.add(new Skip(field.schema()));
            else plan(field.schema(), child, prefix.isEmpty() ? field.name() : prefix + "." + field.name(), needEnd || i < last, steps);
        }
    }

    private static void emitSkip(CodeBuilder c, int cursor, Schema schema, List<Schema> schemas) {
        switch (schema.getType()) {
            case NULL -> { }
            case INT, LONG, ENUM -> c.aload(cursor).invokevirtual(CURSOR, "skipVarint", MethodTypeDesc.of(CD_void));
            case BOOLEAN, FLOAT, DOUBLE, FIXED -> {
                int size = switch (schema.getType()) {
                    case BOOLEAN -> 1;
                    case FLOAT -> 4;
                    case DOUBLE -> 8;
                    default -> schema.getFixedSize();
                };
                c.aload(cursor).loadConstant(size).invokevirtual(CURSOR, "skip", MethodTypeDesc.of(CD_void, CD_int));
            }
            case STRING, BYTES -> c.aload(cursor).aload(cursor)
                .invokevirtual(CURSOR, "readLength", MethodTypeDesc.of(CD_int))
                .invokevirtual(CURSOR, "skip", MethodTypeDesc.of(CD_void, CD_int));
            default -> {
                int index = schemas.size();
                schemas.add(schema);
                c.aload(cursor).aload(0).getfield(GENERATED, "schemas", SCHEMAS).loadConstant(index).aaload()
                    .invokevirtual(CURSOR, "skipValue", MethodTypeDesc.of(CD_void, SCHEMA));
            }
        }
    }

    private static void emitFilter(CodeBuilder c, Filter filter, Map<String, Local> values, List<byte[]> literals, Label yes, Label no) {
        switch (filter) {
            case Filter.NumberComparison f -> {
                c.lload(values.get(f.path()).first()).l2d().loadConstant(f.value()).dcmpg();
                switch (f.comparison()) {
                    case EQ -> c.ifeq(yes);
                    case NE -> c.ifne(yes);
                    case LT -> c.iflt(yes);
                    case LE -> c.ifle(yes);
                    case GT -> c.ifgt(yes);
                    case GE -> c.ifge(yes);
                }
                c.goto_(no);
            }
            case Filter.StringEquality f -> {
                Local value = values.get(f.path());
                byte[] literal = f.value().getBytes(StandardCharsets.UTF_8);
                // The literal is known when generating the filter. For short strings,
                // expose its length and bytes as constants instead of loading a byte[]
                // and invoking the general range-comparison routine for every record.
                // The reading plan has already validated the complete string slice.
                if (literal.length <= 8) {
                    c.iload(value.second()).loadConstant(literal.length).if_icmpne(no);
                    for (int i = 0; i < literal.length; i++) {
                        c.aload(1).iload(value.first());
                        if (i != 0) c.loadConstant(i).iadd();
                        c.baload().loadConstant((int) literal[i]).if_icmpne(no);
                    }
                    c.goto_(yes);
                    break;
                }
                int index = literals.size();
                literals.add(literal);
                c.aload(1).iload(value.first()).iload(value.second())
                    .aload(0).getfield(GENERATED, "literals", LITERALS).loadConstant(index).aaload()
                    .invokestatic(CURSOR, "utf8Equals", MethodTypeDesc.of(CD_boolean, BYTES, CD_int, CD_int, BYTES))
                    .ifne(yes).goto_(no);
            }
            case Filter.And f -> {
                Label next = c.newLabel();
                Filter left = f.left(), right = f.right();
                // Experimental heuristic: categorical equality before a numeric
                // threshold. All referenced fields have already been validated;
                // only the order of pure comparisons changes, never the reading plan.
                if (left instanceof Filter.NumberComparison && right instanceof Filter.StringEquality) {
                    left = f.right();
                    right = f.left();
                }
                emitFilter(c, left, values, literals, next, no);
                c.labelBinding(next);
                emitFilter(c, right, values, literals, yes, no);
            }
            case Filter.Or f -> {
                Label next = c.newLabel();
                emitFilter(c, f.left(), values, literals, yes, next);
                c.labelBinding(next);
                emitFilter(c, f.right(), values, literals, yes, no);
            }
            case Filter.Not f -> emitFilter(c, f.operand(), values, literals, no, yes);
        }
    }
}
