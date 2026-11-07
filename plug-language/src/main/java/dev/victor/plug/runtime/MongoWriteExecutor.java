package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Executes Plug's INSERT, UPDATE and DELETE against MongoDB.
 *
 * Every write starts from the effective M3 schema, so MongoDB's
 * flexible documents never bypass Plug's stricter contract:
 * unknown fields and type mismatches are rejected before any
 * driver call, and required/primary-key rules are enforced here
 * as well as by the M4 validator.
 *
 * Insert also applies the Plug semantics MongoDB cannot express
 * in a validator: omitted fields with a declared default get
 * that default, every other omitted schema field is stored as
 * an explicit BSON null (so EQ null / IS NULL find it), and a
 * primary key is never generated.
 *
 * UPDATE and DELETE compile their WHERE through
 * MongoQueryCompiler.compileFilter, so null/missing and logical
 * semantics are exactly the M2 ones. Both refuse a conditionless
 * plan defensively, even though Runtime already rejects one.
 *
 * Pure sequencing and translation otherwise: driver mechanics
 * live behind Store, which tests replace with a recording double.
 */
final class MongoWriteExecutor {

    /*
     * The MongoDB surface writes need. Production uses the
     * driver-backed implementation; tests record documents,
     * filters and counts.
     */
    interface Store {

        long count(String collection, Bson filter);

        void insert(String collection, Document document);

        long updateMany(
                String collection,
                Bson filter,
                Bson update
        );

        long deleteMany(String collection, Bson filter);
    }

    private static final int DUPLICATE_KEY = 11000;

    private final Store store;

    private final MongoSchemaResolver resolver;

    MongoWriteExecutor(
            MongoDatabase database,
            MongoSchemaResolver resolver
    ) {

        if (database == null) {

            throw new PlugException(
                    "MongoDB database is required."
            );
        }

        this.store = new DriverStore(database);
        this.resolver = requireResolver(resolver);
    }

    /*
     * Package-private seam for focused tests.
     */
    MongoWriteExecutor(
            Store store,
            MongoSchemaResolver resolver
    ) {

        if (store == null) {

            throw new PlugException(
                    "Write store is required."
            );
        }

        this.store = store;
        this.resolver = requireResolver(resolver);
    }

    private static MongoSchemaResolver requireResolver(
            MongoSchemaResolver resolver
    ) {

        if (resolver == null) {

            throw new PlugException(
                    "Schema resolver is required."
            );
        }

        return resolver;
    }

    QueryResult insert(ExecutionPlan plan) {

        requirePlan(plan, "insert");

        String collection = plan.getCollection();

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve(collection);

        Map<String, Class<?>> schema = resolved.getFields();

        Map<String, Object> data = plan.getData();

        QueryValidation.validateWriteFields(data, schema);

        TableDefinition definition =
                resolved.getDefinition();

        Map<String, Object> values =
                new LinkedHashMap<>(data);

        applyDefaults(definition, data, values, schema);

        requireExplicitPrimaryKey(definition, data);

        requireNonNull(resolved, values);

        String primaryField = primaryKeyField(definition);

        if (primaryField != null) {

            Object primaryValue = data.get(primaryField);

            if (count(
                    collection,
                    new Document(primaryField, primaryValue)
            ) > 0) {

                throw duplicatePrimaryKey(
                        primaryField,
                        primaryValue
                );
            }
        }

        /*
         * Fields absent from the supplied data are stored as
         * explicit nulls. This is what makes Plug's explicit
         * null distinct from a missing field on Mongo, matching
         * the mock and PostgreSQL adapters.
         */
        Document document = new Document();

        for (String field : schema.keySet()) {
            document.put(field, values.get(field));
        }

        try {

            store.insert(collection, document);

        } catch (RuntimeException e) {

            throw writeFailure(
                    "INSERT",
                    collection,
                    e
            );
        }

        return new QueryResult(
                1,
                new ArrayList<>()
        );
    }

    QueryResult update(ExecutionPlan plan) {

        requirePlan(plan, "update");

        requireWhere(plan, "update");

        String collection = plan.getCollection();

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve(collection);

        Map<String, Class<?>> schema = resolved.getFields();

        QueryValidation.validateWhere(plan.getQuery(), schema);
        QueryValidation.validateWriteFields(plan.getData(), schema);

        if (plan.getData().isEmpty()) {

            throw new PlugException(
                    "update() expects at least one field."
            );
        }

        Bson filter = MongoQueryCompiler.compileFilter(
                plan.getQuery(),
                schema
        );

        Bson update = new Document(
                "$set",
                new Document(plan.getData())
        );

        try {

            return new QueryResult(
                    (int) store.updateMany(
                            collection,
                            filter,
                            update
                    ),
                    new ArrayList<>()
            );

        } catch (RuntimeException e) {

            throw writeFailure(
                    "UPDATE",
                    collection,
                    e
            );
        }
    }

    QueryResult delete(ExecutionPlan plan) {

        requirePlan(plan, "delete");

        requireWhere(plan, "delete");

        String collection = plan.getCollection();

        Map<String, Class<?>> schema =
                resolver.resolve(collection).getFields();

        QueryValidation.validateWhere(plan.getQuery(), schema);

        Bson filter = MongoQueryCompiler.compileFilter(
                plan.getQuery(),
                schema
        );

        try {

            return new QueryResult(
                    (int) store.deleteMany(collection, filter),
                    new ArrayList<>()
            );

        } catch (RuntimeException e) {

            throw writeFailure(
                    "DELETE",
                    collection,
                    e
            );
        }
    }

    /*
     * Declared defaults fill only fields the caller omitted.
     * A primary key is never defaulted: it must be supplied
     * explicitly, matching the milestone's no-generation rule.
     * A DECIMAL default written as an integer literal becomes
     * the field's Double type, as in the mock adapter.
     */
    private static void applyDefaults(
            TableDefinition definition,
            Map<String, Object> data,
            Map<String, Object> values,
            Map<String, Class<?>> schema
    ) {

        if (definition == null) {
            return;
        }

        for (TableDefinition.FieldDefinition field :
                definition.getFields()) {

            if (field.hasConstraint(
                    TableDefinition.FieldConstraint.PRIMARY_KEY
            )) {
                continue;
            }

            if (!field.hasConstraint(
                    TableDefinition.FieldConstraint.DEFAULT
            )) {
                continue;
            }

            if (data.containsKey(field.getName())) {
                continue;
            }

            Object value =
                    field.getDefaultValue().getValue();

            if (schema.get(field.getName()) == Double.class
                    && value instanceof Integer) {

                value = ((Integer) value).doubleValue();
            }

            values.put(field.getName(), value);
        }
    }

    private static void requireExplicitPrimaryKey(
            TableDefinition definition,
            Map<String, Object> data
    ) {

        String field = primaryKeyField(definition);

        if (field == null) {
            return;
        }

        if (!data.containsKey(field)) {

            throw new PlugException(
                    "Missing primary key: " + field
            );
        }

        if (data.get(field) == null) {

            throw new PlugException(
                    "Primary key '" + field
                            + "' cannot be null."
            );
        }
    }

    /*
     * Required fields must be present and non-null after
     * defaults have been applied. This mirrors the NOT NULL
     * contract of the mock and PostgreSQL adapters and fails
     * before MongoDB's validator has to.
     */
    private static void requireNonNull(
            MongoSchemaResolver.ResolvedSchema resolved,
            Map<String, Object> values
    ) {

        for (String field : resolved.getRequiredFields()) {

            if (!values.containsKey(field)) {

                throw new PlugException(
                        "Missing required field: " + field
                );
            }

            if (values.get(field) == null) {

                throw new PlugException(
                        "Required field '" + field
                                + "' cannot be null."
                );
            }
        }
    }

    private static String primaryKeyField(
            TableDefinition definition
    ) {

        if (definition == null) {
            return null;
        }

        for (TableDefinition.FieldDefinition field :
                definition.getFields()) {

            if (field.hasConstraint(
                    TableDefinition.FieldConstraint.PRIMARY_KEY
            )) {
                return field.getName();
            }
        }

        return null;
    }

    private static PlugException duplicatePrimaryKey(
            String field,
            Object value
    ) {

        if ("id".equals(field)) {

            return new PlugException(
                    "Duplicate id: " + value
            );
        }

        return new PlugException(
                "Duplicate primary key '" + field
                        + "': " + value
        );
    }

    private static void requirePlan(
            ExecutionPlan plan,
            String operation
    ) {

        if (plan == null || plan.getOperation() == null) {

            throw new PlugException(
                    "Execution plan is required."
            );
        }

        if (plan.getCollection() == null
                || plan.getCollection().isBlank()) {

            throw new PlugException(
                    "Collection name is required."
            );
        }

        if (operation.equals("insert")) {
            return;
        }

        if (plan.getQuery() == null) {

            throw new PlugException(
                    operation + "() requires a where() condition."
            );
        }
    }

    /*
     * Adapter-side safety: a write plan with no predicate must
     * never reach the store, even if it was hand-built instead
     * of coming through Runtime.
     */
    private static void requireWhere(
            ExecutionPlan plan,
            String operation
    ) {

        Query query = plan.getQuery();

        if (query.getConditions().isEmpty()
                && query.getExpression() == null) {

            throw new PlugException(
                    operation + "() requires a where() condition."
            );
        }
    }

    private long count(String collection, Bson filter) {

        try {

            return store.count(collection, filter);

        } catch (RuntimeException e) {

            throw new PlugException(
                    "Cannot check MongoDB collection '"
                            + collection + "': "
                            + e.getMessage()
            );
        }
    }

    private static PlugException writeFailure(
            String operation,
            String collection,
            RuntimeException e
    ) {

        if (isDuplicateKey(e)) {

            return new PlugException(
                    "Duplicate value for a unique constraint "
                            + "in collection '" + collection
                            + "': " + e.getMessage()
            );
        }

        return new PlugException(
                operation + " failed: " + e.getMessage()
        );
    }

    private static boolean isDuplicateKey(RuntimeException e) {

        return e instanceof MongoWriteException
                && ((MongoWriteException) e)
                .getError()
                .getCode() == DUPLICATE_KEY;
    }

    /*
     * Driver-backed store: the configured database's
     * collection handles, with counts and affected-document
     * counts returned unchanged.
     */
    private static final class DriverStore implements Store {

        private final MongoDatabase database;

        private DriverStore(MongoDatabase database) {
            this.database = database;
        }

        @Override
        public long count(String collection, Bson filter) {

            return database.getCollection(collection)
                    .countDocuments(filter);
        }

        @Override
        public void insert(
                String collection,
                Document document
        ) {

            database.getCollection(collection)
                    .insertOne(document);
        }

        @Override
        public long updateMany(
                String collection,
                Bson filter,
                Bson update
        ) {

            return database.getCollection(collection)
                    .updateMany(filter, update)
                    .getMatchedCount();
        }

        @Override
        public long deleteMany(
                String collection,
                Bson filter
        ) {

            return database.getCollection(collection)
                    .deleteMany(filter)
                    .getDeletedCount();
        }
    }
}
