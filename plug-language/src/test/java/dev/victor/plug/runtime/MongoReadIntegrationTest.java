package dev.victor.plug.runtime;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/*
 * Live MongoDB coverage for plain reads: actual find() filters,
 * projection, server-side sorting/limits, explicit-null versus
 * missing behavior, and exact BSON materialization.
 *
 * Skips unless PLUG_MONGO_HOST is set. Optional:
 * PLUG_MONGO_PORT (27017), PLUG_MONGO_DB (plugdb).
 */
public class MongoReadIntegrationTest {

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

    private static Value number(int value) {
        return new Value(Value.Type.NUMBER, value);
    }

    @Test
    void readsFilterSortLimitAndMaterializeAgainstLiveMongo() {

        String host = System.getenv("PLUG_MONGO_HOST");

        assumeTrue(
                host != null,
                "No PLUG_MONGO_HOST configured; integration "
                        + "tests skip silently"
        );

        String collection =
                "plug_read_people_" + System.nanoTime();

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
                            new Document("id", 1L)
                                    .append("name", "Alex")
                                    .append("age", 30)
                                    .append("balance", 10.5)
                                    .append("active", true),
                            new Document("id", 2)
                                    .append("name", "Grace")
                                    .append("age", 20)
                                    .append("balance", 5.0)
                                    .append("active", false),
                            new Document("id", 3)
                                    .append("name", null)
                                    .append("age", null)
                                    .append("balance", null)
                                    .append("active", null),
                            new Document("id", 4),
                            new Document("id", 5)
                                    .append("name", "Kofi")
                                    .append("age", 25)
                                    .append("balance", 7.5)
                                    .append("active", true)
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
                                    ),
                                    field(
                                            "active",
                                            TableDefinition.FieldType.BOOL
                                    )
                            )
                    )
            );

            MongoReadExecutor executor =
                    new MongoReadExecutor(database, resolver);

            Query filtered = new Query(
                    Arrays.asList("id", "name", "age")
            );

            filtered.where("age", "GTE", number(20));
            filtered.sort("age", "DESC");
            filtered.limit(2);

            QueryResult result = executor.execute(
                    new ExecutionPlan(filtered, collection)
            );

            assertEquals(2, result.getCount());

            assertRow(
                    result.getRows().get(0),
                    1,
                    "Alex",
                    30
            );

            assertRow(
                    result.getRows().get(1),
                    5,
                    "Kofi",
                    25
            );

            Query ascending = new Query(
                    Arrays.asList("id", "name")
            );

            ascending.sort("id", "ASC");

            QueryResult all = executor.execute(
                    new ExecutionPlan(ascending, collection)
            );

            assertEquals(5, all.getCount());
            assertEquals(
                    Arrays.asList(1, 2, 3, 4, 5),
                    ids(all)
            );

            /*
             * EQ null is defined as an explicit BSON null, not a
             * missing field, so the document without name must
             * not be returned.
             */
            Query explicitNull = new Query(
                    Arrays.asList("id", "name")
            );

            explicitNull.where(
                    "name",
                    "EQ",
                    new Value(Value.Type.NULL, null)
            );

            QueryResult nulls = executor.execute(
                    new ExecutionPlan(explicitNull, collection)
            );

            assertEquals(1, nulls.getCount());
            assertEquals(3, nulls.getRows().get(0).get("id"));
            assertNull(nulls.getRows().get(0).get("name"));

            Query missing = new Query(
                    Arrays.asList("name", "age", "balance")
            );

            missing.where("id", "EQ", number(4));

            QueryResult missingRow = executor.execute(
                    new ExecutionPlan(missing, collection)
            );

            assertEquals(1, missingRow.getCount());

            assertEquals(
                    Arrays.asList("name", "age", "balance"),
                    new ArrayList<>(
                            missingRow.getRows().get(0).keySet()
                    )
            );

            assertNull(missingRow.getRows().get(0).get("name"));
            assertNull(missingRow.getRows().get(0).get("age"));
            assertNull(missingRow.getRows().get(0).get("balance"));

            Query noRows = new Query(
                    Arrays.asList("name")
            );

            noRows.limit(0);

            QueryResult limited = executor.execute(
                    new ExecutionPlan(noRows, collection)
            );

            assertEquals(0, limited.getCount());
            assertEquals(0, limited.getRows().size());

            Query noMatch = new Query(
                    Arrays.asList("name")
            );

            noMatch.where("age", "GT", number(100));

            QueryResult empty = executor.execute(
                    new ExecutionPlan(noMatch, collection)
            );

            assertEquals(0, empty.getCount());
            assertEquals(0, empty.getRows().size());

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

    private static void assertRow(
            Map<String, Object> row,
            int id,
            String name,
            int age
    ) {

        assertEquals(
                Arrays.asList("id", "name", "age"),
                new ArrayList<>(row.keySet())
        );
        assertEquals(id, row.get("id"));
        assertEquals(name, row.get("name"));
        assertEquals(age, row.get("age"));
    }

    private static List<Integer> ids(QueryResult result) {

        List<Integer> ids = new ArrayList<>();

        for (Map<String, Object> row : result.getRows()) {
            ids.add((Integer) row.get("id"));
        }

        return ids;
    }
}
