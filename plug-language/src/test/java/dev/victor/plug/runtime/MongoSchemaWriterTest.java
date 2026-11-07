package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoSchemaWriter unit tests.
 *
 * Execution runs against a recording admin and a resolver with
 * an empty collection source, so collection creation, index
 * building, rollback and registration are all covered without
 * a MongoDB server.
 */
public class MongoSchemaWriterTest {

    /* ---------------- test doubles ---------------- */

    private static final class RecordingAdmin
            implements MongoSchemaWriter.Admin {

        private final Set<String> existing = new LinkedHashSet<>();

        private final List<String> created = new ArrayList<>();
        private final List<CreateCollectionOptions> createOptions =
                new ArrayList<>();
        private final List<IndexModel> indexes = new ArrayList<>();
        private final List<String> dropped = new ArrayList<>();

        private RuntimeException existsFailure;
        private RuntimeException createFailure;
        private RuntimeException indexFailure;
        private RuntimeException dropFailure;

        @Override
        public boolean collectionExists(String collection) {

            if (existsFailure != null) {
                throw existsFailure;
            }

            return existing.contains(collection);
        }

        @Override
        public void createCollection(
                String collection,
                CreateCollectionOptions options
        ) {

            if (createFailure != null) {
                throw createFailure;
            }

            created.add(collection);
            createOptions.add(options);
        }

        @Override
        public void createIndex(
                String collection,
                IndexModel index
        ) {

            if (indexFailure != null) {
                throw indexFailure;
            }

            indexes.add(index);
        }

        @Override
        public void dropCollection(String collection) {

            if (dropFailure != null) {
                throw dropFailure;
            }

            dropped.add(collection);
        }
    }

    private static final class EmptySource
            implements MongoSchemaResolver.CollectionSource {

        @Override
        public List<Document> collectionMetadata(
                String collection
        ) {
            return Collections.emptyList();
        }

        @Override
        public List<Document> sample(
                String collection,
                int limit
        ) {
            return Collections.emptyList();
        }
    }

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

    private static TableDefinition people() {

        return definition(
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
                        "email",
                        TableDefinition.FieldType.STRING,
                        TableDefinition.FieldConstraint.UNIQUE
                )
        );
    }

    private static MongoSchemaResolver resolver() {
        return new MongoSchemaResolver(new EmptySource());
    }

    private static MongoSchemaWriter writer(
            RecordingAdmin admin,
            MongoSchemaResolver resolver
    ) {
        return new MongoSchemaWriter(admin, resolver);
    }

    private static void assertNotRegistered(
            MongoSchemaResolver resolver,
            String collection
    ) {

        PlugException error = assertThrows(
                PlugException.class,
                () -> resolver.resolve(collection)
        );

        assertEquals(
                "Unknown collection: " + collection,
                error.getMessage()
        );
    }

    /* ---------------- collection creation ---------------- */

    @Test
    void createsTheNamedCollectionWithItsValidator() {

        RecordingAdmin admin = new RecordingAdmin();
        MongoSchemaResolver resolver = resolver();
        TableDefinition definition = people();

        QueryResult result = writer(admin, resolver)
                .createTable(definition);

        assertEquals(Arrays.asList("people"), admin.created);

        assertEquals(0, result.getCount());
        assertTrue(result.getRows().isEmpty());

        CreateCollectionOptions options =
                admin.createOptions.get(0);

        assertEquals(
                MongoSchemaCompiler
                        .compileCreateCollection(definition)
                        .getValidator(),
                options.getValidationOptions().getValidator()
        );

        assertEquals(
                ValidationLevel.STRICT,
                options.getValidationOptions()
                        .getValidationLevel()
        );

        assertEquals(
                ValidationAction.ERROR,
                options.getValidationOptions()
                        .getValidationAction()
        );
    }

    @Test
    void existingCollectionIsNotOverwritten() {

        RecordingAdmin admin = new RecordingAdmin();
        admin.existing.add("people");

        MongoSchemaResolver resolver = resolver();

        PlugException error = assertThrows(
                PlugException.class,
                () -> writer(admin, resolver)
                        .createTable(people())
        );

        assertEquals(
                "Collection already exists: people",
                error.getMessage()
        );

        assertTrue(admin.created.isEmpty());
        assertTrue(admin.indexes.isEmpty());
        assertNotRegistered(resolver, "people");
    }

    @Test
    void namespaceExistsRaceUsesTheSameMessage() {

        RecordingAdmin admin = new RecordingAdmin();

        admin.createFailure = new MongoCommandException(
                new BsonDocument(
                        "code",
                        new BsonInt32(48)
                ).append(
                        "errmsg",
                        new BsonString("collection already exists")
                ),
                new ServerAddress()
        );

        MongoSchemaResolver resolver = resolver();

        PlugException error = assertThrows(
                PlugException.class,
                () -> writer(admin, resolver)
                        .createTable(people())
        );

        assertEquals(
                "Collection already exists: people",
                error.getMessage()
        );

        assertNotRegistered(resolver, "people");
    }

    @Test
    void collectionCreationFailureIsTranslated() {

        RecordingAdmin admin = new RecordingAdmin();
        admin.createFailure = new RuntimeException("not authorized");

        MongoSchemaResolver resolver = resolver();

        PlugException error = assertThrows(
                PlugException.class,
                () -> writer(admin, resolver)
                        .createTable(people())
        );

        assertEquals(
                "CREATE TABLE failed: not authorized",
                error.getMessage()
        );

        assertTrue(admin.indexes.isEmpty());
        assertNotRegistered(resolver, "people");
    }

    @Test
    void adminLookupFailureIsTranslated() {

        RecordingAdmin admin = new RecordingAdmin();
        admin.existsFailure =
                new RuntimeException("connection lost");

        PlugException error = assertThrows(
                PlugException.class,
                () -> writer(admin, resolver())
                        .createTable(people())
        );

        assertEquals(
                "Cannot inspect MongoDB collection 'people': "
                        + "connection lost",
                error.getMessage()
        );
    }

    /* ---------------- indexes ---------------- */

    @Test
    void uniqueIndexesAreCreatedInDefinitionOrder() {

        RecordingAdmin admin = new RecordingAdmin();

        writer(admin, resolver()).createTable(people());

        assertEquals(2, admin.indexes.size());

        assertEquals(
                new Document("id", 1).toBsonDocument(),
                admin.indexes.get(0)
                        .getKeys()
                        .toBsonDocument()
        );

        assertEquals(
                new Document("email", 1).toBsonDocument(),
                admin.indexes.get(1)
                        .getKeys()
                        .toBsonDocument()
        );

        assertTrue(admin.indexes.get(0).getOptions().isUnique());
        assertTrue(admin.indexes.get(1).getOptions().isUnique());
    }

    @Test
    void indexFailureDropsTheNewCollectionAndDoesNotRegister() {

        RecordingAdmin admin = new RecordingAdmin();
        admin.indexFailure =
                new RuntimeException("index build failed");

        MongoSchemaResolver resolver = resolver();

        PlugException error = assertThrows(
                PlugException.class,
                () -> writer(admin, resolver)
                        .createTable(people())
        );

        assertEquals(
                "CREATE TABLE failed while creating unique "
                        + "indexes for 'people': index build failed",
                error.getMessage()
        );

        assertEquals(Arrays.asList("people"), admin.dropped);
        assertNotRegistered(resolver, "people");
    }

    @Test
    void duplicateKeyFailureIsTranslated() {

        RecordingAdmin admin = new RecordingAdmin();

        admin.indexFailure = new MongoWriteException(
                new WriteError(
                        11000,
                        "E11000 duplicate key error",
                        new BsonDocument()
                ),
                new ServerAddress(),
                Collections.emptyList()
        );

        MongoSchemaResolver resolver = resolver();

        PlugException error = assertThrows(
                PlugException.class,
                () -> writer(admin, resolver)
                        .createTable(people())
        );

        assertTrue(error.getMessage().contains(
                "Duplicate value violates a unique constraint "
                        + "on collection 'people'"
        ));

        assertEquals(Arrays.asList("people"), admin.dropped);
        assertNotRegistered(resolver, "people");
    }

    @Test
    void failedCleanupIsReported() {

        RecordingAdmin admin = new RecordingAdmin();
        admin.indexFailure = new RuntimeException("index boom");
        admin.dropFailure = new RuntimeException("drop boom");

        PlugException error = assertThrows(
                PlugException.class,
                () -> writer(admin, resolver())
                        .createTable(people())
        );

        assertTrue(error.getMessage().endsWith(
                "; cleanup also failed: drop boom"
        ));
    }

    /* ---------------- defaults and registration ---------------- */

    @Test
    void successfulCreationRegistersTheDeclaredSchema() {

        RecordingAdmin admin = new RecordingAdmin();
        MongoSchemaResolver resolver = resolver();

        writer(admin, resolver).createTable(people());

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                resolved.getOrigin()
        );

        assertEquals(
                Integer.class,
                resolved.getFields().get("id")
        );

        assertEquals(
                String.class,
                resolved.getFields().get("email")
        );

        assertEquals(
                new java.util.LinkedHashSet<>(
                        Arrays.asList("id", "name")
                ),
                resolved.getRequiredFields()
        );
    }

    @Test
    void declaredDefaultsRemainAvailableAfterCreation() {

        RecordingAdmin admin = new RecordingAdmin();
        MongoSchemaResolver resolver = resolver();

        TableDefinition definition = definition(
                "people",
                field(
                        "id",
                        TableDefinition.FieldType.INT,
                        TableDefinition.FieldConstraint.PRIMARY_KEY
                ),
                defaulted(
                        "name",
                        TableDefinition.FieldType.STRING,
                        new Value(Value.Type.STRING, "unknown")
                )
        );

        writer(admin, resolver).createTable(definition);

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve("people");

        assertEquals(
                "unknown",
                resolved.getDefinition()
                        .getFields()
                        .get(1)
                        .getDefaultValue()
                        .getValue()
        );

        assertTrue(
                resolved.getDefinition()
                        .getFields()
                        .get(1)
                        .hasConstraint(
                                TableDefinition.FieldConstraint.DEFAULT
                        )
        );
    }

    @Test
    void invalidDefinitionTouchesNoServerAndRegistersNothing() {

        RecordingAdmin admin = new RecordingAdmin();
        MongoSchemaResolver resolver = resolver();

        assertThrows(
                PlugException.class,
                () -> writer(admin, resolver)
                        .createTable(
                                definition(
                                        "People",
                                        field(
                                                "name",
                                                TableDefinition.FieldType.STRING
                                        )
                                )
                        )
        );

        assertTrue(admin.created.isEmpty());
        assertTrue(admin.indexes.isEmpty());
        assertNotRegistered(resolver, "People");
    }

    @Test
    void nullDefinitionIsRejected() {

        assertEquals(
                "Table definition is required.",
                assertThrows(
                        PlugException.class,
                        () -> writer(
                                new RecordingAdmin(),
                                resolver()
                        ).createTable(null)
                ).getMessage()
        );
    }

    @Test
    void collectionsRemainIndependent() {

        RecordingAdmin admin = new RecordingAdmin();
        MongoSchemaResolver resolver = resolver();

        MongoSchemaWriter writer = writer(admin, resolver);

        writer.createTable(people());

        writer.createTable(
                definition(
                        "orders",
                        field(
                                "reference",
                                TableDefinition.FieldType.STRING,
                                TableDefinition.FieldConstraint.UNIQUE
                        )
                )
        );

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                resolver.resolve("people").getOrigin()
        );

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                resolver.resolve("orders").getOrigin()
        );

        assertEquals(
                String.class,
                resolver.resolve("orders")
                        .getFields()
                        .get("reference")
        );

        assertEquals(Arrays.asList("people", "orders"),
                admin.created);
    }

    @Test
    void nullableOptionalFieldsRoundTripThroughTheValidator() {

        RecordingAdmin admin = new RecordingAdmin();
        MongoSchemaResolver resolver = resolver();

        writer(admin, resolver).createTable(
                definition(
                        "people",
                        field(
                                "id",
                                TableDefinition.FieldType.INT,
                                TableDefinition.FieldConstraint.PRIMARY_KEY
                        ),
                        field(
                                "age",
                                TableDefinition.FieldType.INT
                        )
                )
        );

        Document validator = (Document) admin.createOptions.get(0)
                .getValidationOptions()
                .getValidator();

        EmptyMetadataSource source = new EmptyMetadataSource(validator);

        MongoSchemaResolver.ResolvedSchema resolved =
                new MongoSchemaResolver(source)
                        .resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.VALIDATOR,
                resolved.getOrigin()
        );

        assertEquals(Integer.class, resolved.getFields().get("id"));
        assertEquals(Integer.class, resolved.getFields().get("age"));
    }

    /*
     * Feeds the emitted validator back into M3 resolution, so
     * the nullable union the compiler writes is proven to
     * resolve to the same Plug types.
     */
    private static final class EmptyMetadataSource
            implements MongoSchemaResolver.CollectionSource {

        private final Document validator;

        private EmptyMetadataSource(Document validator) {
            this.validator = validator;
        }

        @Override
        public List<Document> collectionMetadata(
                String collection
        ) {

            return Collections.singletonList(
                    new Document("name", collection)
                            .append(
                                    "options",
                                    new Document(
                                            "validator",
                                            validator
                                    )
                            )
            );
        }

        @Override
        public List<Document> sample(
                String collection,
                int limit
        ) {
            return Collections.emptyList();
        }
    }

    /* ---------------- adapter integration ---------------- */

    @Test
    void createTableNeedsAConnection() {

        assertEquals(
                "No database connection.",
                assertThrows(
                        PlugException.class,
                        () -> new MongoDatabaseAdapter()
                                .executeSchema(
                                        SchemaPlan.createTable(people())
                                )
                ).getMessage()
        );
    }

    @Test
    void createDatabaseIsStillRejectedExplicitly() {

        assertEquals(
                "CREATE DATABASE is not supported by the "
                        + "MongoDB adapter. Connect to an existing "
                        + "database.",
                assertThrows(
                        PlugException.class,
                        () -> new MongoDatabaseAdapter()
                                .executeSchema(
                                        SchemaPlan.createDatabase("other")
                                )
                ).getMessage()
        );
    }

    @Test
    void adapterDelegatesCreateTableAndRegistersTheSchema() {

        RecordingAdmin admin = new RecordingAdmin();
        MongoSchemaResolver resolver = resolver();

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver;
        adapter.schemaWriter = writer(admin, resolver);

        adapter.executeSchema(SchemaPlan.createTable(people()));

        assertEquals(Arrays.asList("people"), admin.created);

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                adapter.schemaResolver.resolve("people").getOrigin()
        );
    }

    @Test
    void closeDropsTheWriter() {

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.schemaWriter = writer(
                new RecordingAdmin(),
                resolver()
        );

        adapter.close();

        assertNull(adapter.schemaWriter);
        assertNull(adapter.schemaResolver);
    }
}
