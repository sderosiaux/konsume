package konsume.classfile;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.EncoderFactory;
import org.junit.Test;

import static konsume.classfile.Filter.*;
import static org.junit.Assert.*;

public class AvroFilterCompilerTest {
    private static final String AGE = "{\"name\":\"age\",\"type\":\"long\"}";
    private static final String COUNTRY = "{\"name\":\"country\",\"type\":\"string\"}";
    private static final String NOTE = "{\"name\":\"note\",\"type\":\"string\"}";

    private static Schema schema(List<String> fields) {
        return new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"Event\",\"fields\":["
            + "{\"name\":\"name\",\"type\":\"string\"},"
            + "{\"name\":\"user\",\"type\":{\"type\":\"record\",\"name\":\"User\",\"fields\":["
            + String.join(",", fields) + "]}},"
            + "{\"name\":\"sequence\",\"type\":\"long\"}]}");
    }

    private static GenericRecord event(Schema schema, long age, String country, long sequence) {
        var user = new GenericData.Record(schema.getField("user").schema());
        user.put("age", age);
        user.put("country", country);
        if (user.getSchema().getField("note") != null) user.put("note", "ignored 🐈");
        var record = new GenericData.Record(schema);
        record.put("name", "message");
        record.put("user", user);
        record.put("sequence", sequence);
        return record;
    }

    private static byte[] encode(GenericRecord record) throws Exception {
        var out = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(record.getSchema()).write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    private static Object field(GenericRecord record, String path) {
        Object value = record;
        for (String name : path.split("\\.")) value = ((GenericRecord) value).get(name);
        return value;
    }

    // Independent reference on materialized Avro data, not the generated reading plan.
    private static boolean evaluate(Filter filter, GenericRecord record) {
        return switch (filter) {
            case NumberComparison f -> {
                double a = ((Number) field(record, f.path())).doubleValue();
                double b = f.value();
                yield switch (f.comparison()) {
                    case EQ -> a == b;
                    case NE -> a != b;
                    case LT -> a < b;
                    case LE -> a <= b;
                    case GT -> a > b;
                    case GE -> a >= b;
                };
            }
            case StringEquality f -> field(record, f.path()).toString().equals(f.value());
            case And f -> evaluate(f.left(), record) && evaluate(f.right(), record);
            case Or f -> evaluate(f.left(), record) || evaluate(f.right(), record);
            case Not f -> !evaluate(f.operand(), record);
        };
    }

    @Test public void dynamicFiltersAndFieldOrdersAgreeWithMaterializedAvro() throws Exception {
        var random = new Random(4021);
        String[] countries = {"FR", "US", "", "é🇫🇷", "x".repeat(200)};
        for (int layout = 0; layout < 12; layout++) {
            var fields = new ArrayList<>(List.of(AGE, COUNTRY, NOTE));
            Collections.shuffle(fields, random);
            var schema = schema(fields);
            double threshold = random.nextInt(80) - 20;
            String country = countries[layout % countries.length];
            var filters = List.of(
                and(gt("user.age", threshold), eq("user.country", country)),
                or(lt("user.age", threshold), not(eq("user.country", country))),
                and(ge("user.age", threshold), le("user.age", threshold + 5)),
                and(ne("user.age", threshold), eq("sequence", 7)),
                or(eq("user.age", threshold), gt("sequence", 30))
            );
            for (Filter filter : filters) {
                AvroFilter compiled = AvroFilterCompiler.compile(schema, filter);
                assertTrue(compiled.getClass().isHidden());
                for (int i = 0; i < 100; i++) {
                    var record = event(schema, random.nextInt(100) - 30, countries[random.nextInt(countries.length)], i);
                    byte[] bytes = encode(record);
                    assertEquals(filter.toString(), evaluate(filter, record), compiled.test(bytes));
                }
            }
        }
    }

    @Test public void numberBoundariesUseTheSameDoubleSemanticsAsJq() throws Exception {
        var schema = schema(List.of(AGE, COUNTRY));
        long[] values = {Long.MIN_VALUE, -1, 0, 1, 30, 31, 9_007_199_254_740_992L, 9_007_199_254_740_993L, Long.MAX_VALUE};
        for (Comparison op : Comparison.values()) {
            for (double literal : new double[] {-0.0, 30.5, 9_007_199_254_740_992d, (double) Long.MAX_VALUE}) {
                Filter filter = new NumberComparison("user.age", op, literal);
                var compiled = AvroFilterCompiler.compile(schema, filter);
                for (long value : values) {
                    var record = event(schema, value, "FR", 0);
                    assertEquals(evaluate(filter, record), compiled.test(encode(record)));
                }
            }
        }
    }

    @Test public void stringLiteralBoundariesUnicodeAndEveryMismatchAgreeWithAvro() throws Exception {
        var schema = schema(List.of(AGE, COUNTRY));
        for (String literal : List.of("", "F", "FR", "abcdefg", "abcdefgh", "abcdefghi",
                "x".repeat(200), "é", "€", "🐈", "éééé", "ééééé", "\u0000FR")) {
            var filter = AvroFilterCompiler.compile(schema, eq("user.country", literal));
            byte[] payload = encode(event(schema, 42, literal, 0));
            byte[] slice = new byte[payload.length + 11];
            System.arraycopy(payload, 0, slice, 7, payload.length);
            assertTrue(literal, filter.test(slice, 7, payload.length));
            assertFalse(literal, filter.test(encode(event(schema, 42, literal + "!", 0))));
            for (int i = 0; i < literal.length(); i++) {
                String changed = literal.substring(0, i) + "!" + literal.substring(i + 1);
                assertFalse(literal + " mismatch at " + i, filter.test(encode(event(schema, 42, changed, 0))));
            }
            Filter expression = and(not(eq("user.country", "DE")),
                or(eq("user.country", literal), eq("user.country", "another long literal")));
            var compound = AvroFilterCompiler.compile(schema, expression);
            for (String country : List.of(literal, "DE", "another long literal", "different")) {
                var record = event(schema, 42, country, 0);
                assertEquals(evaluate(expression, record), compound.test(encode(record)));
            }
        }
    }

    @Test public void integerThresholdSpecializationPreservesDoubleRounding() throws Exception {
        var schema = schema(List.of(AGE, COUNTRY));
        long exact = 1L << 53;
        var values = new ArrayList<Long>(List.of(Long.MIN_VALUE, Long.MAX_VALUE,
            -exact - 2, -exact - 1, -exact, -exact + 1, -exact + 2,
            exact - 2, exact - 1, exact, exact + 1, exact + 2,
            -31L, -30L, -1L, 0L, 1L, 29L, 30L, 31L));
        var random = new Random(73419);
        for (int i = 0; i < 100; i++) values.add(random.nextLong());
        double[] thresholds = {-0x1.0p63, -0x1.0p53 - 2, -0x1.0p53,
            -0x1.0p53 + 1, -0x1.0p53 + 2, -30, -0.5, -0.0, 0.0, 0.5,
            1, 30, 30.5, 0x1.0p53 - 2, 0x1.0p53 - 1, 0x1.0p53,
            0x1.0p53 + 2, 0x1.0p63};
        for (Comparison op : Comparison.values()) {
            for (double threshold : thresholds) {
                Filter expression = new NumberComparison("user.age", op, threshold);
                var compiled = AvroFilterCompiler.compile(schema, expression);
                for (long value : values) {
                    var record = event(schema, value, "FR", 0);
                    assertEquals(value + " " + op + " " + threshold,
                        evaluate(expression, record), compiled.test(encode(record)));
                }
            }
        }
    }

    @Test public void literalLoadsRespectEveryAlignmentAndSliceBoundary() throws Exception {
        var schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[" + COUNTRY + "]}");
        var literals = new ArrayList<>(List.of("é", "€", "🐈", "ééé", "éééé", "a€🐈", "\u0000FR"));
        for (int size = 0; size <= 40; size++) literals.add("x".repeat(size));
        for (String literal : literals) {
            var filter = AvroFilterCompiler.compile(schema, eq("country", literal));
            var record = new GenericData.Record(schema);
            record.put("country", literal);
            byte[] payload = encode(record);
            // All literals here use a one-byte Avro length; the bytes after it are the value.
            assertTrue(payload.length <= 41);
            for (int offset = 0; offset < 16; offset++) {
                byte[] bytes = new byte[offset + payload.length + 16];
                Arrays.fill(bytes, (byte) 0x55);
                System.arraycopy(payload, 0, bytes, offset, payload.length);
                assertTrue(filter.test(bytes, offset, payload.length));
                for (int index = 1; index < payload.length; index++) {
                    bytes[offset + index] ^= 1;
                    assertFalse(filter.test(bytes, offset, payload.length));
                    bytes[offset + index] ^= 1;
                }
                for (int length = 0; length < payload.length; length++) {
                    final int start = offset, truncatedLength = length;
                    // Matching bytes exist beyond the slice: they must never make this valid.
                    assertThrows(MalformedAvroException.class,
                        () -> filter.test(bytes, start, truncatedLength));
                }
            }
        }
    }

    @Test public void rejectedNumericConditionStillValidatesReferencedString() {
        var schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[" + AGE + "," + COUNTRY + "]}");
        var filter = AvroFilterCompiler.compile(schema, and(gt("age", 30), eq("country", "FR")));
        // age=0 rejects the predicate, but all referenced fields must still be validated.
        assertThrows(MalformedAvroException.class, () -> filter.test(new byte[] {0, 4, 70}));
        assertThrows(MalformedAvroException.class, () -> filter.test(new byte[] {0, 4, 70, 82}, 0, 3));
    }

    @Test public void slicesFramingAndOriginalBytes() throws Exception {
        var schema = schema(List.of(AGE, COUNTRY));
        var filter = AvroFilterCompiler.compile(schema, and(gt("user.age", 30), eq("user.country", "FR")));
        byte[] raw = encode(event(schema, 42, "FR", 1));
        byte[] framed = ByteBuffer.allocate(raw.length + 5).put((byte) 0).putInt(17).put(raw).array();
        byte[] before = framed.clone();
        assertTrue(filter.testConfluent(framed, 17));
        assertTrue(filter.test(framed, 5, raw.length));
        assertArrayEquals(before, framed);
        assertThrows(MalformedAvroException.class, () -> filter.testConfluent(framed, 18));
        assertThrows(MalformedAvroException.class, () -> filter.testConfluent(new byte[4], 17));
        framed[0] = 1;
        assertThrows(MalformedAvroException.class, () -> filter.testConfluent(framed, 17));
        assertThrows(IndexOutOfBoundsException.class, () -> filter.test(raw, -1, raw.length));
        assertThrows(IndexOutOfBoundsException.class, () -> filter.test(raw, 1, raw.length));
    }

    @Test public void truncationIsCheckedWithinTheSuppliedSlice() throws Exception {
        var schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[" + AGE + "," + COUNTRY + "]}");
        var record = new GenericData.Record(schema);
        record.put("age", 42L);
        record.put("country", "FR");
        byte[] bytes = encode(record);
        var filter = AvroFilterCompiler.compile(schema, and(gt("age", 30), eq("country", "FR")));
        for (int end = 0; end < bytes.length; end++) {
            final int size = end;
            assertThrows(MalformedAvroException.class, () -> filter.test(bytes, 0, size));
        }
        byte[] badVarint = new byte[10];
        Arrays.fill(badVarint, (byte) 0xff);
        assertThrows(MalformedAvroException.class, () -> filter.test(badVarint));
        assertThrows(MalformedAvroException.class, () -> filter.test(new byte[] {84, 127}));
    }

    @Test public void trailingUnusedFieldsAreIntentionallyNotValidated() {
        var schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[" + AGE + "," + COUNTRY + "]}");
        var filter = AvroFilterCompiler.compile(schema, gt("age", 30));
        assertTrue(filter.test(new byte[] {84})); // country is absent, but not needed by this filter.
    }

    @Test public void skipsComplexFieldsBeforeTheRequestedValue() throws Exception {
        var schema = new Schema.Parser().parse("""
            {"type":"record","name":"R","fields":[
              {"name":"a","type":{"type":"array","items":"long"}},
              {"name":"m","type":{"type":"map","values":"string"}},
              {"name":"u","type":["null","string"]},
              {"name":"f","type":{"type":"fixed","name":"F","size":3}},
              {"name":"b","type":"bytes"},
              {"name":"node","type":{"type":"record","name":"Node","fields":[
                {"name":"next","type":["null","Node"]},{"name":"v","type":"long"}]}},
              {"name":"age","type":"long"}]}
            """);
        var record = new GenericData.Record(schema);
        record.put("a", List.of(-1L, 42L, Long.MAX_VALUE));
        record.put("m", Map.of("first", "🇫🇷", "second", ""));
        record.put("u", "skip");
        record.put("f", new GenericData.Fixed(schema.getField("f").schema(), new byte[] {1, 2, 3}));
        record.put("b", ByteBuffer.wrap(new byte[] {4, 5}));
        var nodeSchema = schema.getField("node").schema();
        var leaf = new GenericData.Record(nodeSchema);
        leaf.put("next", null); leaf.put("v", 1L);
        var node = new GenericData.Record(nodeSchema);
        node.put("next", leaf); node.put("v", 2L);
        record.put("node", node);
        record.put("age", 42L);
        assertTrue(AvroFilterCompiler.compile(schema, gt("age", 30)).test(encode(record)));
    }

    @Test public void skipsNegativeCountBlocksAndRejectsInvalidLengths() {
        var schema = new Schema.Parser().parse("""
            {"type":"record","name":"R","fields":[
              {"name":"a","type":{"type":"array","items":"int"}},
              {"name":"age","type":"int"}]}
            """);
        var filter = AvroFilterCompiler.compile(schema, gt("age", 30));
        assertTrue(filter.test(new byte[] {3, 4, 2, 4, 0, 84}));
        assertThrows(MalformedAvroException.class, () -> filter.test(new byte[] {3, 100, 2, 4, 0, 84}));
    }

    @Test public void unsupportedFiltersFailAtCompilation() {
        var schema = schema(List.of(AGE, COUNTRY));
        assertThrows(IllegalArgumentException.class, () -> AvroFilterCompiler.compile(schema, gt("user.missing", 0)));
        assertThrows(IllegalArgumentException.class, () -> AvroFilterCompiler.compile(schema, gt("user.country", 0)));
        assertThrows(IllegalArgumentException.class, () -> AvroFilterCompiler.compile(schema, eq("user.age", "42")));
        var nullable = schema(List.of(AGE, "{\"name\":\"country\",\"type\":[\"null\",\"string\"]}"));
        assertThrows(IllegalArgumentException.class, () -> AvroFilterCompiler.compile(nullable, eq("user.country", "FR")));
        assertThrows(IllegalArgumentException.class, () -> gt(".user.age", 30));
        assertThrows(IllegalArgumentException.class, () -> gt("user.age", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> eq("user.country", "\ud800"));
    }

    @Test public void oneCompiledInstanceCanBeSharedBetweenThreads() throws Exception {
        var schema = schema(List.of(COUNTRY, AGE));
        var filter = AvroFilterCompiler.compile(schema, and(gt("user.age", 30), eq("user.country", "FR")));
        byte[] yes = encode(event(schema, 42, "FR", 1));
        byte[] no = encode(event(schema, 42, "DE", 1));
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) tasks.add(() -> {
                for (int j = 0; j < 1000; j++) if (!filter.test(yes) || filter.test(no)) return false;
                return true;
            });
            for (var future : pool.invokeAll(tasks)) assertTrue(future.get());
        }
    }
}
