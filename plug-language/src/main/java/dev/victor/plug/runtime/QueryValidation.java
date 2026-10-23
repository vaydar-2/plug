package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.ConditionExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;
import dev.victor.plug.ast.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.victor.plug.runtime.ExecutionPlan.Aggregate;

/*
 * Backend-neutral query validation shared by every adapter.
 *
 * Field existence, type checking, join-key structure, and
 * schema-based field resolution are rules about the query
 * model, not about any storage engine. Extracting them here
 * means a real backend (the PostgreSQL adapter) enforces
 * exactly the same contract as the mock, from one source of
 * truth, instead of copying this logic or diverging from it.
 *
 * Logic and error messages are lifted verbatim from
 * MockDatabaseAdapter, which now delegates here; the 114
 * existing tests keep defining the contract.
 */
public class QueryValidation {

    private QueryValidation() {
    }

    /*
     * Aggregate intent against a schema — COUNT needs no
     * field; SUM/AVG must be numeric; MIN/MAX work on any
     * comparable field. Shared so every backend reports
     * the same messages.
     */
    public static void validateAggregate(
            Aggregate aggregate,
            String field,
            Map<String, Class<?>> schema
    ) {

        if (aggregate == Aggregate.COUNT) {
            return;
        }

        if (field == null
                || !schema.containsKey(field)) {

            throw new PlugException(
                    "Unknown field: " + field
            );
        }

        Class<?> fieldType =
                schema.get(field);

        if (aggregate == Aggregate.SUM
                || aggregate == Aggregate.AVG) {

            if (!Number.class.isAssignableFrom(fieldType)) {

                throw new PlugException(
                        aggregate
                                + " requires a numeric field. Field '"
                                + field
                                + "' is not numeric."
                );
            }
        }

        if (!Comparable.class.isAssignableFrom(fieldType)) {

            throw new PlugException(
                    "Field '" + field
                            + "' cannot be aggregated."
            );
        }
    }

    /*
     * Joined reads: field resolution inside a multi-
     * collection schema set.
     *
     * "orders.total" is exact: the collection must be part
     * of the join and must define the field.
     *
     * A bare "total" resolves base-first: the base
     * collection wins immediately, otherwise the joins are
     * searched in chain order and a name found in more
     * than one joined collection is ambiguous.
     */
    public static String resolveField(
            String field,
            Map<String, Map<String, Class<?>>> schemas,
            String baseCollection
    ) {

        if (field.contains(".")) {

            String collection = collectionOf(field);

            Map<String, Class<?>> schema =
                    schemas.get(collection);

            if (schema == null) {

                throw new PlugException(
                        "Unknown collection in field: "
                                + field
                );
            }

            if (!schema.containsKey(bareName(field))) {

                throw new PlugException(
                        "Unknown field: " + field
                );
            }

            return field;
        }

        Map<String, Class<?>> baseSchema =
                schemas.get(baseCollection);

        if (baseSchema.containsKey(field)) {
            return baseCollection + "." + field;
        }

        List<String> matches = new ArrayList<>();

        for (Map.Entry<String, Map<String, Class<?>>> entry :
                schemas.entrySet()) {

            if (entry.getKey().equals(baseCollection)) {
                continue;
            }

            if (entry.getValue().containsKey(field)) {
                matches.add(
                        entry.getKey() + "." + field
                );
            }
        }

        if (matches.isEmpty()) {

            throw new PlugException(
                    "Unknown field: " + field
            );
        }

        if (matches.size() > 1) {

            throw new PlugException(
                    "Ambiguous field: " + field + " ("
                            + String.join(
                            ", ",
                            matches
                    ) + ")"
            );
        }

        return matches.get(0);
    }

    public static void validateResolvedCondition(
            Condition condition,
            Map<String, Map<String, Class<?>>> schemas,
            String baseCollection
    ) {

        String resolved = resolveField(
                condition.getField(),
                schemas,
                baseCollection
        );

        /*
         * validateCondition matches bare schema keys, so the
         * resolved field is stripped back to its bare name
         * and checked against the schema of the collection
         * it resolved to.
         */
        validateCondition(
                new Condition(
                        bareName(resolved),
                        condition.getOperator(),
                        condition.getValue(),
                        condition.getLogicalOperator()
                ),
                schemas.get(collectionOf(resolved))
        );
    }

    public static void validateResolvedExpression(
            ConditionExpression expression,
            Map<String, Map<String, Class<?>>> schemas,
            String baseCollection
    ) {

        if (expression instanceof ComparisonExpression) {

            ComparisonExpression comparison =
                    (ComparisonExpression) expression;

            validateResolvedCondition(
                    new Condition(
                            comparison.getField(),
                            comparison.getOperator(),
                            comparison.getValue(),
                            Condition.LogicalOperator.AND
                    ),
                    schemas,
                    baseCollection
            );

            return;
        }

        if (expression instanceof LogicalExpression) {

            LogicalExpression logical =
                    (LogicalExpression) expression;

            validateResolvedExpression(
                    logical.getLeft(),
                    schemas,
                    baseCollection
            );

            validateResolvedExpression(
                    logical.getRight(),
                    schemas,
                    baseCollection
            );

            return;
        }

        if (expression instanceof NotExpression) {

            validateResolvedExpression(
                    ((NotExpression) expression)
                            .getExpression(),
                    schemas,
                    baseCollection
            );

            return;
        }

        throw new PlugException(
                "Unknown condition expression."
        );
    }

    /*
     * Structure of a key pair: both sides collection-
     * qualified, fields existing in their collections,
     * exactly one side referencing the newly joined
     * collection, and matching key types.
     */
    public static void validateJoin(
            Join join,
            Map<String, Map<String, Class<?>>> schemas
    ) {

        validateJoinSide(
                join.getLeftField(),
                schemas
        );

        validateJoinSide(
                join.getRightField(),
                schemas
        );

        String leftCollection =
                collectionOf(join.getLeftField());

        String rightCollection =
                collectionOf(join.getRightField());

        boolean leftIsJoined =
                leftCollection.equals(join.getCollection());

        boolean rightIsJoined =
                rightCollection.equals(join.getCollection());

        if (leftIsJoined == rightIsJoined) {

            throw new PlugException(
                    "join() key must reference the joined "
                            + "collection on one side and an "
                            + "existing collection on the other: "
                            + "(" + join.getLeftField()
                            + ", EQ, "
                            + join.getRightField() + ")"
            );
        }

        Class<?> leftType = schemas
                .get(leftCollection)
                .get(bareName(join.getLeftField()));

        Class<?> rightType = schemas
                .get(rightCollection)
                .get(bareName(join.getRightField()));

        if (!leftType.equals(rightType)) {

            throw new PlugException(
                    "Type mismatch in join key: "
                            + join.getLeftField() + " ("
                            + leftType.getSimpleName() + ") vs "
                            + join.getRightField() + " ("
                            + rightType.getSimpleName() + ")"
            );
        }
    }

    private static void validateJoinSide(
            String field,
            Map<String, Map<String, Class<?>>> schemas
    ) {

        String collection = collectionOf(field);

        Map<String, Class<?>> schema =
                schemas.get(collection);

        if (schema == null) {
            throw new PlugException(
                    "Unknown collection in join key: "
                            + collection
            );
        }

        if (!schema.containsKey(bareName(field))) {

            throw new PlugException(
                    "Unknown field: " + field
            );
        }
    }

    /*
     * Reads and writes share the same where() validation:
     * flat conditions plus every comparison in the grouped
     * expression tree.
     */
    public static void validateWhere(
            Query query,
            Map<String, Class<?>> schema
    ) {

        for (Condition condition :
                query.getConditions()) {

            validateCondition(
                    condition,
                    schema
            );
        }

        /*
         * A grouped/logical where() stores its conditions
         * inside the expression tree, not in the flat
         * conditions list. Validate every comparison seen
         * while walking that tree.
         */
        if (query.getExpression() != null) {

            validateExpression(
                    query.getExpression(),
                    schema
            );
        }
    }

    /*
     * Insert/update payloads follow the same schema rules
     * as conditions: every field must exist and match by
     * type; null is accepted for any field.
     */
    public static void validateWriteFields(
            Map<String, Object> data,
            Map<String, Class<?>> schema
    ) {

        for (Map.Entry<String, Object> entry :
                data.entrySet()) {

            String field = entry.getKey();

            Object value = entry.getValue();

            if (!schema.containsKey(field)) {
                throw new PlugException(
                        "Unknown field: " + field
                );
            }

            if (value == null) {
                continue;
            }

            Class<?> expectedType =
                    schema.get(field);

            if (!expectedType.isInstance(value)) {

                throw new PlugException(
                        "Type mismatch for field '"
                                + field +
                                "'. Expected " +
                                expectedType
                                        .getSimpleName() +
                                " but got " +
                                value
                                        .getClass()
                                        .getSimpleName()
                );
            }
        }
    }

    public static void validateExpression(
            ConditionExpression expression,
            Map<String, Class<?>> schema
    ) {

        if (expression instanceof ComparisonExpression) {

            ComparisonExpression comparison =
                    (ComparisonExpression) expression;

            validateCondition(
                    new Condition(
                            comparison.getField(),
                            comparison.getOperator(),
                            comparison.getValue(),
                            Condition.LogicalOperator.AND
                    ),
                    schema
            );

            return;
        }

        if (expression instanceof LogicalExpression) {

            LogicalExpression logical =
                    (LogicalExpression) expression;

            validateExpression(
                    logical.getLeft(),
                    schema
            );

            validateExpression(
                    logical.getRight(),
                    schema
            );

            return;
        }

        if (expression instanceof NotExpression) {

            validateExpression(
                    ((NotExpression) expression)
                            .getExpression(),
                    schema
            );

            return;
        }

        throw new PlugException(
                "Unknown condition expression."
        );
    }

    public static void validateCondition(
            Condition condition,
            Map<String, Class<?>> schema
    ) {

        String field = condition.getField();

        if (!schema.containsKey(field)) {
            throw new PlugException(
                    "Unknown field: " + field
            );
        }

        Class<?> expectedType =
                schema.get(field);

        Value value =
                condition.getValue();

        if (condition.getOperator().equals("BETWEEN")) {

            if (value.getType()
                    != Value.Type.LIST) {

                throw new PlugException(
                        "BETWEEN requires a list of two values."
                );
            }

            @SuppressWarnings("unchecked")
            List<Value> range =
                    (List<Value>) value.getValue();

            if (range.size() != 2) {

                throw new PlugException(
                        "BETWEEN requires exactly two values."
                );
            }

            for (Value bound : range) {

                Object boundValue =
                        bound.getValue();

                if (boundValue != null
                        && !expectedType.isInstance(
                        boundValue
                )) {

                    throw new PlugException(
                            "Type mismatch for field '"
                                    + field +
                                    "'. Expected " +
                                    expectedType
                                            .getSimpleName() +
                                    " but got " +
                                    boundValue
                                            .getClass()
                                            .getSimpleName()
                    );
                }
            }

            return;
        }

        if (condition.getOperator().equals("IN")) {

            if (value.getType()
                    != Value.Type.LIST) {

                throw new PlugException(
                        "IN requires a list of values."
                );
            }

            @SuppressWarnings("unchecked")
            List<Value> values =
                    (List<Value>) value.getValue();

            for (Value item : values) {

                Object itemValue =
                        item.getValue();

                if (itemValue != null
                        && !expectedType.isInstance(
                        itemValue
                )) {

                    throw new PlugException(
                            "Type mismatch for field '"
                                    + field +
                                    "'. Expected " +
                                    expectedType
                                            .getSimpleName() +
                                    " but got " +
                                    itemValue
                                            .getClass()
                                            .getSimpleName()
                    );
                }
            }

            return;
        }

        Object expectedValue =
                value.getValue();

        if (expectedValue != null
                && !expectedType.isInstance(
                expectedValue
        )) {

            throw new PlugException(
                    "Type mismatch for field '"
                            + field +
                            "'. Expected " +
                            expectedType
                                    .getSimpleName() +
                            " but got " +
                            expectedValue
                                    .getClass()
                                    .getSimpleName()
            );
        }
    }

    public static String collectionOf(String qualified) {

        return qualified.substring(
                0,
                qualified.indexOf('.')
        );
    }

    public static String bareName(String qualified) {

        return qualified.substring(
                qualified.indexOf('.') + 1
        );
    }
}
