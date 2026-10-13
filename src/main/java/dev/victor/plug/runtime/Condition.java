package dev.victor.plug.runtime;

import dev.victor.plug.ast.Value;

public class Condition {

    public enum LogicalOperator {
        AND,
        OR
    }

    private final String field;
    private final String operator;
    private final Value value;
    private final LogicalOperator logicalOperator;

    public Condition(
            String field,
            String operator,
            Value value,
            LogicalOperator logicalOperator
    ) {
        this.field = field;
        this.operator = operator;
        this.value = value;
        this.logicalOperator = logicalOperator;
    }

    public String getField() {
        return field;
    }

    public String getOperator() {
        return operator;
    }

    public Value getValue() {
        return value;
    }

    public LogicalOperator getLogicalOperator() {
        return logicalOperator;
    }

    @Override
    public String toString() {

        return logicalOperator
                + " "
                + field
                + " "
                + operator
                + " "
                + value;
    }
}