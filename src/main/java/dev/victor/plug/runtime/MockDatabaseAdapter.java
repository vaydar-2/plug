package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.Value;
import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.ConditionExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockDatabaseAdapter implements DatabaseAdapter {

    @Override
    public void connect(ConnectionConfig config) {

        System.out.println(
                "Mock database connected:"
        );

        System.out.println(
                "Host: " + config.getHost()
        );

        System.out.println(
                "Port: " + config.getPort()
        );

        System.out.println(
                "Database: " + config.getDatabase()
        );

        System.out.println(
                "Username: " + config.getUsername()
        );
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {

        String collection = plan.getCollection();

        List<Map<String, Object>> records =
                MockData.DATA.get(collection);

        if (records == null) {
            throw new PlugException(
                    "Collection not found: " + collection
            );
        }

        Map<String, Class<?>> schema =
                MockData.SCHEMAS.get(collection);

        if (schema == null) {
            throw new PlugException(
                    "Schema not found for collection: "
                            + collection
            );
        }

        for (Condition condition :
                plan.getQuery().getConditions()) {

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
        if (plan.getQuery().getExpression() != null) {

            validateExpression(
                    plan.getQuery().getExpression(),
                    schema
            );
        }

        List<Map<String, Object>> filteredRecords =
                new ArrayList<>();

        for (Map<String, Object> record : records) {

            boolean matches =
                    evaluateQuery(
                            plan.getQuery(),
                            record
                    );

            if (matches) {
                filteredRecords.add(record);
            }
        }

        Sort sort = plan.getQuery().getSort();

        if (sort != null) {

            String field = sort.getField();

            if (!schema.containsKey(field)) {
                throw new PlugException(
                        "Unknown sort field: " + field
                );
            }

            Class<?> fieldType = schema.get(field);

            if (!Comparable.class.isAssignableFrom(fieldType)) {
                throw new PlugException(
                        "Field '" + field +
                                "' cannot be sorted."
                );
            }

            Comparator<Map<String, Object>> comparator =
                    (first, second) -> {

                        Comparable firstValue =
                                (Comparable) first.get(field);

                        Comparable secondValue =
                                (Comparable) second.get(field);

                        return firstValue.compareTo(
                                secondValue
                        );
                    };

            if (sort.getDirection().equals("DESC")) {
                comparator = comparator.reversed();
            }

            filteredRecords.sort(comparator);
        }

        Integer limit =
                plan.getQuery().getLimit();

        if (limit != null
                && filteredRecords.size() > limit) {

            filteredRecords =
                    new ArrayList<>(
                            filteredRecords.subList(
                                    0,
                                    limit
                            )
                    );
        }

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (Map<String, Object> record :
                filteredRecords) {

            Map<String, Object> result =
                    new HashMap<>();

            for (String field :
                    plan.getQuery().getFields()) {

                if (record.containsKey(field)) {

                    result.put(
                            field,
                            record.get(field)
                    );
                }
            }

            rows.add(result);
        }

        return new QueryResult(
                rows.size(),
                rows
        );
    }

    private void validateCondition(
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

    private void validateExpression(
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

    private boolean compare(
            Condition condition,
            Map<String, Object> record
    ) {

        Object actualValue =
                record.get(condition.getField());

        Object expectedValue =
                condition.getValue().getValue();

        switch (condition.getOperator()) {

            case "EQ":

                if (expectedValue == null) {
                    return actualValue == null;
                }

                return actualValue != null
                        && actualValue.equals(expectedValue);


            case "NE":

                if (expectedValue == null) {
                    return actualValue != null;
                }

                return actualValue != null
                        && !actualValue.equals(expectedValue);

            case "GT":
                return compareNumbers(
                        actualValue,
                        expectedValue
                ) > 0;

            case "GTE":
                return compareNumbers(
                        actualValue,
                        expectedValue
                ) >= 0;

            case "LT":
                return compareNumbers(
                        actualValue,
                        expectedValue
                ) < 0;

            case "LTE":
                return compareNumbers(
                        actualValue,
                        expectedValue
                ) <= 0;

            case "IN":

                @SuppressWarnings("unchecked")
                List<Value> values =
                        (List<Value>) expectedValue;

                for (Value value : values) {

                    if (actualValue != null
                            && actualValue.equals(
                            value.getValue()
                    )) {

                        return true;
                    }
                }

                return false;

            case "CONTAINS":

                if (!(actualValue instanceof String)
                        || !(expectedValue
                        instanceof String)) {

                    throw new PlugException(
                            "CONTAINS requires string values."
                    );
                }

                return ((String) actualValue)
                        .contains(
                                (String) expectedValue
                        );

            default:
                throw new PlugException(
                        "Unsupported operator: "
                                + condition.getOperator()
                );
        }
    }

    private int compareNumbers(
            Object actualValue,
            Object expectedValue
    ) {

        if (!(actualValue instanceof Number)
                || !(expectedValue instanceof Number)) {

            throw new PlugException(
                    "Numeric comparison requires "
                            + "numeric values."
            );
        }

        double actual =
                ((Number) actualValue).doubleValue();

        double expected =
                ((Number) expectedValue).doubleValue();

        return Double.compare(
                actual,
                expected
        );
    }

    /*
     * A query may carry both representations at once:
     * an expression tree (from where() with grouping/logic)
     * and a flat conditions list (from chained and()/or()
     * calls). Evaluate the tree first, then fold the flat
     * conditions in left-to-right using each condition's
     * own logical operator, so a mixed where/and/or chain
     * no longer silently drops the flat conditions.
     */
    private boolean evaluateQuery(
            Query query,
            Map<String, Object> record
    ) {

        boolean result;

        if (query.getExpression() != null) {

            result = evaluateExpression(
                    query.getExpression(),
                    record
            );

        } else if (query.getConditions().isEmpty()) {

            return true;

        } else {

            result = compare(
                    query.getConditions().get(0),
                    record
            );

            return foldConditions(
                    result,
                    query.getConditions(),
                    1,
                    record
            );
        }

        return foldConditions(
                result,
                query.getConditions(),
                0,
                record
        );
    }

    /*
     * Folds conditions from startIndex. Each condition's own
     * logical operator is the one connecting it to whatever
     * came before it, giving flat chains strict left-to-right
     * semantics ((a AND b) OR c, etc.).
     */
    private boolean foldConditions(
            boolean seed,
            List<Condition> conditions,
            int startIndex,
            Map<String, Object> record
    ) {

        boolean result = seed;

        for (int i = startIndex;
                i < conditions.size(); i++) {

            Condition condition =
                    conditions.get(i);

            boolean current =
                    compare(condition, record);

            if (condition.getLogicalOperator()
                    == Condition.LogicalOperator.AND) {

                result = result && current;

            } else {

                result = result || current;
            }
        }

        return result;
    }

    private boolean evaluateExpression(
            ConditionExpression expression,
            Map<String, Object> record
    ) {

        if (expression instanceof ComparisonExpression) {

            ComparisonExpression comparison =
                    (ComparisonExpression) expression;

            Condition condition =
                    new Condition(
                            comparison.getField(),
                            comparison.getOperator(),
                            comparison.getValue(),
                            Condition.LogicalOperator.AND
                    );

            return compare(condition, record);
        }

        if (expression instanceof LogicalExpression) {

            LogicalExpression logical =
                    (LogicalExpression) expression;

            boolean left =
                    evaluateExpression(
                            logical.getLeft(),
                            record
                    );

            boolean right =
                    evaluateExpression(
                            logical.getRight(),
                            record
                    );

            if (logical.getOperator()
                    == LogicalExpression.Operator.AND) {

                return left && right;
            }

            return left || right;
        }

        if (expression instanceof NotExpression) {

            NotExpression not =
                    (NotExpression) expression;

            return !evaluateExpression(
                    not.getExpression(),
                    record
            );
        }

        throw new PlugException(
                "Unknown condition expression."
        );
    }

    @Override
    public void close() {

        System.out.println(
                "Mock database connection closed."
        );
    }
}