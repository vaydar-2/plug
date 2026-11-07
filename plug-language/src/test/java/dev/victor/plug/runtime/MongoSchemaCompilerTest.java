package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;

import org.bson.Document;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoSchemaCompiler unit tests: the $jsonSchema validator and
 * unique index definitions, with no client or server involved.
 */
public class MongoSchemaCompilerTest {

    /* ---------------- builders ---------------- */

    private static TableDefinition.FieldDefinition field(
            String name,
            TableDefinition.FieldType type,
            TableDefinition.FieldConstraint... constraints
    ) {

        return new TableDefinition.FieldDefinition(
                name,
                type,
                Arrays.asList(constraints),
                null
        );
    }

    private static TableDefinition.FieldDefinition defaulted(
            String name,
            TableDefinition.FieldType type,
            Value value
    ) {

        return new TableDefinition.FieldDefinition(
                name,
                type,
                Arrays.asList(
                        TableDefinition.FieldConstraint.DEFAULT
                ),
                value
        );
    }

    private static TableDefinition definition(
            String name,
            TableDefinition.FieldDefinition... fields
    ) {

        return new TableDefinition(
                name,
                Arrays.asList(fields)
        );
    }

    private static Document jsonSchema(
            MongoSchemaCompiler.CompiledCollection compiled
    ) {
        return (Document) compiled.getValidator().get("$jsonSchema");
    }

    private static Document properties(
            MongoSchemaCompiler.CompiledCollection compiled
    ) {
        return (Document) jsonSchema(compiled).get("properties");
    }

    private static Document property(
            MongoSchemaCompiler.CompiledCollection compiled,
            String field
    ) {
        return (Document) properties(compiled).get(field);
    }

    /* ---------------- validator generation ---------------- */

    @Test
    void requiredFieldsMapToTheirBsonTypes() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint.PRIMARY_KEY
                                ),
                                field(
                                        "balance",
                                        TableDefinition.FieldType.DECIMAL,
                                        TableDefinition.FieldConstraint.REQUIRED
                                ),
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING,
                                        TableDefinition.FieldConstraint.REQUIRED
                                ),
                                field(
                                        "active",
                                        TableDefinition.FieldType.BOOL,
                                        TableDefinition.FieldConstraint.REQUIRED
                                )
                        )
                );

        assertEquals(
                new Document("bsonType", "int"),
                property(compiled, "id")
        );

        assertEquals(
                new Document("bsonType", "double"),
                property(compiled, "balance")
        );

        assertEquals(
                new Document("bsonType", "string"),
                property(compiled, "name")
        );

        assertEquals(
                new Document("bsonType", "bool"),
                property(compiled, "active")
        );
    }

    @Test
    void optionalFieldsAdmitNull() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "age",
                                        TableDefinition.FieldType.INT
                                ),
                                field(
                                        "balance",
                                        TableDefinition.FieldType.DECIMAL
                                ),
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                ),
                                field(
                                        "active",
                                        TableDefinition.FieldType.BOOL
                                )
                        )
                );

        assertEquals(
                Arrays.asList("int", "null"),
                property(compiled, "age").get("bsonType")
        );

        assertEquals(
                Arrays.asList("double", "null"),
                property(compiled, "balance").get("bsonType")
        );

        assertEquals(
                Arrays.asList("string", "null"),
                property(compiled, "name").get("bsonType")
        );

        assertEquals(
                Arrays.asList("bool", "null"),
                property(compiled, "active").get("bsonType")
        );
    }

    @Test
    void requiredArrayContainsRequiredAndPrimaryKeyFields() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint.PRIMARY_KEY
                                ),
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING,
                                        TableDefinition.FieldConstraint.REQUIRED
                                ),
                                field(
                                        "age",
                                        TableDefinition.FieldType.INT
                                )
                        )
                );

        assertEquals(
                Arrays.asList("id", "name"),
                jsonSchema(compiled).get("required")
        );
    }

    @Test
    void additionalPropertiesAreDisallowed() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                )
                        )
                );

        assertEquals(
                false,
                jsonSchema(compiled).get("additionalProperties")
        );

        assertEquals("object", jsonSchema(compiled).get("bsonType"));
    }

    @Test
    void idIsAdmittedAsAnObjectId() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                )
                        )
                );

        assertEquals(
                new Document("bsonType", "objectId"),
                property(compiled, "_id")
        );

        assertFalse(
                ((List<?>) jsonSchema(compiled).get("required"))
                        .contains("_id")
        );
    }

    @Test
    void declaredUnderscoreIdIsRejected() {

        PlugException error = assertThrows(
                PlugException.class,
                () -> MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "_id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint.PRIMARY_KEY
                                )
                        )
                )
        );

        assertTrue(error.getMessage().contains(
                "Field '_id' is reserved by MongoDB"
        ));
    }

    @Test
    void defaultsAreNotRepresentedInTheValidator() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                defaulted(
                                        "name",
                                        TableDefinition.FieldType.STRING,
                                        new Value(
                                                Value.Type.STRING,
                                                "unknown"
                                        )
                                )
                        )
                );

        assertEquals(
                Arrays.asList("string", "null"),
                property(compiled, "name").get("bsonType")
        );

        assertFalse(
                property(compiled, "name")
                        .containsKey("default")
        );

        assertFalse(
                compiled.getValidator()
                        .toJson()
                        .contains("default")
        );
    }

    @Test
    void validatorIsWrappedInJsonSchema() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                )
                        )
                );

        assertEquals(
                Collections.singleton("$jsonSchema"),
                compiled.getValidator().keySet()
        );

        assertTrue(compiled.getValidator().get("$jsonSchema")
                instanceof Document);
    }

    /* ---------------- indexes ---------------- */

    @Test
    void primaryKeyGetsAUniquePartialIndex() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint.PRIMARY_KEY
                                )
                        )
                );

        assertEquals(1, compiled.getIndexes().size());

        IndexModel index = compiled.getIndexes().get(0);
        IndexOptions options = index.getOptions();

        assertEquals(
                new Document("id", 1).toBsonDocument(),
                index.getKeys().toBsonDocument()
        );

        assertTrue(options.isUnique());
        assertEquals("id_unique", options.getName());

        assertEquals(
                new Document(
                        "id",
                        new Document(
                                "$type",
                                Arrays.asList("int", "long")
                        )
                ),
                options.getPartialFilterExpression()
        );
    }

    @Test
    void eachUniqueFieldGetsAnIndex() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "email",
                                        TableDefinition.FieldType.STRING,
                                        TableDefinition.FieldConstraint.UNIQUE
                                ),
                                field(
                                        "handle",
                                        TableDefinition.FieldType.STRING,
                                        TableDefinition.FieldConstraint.UNIQUE
                                ),
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                )
                        )
                );

        assertEquals(2, compiled.getIndexes().size());

        assertEquals(
                new Document("email", 1).toBsonDocument(),
                compiled.getIndexes().get(0)
                        .getKeys()
                        .toBsonDocument()
        );

        assertEquals(
                new Document("handle", 1).toBsonDocument(),
                compiled.getIndexes().get(1)
                        .getKeys()
                        .toBsonDocument()
        );
    }

    @Test
    void primaryKeyAndUniqueOnOneFieldCreateOneIndex() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint.PRIMARY_KEY,
                                        TableDefinition.FieldConstraint.UNIQUE
                                )
                        )
                );

        assertEquals(1, compiled.getIndexes().size());
    }

    @Test
    void optionalUniqueFieldsUseTypePartialFilters() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "score",
                                        TableDefinition.FieldType.DECIMAL,
                                        TableDefinition.FieldConstraint.UNIQUE
                                ),
                                field(
                                        "code",
                                        TableDefinition.FieldType.STRING,
                                        TableDefinition.FieldConstraint.UNIQUE
                                ),
                                field(
                                        "flag",
                                        TableDefinition.FieldType.BOOL,
                                        TableDefinition.FieldConstraint.UNIQUE
                                )
                        )
                );

        assertEquals(
                new Document(
                        "score",
                        new Document(
                                "$type",
                                Arrays.asList("double", "decimal")
                        )
                ),
                compiled.getIndexes().get(0)
                        .getOptions()
                        .getPartialFilterExpression()
        );

        assertEquals(
                new Document(
                        "code",
                        new Document("$type", "string")
                ),
                compiled.getIndexes().get(1)
                        .getOptions()
                        .getPartialFilterExpression()
        );

        assertEquals(
                new Document(
                        "flag",
                        new Document("$type", "bool")
                ),
                compiled.getIndexes().get(2)
                        .getOptions()
                        .getPartialFilterExpression()
        );
    }

    @Test
    void fieldsWithoutUniqueConstraintsGetNoIndex() {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING,
                                        TableDefinition.FieldConstraint.REQUIRED
                                )
                        )
                );

        assertTrue(compiled.getIndexes().isEmpty());
    }

    /* ---------------- invalid definitions ---------------- */

    @Test
    void nullDefinitionIsRejected() {

        assertEquals(
                "Table definition is required.",
                assertThrows(
                        PlugException.class,
                        () -> MongoSchemaCompiler
                                .compileCreateCollection(null)
                ).getMessage()
        );
    }

    @Test
    void invalidTableNameIsRejected() {

        assertThrows(
                PlugException.class,
                () -> MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "People",
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                )
                        )
                )
        );
    }

    @Test
    void emptyDefinitionIsRejected() {

        assertThrows(
                PlugException.class,
                () -> MongoSchemaCompiler.compileCreateCollection(
                        definition("people")
                )
        );
    }

    @Test
    void mismatchedDefaultIsRejected() {

        assertThrows(
                PlugException.class,
                () -> MongoSchemaCompiler.compileCreateCollection(
                        definition(
                                "people",
                                defaulted(
                                        "age",
                                        TableDefinition.FieldType.INT,
                                        new Value(
                                                Value.Type.STRING,
                                                "young"
                                        )
                                )
                        )
                )
        );
    }
}
