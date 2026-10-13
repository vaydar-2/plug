package dev.victor.plug.ast;

public class ComparisonExpression implements ConditionExpression {

    private final String field;
    private final String operator;
    private final Value value;

    public ComparisonExpression(
            String field,
            String operator,
            Value value
    ) {
        this.field = field;
        this.operator = operator;
        this.value = value;
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

    @Override
    public String toString() {
        return "("
                + field
                + " "
                + operator
                + " "
                + value
                + ")";
    }
}