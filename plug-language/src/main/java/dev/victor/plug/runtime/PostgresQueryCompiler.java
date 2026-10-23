package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.ConditionExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Translates an already validated ExecutionPlan into one
 * PostgreSQL statement plus its bind parameters.
 *
 * The class is deliberately pure: it never touches JDBC, a
 * connection, or the database. Schemas are handed in by the
 * adapter (from information_schema), validation has already
 * run through QueryValidation, and this class only decides
 * what the SQL text looks like. That keeps it testable
 * without a server and keeps every value bound through ?
 * placeholders — no user data is ever concatenated into the
 * statement text.
 *
 * Semantics mirrored from the mock adapter:
 * - flat where/and/or chains fold strictly left-to-right,
 *   so every fold step is parenthesized explicitly
 *   (a OR b AND c in Plug is (a OR b) AND c, never the
 *   SQL-precedence reading a OR (b AND c));
 * - EQ with null becomes IS NULL, NE with null becomes
 *   IS NOT NULL (SQL's "= NULL" never matches);
 * - CONTAINS is an escaped LIKE '%...%' (%, _ and \
 *   literals neutralized; SQL null fields simply never
 *   match, unlike the mock, which throws per row — a
 *   documented adapter difference);
 * - an empty aggregate input yields SQL NULL, matching the
 *   mock's SQL-style aggregate semantics.
 */
public class PostgresQueryCompiler {

    /*
     * One bound value plus the Java type Plug mapped the
     * column to, so the executing adapter can call
     * setNull(java.sql.Types.X) for null parameters.
     */
    public static class SqlParam {

        private final Object value;

        private final Class<?> type;

        SqlParam(
                Object value,
                Class<?> type
        ) {
            this.value = value;
            this.type = type;
        }

        public Object getValue() {
            return value;
        }

        public Class<?> getType() {
            return type;
        }
    }

    public static class CompiledSql {

        private final String sql;

        private final List<SqlParam> params;

        CompiledSql(
                String sql,
                List<SqlParam> params
        ) {
            this.sql = sql;
            this.params = params;
        }

        public String getSql() {
            return sql;
        }

        public List<SqlParam> getParams() {
            return params;
        }
    }

    /*
     * Plain single-collection read. Projection fields must
     * exist in the schema (the mock silently drops unknown
     * ones; a real backend refuses to guess — a documented
     * adapter difference).
     */
    public static CompiledSql compileRead(
            Query query,
            String table,
            Map<String, Class<?>> schema
    ) {

        validateProjectionFields(
                query.getFields(),
                schema,
                null
        );

        StringBuilder sql = new StringBuilder("SELECT ");

        appendProjection(sql, query.getFields());

        sql.append(" FROM ").append(quote(table));

        List<SqlParam> params = new ArrayList<>();

        appendWhereOrderLimit(
                sql,
                query,
                field -> new ColumnRef(
                        table,
                        field,
                        schema.get(field)
                ),
                params
        );

        return new CompiledSql(
                sql.toString(),
                params
        );
    }

    /*
     * Joined read: INNER JOINs left-to-right, conditions
     * filtering the joined rows, resolution of condition
     * and sort fields strict (base-first bare-name rule
     * with ambiguity errors), projection resolution
     * base-first without the ambiguity error (the mock's
     * merged-row rule: bare names pick the first
     * collection to define them).
     */
    public static CompiledSql compileJoinedRead(
            Query query,
            String baseTable,
            LinkedHashMap<String, Map<String, Class<?>>> schemas
    ) {

        StringBuilder sql = new StringBuilder("SELECT ");

        appendJoinedProjection(
                sql,
                query.getFields(),
                schemas,
                baseTable
        );

        sql.append(" FROM ").append(quote(baseTable));

        for (Join join : query.getJoins()) {

            sql.append(" INNER JOIN ")
                    .append(quote(join.getCollection()))
                    .append(" ON (")
                    .append(qualifiedRef(
                            join.getLeftField(),
                            schemas
                    ))
                    .append(" = ")
                    .append(qualifiedRef(
                            join.getRightField(),
                            schemas
                    ))
                    .append(")");
        }

        List<SqlParam> params = new ArrayList<>();

        appendWhereOrderLimit(
                sql,
                query,
                field -> columnRefResolved(
                        field,
                        schemas,
                        baseTable
                ),
                params
        );

        return new CompiledSql(
                sql.toString(),
                params
        );
    }

    /*
     * Aggregate read (single collection only — the runtime
     * rejects aggregates combined with joins).
     */
    public static CompiledSql compileAggregate(
            ExecutionPlan.Aggregate aggregate,
            String aggregateField,
            Query query,
            String table,
            Map<String, Class<?>> schema
    ) {

        StringBuilder sql = new StringBuilder("SELECT ");

        if (aggregate == ExecutionPlan.Aggregate.COUNT) {
            sql.append("COUNT(*)");
        } else {

            sql.append(aggregate.name())
                    .append('(')
                    .append(quote(aggregateField))
                    .append(')');
        }

        sql.append(" AS \"aggregateValue\" FROM ")
                .append(quote(table));

        List<SqlParam> params = new ArrayList<>();

        appendWhereOrderLimit(
                sql,
                query,
                field -> new ColumnRef(
                        table,
                        field,
                        schema.get(field)
                ),
                params
        );

        return new CompiledSql(
                sql.toString(),
                params
        );
    }

    public static CompiledSql compileInsert(
            String table,
            Map<String, Object> data,
            Map<String, Class<?>> schema
    ) {

        StringBuilder sql = new StringBuilder();

        sql.append("INSERT INTO ").append(quote(table))
                .append(" (");

        StringBuilder placeholders =
                new StringBuilder();

        List<SqlParam> params = new ArrayList<>();

        boolean first = true;

        for (Map.Entry<String, Object> entry :
                data.entrySet()) {

            if (!first) {

                sql.append(", ");
                placeholders.append(", ");
            }

            first = false;

            sql.append(quote(entry.getKey()));
            placeholders.append('?');

            params.add(new SqlParam(
                    entry.getValue(),
                    schema.get(entry.getKey())
            ));
        }

        sql.append(") VALUES (")
                .append(placeholders)
                .append(')');

        return new CompiledSql(
                sql.toString(),
                params
        );
    }

    public static CompiledSql compileUpdate(
            Query query,
            String table,
            Map<String, Object> data,
            Map<String, Class<?>> schema
    ) {

        StringBuilder sql = new StringBuilder();

        sql.append("UPDATE ").append(quote(table))
                .append(" SET ");

        List<SqlParam> params = new ArrayList<>();

        boolean first = true;

        for (Map.Entry<String, Object> entry :
                data.entrySet()) {

            if (!first) {
                sql.append(", ");
            }

            first = false;

            sql.append(quote(entry.getKey()))
                    .append(" = ?");

            params.add(new SqlParam(
                    entry.getValue(),
                    schema.get(entry.getKey())
            ));
        }

        // SET values bind first, then the WHERE parameters.
        appendWhereOrderLimit(
                sql,
                query,
                field -> new ColumnRef(
                        table,
                        field,
                        schema.get(field)
                ),
                params
        );

        return new CompiledSql(
                sql.toString(),
                params
        );
    }

    public static CompiledSql compileDelete(
            Query query,
            String table,
            Map<String, Class<?>> schema
    ) {

        StringBuilder sql = new StringBuilder();

        sql.append("DELETE FROM ").append(quote(table));

        List<SqlParam> params = new ArrayList<>();

        appendWhereOrderLimit(
                sql,
                query,
                field -> new ColumnRef(
                        table,
                        field,
                        schema.get(field)
                ),
                params
        );

        return new CompiledSql(
                sql.toString(),
                params
        );
    }

    private static void appendProjection(
            StringBuilder sql,
            List<String> fields
    ) {

        if (fields.isEmpty()) {
            throw new PlugException(
                    "get() expects at least one field."
            );
        }

        List<String> items = new ArrayList<>();

        for (String field : fields) {

            items.add(
                    quote(field)
                            + " AS "
                            + quote(field)
            );
        }

        sql.append(String.join(", ", items));
    }

    private static void appendJoinedProjection(
            StringBuilder sql,
            List<String> fields,
            Map<String, Map<String, Class<?>>> schemas,
            String baseCollection
    ) {

        if (fields.isEmpty()) {
            throw new PlugException(
                    "get() expects at least one field."
            );
        }

        List<String> items = new ArrayList<>();

        for (String field : fields) {

            ColumnRef ref = resolveProjection(
                    field,
                    schemas,
                    baseCollection
            );

            items.add(
                    quote(ref.getTable())
                            + '.'
                            + quote(ref.getColumn())
                            + " AS "
                            + quote(field)
            );
        }

        sql.append(String.join(", ", items));
    }

    /*
     * Shared statement tail: WHERE (both modes), then
     * ORDER BY and LIMIT for reads. Callers pass the
     * collector their statement already uses so SET values
     * stay ahead of WHERE parameters in compileUpdate.
     */
    private static void appendWhereOrderLimit(
            StringBuilder sql,
            Query query,
            ColumnResolver resolver,
            List<SqlParam> params
    ) {

        String conditionSql = conditionChainSql(
                query,
                resolver,
                params
        );

        if (conditionSql != null) {
            sql.append(" WHERE ").append(conditionSql);
        }

        Sort sort = query.getSort();

        if (sort != null) {

            ColumnRef sortRef =
                    resolver.resolve(sort.getField());

            sql.append(" ORDER BY ")
                    .append(quote(sortRef.getTable()))
                    .append('.')
                    .append(quote(sortRef.getColumn()))
                    .append(' ')
                    .append(sort.getDirection());
        }

        Integer limit = query.getLimit();

        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }
    }

    /*
     * The chain: expression tree first (or the first flat
     * condition as the seed), then every remaining flat
     * condition folded left-to-right with its own logical
     * operator. Every step is parenthesized, which is what
     * guarantees SQL cannot re-read the chain with its own
     * operator precedence.
     */
    private static String conditionChainSql(
            Query query,
            ColumnResolver resolver,
            List<SqlParam> params
    ) {

        ConditionExpression expression =
                query.getExpression();

        List<Condition> conditions =
                query.getConditions();

        if (expression == null
                && conditions.isEmpty()) {
            return null;
        }

        StringBuilder chain = new StringBuilder();

        if (expression != null) {
            chain.append(walkExpression(
                    expression,
                    resolver,
                    params
            ));
        } else {
            chain.append('(')
                    .append(comparisonSql(
                            conditions.get(0),
                            resolver,
                            params
                    ))
                    .append(')');
        }

        int startIndex =
                expression != null ? 0 : 1;

        for (int i = startIndex;
                i < conditions.size();
                i++) {

            Condition condition = conditions.get(i);

            /*
             * The accumulated left side is re-wrapped on
             * every fold: ((a) OR (b)) AND (c) — otherwise
             * SQL's operator precedence would silently
             * re-group the chain (a OR b AND c would be
             * read as a OR (b AND c)).
             */
            String connector =
                    condition.getLogicalOperator()
                            == Condition.LogicalOperator.AND
                            ? " AND ("
                            : " OR (";

            chain = new StringBuilder(
                    "("
                            + chain
                            + connector
                            + comparisonSql(
                            condition,
                            resolver,
                            params
                    )
                            + "))"
            );
        }

        return chain.toString();
    }

    private static String walkExpression(
            ConditionExpression expression,
            ColumnResolver resolver,
            List<SqlParam> params
    ) {

        if (expression instanceof ComparisonExpression) {

            ComparisonExpression comparison =
                    (ComparisonExpression) expression;

            return '(' + comparisonSql(
                    new Condition(
                            comparison.getField(),
                            comparison.getOperator(),
                            comparison.getValue(),
                            Condition.LogicalOperator.AND
                    ),
                    resolver,
                    params
            ) + ')';
        }

        if (expression instanceof LogicalExpression) {

            LogicalExpression logical =
                    (LogicalExpression) expression;

            String operator =
                    logical.getOperator()
                            == LogicalExpression.Operator.AND
                            ? " AND "
                            : " OR ";

            return '('
                    + walkExpression(
                    logical.getLeft(),
                    resolver,
                    params
            )
                    + operator
                    + walkExpression(
                    logical.getRight(),
                    resolver,
                    params
            )
                    + ')';
        }

        if (expression instanceof NotExpression) {

            return "NOT ("
                    + walkExpression(
                    ((NotExpression) expression)
                            .getExpression(),
                    resolver,
                    params
            )
                    + ')';
        }

        throw new PlugException(
                "Unknown condition expression."
        );
    }

    private static String comparisonSql(
            Condition condition,
            ColumnResolver resolver,
            List<SqlParam> params
    ) {

        ColumnRef ref =
                resolver.resolve(condition.getField());

        String column = quote(ref.getTable())
                + '.' + quote(ref.getColumn());

        String operator = condition.getOperator();

        Object expected =
                condition.getValue().getValue();

        switch (operator) {

            case "EQ":

                if (expected == null) {
                    return column + " IS NULL";
                }

                params.add(new SqlParam(
                        expected,
                        ref.getType()
                ));

                return column + " = ?";

            case "NE":

                if (expected == null) {
                    return column + " IS NOT NULL";
                }

                params.add(new SqlParam(
                        expected,
                        ref.getType()
                ));

                return column + " <> ?";

            case "GT":
            case "GTE":
            case "LT":
            case "LTE":

                if (expected == null) {
                    throw new PlugException(
                            "Numeric comparison requires "
                                    + "numeric values."
                    );
                }

                params.add(new SqlParam(
                        expected,
                        ref.getType()
                ));

                return column + ' '
                        + sqlComparisonOperator(operator)
                        + " ?";

            case "IN": {

                @SuppressWarnings("unchecked")
                List<dev.victor.plug.ast.Value> items =
                        (List<dev.victor.plug.ast.Value>)
                                condition.getValue()
                                        .getValue();

                StringBuilder list = new StringBuilder(
                        column + " IN ("
                );

                for (int i = 0;
                        i < items.size();
                        i++) {

                    if (i > 0) {
                        list.append(", ");
                    }

                    list.append('?');

                    params.add(new SqlParam(
                            items.get(i).getValue(),
                            ref.getType()
                    ));
                }

                list.append(')');

                return list.toString();
            }

            case "BETWEEN": {

                @SuppressWarnings("unchecked")
                List<dev.victor.plug.ast.Value> range =
                        (List<dev.victor.plug.ast.Value>)
                                condition.getValue()
                                        .getValue();

                params.add(new SqlParam(
                        range.get(0).getValue(),
                        ref.getType()
                ));

                params.add(new SqlParam(
                        range.get(1).getValue(),
                        ref.getType()
                ));

                return column + " BETWEEN ? AND ?";
            }

            case "CONTAINS": {

                if (!(expected instanceof String)) {
                    throw new PlugException(
                            "CONTAINS requires "
                                    + "string values."
                    );
                }

                params.add(new SqlParam(
                        likeContainsPattern(
                                (String) expected
                        ),
                        String.class
                ));

                return column + " LIKE ? ESCAPE '\\'";
            }

            case "LIKE":

                if (!(expected instanceof String)) {
                    throw new PlugException(
                            "LIKE requires string values."
                    );
                }

                params.add(new SqlParam(
                        expected,
                        String.class
                ));

                return column + " LIKE ?";

            case "IS NULL":
                return column + " IS NULL";

            case "IS NOT NULL":
                return column + " IS NOT NULL";

            default:
                throw new PlugException(
                        "Unsupported operator: "
                                + operator
                );
        }
    }

    private static String sqlComparisonOperator(
            String operator
    ) {

        switch (operator) {

            case "EQ":
                return "=";

            case "NE":
                return "<>";

            case "GT":
                return ">";

            case "GTE":
                return ">=";

            case "LT":
                return "<";

            case "LTE":
                return "<=";

            default:
                return operator;
        }
    }

    /*
     * CONTAINS evaluates to an escaped anchored-substring
     * LIKE pattern: literal %, _ and \ in the search text
     * are neutralized with the ESCAPE character.
     */
    private static String likeContainsPattern(
            String text
    ) {

        String escaped = text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        return "%" + escaped + "%";
    }

    /*
     * Projection resolver mirroring the mock's merged-row
     * rule: qualified names must exist; bare names pick the
     * base collection first, then the joins in chain order
     * (no ambiguity error — first definition wins).
     */
    private static ColumnRef resolveProjection(
            String field,
            Map<String, Map<String, Class<?>>> schemas,
            String baseCollection
    ) {

        if (field.contains(".")) {

            String collection =
                    QueryValidation.collectionOf(field);

            Map<String, Class<?>> schema =
                    schemas.get(collection);

            String column =
                    QueryValidation.bareName(field);

            if (schema == null
                    || !schema.containsKey(column)) {

                throw new PlugException(
                        "Unknown field: " + field
                );
            }

            return new ColumnRef(
                    collection,
                    column,
                    schema.get(column)
            );
        }

        Map<String, Class<?>> baseSchema =
                schemas.get(baseCollection);

        if (baseSchema.containsKey(field)) {

            return new ColumnRef(
                    baseCollection,
                    field,
                    baseSchema.get(field)
            );
        }

        for (Map.Entry<String, Map<String, Class<?>>> entry :
                schemas.entrySet()) {

            if (entry.getKey().equals(baseCollection)) {
                continue;
            }

            if (entry.getValue().containsKey(field)) {

                return new ColumnRef(
                        entry.getKey(),
                        field,
                        entry.getValue().get(field)
                );
            }
        }

        throw new PlugException(
                "Unknown field: " + field
        );
    }

    /*
     * Strict resolver for conditions, sort fields, and join
     * keys — same resolution rules and error messages the
     * mock enforces through QueryValidation.
     */
    private static ColumnRef columnRefResolved(
            String field,
            LinkedHashMap<String, Map<String, Class<?>>> schemas,
            String baseCollection
    ) {

        String resolved =
                QueryValidation.resolveField(
                        field,
                        schemas,
                        baseCollection
                );

        String collection =
                QueryValidation.collectionOf(resolved);

        String column =
                QueryValidation.bareName(resolved);

        Map<String, Class<?>> schema =
                schemas.get(collection);

        return new ColumnRef(
                collection,
                column,
                schema.get(column)
        );
    }

    private static String qualifiedRef(
            String qualifiedField,
            Map<String, Map<String, Class<?>>> schemas
    ) {

        String collection =
                QueryValidation.collectionOf(qualifiedField);

        String column =
                QueryValidation.bareName(qualifiedField);

        return quote(collection)
                + '.' + quote(column);
    }

    private static void validateProjectionFields(
            List<String> fields,
            Map<String, Class<?>> schema,
            String unused
    ) {

        if (fields.isEmpty()) {
            throw new PlugException(
                    "get() expects at least one field."
            );
        }

        for (String field : fields) {

            if (!schema.containsKey(field)) {
                throw new PlugException(
                        "Unknown field: " + field
                );
            }
        }
    }

    static String quote(String identifier) {
        return '"' + identifier + '"';
    }

    private interface ColumnResolver {

        ColumnRef resolve(String field);
    }

    static class ColumnRef {

        private final String table;

        private final String column;

        private final Class<?> type;

        ColumnRef(
                String table,
                String column,
                Class<?> type
        ) {

            this.table = table;
            this.column = column;
            this.type = type;
        }

        String getTable() {
            return table;
        }

        String getColumn() {
            return column;
        }

        Class<?> getType() {
            return type;
        }
    }
}
