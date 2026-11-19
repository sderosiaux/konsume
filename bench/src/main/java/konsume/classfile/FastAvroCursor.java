package konsume.classfile;

import java.util.Objects;

/** Experimental control: same slice and malformed-varint checks, a one-byte fast path. */
final class FastAvroCursor {
    private final byte[] bytes;
    private final int limit;
    private int position;

    FastAvroCursor(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        this.bytes = bytes;
        this.position = offset;
        this.limit = offset + length;
    }

    int position() { return position; }

    long readLong() {
        if (position == limit) throw new MalformedAvroException("Truncated varint");
        int first = bytes[position];
        if (first >= 0) {
            position++;
            return (first >>> 1) ^ -(first & 1);
        }
        return readLongSlow();
    }

    private long readLongSlow() {
        long value = 0;
        for (int shift = 0; shift <= 63; shift += 7) {
            if (position == limit) throw new MalformedAvroException("Truncated varint");
            int b = bytes[position++] & 255;
            if (shift == 63 && (b & 254) != 0) throw new MalformedAvroException("Invalid varint");
            value |= (long) (b & 127) << shift;
            if ((b & 128) == 0) return (value >>> 1) ^ -(value & 1);
        }
        throw new MalformedAvroException("Invalid varint");
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
