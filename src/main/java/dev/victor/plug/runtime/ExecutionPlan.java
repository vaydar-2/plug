package dev.victor.plug.runtime;

public class ExecutionPlan {

    private final Query query;
    private final String collection;

    public ExecutionPlan(
            Query query,
            String collection
    ) {
        this.query = query;
        this.collection = collection;
    }

    public Query getQuery() {
        return query;
    }

    public String getCollection() {
        return collection;
    }

    @Override
    public String toString() {

        return "ExecutionPlan{" +
                "collection='" + collection + '\'' +
                ", query=" + query +
                '}';
    }
}