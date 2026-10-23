package dev.victor.plug;

import dev.victor.plug.PlugException;
import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.NotExpression;
import dev.victor.plug.runtime.ExecutionPlan;
import dev.victor.plug.runtime.Join;
import dev.victor.plug.runtime.PostgresQueryCompiler;
import dev.victor.plug.runtime.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
 * Compiler unit tests: SQL text and bind parameters for
 * every statement shape, built with the same Query model
 * Runtime produces. No JDBC, no server — the pure
 * translation contract.
 */
public class PostgresQueryCompilerTest {

    private static List<String> list(String... items) {

        List<String> fields = new ArrayList<>();

        for (String item : items) {
            fields.add(item);
        }

        return fields;
    }

    private static List<dev.victor.plug.ast.Value> values(
            dev.victor.plug.ast.Value... items
    ) {

        List<dev.victor.plug.ast.Value> values =
                new ArrayList<>();

        for (dev.victor.plug.ast.Value item : items) {
            values.add(item);
        }

        return values;
    }

    private static Query readBase() {

        Query query = new Query(list("name", "balance"));

        query.where(
                "country",
                "EQ",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.STRING,
                        "Ghana"
                )
        );

        return query;
    }

    private static Map<String, Class<?>> usersSchema() {

        Map<String, Class<?>> schema = new LinkedHashMap<>();

        schema.put("id", Integer.class);
        schema.put("name", String.class);
        schema.put("age", Integer.class);
        schema.put("email", String.class);
        schema.put("balance", Integer.class);
        schema.put("active", Boolean.class);
        schema.put("country", String.class);
        schema.put("role", String.class);

        return schema;
    }

    private static Map<String, Class<?>> ordersSchema() {

        Map<String, Class<?>> schema = new LinkedHashMap<>();

        schema.put("id", Integer.class);
        schema.put("user_id", Integer.class);
        schema.put("product", String.class);
        schema.put("total", Integer.class);

        return schema;
    }

    private static Map<String, Class<?>> inventorySchema() {

        Map<String, Class<?>> schema = new LinkedHashMap<>();

        schema.put("id", Integer.class);
        schema.put("order_id", Integer.class);
        schema.put("product", String.class);
        schema.put("stock", Integer.class);

        return schema;
    }

    private static LinkedHashMap<String, Map<String, Class<?>>>
            usersOrdersSchemas() {

        LinkedHashMap<String, Map<String, Class<?>>> schemas =
                new LinkedHashMap<>();

        schemas.put("users", usersSchema());
        schemas.put("orders", ordersSchema());

        return schemas;
    }

    @Test
    public void simpleReadSelectsQuotedColumnsWithAliases() {

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileRead(
                        readBase(),
                        "users",
                        usersSchema()
                );

        assertEquals(
                "SELECT \"name\" AS \"name\", "
                        + "\"balance\" AS \"balance\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"country\" = ?)",
                compiled.getSql()
        );

        assertEquals(1, compiled.getParams().size());
        assertEquals(
                "Ghana",
                compiled.getParams().get(0).getValue()
        );
    }

    @Test
    public void flatChainFoldsLeftToRight() {

        /*
         * (a OR b) AND c — plug's left-to-right reading,
         * parenthesized so SQL cannot re-read it as
         * a OR (b AND c).
         */
        Query query = readBase();

        query.or(
                "age",
                "LT",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.NUMBER,
                        21
                )
        );

        query.and(
                "active",
                "EQ",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.BOOLEAN,
                        true
                )
        );

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileRead(
                        query,
                        "users",
                        usersSchema()
                );

        /*
         * The fold re-wraps its accumulated left side on
         * every step, so the canonical SQL for
         * (a OR b) AND c gains one outer paren level per
         * fold: (((a) OR (b)) AND (c)).
         */
        assertEquals(
                "SELECT \"name\" AS \"name\", "
                        + "\"balance\" AS \"balance\" "
                        + "FROM \"users\" "
                        + "WHERE (((\"users\".\"country\" = ?) "
                        + "OR (\"users\".\"age\" < ?)) "
                        + "AND (\"users\".\"active\" = ?))",
                compiled.getSql()
        );

        assertEquals(3, compiled.getParams().size());
    }

    @Test
    public void notExpressionGeneratesNotInSql() {

        Query query = new Query(list("name"));

        query.expression(new NotExpression(
                new ComparisonExpression(
                        "country",
                        "EQ",
                        new dev.victor.plug.ast.Value(
                                dev.victor.plug.ast.Value.Type.STRING,
                                "Ghana"
                        )
                )
        ));

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileRead(
                        query,
                        "users",
                        usersSchema()
                );

        /*
         * Walked comparison leaves come back self-wrapped,
         * so NOT adds the second pair: NOT ((c = ?)).
         */
        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE NOT ((\"users\".\"country\" = ?))",
                compiled.getSql()
        );
    }

    @Test
    public void conditionalOperatorsCompileToSql() {

        Query ne = new Query(list("name"));

        ne.where(
                "country",
                "NE",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.STRING,
                        "Ghana"
                )
        );

        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"country\" <> ?)",
                PostgresQueryCompiler.compileRead(
                        ne,
                        "users",
                        usersSchema()
                ).getSql()
        );

        Query in = new Query(list("name"));

        in.where(
                "country",
                "IN",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.LIST,
                        values(
                                new dev.victor.plug.ast.Value(
                                        dev.victor.plug.ast.Value.Type.STRING,
                                        "Kenya"
                                ),
                                new dev.victor.plug.ast.Value(
                                        dev.victor.plug.ast.Value.Type.STRING,
                                        "Nigeria"
                                )
                        )
                )
        );

        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"country\" IN (?, ?))",
                PostgresQueryCompiler.compileRead(
                        in,
                        "users",
                        usersSchema()
                ).getSql()
        );

        Query between = new Query(list("name"));

        between.where(
                "balance",
                "BETWEEN",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.LIST,
                        values(
                                new dev.victor.plug.ast.Value(
                                        dev.victor.plug.ast.Value.Type.NUMBER,
                                        1000
                                ),
                                new dev.victor.plug.ast.Value(
                                        dev.victor.plug.ast.Value.Type.NUMBER,
                                        5000
                                )
                        )
                )
        );

        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"balance\" BETWEEN ? AND ?)",
                PostgresQueryCompiler.compileRead(
                        between,
                        "users",
                        usersSchema()
                ).getSql()
        );

        Query isNull = new Query(list("name"));

        isNull.where(
                "email",
                "IS NULL",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.NULL,
                        null
                )
        );

        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"email\" IS NULL)",
                PostgresQueryCompiler.compileRead(
                        isNull,
                        "users",
                        usersSchema()
                ).getSql()
        );

        /*
         * SQL "balance = NULL" never matches, so EQ with a
         * null value must compile to IS NULL (mock parity).
         */
        Query eqNull = new Query(list("name"));

        eqNull.where(
                "balance",
                "EQ",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.NULL,
                        null
                )
        );

        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"balance\" IS NULL)",
                PostgresQueryCompiler.compileRead(
                        eqNull,
                        "users",
                        usersSchema()
                ).getSql()
        );

        /* CONTAINS escapes literal %, _ and \ so the
         * pattern matches substrings literally. */
        Query contains = new Query(list("name"));

        contains.where(
                "name",
                "CONTAINS",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.STRING,
                        "50%_off"
                )
        );

        PostgresQueryCompiler.CompiledSql containsCompiled =
                PostgresQueryCompiler.compileRead(
                        contains,
                        "users",
                        usersSchema()
                );

        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"name\" "
                        + "LIKE ? ESCAPE '\\')",
                containsCompiled.getSql()
        );

        assertEquals(
                "%50\\%\\_off%",
                containsCompiled.getParams().get(0).getValue()
        );

        Query like = new Query(list("name"));

        like.where(
                "name",
                "LIKE",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.STRING,
                        "V%"
                )
        );

        PostgresQueryCompiler.CompiledSql likeCompiled =
                PostgresQueryCompiler.compileRead(
                        like,
                        "users",
                        usersSchema()
                );

        assertEquals(
                "SELECT \"name\" AS \"name\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"name\" LIKE ?)",
                likeCompiled.getSql()
        );

        assertEquals(
                "V%",
                likeCompiled.getParams().get(0).getValue()
        );
    }

    @Test
    public void sortAndLimitCompileToOrderAndLimit() {

        Query query = readBase();

        query.sort("balance", "DESC");
        query.limit(3);

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileRead(
                        query,
                        "users",
                        usersSchema()
                );

        assertEquals(
                "SELECT \"name\" AS \"name\", "
                        + "\"balance\" AS \"balance\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"country\" = ?) "
                        + "ORDER BY \"users\".\"balance\" DESC "
                        + "LIMIT 3",
                compiled.getSql()
        );
    }

    @Test
    public void joinReadCompilesInnerJoinsWithQualifiedKeys() {

        Query query = readBase();

        query.join(new Join(
                "users.id",
                "orders.user_id",
                "orders"
        ));

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileJoinedRead(
                        query,
                        "users",
                        usersOrdersSchemas()
                );

        assertEquals(
                "SELECT \"users\".\"name\" AS \"name\", "
                        + "\"users\".\"balance\" AS \"balance\" "
                        + "FROM \"users\" "
                        + "INNER JOIN \"orders\" "
                        + "ON (\"users\".\"id\" = \"orders\".\"user_id\") "
                        + "WHERE (\"users\".\"country\" = ?)",
                compiled.getSql()
        );
    }

    @Test
    public void joinedProjectionResolvesBareNamesBaseFirst() {

        /*
         * "total" exists only in orders; bare "product"
         * exists in both but not in users — the base-first
         * rule then falls through to the first join,
         * matching the mock's merged-row rule.
         */
        Query query = new Query(
                list("name", "total", "product")
        );

        query.join(new Join(
                "orders.user_id",
                "users.id",
                "orders"
        ));

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileJoinedRead(
                        query,
                        "users",
                        usersOrdersSchemas()
                );

        assertEquals(
                "SELECT \"users\".\"name\" AS \"name\", "
                        + "\"orders\".\"total\" AS \"total\", "
                        + "\"orders\".\"product\" AS \"product\" "
                        + "FROM \"users\" "
                        + "INNER JOIN \"orders\" "
                        + "ON (\"orders\".\"user_id\" = \"users\".\"id\")",
                compiled.getSql()
        );
    }

    @Test
    public void doubleJoinChainsLeftToRight() {

        LinkedHashMap<String, Map<String, Class<?>>> schemas =
                usersOrdersSchemas();

        schemas.put("inventory", inventorySchema());

        Query query = new Query(list("name", "stock"));

        query.join(new Join(
                "users.id",
                "orders.user_id",
                "orders"
        ));

        query.join(new Join(
                "orders.id",
                "inventory.order_id",
                "inventory"
        ));

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileJoinedRead(
                        query,
                        "users",
                        schemas
                );

        assertEquals(
                "SELECT \"users\".\"name\" AS \"name\", "
                        + "\"inventory\".\"stock\" AS \"stock\" "
                        + "FROM \"users\" "
                        + "INNER JOIN \"orders\" "
                        + "ON (\"users\".\"id\" = \"orders\".\"user_id\") "
                        + "INNER JOIN \"inventory\" "
                        + "ON (\"orders\".\"id\" = \"inventory\".\"order_id\")",
                compiled.getSql()
        );
    }

    @Test
    public void aggregatesCompileToSingleValueSelects() {

        PostgresQueryCompiler.CompiledSql compiled =
                PostgresQueryCompiler.compileAggregate(
                        ExecutionPlan.Aggregate.SUM,
                        "balance",
                        readBase(),
                        "users",
                        usersSchema()
                );

        assertEquals(
                "SELECT SUM(\"balance\") AS \"aggregateValue\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"country\" = ?)",
                compiled.getSql()
        );

        PostgresQueryCompiler.CompiledSql countCompiled =
                PostgresQueryCompiler.compileAggregate(
                        ExecutionPlan.Aggregate.COUNT,
                        null,
                        readBase(),
                        "users",
                        usersSchema()
                );

        assertEquals(
                "SELECT COUNT(*) AS \"aggregateValue\" "
                        + "FROM \"users\" "
                        + "WHERE (\"users\".\"country\" = ?)",
                countCompiled.getSql()
        );
    }

    @Test
    public void writesCompileToInsertUpdateDelete() {

        Map<String, Object> data = new LinkedHashMap<>();

        data.put("name", "Victor");
        data.put("balance", 25000);

        PostgresQueryCompiler.CompiledSql insert =
                PostgresQueryCompiler.compileInsert(
                        "users",
                        data,
                        usersSchema()
                );

        assertEquals(
                "INSERT INTO \"users\" (\"name\", \"balance\") "
                        + "VALUES (?, ?)",
                insert.getSql()
        );

        assertEquals(2, insert.getParams().size());

        Query conditionQuery = new Query(list());

        conditionQuery.where(
                "id",
                "EQ",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.NUMBER,
                        1
                )
        );

        PostgresQueryCompiler.CompiledSql update =
                PostgresQueryCompiler.compileUpdate(
                        conditionQuery,
                        "users",
                        data,
                        usersSchema()
                );

        assertEquals(
                "UPDATE \"users\" "
                        + "SET \"name\" = ?, \"balance\" = ? "
                        + "WHERE (\"users\".\"id\" = ?)",
                update.getSql()
        );

        /* SET values bind before WHERE conditions. */
        assertEquals(3, update.getParams().size());
        assertEquals(
                "Victor",
                update.getParams().get(0).getValue()
        );
        assertEquals(
                1,
                update.getParams().get(2).getValue()
        );

        PostgresQueryCompiler.CompiledSql delete =
                PostgresQueryCompiler.compileDelete(
                        conditionQuery,
                        "users",
                        usersSchema()
                );

        assertEquals(
                "DELETE FROM \"users\" "
                        + "WHERE (\"users\".\"id\" = ?)",
                delete.getSql()
        );
    }

    @Test
    public void unknownProjectionFieldIsRejected() {

        Query query = new Query(list("nickname"));

        PlugException exception = assertThrows(
                PlugException.class,
                () -> PostgresQueryCompiler.compileRead(
                        query,
                        "users",
                        usersSchema()
                )
        );

        assertEquals(
                "Unknown field: nickname",
                exception.getMessage()
        );
    }

    @Test
    public void nullNumericComparisonIsRejected() {

        Query query = new Query(list("name"));

        query.where(
                "balance",
                "GT",
                new dev.victor.plug.ast.Value(
                        dev.victor.plug.ast.Value.Type.NULL,
                        null
                )
        );

        PlugException exception = assertThrows(
                PlugException.class,
                () -> PostgresQueryCompiler.compileRead(
                        query,
                        "users",
                        usersSchema()
                )
        );

        assertEquals(
                "Numeric comparison requires numeric values.",
                exception.getMessage()
        );
    }
}
