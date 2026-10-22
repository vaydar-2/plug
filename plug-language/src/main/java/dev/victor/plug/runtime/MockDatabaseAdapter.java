package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.Value;
import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.ConditionExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

        switch (plan.getOperation()) {

            case INSERT:
                return insert(plan);

            case UPDATE:
                return update(plan);

            case DELETE:
                return delete(plan);

            case READ:
            default:
                return read(plan);
        }
    }

    private QueryResult read(ExecutionPlan plan) {

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

        validateWhere(
                plan.getQuery(),
                schema
        );

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

        /*
         * Aggregate reads fold the filtered records into one
         * value. sort/limit do not apply — the runtime rejects
         * that combination before it reaches an adapter.
         */
        if (plan.getAggregate() != ExecutionPlan.Aggregate.NONE) {

            Object value = computeAggregate(
                    plan.getAggregate(),
                    plan.getAggregateField(),
                    schema,
                    filteredRecords
            );

            return new QueryResult(
                    filteredRecords.size(),
                    Collections.emptyList(),
                    value
            );
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

    /*
     * Reads and writes share the same where() validation:
     * flat conditions plus every comparison in the grouped
     * expression tree.
     */
    private void validateWhere(
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

    private QueryResult insert(ExecutionPlan plan) {

        List<Map<String, Object>> records =
                requireCollection(plan);

        Map<String, Class<?>> schema =
                requireSchema(plan);

        validateWriteFields(
                plan.getData(),
                schema
        );

        Map<String, Object> record =
                new HashMap<>(plan.getData());

        Object explicitId =
                record.get("id");

        if (explicitId != null) {

            for (Map<String, Object> existing : records) {

                if (explicitId.equals(existing.get("id"))) {

                    throw new PlugException(
                            "Duplicate id: " + explicitId
                    );
                }
            }

        } else {

            /*
             * An insert without an explicit id auto-assigns
             * the next id above the current maximum.
             */
            int maxId = 0;

            for (Map<String, Object> existing : records) {

                Object existingId =
                        existing.get("id");

                if (existingId instanceof Integer
                        && (Integer) existingId > maxId) {

                    maxId = (Integer) existingId;
                }
            }

            record.put("id", maxId + 1);
        }

        records.add(record);

        return new QueryResult(
                1,
                Collections.emptyList()
        );
    }

    private QueryResult update(ExecutionPlan plan) {

        List<Map<String, Object>> records =
                requireCollection(plan);

        Map<String, Class<?>> schema =
                requireSchema(plan);

        validateWhere(
                plan.getQuery(),
                schema
        );

        validateWriteFields(
                plan.getData(),
                schema
        );

        int updated = 0;

        for (int i = 0; i < records.size(); i++) {

            Map<String, Object> record =
                    records.get(i);

            if (evaluateQuery(
                    plan.getQuery(),
                    record
            )) {

                /*
                 * Seeded records are shared across calls, so
                 * an update replaces the entry with a copy
                 * instead of mutating it in place.
                 */
                Map<String, Object> updatedRecord =
                        new HashMap<>(record);

                updatedRecord.putAll(plan.getData());

                records.set(i, updatedRecord);

                updated++;
            }
        }

        return new QueryResult(
                updated,
                Collections.emptyList()
        );
    }

    private QueryResult delete(ExecutionPlan plan) {

        List<Map<String, Object>> records =
                requireCollection(plan);

        Map<String, Class<?>> schema =
                requireSchema(plan);

        validateWhere(
                plan.getQuery(),
                schema
        );

        int before = records.size();

        records.removeIf(record ->
                evaluateQuery(
                        plan.getQuery(),
                        record
                ));

        return new QueryResult(
                before - records.size(),
                Collections.emptyList()
        );
    }

    /*
     * Insert/update payloads follow the same schema rules
     * as conditions: every field must exist and match by
     * type; null is accepted for any field.
     */
    private void validateWriteFields(
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

    /*
     * COUNT needs no field. SUM/AVG must be numeric; MIN/MAX
     * work on any comparable field (strings lexicographic).
     * Null values are skipped; an empty input yields null
     * for everything except COUNT.
     */
    private Object computeAggregate(
            ExecutionPlan.Aggregate aggregate,
            String field,
            Map<String, Class<?>> schema,
            List<Map<String, Object>> records
    ) {

        if (aggregate == ExecutionPlan.Aggregate.COUNT) {
            return records.size();
        }

        if (!schema.containsKey(field)) {

            throw new PlugException(
                    "Unknown field: " + field
            );
        }

        Class<?> fieldType =
                schema.get(field);

        if (aggregate == ExecutionPlan.Aggregate.SUM
                || aggregate == ExecutionPlan.Aggregate.AVG) {

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

        boolean numeric =
                aggregate == ExecutionPlan.Aggregate.SUM
                        || aggregate == ExecutionPlan.Aggregate.AVG;

        double numericSum = 0;

        long numericCount = 0;

        Comparable best = null;

        for (Map<String, Object> record : records) {

            Object value = record.get(field);

            if (value == null) {
                continue;
            }

            if (numeric) {

                numericSum +=
                        ((Number) value).doubleValue();

                numericCount++;

            } else {

                Comparable comparable =
                        (Comparable) value;

                if (best == null) {

                    best = comparable;

                } else if (aggregate == ExecutionPlan.Aggregate.MIN) {

                    if (comparable.compareTo(best) < 0) {
                        best = comparable;
                    }

                } else {

                    if (comparable.compareTo(best) > 0) {
                        best = comparable;
                    }
                }
            }
        }

        switch (aggregate) {

            case SUM:
                /*
                 * SQL semantics: the sum of an empty set is
                 * null, and Integer fields stay Integer for
                 * non-empty input.
                 */
                if (numericCount == 0) {
                    return null;
                }

                if (fieldType == Integer.class) {
                    return (int) numericSum;
                }

                return numericSum;

            case AVG:
                if (numericCount == 0) {
                    return null;
                }
                return numericSum / numericCount;

            case MIN:
            case MAX:
                return best;

            default:
                return null;
        }
    }

    private List<Map<String, Object>> requireCollection(
            ExecutionPlan plan
    ) {

        List<Map<String, Object>> records =
                MockData.DATA.get(
                        plan.getCollection()
                );

        if (records == null) {

            throw new PlugException(
                    "Collection not found: "
                            + plan.getCollection()
            );
        }

        return records;
    }

    private Map<String, Class<?>> requireSchema(
            ExecutionPlan plan
    ) {

        Map<String, Class<?>> schema =
                MockData.SCHEMAS.get(
                        plan.getCollection()
                );

        if (schema == null) {

            throw new PlugException(
                    "Schema not found for collection: "
                            + plan.getCollection()
            );
        }

        return schema;
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

            case "BETWEEN": {

                @SuppressWarnings("unchecked")
                List<Value> range =
                        (List<Value>) expectedValue;

                if (range.size() != 2
                        || actualValue == null
                        || !(actualValue instanceof Comparable)) {

                    return false;
                }

                Object low =
                        range.get(0).getValue();

                Object high =
                        range.get(1).getValue();

                Comparable comparable =
                        (Comparable) actualValue;

                /*
                 * Inclusive on both ends, and comparable types
                 * line up because validation checked both
                 * bounds against the schema.
                 */
                return comparable.compareTo(
                        (Comparable) low
                ) >= 0
                        && comparable.compareTo(
                        (Comparable) high
                ) <= 0;
            }

            case "LIKE":

                /*
                 * Null fields never match a pattern
                 * (SQL-style), unlike CONTAINS which throws
                 * on null.
                 */
                if (actualValue == null) {
                    return false;
                }

                if (!(actualValue instanceof String)
                        || !(expectedValue
                        instanceof String)) {

                    throw new PlugException(
                            "LIKE requires string values."
                    );
                }

                return Pattern.compile(
                        likePatternToRegex(
                                (String) expectedValue
                        )
                ).matcher(
                        (String) actualValue
                ).matches();

            case "IS NULL":
                return actualValue == null;

            case "IS NOT NULL":
                return actualValue != null;

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
     * LIKE uses SQL conventions: % matches any run of
     * characters, _ matches exactly one, the whole string
     * must match, and matching is case-sensitive.
     */
    private String likePatternToRegex(String pattern) {

        StringBuilder regex = new StringBuilder();

        for (char c : pattern.toCharArray()) {

            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(
                        Pattern.quote(
                                String.valueOf(c)
                        )
                );
            }
        }

        return regex.toString();
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