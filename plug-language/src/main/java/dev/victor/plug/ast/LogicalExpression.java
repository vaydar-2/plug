package dev.victor.plug.ast;

public class LogicalExpression implements ConditionExpression {

    public enum Operator {
        AND,
        OR
    }

    private final ConditionExpression left;
    private final ConditionExpression right;
    private final Operator operator;

    public LogicalExpression(
            ConditionExpression left,
            ConditionExpression right,
            Operator operator
    ) {
        this.left = left;
        this.right = right;
        this.operator = operator;
    }

    public ConditionExpression getLeft() {
        return left;
    }

    public ConditionExpression getRight() {
        return right;
    }

    public Operator getOperator() {
        return operator;
    }

    @Override
    public String toString() {
        return "("
                + left
                + " "
                + operator
                + " "
                + right
                + ")";
    }
}