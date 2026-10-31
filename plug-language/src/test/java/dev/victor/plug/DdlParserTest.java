package dev.victor.plug;

import dev.victor.plug.ast.Call;
import dev.victor.plug.ast.Program;
import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;
import dev.victor.plug.lexer.Lexer;
import dev.victor.plug.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Parser tests for DDL shapes. Everything structural is
 * verified on the typed AST itself: field order, types,
 * constraints, and defaults — never against source text.
 */
public class DdlParserTest {

    private static Call firstCall(String source) {

        Program program = new Parser(
                new Lexer(source).tokenize()
        ).parse();

        return program
                .getStatements()
                .get(0)
                .getCalls()
                .get(0);
    }

    private static PlugException parseFailing(String source) {

        return assertThrows(
                PlugException.class,
                () -> new Parser(
                        new Lexer(source).tokenize()
                ).parse()
        );
    }

    @Test
    public void parsesDefinitionIntoTypedAst() {

        Call call = firstCall(
                "plug.create.table(\"users\") {\n"
                        + "    id: INT primary_key,\n"
                        + "    name: STRING required,\n"
                        + "    email: STRING unique,\n"
                        + "    age: INT default 18,\n"
                        + "    active: BOOL default true\n"
                        + "};"
        );

        assertEquals("plug.create", call.getObject());

        assertEquals("table", call.getMethod());

        TableDefinition definition = call.getTableDefinition();

        assertEquals("users", definition.getName());

        assertEquals(
                5,
                definition.getFields().size()
        );

        TableDefinition.FieldDefinition id =
                definition.getFields().get(0);

        assertEquals("id", id.getName());

        assertEquals(
                TableDefinition.FieldType.INT,
                id.getType()
        );

        assertTrue(id.hasConstraint(
                TableDefinition.FieldConstraint.PRIMARY_KEY
        ));

        TableDefinition.FieldDefinition name =
                definition.getFields().get(1);

        assertEquals("name", name.getName());

        assertEquals(
                TableDefinition.FieldType.STRING,
                name.getType()
        );

        assertTrue(name.hasConstraint(
                TableDefinition.FieldConstraint.REQUIRED
        ));

        TableDefinition.FieldDefinition email =
                definition.getFields().get(2);

        assertTrue(email.hasConstraint(
                TableDefinition.FieldConstraint.UNIQUE
        ));

        TableDefinition.FieldDefinition age =
                definition.getFields().get(3);

        assertNull(
                name.getDefaultValue()
        );

        assertEquals(
                TableDefinition.FieldConstraint.DEFAULT,
                age.getConstraints().get(0)
        );

        assertEquals(
                Value.Type.NUMBER,
                age.getDefaultValue().getType()
        );

        assertEquals(18, age.getDefaultValue().getValue());

        TableDefinition.FieldDefinition active =
                definition.getFields().get(4);

        assertEquals(
                Value.Type.BOOLEAN,
                active.getDefaultValue().getType()
        );

        assertEquals(
                true,
                active.getDefaultValue().getValue()
        );
    }

    @Test
    public void parsesStringDefault() {

        Call call = firstCall(
                "plug.create.table(\"users\") { "
                        + "country: STRING default \"Ghana\" };"
        );

        TableDefinition definition = call.getTableDefinition();

        assertEquals(
                "Ghana",
                definition.getFields()
                        .get(0)
                        .getDefaultValue()
                        .getValue()
        );
    }

    @Test
    public void parsesCreateDatabase() {

        Call call = firstCall(
                "plug.create.database(\"shop\");"
        );

        assertEquals("plug.create", call.getObject());

        assertEquals("database", call.getMethod());

        assertNull(call.getTableDefinition());

        assertEquals(
                "shop",
                call.getArguments().get(0).getValue().getValue()
        );
    }

    @Test
    public void typeNamesAreCaseInsensitive() {

        Call call = firstCall(
                "plug.create.table(\"users\") { age: int };"
        );

        assertEquals(
                TableDefinition.FieldType.INT,
                call.getTableDefinition()
                        .getFields()
                        .get(0)
                        .getType()
        );
    }

    @Test
    public void unknownTypeIsRejectedExplicitly() {

        PlugException exception = parseFailing(
                "plug.create.table(\"users\") { "
                        + "place: DOUBLE };"
        );

        assertTrue(
                exception.getMessage().contains(
                        "Unsupported type: DOUBLE"
                )
        );
    }

    @Test
    public void unknownConstraintIsRejectedExplicitly() {

        PlugException exception = parseFailing(
                "plug.create.table(\"users\") { "
                        + "email: STRING unique indexed };"
        );

        assertTrue(
                exception.getMessage().contains(
                        "Unsupported constraint: indexed"
                )
        );
    }

    @Test
    public void duplicateConstraintIsRejected() {

        PlugException exception = parseFailing(
                "plug.create.table(\"users\") { "
                        + "id: INT required required };"
        );

        assertTrue(
                exception.getMessage().contains(
                        "Duplicate constraint 'required'"
                )
        );
    }

    @Test
    public void duplicateFieldIsRejected() {

        PlugException exception = parseFailing(
                "plug.create.table(\"users\") { "
                        + "id: INT primary_key, "
                        + "id: STRING unique };"
        );

        assertTrue(
                exception.getMessage().contains(
                        "Duplicate field in table definition: id"
                )
        );
    }

    @Test
    public void emptyBlockIsRejected() {

        PlugException exception = parseFailing(
                "plug.create.table(\"users\") { };"
        );

        assertTrue(
                exception.getMessage().contains(
                        "Table must define at least one field."
                )
        );
    }

    @Test
    public void missingBraceIsRejected() {

        PlugException exception = parseFailing(
                "plug.create.table(\"users\") ( id: INT );"
        );

        assertTrue(
                exception.getMessage().contains(
                        "Expected '{' with column definitions"
                )
        );
    }

    @Test
    public void missingColumnNameArgIsRejected() {

        PlugException exception = parseFailing(
                "plug.create.table(5) { id: INT };"
        );

        assertTrue(
                exception.getMessage().contains(
                        "plug.create.table requires a table name"
                )
        );
    }

    @Test
    public void defaultWithoutSeparatorIsStillOneField() {

        Call call = firstCall(
                "plug.create.table(\"users\") { "
                        + "age: INT default 18 required };"
        );

        TableDefinition.FieldDefinition age = call
                .getTableDefinition()
                .getFields()
                .get(0);

        assertTrue(age.hasConstraint(
                TableDefinition.FieldConstraint.REQUIRED
        ));

        assertTrue(age.hasConstraint(
                TableDefinition.FieldConstraint.DEFAULT
        ));
    }
}
