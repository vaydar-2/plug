package dev.victor.plug.runtime;

import java.util.Map;

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

    /*
     * Write operations reuse the same plan/query shape as
     * reads; only the operation tag and the applied data
     * differ, so adapters implement one execute() entry.
     */
    public ExecutionPlan planWrite(
            Query query,
            String collection,
            ExecutionPlan.Operation operation,
            Map<String, Object> data
    ) {
        return new ExecutionPlan(
                query,
                collection,
                operation,
                data
        );
    }

    /*
     * Aggregate reads reuse the query's conditions; the
     * adapter folds the matching records into one value.
     */
    public ExecutionPlan planAggregate(
            Query query,
            String collection,
            ExecutionPlan.Aggregate aggregate,
            String aggregateField
    ) {
        return new ExecutionPlan(
                query,
                collection,
                aggregate,
                aggregateField
        );
    }
}