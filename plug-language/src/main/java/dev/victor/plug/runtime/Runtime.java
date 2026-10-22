package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.Argument;
import dev.victor.plug.ast.Call;
import dev.victor.plug.ast.Program;
import dev.victor.plug.ast.Statement;
import dev.victor.plug.ast.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

        ExecutionPlan.Operation operation = null;

        Map<String, Object> writeData = null;

        ExecutionPlan.Aggregate aggregate =
                ExecutionPlan.Aggregate.NONE;

        String aggregateField = null;

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

            if (call.getObject().equals("plug")
                    && call.getMethod().equals("insert")) {

                requireConnectionAndCollection();

                operation = ExecutionPlan.Operation.INSERT;
                writeData = writeData(
                        call.getArguments(),
                        "insert"
                );

                query = new Query(new ArrayList<>());

                continue;
            }

            if (call.getObject().equals("plug")
                    && call.getMethod().equals("update")) {

                requireConnectionAndCollection();

                operation = ExecutionPlan.Operation.UPDATE;
                writeData = writeData(
                        call.getArguments(),
                        "update"
                );

                query = new Query(new ArrayList<>());

                continue;
            }

            if (call.getObject().equals("plug")
                    && call.getMethod().equals("delete")) {

                if (!call.getArguments().isEmpty()) {
                    throw new PlugException(
                            "delete() takes no arguments."
                    );
                }

                requireConnectionAndCollection();

                operation = ExecutionPlan.Operation.DELETE;
                writeData = new LinkedHashMap<>();

                query = new Query(new ArrayList<>());

                continue;
            }

            if (call.getMethod().equals("get")) {

                requireConnectionAndCollection();

                query = get(call.getArguments());
                continue;
            }

            if (call.getMethod().equals("where")) {

                if (aggregate != ExecutionPlan.Aggregate.NONE) {
                    throw new PlugException(
                            "where() must come before an aggregate."
                    );
                }

                if (operation != null
                        && operation != ExecutionPlan.Operation.UPDATE
                        && operation != ExecutionPlan.Operation.DELETE) {

                    throw new PlugException(
                            methodLabel(operation)
                                    + "() does not accept where()."
                    );
                }

                if (query == null) {
                    throw new PlugException(
                            "where() must follow get(), "
                                    + "update(), or delete()."
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

                if (aggregate != ExecutionPlan.Aggregate.NONE) {
                    throw new PlugException(
                            "and() must come before an aggregate."
                    );
                }

                if (operation == ExecutionPlan.Operation.INSERT) {
                    throw new PlugException(
                            "insert() does not accept and()."
                    );
                }

                if (query == null) {
                    throw new PlugException(
                            "and() must follow get(), "
                                    + "update(), or delete()."
                    );
                }

                and(query, call.getArguments());
                continue;
            }

            if (call.getMethod().equals("or")) {

                if (aggregate != ExecutionPlan.Aggregate.NONE) {
                    throw new PlugException(
                            "or() must come before an aggregate."
                    );
                }

                if (operation == ExecutionPlan.Operation.INSERT) {
                    throw new PlugException(
                            "insert() does not accept or()."
                    );
                }

                if (query == null) {
                    throw new PlugException(
                            "or() must follow get(), "
                                    + "update(), or delete()."
                    );
                }

                or(query, call.getArguments());
                continue;
            }

            if (call.getMethod().equals("count")
                    || call.getMethod().equals("sum")
                    || call.getMethod().equals("avg")
                    || call.getMethod().equals("min")
                    || call.getMethod().equals("max")) {

                String method = call.getMethod();

                if (operation != null) {
                    throw new PlugException(
                            "An aggregate cannot follow "
                                    + methodLabel(operation) + "()."
                    );
                }

                if (query == null) {
                    throw new PlugException(
                            method + "() must follow get()."
                    );
                }

                if (query.getSort() != null
                        || query.getLimit() != null) {
                    throw new PlugException(
                            "sort()/limit() cannot be combined "
                                    + "with an aggregate."
                    );
                }

                if (statement.getCalls()
                        .get(statement.getCalls().size() - 1)
                        != call) {

                    throw new PlugException(
                            method + "() must be the last call "
                                    + "in the chain."
                    );
                }

                List<Argument> aggregateArguments =
                        call.getArguments();

                if (method.equals("count")) {

                    if (!aggregateArguments.isEmpty()) {
                        throw new PlugException(
                                "count() takes no arguments."
                        );
                    }

                    aggregate = ExecutionPlan.Aggregate.COUNT;

                    continue;
                }

                if (aggregateArguments.size() != 1
                        || aggregateArguments.get(0).getName() != null
                        || aggregateArguments.get(0).getValue().getType()
                        != Value.Type.IDENTIFIER) {

                    throw new PlugException(
                            method + "() expects one field name, e.g. "
                                    + method + "(balance)."
                    );
                }

                aggregateField = (String) aggregateArguments
                        .get(0).getValue().getValue();

                switch (method) {

                    case "sum":
                        aggregate = ExecutionPlan.Aggregate.SUM;
                        break;

                    case "avg":
                        aggregate = ExecutionPlan.Aggregate.AVG;
                        break;

                    case "min":
                        aggregate = ExecutionPlan.Aggregate.MIN;
                        break;

                    case "max":
                        aggregate = ExecutionPlan.Aggregate.MAX;
                        break;

                    default:
                        break;
                }

                continue;
            }

            if (call.getMethod().equals("sort")) {

                if (operation != null) {
                    throw new PlugException(
                            "sort() only works with get()."
                    );
                }

                if (aggregate != ExecutionPlan.Aggregate.NONE) {
                    throw new PlugException(
                            "sort() cannot be combined "
                                    + "with an aggregate."
                    );
                }

                if (query == null) {
                    throw new PlugException(
                            "sort() must follow get()."
                    );
                }

                sort(query, call.getArguments());
                continue;
            }

            if (call.getMethod().equals("limit")) {

                if (operation != null) {
                    throw new PlugException(
                            "limit() only works with get()."
                    );
                }

                if (aggregate != ExecutionPlan.Aggregate.NONE) {
                    throw new PlugException(
                            "limit() cannot be combined "
                                    + "with an aggregate."
                    );
                }

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

        ExecutionEngine engine = new ExecutionEngine();

        if (operation != null) {

            requireWriteConditions(operation, query);

            QueryPlanner planner = new QueryPlanner();

            ExecutionPlan plan = planner.planWrite(
                    query,
                    connectionManager
                            .getContext()
                            .getCollection(),
                    operation,
                    writeData
            );

            QueryResult result = engine.execute(
                    plan,
                    connectionManager.getContext()
            );

            displayQueryResult(result, operation);

        } else if (aggregate != ExecutionPlan.Aggregate.NONE) {

            QueryPlanner planner = new QueryPlanner();

            ExecutionPlan plan = planner.planAggregate(
                    query,
                    connectionManager
                            .getContext()
                            .getCollection(),
                    aggregate,
                    aggregateField
            );

            QueryResult result = engine.execute(
                    plan,
                    connectionManager.getContext()
            );

            displayAggregate(
                    result,
                    aggregate,
                    aggregateField
            );

        } else if (query != null) {

            if (query.getFields().isEmpty()) {
                throw new PlugException(
                        "get() expects at least one field."
                );
            }

            QueryPlanner planner = new QueryPlanner();

            ExecutionPlan plan = planner.plan(
                    query,
                    connectionManager
                            .getContext()
                            .getCollection()
            );

            QueryResult result = engine.execute(
                    plan,
                    connectionManager.getContext()
            );

            displayQueryResult(
                    result,
                    ExecutionPlan.Operation.READ
            );
        }
    }

    /*
     * update() and delete() must always scope their effect
     * with at least one condition, so a typo can never wipe
     * or rewrite a whole collection.
     */
    private void requireWriteConditions(
            ExecutionPlan.Operation operation,
            Query query
    ) {

        if (operation == ExecutionPlan.Operation.INSERT) {
            return;
        }

        if (query.getConditions().isEmpty()
                && query.getExpression() == null) {

            throw new PlugException(
                    methodLabel(operation)
                            + "() requires a where() condition."
            );
        }
    }

    /*
     * Aggregate display: "COUNT = 20" or
     * "SUM(balance) = 590700".
     */
    private void displayAggregate(
            QueryResult result,
            ExecutionPlan.Aggregate aggregate,
            String field
    ) {

        String label = aggregate.name();

        if (field != null) {
            label = label + "(" + field + ")";
        }

        System.out.println(
                label + " = " + result.getAggregateValue()
        );
    }

    private void requireConnectionAndCollection() {

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
    }

    /*
     * Result display lives in the runtime, not in the
     * adapter — adapters return QueryResult only.
     */
    private void displayQueryResult(
            QueryResult result,
            ExecutionPlan.Operation operation
    ) {

        switch (operation) {

            case INSERT:
                System.out.println(
                        "Records inserted: "
                                + result.getCount()
                );
                break;

            case UPDATE:
                System.out.println(
                        "Records updated: "
                                + result.getCount()
                );
                break;

            case DELETE:
                System.out.println(
                        "Records deleted: "
                                + result.getCount()
                );
                break;

            case READ:
            default:
                System.out.println(
                        "Records found: " + result.getCount()
                );
                break;
        }

        for (Map<String, Object> row : result.getRows()) {
            System.out.println(row);
        }
    }

    /*
     * get() may be argument-free when an aggregate consumes
     * the statement (plug.get().count()); the projection is
     * only validated in the plain read path.
     */
    private Query get(List<Argument> arguments) {

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
                comparisonOperator(arguments),
                arguments.get(2).getValue()
        );
    }

    /*
     * Flat-form IS: .where(email, IS, null) becomes IS NULL
     * without changing the condition model. Anything other
     * than a null value after IS is rejected.
     */
    private String comparisonOperator(List<Argument> arguments) {

        String operator = getValue(arguments.get(1));

        if (operator.equalsIgnoreCase("IS")) {

            if (arguments.get(2).getValue().getType()
                    == Value.Type.NULL) {

                return "IS NULL";
            }

            throw new PlugException(
                    "IS requires a null value."
            );
        }

        return operator;
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
                comparisonOperator(arguments),
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
                comparisonOperator(arguments),
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

    /*
     * insert()/update() arguments are field/value pairs:
     * only named arguments, and only literal values
     * (string, number, boolean, null).
     */
    private Map<String, Object> writeData(
            List<Argument> arguments,
            String method
    ) {

        if (arguments.isEmpty()) {
            throw new PlugException(
                    method + "() expects at least one field, e.g. "
                            + method + "(name: \"Victor\")."
            );
        }

        Map<String, Object> data = new LinkedHashMap<>();

        for (Argument argument : arguments) {

            if (argument.getName() == null) {
                throw new PlugException(
                        method + "() requires named arguments, "
                                + "e.g. " + method + "(name: \"Victor\")."
                );
            }

            Value value = argument.getValue();

            switch (value.getType()) {

                case STRING:
                case NUMBER:
                case BOOLEAN:

                    data.put(
                            argument.getName(),
                            value.getValue()
                    );
                    break;

                case NULL:

                    data.put(
                            argument.getName(),
                            null
                    );
                    break;

                default:

                    throw new PlugException(
                            method + "() does not accept "
                                    + value.getType()
                                    + " values for field '"
                                    + argument.getName() + "'."
                    );
            }
        }

        return data;
    }

    private String methodLabel(
            ExecutionPlan.Operation operation
    ) {

        switch (operation) {

            case INSERT:
                return "insert";

            case UPDATE:
                return "update";

            case DELETE:
                return "delete";

            case READ:
            default:
                return "get";
        }
    }

    private String getValue(Argument argument) {

        Object value = argument.getValue().getValue();

        if (value == null) {
            return "null";
        }

        return value.toString();
    }
}