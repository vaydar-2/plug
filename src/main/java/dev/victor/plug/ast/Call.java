package dev.victor.plug.ast;

import java.util.List;

public class Call {

    private final String object;
    private final String method;
    private final List<Argument> arguments;
    private final ConditionExpression expression;

    public Call(
            String object,
            String method,
            List<Argument> arguments
    ) {
        this.object = object;
        this.method = method;
        this.arguments = arguments;
        this.expression = null;
    }

    public Call(
            String object,
            String method,
            ConditionExpression expression
    ) {
        this.object = object;
        this.method = method;
        this.arguments = null;
        this.expression = expression;
    }

    public String getObject() {
        return object;
    }

    public String getMethod() {
        return method;
    }

    public List<Argument> getArguments() {
        return arguments;
    }

    public ConditionExpression getExpression() {
        return expression;
    }

    @Override
    public String toString() {

        if (expression != null) {
            return object + "." + method
                    + "(" + expression + ")";
        }

        return object + "." + method + arguments;
    }
}