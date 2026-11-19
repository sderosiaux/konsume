package konsume.classfile;

/** Diagnostic only: same peeled loop, array bounds instead of a bounded slice contract. */
final class ArrayBoundedCursor {
    private final byte[] bytes;
    private int position;

    ArrayBoundedCursor(byte[] bytes, int offset, int ignoredLength) {
        this.bytes = bytes;
        this.position = offset;
    }

    int position() { return position; }

    long readLong() {
        int b = bytes[position++] & 255;
        long value = b & 127;
        int shift = 7;
        while ((b & 128) != 0) {
            b = bytes[position++] & 255;
            if (shift == 63 && (b & 254) != 0) throw new MalformedAvroException("Invalid varint");
            value |= (long) (b & 127) << shift;
            shift += 7;
        }
        return (value >>> 1) ^ -(value & 1);
    }

    int readLength() {
        long n = readLong();
        if (n < 0 || n > bytes.length - position) throw new MalformedAvroException("Invalid byte length");
        return (int) n;
    }

    void skip(int n) {
        if (n < 0 || n > bytes.length - position) throw new MalformedAvroException("Truncated value");
        position += n;
    }
}
