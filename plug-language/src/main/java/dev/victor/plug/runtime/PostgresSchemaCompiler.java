package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import dev.victor.plug.ast.Value;

import java.util.ArrayList;

import java.util.List;

/*
 * Compiles validated schema definitions into PostgreSQL DDL.
 *
 * Input is assumed to be a TableDefinition that passed
 * SchemaValidation, but the compiler validates again so it is
 * safe to call directly. Every collection and field name is a
 * checked lowercase identifier by then, so the emitted SQL is
 * built from trusted identifiers and typed literal defaults
 * only — script values never reach SQL as text.
 */
public class PostgresSchemaCompiler {

    private PostgresSchemaCompiler() {
    }

    /*
     * Type mapping: INT is a plain integer, or serial when the
     * column is the primary key (the mock and the adapter's
     * insert path both expect an auto-assigned id); DECIMAL is
     * double precision, matching the adapter's existing
     * runtime mapping; STRING is text; BOOL is boolean.
     */
    private static String columnType(
            TableDefinition.FieldDefinition field
    ) {

        switch (field.getType()) {

            case INT:
                return field.hasConstraint(
                        TableDefinition.FieldConstraint.PRIMARY_KEY
                )
                        ? "serial"
                        : "integer";

            case DECIMAL:
                return "double precision";

            case STRING:
                return "text";

            case BOOL:
                return "boolean";

            default:
                throw new PlugException(
                        "Unsupported field type: " + field.getType()
                );
        }
    }

    public static String compileCreateTable(
            TableDefinition definition
    ) {

        SchemaValidation.validate(definition);

        List<String> columns = new ArrayList<>();

        for (TableDefinition.FieldDefinition field :
                definition.getFields()) {

            columns.add(columnDefinition(field));
        }

        return "CREATE TABLE "
                + definition.getName()
                + " ("
                + String.join(", ", columns)
                + ")";
    }

    private static String columnDefinition(
            TableDefinition.FieldDefinition field
    ) {

        StringBuilder column = new StringBuilder(field.getName());

        column.append(' ').append(columnType(field));

        if (field.hasConstraint(
                TableDefinition.FieldConstraint.PRIMARY_KEY
        )) {

            /*
             * PRIMARY KEY already implies NOT NULL and UNIQUE;
             * a second UNIQUE clause would be redundant noise.
             */
            column.append(" PRIMARY KEY");

        } else {

            if (field.hasConstraint(
                    TableDefinition.FieldConstraint.REQUIRED
            )) {
                column.append(" NOT NULL");
            }

            if (field.hasConstraint(
                    TableDefinition.FieldConstraint.UNIQUE
            )) {
                column.append(" UNIQUE");
            }
        }

        if (field.hasConstraint(
                TableDefinition.FieldConstraint.DEFAULT
        )) {

            column.append(" DEFAULT ")
                    .append(defaultLiteral(field));
        }

        return column.toString();
    }

    /*
     * SchemaValidation guarantees the default is a literal of
     * the field's own type, so rendering is straightforward;
     * string defaults are the one value that needs escaping.
     */
    private static String defaultLiteral(
            TableDefinition.FieldDefinition field
    ) {

        Value value = field.getDefaultValue();

        Object content = value.getValue();

        switch (field.getType()) {

            case INT:
            case DECIMAL:
                return String.valueOf(content);

            case BOOL:
                return String.valueOf(content);

            case STRING:
                return "'" + ((String) content)
                        .replace("'", "''") + "'";

            default:
                throw new PlugException(
                        "Unsupported default type: "
                                + field.getType()
                );
        }
    }

    /*
     * CREATE DATABASE cannot run inside a transaction; the
     * adapter already executes it on a fresh autocommit
     * connection, so the statement is emitted as plain DDL.
     */
    public static String compileCreateDatabase(String name) {

        SchemaValidation.validateDatabaseName(name);

        return "CREATE DATABASE " + name;
    }
}
