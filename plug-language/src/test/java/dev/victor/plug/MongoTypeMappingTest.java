package dev.victor.plug;

import dev.victor.plug.runtime.MongoTypeMapping;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Type mapping contract: which BSON values and declared
 * bsonTypes produce a Plug type, and which are refused. Pure
 * unit tests — no client and no server.
 *
 * Boundary focus: int range, exact-double range (2^53), and
 * decimal values whose binary double does not round trip.
 */
public class MongoTypeMappingTest {

    private static List<Object> sample(Object... values) {

        List<Object> samples = new ArrayList<>();

        for (Object value : values) {
            samples.add(value);
        }

        return samples;
    }

    /* ---------- declared $jsonSchema types ---------- */

    @Test
    public void declaredScalarTypesMapExactly() {

        assertEquals(
                Integer.class,
                MongoTypeMapping.declaredType("int")
        );

        /*
         * Plug has no 64-bit integer type; "long" declares
         * Integer and oversized values fail closed at decode.
         */
        assertEquals(
                Integer.class,
                MongoTypeMapping.declaredType("long")
        );

        assertEquals(
                Double.class,
                MongoTypeMapping.declaredType("double")
        );

        assertEquals(
                Double.class,
                MongoTypeMapping.declaredType("decimal")
        );

        assertEquals(
                String.class,
                MongoTypeMapping.declaredType("string")
        );

        assertEquals(
                Boolean.class,
                MongoTypeMapping.declaredType("bool")
        );
    }

    @Test
    public void declaredTypeUnionsCollapseOnlyOneExactFamily() {

        assertEquals(
                Integer.class,
                MongoTypeMapping.declaredType(
                        List.of("int", "long")
                )
        );

        assertEquals(
                Double.class,
                MongoTypeMapping.declaredType(
                        List.of("int", "double")
                )
        );

        assertEquals(
                Double.class,
                MongoTypeMapping.declaredType(
                        List.of("double", "decimal")
                )
        );

        assertEquals(
                Integer.class,
                MongoTypeMapping.declaredType(
                        List.of("int")
                )
        );

        assertNull(
                MongoTypeMapping.declaredType(
                        List.of("int", "string")
                )
        );

        assertNull(
                MongoTypeMapping.declaredType(
                        List.of("long", "objectId")
                )
        );

        assertNull(
                MongoTypeMapping.declaredType(List.of())
        );
    }

    @Test
    public void unsupportedDeclaredTypesAreOmitted() {

        String[] unsupported = {
                "object",
                "array",
                "binData",
                "objectId",
                "date",
                "null",
                "regex",
                "timestamp",
                "minKey",
                "maxKey",
                "not-a-bson-type"
        };

        for (String bsonType : unsupported) {

            assertNull(
                    MongoTypeMapping.declaredType(bsonType),
                    bsonType
            );
        }
    }

    /* ---------- int32 / int64 inference ---------- */

    @Test
    public void int32SamplesMapToInteger() {

        assertEquals(
                Integer.class,
                MongoTypeMapping.inferType(
                        sample(
                                0,
                                1,
                                -1,
                                Integer.MAX_VALUE,
                                Integer.MIN_VALUE
                        )
                )
        );
    }

    @Test
    public void int64WithinIntRangeMapsToInteger() {

        assertEquals(
                Integer.class,
                MongoTypeMapping.inferType(
                        sample(
                                0L,
                                1L,
                                -1L,
                                (long) Integer.MAX_VALUE,
                                (long) Integer.MIN_VALUE
                        )
                )
        );
    }

    @Test
    public void int64BeyondIntRangeButExactMapsToDouble() {

        long twoToTheFiftyThree = 1L << 53;

        assertEquals(
                Double.class,
                MongoTypeMapping.inferType(
                        sample(
                                (long) Integer.MAX_VALUE + 1,
                                (long) Integer.MIN_VALUE - 1,
                                4_000_000_000L,
                                twoToTheFiftyThree,
                                -twoToTheFiftyThree
                        )
                )
        );
    }

    @Test
    public void int64BeyondExactDoubleRangeIsOmitted() {

        long twoToTheFiftyThree = 1L << 53;

        assertNull(
                MongoTypeMapping.inferType(
                        sample(twoToTheFiftyThree + 1)
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(-twoToTheFiftyThree - 1)
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(Long.MAX_VALUE)
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(Long.MIN_VALUE)
                )
        );
    }

    @Test
    public void mixedIntegerAndInt64WidensOnlyWhenExact() {

        assertEquals(
                Integer.class,
                MongoTypeMapping.inferType(
                        sample(1, 2L)
                )
        );

        assertEquals(
                Double.class,
                MongoTypeMapping.inferType(
                        sample(1, 4_000_000_000L)
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(1, (1L << 53) + 1)
                )
        );
    }

    /* ---------- Decimal128 ---------- */

    @Test
    public void exactDecimal128MapsToDouble() {

        assertEquals(
                Double.class,
                MongoTypeMapping.inferType(
                        sample(
                                Decimal128.parse("1.5"),
                                Decimal128.parse("0.5"),
                                Decimal128.parse("-2.75"),
                                Decimal128.parse("42"),
                                Decimal128.parse(
                                        "9007199254740992"
                                )
                        )
                )
        );
    }

    @Test
    public void inexactDecimal128IsOmitted() {

        assertNull(
                MongoTypeMapping.inferType(
                        sample(Decimal128.parse("0.1"))
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(Decimal128.parse("1.1"))
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(
                                Decimal128.parse(
                                        "3.14159265358979323846"
                                )
                        )
                )
        );
    }

    @Test
    public void specialDecimal128ValuesAreOmitted() {

        assertNull(
                MongoTypeMapping.inferType(
                        sample(Decimal128.NaN)
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(Decimal128.POSITIVE_INFINITY)
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(Decimal128.NEGATIVE_INFINITY)
                )
        );
    }

    /* ---------- other types and mixtures ---------- */

    @Test
    public void stringBooleanAndDoubleSamplesMapDirectly() {

        assertEquals(
                String.class,
                MongoTypeMapping.inferType(
                        sample("a", "b")
                )
        );

        assertEquals(
                Boolean.class,
                MongoTypeMapping.inferType(
                        sample(true, false)
                )
        );

        assertEquals(
                Double.class,
                MongoTypeMapping.inferType(
                        sample(
                                1.5,
                                -2.0,
                                Double.NaN,
                                Double.POSITIVE_INFINITY
                        )
                )
        );
    }

    @Test
    public void unsupportedSampleValuesAreOmitted() {

        assertNull(
                MongoTypeMapping.inferType(
                        sample(new ObjectId())
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(new Document("name", "x"))
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(new Date())
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(new byte[]{1, 2})
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(List.of(1, 2))
                )
        );
    }

    @Test
    public void emptyOrAllNullSamplesYieldNoType() {

        assertNull(
                MongoTypeMapping.inferType(sample())
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(null, null)
                )
        );
    }

    @Test
    public void mixedFamiliesAreOmitted() {

        assertNull(
                MongoTypeMapping.inferType(
                        sample(1, "one")
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(1, true)
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(1.5, "one")
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(1, new ObjectId())
                )
        );

        assertNull(
                MongoTypeMapping.inferType(
                        sample(
                                Decimal128.parse("1.5"),
                                "one"
                        )
                )
        );
    }

    /* ---------- decoding established schema types ---------- */

    @Test
    public void decodingIntegerAcceptsInt32AndInRangeInt64() {

        assertEquals(
                5,
                MongoTypeMapping.toPlugValue(
                        "age",
                        5,
                        Integer.class
                )
        );

        assertEquals(
                5,
                MongoTypeMapping.toPlugValue(
                        "age",
                        5L,
                        Integer.class
                )
        );

        assertEquals(
                Integer.MAX_VALUE,
                MongoTypeMapping.toPlugValue(
                        "age",
                        (long) Integer.MAX_VALUE,
                        Integer.class
                )
        );
    }

    @Test
    public void decodingIntegerRejectsOutOfRangeValues() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> MongoTypeMapping.toPlugValue(
                        "age",
                        2_147_483_648L,
                        Integer.class
                )
        );

        assertTrue(
                exception.getMessage().contains("Field 'age'")
        );

        assertTrue(
                exception.getMessage().contains(
                        "java.lang.Long"
                )
        );

        assertTrue(
                exception.getMessage().contains("Integer")
        );
    }

    @Test
    public void decodingDoubleAcceptsExactValuesOnly() {

        assertEquals(
                2.0,
                MongoTypeMapping.toPlugValue(
                        "amount",
                        2,
                        Double.class
                )
        );

        assertEquals(
                5.0,
                MongoTypeMapping.toPlugValue(
                        "amount",
                        5L,
                        Double.class
                )
        );

        assertEquals(
                9.007199254740992E15,
                MongoTypeMapping.toPlugValue(
                        "amount",
                        1L << 53,
                        Double.class
                )
        );

        assertEquals(
                1.5,
                MongoTypeMapping.toPlugValue(
                        "amount",
                        Decimal128.parse("1.5"),
                        Double.class
                )
        );

        assertThrows(
                PlugException.class,
                () -> MongoTypeMapping.toPlugValue(
                        "amount",
                        (1L << 53) + 1,
                        Double.class
                )
        );

        /*
         * 0.1 has no exact binary double expansion, so it can
         * never be read as Double without pretending it is the
         * rounded value.
         */
        assertThrows(
                PlugException.class,
                () -> MongoTypeMapping.toPlugValue(
                        "amount",
                        Decimal128.parse("0.1"),
                        Double.class
                )
        );
    }

    @Test
    public void decodingDoubleKeepsDoubleSpecialValues() {

        assertEquals(
                Double.NaN,
                MongoTypeMapping.toPlugValue(
                        "amount",
                        Double.NaN,
                        Double.class
                )
        );

        assertEquals(
                Double.POSITIVE_INFINITY,
                MongoTypeMapping.toPlugValue(
                        "amount",
                        Double.POSITIVE_INFINITY,
                        Double.class
                )
        );
    }

    @Test
    public void decodingStringAndBooleanIsStrict() {

        assertEquals(
                "Victor",
                MongoTypeMapping.toPlugValue(
                        "name",
                        "Victor",
                        String.class
                )
        );

        assertEquals(
                true,
                MongoTypeMapping.toPlugValue(
                        "active",
                        true,
                        Boolean.class
                )
        );

        assertThrows(
                PlugException.class,
                () -> MongoTypeMapping.toPlugValue(
                        "active",
                        "true",
                        Boolean.class
                )
        );

        assertThrows(
                PlugException.class,
                () -> MongoTypeMapping.toPlugValue(
                        "age",
                        "5",
                        Integer.class
                )
        );
    }

    @Test
    public void explicitNullDecodesAsNullForEveryType() {

        assertNull(
                MongoTypeMapping.toPlugValue(
                        "age",
                        null,
                        Integer.class
                )
        );

        assertNull(
                MongoTypeMapping.toPlugValue(
                        "amount",
                        null,
                        Double.class
                )
        );

        assertNull(
                MongoTypeMapping.toPlugValue(
                        "name",
                        null,
                        String.class
                )
        );

        assertNull(
                MongoTypeMapping.toPlugValue(
                        "active",
                        null,
                        Boolean.class
                )
        );
    }

    @Test
    public void unsupportedTargetTypeIsRejected() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> MongoTypeMapping.toPlugValue(
                        "id",
                        5L,
                        Long.class
                )
        );

        assertTrue(
                exception.getMessage().contains(
                        "Unsupported Plug type"
                )
        );
    }
}
