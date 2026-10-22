package dev.victor.plug.runtime;

public interface DatabaseAdapter {

    void connect(ConnectionConfig config);

    /*
     * Adapters return structured results instead of printing,
     * so real database backends never touch the console.
     */
    QueryResult execute(ExecutionPlan plan);

    void close();
}