package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;

import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoWriteExecutor unit tests.
 *
 * Schema resolution is real (session-declared definitions and
 * validator metadata through the M3 resolver), while the store
 * records documents/filters and returns configured counts. No
 * MongoDB server is involved.
 */
public class MongoWriteExecutorTest {

    /* ---------------- test doubles ---------------- */

    private static final class RecordingStore
            implements MongoWriteExecutor.Store {

        private final List<Document> inserted = new ArrayList<>();
        private final List<Bson> countFilters = new ArrayList<>();
        private final List<Bson> updateFilters = new ArrayList<>();
        private final List<Bson> updates = new ArrayList<>();
        private final List<Bson> deleteFilters = new ArrayList<>();

        private long countResult;
        private long updateCount;
        private long deleteCount;

        private RuntimeException countFailure;
        private RuntimeException insertFailure;
        private RuntimeException updateFailure;
        private RuntimeException deleteFailure;

        @Override
        public long count(String collection, Bson filter) {

            countFilters.add(filter);

            if (countFailure != null) {
                throw countFailure;
            }

            return countResult;
        }

        @Override
        public void insert(
                String collection,
                Document document
        ) {

            if (insertFailure != null) {
                throw insertFailure;
            }

            inserted.add(document);
        }

        @Override
        public long updateMany(
                String collection,
                Bson filter,
                Bson update
        ) {

            updateFilters.add(filter);
            updates.add(update);

            if (updateFailure != null) {
                throw updateFailure;
            }

            return updateCount;
        }

        @Override
        public long deleteMany(
                String collection,
                Bson filter
        ) {

            deleteFilters.add(filter);

            if (deleteFailure != null) {
                throw deleteFailure;
            }

            return deleteCount;
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

    private static TableDefinition people() {

        return new TableDefinition(
                "people",
                Arrays.asList(
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
                        ),
                        field(
                                "age",
                                TableDefinition.FieldType.INT
                        ),
                        defaulted(
                                "balance",
                                TableDefinition.FieldType.DECIMAL,
                                new Value(Value.Type.NUMBER, 10)
                        ),
                        field(
                                "active",
                                TableDefinition.FieldType.BOOL
                        )
                )
        );
    }

    private static MongoSchemaResolver resolver() {

        MongoSchemaResolver resolver = new MongoSchemaResolver(
                new MongoSchemaResolver.CollectionSource() {

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
        );

        resolver.declare(people());

        return resolver;
    }

    private static MongoWriteExecutor executor(
            RecordingStore store
    ) {
        return new MongoWriteExecutor(store, resolver());
    }

    private static Map<String, Object> data(Object... pairs) {

        Map<String, Object> data = new LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            data.put((String) pairs[i], pairs[i + 1]);
        }

        return data;
    }

    private static ExecutionPlan plan(
            ExecutionPlan.Operation operation,
            Map<String, Object> data,
            Query query
    ) {
        return new ExecutionPlan(
                query,
                "people",
                operation,
                data
        );
    }

    private static ExecutionPlan insert(
            Map<String, Object> data
    ) {
        return plan(
                ExecutionPlan.Operation.INSERT,
                data,
                new Query(Collections.emptyList())
        );
    }

    private static Query where(
            String field,
            String operator,
            Value value
    ) {
        return new Query(Collections.emptyList())
                .where(field, operator, value);
    }

    private static Value string(String value) {
        return new Value(Value.Type.STRING, value);
    }

    private static Value number(int value) {
        return new Value(Value.Type.NUMBER, value);
    }

    private static Value nullValue() {
        return new Value(Value.Type.NULL, null);
    }

    private static MongoWriteException duplicateKey() {

        return new MongoWriteException(
                new WriteError(
                        11000,
                        "E11000 duplicate key error",
                        new BsonDocument()
                ),
                new ServerAddress(),
                Collections.emptyList()
        );
    }

    /* ---------------- insert ---------------- */

    @Test
    void insertAppliesDefaultsAndStoresOmittedFieldsAsNull() {

        RecordingStore store = new RecordingStore();

        executor(store).insert(
                insert(data(
                        "id", 1,
                        "name", "Alex"
                ))
        );

        Document document = store.inserted.get(0);

        assertEquals(1, document.get("id"));
        assertEquals("Alex", document.get("name"));

        assertEquals(10.0, document.get("balance"));

        assertTrue(document.containsKey("email"));
        assertNull(document.get("email"));

        assertTrue(document.containsKey("age"));
        assertNull(document.get("age"));

        assertTrue(document.containsKey("active"));
        assertNull(document.get("active"));

        assertFalse(document.containsKey("_id"));
    }

    @Test
    void explicitValuesAndNullsAreNotOverwrittenByDefaults() {

        RecordingStore store = new RecordingStore();

        executor(store).insert(
                insert(data(
                        "id", 1,
                        "name", "Alex",
                        "balance", null
                ))
        );

        Document document = store.inserted.get(0);

        assertTrue(document.containsKey("balance"));
        assertNull(document.get("balance"));
    }

    @Test
    void insertPreservesNumericValuesExactly() {

        RecordingStore store = new RecordingStore();

        executor(store).insert(
                insert(data(
                        "id", 7,
                        "name", "Alex",
                        "age", 42,
                        "balance", 123.456789
                ))
        );

        Document document = store.inserted.get(0);

        assertEquals(Integer.class, document.get("id").getClass());
        assertEquals(Integer.class, document.get("age").getClass());
        assertEquals(Double.class, document.get("balance").getClass());
        assertEquals(123.456789, document.get("balance"));
    }

    @Test
    void insertRejectsUnknownFields() {

        RecordingStore store = new RecordingStore();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data(
                                "id", 1,
                                "name", "Alex",
                                "nope", "x"
                        ))
                )
        );

        assertEquals("Unknown field: nope", error.getMessage());
        assertTrue(store.inserted.isEmpty());
    }

    @Test
    void insertRejectsTypeMismatches() {

        RecordingStore store = new RecordingStore();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data(
                                "id", 1,
                                "name", "Alex",
                                "age", "old"
                        ))
                )
        );

        assertTrue(error.getMessage().startsWith(
                "Type mismatch for field 'age'"
        ));

        assertTrue(store.inserted.isEmpty());
    }

    @Test
    void insertRequiresAnExplicitPrimaryKey() {

        RecordingStore store = new RecordingStore();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data("name", "Alex"))
                )
        );

        assertEquals(
                "Missing primary key: id",
                error.getMessage()
        );

        assertTrue(store.inserted.isEmpty());
    }

    @Test
    void insertRejectsANullPrimaryKey() {

        RecordingStore store = new RecordingStore();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data(
                                "id", null,
                                "name", "Alex"
                        ))
                )
        );

        assertEquals(
                "Primary key 'id' cannot be null.",
                error.getMessage()
        );
    }

    @Test
    void insertRequiresRequiredFields() {

        RecordingStore store = new RecordingStore();

        PlugException missing = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data("id", 1))
                )
        );

        assertEquals(
                "Missing required field: name",
                missing.getMessage()
        );

        PlugException explicitNull = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data(
                                "id", 2,
                                "name", null
                        ))
                )
        );

        assertEquals(
                "Required field 'name' cannot be null.",
                explicitNull.getMessage()
        );

        assertTrue(store.inserted.isEmpty());
    }

    @Test
    void insertDetectsDuplicatePrimaryKeys() {

        RecordingStore store = new RecordingStore();
        store.countResult = 1;

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data(
                                "id", 1,
                                "name", "Alex"
                        ))
                )
        );

        assertEquals("Duplicate id: 1", error.getMessage());

        assertEquals(
                new Document("id", 1),
                store.countFilters.get(0)
        );

        assertTrue(store.inserted.isEmpty());
    }

    @Test
    void insertTranslatesDuplicateUniqueValues() {

        RecordingStore store = new RecordingStore();
        store.insertFailure = duplicateKey();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data(
                                "id", 1,
                                "name", "Alex",
                                "email", "a@b.com"
                        ))
                )
        );

        assertTrue(error.getMessage().startsWith(
                "Duplicate value for a unique constraint "
                        + "in collection 'people'"
        ));
    }

    @Test
    void insertTranslatesOtherDriverFailures() {

        RecordingStore store = new RecordingStore();
        store.insertFailure = new RuntimeException("write concern");

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).insert(
                        insert(data(
                                "id", 1,
                                "name", "Alex"
                        ))
                )
        );

        assertEquals(
                "INSERT failed: write concern",
                error.getMessage()
        );
    }

    @Test
    void unknownCollectionFailsBeforeWriting() {

        RecordingStore store = new RecordingStore();

        MongoWriteExecutor executor = new MongoWriteExecutor(
                store,
                new MongoSchemaResolver(
                        new MongoSchemaResolver.CollectionSource() {

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
                )
        );

        assertEquals(
                "Unknown collection: people",
                assertThrows(
                        PlugException.class,
                        () -> executor.insert(
                                insert(data("id", 1))
                        )
                ).getMessage()
        );

        assertTrue(store.inserted.isEmpty());
    }

    /* ---------------- update ---------------- */

    @Test
    void updateAppliesOnlyTheSpecifiedFields() {

        RecordingStore store = new RecordingStore();
        store.updateCount = 2;

        Query query = where("id", "EQ", number(1));

        QueryResult result = executor(store).update(
                plan(
                        ExecutionPlan.Operation.UPDATE,
                        data("age", 30),
                        query
                )
        );

        assertEquals(2, result.getCount());
        assertTrue(result.getRows().isEmpty());

        assertEquals(
                MongoQueryCompiler.compileFilter(
                        query,
                        resolver().resolve("people").getFields()
                ),
                store.updateFilters.get(0)
        );

        assertEquals(
                new Document("$set", new Document("age", 30)),
                store.updates.get(0)
        );
    }

    @Test
    void updatePreservesExplicitNulls() {

        RecordingStore store = new RecordingStore();
        store.updateCount = 1;

        executor(store).update(
                plan(
                        ExecutionPlan.Operation.UPDATE,
                        data("email", null),
                        where("id", "EQ", number(1))
                )
        );

        assertEquals(
                new Document("$set", new Document("email", null)),
                store.updates.get(0)
        );
    }

    @Test
    void updateDoesNotApplyInsertDefaults() {

        RecordingStore store = new RecordingStore();
        store.updateCount = 1;

        executor(store).update(
                plan(
                        ExecutionPlan.Operation.UPDATE,
                        data("name", "Sam"),
                        where("id", "EQ", number(1))
                )
        );

        Document update = (Document) store.updates.get(0);

        assertEquals(
                new Document("$set", new Document("name", "Sam")),
                update
        );

        assertFalse(
                ((Document) update.get("$set"))
                        .containsKey("balance")
        );
    }

    @Test
    void updateRejectsUnknownFields() {

        RecordingStore store = new RecordingStore();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).update(
                        plan(
                                ExecutionPlan.Operation.UPDATE,
                                data("nope", 1),
                                where("id", "EQ", number(1))
                        )
                )
        );

        assertEquals("Unknown field: nope", error.getMessage());
        assertTrue(store.updates.isEmpty());
    }

    @Test
    void updateRejectsTypeMismatches() {

        RecordingStore store = new RecordingStore();

        assertThrows(
                PlugException.class,
                () -> executor(store).update(
                        plan(
                                ExecutionPlan.Operation.UPDATE,
                                data("age", "old"),
                                where("id", "EQ", number(1))
                        )
                )
        );

        assertTrue(store.updates.isEmpty());
    }

    @Test
    void updateRejectsAMissingWherePredicate() {

        RecordingStore store = new RecordingStore();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).update(
                        plan(
                                ExecutionPlan.Operation.UPDATE,
                                data("age", 30),
                                new Query(Collections.emptyList())
                        )
                )
        );

        assertEquals(
                "update() requires a where() condition.",
                error.getMessage()
        );

        assertTrue(store.updates.isEmpty());
    }

    @Test
    void updateCompilesNullAndMissingFieldSemantics() {

        RecordingStore store = new RecordingStore();

        Query query = where("email", "EQ", nullValue());

        executor(store).update(
                plan(
                        ExecutionPlan.Operation.UPDATE,
                        data("age", 30),
                        query
                )
        );

        assertEquals(
                new Document(
                        "email",
                        new Document("$exists", true)
                                .append("$eq", null)
                ),
                store.updateFilters.get(0)
        );
    }

    @Test
    void updateTranslatesDuplicateUniqueValues() {

        RecordingStore store = new RecordingStore();
        store.updateFailure = duplicateKey();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).update(
                        plan(
                                ExecutionPlan.Operation.UPDATE,
                                data("email", "taken@b.com"),
                                where("id", "EQ", number(1))
                        )
                )
        );

        assertTrue(error.getMessage().contains(
                "Duplicate value for a unique constraint"
        ));
    }

    @Test
    void updateTranslatesDriverFailures() {

        RecordingStore store = new RecordingStore();
        store.updateFailure = new RuntimeException("network");

        assertEquals(
                "UPDATE failed: network",
                assertThrows(
                        PlugException.class,
                        () -> executor(store).update(
                                plan(
                                        ExecutionPlan.Operation.UPDATE,
                                        data("age", 30),
                                        where("id", "EQ", number(1))
                                )
                        )
                ).getMessage()
        );
    }

    /* ---------------- delete ---------------- */

    @Test
    void deleteReturnsTheDeletedCount() {

        RecordingStore store = new RecordingStore();
        store.deleteCount = 3;

        Query query = where("age", "GT", number(20));

        QueryResult result = executor(store).delete(
                plan(
                        ExecutionPlan.Operation.DELETE,
                        Collections.emptyMap(),
                        query
                )
        );

        assertEquals(3, result.getCount());
        assertTrue(result.getRows().isEmpty());

        assertEquals(
                MongoQueryCompiler.compileFilter(
                        query,
                        resolver().resolve("people").getFields()
                ),
                store.deleteFilters.get(0)
        );
    }

    @Test
    void deleteCompilesLogicalExpressions() {

        RecordingStore store = new RecordingStore();
        store.deleteCount = 1;

        Query query = new Query(Collections.emptyList())
                .where("age", "GTE", number(18))
                .and("active", "EQ", new Value(Value.Type.BOOLEAN, true));

        executor(store).delete(
                plan(
                        ExecutionPlan.Operation.DELETE,
                        Collections.emptyMap(),
                        query
                )
        );

        assertEquals(
                MongoQueryCompiler.compileFilter(
                        query,
                        resolver().resolve("people").getFields()
                ),
                store.deleteFilters.get(0)
        );
    }

    @Test
    void deleteCompilesExplicitNullSemantics() {

        RecordingStore store = new RecordingStore();

        executor(store).delete(
                plan(
                        ExecutionPlan.Operation.DELETE,
                        Collections.emptyMap(),
                        where("email", "IS NULL", nullValue())
                )
        );

        assertEquals(
                new Document(
                        "email",
                        new Document("$exists", true)
                                .append("$eq", null)
                ),
                store.deleteFilters.get(0)
        );
    }

    @Test
    void deleteRejectsAMissingWherePredicate() {

        RecordingStore store = new RecordingStore();

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(store).delete(
                        plan(
                                ExecutionPlan.Operation.DELETE,
                                Collections.emptyMap(),
                                new Query(Collections.emptyList())
                        )
                )
        );

        assertEquals(
                "delete() requires a where() condition.",
                error.getMessage()
        );

        assertTrue(store.deleteFilters.isEmpty());
    }

    @Test
    void deleteTranslatesDriverFailures() {

        RecordingStore store = new RecordingStore();
        store.deleteFailure = new RuntimeException("network");

        assertEquals(
                "DELETE failed: network",
                assertThrows(
                        PlugException.class,
                        () -> executor(store).delete(
                                plan(
                                        ExecutionPlan.Operation.DELETE,
                                        Collections.emptyMap(),
                                        where("id", "EQ", number(1))
                                )
                        )
                ).getMessage()
        );
    }

    /* ---------------- adapter integration ---------------- */

    @Test
    void writesNeedAConnection() {

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        assertEquals(
                "No database connection.",
                assertThrows(
                        PlugException.class,
                        () -> adapter.execute(
                                insert(data(
                                        "id", 1,
                                        "name", "Alex"
                                ))
                        )
                ).getMessage()
        );
    }

    @Test
    void adapterDelegatesWrites() {

        RecordingStore store = new RecordingStore();
        store.updateCount = 1;
        store.deleteCount = 2;

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();
        adapter.schemaResolver = resolver();
        adapter.writeExecutor = new MongoWriteExecutor(
                store,
                adapter.schemaResolver
        );

        QueryResult inserted = adapter.execute(
                insert(data(
                        "id", 1,
                        "name", "Alex"
                ))
        );

        assertEquals(1, inserted.getCount());

        QueryResult updated = adapter.execute(
                plan(
                        ExecutionPlan.Operation.UPDATE,
                        data("age", 30),
                        where("id", "EQ", number(1))
                )
        );

        assertEquals(1, updated.getCount());

        QueryResult deleted = adapter.execute(
                plan(
                        ExecutionPlan.Operation.DELETE,
                        Collections.emptyMap(),
                        where("id", "EQ", number(1))
                )
        );

        assertEquals(2, deleted.getCount());
    }

    @Test
    void closeDropsTheWriteExecutor() {

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.writeExecutor = new MongoWriteExecutor(
                new RecordingStore(),
                resolver()
        );

        adapter.close();

        assertNull(adapter.writeExecutor);
        assertNull(adapter.schemaResolver);
    }
}
