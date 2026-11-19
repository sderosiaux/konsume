package konsume.classfile;

import java.nio.charset.StandardCharsets;

/** Benchmark baseline only: same Java cursor and output contract as the generated predicate. */
public final class HandwrittenAvroFilter implements AvroFilter {
    private final byte[] france = "FR".getBytes(StandardCharsets.UTF_8);

    @Override public boolean test(byte[] bytes, int offset, int length) {
        var cursor = new AvroCursor(bytes, offset, length);
        cursor.skip(cursor.readLength()); // name
        long age = cursor.readLong();
        int countryLength = cursor.readLength();
        int countryOffset = cursor.position();
        cursor.skip(countryLength);
        return (double) age > 30 && AvroCursor.utf8Equals(bytes, countryOffset, countryLength, france);
    }
}
