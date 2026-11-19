package konsume.classfile;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable filter description. Numeric comparisons use doubles, like konsume's jq engine. */
public sealed interface Filter {
    enum Comparison { EQ, NE, LT, LE, GT, GE }

    /** The referenced writer-schema field must be an Avro int or long. */
    record NumberComparison(String path, Comparison comparison, double value) implements Filter {
        public NumberComparison {
            validatePath(path);
            Objects.requireNonNull(comparison, "comparison");
            if (!Double.isFinite(value)) throw new IllegalArgumentException("The literal must be finite");
        }
    }

    /** The referenced writer-schema field must be an Avro string. */
    record StringEquality(String path, String value) implements Filter {
        public StringEquality {
            validatePath(path);
            Objects.requireNonNull(value, "value");
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
                throw new IllegalArgumentException("The literal must be valid Unicode");
            }
        }
    }

    record And(Filter left, Filter right) implements Filter {
        public And { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }

    record Or(Filter left, Filter right) implements Filter {
        public Or { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }

    record Not(Filter operand) implements Filter {
        public Not { Objects.requireNonNull(operand); }
    }

    static Filter gt(String path, double value) { return new NumberComparison(path, Comparison.GT, value); }
    static Filter ge(String path, double value) { return new NumberComparison(path, Comparison.GE, value); }
    static Filter lt(String path, double value) { return new NumberComparison(path, Comparison.LT, value); }
    static Filter le(String path, double value) { return new NumberComparison(path, Comparison.LE, value); }
    static Filter eq(String path, double value) { return new NumberComparison(path, Comparison.EQ, value); }
    static Filter ne(String path, double value) { return new NumberComparison(path, Comparison.NE, value); }
    static Filter eq(String path, String value) { return new StringEquality(path, value); }
    static Filter and(Filter left, Filter right) { return new And(left, right); }
    static Filter or(Filter left, Filter right) { return new Or(left, right); }
    static Filter not(Filter operand) { return new Not(operand); }

    private static void validatePath(String path) {
        Objects.requireNonNull(path, "path");
        for (String name : path.split("\\.", -1)) {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Expected a dotted Avro field path: " + path);
            }
        }
    }
}
