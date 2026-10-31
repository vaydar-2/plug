package dev.victor.plug.runtime;

import dev.victor.plug.ast.TableDefinition;

/*
 * A single schema operation handed to an adapter, parallel
 * to ExecutionPlan for CRUD. Schema work goes through its
 * own adapter contract method (executeSchema) rather than
 * being forced into the read/write plan shape.
 */
public class SchemaPlan {

    public enum Operation {
        CREATE_TABLE,
        CREATE_DATABASE
    }

    private final Operation operation;
    private final TableDefinition table;

    /*
     * Only for CREATE_DATABASE; CREATE_TABLE carries the
     * definition instead.
     */
    private final String databaseName;

    private SchemaPlan(
            Operation operation,
            TableDefinition table,
            String databaseName
    ) {
        this.operation = operation;
        this.table = table;
        this.databaseName = databaseName;
    }

    public static SchemaPlan createTable(
            TableDefinition table
    ) {
        return new SchemaPlan(Operation.CREATE_TABLE, table, null);
    }

    public static SchemaPlan createDatabase(
            String databaseName
    ) {
        return new SchemaPlan(
                Operation.CREATE_DATABASE,
                null,
                databaseName
        );
    }

    public Operation getOperation() {
        return operation;
    }

    public TableDefinition getTable() {
        return table;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    @Override
    public String toString() {

        return "SchemaPlan{" + operation
                + ", table=" + table
                + ", database=" + databaseName + '}';
    }
}
