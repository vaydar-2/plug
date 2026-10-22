package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.Value;
import dev.victor.plug.ast.ConditionExpression;

import java.util.ArrayList;
import java.util.List;

public class Query {

    private final List<String> fields;
    private final List<Condition> conditions;
    private final List<Join> joins;

    private Sort sort;
    private Integer limit;

    private ConditionExpression expression;

    public Query(List<String> fields) {

        this.fields = fields;
        this.conditions = new ArrayList<>();
        this.joins = new ArrayList<>();
    }

    public Query where(
            String field,
            String operator,
            Value value
    ) {

        conditions.add(
                new Condition(
                        field,
                        operator,
                        value,
                        Condition.LogicalOperator.AND
                )
        );

        return this;
    }

    public Query and(
            String field,
            String operator,
            Value value
    ) {

        conditions.add(
                new Condition(
                        field,
                        operator,
                        value,
                        Condition.LogicalOperator.AND
                )
        );

        return this;
    }

    public Query or(
            String field,
            String operator,
            Value value
    ) {

        conditions.add(
                new Condition(
                        field,
                        operator,
                        value,
                        Condition.LogicalOperator.OR
                )
        );

        return this;
    }

    /*
     * Joins attach to reads only and are validated against
     * the backend schemas by the adapter. Fields anywhere
     * in the query may reference joined collections through
     * collection-qualified ("orders.total") names.
     */
    public Query join(Join join) {

        joins.add(join);

        return this;
    }

    public List<Join> getJoins() {
        return joins;
    }

    public Query sort(
            String field,
            String direction
    ) {

        this.sort = new Sort(
                field,
                direction
        );

        return this;
    }

    public Query limit(int limit) {

        if (limit < 0) {
            throw new PlugException(
                    "limit() cannot be negative."
            );
        }

        this.limit = limit;

        return this;
    }

    public List<String> getFields() {
        return fields;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public Sort getSort() {
        return sort;
    }

    public Integer getLimit() {
        return limit;
    }

    public Query expression(ConditionExpression expression) {
        this.expression = expression;
        return this;
    }

    public ConditionExpression getExpression() {
        return expression;
    }


    @Override
    public String toString() {

        return "Query{" +
                "fields=" + fields +
                ", conditions=" + conditions +
                ", expression=" + expression +
                ", joins=" + joins +
                ", sort=" + sort +
                ", limit=" + limit +
                '}';
    }

}