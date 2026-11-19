package konsume.classfile;

/** Invalid or truncated bytes encountered in the portion of the payload actually read. */
public final class MalformedAvroException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MalformedAvroException(String message) {
        super(message, null, false, false);
    }
}
