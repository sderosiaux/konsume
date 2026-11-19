package konsume.classfile;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Plain, unaligned loads used only after the generated reading plan validates the slice. */
final class LiteralBytes {
    private static final VarHandle SHORTS = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private LiteralBytes() { }

    static int read16(byte[] bytes, int offset) { return (short) SHORTS.get(bytes, offset); }
    static int read32(byte[] bytes, int offset) { return (int) INTS.get(bytes, offset); }
    static long read64(byte[] bytes, int offset) { return (long) LONGS.get(bytes, offset); }
}
