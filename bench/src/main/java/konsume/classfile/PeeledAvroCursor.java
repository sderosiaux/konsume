package konsume.classfile;

import java.util.Objects;

/** Control: Scala's loop shape, with all of the Java library's validation retained. */
final class PeeledAvroCursor {
    private final byte[] bytes;
    private final int limit;
    private int position;

    PeeledAvroCursor(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        this.bytes = bytes;
        this.position = offset;
        this.limit = offset + length;
    }

    int position() { return position; }

    long readLong() {
        if (position == limit) throw new MalformedAvroException("Truncated varint");
        int b = bytes[position++] & 255;
        long value = b & 127;
        int shift = 7;
        while ((b & 128) != 0) {
            if (position == limit) throw new MalformedAvroException("Truncated varint");
            b = bytes[position++] & 255;
            // At most one payload bit is legal in byte ten; this also rejects continuation.
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

    void skip(int n) {
        if (n < 0 || n > limit - position) throw new MalformedAvroException("Truncated value");
        position += n;
    }
}
