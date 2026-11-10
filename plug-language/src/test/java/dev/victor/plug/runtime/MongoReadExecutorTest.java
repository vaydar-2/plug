package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import org.bson.Document;
import org.bson.types.Decimal128;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoReadExecutor unit tests: compiled filter/projection/sort/
 * limit forwarding, ordered materialization, exact BSON decoding,
 * invalid plans, and translated driver errors. The reader seam
 * records the compiled query and feeds documents, so no server is
 * needed.
 */
public class MongoReadExecutorTest {

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

        resolver.declare(
                new TableDefinition(
                        "people",
                        Arrays.asList(
                                field(
                                        "id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint
                                                .PRIMARY_KEY
                                ),
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                ),
                                field(
                                        "age",
                                        TableDefinition.FieldType.INT
                                ),
                                field(
                                        "balance",
                                        TableDefinition.FieldType.DECIMAL
                                ),
                                field(
                                        "active",
                                        TableDefinition.FieldType.BOOL
                                )
                        )
                )
        );

        return resolver;
    }

    private static final class FakeReader
            implements MongoReadExecutor.Reader {

        private final List<Document> documents;
        private final RuntimeException failure;

        private String collection;
        private MongoQueryCompiler.CompiledQuery query;
        private int calls;

        private FakeReader(
                List<Document> documents,
                RuntimeException failure
        ) {
            this.documents = documents;
            this.failure = failure;
        }

        @Override
        public List<Document> find(
                String collection,
                MongoQueryCompiler.CompiledQuery query
        ) {

            this.collection = collection;
            this.query = query;
            this.calls++;

            if (failure != null) {
                throw failure;
            }

            return documents;
        }
    }

    private static MongoReadExecutor executor(FakeReader reader) {
        return new MongoReadExecutor(reader, resolver());
    }

    private static ExecutionPlan plan(Query query) {
        return new ExecutionPlan(query, "people");
    }

    private static Query query(String... fields) {
        return new Query(Arrays.asList(fields));
    }

    private static Value number(int value) {
        return new Value(Value.Type.NUMBER, value);
    }

    private static Value bool(boolean value) {
        return new Value(Value.Type.BOOLEAN, value);
    }

    @Test
    void basicReadCompilesAnUnfilteredFindAndMaterializesRows() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("id", 1)
                                .append("name", "Alex")
                                .append("age", 30)
                                .append("balance", 10.5)
                                .append("active", true)
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("id", "name", "age"))
        );

        assertEquals("people", reader.collection);
        assertTrue(reader.query.getFilter().isEmpty());
        assertNull(reader.query.getSort());
        assertNull(reader.query.getLimit());

        assertEquals(1, reader.calls);
        assertEquals(1, result.getCount());
        assertEquals(1, result.getRows().size());

        Map<String, Object> row = result.getRows().get(0);

        assertEquals(
                Arrays.asList("id", "name", "age"),
                new ArrayList<>(row.keySet())
        );
        assertEquals(1, row.get("id"));
        assertEquals("Alex", row.get("name"));
        assertEquals(30, row.get("age"));
    }

    @Test
    void whereConditionsAreValidatedAndCompiled() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        Query query = query("name")
                .where("age", "GT", number(18))
                .and("name", "EQ", new Value(
                        Value.Type.STRING,
                        "Alex"
                ));

        executor(reader).execute(plan(query));

        assertEquals(
                new Document(
                        "$and",
                        Arrays.asList(
                                new Document(
                                        "age",
                                        new Document("$gt", 18)
                                ),
                                new Document(
                                        "name",
                                        new Document("$eq", "Alex")
                                )
                        )
                ),
                reader.query.getFilter()
        );
    }

    @Test
    void explicitNullAndMissingFieldsBothMaterializeAsNull() {

        FakeReader reader = new FakeReader(
                Arrays.asList(
                        new Document("id", 1)
                                .append("name", null),
                        new Document("id", 2)
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("name"))
        );

        assertEquals(2, result.getCount());

        for (Map<String, Object> row : result.getRows()) {
            assertTrue(row.containsKey("name"));
            assertNull(row.get("name"));
        }
    }

    @Test
    void projectionPreservesRequestedFieldOrderAndExcludesId() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("id", 3)
                                .append("name", "Ama")
                                .append("balance", 2.5)
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("balance", "id", "name"))
        );

        assertEquals(
                new Document("balance", 1)
                        .append("id", 1)
                        .append("name", 1)
                        .append("_id", 0),
                reader.query.getProjection()
        );

        assertEquals(
                Arrays.asList("balance", "id", "name"),
                new ArrayList<>(
                        result.getRows().get(0).keySet()
                )
        );
    }

    @Test
    void ascendingAndDescendingSortsAreForwarded() {

        FakeReader ascending = new FakeReader(
                Collections.emptyList(),
                null
        );

        executor(ascending).execute(
                plan(query("name").sort("age", "ASC"))
        );

        assertEquals(
                new Document("age", 1),
                ascending.query.getSort()
        );

        FakeReader descending = new FakeReader(
                Collections.emptyList(),
                null
        );

        executor(descending).execute(
                plan(query("name").sort("age", "DESC"))
        );

        assertEquals(
                new Document("age", -1),
                descending.query.getSort()
        );
    }

    @Test
    void positiveLimitIsForwarded() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("name", "Alex")
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("name").limit(2))
        );

        assertEquals(Integer.valueOf(2), reader.query.getLimit());
        assertEquals(1, result.getCount());
    }

    @Test
    void limitZeroReturnsNoRowsWithoutTouchingTheDriver() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("name", "Alex")
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("name").limit(0))
        );

        assertEquals(0, result.getCount());
        assertTrue(result.getRows().isEmpty());
        assertEquals(0, reader.calls);
    }

    @Test
    void emptyCollectionReturnsAnEmptyResult() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("name"))
        );

        assertEquals(0, result.getCount());
        assertTrue(result.getRows().isEmpty());
        assertEquals(1, reader.calls);
    }

    @Test
    void unknownProjectionFieldFailsBeforeReading() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        assertEquals(
                "Unknown field: missing",
                assertThrows(
                        PlugException.class,
                        () -> executor(reader).execute(
                                plan(query("missing"))
                        )
                ).getMessage()
        );

        assertEquals(0, reader.calls);
    }

    @Test
    void unknownWhereAndSortFieldsFailBeforeReading() {

        FakeReader whereReader = new FakeReader(
                Collections.emptyList(),
                null
        );

        assertEquals(
                "Unknown field: missing",
                assertThrows(
                        PlugException.class,
                        () -> executor(whereReader).execute(
                                plan(
                                        query("name")
                                                .where(
                                                        "missing",
                                                        "EQ",
                                                        new Value(
                                                                Value.Type.STRING,
                                                                "x"
                                                        )
                                                )
                                )
                        )
                ).getMessage()
        );

        assertEquals(0, whereReader.calls);

        FakeReader sortReader = new FakeReader(
                Collections.emptyList(),
                null
        );

        assertEquals(
                "Unknown field: missing",
                assertThrows(
                        PlugException.class,
                        () -> executor(sortReader).execute(
                                plan(
                                        query("name")
                                                .sort(
                                                        "missing",
                                                        "ASC"
                                                )
                                )
                        )
                ).getMessage()
        );

        assertEquals(0, sortReader.calls);
    }

    @Test
    void whereTypeMismatchesFailBeforeReading() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(reader).execute(
                        plan(
                                query("name")
                                        .where(
                                                "age",
                                                "EQ",
                                                new Value(
                                                        Value.Type.STRING,
                                                        "old"
                                                )
                                        )
                        )
                )
        );

        assertTrue(
                error.getMessage().contains(
                        "Type mismatch for field 'age'"
                ),
                error.getMessage()
        );

        assertEquals(0, reader.calls);
    }

    @Test
    void invalidPlansAndUnsupportedPlanShapesAreRejected() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        MongoReadExecutor executor = executor(reader);

        assertEquals(
                "Execution plan and query are required.",
                assertThrows(
                        PlugException.class,
                        () -> executor.execute(null)
                ).getMessage()
        );

        assertEquals(
                "Execution plan and query are required.",
                assertThrows(
                        PlugException.class,
                        () -> executor.execute(
                                new ExecutionPlan(null, "people")
                        )
                ).getMessage()
        );

        assertEquals(
                "Collection name is required.",
                assertThrows(
                        PlugException.class,
                        () -> executor.execute(
                                new ExecutionPlan(query("name"), null)
                        )
                ).getMessage()
        );

        assertEquals(
                "get() expects at least one field.",
                assertThrows(
                        PlugException.class,
                        () -> executor.execute(
                                plan(query())
                        )
                ).getMessage()
        );

        Query joined = query("name");

        joined.join(
                new Join("people.id", "orders.person_id", "orders")
        );

        assertEquals(
                "Read plans cannot contain joins.",
                assertThrows(
                        PlugException.class,
                        () -> executor.execute(plan(joined))
                ).getMessage()
        );

        assertEquals(
                "Read plans cannot contain aggregates.",
                assertThrows(
                        PlugException.class,
                        () -> executor.execute(
                                new ExecutionPlan(
                                        query("name"),
                                        "people",
                                        ExecutionPlan.Aggregate.COUNT,
                                        "id"
                                )
                        )
                ).getMessage()
        );

        assertEquals(0, reader.calls);
    }

    @Test
    void numericValuesConvertExactly() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("id", 7L)
                                .append("age", 30)
                                .append("balance", 2)
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("id", "age", "balance"))
        );

        Map<String, Object> row = result.getRows().get(0);

        assertInstanceOf(Integer.class, row.get("id"));
        assertEquals(7, row.get("id"));
        assertInstanceOf(Integer.class, row.get("age"));
        assertInstanceOf(Double.class, row.get("balance"));
        assertEquals(2.0, row.get("balance"));
    }

    @Test
    void exactDecimalConvertsAndInexactDecimalFailsExplicitly() {

        FakeReader exactReader = new FakeReader(
                Collections.singletonList(
                        new Document("balance", new Decimal128(
                                new BigDecimal("12.5")
                        ))
                ),
                null
        );

        assertEquals(
                12.5,
                executor(exactReader).execute(
                        plan(query("balance"))
                ).getRows().get(0).get("balance")
        );

        FakeReader inexactReader = new FakeReader(
                Collections.singletonList(
                        new Document("balance", new Decimal128(
                                new BigDecimal("0.1")
                        ))
                ),
                null
        );

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(inexactReader).execute(
                        plan(query("balance"))
                )
        );

        assertTrue(
                error.getMessage().contains(
                        "cannot be represented as Double"
                ),
                error.getMessage()
        );
    }

    @Test
    void resultMaterializationKeepsRowOrder() {

        FakeReader reader = new FakeReader(
                Arrays.asList(
                        new Document("name", "Alex")
                                .append("id", 1),
                        new Document("name", "Grace")
                                .append("id", 2)
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("name", "id"))
        );

        assertEquals(2, result.getCount());
        assertEquals("Alex", result.getRows().get(0).get("name"));
        assertEquals("Grace", result.getRows().get(1).get("name"));
        assertEquals(
                Arrays.asList("name", "id"),
                new ArrayList<>(
                        result.getRows().get(0).keySet()
                )
        );
    }

    @Test
    void driverFailureIsTranslatedAndKeepsTheCollection() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                new IllegalStateException("boom")
        );

        assertEquals(
                "Cannot read MongoDB collection 'people': boom",
                assertThrows(
                        PlugException.class,
                        () -> executor(reader).execute(
                                plan(query("name"))
                        )
                ).getMessage()
        );
    }

    @Test
    void adapterDelegatesPlainReadsAndClearsOnClose() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("name", "Alex")
                                .append("age", 30)
                ),
                null
        );

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.readExecutor = new MongoReadExecutor(
                reader,
                adapter.schemaResolver
        );

        QueryResult result = adapter.execute(
                plan(query("name", "age"))
        );

        assertEquals(1, result.getCount());
        assertEquals("Alex", result.getRows().get(0).get("name"));
        assertEquals("people", reader.collection);

        adapter.close();

        assertNull(adapter.readExecutor);
    }

    @Test
    void adapterPlainReadNeedsAConnection() {

        assertEquals(
                "No database connection.",
                assertThrows(
                        PlugException.class,
                        () -> new MongoDatabaseAdapter().execute(
                                plan(query("name"))
                        )
                ).getMessage()
        );
    }

    @Test
    void booleanValuesRemainBoolean() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("active", true)
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(query("active").where(
                        "active",
                        "EQ",
                        bool(true)
                ))
        );

        assertEquals(true, result.getRows().get(0).get("active"));
    }
}
