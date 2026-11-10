package dev.victor.plug.runtime;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/*
 * Live MongoDB coverage for aggregate details a pure pipeline
 * test cannot prove: matching applies before folding, null and
 * missing values are skipped, an all-null SUM is null rather
 * than MongoDB's raw zero, and COUNT over no matches is zero.
 *
 * Skips unless PLUG_MONGO_HOST is set. Optional:
 * PLUG_MONGO_PORT (27017), PLUG_MONGO_DB (plugdb).
 */
public class MongoAggregateIntegrationTest {

    private static String env(String name, String fallback) {

        String value = System.getenv(name);

        return value == null ? fallback : value;
    }

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

    @Test
    void aggregatesFilterBeforeFoldingAndSkipNullValues() {

        String host = System.getenv("PLUG_MONGO_HOST");

        assumeTrue(
                host != null,
                "No PLUG_MONGO_HOST configured; integration "
                        + "tests skip silently"
        );

        String collection =
                "plug_agg_people_" + System.nanoTime();

        MongoClient client = MongoClients.create(
                "mongodb://" + host + ":"
                        + env("PLUG_MONGO_PORT", "27017")
        );

        try {

            MongoDatabase database = client.getDatabase(
                    env("PLUG_MONGO_DB", "plugdb")
            );

            database.createCollection(collection);

            database.getCollection(collection).insertMany(
                    Arrays.asList(
                            new Document("id", 1)
                                    .append("name", "b")
                                    .append("age", 10)
                                    .append("balance", 1.5),
                            new Document("id", 2)
                                    .append("name", "a")
                                    .append("age", 20)
                                    .append("balance", 2.5),
                            new Document("id", 3)
                                    .append("name", null)
                                    .append("age", null)
                                    .append("balance", null),
                            new Document("id", 4)
                    )
            );

            MongoSchemaResolver resolver =
                    new MongoSchemaResolver(database);

            resolver.declare(
                    new TableDefinition(
                            collection,
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

            MongoAggregateExecutor executor =
                    new MongoAggregateExecutor(database, resolver);

            assertEquals(
                    4,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.COUNT,
                            null,
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            assertEquals(
                    0,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.COUNT,
                            null,
                            new Query(Collections.emptyList())
                                    .where("age", "LT", number(-1))
                    ).getAggregateValue()
            );

            assertEquals(
                    1,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.COUNT,
                            null,
                            new Query(Collections.emptyList())
                                    .where("age", "GTE", number(20))
                    ).getAggregateValue()
            );

            assertEquals(
                    30,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.SUM,
                            "age",
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            assertEquals(
                    15.0,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.AVG,
                            "age",
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            assertEquals(
                    4.0,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.SUM,
                            "balance",
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            assertEquals(
                    10,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.MIN,
                            "age",
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            assertEquals(
                    20,
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.MAX,
                            "age",
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            assertEquals(
                    "a",
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.MIN,
                            "name",
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            assertEquals(
                    "b",
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.MAX,
                            "name",
                            new Query(Collections.emptyList())
                    ).getAggregateValue()
            );

            /*
             * id 3 has explicit nulls and id 4 has missing
             * fields. MongoDB's raw $sum is 0 in both cases;
             * Plug's established result is null. AVG, MIN and
             * MAX have the same empty-value result.
             */
            assertNull(
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.SUM,
                            "age",
                            new Query(Collections.emptyList())
                                    .where("id", "EQ", number(3))
                    ).getAggregateValue()
            );

            assertNull(
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.SUM,
                            "balance",
                            new Query(Collections.emptyList())
                                    .where("id", "EQ", number(4))
                    ).getAggregateValue()
            );

            assertNull(
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.AVG,
                            "age",
                            new Query(Collections.emptyList())
                                    .where("id", "EQ", number(3))
                    ).getAggregateValue()
            );

            assertNull(
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.MIN,
                            "name",
                            new Query(Collections.emptyList())
                                    .where("id", "EQ", number(4))
                    ).getAggregateValue()
            );

            assertNull(
                    run(
                            executor,
                            collection,
                            ExecutionPlan.Aggregate.MAX,
                            "age",
                            new Query(Collections.emptyList())
                                    .where("id", "EQ", number(4))
                    ).getAggregateValue()
            );

        } finally {

            try {

                MongoDatabase database = client.getDatabase(
                        env("PLUG_MONGO_DB", "plugdb")
                );

                database.getCollection(collection).drop();

            } finally {

                client.close();
            }
        }
    }

    private static QueryResult run(
            MongoAggregateExecutor executor,
            String collection,
            ExecutionPlan.Aggregate aggregate,
            String field,
            Query query
    ) {

        return executor.execute(
                new ExecutionPlan(
                        query,
                        collection,
                        aggregate,
                        field
                )
        );
    }

    private static Value number(int value) {
        return new Value(Value.Type.NUMBER, value);
    }
}
