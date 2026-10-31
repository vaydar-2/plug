package dev.victor.plug.ast;

import java.util.List;

public class Call {

    private final String object;
    private final String method;
    private final List<Argument> arguments;
    private final ConditionExpression expression;

    /*
     * DDL calls (plug.create.table/create.database) carry a
     * typed definition instead of condition expressions;
     * every non-DDL call leaves this null.
     */
    private final TableDefinition tableDefinition;

    public Call(
            String object,
            String method,
            List<Argument> arguments
    ) {
        this.object = object;
        this.method = method;
        this.arguments = arguments;
        this.expression = null;
        this.tableDefinition = null;
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
        this.tableDefinition = null;
    }

    public Call(
            String object,
            String method,
            List<Argument> arguments,
            TableDefinition tableDefinition
    ) {
        this.object = object;
        this.method = method;
        this.arguments = arguments;
        this.expression = null;
        this.tableDefinition = tableDefinition;
    }

    public TableDefinition getTableDefinition() {
        return tableDefinition;
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