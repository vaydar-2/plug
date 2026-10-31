package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

public interface DatabaseAdapter {

    void connect(ConnectionConfig config);

    /*
     * Adapters return structured results instead of printing,
     * so real database backends never touch the console.
     */
    QueryResult execute(ExecutionPlan plan);

    /*
     * Schema operations (CREATE_TABLE, CREATE_DATABASE) ride
     * a separate contract method so DDL is never squeezed
     * into the CRUD plan shape. Adapters that cannot express
     * a schema kind must reject it explicitly — never
     * silently ignore it. Execution goes through the same
     * DatabaseContext so availability rules stay uniform.
     */
    default QueryResult executeSchema(SchemaPlan plan) {

        throw new PlugException(
                "Adapter does not support schema operations."
        );
    }

    void close();
}