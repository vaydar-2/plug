package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.Argument;
import dev.victor.plug.ast.Call;
import dev.victor.plug.ast.Program;
import dev.victor.plug.ast.Statement;
import dev.victor.plug.ast.Value;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Runtime {

    private final ConnectionManager connectionManager;

    public Runtime() {
        connectionManager = new ConnectionManager();
    }

    public void execute(Program program) {

        /*
         * Auto-close after every program run, even on error,
         * so adapters never leak open connections.
         */
        try {

            for (Statement statement : program.getStatements()) {
                executeStatement(statement);
            }

        } finally {
            connectionManager.close();
        }
    }

    private void executeStatement(Statement statement) {

        Query query = null;

        for (Call call : statement.getCalls()) {

            if (call.getObject().equals("plug")
                    && call.getMethod().equals("connect")) {

                connectionManager.connect(
                        call.getArguments()
                );

                continue;
            }

            if (call.getObject().equals("plug")
                    && call.getMethod().equals("use")) {

                use(call.getArguments());

                continue;
            }

            if (call.getObject().equals("plug")
                    && call.getMethod().equals("close")) {

                connectionManager.close();

                continue;
            }

            if (call.getMethod().equals("get")) {

                if (connectionManager.getContext() == null) {
                    throw new PlugException(
                            "No database connection. Use plug.connect() first."
                    );
                }

                if (connectionManager.getContext().getCollection() == null) {
                    throw new PlugException(
                            "No collection selected. Use plug.use() first."
                    );
                }

                query = get(call.getArguments());
                continue;
            }

            if (call.getMethod().equals("where")) {

                if (query == null) {
                    throw new PlugException(
                            "where() must follow get()."
                    );
                }

                if (call.getExpression() != null) {

                    query.expression(
                            call.getExpression()
                    );

                } else {

                    where(
                            query,
                            call.getArguments()
                    );
                }

                continue;
            }

            if (call.getMethod().equals("and")) {

                if (query == null) {
                    throw new PlugException(
                            "and() must follow get()."
                    );
                }

                and(query, call.getArguments());
                continue;
            }

            if (call.getMethod().equals("or")) {

                if (query == null) {
                    throw new PlugException(
                            "or() must follow get()."
                    );
                }

                or(query, call.getArguments());
                continue;
            }

            if (call.getMethod().equals("sort")) {

                if (query == null) {
                    throw new PlugException(
                            "sort() must follow get()."
                    );
                }

                sort(query, call.getArguments());
                continue;
            }

            if (call.getMethod().equals("limit")) {

                if (query == null) {
                    throw new PlugException(
                            "limit() must follow get()."
                    );
                }

                limit(query, call.getArguments());
                continue;
            }

            throw new PlugException(
                    "Unknown operation: "
                            + call.getObject()
                            + "."
                            + call.getMethod()
            );
        }

        if (query != null) {

            QueryPlanner planner = new QueryPlanner();

            ExecutionPlan plan = planner.plan(
                    query,
                    connectionManager
                            .getContext()
                            .getCollection()
            );

            ExecutionEngine engine = new ExecutionEngine();

            QueryResult result = engine.execute(
                    plan,
                    connectionManager.getContext()
            );

            displayQueryResult(result);
        }
    }

    /*
     * Result display lives in the runtime, not in the
     * adapter — adapters return QueryResult only.
     */
    private void displayQueryResult(QueryResult result) {

        System.out.println(
                "Records found: " + result.getCount()
        );

        for (Map<String, Object> row : result.getRows()) {
            System.out.println(row);
        }
    }

    private Query get(List<Argument> arguments) {

        if (arguments.isEmpty()) {
            throw new PlugException(
                    "get() expects at least one field."
            );
        }

        return new Query(
                positionalValues(arguments)
        );
    }

    private void use(List<Argument> arguments) {

        if (arguments.size() != 1) {
            throw new PlugException(
                    "use() expects exactly one argument."
            );
        }

        String collection = getValue(arguments.get(0));

        if (collection.equals("null")
                || collection.isEmpty()) {
            throw new PlugException(
                    "use() requires a collection name."
            );
        }

        if (connectionManager.getContext() == null) {
            throw new PlugException(
                    "No database connection. Use plug.connect() first."
            );
        }

        connectionManager
                .getContext()
                .setCollection(collection);

        System.out.println(
                "Using collection: " + collection
        );
    }

    private void where(
            Query query,
            List<Argument> arguments
    ) {

        if (arguments.size() != 3) {
            throw new PlugException(
                    "where() expects field, operator, and value."
            );
        }

        query.where(
                getValue(arguments.get(0)),
                getValue(arguments.get(1)),
                arguments.get(2).getValue()
        );
    }

    private void and(
            Query query,
            List<Argument> arguments
    ) {

        if (arguments.size() != 3) {
            throw new PlugException(
                    "and() expects field, operator, and value."
            );
        }

        query.and(
                getValue(arguments.get(0)),
                getValue(arguments.get(1)),
                arguments.get(2).getValue()
        );
    }

    private void or(
            Query query,
            List<Argument> arguments
    ) {

        if (arguments.size() != 3) {
            throw new PlugException(
                    "or() expects field, operator, and value."
            );
        }

        query.or(
                getValue(arguments.get(0)),
                getValue(arguments.get(1)),
                arguments.get(2).getValue()
        );
    }

    private void sort(
            Query query,
            List<Argument> arguments
    ) {

        if (arguments.size() != 2) {
            throw new PlugException(
                    "sort() expects field and direction."
            );
        }

        String field =
                getValue(arguments.get(0));

        String direction =
                getValue(arguments.get(1));

        if (!direction.equals("ASC")
                && !direction.equals("DESC")) {

            throw new PlugException(
                    "sort() direction must be ASC or DESC."
            );
        }

        query.sort(
                field,
                direction
        );
    }

    private void limit(
            Query query,
            List<Argument> arguments
    ) {

        if (arguments.size() != 1) {
            throw new PlugException(
                    "limit() expects exactly one argument."
            );
        }

        Value value =
                arguments.get(0).getValue();

        if (value.getType() != Value.Type.NUMBER) {
            throw new PlugException(
                    "limit() expects a number."
            );
        }

        if (value.getValue() instanceof Double) {
            throw new PlugException(
                    "limit() expects a whole number."
            );
        }

        query.limit(
                (Integer) value.getValue()
        );
    }

    private List<String> positionalValues(
            List<Argument> arguments
    ) {

        return arguments.stream()
                .map(this::getValue)
                .collect(Collectors.toList());
    }

    private String getValue(Argument argument) {

        Object value = argument.getValue().getValue();

        if (value == null) {
            return "null";
        }

        return value.toString();
    }
}