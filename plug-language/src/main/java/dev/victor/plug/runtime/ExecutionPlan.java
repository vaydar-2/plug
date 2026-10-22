package dev.victor.plug.runtime;

import java.util.Collections;
import java.util.Map;

/*
 * A single unit of work handed to an adapter. Reads carry a
 * Query; writes (insert/update/delete) additionally carry the
 * field/value data the operation applies. An aggregate read
 * folds its records into one value instead of returning rows.
 */
public class ExecutionPlan {

    public enum Operation {
        READ,
        INSERT,
        UPDATE,
        DELETE
    }

    public enum Aggregate {
        NONE,
        COUNT,
        SUM,
        AVG,
        MIN,
        MAX
    }

    private final Query query;
    private final String collection;
    private final Operation operation;
    private final Map<String, Object> data;
    private final Aggregate aggregate;
    private final String aggregateField;

    public ExecutionPlan(
            Query query,
            String collection
    ) {
        this(
                query,
                collection,
                Operation.READ,
                Collections.<String, Object>emptyMap(),
                Aggregate.NONE,
                null
        );
    }

    public ExecutionPlan(
            Query query,
            String collection,
            Operation operation,
            Map<String, Object> data
    ) {
        this(
                query,
                collection,
                operation,
                data,
                Aggregate.NONE,
                null
        );
    }

    public ExecutionPlan(
            Query query,
            String collection,
            Aggregate aggregate,
            String aggregateField
    ) {
        this(
                query,
                collection,
                Operation.READ,
                Collections.<String, Object>emptyMap(),
                aggregate,
                aggregateField
        );
    }

    public ExecutionPlan(
            Query query,
            String collection,
            Operation operation,
            Map<String, Object> data,
            Aggregate aggregate,
            String aggregateField
    ) {
        this.query = query;
        this.collection = collection;
        this.operation = operation;
        this.data = data;
        this.aggregate = aggregate;
        this.aggregateField = aggregateField;
    }

    public Query getQuery() {
        return query;
    }

    public String getCollection() {
        return collection;
    }

    public Operation getOperation() {
        return operation;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public Aggregate getAggregate() {
        return aggregate;
    }

    public String getAggregateField() {
        return aggregateField;
    }

    @Override
    public String toString() {

        return "ExecutionPlan{" +
                "collection='" + collection + '\'' +
                ", operation=" + operation +
                ", data=" + data +
                ", aggregate=" + aggregate +
                ", aggregateField=" + aggregateField +
                ", query=" + query +
                '}';
    }
}