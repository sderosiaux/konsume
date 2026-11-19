package konsume.classfile;

import java.util.Arrays;
import java.util.Objects;
import org.apache.avro.Schema;

/** Runtime support for generated classes; one cursor per call, no shared mutable state. */
final class AvroCursor {
    private final byte[] bytes;
    private final int limit;
    private int position;

    AvroCursor(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        this.bytes = bytes;
        this.position = offset;
        this.limit = offset + length;
    }

    int position() { return position; }

    long readLong() {
        // Most lengths and small integers fit in one byte. Keep the whole decoder in one
        // method so C2 can inline it and eliminate the per-call cursor allocation.
        if (position == limit) throw new MalformedAvroException("Truncated varint");
        int b = bytes[position++] & 255;
        long value = b & 127;
        int shift = 7;
        while ((b & 128) != 0) {
            if (position == limit) throw new MalformedAvroException("Truncated varint");
            b = bytes[position++] & 255;
            // Only one payload bit is legal in byte ten; this also rejects continuation.
            if (shift == 63 && (b & 254) != 0) throw new MalformedAvroException("Invalid varint");
            value |= (long) (b & 127) << shift;
            shift += 7;
        }
        return (value >>> 1) ^ -(value & 1);
    }

    int readLength() {
        long n = readLong();
        if (n < 0 || n > limit - position) throw new MalformedAvroException("Invalid byte length");
        return (int) n;
    }

    /** Validate the length once, advance over the entire value and return its byte length. */
    int readLengthAndSkip() {
        int n = readLength();
        position += n; // readLength already proved n <= limit - position.
        return n;
    }

    void skip(int n) {
        if (n < 0 || n > limit - position) throw new MalformedAvroException("Truncated value");
        position += n;
    }

    void skipVarint() { readLong(); }

    static boolean utf8Equals(byte[] bytes, int offset, int length, byte[] literal) {
        return length == literal.length && Arrays.equals(bytes, offset, offset + length, literal, 0, length);
    }

    // Complex unused fields are skipped generically; common scalar skips are emitted directly.
    void skipValue(Schema schema) { skipValue(schema, 0); }

    private void skipValue(Schema schema, int depth) {
        if (depth > 128) throw new MalformedAvroException("Skipped value exceeds nesting limit (128)");
        switch (schema.getType()) {
            case NULL -> { }
            case BOOLEAN -> skip(1);
            case INT, LONG, ENUM -> skipVarint();
            case FLOAT -> skip(4);
            case DOUBLE -> skip(8);
            case STRING, BYTES -> readLengthAndSkip();
            case FIXED -> skip(schema.getFixedSize());
            case RECORD -> {
                for (Schema.Field field : schema.getFields()) skipValue(field.schema(), depth + 1);
            }
            case UNION -> {
                long index = readLong();
                if (index < 0 || index >= schema.getTypes().size()) {
                    throw new MalformedAvroException("Invalid union index");
                }
                skipValue(schema.getTypes().get((int) index), depth + 1);
            }
            case ARRAY, MAP -> {
                long count;
                while ((count = readLong()) != 0) {
                    if (count < 0) {
                        if (count == Long.MIN_VALUE) throw new MalformedAvroException("Invalid block count");
                        readLengthAndSkip(); // Negative count is followed by the encoded block's byte length.
                    } else {
                        for (long i = 0; i < count; i++) {
                            if (schema.getType() == Schema.Type.MAP) readLengthAndSkip();
                            skipValue(schema.getType() == Schema.Type.MAP ? schema.getValueType() : schema.getElementType(), depth + 1);
                        }
                    }
                }
            }
        }
    }
}
