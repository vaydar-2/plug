package dev.victor.plug.ast;

public class NotExpression implements ConditionExpression {

    private final ConditionExpression expression;

    public NotExpression(ConditionExpression expression) {
        this.expression = expression;
    }

    public ConditionExpression getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return "(NOT " + expression + ")";
    }
}