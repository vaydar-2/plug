package dev.victor.plug;

import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.ConditionExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;
import dev.victor.plug.ast.Value;
import dev.victor.plug.runtime.MongoQueryCompiler;
import dev.victor.plug.runtime.Query;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoQueryCompiler unit tests.
 *
 * Most assertions run the compiled filter through a small
 * evaluator with MongoDB's documented matching rules
 * (including the difference between a missing field and an
 * explicit null). That tests the resulting semantics, not just
 * the BSON shape. Shape assertions are kept for the cases where
 * the exact existence guard is the contract.
 */
public class MongoQueryCompilerTest {

    private static List<String> list(String... fields) {

        List<String> result = new ArrayList<>();

        for (String field : fields) {
            result.add(field);
        }

        return result;
    }

    private static Value string(String value) {
        return new Value(Value.Type.STRING, value);
    }

    private static Value number(int value) {
        return new Value(Value.Type.NUMBER, value);
    }

    private static Value nullValue() {
        return new Value(Value.Type.NULL, null);
    }

    private static Value valueList(Value... items) {

        List<Value> values = new ArrayList<>();

        for (Value item : items) {
            values.add(item);
        }

        return new Value(Value.Type.LIST, values);
    }

    private static Query read(String... fields) {
        return new Query(list(fields));
    }

    private static Map<String, Class<?>> schema() {

        Map<String, Class<?>> schema =
                new LinkedHashMap<>();

        schema.put("id", Integer.class);
        schema.put("name", String.class);
        schema.put("email", String.class);
        schema.put("age", Integer.class);
        schema.put("balance", Integer.class);
        schema.put("active", Boolean.class);
        schema.put("country", String.class);
        schema.put("role", String.class);

        return schema;
    }

    private static MongoQueryCompiler.CompiledQuery compile(
            Query query
    ) {
        return MongoQueryCompiler.compileRead(
                query,
                schema()
        );
    }

    private static Document person() {

        return new Document("id", 1)
                .append("name", "Alex")
                .append("age", 17)
                .append("balance", 100)
                .append("active", true)
                .append("country", "Ghana")
                .append("role", "student");
    }

    private static boolean matches(
            Query query,
            Document document
    ) {
        return matches(
                compile(query).getFilter(),
                document
        );
    }

    private static boolean matches(
            Document filter,
            Document document
    ) {

        for (Map.Entry<String, Object> entry :
                filter.entrySet()) {

            String key = entry.getKey();
            Object specification = entry.getValue();

            if ("$and".equals(key)) {

                for (Document child : documents(specification)) {

                    if (!matches(child, document)) {
                        return false;
                    }
                }

            } else if ("$or".equals(key)) {

                boolean any = false;

                for (Document child : documents(specification)) {

                    if (matches(child, document)) {
                        any = true;
                        break;
                    }
                }

                if (!any) {
                    return false;
                }

            } else if ("$nor".equals(key)) {

                for (Document child : documents(specification)) {

                    if (matches(child, document)) {
                        return false;
                    }
                }

            } else if (!fieldMatches(
                    key,
                    specification,
                    document
            )) {

                return false;
            }
        }

        return true;
    }

    private static List<Document> documents(Object value) {

        List<Document> documents = new ArrayList<>();

        for (Object item : (List<?>) value) {
            documents.add((Document) item);
        }

        return documents;
    }

    private static boolean fieldMatches(
            String field,
            Object specification,
            Document document
    ) {

        boolean exists = document.containsKey(field);
        Object actual = document.get(field);

        if (!(specification instanceof Document)) {
            return exists && equal(actual, specification);
        }

        for (Map.Entry<String, Object> entry :
                ((Document) specification).entrySet()) {

            String operator = entry.getKey();
            Object expected = entry.getValue();

            switch (operator) {

                case "$exists":

                    if (Boolean.TRUE.equals(expected)) {

                        if (!exists) {
                            return false;
                        }
                    } else if (exists) {
                        return false;
                    }

                    break;

                case "$eq":

                    if (expected == null) {

                        /*
                         * MongoDB's bare $eq:null also matches a
                         * missing field; the compiler's $exists
                         * guard is what narrows it to explicit null.
                         */
                        if (exists && actual != null) {
                            return false;
                        }
                    } else if (!exists
                            || !equal(actual, expected)) {

                        return false;
                    }

                    break;

                case "$ne":

                    if (expected == null) {

                        if (!exists || actual == null) {
                            return false;
                        }
                    } else if (exists
                            && equal(actual, expected)) {

                        return false;
                    }

                    break;

                case "$gt":
                case "$gte":
                case "$lt":
                case "$lte":

                    if (!exists
                            || actual == null
                            || expected == null) {

                        return false;
                    }

                    int compared = compare(actual, expected);

                    if ("$gt".equals(operator) && compared <= 0) {
                        return false;
                    }

                    if ("$gte".equals(operator) && compared < 0) {
                        return false;
                    }

                    if ("$lt".equals(operator) && compared >= 0) {
                        return false;
                    }

                    if ("$lte".equals(operator) && compared > 0) {
                        return false;
                    }

                    break;

                case "$in": {

                    boolean found = false;

                    for (Object item : (List<?>) expected) {

                        if ((!exists && item == null)
                                || (exists
                                && equal(actual, item))) {

                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        return false;
                    }

                    break;
                }

                case "$regex":

                    if (!(actual instanceof String)
                            || !Pattern.compile(
                                    (String) expected
                            ).matcher(
                                    (String) actual
                            ).find()) {

                        return false;
                    }

                    break;

                default:
                    throw new AssertionError(
                            "Unsupported test operator: "
                                    + operator
                    );
            }
        }

        return true;
    }

    private static boolean equal(Object left, Object right) {

        if (left instanceof Number
                && right instanceof Number) {

            return Double.compare(
                    ((Number) left).doubleValue(),
                    ((Number) right).doubleValue()
            ) == 0;
        }

        return left == null
                ? right == null
                : left.equals(right);
    }

    private static int compare(Object left, Object right) {

        if (left instanceof Number
                && right instanceof Number) {

            return Double.compare(
                    ((Number) left).doubleValue(),
                    ((Number) right).doubleValue()
            );
        }

        @SuppressWarnings("unchecked")
        Comparable<Object> comparable =
                (Comparable<Object>) left;

        return comparable.compareTo(right);
    }

    private static Document fieldOperators(
            MongoQueryCompiler.CompiledQuery compiled,
            String field
    ) {
        return (Document) compiled.getFilter().get(field);
    }

    @Test
    public void eqNullMatchesOnlyExplicitNull() {

        Query query = read("name").where(
                "email",
                "EQ",
                nullValue()
        );

        MongoQueryCompiler.CompiledQuery compiled =
                compile(query);

        Document operators = fieldOperators(
                compiled,
                "email"
        );

        assertEquals(Boolean.TRUE, operators.get("$exists"));
        assertTrue(operators.containsKey("$eq"));
        assertNull(operators.get("$eq"));

        assertTrue(matches(
                compiled.getFilter(),
                person().append("email", null)
        ));

        assertFalse(matches(
                compiled.getFilter(),
                person()
        ));

        assertFalse(matches(
                compiled.getFilter(),
                person().append("email", "alex@example.com")
        ));
    }

    @Test
    public void isNullAlsoMatchesOnlyExplicitNull() {

        Query query = read("name").where(
                "email",
                "IS NULL",
                nullValue()
        );

        assertEquals(
                new Document(
                        "email",
                        new Document("$exists", true)
                                .append("$eq", null)
                ),
                compile(query).getFilter()
        );

        assertTrue(matches(
                query,
                person().append("email", null)
        ));

        assertFalse(matches(query, person()));

        assertFalse(matches(
                query,
                person().append("email", "alex@example.com")
        ));
    }

    @Test
    public void neNullMatchesOnlyPresentNonNullValues() {

        Query query = read("name").where(
                "email",
                "NE",
                nullValue()
        );

        assertEquals(
                new Document(
                        "email",
                        new Document("$exists", true)
                                .append("$ne", null)
                ),
                compile(query).getFilter()
        );

        assertTrue(matches(
                query,
                person().append("email", "alex@example.com")
        ));

        assertFalse(matches(
                query,
                person().append("email", null)
        ));

        assertFalse(matches(query, person()));
    }

    @Test
    public void isNotNullMatchesOnlyPresentNonNullValues() {

        Query query = read("name").where(
                "email",
                "IS NOT NULL",
                nullValue()
        );

        assertTrue(matches(
                query,
                person().append("email", "alex@example.com")
        ));

        assertFalse(matches(
                query,
                person().append("email", null)
        ));

        assertFalse(matches(query, person()));
    }

    @Test
    public void neValueExcludesNullAndMissingFields() {

        Query query = read("name").where(
                "country",
                "NE",
                string("Ghana")
        );

        MongoQueryCompiler.CompiledQuery compiled =
                compile(query);

        assertTrue(compiled.getFilter().containsKey("$and"));

        List<?> clauses = (List<?>)
                compiled.getFilter().get("$and");

        assertEquals(2, clauses.size());

        assertTrue(matches(
                compiled.getFilter(),
                person().append("country", "Kenya")
        ));

        assertFalse(matches(
                compiled.getFilter(),
                person().append("country", "Ghana")
        ));

        assertFalse(matches(
                compiled.getFilter(),
                person().append("country", null)
        ));

        assertFalse(matches(
                compiled.getFilter(),
                new Document("id", 9)
                        .append("name", "Absent")
        ));
    }

    @Test
    public void eqValueExcludesNullAndMissingFields() {

        Query query = read("name").where(
                "country",
                "EQ",
                string("Ghana")
        );

        assertTrue(matches(query, person()));

        assertFalse(matches(
                query,
                person().append("country", "Kenya")
        ));

        assertFalse(matches(
                query,
                person().append("country", null)
        ));

        assertFalse(matches(
                query,
                new Document("id", 9)
                        .append("name", "Absent")
        ));
    }

    @Test
    public void allComparisonOperatorsMatchPresentValuesOnly() {

        assertComparison("GT", 20, 21, true);
        assertComparison("GT", 20, 20, false);
        assertComparison("GT", 20, 19, false);

        assertComparison("GTE", 20, 20, true);
        assertComparison("GTE", 20, 19, false);

        assertComparison("LT", 20, 19, true);
        assertComparison("LT", 20, 20, false);

        assertComparison("LTE", 20, 20, true);
        assertComparison("LTE", 20, 21, false);
    }

    private static void assertComparison(
            String operator,
            int bound,
            int actual,
            boolean expected
    ) {

        Query query = read("name").where(
                "age",
                operator,
                number(bound)
        );

        assertEquals(
                expected,
                matches(
                        query,
                        person().append("age", actual)
                ),
                operator + " " + actual
        );

        assertFalse(matches(
                query,
                person().append("age", null)
        ));

        assertFalse(matches(
                query,
                new Document("id", 9)
                        .append("name", "Absent")
        ));
    }

    @Test
    public void emptyInMatchesNothing() {

        Query query = read("name").where(
                "country",
                "IN",
                valueList()
        );

        MongoQueryCompiler.CompiledQuery compiled =
                compile(query);

        assertEquals(
                new Document(
                        "country",
                        new Document(
                                "$in",
                                new ArrayList<>()
                        )
                ),
                compiled.getFilter()
        );

        assertFalse(matches(compiled.getFilter(), person()));

        assertFalse(matches(
                compiled.getFilter(),
                person().append("country", null)
        ));

        assertFalse(matches(
                compiled.getFilter(),
                new Document("id", 9)
                        .append("name", "Absent")
        ));
    }

    @Test
    public void inStripsNullItemsAndStillIgnoresNullFields() {

        Query query = read("name").where(
                "country",
                "IN",
                valueList(
                        nullValue(),
                        string("Kenya"),
                        string("Ghana")
                )
        );

        MongoQueryCompiler.CompiledQuery compiled =
                compile(query);

        List<?> values = (List<?>)
                fieldOperators(compiled, "country")
                        .get("$in");

        assertEquals(2, values.size());
        assertFalse(values.contains(null));

        assertTrue(matches(
                compiled.getFilter(),
                person().append("country", "Kenya")
        ));

        assertTrue(matches(
                compiled.getFilter(),
                person()
        ));

        assertFalse(matches(
                compiled.getFilter(),
                person().append("country", null)
        ));

        assertFalse(matches(
                compiled.getFilter(),
                new Document("id", 9)
                        .append("name", "Absent")
        ));
    }

    @Test
    public void inWithOnlyNullsMatchesNothing() {

        Query query = read("name").where(
                "country",
                "IN",
                valueList(nullValue())
        );

        assertFalse(matches(
                query,
                person().append("country", null)
        ));

        assertFalse(matches(query, person()));

        assertFalse(matches(
                query,
                new Document("id", 9)
                        .append("name", "Absent")
        ));

        assertTrue(
                ((List<?>) fieldOperators(
                        compile(query),
                        "country"
                ).get("$in")).isEmpty()
        );
    }

    @Test
    public void betweenIsInclusiveAndNullBoundMatchesNothing() {

        Query between = read("name").where(
                "age",
                "BETWEEN",
                valueList(number(18), number(30))
        );

        assertTrue(matches(
                between,
                person().append("age", 18)
        ));

        assertTrue(matches(
                between,
                person().append("age", 30)
        ));

        assertFalse(matches(
                between,
                person().append("age", 17)
        ));

        assertFalse(matches(
                between,
                person().append("age", 31)
        ));

        assertFalse(matches(
                between,
                person().append("age", null)
        ));

        Query nullLow = read("name").where(
                "age",
                "BETWEEN",
                valueList(nullValue(), number(30))
        );

        Query nullHigh = read("name").where(
                "age",
                "BETWEEN",
                valueList(number(18), nullValue())
        );

        assertFalse(matches(
                nullLow,
                person().append("age", 20)
        ));

        assertFalse(matches(
                nullHigh,
                person().append("age", 20)
        ));

        assertTrue(
                ((List<?>) fieldOperators(
                        compile(nullLow),
                        "age"
                ).get("$in")).isEmpty()
        );
    }

    @Test
    public void containsIsLiteralCaseSensitiveSubstring() {

        Document special = person().append(
                "name",
                "50%_off (v2) [x] \\ end"
        );

        assertTrue(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("(v2) [x] \\")
                ),
                special
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("(v3) [x] \\")
                ),
                special
        ));

        assertTrue(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("50%_off")
                ),
                special
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("50Xoff")
                ),
                special
        ));

        Document literalDot = person().append("name", "A.B");
        Document anyCharacter = person().append("name", "AxB");

        assertTrue(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("A.B")
                ),
                literalDot
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("A.B")
                ),
                anyCharacter
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("alex")
                ),
                person().append("name", "Alex")
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("Alex")
                ),
                person().append("name", null)
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("Alex")
                ),
                new Document("id", 9)
        ));
    }

    @Test
    public void containsHandlesUnicode() {

        Document unicode = person().append(
                "name",
                "Zoë 日本"
        );

        assertTrue(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("ë 日")
                ),
                unicode
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("ë 本")
                ),
                unicode
        ));
    }

    @Test
    public void likeUsesSqlWildcardsAndWholeStringAnchoring() {

        Document victor = person().append("name", "Victor");
        Document junior = person().append("name", "Victor Jr");
        Document lower = person().append("name", "victor");

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("V%")
                ),
                victor
        ));

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("V%")
                ),
                junior
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("V%")
                ),
                lower
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("Victor")
                ),
                junior
        ));

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("_ictor")
                ),
                victor
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("_ictor")
                ),
                junior
        ));
    }

    @Test
    public void likeEscapesRegexMetacharacters() {

        Document literalDot = person().append("name", "A.B");
        Document anyCharacter = person().append("name", "AxB");

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("A.B")
                ),
                literalDot
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("A.B")
                ),
                anyCharacter
        ));

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("A_B")
                ),
                literalDot
        ));

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("A_B")
                ),
                anyCharacter
        ));

        Document regexText = person().append(
                "name",
                "a+b(c)[d]{e}^$|?\\"
        );

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("a+b(c)[d]{e}^$|?\\")
                ),
                regexText
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("a+b(c)[d]{e}^$|?X")
                ),
                regexText
        ));
    }

    @Test
    public void likeHandlesUnicodeAndTrailingNewlineExactly() {

        Document unicode = person().append("name", "Zoë");

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("Zo_")
                ),
                unicode
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("zoë")
                ),
                unicode
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("line")
                ),
                person().append("name", "line\n")
        ));
    }

    @Test
    public void likeAndContainsRemainSemanticallyDistinct() {

        Document wildcardText = person().append(
                "name",
                "50Xoff"
        );

        assertTrue(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("50%_off")
                ),
                wildcardText
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("50%_off")
                ),
                wildcardText
        ));

        assertTrue(matches(
                read("name").where(
                        "name",
                        "CONTAINS",
                        string("Xof")
                ),
                wildcardText
        ));

        assertFalse(matches(
                read("name").where(
                        "name",
                        "LIKE",
                        string("Xof")
                ),
                wildcardText
        ));
    }

    @Test
    public void nestedLogicalExpressionsPreserveTheirTree() {

        ConditionExpression expression =
                new LogicalExpression(
                        new LogicalExpression(
                                new ComparisonExpression(
                                        "country",
                                        "EQ",
                                        string("Ghana")
                                ),
                                new NotExpression(
                                        new ComparisonExpression(
                                                "email",
                                                "IS NULL",
                                                nullValue()
                                        )
                                ),
                                LogicalExpression.Operator.AND
                        ),
                        new ComparisonExpression(
                                "age",
                                "GT",
                                number(40)
                        ),
                        LogicalExpression.Operator.OR
                );

        MongoQueryCompiler.CompiledQuery compiled =
                compile(read("name").expression(expression));

        assertTrue(compiled.getFilter().containsKey("$or"));

        assertTrue(matches(
                compiled.getFilter(),
                person().append("email", "a@example.com")
        ));

        assertTrue(matches(
                compiled.getFilter(),
                person().append("email", null)
                        .append("age", 50)
        ));

        assertTrue(matches(
                compiled.getFilter(),
                person()
        ));

        assertFalse(matches(
                compiled.getFilter(),
                person().append("country", "Kenya")
                        .append("email", "a@example.com")
        ));
    }

    @Test
    public void flatChainFoldsStrictlyLeftToRight() {

        Query query = read("name")
                .where(
                        "role",
                        "EQ",
                        string("developer")
                )
                .or(
                        "country",
                        "EQ",
                        string("Nigeria")
                )
                .and(
                        "age",
                        "GT",
                        number(30)
                );

        MongoQueryCompiler.CompiledQuery compiled =
                compile(query);

        assertTrue(compiled.getFilter().containsKey("$and"));

        /*
         * (developer OR Nigeria) AND age>30. Conventional SQL
         * precedence would read developer OR (Nigeria AND
         * age>30), which would match this document.
         */
        assertFalse(matches(
                compiled.getFilter(),
                person().append("role", "developer")
                        .append("country", "Ghana")
                        .append("age", 20)
        ));

        assertTrue(matches(
                compiled.getFilter(),
                person().append("role", "developer")
                        .append("country", "Ghana")
                        .append("age", 40)
        ));

        assertTrue(matches(
                compiled.getFilter(),
                person().append("role", "student")
                        .append("country", "Nigeria")
                        .append("age", 40)
        ));

        assertFalse(matches(
                compiled.getFilter(),
                person().append("role", "student")
                        .append("country", "Nigeria")
                        .append("age", 20)
        ));
    }

    @Test
    public void expressionTreeIsFoldedWithLaterFlatConditions() {

        ConditionExpression expression =
                new LogicalExpression(
                        new ComparisonExpression(
                                "country",
                                "EQ",
                                string("Ghana")
                        ),
                        new ComparisonExpression(
                                "age",
                                "GT",
                                number(18)
                        ),
                        LogicalExpression.Operator.AND
                );

        Query query = read("name")
                .expression(expression)
                .or(
                        "role",
                        "EQ",
                        string("student")
                );

        assertTrue(matches(
                query,
                person().append("age", 17)
        ));

        assertTrue(matches(
                query,
                person().append("country", "Kenya")
                        .append("age", 40)
                        .append("role", "student")
        ));

        assertFalse(matches(
                query,
                person().append("country", "Kenya")
                        .append("age", 40)
                        .append("role", "manager")
        ));
    }

    @Test
    public void unsupportedOperatorsUseEstablishedError() {

        PlugException nin = assertThrows(
                PlugException.class,
                () -> compile(read("name").where(
                        "age",
                        "NIN",
                        valueList(number(1))
                ))
        );

        assertEquals(
                "Unsupported operator: NIN",
                nin.getMessage()
        );

        PlugException unknown = assertThrows(
                PlugException.class,
                () -> compile(read("name").where(
                        "age",
                        "FOO",
                        number(1)
                ))
        );

        assertEquals(
                "Unsupported operator: FOO",
                unknown.getMessage()
        );
    }

    @Test
    public void invalidOperatorValuesUseEstablishedErrors() {

        assertEquals(
                "Numeric comparison requires numeric values.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").where(
                                "age",
                                "GT",
                                nullValue()
                        ))
                ).getMessage()
        );

        assertEquals(
                "LIKE requires string values.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").where(
                                "name",
                                "LIKE",
                                number(1)
                        ))
                ).getMessage()
        );

        assertEquals(
                "CONTAINS requires string values.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").where(
                                "name",
                                "CONTAINS",
                                number(1)
                        ))
                ).getMessage()
        );

        assertEquals(
                "IN requires a list of values.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").where(
                                "country",
                                "IN",
                                string("Ghana")
                        ))
                ).getMessage()
        );

        assertEquals(
                "BETWEEN requires a list of two values.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").where(
                                "age",
                                "BETWEEN",
                                number(1)
                        ))
                ).getMessage()
        );

        assertEquals(
                "BETWEEN requires exactly two values.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").where(
                                "age",
                                "BETWEEN",
                                valueList(number(1))
                        ))
                ).getMessage()
        );
    }

    @Test
    public void projectionIncludesFieldsAndSuppressesId() {

        MongoQueryCompiler.CompiledQuery compiled =
                compile(read("name", "balance"));

        assertEquals(
                new Document("name", 1)
                        .append("balance", 1)
                        .append("_id", 0),
                compiled.getProjection()
        );

        assertEquals(
                "Unknown field: missing",
                assertThrows(
                        PlugException.class,
                        () -> compile(read(
                                "name",
                                "missing"
                        ))
                ).getMessage()
        );

        assertEquals(
                "get() expects at least one field.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read())
                ).getMessage()
        );
    }

    @Test
    public void sortMapsAscendingAndDescendingDirections() {

        MongoQueryCompiler.CompiledQuery ascending =
                compile(read("name").sort("age", "ASC"));

        assertEquals(
                new Document("age", 1),
                ascending.getSort()
        );

        MongoQueryCompiler.CompiledQuery descending =
                compile(read("name").sort("age", "DESC"));

        assertEquals(
                new Document("age", -1),
                descending.getSort()
        );

        assertEquals(
                "sort() direction must be ASC or DESC.",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").sort(
                                "age",
                                "UP"
                        ))
                ).getMessage()
        );

        assertEquals(
                "Unknown field: missing",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").sort(
                                "missing",
                                "ASC"
                        ))
                ).getMessage()
        );
    }

    @Test
    public void limitIsCarriedAndAbsentLimitIsNull() {

        assertEquals(
                Integer.valueOf(5),
                compile(read("name").limit(5)).getLimit()
        );

        MongoQueryCompiler.CompiledQuery zero =
                compile(read("name").limit(0));

        assertEquals(
                Integer.valueOf(0),
                zero.getLimit()
        );

        assertFalse(matches(zero.getFilter(), person()));

        assertNull(compile(read("name")).getLimit());
    }

    @Test
    public void readWithoutConditionsUsesEmptyFilter() {

        assertEquals(
                new Document(),
                compile(read("name")).getFilter()
        );
    }

    @Test
    public void unknownConditionFieldIsRejected() {

        assertEquals(
                "Unknown field: missing",
                assertThrows(
                        PlugException.class,
                        () -> compile(read("name").where(
                                "missing",
                                "EQ",
                                number(1)
                        ))
                ).getMessage()
        );
    }

    @Test
    public void queryAndSchemaAreRequired() {

        assertEquals(
                "Query is required.",
                assertThrows(
                        PlugException.class,
                        () -> MongoQueryCompiler.compileRead(
                                null,
                                schema()
                        )
                ).getMessage()
        );

        assertEquals(
                "Schema is required.",
                assertThrows(
                        PlugException.class,
                        () -> MongoQueryCompiler.compileRead(
                                read("name"),
                                null
                        )
                ).getMessage()
        );
    }
}
