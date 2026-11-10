package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

/*
 * MongoJoinExecutor unit tests: materialized field names and
 * order, exact value decoding, missing values, translated
 * driver errors, and adapter dispatch. The reader seam feeds
 * result documents, so no server is needed.
 */
public class MongoJoinExecutorTest {

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
                        "users",
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
                                )
                        )
                )
        );

        resolver.declare(
                new TableDefinition(
                        "orders",
                        Arrays.asList(
                                field(
                                        "id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint
                                                .PRIMARY_KEY
                                ),
                                field(
                                        "user_id",
                                        TableDefinition.FieldType.INT
                                ),
                                field(
                                        "total",
                                        TableDefinition.FieldType.DECIMAL
                                )
                        )
                )
        );

        resolver.declare(
                new TableDefinition(
                        "inventory",
                        Arrays.asList(
                                field(
                                        "id",
                                        TableDefinition.FieldType.INT,
                                        TableDefinition.FieldConstraint
                                                .PRIMARY_KEY
                                ),
                                field(
                                        "order_id",
                                        TableDefinition.FieldType.INT
                                ),
                                field(
                                        "total",
                                        TableDefinition.FieldType.DECIMAL
                                )
                        )
                )
        );

        return resolver;
    }

    private static final class FakeReader
            implements MongoJoinExecutor.Reader {

        private final List<Document> documents;
        private final RuntimeException failure;

        private String collection;
        private List<Bson> pipeline;

        private FakeReader(
                List<Document> documents,
                RuntimeException failure
        ) {
            this.documents = documents;
            this.failure = failure;
        }

        @Override
        public List<Document> aggregate(
                String collection,
                List<Bson> pipeline
        ) {

            this.collection = collection;
            this.pipeline = pipeline;

            if (failure != null) {
                throw failure;
            }

            return documents;
        }
    }

    private static Query joinedQuery(String... fields) {

        Query query = new Query(Arrays.asList(fields));

        query.join(
                new Join(
                        "users.id",
                        "orders.user_id",
                        "orders"
                )
        );

        return query;
    }

    private static MongoJoinExecutor executor(
            FakeReader reader
    ) {
        return new MongoJoinExecutor(reader, resolver());
    }

    @Test
    void materializesRequestedLogicalNamesInOrder() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("id", 1)
                                .append("name", "Alex")
                                .append(
                                        "__join_0",
                                        new Document(
                                                "total",
                                                new Decimal128(
                                                        new BigDecimal(
                                                                "12.50"
                                                        )
                                                )
                                        )
                                )
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                new ExecutionPlan(
                        joinedQuery(
                                "name",
                                "orders.total",
                                "id"
                        ),
                        "users"
                )
        );

        assertEquals("users", reader.collection);
        assertFalse(reader.pipeline.isEmpty());

        assertEquals(1, result.getCount());

        Map<String, Object> row = result.getRows().get(0);

        assertEquals(
                Arrays.asList("name", "orders.total", "id"),
                new ArrayList<>(row.keySet())
        );

        assertEquals("Alex", row.get("name"));
        assertEquals(12.5, row.get("orders.total"));
        assertEquals(1, row.get("id"));
        assertFalse(row.containsKey("__join_0"));
    }

    @Test
    void missingJoinedFieldMaterializesAsNull() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("name", "Alex")
                                .append(
                                        "__join_0",
                                        new Document("other", 1)
                                )
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                new ExecutionPlan(
                        joinedQuery("name", "orders.total"),
                        "users"
                )
        );

        assertNull(result.getRows().get(0).get("orders.total"));
    }

    @Test
    void bareProjectionUsesTheFirstJoinedCollection() {

        Query query = new Query(
                Arrays.asList("total", "orders.total")
        );

        query.join(
                new Join("users.id", "orders.user_id", "orders")
        );

        query.join(
                new Join(
                        "orders.id",
                        "inventory.order_id",
                        "inventory"
                )
        );

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document(
                                "__join_0",
                                new Document(
                                        "total",
                                        new Decimal128(
                                                new BigDecimal("5.5")
                                        )
                                )
                        ).append(
                                "__join_1",
                                new Document(
                                        "total",
                                        new Decimal128(
                                                new BigDecimal("9.5")
                                        )
                                )
                        )
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                new ExecutionPlan(query, "users")
        );

        Map<String, Object> row = result.getRows().get(0);

        assertEquals(5.5, row.get("total"));
        assertEquals(5.5, row.get("orders.total"));
        assertEquals(
                Arrays.asList("total", "orders.total"),
                new ArrayList<>(row.keySet())
        );
    }

    @Test
    void int64DecodesToIntegerAndMissingBaseFieldIsNull() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("id", 7L)
                                .append(
                                        "__join_0",
                                        new Document()
                                )
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                new ExecutionPlan(
                        joinedQuery("id", "name"),
                        "users"
                )
        );

        Map<String, Object> row = result.getRows().get(0);

        assertEquals(7, row.get("id"));
        assertNull(row.get("name"));
    }

    @Test
    void driverFailureIsTranslatedAndKeepsTheBaseCollection() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                new IllegalStateException("boom")
        );

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(reader).execute(
                        new ExecutionPlan(
                                joinedQuery("name"),
                                "users"
                        )
                )
        );

        assertEquals(
                "Cannot read MongoDB collection 'users': boom",
                error.getMessage()
        );
    }

    @Test
    void aggregateWithJoinIsRejectedBeforeReading() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        ExecutionPlan plan = new ExecutionPlan(
                joinedQuery("name"),
                "users",
                ExecutionPlan.Aggregate.COUNT,
                "id"
        );

        assertEquals(
                "An aggregate cannot be combined with join().",
                assertThrows(
                        PlugException.class,
                        () -> executor(reader).execute(plan)
                ).getMessage()
        );

        assertNull(reader.collection);
    }

    @Test
    void adapterDelegatesJoinedReadAndClearsOnClose() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("name", "Alex")
                                .append(
                                        "__join_0",
                                        new Document(
                                                "total",
                                                new Decimal128(
                                                        new BigDecimal("4")
                                                )
                                        )
                                )
                ),
                null
        );

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.joinExecutor = new MongoJoinExecutor(
                reader,
                adapter.schemaResolver
        );

        QueryResult result = adapter.execute(
                new ExecutionPlan(
                        joinedQuery("name", "orders.total"),
                        "users"
                )
        );

        assertEquals(1, result.getCount());
        assertEquals(
                4.0,
                result.getRows().get(0).get("orders.total")
        );

        adapter.close();

        assertNull(adapter.joinExecutor);
    }

    @Test
    void adapterRejectsJoinedReadWithoutConnection() {

        Query query = joinedQuery("name");

        assertEquals(
                "No database connection.",
                assertThrows(
                        PlugException.class,
                        () -> new MongoDatabaseAdapter().execute(
                                new ExecutionPlan(query, "users")
                        )
                ).getMessage()
        );
    }

    @Test
    void adapterStillRejectsPlainRead() {

        assertEquals(
                "MongoDB adapter does not support query "
                        + "operations yet: READ",
                assertThrows(
                        PlugException.class,
                        () -> new MongoDatabaseAdapter().execute(
                                new ExecutionPlan(
                                        new Query(
                                                Arrays.asList("name")
                                        ),
                                        "users"
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void adapterRejectsAggregateWithJoin() {

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.joinExecutor = new MongoJoinExecutor(
                new FakeReader(Collections.emptyList(), null),
                adapter.schemaResolver
        );

        assertEquals(
                "An aggregate cannot be combined with join().",
                assertThrows(
                        PlugException.class,
                        () -> adapter.execute(
                                new ExecutionPlan(
                                        joinedQuery("name"),
                                        "users",
                                        ExecutionPlan.Aggregate.COUNT,
                                        "id"
                                )
                        )
                ).getMessage()
        );
    }
}
