package konsume.classfile;

import java.util.Objects;

/**
 * A reusable, thread-safe predicate for one Avro writer schema. Inputs must not be mutated
 * during evaluation. A true result lets the caller retain the original message unchanged.
 * Only the prefix through the last required field is read; this is not a full Avro validator.
 */
@FunctionalInterface
public interface AvroFilter {
    /**
     * Tests raw Avro bytes in the given slice (without a Confluent header).
     * @throws MalformedAvroException if the required prefix cannot be read
     * @throws IndexOutOfBoundsException if the caller supplies an invalid slice
     */
    boolean test(byte[] bytes, int offset, int length);

    default boolean test(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return test(bytes, 0, bytes.length);
    }

    /** Checks Confluent framing and the expected writer-schema id before evaluating the payload. */
    default boolean testConfluent(byte[] bytes, int expectedSchemaId) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 5 || bytes[0] != 0) {
            throw new MalformedAvroException("Invalid Confluent header");
        }
        int id = (bytes[1] & 255) << 24 | (bytes[2] & 255) << 16 | (bytes[3] & 255) << 8 | bytes[4] & 255;
        if (id != expectedSchemaId) throw new MalformedAvroException("Unexpected writer-schema id: " + id);
        return test(bytes, 5, bytes.length - 5);
    }
}
