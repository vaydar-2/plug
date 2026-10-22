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
}
