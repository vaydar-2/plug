package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import org.bson.Document;
import org.bson.conversions.Bson;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoAggregateCompiler unit tests: stage order, shared WHERE
 * compilation, accumulator shape, and the aggregate contract
 * already enforced by Runtime and the other adapters.
 */
public class MongoAggregateCompilerTest {

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

    private static TableDefinition people() {

        return new TableDefinition(
                "people",
                Arrays.asList(
                        field(
                                "id",
                                TableDefinition.FieldType.INT,
                                TableDefinition.FieldConstraint.PRIMARY_KEY
                        ),
                        field("name", TableDefinition.FieldType.STRING),
                        field("age", TableDefinition.FieldType.INT),
                        field("balance", TableDefinition.FieldType.DECIMAL),
                        field("active", TableDefinition.FieldType.BOOL)
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

    private static MongoAggregateCompiler compiler() {
        return new MongoAggregateCompiler(resolver());
    }

    private static Map<String, Class<?>> schema() {
        return resolver().resolve("people").getFields();
    }

    private static Value number(int value) {
        return new Value(Value.Type.NUMBER, value);
    }

    private static Value string(String value) {
        return new Value(Value.Type.STRING, value);
    }

    private static ExecutionPlan plan(
            Query query,
            ExecutionPlan.Aggregate aggregate,
            String field
    ) {
        return new ExecutionPlan(query, "people", aggregate, field);
    }

    private static Document stage(
            MongoAggregateCompiler.CompiledAggregate compiled,
            int index
    ) {
        return (Document) compiled.getPipeline().get(index);
    }

    private static Document group(
            MongoAggregateCompiler.CompiledAggregate compiled
    ) {
        return (Document) stage(compiled, 1).get("$group");
    }

    @Test
    void countCompilesToMatchThenGroup() {

        Query query = new Query(Collections.emptyList())
                .where("age", "GTE", number(18));

        MongoAggregateCompiler.CompiledAggregate compiled =
                compiler().compile(
                        plan(query, ExecutionPlan.Aggregate.COUNT, null)
                );

        assertEquals("people", compiled.getCollection());
        assertEquals(
                ExecutionPlan.Aggregate.COUNT,
                compiled.getAggregate()
        );
        assertNull(compiled.getFieldType());

        assertEquals(2, compiled.getPipeline().size());
        assertTrue(stage(compiled, 0).containsKey("$match"));

        assertEquals(
                MongoQueryCompiler.compileFilter(
                        query,
                        schema()
                ),
                stage(compiled, 0).get("$match")
        );

        Document group = group(compiled);

        assertNull(group.get("_id"));
        assertEquals(
                new Document("$sum", 1),
                group.get("aggregateValue")
        );
        assertFalse(group.containsKey("valueCount"));
    }

    @Test
    void sumTracksNumericValuesForSqlEmptySemantics() {

        MongoAggregateCompiler.CompiledAggregate compiled =
                compiler().compile(
                        plan(
                                new Query(Collections.emptyList()),
                                ExecutionPlan.Aggregate.SUM,
                                "age"
                        )
                );

        Document group = group(compiled);

        assertEquals(
                new Document("$sum", "$age"),
                group.get("aggregateValue")
        );

        String counter = group.get("valueCount").toString();

        assertTrue(counter.contains("$type"), counter);
        assertTrue(counter.contains("$age"), counter);
        assertTrue(counter.contains("int"), counter);
        assertTrue(counter.contains("long"), counter);
        assertTrue(counter.contains("double"), counter);
        assertTrue(counter.contains("decimal"), counter);

        assertEquals(Integer.class, compiled.getFieldType());
    }

    @Test
    void avgMinAndMaxUseTheirMongoAccumulators() {

        assertAccumulator(
                ExecutionPlan.Aggregate.AVG,
                "balance",
                "$avg",
                "$balance"
        );

        assertAccumulator(
                ExecutionPlan.Aggregate.MIN,
                "name",
                "$min",
                "$name"
        );

        assertAccumulator(
                ExecutionPlan.Aggregate.MAX,
                "age",
                "$max",
                "$age"
        );
    }

    private static void assertAccumulator(
            ExecutionPlan.Aggregate aggregate,
            String field,
            String operator,
            String path
    ) {

        Document group = group(
                compiler().compile(
                        plan(
                                new Query(Collections.emptyList()),
                                aggregate,
                                field
                        )
                )
        );

        assertEquals(
                new Document(operator, path),
                group.get("aggregateValue")
        );
    }

    @Test
    void combinedWhereKeepsItsLogicalStructure() {

        Query query = new Query(Collections.emptyList())
                .where("age", "GTE", number(18))
                .or("name", "EQ", string("Alex"))
                .and(
                        "active",
                        "EQ",
                        new Value(Value.Type.BOOLEAN, true)
                );

        MongoAggregateCompiler.CompiledAggregate compiled =
                compiler().compile(
                        plan(
                                query,
                                ExecutionPlan.Aggregate.AVG,
                                "balance"
                        )
                );

        Bson expected = MongoQueryCompiler.compileFilter(
                query,
                schema()
        );

        Document match = (Document) stage(compiled, 0).get("$match");

        assertEquals(expected, match);
        assertTrue(match.toJson().contains("$or"));
        assertTrue(match.toJson().contains("$and"));
    }

    @Test
    void projectionFieldsDoNotAffectACount() {

        MongoAggregateCompiler.CompiledAggregate compiled =
                compiler().compile(
                        plan(
                                new Query(
                                        Arrays.asList("name", "age")
                                ),
                                ExecutionPlan.Aggregate.COUNT,
                                null
                        )
                );

        assertEquals(2, compiled.getPipeline().size());
        assertEquals(
                new Document("$sum", 1),
                group(compiled).get("aggregateValue")
        );

        assertFalse(
                group(compiled)
                        .toJson()
                        .contains("$name")
        );
    }

    @Test
    void unknownFieldIsRejected() {

        assertEquals(
                "Unknown field: nickname",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(
                                        new Query(
                                                Collections.emptyList()
                                        ),
                                        ExecutionPlan.Aggregate.MIN,
                                        "nickname"
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void sumRequiresANumericField() {

        assertEquals(
                "SUM requires a numeric field. "
                        + "Field 'name' is not numeric.",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(
                                        new Query(
                                                Collections.emptyList()
                                        ),
                                        ExecutionPlan.Aggregate.SUM,
                                        "name"
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void joinCombinedWithAggregateIsRejected() {

        Query query = new Query(Collections.emptyList());

        query.join(new Join("people.id", "orders.person_id", "orders"));

        assertEquals(
                "An aggregate cannot be combined with join().",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(
                                        query,
                                        ExecutionPlan.Aggregate.COUNT,
                                        null
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void sortAndLimitAreRejected() {

        Query sorted = new Query(Collections.emptyList());
        sorted.sort("age", "ASC");

        assertEquals(
                "sort()/limit() cannot be combined with an aggregate.",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(
                                        sorted,
                                        ExecutionPlan.Aggregate.MAX,
                                        "age"
                                )
                        )
                ).getMessage()
        );

        Query limited = new Query(Collections.emptyList());
        limited.limit(1);

        assertEquals(
                "sort()/limit() cannot be combined with an aggregate.",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(
                                        limited,
                                        ExecutionPlan.Aggregate.COUNT,
                                        null
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void nonAggregatePlanIsRejected() {

        assertEquals(
                "Aggregate operation is required.",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                new ExecutionPlan(
                                        new Query(
                                                Collections.emptyList()
                                        ),
                                        "people"
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void invalidWhereIsRejectedBeforeAQueryIsBuilt() {

        Query query = new Query(Collections.emptyList())
                .where("unknown", "EQ", number(1));

        assertEquals(
                "Unknown field: unknown",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(
                                        query,
                                        ExecutionPlan.Aggregate.COUNT,
                                        null
                                )
                        )
                ).getMessage()
        );
    }

    @Test
    void avgFieldTypeIsTheDeclaredTypeButResultIsDouble() {

        MongoAggregateCompiler.CompiledAggregate compiled =
                compiler().compile(
                        plan(
                                new Query(Collections.emptyList()),
                                ExecutionPlan.Aggregate.AVG,
                                "age"
                        )
                );

        assertEquals(Integer.class, compiled.getFieldType());
    }
}
