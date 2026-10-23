package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/*
 * PostgreSQL implementation of the DatabaseAdapter contract.
 *
 * Everything query-shaped (field existence, type checking,
 * join structure, left-to-right condition folding) is shared
 * with the mock through QueryValidation and
 * PostgresQueryCompiler; this class only owns the JDBC
 * mechanics: opening the connection, introspecting schemas
 * from information_schema, binding parameters, and mapping
 * result rows back into Plug's Java types.
 *
 * The core query model (Runtime, Query, ExecutionPlan) never
 * mentions PostgreSQL — this class plugs in through the
 * same three methods the mock implements.
 */
public class PostgresDatabaseAdapter implements DatabaseAdapter {

    /*
     * Collection names become SQL table identifiers, so a
     * script name that is not a safe unquoted identifier is
     * rejected up front (PostgreSQL's unquoted lowercase
     * convention). Column names never reach SQL from script
     * input — they are resolved against the introspected
     * schema first.
     */
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[a-z_][a-z0-9_]*");

    private Connection connection;

    private final Map<String, Map<String, Class<?>>> schemaCache =
            new LinkedHashMap<>();

    @Override
    public void connect(ConnectionConfig config) {

        requireIdentifier(config.getDatabase());

        String url = "jdbc:postgresql://"
                + config.getHost()
                + ":"
                + config.getPort()
                + "/"
                + config.getDatabase();

        try {

            connection = DriverManager.getConnection(
                    url,
                    config.getUsername(),
                    config.getPassword()
            );

            /*
             * Fail fast on unusable connections rather than
             * at the first statement.
             */
            try (PreparedStatement probe =
                         connection.prepareStatement(
                                 "SELECT 1"
                         );
                 ResultSet ignored = probe.executeQuery()) {

                ignored.next();
            }

        } catch (SQLException e) {

            connection = null;

            throw new PlugException(
                    "Cannot connect to PostgreSQL database '"
                            + config.getDatabase()
                            + "' at "
                            + config.getHost()
                            + ":"
                            + config.getPort()
                            + ": "
                            + e.getMessage()
            );
        }
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {

        requireConnection();

        switch (plan.getOperation()) {

            case INSERT:
                return insert(plan);

            case UPDATE:
                return update(plan);

            case DELETE:
                return delete(plan);

            case READ:
            default:
                return read(plan);
        }
    }

    @Override
    public void close() {

        schemaCache.clear();

        if (connection == null) {
            return;
        }

        try {
            connection.close();
        } catch (SQLException e) {
            /* Closing a dead connection is not fatal. */
        } finally {
            connection = null;
        }
    }

    /*
     * Reads: plain reads, aggregates, and joined reads.
     */
    private QueryResult read(ExecutionPlan plan) {

        Query query = plan.getQuery();

        String table = plan.getCollection();

        Map<String, Class<?>> schema = schemaOf(table);

        if (!query.getJoins().isEmpty()) {

            if (plan.getAggregate()
                    != ExecutionPlan.Aggregate.NONE) {

                throw new PlugException(
                        "An aggregate cannot be combined "
                                + "with join()."
                );
            }

            return readWithJoins(
                    plan,
                    table,
                    schema
            );
        }

        QueryValidation.validateWhere(query, schema);

        Sort sort = query.getSort();

        if (sort != null) {

            validateSortField(sort, schema);
        }

        if (plan.getAggregate()
                != ExecutionPlan.Aggregate.NONE) {

            QueryValidation.validateAggregate(
                    plan.getAggregate(),
                    plan.getAggregateField(),
                    schema
            );

            return aggregate(plan, table, schema);
        }

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileRead(
                        query,
                        table,
                        schema
                );

        List<Map<String, Object>> rows =
                runRead(
                        compiled,
                        table,
                        schema,
                        query.getFields()
                );

        return new QueryResult(
                rows.size(),
                rows
        );
    }

    private QueryResult readWithJoins(
            ExecutionPlan plan,
            String baseTable,
            Map<String, Class<?>> baseSchema
    ) {

        Query query = plan.getQuery();

        /*
         * Schemas in join order: base first, then each
         * joined collection — later joins may reference
         * any earlier collection in keys and conditions.
         */
        LinkedHashMap<String, Map<String, Class<?>>> schemas =
                new LinkedHashMap<>();

        schemas.put(baseTable, baseSchema);

        for (Join join : query.getJoins()) {
            schemas.put(
                    join.getCollection(),
                    schemaOf(join.getCollection())
            );
        }

        for (Join join : query.getJoins()) {
            QueryValidation.validateJoin(join, schemas);
        }

        for (Condition condition :
                query.getConditions()) {

            QueryValidation.validateResolvedCondition(
                    condition,
                    schemas,
                    baseTable
            );
        }

        if (query.getExpression() != null) {

            QueryValidation.validateResolvedExpression(
                    query.getExpression(),
                    schemas,
                    baseTable
            );
        }

        Sort sort = query.getSort();

        if (sort != null) {

            String resolved =
                    QueryValidation.resolveField(
                            sort.getField(),
                            schemas,
                            baseTable
                    );

            Class<?> fieldType = schemas
                    .get(QueryValidation.collectionOf(resolved))
                    .get(QueryValidation.bareName(resolved));

            if (!Comparable.class.isAssignableFrom(fieldType)) {
                throw new PlugException(
                        "Field '" + sort.getField()
                                + "' cannot be sorted."
                );
            }
        }

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileJoinedRead(
                        query,
                        baseTable,
                        schemas
                );

        List<Map<String, Object>> rows = runJoinedRead(
                compiled,
                query
        );

        return new QueryResult(
                rows.size(),
                rows
        );
    }

    /*
     * Aggregate reads fold into one value. COUNT is an
     * Integer like the mock's row count; SUM keeps Integer
     * columns Integer; AVG is always a double; MIN/MAX
     * return the column's own type. An empty input folds
     * to SQL null (except COUNT), matching the mock.
     */
    private QueryResult aggregate(
            ExecutionPlan plan,
            String table,
            Map<String, Class<?>> schema
    ) {

        ExecutionPlan.Aggregate aggregate =
                plan.getAggregate();

        String field = plan.getAggregateField();

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileAggregate(
                        aggregate,
                        field,
                        plan.getQuery(),
                        table,
                        schema
                );

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             compiled.getSql()
                     )) {

            bind(statement, compiled.getParams());

            try (ResultSet rs =
                         statement.executeQuery()) {

                rs.next();

                Object value = rs.getObject(1);

                if (aggregate
                        == ExecutionPlan.Aggregate.COUNT) {

                    long count = rs.getLong(1);

                    return new QueryResult(
                            (int) count,
                            new ArrayList<>(),
                            (int) count
                    );
                }

                Class<?> columnType =
                        field != null
                                ? schema.get(field)
                                : null;

                return new QueryResult(
                        0,
                        new ArrayList<>(),
                        normalizeAggregateValue(
                                value,
                                aggregate,
                                columnType
                        )
                );
            }

        } catch (SQLException e) {

            throw new PlugException(
                    statementError(plan, e)
            );
        }
    }

    private Object normalizeAggregateValue(
            Object value,
            ExecutionPlan.Aggregate aggregate,
            Class<?> columnType
    ) {

        if (value == null) {
            return null;
        }

        boolean integerColumn =
                columnType == Integer.class;

        if (aggregate == ExecutionPlan.Aggregate.SUM
                && integerColumn) {

            return asInt(value);
        }

        if (aggregate == ExecutionPlan.Aggregate.AVG) {

            return ((Number) asNumber(value))
                    .doubleValue();
        }

        /* MIN/MAX and SUM on non-integer columns keep the
         * database's own representation, normalized to
         * Plug's Java types. */
        return normalize(value, columnType);
    }

    /*
     * Writes. Insert relies on the database's own defaults
     * (serial/identity) when no id is given — the mock's
     * max-id+1 emulation has no reason to exist here. An
     * explicit id that collides is reported with the same
     * "Duplicate id: N" message the mock produces.
     */
    private QueryResult insert(ExecutionPlan plan) {

        String table = plan.getCollection();

        Map<String, Class<?>> schema =
                schemaOf(table);

        QueryValidation.validateWriteFields(
                plan.getData(),
                schema
        );

        Object explicitId = plan.getData().get("id");

        if (explicitId != null) {

            try (PreparedStatement probe =
                         connection.prepareStatement(
                                 "SELECT 1 FROM "
                                         + PostgresQueryCompiler
                                         .quote(table)
                                         + " WHERE \"id\" = ?"
                         )) {

                probe.setObject(1, explicitId);

                try (ResultSet rs =
                             probe.executeQuery()) {

                    if (rs.next()) {

                        throw new PlugException(
                                "Duplicate id: " + explicitId
                        );
                    }
                }

            } catch (SQLException e) {

                throw new PlugException(
                        "Duplicate id check failed: "
                                + e.getMessage()
                );
            }
        }

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileInsert(
                        table,
                        plan.getData(),
                        schema
                );

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             compiled.getSql()
                     )) {

            bind(statement, compiled.getParams());

            statement.executeUpdate();

        } catch (SQLException e) {

            if (isUniqueViolation(e) && explicitId != null) {

                throw new PlugException(
                        "Duplicate id: " + explicitId
                );
            }

            throw new PlugException(
                    statementError(plan, e)
            );
        }

        return new QueryResult(
                1,
                new ArrayList<>()
        );
    }

    private QueryResult update(ExecutionPlan plan) {

        String table = plan.getCollection();

        Map<String, Class<?>> schema =
                schemaOf(table);

        QueryValidation.validateWhere(
                plan.getQuery(),
                schema
        );

        QueryValidation.validateWriteFields(
                plan.getData(),
                schema
        );

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileUpdate(
                        plan.getQuery(),
                        table,
                        plan.getData(),
                        schema
                );

        int updated = executeUpdate(compiled);

        return new QueryResult(
                updated,
                new ArrayList<>()
        );
    }

    private QueryResult delete(ExecutionPlan plan) {

        String table = plan.getCollection();

        Map<String, Class<?>> schema =
                schemaOf(table);

        QueryValidation.validateWhere(
                plan.getQuery(),
                schema
        );

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileDelete(
                        plan.getQuery(),
                        table,
                        schema
                );

        int deleted = executeUpdate(compiled);

        return new QueryResult(
                deleted,
                new ArrayList<>()
        );
    }

    private int executeUpdate(
            PostgresQueryCompiler.CompiledSql compiled
    ) {

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             compiled.getSql()
                     )) {

            bind(statement, compiled.getParams());

            return statement.executeUpdate();

        } catch (SQLException e) {

            throw new PlugException(e.getMessage());
        }
    }

    /*
     * Statement execution and row mapping. Rows are
     * LinkedHashMaps keyed by the requested field names in
     * projection order, with values normalized to Plug's
     * four Java types.
     */
    private List<Map<String, Object>> runRead(
            PostgresQueryCompiler.CompiledSql compiled,
            String table,
            Map<String, Class<?>> schema,
            List<String> fields
    ) {

        List<Map<String, Object>> rows =
                new ArrayList<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             compiled.getSql()
                     )) {

            bind(statement, compiled.getParams());

            try (ResultSet rs =
                         statement.executeQuery()) {

                List<String> labels =
                        columnLabels(rs);

                List<Class<?>> types =
                        columnTypes(labels, fields, schema);

                while (rs.next()) {

                    Map<String, Object> row =
                            new LinkedHashMap<>();

                    for (int i = 0;
                            i < labels.size();
                            i++) {

                        row.put(
                                labels.get(i),
                                normalize(
                                        rs.getObject(i + 1),
                                        types.get(i)
                                )
                        );
                    }

                    rows.add(row);
                }
            }

        } catch (SQLException e) {

            throw new PlugException(e.getMessage());
        }

        return rows;
    }

    private List<Map<String, Object>> runJoinedRead(
            PostgresQueryCompiler.CompiledSql compiled,
            Query query
    ) {

        List<Map<String, Object>> rows =
                new ArrayList<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             compiled.getSql()
                     )) {

            bind(statement, compiled.getParams());

            try (ResultSet rs =
                         statement.executeQuery()) {

                while (rs.next()) {

                    Map<String, Object> row =
                            new LinkedHashMap<>();

                    ResultSetMetaData meta =
                            rs.getMetaData();

                    for (int i = 1;
                            i <= meta.getColumnCount();
                            i++) {

                        row.put(
                                meta.getColumnLabel(i),
                                normalize(
                                        rs.getObject(i),
                                        null
                                )
                        );
                    }

                    rows.add(row);
                }
            }

        } catch (SQLException e) {

            throw new PlugException(e.getMessage());
        }

        return rows;
    }

    private List<String> columnLabels(ResultSet rs)
            throws SQLException {

        ResultSetMetaData meta = rs.getMetaData();

        List<String> labels = new ArrayList<>();

        for (int i = 1;
                i <= meta.getColumnCount();
                i++) {

            labels.add(meta.getColumnLabel(i));
        }

        return labels;
    }

    private List<Class<?>> columnTypes(
            List<String> labels,
            List<String> fields,
            Map<String, Class<?>> schema
    ) {

        List<Class<?>> types = new ArrayList<>();

        for (String label : labels) {

            String key = fields.contains(label)
                    ? label
                    : QueryValidation.bareName(
                    fields.get(
                            Math.max(
                                    0,
                                    fields.indexOf(label)
                            )
                    )
            );

            types.add(schema.get(key));
        }

        return types;
    }

    /*
     * Schema introspection. The first touch of a collection
     * reads its columns from information_schema and maps
     * PostgreSQL types to Plug's four Java types.
     * Unsupported column types are left out of the schema,
     * so referencing them reports "Unknown field" — the
     * documented limitation for this adapter.
     */
    private Map<String, Class<?>> schemaOf(
            String table
    ) {

        requireIdentifier(table);

        Map<String, Class<?>> cached =
                schemaCache.get(table);

        if (cached != null) {
            return cached;
        }

        Map<String, Class<?>> schema =
                new LinkedHashMap<>();

        String sql =
                "SELECT column_name, data_type "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "AND table_name = ? "
                        + "ORDER BY ordinal_position";

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, table);

            try (ResultSet rs =
                         statement.executeQuery()) {

                boolean any = false;

                while (rs.next()) {

                    any = true;

                    String column =
                            rs.getString("column_name");

                    Class<?> type = javaType(
                            rs.getString("data_type")
                    );

                    if (type != null) {
                        schema.put(column, type);
                    }
                }

                if (!any) {

                    throw new PlugException(
                            "Collection not found: " + table
                    );
                }
            }

        } catch (SQLException e) {

            throw new PlugException(e.getMessage());
        }

        schemaCache.put(table, schema);

        return schema;
    }

    private Class<?> javaType(String dataType) {

        switch (dataType) {

            case "smallint":
            case "integer":
            case "int":
                return Integer.class;

            case "bigint":
                return Long.class;

            case "real":
            case "double precision":
            case "numeric":
            case "decimal":
                return Double.class;

            case "boolean":
                return Boolean.class;

            case "character varying":
            case "varchar":
            case "text":
            case "character":
            case "char":
                return String.class;

            default:
                /* Unsupported columns are simply not part of
                 * the schema; referencing one reports an
                 * unknown field. */
                return null;
        }
    }

    private void validateSortField(
            Sort sort,
            Map<String, Class<?>> schema
    ) {

        String field = sort.getField();

        if (!schema.containsKey(field)) {

            throw new PlugException(
                    "Unknown sort field: " + field
            );
        }

        Class<?> fieldType = schema.get(field);

        if (!Comparable.class.isAssignableFrom(fieldType)) {

            throw new PlugException(
                    "Field '" + field
                            + "' cannot be sorted."
            );
        }
    }

    private void bind(
            PreparedStatement statement,
            List<PostgresQueryCompiler.SqlParam> params
    ) throws SQLException {

        for (int i = 0;
                i < params.size();
                i++) {

            PostgresQueryCompiler.SqlParam param =
                    params.get(i);

            if (param.getValue() == null) {
                statement.setNull(
                        i + 1,
                        sqlType(param.getType())
                );
            } else {
                statement.setObject(
                        i + 1,
                        param.getValue()
                );
            }
        }
    }

    private int sqlType(Class<?> type) {

        if (type == Integer.class
                || type == Long.class) {
            return Types.INTEGER;
        }

        if (type == Double.class) {
            return Types.DOUBLE;
        }

        if (type == Boolean.class) {
            return Types.BOOLEAN;
        }

        return Types.VARCHAR;
    }

    /*
     * PostgreSQL JDBC values are normalized to the types
     * Plug scripts produce and the mock adapter returns.
     */
    private Object normalize(
            Object value,
            Class<?> expected
    ) {

        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal
                || value instanceof BigInteger) {

            Number number = (Number) value;

            if (expected == Long.class) {
                return number.longValue();
            }

            if (expected == Integer.class
                    || expected == null) {
                return number.intValue();
            }

            return number.doubleValue();
        }

        if (value instanceof Float) {
            return ((Float) value).doubleValue();
        }

        if (value instanceof Long
                && expected == Integer.class) {

            return ((Long) value).intValue();
        }

        return value;
    }

    private Object asInt(Object value) {

        if (value instanceof Integer) {
            return value;
        }

        if (value instanceof Long) {
            return (int) (long) (Long) value;
        }

        if (value instanceof BigDecimal
                || value instanceof BigInteger) {

            return ((Number) value).intValue();
        }

        return value;
    }

    private Object asNumber(Object value) {

        if (value instanceof Number) {
            return value;
        }

        if (value instanceof BigDecimal
                || value instanceof BigInteger) {

            return (Number) value;
        }

        return value;
    }

    private boolean isUniqueViolation(SQLException e) {

        SQLException current = e;

        while (current != null) {

            if ("23505".equals(current.getSQLState())) {
                return true;
            }

            current = current.getNextException();
        }

        return false;
    }

    private String statementError(
            ExecutionPlan plan,
            SQLException e
    ) {

        return plan.getOperation() + " failed: "
                + e.getMessage();
    }

    private void requireConnection() {

        if (connection == null) {

            throw new PlugException(
                    "No database connection."
            );
        }
    }

    private void requireIdentifier(String name) {

        if (name == null
                || !SAFE_IDENTIFIER
                .matcher(name)
                .matches()) {

            throw new PlugException(
                    "Invalid collection name: " + name
                            + ". Collection names map to SQL "
                            + "tables and may only use "
                            + "lowercase letters, digits and "
                            + "underscores."
            );
        }
    }
}
