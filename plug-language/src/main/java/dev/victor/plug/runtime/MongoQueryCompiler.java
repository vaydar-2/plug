package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.ConditionExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;
import dev.victor.plug.ast.Value;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/*
 * Translates an already validated Query into a MongoDB read
 * document: filter, projection, sort and limit.
 *
 * The class is deliberately pure and static, like
 * PostgresQueryCompiler: it never touches a client, collection
 * handle or server, so the translation contract is testable
 * without MongoDB. Field existence is the only schema fact the
 * compiler needs; typing has already been enforced by
 * QueryValidation.
 *
 * Semantics mirrored from the mock/PostgreSQL contract:
 * - Plug fields always exist and may be null, so EQ null and
 *   IS NULL require $exists:true; a missing field is absent,
 *   not null. NE and IS NOT NULL require a present, non-null
 *   value. NE <value> is built from two $ne clauses because a
 *   bare $ne would also match nulls and missing fields.
 * - IN drops null items (the mock never matches a null
 *   actual value); an empty or all-null IN matches nothing.
 *   BETWEEN with a null bound also matches nothing.
 * - LIKE keeps SQL-style % and _ wildcards, is whole-string
 *   and case-sensitive, and escapes every other regex
 *   character. CONTAINS is a literal, unanchored substring
 *   regex. Neither matches null or missing fields.
 * - Flat where/and/or chains fold strictly left-to-right; an
 *   existing expression tree is translated structurally and
 *   then flat conditions are folded onto it.
 */
public final class MongoQueryCompiler {

    private MongoQueryCompiler() {
    }

    /*
     * The pieces an adapter needs for a single-collection read.
     * sort is null when the query has no sort(); limit is null
     * when it has no limit().
     */
    public static class CompiledQuery {

        private final Document filter;
        private final Document projection;
        private final Document sort;
        private final Integer limit;

        CompiledQuery(
                Document filter,
                Document projection,
                Document sort,
                Integer limit
        ) {
            this.filter = filter;
            this.projection = projection;
            this.sort = sort;
            this.limit = limit;
        }

        public Document getFilter() {
            return filter;
        }

        public Document getProjection() {
            return projection;
        }

        public Document getSort() {
            return sort;
        }

        public Integer getLimit() {
            return limit;
        }
    }

    public static CompiledQuery compileRead(
            Query query,
            Map<String, Class<?>> schema
    ) {

        if (query == null) {
            throw new PlugException(
                    "Query is required."
            );
        }

        if (schema == null) {
            throw new PlugException(
                    "Schema is required."
            );
        }

        Document projection = projection(
                query.getFields(),
                schema
        );

        Document filter = filter(query, schema);

        /*
         * Mongo's limit(0) means "no limit", while Plug's
         * limit(0) means no rows. Keep the zero in the compiled
         * plan for fidelity and make the filter guarantee the
         * result even if an executor passes it to Mongo.
         */
        if (Integer.valueOf(0).equals(query.getLimit())) {
            filter = matchNothing(
                    query.getFields().get(0)
            );
        }

        return new CompiledQuery(
                filter,
                projection,
                sort(query.getSort(), schema),
                query.getLimit()
        );
    }

    /*
     * A read with no conditions has the empty filter (match all).
     * The seed comparison is not wrapped; every subsequent flat
     * condition wraps the accumulated side, which preserves the
     * left-to-right fold exactly as the mock evaluates it.
     */
    private static Document filter(
            Query query,
            Map<String, Class<?>> schema
    ) {

        ConditionExpression expression =
                query.getExpression();

        List<Condition> conditions =
                query.getConditions();

        if (expression == null && conditions.isEmpty()) {
            return new Document();
        }

        Document compiled;

        int startIndex;

        if (expression != null) {

            compiled = expression(expression, schema);
            startIndex = 0;

        } else {

            compiled = condition(conditions.get(0), schema);
            startIndex = 1;
        }

        for (int i = startIndex;
                i < conditions.size();
                i++) {

            Condition current = conditions.get(i);

            compiled = new Document(
                    current.getLogicalOperator()
                            == Condition.LogicalOperator.AND
                            ? "$and"
                            : "$or",
                    Arrays.asList(
                            compiled,
                            condition(current, schema)
                    )
            );
        }

        return compiled;
    }

    private static Document expression(
            ConditionExpression expression,
            Map<String, Class<?>> schema
    ) {

        if (expression instanceof ComparisonExpression) {

            ComparisonExpression comparison =
                    (ComparisonExpression) expression;

            return condition(
                    new Condition(
                            comparison.getField(),
                            comparison.getOperator(),
                            comparison.getValue(),
                            Condition.LogicalOperator.AND
                    ),
                    schema
            );
        }

        if (expression instanceof LogicalExpression) {

            LogicalExpression logical =
                    (LogicalExpression) expression;

            return new Document(
                    logical.getOperator()
                            == LogicalExpression.Operator.AND
                            ? "$and"
                            : "$or",
                    Arrays.asList(
                            expression(logical.getLeft(), schema),
                            expression(logical.getRight(), schema)
                    )
            );
        }

        if (expression instanceof NotExpression) {

            return new Document(
                    "$nor",
                    Collections.singletonList(
                            expression(
                                    ((NotExpression) expression)
                                            .getExpression(),
                                    schema
                            )
                    )
            );
        }

        throw new PlugException(
                "Unknown condition expression."
        );
    }

    private static Document condition(
            Condition condition,
            Map<String, Class<?>> schema
    ) {

        String field = condition.getField();

        requireField(field, schema);

        Object expected = condition.getValue() == null
                ? null
                : condition.getValue().getValue();

        String operator = condition.getOperator();

        if (operator == null) {
            throw new PlugException(
                    "Unsupported operator: null"
            );
        }

        switch (operator) {

            case "EQ":
                return expected == null
                        ? explicitNull(field)
                        : new Document(
                                field,
                                new Document("$eq", expected)
                        );

            case "NE":

                if (expected == null) {
                    return presentNonNull(field);
                }

                /*
                 * $ne:null excludes missing fields and explicit
                 * nulls; the second clause applies the value
                 * test. A bare $ne:<value> would match both.
                 */
                return new Document(
                        "$and",
                        Arrays.asList(
                                new Document(
                                        field,
                                        new Document("$ne", null)
                                ),
                                new Document(
                                        field,
                                        new Document("$ne", expected)
                                )
                        )
                );

            case "GT":
                return comparison(field, "$gt", expected);

            case "GTE":
                return comparison(field, "$gte", expected);

            case "LT":
                return comparison(field, "$lt", expected);

            case "LTE":
                return comparison(field, "$lte", expected);

            case "IN": {

                List<Value> items = listValue(
                        condition,
                        "IN requires a list of values."
                );

                List<Object> nonNull = new ArrayList<>();

                for (Value item : items) {

                    if (item.getValue() != null) {
                        nonNull.add(item.getValue());
                    }
                }

                if (nonNull.isEmpty()) {
                    return matchNothing(field);
                }

                return new Document(
                        field,
                        new Document("$in", nonNull)
                );
            }

            case "BETWEEN": {

                List<Value> range = listValue(
                        condition,
                        "BETWEEN requires a list of two values."
                );

                if (range.size() != 2) {
                    throw new PlugException(
                            "BETWEEN requires exactly two values."
                    );
                }

                Object low = range.get(0).getValue();
                Object high = range.get(1).getValue();

                if (low == null || high == null) {
                    return matchNothing(field);
                }

                return new Document(
                        field,
                        new Document("$gte", low)
                                .append("$lte", high)
                );
            }

            case "CONTAINS":

                if (!(expected instanceof String)) {
                    throw new PlugException(
                            "CONTAINS requires string values."
                    );
                }

                return new Document(
                        field,
                        new Document(
                                "$regex",
                                regexLiteral(
                                        (String) expected
                                )
                        )
                );

            case "LIKE":

                if (!(expected instanceof String)) {
                    throw new PlugException(
                            "LIKE requires string values."
                    );
                }

                return new Document(
                        field,
                        new Document(
                                "$regex",
                                likeRegex((String) expected)
                        )
                );

            case "IS NULL":
                return explicitNull(field);

            case "IS NOT NULL":
                return presentNonNull(field);

            default:
                throw new PlugException(
                        "Unsupported operator: " + operator
                );
        }
    }

    /*
     * Null is not a comparable value in Plug or SQL: the
     * PostgreSQL compiler rejects it at compile time, and the
     * mock raises on a null actual value. Keep the compiler's
     * established error message.
     */
    private static Document comparison(
            String field,
            String operator,
            Object expected
    ) {

        if (expected == null) {
            throw new PlugException(
                    "Numeric comparison requires numeric values."
            );
        }

        return new Document(
                field,
                new Document(operator, expected)
        );
    }

    private static Document explicitNull(String field) {

        return new Document(
                field,
                new Document("$exists", true)
                        .append("$eq", null)
        );
    }

    private static Document presentNonNull(String field) {

        return new Document(
                field,
                new Document("$exists", true)
                        .append("$ne", null)
        );
    }

    /*
     * An empty $in list is a valid MongoDB predicate that
     * matches no document, including missing fields. It also
     * encodes limit(0), since Mongo's own limit(0) is
     * unbounded.
     */
    private static Document matchNothing(String field) {

        return new Document(
                field,
                new Document(
                        "$in",
                        Collections.emptyList()
                )
        );
    }

    private static List<Value> listValue(
            Condition condition,
            String message
    ) {

        if (condition.getValue() == null
                || !(condition.getValue().getValue()
                instanceof List)) {

            throw new PlugException(message);
        }

        @SuppressWarnings("unchecked")
        List<Value> values = (List<Value>)
                condition.getValue().getValue();

        return values;
    }

    private static Document projection(
            List<String> fields,
            Map<String, Class<?>> schema
    ) {

        if (fields.isEmpty()) {
            throw new PlugException(
                    "get() expects at least one field."
            );
        }

        Document projection = new Document();

        for (String field : fields) {

            requireField(field, schema);
            projection.put(field, 1);
        }

        /*
         * _id is Mongo storage detail, not a Plug field; keeping
         * it out preserves the explicit projection contract.
         */
        projection.put("_id", 0);

        return projection;
    }

    private static Document sort(
            Sort sort,
            Map<String, Class<?>> schema
    ) {

        if (sort == null) {
            return null;
        }

        requireField(sort.getField(), schema);

        int direction;

        if ("ASC".equals(sort.getDirection())) {
            direction = 1;
        } else if ("DESC".equals(sort.getDirection())) {
            direction = -1;
        } else {
            throw new PlugException(
                    "sort() direction must be ASC or DESC."
            );
        }

        return new Document(
                sort.getField(),
                direction
        );
    }

    private static void requireField(
            String field,
            Map<String, Class<?>> schema
    ) {

        if (field == null || !schema.containsKey(field)) {

            throw new PlugException(
                    "Unknown field: " + field
            );
        }
    }

    /*
     * LIKE keeps SQL wildcard semantics (%, _) but anchors the
     * whole string. \A and \z are used instead of ^ and $ so a
     * trailing newline cannot make a shorter pattern match.
     */
    private static String likeRegex(String pattern) {

        StringBuilder regex = new StringBuilder("\\A");

        for (int i = 0; i < pattern.length(); ) {

            int codePoint = pattern.codePointAt(i);

            if (codePoint == '%') {
                regex.append(".*");
            } else if (codePoint == '_') {
                regex.append('.');
            } else {
                appendRegexLiteral(regex, codePoint);
            }

            i += Character.charCount(codePoint);
        }

        return regex.append("\\z").toString();
    }

    /*
     * CONTAINS is a plain substring: every regex metacharacter
     * in the user's text is escaped, so no input can become
     * pattern syntax.
     */
    private static String regexLiteral(String text) {

        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < text.length(); ) {

            int codePoint = text.codePointAt(i);

            appendRegexLiteral(regex, codePoint);

            i += Character.charCount(codePoint);
        }

        return regex.toString();
    }

    private static void appendRegexLiteral(
            StringBuilder regex,
            int codePoint
    ) {

        if (isRegexMeta(codePoint)) {
            regex.append('\\');
        }

        regex.appendCodePoint(codePoint);
    }

    private static boolean isRegexMeta(int codePoint) {

        switch (codePoint) {

            case '\\':
            case '^':
            case '$':
            case '.':
            case '|':
            case '?':
            case '*':
            case '+':
            case '(':
            case ')':
            case '[':
            case ']':
            case '{':
            case '}':
                return true;

            default:
                return false;
        }
    }
}
