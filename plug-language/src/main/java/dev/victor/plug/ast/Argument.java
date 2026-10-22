package dev.victor.plug.ast;

public class Argument {

    private final String name;
    private final Value value;

    /*
     * join()'s on: argument carries a condition expression
     * instead of a literal value; every other argument is a
     * plain Value and leaves this null.
     */
    private final ConditionExpression condition;

    public Argument(String name, Value value) {
        this.name = name;
        this.value = value;
        this.condition = null;
    }

    public Argument(String name, ConditionExpression condition) {
        this.name = name;
        this.value = null;
        this.condition = condition;
    }

    public String getName() {
        return name;
    }

    public Value getValue() {
        return value;
    }

    public ConditionExpression getCondition() {
        return condition;
    }

    @Override
    public String toString() {

        if (name == null) {
            return value.toString();
        }

        return name + ": " + (condition != null ? condition : value);
    }
}