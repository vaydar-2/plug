package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

public class ExecutionEngine {

    public QueryResult execute(
            ExecutionPlan plan,
            DatabaseContext context
    ) {

        if (context == null) {
            throw new PlugException(
                    "No database connection."
            );
        }

        return context.getAdapter().execute(plan);
    }

    /*
     * Schema operations ride their own contract method:
     * adapters opt into DDL instead of being forced to
     * express it as reads or writes.
     */
    public QueryResult executeSchema(
            SchemaPlan plan,
            DatabaseContext context
    ) {

        if (context == null) {
            throw new PlugException(
                    "No database connection."
            );
        }

        return context.getAdapter().executeSchema(plan);
    }
}
