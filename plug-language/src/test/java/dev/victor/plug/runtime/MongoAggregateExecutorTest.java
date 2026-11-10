package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoAggregateExecutor unit tests: SQL-compatible empty
 * behavior, exact BSON -> Plug conversion, explicit overflow,
 * pipeline dispatch, and adapter wiring. The reader seam feeds
 * aggregate documents, so no server is needed.
 */
public class MongoAggregateExecutorTest {

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
                                )
                        )
                )
        );

        return resolver;
    }

    private static final class FakeReader
            implements MongoAggregateExecutor.Reader {

        private final List<Document> documents;
        private final RuntimeException failure;

        private String collection;
        private List<Bson> pipeline;
        private int calls;

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
            this.calls++;

            if (failure != null) {
                throw failure;
            }

            return documents;
        }
    }

    private static MongoAggregateExecutor executor(
            FakeReader reader
    ) {
        return new MongoAggregateExecutor(reader, resolver());
    }

    private static ExecutionPlan plan(
            Query query,
            ExecutionPlan.Aggregate aggregate,
            String field
    ) {
        return new ExecutionPlan(query, "people", aggregate, field);
    }

    private static ExecutionPlan plan(
            ExecutionPlan.Aggregate aggregate,
            String field
    ) {
        return plan(
                new Query(Collections.emptyList()),
                aggregate,
                field
        );
    }

    private static Document countResult(long count) {
        return new Document("_id", null)
                .append("aggregateValue", count);
    }

    @Test
    void countReturnsTheMatchingDocumentCount() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(countResult(3)),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(ExecutionPlan.Aggregate.COUNT, null)
        );

        assertEquals("people", reader.collection);
        assertEquals(2, reader.pipeline.size());
        assertEquals(3, result.getCount());
        assertEquals(3, result.getAggregateValue());
        assertTrue(result.getRows().isEmpty());
    }

    @Test
    void emptyCountIsZero() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(ExecutionPlan.Aggregate.COUNT, null)
        );

        assertEquals(0, result.getCount());
        assertEquals(0, result.getAggregateValue());
        assertTrue(result.getRows().isEmpty());
        assertEquals(2, reader.pipeline.size());
    }

    @Test
    void otherAggregatesOverNoRowsAreNull() {

        for (ExecutionPlan.Aggregate aggregate :
                Arrays.asList(
                        ExecutionPlan.Aggregate.SUM,
                        ExecutionPlan.Aggregate.AVG,
                        ExecutionPlan.Aggregate.MIN,
                        ExecutionPlan.Aggregate.MAX
                )) {

            QueryResult result = executor(
                    new FakeReader(
                            Collections.emptyList(),
                            null
                    )
            ).execute(
                    plan(aggregate, "age")
            );

            assertEquals(0, result.getCount());
            assertNull(result.getAggregateValue());
            assertTrue(result.getRows().isEmpty());
        }
    }

    @Test
    void sumKeepsAnIntegerFieldAsInteger() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("_id", null)
                                .append("aggregateValue", 60L)
                                .append("valueCount", 3)
                ),
                null
        );

        QueryResult result = executor(reader).execute(
                plan(ExecutionPlan.Aggregate.SUM, "age")
        );

        assertEquals(0, result.getCount());
        assertInstanceOf(Integer.class, result.getAggregateValue());
        assertEquals(60, result.getAggregateValue());
    }

    @Test
    void sumOfAnInt64WithinRangeIsAnInteger() {

        QueryResult result = executor(
                new FakeReader(
                        Collections.singletonList(
                                new Document("_id", null)
                                        .append(
                                                "aggregateValue",
                                                2_147_483_647L
                                        )
                                        .append("valueCount", 1L)
                        ),
                        null
                )
        ).execute(
                plan(ExecutionPlan.Aggregate.SUM, "age")
        );

        assertEquals(
                Integer.MAX_VALUE,
                result.getAggregateValue()
        );
    }

    @Test
    void sumOverflowFailsExplicitly() {

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(
                        new FakeReader(
                                Collections.singletonList(
                                        new Document("_id", null)
                                                .append(
                                                        "aggregateValue",
                                                        2_147_483_648L
                                                )
                                                .append("valueCount", 1)
                                ),
                                null
                        )
                ).execute(
                        plan(ExecutionPlan.Aggregate.SUM, "age")
                )
        );

        assertTrue(
                error.getMessage().contains(
                        "cannot be represented as Integer"
                ),
                error.getMessage()
        );
    }

    @Test
    void sumOfOnlyNullValuesIsNullNotZero() {

        QueryResult result = executor(
                new FakeReader(
                        Collections.singletonList(
                                new Document("_id", null)
                                        .append("aggregateValue", 0)
                                        .append("valueCount", 0)
                        ),
                        null
                )
        ).execute(
                plan(ExecutionPlan.Aggregate.SUM, "age")
        );

        assertNull(result.getAggregateValue());
    }

    @Test
    void sumOfDecimalValuesStaysDouble() {

        QueryResult result = executor(
                new FakeReader(
                        Collections.singletonList(
                                new Document("_id", null)
                                        .append(
                                                "aggregateValue",
                                                new Decimal128(
                                                        new BigDecimal(
                                                                "12.5"
                                                        )
                                                )
                                        )
                                        .append("valueCount", 2)
                        ),
                        null
                )
        ).execute(
                plan(ExecutionPlan.Aggregate.SUM, "balance")
        );

        assertInstanceOf(Double.class, result.getAggregateValue());
        assertEquals(12.5, result.getAggregateValue());
    }

    @Test
    void inexactDecimalConversionFailsExplicitly() {

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(
                        new FakeReader(
                                Collections.singletonList(
                                        new Document("_id", null)
                                                .append(
                                                        "aggregateValue",
                                                        new Decimal128(
                                                                new BigDecimal(
                                                                        "0.1"
                                                                )
                                                        )
                                                )
                                                .append("valueCount", 1)
                                ),
                                null
                        )
                ).execute(
                        plan(ExecutionPlan.Aggregate.SUM, "balance")
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
    void avgIsAlwaysDouble() {

        QueryResult result = executor(
                new FakeReader(
                        Collections.singletonList(
                                new Document("_id", null)
                                        .append("aggregateValue", 20.0)
                        ),
                        null
                )
        ).execute(
                plan(ExecutionPlan.Aggregate.AVG, "age")
        );

        assertInstanceOf(Double.class, result.getAggregateValue());
        assertEquals(20.0, result.getAggregateValue());
    }

    @Test
    void minAndMaxReturnTheFieldValue() {

        QueryResult min = executor(
                new FakeReader(
                        Collections.singletonList(
                                new Document("_id", null)
                                        .append(
                                                "aggregateValue",
                                                "Alex"
                                        )
                        ),
                        null
                )
        ).execute(
                plan(ExecutionPlan.Aggregate.MIN, "name")
        );

        assertEquals("Alex", min.getAggregateValue());

        QueryResult max = executor(
                new FakeReader(
                        Collections.singletonList(
                                new Document("_id", null)
                                        .append("aggregateValue", 9L)
                        ),
                        null
                )
        ).execute(
                plan(ExecutionPlan.Aggregate.MAX, "age")
        );

        assertInstanceOf(Integer.class, max.getAggregateValue());
        assertEquals(9, max.getAggregateValue());
    }

    @Test
    void nullAggregateValueStaysNull() {

        QueryResult result = executor(
                new FakeReader(
                        Collections.singletonList(
                                new Document("_id", null)
                                        .append("aggregateValue", null)
                        ),
                        null
                )
        ).execute(
                plan(ExecutionPlan.Aggregate.AVG, "age")
        );

        assertNull(result.getAggregateValue());
    }

    @Test
    void countOutsideIntRangeFailsExplicitly() {

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(
                        new FakeReader(
                                Collections.singletonList(
                                        countResult(2_147_483_648L)
                                ),
                                null
                        )
                ).execute(
                        plan(ExecutionPlan.Aggregate.COUNT, null)
                )
        );

        assertTrue(
                error.getMessage().contains(
                        "cannot be represented as Integer"
                ),
                error.getMessage()
        );
    }

    @Test
    void driverFailureIsTranslated() {

        PlugException error = assertThrows(
                PlugException.class,
                () -> executor(
                        new FakeReader(
                                Collections.emptyList(),
                                new IllegalStateException("boom")
                        )
                ).execute(
                        plan(ExecutionPlan.Aggregate.COUNT, null)
                )
        );

        assertEquals(
                "Cannot aggregate MongoDB collection 'people': boom",
                error.getMessage()
        );
    }

    @Test
    void aggregateWithJoinIsRejectedBeforeReading() {

        FakeReader reader = new FakeReader(
                Collections.emptyList(),
                null
        );

        Query query = new Query(Collections.emptyList());

        query.join(
                new Join("people.id", "orders.person_id", "orders")
        );

        assertEquals(
                "An aggregate cannot be combined with join().",
                assertThrows(
                        PlugException.class,
                        () -> executor(reader).execute(
                                plan(
                                        query,
                                        ExecutionPlan.Aggregate.COUNT,
                                        null
                                )
                        )
                ).getMessage()
        );

        assertEquals(0, reader.calls);
    }

    @Test
    void moreThanOneGroupDocumentIsRejected() {

        assertEquals(
                "Aggregate returned more than one result.",
                assertThrows(
                        PlugException.class,
                        () -> executor(
                                new FakeReader(
                                        Arrays.asList(
                                                countResult(1),
                                                countResult(2)
                                        ),
                                        null
                                )
                        ).execute(
                                plan(
                                        ExecutionPlan.Aggregate.COUNT,
                                        null
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void adapterDelegatesAggregatesAndClearsOnClose() {

        FakeReader reader = new FakeReader(
                Collections.singletonList(
                        new Document("_id", null)
                                .append("aggregateValue", 2)
                                .append("valueCount", 2)
                ),
                null
        );

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.aggregateExecutor = new MongoAggregateExecutor(
                reader,
                adapter.schemaResolver
        );

        QueryResult result = adapter.execute(
                plan(ExecutionPlan.Aggregate.SUM, "age")
        );

        assertEquals(2, result.getAggregateValue());
        assertEquals("people", reader.collection);
        assertEquals(2, reader.pipeline.size());

        adapter.close();

        assertNull(adapter.aggregateExecutor);
    }

    @Test
    void adapterAggregateNeedsAConnection() {

        assertEquals(
                "No database connection.",
                assertThrows(
                        PlugException.class,
                        () -> new MongoDatabaseAdapter().execute(
                                plan(
                                        ExecutionPlan.Aggregate.COUNT,
                                        null
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void adapterStillRejectsPlainReads() {

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.aggregateExecutor = new MongoAggregateExecutor(
                new FakeReader(Collections.emptyList(), null),
                adapter.schemaResolver
        );

        assertEquals(
                "MongoDB adapter does not support query "
                        + "operations yet: READ",
                assertThrows(
                        PlugException.class,
                        () -> adapter.execute(
                                new ExecutionPlan(
                                        new Query(
                                                Arrays.asList("name")
                                        ),
                                        "people"
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void adapterRejectsAggregateWithJoinBeforeDispatch() {

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        adapter.schemaResolver = resolver();
        adapter.aggregateExecutor = new MongoAggregateExecutor(
                new FakeReader(Collections.emptyList(), null),
                adapter.schemaResolver
        );

        Query query = new Query(Collections.emptyList());

        query.join(
                new Join("people.id", "orders.person_id", "orders")
        );

        assertEquals(
                "An aggregate cannot be combined with join().",
                assertThrows(
                        PlugException.class,
                        () -> adapter.execute(
                                plan(
                                        query,
                                        ExecutionPlan.Aggregate.COUNT,
                                        null
                                )
                        )
                ).getMessage()
        );
    }
}
