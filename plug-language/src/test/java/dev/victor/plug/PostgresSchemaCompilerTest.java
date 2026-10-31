package dev.victor.plug;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;
import dev.victor.plug.runtime.PostgresSchemaCompiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Compiler parity tests: the DDL definitions the language
 * accepts compile to exactly the SQL seen in this file. No
 * server is involved — like PostgresQueryCompilerTest this
 * runs in the plain unit suite.
 */
public class PostgresSchemaCompilerTest {

    private static TableDefinition.FieldDefinition field(
            String name,
            TableDefinition.FieldType type,
            TableDefinition.FieldConstraint... constraints
    ) {

        List<TableDefinition.FieldConstraint> list =
                new ArrayList<>();

        Value defaultValue = null;

        for (TableDefinition.FieldConstraint constraint :
                constraints) {

            list.add(constraint);

            if (constraint
                    == TableDefinition.FieldConstraint.DEFAULT) {

                defaultValue = defaultValueForField(type);
            }
        }

        return new TableDefinition.FieldDefinition(
                name,
                type,
                list,
                defaultValue
        );
    }

    private static Value defaultValueForField(
            TableDefinition.FieldType type
    ) {

        switch (type) {

            case INT:
                return new Value(Value.Type.NUMBER, 18);

            case DECIMAL:
                return new Value(Value.Type.NUMBER, 9.99);

            case STRING:
                return new Value(Value.Type.STRING, "Ghana");

            case BOOL:
            default:
                return new Value(Value.Type.BOOLEAN, true);
        }
    }

    @Test
    public void compilesTheReferenceDefinition() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(field("id",
                TableDefinition.FieldType.INT,
                TableDefinition.FieldConstraint.PRIMARY_KEY));

        fields.add(field("name",
                TableDefinition.FieldType.STRING,
                TableDefinition.FieldConstraint.REQUIRED));

        fields.add(field("email",
                TableDefinition.FieldType.STRING,
                TableDefinition.FieldConstraint.UNIQUE));

        fields.add(field("age",
                TableDefinition.FieldType.INT,
                TableDefinition.FieldConstraint.DEFAULT));

        fields.add(field("active",
                TableDefinition.FieldType.BOOL,
                TableDefinition.FieldConstraint.DEFAULT));

        assertEquals(
                "CREATE TABLE users ("
                        + "id serial PRIMARY KEY, "
                        + "name text NOT NULL, "
                        + "email text UNIQUE, "
                        + "age integer DEFAULT 18, "
                        + "active boolean DEFAULT true)",
                PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("users", fields)
                )
        );
    }

    @Test
    public void plainIntWithoutPrimaryKeyMapsToInteger() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(field("quantity",
                TableDefinition.FieldType.INT));

        assertEquals(
                "CREATE TABLE inventory (quantity integer)",
                PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("inventory", fields)
                )
        );
    }

    @Test
    public void decimalMapsToDoublePrecision() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(field("price",
                TableDefinition.FieldType.DECIMAL,
                TableDefinition.FieldConstraint.DEFAULT));

        assertEquals(
                "CREATE TABLE shop_items "
                        + "(price double precision DEFAULT 9.99)",
                PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("shop_items", fields)
                )
        );
    }

    @Test
    public void pkImpliesRequiredAndUniqueWithoutNoise() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(field("id",
                TableDefinition.FieldType.INT,
                TableDefinition.FieldConstraint.PRIMARY_KEY,
                TableDefinition.FieldConstraint.REQUIRED,
                TableDefinition.FieldConstraint.UNIQUE));

        assertEquals(
                "CREATE TABLE users (id serial PRIMARY KEY)",
                PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("users", fields)
                )
        );
    }

    @Test
    public void stringDefaultsEscapeSingleQuotes() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(new TableDefinition.FieldDefinition(
                "name",
                TableDefinition.FieldType.STRING,
                new ArrayList<>(
                        List.of(TableDefinition.FieldConstraint
                                .DEFAULT)
                ),
                new Value(Value.Type.STRING, "O'Brien")
        ));

        assertEquals(
                "CREATE TABLE users ("
                        + "name text DEFAULT 'O''Brien')",
                PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("users", fields)
                )
        );
    }

    @Test
    public void stringDefaultIsQuoted() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(field("country",
                TableDefinition.FieldType.STRING,
                TableDefinition.FieldConstraint.DEFAULT));

        assertEquals(
                "CREATE TABLE users ("
                        + "country text DEFAULT 'Ghana')",
                PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("users", fields)
                )
        );
    }

    @Test
    public void nonIntegerIntDefaultIsRejected() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(new TableDefinition.FieldDefinition(
                "age",
                TableDefinition.FieldType.INT,
                new ArrayList<>(
                        List.of(TableDefinition.FieldConstraint
                                .DEFAULT)
                ),
                new Value(Value.Type.NUMBER, 18.5)
        ));

        PlugException exception = assertThrows(
                PlugException.class,
                () -> PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("users", fields)
                )
        );

        assertTrue(
                exception.getMessage().contains(
                        "must be an integer"
                )
        );
    }

    @Test
    public void uppercaseIdentifierIsRejected() {

        List<TableDefinition.FieldDefinition> fields =
                new ArrayList<>();

        fields.add(field("userId",
                TableDefinition.FieldType.INT));

        PlugException exception = assertThrows(
                PlugException.class,
                () -> PostgresSchemaCompiler.compileCreateTable(
                        new TableDefinition("users", fields)
                )
        );

        assertTrue(
                exception.getMessage().contains(
                        "Invalid field name: userId"
                )
        );
    }

    @Test
    public void compilesCreateDatabase() {

        assertEquals(
                "CREATE DATABASE shop",
                PostgresSchemaCompiler.compileCreateDatabase(
                        "shop"
                )
        );
    }

    @Test
    public void unsafeDatabaseNameIsRejected() {

        assertThrows(
                PlugException.class,
                () -> PostgresSchemaCompiler
                        .compileCreateDatabase("Shop-DB")
        );
    }
}
