package dev.victor.plug.runtime;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;
import org.bson.types.Decimal128;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/*
 * Live MongoDB coverage for the details a pipeline shape test
 * cannot prove: the lookup really rejects null and missing
 * keys on both sides, $unwind really removes non-matches, and
 * a combined WHERE runs after the joins.
 *
 * Skips unless PLUG_MONGO_HOST is set. Optional:
 * PLUG_MONGO_PORT (27017), PLUG_MONGO_DB (plugdb).
 */
public class MongoJoinIntegrationTest {

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

    private static Value decimal(double value) {
        return new Value(Value.Type.NUMBER, value);
    }

    @Test
    void joinsRejectNullAndMissingKeysAndFilterAfterJoining() {

        String host = System.getenv("PLUG_MONGO_HOST");

        assumeTrue(
                host != null,
                "No PLUG_MONGO_HOST configured; integration "
                        + "tests skip silently"
        );

        String users =
                "plug_join_users_" + System.nanoTime();

        String orders =
                "plug_join_orders_" + System.nanoTime();

        MongoClient client = MongoClients.create(
                "mongodb://" + host + ":"
                        + env("PLUG_MONGO_PORT", "27017")
        );

        try {

            MongoDatabase database = client.getDatabase(
                    env("PLUG_MONGO_DB", "plugdb")
            );

            database.createCollection(users);
            database.createCollection(orders);

            MongoSchemaResolver resolver =
                    new MongoSchemaResolver(database);

            resolver.declare(
                    new TableDefinition(
                            users,
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
                            orders,
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

            database.getCollection(users).insertMany(
                    Arrays.asList(
                            new Document("id", 1)
                                    .append("name", "Alex"),
                            new Document("id", 2)
                                    .append("name", "Grace"),
                            new Document("id", 3)
                                    .append("name", "NoOrders"),
                            new Document("id", null)
                                    .append("name", "NullId"),
                            new Document("name", "MissingId")
                    )
            );

            database.getCollection(orders).insertMany(
                    Arrays.asList(
                            new Document("id", 1)
                                    .append("user_id", 1)
                                    .append("total", decimal128("10")),
                            new Document("id", 2)
                                    .append("user_id", 1)
                                    .append("total", decimal128("20")),
                            new Document("id", 3)
                                    .append("user_id", 2)
                                    .append("total", decimal128("5")),
                            new Document("id", 4)
                                    .append("user_id", null)
                                    .append("total", decimal128("99")),
                            new Document("id", 5)
                                    .append("total", decimal128("100"))
                    )
            );

            Query query = new Query(
                    Arrays.asList(
                            "name",
                            orders + ".total"
                    )
            );

            query.join(
                    new Join(
                            users + ".id",
                            orders + ".user_id",
                            orders
                    )
            );

            query.sort(orders + ".id", "ASC");

            QueryResult all = new MongoJoinExecutor(
                    database,
                    resolver
            ).execute(
                    new ExecutionPlan(query, users)
            );

            assertEquals(3, all.getCount());

            assertRow(all.getRows().get(0), "Alex", 10.0);
            assertRow(all.getRows().get(1), "Alex", 20.0);
            assertRow(all.getRows().get(2), "Grace", 5.0);

            Query filtered = new Query(
                    Arrays.asList(
                            "name",
                            orders + ".total"
                    )
            );

            filtered.join(
                    new Join(
                            users + ".id",
                            orders + ".user_id",
                            orders
                    )
            );

            filtered.where(
                    orders + ".total",
                    "GTE",
                    decimal(10)
            );

            filtered.sort(orders + ".id", "ASC");

            QueryResult matched = new MongoJoinExecutor(
                    database,
                    resolver
            ).execute(
                    new ExecutionPlan(filtered, users)
            );

            assertEquals(2, matched.getCount());

            assertRow(
                    matched.getRows().get(0),
                    "Alex",
                    10.0
            );

            assertRow(
                    matched.getRows().get(1),
                    "Alex",
                    20.0
            );

        } finally {

            try {

                MongoDatabase database = client.getDatabase(
                        env("PLUG_MONGO_DB", "plugdb")
                );

                database.getCollection(users).drop();
                database.getCollection(orders).drop();

            } finally {

                client.close();
            }
        }
    }

    private static Decimal128 decimal128(String value) {
        return new Decimal128(new BigDecimal(value));
    }

    private static void assertRow(
            Map<String, Object> row,
            String name,
            double total
    ) {

        assertEquals(name, row.get("name"));
        assertEquals(total, row.get(rowsTotalKey(row)));
        assertEquals(
                Arrays.asList("name", rowsTotalKey(row)),
                List.copyOf(row.keySet())
        );
    }

    /*
     * The qualified output key uses the generated collection
     * name, so read it from the row instead of repeating it.
     */
    private static String rowsTotalKey(
            Map<String, Object> row
    ) {

        for (String key : row.keySet()) {

            if (key.endsWith(".total")) {
                return key;
            }
        }

        throw new AssertionError(
                "no qualified total field in " + row
        );
    }
}
