package dev.victor.plug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/*
 * End-to-end tests against a real PostgreSQL server, kept
 * out of the way of the default build: every test skips
 * unless the PLUG_PG_HOST environment variable points at a
 * reachable server (with PLUG_PG_PORT, PLUG_PG_DB,
 * PLUG_PG_USER, PLUG_PG_PASSWORD optionally overridden —
 * the database and its `users`, `orders` and `inventory`
 * tables with the mock's schemas must exist).
 *
 * Run them with, for example:
 *   PLUG_PG_HOST=localhost PLUG_PG_DB=plugdb \
 *   PLUG_PG_USER=victor PLUG_PG_PASSWORD=secret mvn test
 */
public class PostgresAdapterIntegrationTest {

    private static String host() {
        return env("PLUG_PG_HOST", "localhost");
    }

    private static int port() {
        String value = System.getenv("PLUG_PG_PORT");
        return value == null
                ? 5432
                : Integer.parseInt(value);
    }

    private static String database() {
        return env("PLUG_PG_DB", "plugdb");
    }

    private static String username() {
        return env("PLUG_PG_USER", "victor");
    }

    private static String password() {
        return env("PLUG_PG_PASSWORD", "secret");
    }

    private static String env(
            String name,
            String fallback
    ) {

        String value = System.getenv(name);

        return value == null ? fallback : value;
    }

    private void withServer() {

        assumeTrue(
                System.getenv("PLUG_PG_HOST") != null,
                "No PLUG_PG_HOST configured; integration "
                        + "tests skip silently"
        );
    }

    @Test
    public void readsSortLimitAndConditionsEndToEnd() {

        withServer();

        String output = runCaptured(
                connectScript()
                        + "plug.use(\"users\");\n"
                        + "plug.get(name, balance)"
                        + ".where((country, EQ, \"Ghana\"))"
                        + ".sort(balance, DESC).limit(3);"
        );

        assumeTrue(!output.contains("Cannot connect"),
                "Server unreachable; skipping");

        if (!output.contains("Records found: 3")) {
            throw new AssertionError(
                    "unexpected output: " + output
            );
        }
    }

    @Test
    public void aggregateEndToEnd() {

        withServer();

        String output = runCaptured(
                connectScript()
                        + "plug.use(\"users\");\n"
                        + "plug.get().sum(balance);"
        );

        assumeTrue(!output.contains("Cannot connect"),
                "Server unreachable; skipping");

        if (!output.contains("SUM(balance) = ")) {
            throw new AssertionError(
                    "unexpected output: " + output
            );
        }
    }

    @Test
    public void joinEndToEnd() {

        withServer();

        String output = runCaptured(
                connectScript()
                        + "plug.use(\"users\");\n"
                        + "plug.get(name, orders.total)"
                        + ".join(orders, on: (users.id, EQ, "
                        + "orders.user_id))"
                        + ".where((country, EQ, \"Ghana\"));"
        );

        assumeTrue(!output.contains("Cannot connect"),
                "Server unreachable; skipping");

        if (!output.contains("Records found: ")) {
            throw new AssertionError(
                    "unexpected output: " + output
            );
        }
    }

    /*
     * Create-table round trip: admin-cleanup before and after,
     * then lex/parse/validate/plan/compile against the live
     * server, followed by an insert that relies on the
     * column defaults and a read-back verifying them.
     */
    @Test
    public void createTableInsertAndReadEndToEnd() {

        withServer();

        dropTable("plug_ddl_items");

        try {

            String output = runCaptured(
                    connectScript()
                            + "plug.create.table(\"plug_ddl_items\") {\n"
                            + "    id: INT primary_key,\n"
                            + "    name: STRING required,\n"
                            + "    price: DECIMAL default 9.99,\n"
                            + "    active: BOOL default true\n"
                            + "};\n"
                            + "plug.use(\"plug_ddl_items\");\n"
                            + "plug.insert(name: \"Book\");\n"
                            + "plug.get(id, name, price, active);"
            );

            assumeTrue(!output.contains("Cannot connect"),
                    "Server unreachable; skipping");

            assertTrue(output.contains(
                    "Table created: plug_ddl_items"));

            assertTrue(output.contains(
                    "Records inserted: 1"));

            assertTrue(output.contains("price=9.99"));

            assertTrue(output.contains("active=true"));

        } finally {

            dropTable("plug_ddl_items");
        }
    }

    /*
     * create.database: runs on its own administrative
     * connection inside the adapter; cleanup drops the
     * database afterwards so reruns stay idempotent.
     */
    @Test
    public void createDatabaseEndToEnd() {

        withServer();

        dropDatabase("plug_ddl_db");

        try {

            String output = runCaptured(
                    connectScript()
                            + "plug.create.database(\"plug_ddl_db\");"
            );

            assumeTrue(!output.contains("Cannot connect"),
                    "Server unreachable; skipping");

            assertTrue(output.contains(
                    "Database created: plug_ddl_db"));

        } finally {

            dropDatabase("plug_ddl_db");
        }
    }

    private static void dropTable(String table) {

        runAdminStatement(
                "DROP TABLE IF EXISTS " + table
        );
    }

    private static void dropDatabase(String database) {

        runAdminStatement(
                "DROP DATABASE IF EXISTS " + database
        );
    }

    private static void runAdminStatement(String sql) {

        try (java.sql.Connection connection =
                     java.sql.DriverManager.getConnection(
                             "jdbc:postgresql://"
                                     + host()
                                     + ":"
                                     + port()
                                     + "/postgres",
                             username(),
                             password()
                     );
             java.sql.Statement statement =
                     connection.createStatement()) {

            statement.execute(sql);

        } catch (java.sql.SQLException e) {

            if ("3D000".equals(e.getSQLState())) {
                return; // database dropped already
            }

            throw new AssertionError(
                    "cleanup failed: " + e.getMessage(), e
            );
        }
    }

    private static String connectScript() {

        return "plug.connect(driver: \"postgres\", "
                + "host: \"" + host() + "\", "
                + "port: " + port() + ", "
                + "database: \"" + database() + "\", "
                + "username: \"" + username() + "\", "
                + "password: \"" + password() + "\");\n";
    }

    private String runCaptured(String source) {

        java.io.PrintStream originalOut = System.out;

        java.io.ByteArrayOutputStream buffer =
                new java.io.ByteArrayOutputStream();

        System.setOut(new java.io.PrintStream(
                buffer,
                true,
                java.nio.charset.StandardCharsets.UTF_8
        ));

        try {

            dev.victor.plug.ast.Program program =
                    new dev.victor.plug.parser.Parser(
                            new dev.victor.plug.lexer.Lexer(
                                    source
                            ).tokenize()
                    ).parse();

            new dev.victor.plug.runtime.Runtime()
                    .execute(program);

        } finally {

            System.out.flush();
            System.setOut(originalOut);
        }

        return buffer.toString(
                java.nio.charset.StandardCharsets.UTF_8
        );
    }
}
