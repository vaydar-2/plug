package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.util.List;

/*
 * BSON -> Plug type mapping shared by the MongoDB adapter's
 * schema resolution and document decoding.
 *
 * Pure functions only: no client, no server, no state. Keeping
 * every conversion here means the exactness rules are enforced
 * in one place and can be tested without a MongoDB instance.
 *
 * Plug's value domain is Integer, Double, String and Boolean.
 * There is no Long and no arbitrary-precision number, so a
 * BSON value maps only when the conversion is lossless:
 *
 *   - Int32 becomes Integer.
 *   - Int64 becomes Integer while every value fits the int
 *     range, Double while every value is exactly representable
 *     (|v| <= 2^53), and otherwise the field is omitted.
 *   - Decimal128 becomes Double only when the decimal round
 *     trips exactly; inexact values are omitted.
 *   - Unsupported BSON types are omitted.
 *
 * Omission happens during schema inference. Once a schema type
 * is established, a value that does not fit it fails with a
 * PlugException at decode time instead of being rounded or
 * truncated.
 */
public final class MongoTypeMapping {

    /*
     * 2^53 is the largest integer magnitude a double holds
     * without losing precision. 2^53 itself is exact; 2^53 + 1
     * is not.
     */
    private static final long MAX_EXACT_DOUBLE_INTEGER =
            1L << 53;

    private MongoTypeMapping() {
    }

    /*
     * Plug type declared by a $jsonSchema bsonType, or null when
     * the declared type has no exact Plug equivalent and must
     * therefore stay out of the schema.
     *
     * "long" declares Integer because Plug has no 64-bit integer
     * type: values inside the int range decode normally, and
     * larger values fail closed at decode time rather than being
     * silently widened into Double.
     */
    public static Class<?> declaredType(String bsonType) {

        if (bsonType == null) {
            return null;
        }

        switch (bsonType) {

            case "int":
            case "long":
                return Integer.class;

            case "double":
            case "decimal":
                return Double.class;

            case "string":
                return String.class;

            case "bool":
                return Boolean.class;

            default:
                return null;
        }
    }

    /*
     * Plug type for a declared bsonType union. All members must
     * collapse to one Plug type: identical types stay as they
     * are, and numeric mixtures widen to Double (Integer is
     * exactly representable there). Any non-numeric mixture or
     * unsupported member makes the field unknown.
     */
    public static Class<?> declaredType(List<String> bsonTypes) {

        if (bsonTypes == null || bsonTypes.isEmpty()) {
            return null;
        }

        Class<?> result = null;

        for (String bsonType : bsonTypes) {

            Class<?> type = declaredType(bsonType);

            if (type == null) {
                return null;
            }

            if (result == null) {
                result = type;
            } else if (result == type) {
                continue;
            } else if (isNumeric(result) && isNumeric(type)) {
                result = Double.class;
            } else {
                return null;
            }
        }

        return result;
    }

    /*
     * Plug type inferred from sampled document values (the Java
     * values the driver decodes: Integer, Long, Double,
     * Decimal128, String, Boolean, or null). Returns null when
     * the field must be omitted: no values, only nulls, an
     * unsupported value, or a mixture that has no single exact
     * Plug type.
     */
    public static Class<?> inferType(List<?> values) {

        if (values == null) {
            return null;
        }

        Class<?> result = null;

        for (Object value : values) {

            if (value == null) {
                continue;
            }

            Class<?> candidate = sampleType(value);

            if (candidate == null) {
                return null;
            }

            result = combine(result, candidate);

            if (result == null) {
                return null;
            }
        }

        return result;
    }

    /*
     * Decodes one stored value into the schema's Plug type.
     *
     * Explicit null stays null for every type; nullability is a
     * document/DDL concern, not a conversion failure. Every
     * other value must convert exactly, otherwise this throws a
     * PlugException naming the field, value and stored class:
     * a query never returns a rounded or truncated value.
     */
    public static Object toPlugValue(
            String field,
            Object value,
            Class<?> expectedType
    ) {

        if (value == null) {
            return null;
        }

        if (expectedType == Integer.class) {

            if (value instanceof Integer) {
                return value;
            }

            if (value instanceof Long
                    && fitsInteger((Long) value)) {

                return (int) (long) (Long) value;
            }

            throw cannotRepresent(field, value, expectedType);
        }

        if (expectedType == Double.class) {

            if (value instanceof Double) {
                return value;
            }

            if (value instanceof Integer) {
                return ((Integer) value).doubleValue();
            }

            if (value instanceof Long
                    && isExactDouble((Long) value)) {

                return (double) (long) (Long) value;
            }

            if (value instanceof Decimal128) {

                Double exact = exactDouble((Decimal128) value);

                if (exact != null) {
                    return exact;
                }
            }

            throw cannotRepresent(field, value, expectedType);
        }

        if (expectedType == String.class) {

            if (value instanceof String) {
                return value;
            }

            throw cannotRepresent(field, value, expectedType);
        }

        if (expectedType == Boolean.class) {

            if (value instanceof Boolean) {
                return value;
            }

            throw cannotRepresent(field, value, expectedType);
        }

        throw new PlugException(
                "Unsupported Plug type: " + expectedType
        );
    }

    private static Class<?> sampleType(Object value) {

        if (value instanceof Integer) {
            return Integer.class;
        }

        if (value instanceof Long) {

            long number = (Long) value;

            if (fitsInteger(number)) {
                return Integer.class;
            }

            return isExactDouble(number)
                    ? Double.class
                    : null;
        }

        if (value instanceof Double) {
            return Double.class;
        }

        if (value instanceof Decimal128) {

            return exactDouble((Decimal128) value) != null
                    ? Double.class
                    : null;
        }

        if (value instanceof String) {
            return String.class;
        }

        if (value instanceof Boolean) {
            return Boolean.class;
        }

        return null;
    }

    /*
     * Two sampled types combine only when the result is exact:
     * identical types collapse, and Integer/Double mixtures
     * become Double (every Integer is exact as a double).
     */
    private static Class<?> combine(
            Class<?> current,
            Class<?> next
    ) {

        if (current == null) {
            return next;
        }

        if (current == next) {
            return current;
        }

        if (isNumeric(current) && isNumeric(next)) {
            return Double.class;
        }

        return null;
    }

    private static boolean isNumeric(Class<?> type) {

        return type == Integer.class
                || type == Double.class;
    }

    private static boolean fitsInteger(long value) {

        return value >= Integer.MIN_VALUE
                && value <= Integer.MAX_VALUE;
    }

    private static boolean isExactDouble(long value) {

        return value >= -MAX_EXACT_DOUBLE_INTEGER
                && value <= MAX_EXACT_DOUBLE_INTEGER;
    }

    /*
     * Exact decimal -> double conversion: the double's own
     * BigDecimal expansion must compare numerically equal to the
     * stored decimal (scale does not matter). NaN, infinity,
     * overflow and binary rounding all yield null, so the field
     * is omitted instead of approximated.
     */
    private static Double exactDouble(Decimal128 value) {

        if (value.isNaN() || value.isInfinite()) {
            return null;
        }

        BigDecimal decimal = value.bigDecimalValue();

        double converted = decimal.doubleValue();

        if (!Double.isFinite(converted)) {
            return null;
        }

        if (decimal.compareTo(
                new BigDecimal(converted)
        ) != 0) {

            return null;
        }

        return converted;
    }

    private static PlugException cannotRepresent(
            String field,
            Object value,
            Class<?> expectedType
    ) {

        return new PlugException(
                "Field '" + field + "' value "
                        + value
                        + " (" + value.getClass().getName() + ")"
                        + " cannot be represented as "
                        + expectedType.getSimpleName() + "."
        );
    }
}
