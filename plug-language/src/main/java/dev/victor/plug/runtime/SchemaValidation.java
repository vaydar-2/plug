package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import dev.victor.plug.ast.Value;

import java.util.List;

import java.util.regex.Pattern;

/*
 * Semantic validation for schema definitions, shared by the
 * mock and PostgreSQL adapters. The parser rejects unknown
 * types, unknown constraints, and duplicate names; this
 * class accepts an already-well-formed TableDefinition and
 * enforces the value-level rules both backends agree on:
 * safe identifiers and defaults that are literals matching
 * the field's own type.
 */
public class SchemaValidation {

    /*
     * Collection and field names become table and column
     * identifiers. Both adapters use the same unquoted
     * lowercase convention (PostgreSQL's), so one rule is
     * enforced once, here.
     */
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[a-z_][a-z0-9_]*");

    private SchemaValidation() {
    }

    public static void validate(TableDefinition definition) {

        requireSafeIdentifier(
                definition.getName(),
                "Table"
        );

        if (definition.getFields().isEmpty()) {

            throw new PlugException(
                    "Table must define at least one field."
            );
        }

        for (TableDefinition.FieldDefinition field :
                definition.getFields()) {

            validateField(field);
        }
    }

    public static void validateDatabaseName(String name) {

        requireSafeIdentifier(name, "Database");
    }

    private static void validateField(
            TableDefinition.FieldDefinition field
    ) {

        requireSafeIdentifier(field.getName(), "Field");

        if (field.hasConstraint(
                TableDefinition.FieldConstraint.DEFAULT
        )) {

            validateDefault(field);
        }
    }

    /*
     * Defaults are literals of the field's own type. Lists,
     * identifiers, and null are rejected explicitly — no
     * adapter has to decide what a null default means.
     */
    private static void validateDefault(
            TableDefinition.FieldDefinition field
    ) {

        Value value = field.getDefaultValue();

        if (value == null) {

            throw new PlugException(
                    "Field '" + field.getName()
                            + "' declares default without a value."
            );
        }

        switch (field.getType()) {

            case INT:
                if (!(value.getValue() instanceof Integer)) {

                    throw new PlugException(
                            "Default for field '" + field.getName()
                                    + "' must be an integer: "
                                    + describeLiteral(value)
                    );
                }

                break;

            case DECIMAL:
                requireLiteral(field, value, Value.Type.NUMBER,
                        "a number");

                break;

            case STRING:
                requireLiteral(field, value, Value.Type.STRING,
                        "a string");

                break;

            case BOOL:
                requireLiteral(field, value, Value.Type.BOOLEAN,
                        "true or false");

                break;

            default:

                throw new PlugException(
                        "Field '" + field.getName()
                                + "' has an unsupported type."
                );
        }
    }

    /*
     * Named literal for the message: strings keep their
     * quotes, everything else renders as itself.
     */
    private static String describeLiteral(Value value) {

        if (value.getType() == Value.Type.STRING) {
            return "\"" + value.getValue() + "\"";
        }

        return String.valueOf(value.getValue());
    }

    private static void requireLiteral(
            TableDefinition.FieldDefinition field,
            Value value,
            Value.Type expected,
            String description
    ) {

        if (value.getType() != expected) {

            throw new PlugException(
                    "Default for field '" + field.getName()
                            + "' must be a literal of type "
                            + description + ": "
                            + describeLiteral(value)
            );
        }
    }

    private static void requireSafeIdentifier(
            String name,
            String kind
    ) {

        if (name == null
                || !SAFE_IDENTIFIER
                .matcher(name)
                .matches()) {

            throw new PlugException(
                    "Invalid " + kind.toLowerCase() + " name: "
                            + name + ". Identifiers may only use "
                            + "lowercase letters, digits and "
                            + "underscores."
            );
        }
    }
}
