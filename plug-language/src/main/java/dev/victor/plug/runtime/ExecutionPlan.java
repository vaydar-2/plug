package dev.victor.plug.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
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

        /*
         * Write payloads are copied so the plan's data is
         * frozen at planning time; a null map reads as an
         * empty one (reads pass no data).
         */
        this.data = data == null
                ? Collections.<String, Object>emptyMap()
                : new LinkedHashMap<>(data);

        this.aggregate = aggregate;
        this.aggregateField = aggregateField;
    }

    /*
     * The query is shared by reference: it is fully built
     * before planning and never mutated afterwards, so both
     * planner and adapter see the same immutable-in-practice
     * object. Its internal lists are already protected
     * inside Query. The write data map is exposed read-only.
     */
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
        return Collections.unmodifiableMap(data);
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