package dev.victor.plug;

import dev.victor.plug.ast.Program;
import dev.victor.plug.lexer.Lexer;
import dev.victor.plug.parser.Parser;
import dev.victor.plug.runtime.MockData;
import dev.victor.plug.runtime.Runtime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * End-to-end DDL tests: create.table/create.database flow
 * through lex, parse, validation, planning, and the mock
 * adapter, with output captured. Created tables are removed
 * afterwards because MockData is shared across the suite.
 */
public class DdlRuntimeTest {

    private static final String TABLE = "shop_items";

    private static final String connect =
            "plug.connect(driver: \"mock\", host: \"localhost\", "
                    + "port: 5432, database: \"mydb\", "
                    + "username: \"victor\", password: \"secret\");\n";

    @BeforeEach
    public void prepareCleanTable() {

        removeTestTable();
    }

    @AfterEach
    public void removeTestTableAndDefaults() {

        removeTestTable();

        MockData.DEFAULTS.remove(TABLE);
    }

    private void removeTestTable() {

        MockData.SCHEMAS.remove(TABLE);
        MockData.DEFAULTS.remove(TABLE);
        MockData.DATA.remove(TABLE);
    }

    private String runCaptured(String source) {

        PrintStream originalOut = System.out;

        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream();

        System.setOut(new PrintStream(
                buffer,
                true,
                StandardCharsets.UTF_8
        ));

        try {

            Program program = new Parser(
                    new Lexer(source).tokenize()
            ).parse();

            new Runtime().execute(program);

        } finally {

            System.out.flush();
            System.setOut(originalOut);
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void createTableInsertAndReadAppliesDefaults() {

        String output = runCaptured(
                connect
                        + "plug.create.table(\"" + TABLE + "\") {\n"
                        + "    id: INT primary_key,\n"
                        + "    name: STRING required,\n"
                        + "    price: DECIMAL default 9.99,\n"
                        + "    active: BOOL default true\n"
                        + "};\n"
                        + "plug.use(\"" + TABLE + "\");\n"
                        + "plug.insert(name: \"Book\");\n"
                        + "plug.get(id, name, price, active);"
        );

        assertTrue(
                output.contains(
                        "Table created: " + TABLE
                )
        );

        assertTrue(output.contains("Records inserted: 1"));

        /*
         * Rows are unordered maps; assert each value instead
         * of one exact string.
         */
        assertTrue(output.contains("name=Book"));
        assertTrue(output.contains("id=1"));
        assertTrue(output.contains("price=9.99"));
        assertTrue(output.contains("active=true"));
    }

    @Test
    public void decimalDefaultAcceptsIntegerLiteral() {

        String output = runCaptured(
                connect
                        + "plug.create.table(\"" + TABLE + "\") { "
                        + "id: INT primary_key, price: DECIMAL "
                        + "default 10 };\n"
                        + "plug.use(\"" + TABLE + "\");\n"
                        + "plug.insert(id: 3);\n"
                        + "plug.get(price).where((id, EQ, 3));"
        );

        assertTrue(
                output.contains("{price=10.0}")
        );
    }

    @Test
    public void createdTableWorksWithFilterAndAggregate() {

        String output = runCaptured(
                connect
                        + "plug.create.table(\"" + TABLE + "\") { "
                        + "id: INT primary_key, name: STRING "
                        + "required, price: DECIMAL };\n"
                        + "plug.use(\"" + TABLE + "\");\n"
                        + "plug.insert(id: 1, name: \"A\", "
                        + "price: 5.5);\n"
                        + "plug.insert(id: 2, name: \"B\", "
                        + "price: 15.5);\n"
                        + "plug.get(name).where((price, GTE, 10.0));\n"
                        + "plug.get().sum(price);"
        );

        assertTrue(
                output.contains("{name=B}")
        );

        assertTrue(
                output.contains("SUM(price) = 21.0")
        );
    }

    @Test
    public void duplicateCreateIsRejectedExplicitly() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> runCaptured(
                        connect
                                + "plug.create.table(\"" + TABLE
                                + "\") { id: INT primary_key };\n"
                                + "plug.create.table(\"" + TABLE
                                + "\") { id: INT primary_key };"
                )
        );

        assertEquals(
                "Collection already exists: " + TABLE,
                exception.getMessage()
        );
    }

    @Test
    public void createDatabaseOnMockSucceeds() {

        String output = runCaptured(
                connect
                        + "plug.create.database(\"shop\");"
        );

        assertTrue(
                output.contains("Database created: shop")
        );
    }

    @Test
    public void createNeedsConnection() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> runCaptured(
                        "plug.create.table(\"" + TABLE
                                + "\") { id: INT primary_key };"
                )
        );

        assertEquals(
                "No database connection. Use plug.connect() first.",
                exception.getMessage()
        );
    }

    @Test
    public void uppercaseCollectionNameIsRejected() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> runCaptured(
                        connect
                                + "plug.create.table(\"Users\") { "
                                + "id: INT primary_key };"
                )
        );

        assertEquals(
                "Invalid table name: Users. Identifiers may only "
                        + "use lowercase letters, digits and "
                        + "underscores.",
                exception.getMessage()
        );
    }

    @Test
    public void intDefaultMustBeAnIntegerLiteral() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> runCaptured(
                        connect
                                + "plug.create.table(\"" + TABLE
                                + "\") { age: INT default \"young\" };"
                )
        );

        assertEquals(
                "Default for field 'age' must be an integer: "
                        + "\"young\"",
                exception.getMessage()
        );
    }

    @Test
    public void stringDefaultMustBeAStringLiteral() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> runCaptured(
                        connect
                                + "plug.create.table(\"" + TABLE
                                + "\") { name: STRING default 18 };"
                )
        );

        assertEquals(
                "Default for field 'name' must be a literal of "
                        + "type a string: 18",
                exception.getMessage()
        );
    }

    @Test
    public void boolDefaultMustBeABooleanLiteral() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> runCaptured(
                        connect
                                + "plug.create.table(\"" + TABLE
                                + "\") { active: BOOL default \"yes\" };"
                )
        );

        assertEquals(
                "Default for field 'active' must be a literal of "
                        + "type true or false: \"yes\"",
                exception.getMessage()
        );
    }

    @Test
    public void ddlCannotBeChainedWithQueryCalls() {

        PlugException exception = assertThrows(
                PlugException.class,
                () -> runCaptured(
                        connect
                                + "plug.create.table(\"" + TABLE
                                + "\") { id: INT primary_key }"
                                + ".where((id, EQ, 1));"
                )
        );

        assertEquals(
                "where() must follow get(), update(), or delete().",
                exception.getMessage()
        );
    }
}
