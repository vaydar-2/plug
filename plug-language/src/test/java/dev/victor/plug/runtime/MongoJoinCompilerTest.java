package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.NotExpression;
import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoJoinCompiler unit tests: aggregation pipeline shape,
 * resolver-driven physical paths, alias collisions, and the
 * validation rules shared with the mock and PostgreSQL.
 */
public class MongoJoinCompilerTest {

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

    private static TableDefinition table(
            String name,
            TableDefinition.FieldDefinition... fields
    ) {

        return new TableDefinition(
                name,
                Arrays.asList(fields)
        );
    }

    private static TableDefinition users() {

        return table(
                "users",
                field(
                        "id",
                        TableDefinition.FieldType.INT,
                        TableDefinition.FieldConstraint.PRIMARY_KEY
                ),
                field("name", TableDefinition.FieldType.STRING),
                field("country", TableDefinition.FieldType.STRING)
        );
    }

    private static TableDefinition orders() {

        return table(
                "orders",
                field(
                        "id",
                        TableDefinition.FieldType.INT,
                        TableDefinition.FieldConstraint.PRIMARY_KEY
                ),
                field("user_id", TableDefinition.FieldType.INT),
                field("total", TableDefinition.FieldType.DECIMAL),
                field("status", TableDefinition.FieldType.STRING)
        );
    }

    private static TableDefinition inventory() {

        return table(
                "inventory",
                field(
                        "id",
                        TableDefinition.FieldType.INT,
                        TableDefinition.FieldConstraint.PRIMARY_KEY
                ),
                field("order_id", TableDefinition.FieldType.INT),
                field("sku", TableDefinition.FieldType.STRING),
                field("total", TableDefinition.FieldType.DECIMAL)
        );
    }

    private static TableDefinition shipments() {

        return table(
                "shipments",
                field(
                        "id",
                        TableDefinition.FieldType.INT,
                        TableDefinition.FieldConstraint.PRIMARY_KEY
                ),
                field("order_id", TableDefinition.FieldType.INT),
                field("carrier", TableDefinition.FieldType.STRING)
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

        resolver.declare(users());
        resolver.declare(orders());
        resolver.declare(inventory());
        resolver.declare(shipments());

        return resolver;
    }

    private static MongoJoinCompiler compiler() {
        return new MongoJoinCompiler(resolver());
    }

    private static Query query(String... fields) {
        return new Query(Arrays.asList(fields));
    }

    private static ExecutionPlan plan(Query query) {
        return new ExecutionPlan(query, "users");
    }

    private static Join join(
            String left,
            String right,
            String collection
    ) {
        return new Join(left, right, collection);
    }

    private static Join usersOrders() {
        return join("users.id", "orders.user_id", "orders");
    }

    private static Join ordersInventory() {
        return join("orders.id", "inventory.order_id", "inventory");
    }

    private static Join inventoryShipments() {
        return join(
                "inventory.id",
                "shipments.order_id",
                "shipments"
        );
    }

    private static Value string(String value) {
        return new Value(Value.Type.STRING, value);
    }

    private static Value number(int value) {
        return new Value(Value.Type.NUMBER, value);
    }

    private static Value decimal(double value) {
        return new Value(Value.Type.NUMBER, value);
    }

    private static Document stage(
            MongoJoinCompiler.JoinedPlan plan,
            int index
    ) {
        return (Document) plan.getPipeline().get(index);
    }

    private static Document lookup(
            MongoJoinCompiler.JoinedPlan plan,
            int index
    ) {
        return (Document) stage(plan, index).get("$lookup");
    }

    private static Document project(
            MongoJoinCompiler.JoinedPlan plan
    ) {

        for (Bson bson : plan.getPipeline()) {

            Document stage = (Document) bson;

            if (stage.containsKey("$project")) {
                return (Document) stage.get("$project");
            }
        }

        throw new AssertionError("no $project stage");
    }

    private static Document sort(
            MongoJoinCompiler.JoinedPlan plan
    ) {

        for (Bson bson : plan.getPipeline()) {

            Document stage = (Document) bson;

            if (stage.containsKey("$sort")) {
                return (Document) stage.get("$sort");
            }
        }

        throw new AssertionError("no $sort stage");
    }

    private static Document matchAfterJoins(
            MongoJoinCompiler.JoinedPlan plan,
            int joins
    ) {
        return (Document) stage(plan, joins * 2).get("$match");
    }

    private static Map<String, String> paths(
            MongoJoinCompiler.JoinedPlan plan
    ) {

        Map<String, String> paths = new LinkedHashMap<>();

        for (MongoJoinCompiler.ProjectionField field :
                plan.getProjection()) {

            paths.put(field.getRequested(), field.getPath());
        }

        return paths;
    }

    /* ---------------- basic shape ---------------- */

    @Test
    void basicJoinProducesLookupUnwindMatchAndProject() {

        Query query = query("name", "orders.total");
        query.join(usersOrders());

        MongoJoinCompiler.JoinedPlan plan = compiler().compile(
                plan(query)
        );

        assertEquals(4, plan.getPipeline().size());

        Document lookup = lookup(plan, 0);

        assertEquals("orders", lookup.getString("from"));
        assertEquals("__join_0", lookup.getString("as"));

        Document let = (Document) lookup.get("let");
        assertEquals("$id", let.getString("localKey"));

        assertEquals(
                "$__join_0",
                stage(plan, 1).getString("$unwind")
        );

        assertTrue(stage(plan, 2).containsKey("$match"));

        Document project = project(plan);

        assertEquals(1, project.get("name"));
        assertEquals(1, project.get("__join_0.total"));
        assertEquals(0, project.get("_id"));
        assertFalse(project.containsKey("__join_0.total_x"));

        assertEquals(
                Arrays.asList("name", "orders.total"),
                requested(plan)
        );

        assertEquals(
                "name",
                paths(plan).get("name")
        );

        assertEquals(
                "__join_0.total",
                paths(plan).get("orders.total")
        );
    }

    private static List<String> requested(
            MongoJoinCompiler.JoinedPlan plan
    ) {

        List<String> requested = new ArrayList<>();

        for (MongoJoinCompiler.ProjectionField field :
                plan.getProjection()) {
            requested.add(field.getRequested());
        }

        return requested;
    }

    @Test
    void joinWithoutConditionsStillHasOneCombinedMatch() {

        Query query = query("name");
        query.join(usersOrders());

        MongoJoinCompiler.JoinedPlan plan = compiler().compile(
                plan(query)
        );

        assertEquals(
                new Document(),
                matchAfterJoins(plan, 1)
        );
    }

    @Test
    void reversedJoinKeyOrderUsesTheExistingSideAsLocal() {

        Query query = query("name", "orders.total");
        query.join(
                join("orders.user_id", "users.id", "orders")
        );

        Document lookup = lookup(
                compiler().compile(plan(query)),
                0
        );

        assertEquals(
                "$id",
                ((Document) lookup.get("let"))
                        .getString("localKey")
        );

        String json = lookup.toJson();

        assertTrue(json.contains("$user_id"));
    }

    /* ---------------- guard semantics ---------------- */

    @Test
    void lookupGuardsNullAndMissingKeysOnBothSides() {

        Query query = query("name");
        query.join(usersOrders());

        Document lookup = lookup(
                compiler().compile(plan(query)),
                0
        );

        List<?> pipeline = (List<?>) lookup.get("pipeline");

        Document match = (Document) pipeline.get(0);

        Document expression =
                (Document) match.get("$match");

        String json = expression.toJson();

        /*
         * One $type guard for the local variable and one for
         * the foreign field, plus the explicit null guards.
         */
        int typeGuards = json.split("\\$type", -1).length - 1;

        assertEquals(2, typeGuards);
        assertTrue(json.contains("\"missing\""));
        assertTrue(json.contains("$$localKey"));
        assertTrue(json.contains("$user_id"));
        assertTrue(json.contains("\"$eq\""));
    }

    /* ---------------- multiple joins ---------------- */

    @Test
    void twoSequentialJoinsRunBeforeTheCombinedMatch() {

        Query query = query(
                "name",
                "orders.total",
                "inventory.sku"
        );

        query.join(usersOrders());
        query.join(ordersInventory());
        query.where("orders.total", "GT", decimal(10));
        query.where("inventory.sku", "EQ", string("A-1"));

        MongoJoinCompiler.JoinedPlan plan = compiler().compile(
                plan(query)
        );

        assertEquals("orders", lookup(plan, 0).getString("from"));
        assertEquals("inventory", lookup(plan, 2).getString("from"));

        assertEquals(
                "$__join_0.id",
                ((Document) lookup(plan, 2).get("let"))
                        .getString("localKey")
        );

        assertEquals(
                "$__join_1",
                stage(plan, 3).getString("$unwind")
        );

        Document filter = matchAfterJoins(plan, 2);

        String json = filter.toJson();

        assertTrue(json.contains("__join_0.total"));
        assertTrue(json.contains("__join_1.sku"));
    }

    @Test
    void threeSequentialJoinsUseDistinctAliases() {

        Query query = query(
                "name",
                "orders.total",
                "inventory.sku",
                "shipments.carrier"
        );

        query.join(usersOrders());
        query.join(ordersInventory());
        query.join(inventoryShipments());

        MongoJoinCompiler.JoinedPlan plan = compiler().compile(
                plan(query)
        );

        assertEquals("__join_0", lookup(plan, 0).getString("as"));
        assertEquals("__join_1", lookup(plan, 2).getString("as"));
        assertEquals("__join_2", lookup(plan, 4).getString("as"));

        assertEquals(
                "$__join_1.id",
                ((Document) lookup(plan, 4).get("let"))
                        .getString("localKey")
        );

        assertEquals(8, plan.getPipeline().size());
    }

    /* ---------------- aliases ---------------- */

    @Test
    void internalAliasDoesNotOverwriteAUserField() {

        MongoSchemaResolver resolver = resolver();

        resolver.declare(
                table(
                        "users",
                        field(
                                "id",
                                TableDefinition.FieldType.INT,
                                TableDefinition.FieldConstraint.PRIMARY_KEY
                        ),
                        field("name", TableDefinition.FieldType.STRING),
                        field(
                                "__join_0",
                                TableDefinition.FieldType.STRING
                        )
                )
        );

        Query query = query("name", "orders.total");
        query.join(usersOrders());

        MongoJoinCompiler.JoinedPlan plan =
                new MongoJoinCompiler(resolver)
                        .compile(plan(query));

        assertEquals(
                "__join_0_1",
                lookup(plan, 0).getString("as")
        );

        assertEquals(
                "$__join_0_1",
                stage(plan, 1).getString("$unwind")
        );

        assertEquals(
                "__join_0_1.total",
                project(plan).containsKey("__join_0_1.total")
                        ? "__join_0_1.total"
                        : null
        );

        assertFalse(project(plan).containsKey("__join_0"));
    }

    /* ---------------- projection resolution ---------------- */

    @Test
    void qualifiedFieldsResolveToBaseAndJoinedPaths() {

        Query query = query(
                "users.id",
                "users.name",
                "orders.total"
        );

        query.join(usersOrders());

        Map<String, String> paths = paths(
                compiler().compile(plan(query))
        );

        assertEquals("id", paths.get("users.id"));
        assertEquals("name", paths.get("users.name"));
        assertEquals("__join_0.total", paths.get("orders.total"));
    }

    @Test
    void bareProjectionPrefersBaseThenFirstJoinedCollection() {

        Query query = query("id", "total");
        query.join(usersOrders());
        query.join(ordersInventory());

        Map<String, String> paths = paths(
                compiler().compile(plan(query))
        );

        assertEquals("id", paths.get("id"));
        assertEquals("__join_0.total", paths.get("total"));
    }

    @Test
    void ambiguousBareConditionIsRejected() {

        Query query = query("name");
        query.join(usersOrders());
        query.join(ordersInventory());
        query.where("total", "GT", decimal(10));

        PlugException error = assertThrows(
                PlugException.class,
                () -> compiler().compile(plan(query))
        );

        assertEquals(
                "Ambiguous field: total "
                        + "(orders.total, inventory.total)",
                error.getMessage()
        );
    }

    @Test
    void qualifiedConditionUsesPhysicalPathAfterAllJoins() {

        Query query = query("name");
        query.join(usersOrders());
        query.join(ordersInventory());
        query.where("inventory.sku", "EQ", string("A-1"));

        Document filter = matchAfterJoins(
                compiler().compile(plan(query)),
                2
        );

        assertEquals(
                new Document(
                        "__join_1.sku",
                        new Document("$eq", "A-1")
                ),
                filter
        );
    }

    @Test
    void combinedLogicalConditionsKeepTheirStructureAfterJoins() {

        Query query = query("name");
        query.join(usersOrders());
        query.where("country", "EQ", string("Ghana"));
        query.or("orders.status", "EQ", string("open"));
        query.expression(
                new NotExpression(
                        new ComparisonExpression(
                                "orders.total",
                                "GT",
                                decimal(10)
                        )
                )
        );

        Document filter = matchAfterJoins(
                compiler().compile(plan(query)),
                1
        );

        assertTrue(filter.containsKey("$or"));
        assertTrue(filter.toJson().contains("__join_0.total"));
        assertTrue(
                filter.toJson().contains("$nor"),
                filter.toJson()
        );
    }

    @Test
    void unknownProjectionFieldsAreRejected() {

        Query unknown = query("nope");
        unknown.join(usersOrders());

        assertEquals(
                "Unknown field: nope",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(plan(unknown))
                ).getMessage()
        );

        Query unknownQualified = query("orders.nope");
        unknownQualified.join(usersOrders());

        assertEquals(
                "Unknown field: orders.nope",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(unknownQualified)
                        )
                ).getMessage()
        );

        Query unknownCollection = query("other.name");
        unknownCollection.join(usersOrders());

        assertEquals(
                "Unknown field: other.name",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(
                                plan(unknownCollection)
                        )
                ).getMessage()
        );
    }

    @Test
    void emptyProjectionIsRejected() {

        Query query = query();
        query.join(usersOrders());

        assertEquals(
                "get() expects at least one field.",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(plan(query))
                ).getMessage()
        );
    }

    /* ---------------- validation ---------------- */

    @Test
    void unknownJoinedCollectionIsRejected() {

        Query query = query("name");
        query.join(
                join("users.id", "missing.user_id", "missing")
        );

        assertEquals(
                "Unknown collection: missing",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(plan(query))
                ).getMessage()
        );
    }

    @Test
    void joinKeyTypeMismatchIsRejected() {

        Query query = query("name");
        query.join(join("users.id", "orders.total", "orders"));

        PlugException error = assertThrows(
                PlugException.class,
                () -> compiler().compile(plan(query))
        );

        assertEquals(
                "Type mismatch in join key: users.id (Integer) "
                        + "vs orders.total (Double)",
                error.getMessage()
        );
    }

    @Test
    void duplicateJoinedCollectionIsRejected() {

        Query query = query("name");
        query.join(usersOrders());
        query.join(usersOrders());

        assertEquals(
                "Duplicate collection in join: orders",
                assertThrows(
                        PlugException.class,
                        () -> compiler().compile(plan(query))
                ).getMessage()
        );
    }

    /* ---------------- sort and limit ---------------- */

    @Test
    void sortUsesTheResolvedPathAndKeepsItProjected() {

        Query query = query("name");
        query.join(usersOrders());
        query.sort("orders.total", "DESC");

        MongoJoinCompiler.JoinedPlan plan = compiler().compile(
                plan(query)
        );

        assertEquals(
                -1,
                sort(plan).get("__join_0.total")
        );

        assertEquals(1, project(plan).get("__join_0.total"));
    }

    @Test
    void projectionSortAndLimitAreOrderedAsRequired() {

        Query query = query("name");
        query.join(usersOrders());
        query.sort("id", "ASC");
        query.limit(2);

        List<Bson> pipeline = compiler()
                .compile(plan(query))
                .getPipeline();

        assertTrue(((Document) pipeline.get(2))
                .containsKey("$match"));

        assertTrue(((Document) pipeline.get(3))
                .containsKey("$project"));

        assertTrue(((Document) pipeline.get(4))
                .containsKey("$sort"));

        assertEquals(
                2,
                ((Document) pipeline.get(5)).get("$limit")
        );
    }

    @Test
    void limitZeroUsesAnAlwaysFalseMatchInsteadOfLimit() {

        Query query = query("name");
        query.join(usersOrders());
        query.limit(0);

        MongoJoinCompiler.JoinedPlan plan = compiler().compile(
                plan(query)
        );

        boolean hasLimit = false;

        for (Bson bson : plan.getPipeline()) {

            if (((Document) bson).containsKey("$limit")) {
                hasLimit = true;
            }
        }

        assertFalse(hasLimit);

        Document guard = (Document) stage(plan, 3).get("$match");

        assertEquals(
                Collections.emptyList(),
                ((Document) guard.get("_id")).get("$in")
        );
    }
}
