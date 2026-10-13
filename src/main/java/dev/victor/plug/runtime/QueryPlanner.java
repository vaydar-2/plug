package dev.victor.plug.runtime;

public class QueryPlanner {

    public ExecutionPlan plan(
            Query query,
            String collection
    ) {
        return new ExecutionPlan(
                query,
                collection
        );
    }
}