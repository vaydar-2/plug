package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;

import java.util.ArrayList;
import java.util.List;

/*
 * Executes a compiled MongoDB CREATE TABLE: collection
 * creation with the generated $jsonSchema validator, then the
 * unique indexes, then session registration of the declared
 * TableDefinition.
 *
 * The class is deliberately separate from MongoDatabaseAdapter
 * so the whole lifecycle can be tested against a focused Admin
 * double. It owns only sequencing and error translation;
 * schema semantics live in MongoSchemaCompiler and session
 * state lives in MongoSchemaResolver.
 *
 * Service names are not unique. None of this adapter's
 * operations silently succeed: a failed creation or index
 * build is reported, a partially built collection is dropped
 * best-effort, and the schema is registered only after every
 * step succeeded.
 */
final class MongoSchemaWriter {

    /*
     * The MongoDB surface a CREATE TABLE needs. Production uses
     * the driver-backed implementation; tests record calls.
     */
    interface Admin {

        boolean collectionExists(String collection);

        void createCollection(
                String collection,
                CreateCollectionOptions options
        );

        void createIndex(String collection, IndexModel index);

        void dropCollection(String collection);
    }

    /*
     * NamespaceExists: the collection appeared (or already
     * existed) when createCollection ran.
     */
    private static final int NAMESPACE_EXISTS = 48;

    private static final int DUPLICATE_KEY = 11000;

    private final Admin admin;

    private final MongoSchemaResolver resolver;

    MongoSchemaWriter(
            MongoDatabase database,
            MongoSchemaResolver resolver
    ) {

        if (database == null) {

            throw new PlugException(
                    "MongoDB database is required."
            );
        }

        this.admin = new DriverAdmin(database);
        this.resolver = requireResolver(resolver);
    }

    /*
     * Package-private seam for focused tests.
     */
    MongoSchemaWriter(
            Admin admin,
            MongoSchemaResolver resolver
    ) {

        if (admin == null) {

            throw new PlugException(
                    "Schema admin is required."
            );
        }

        this.admin = admin;
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

    QueryResult createTable(TableDefinition definition) {

        MongoSchemaCompiler.CompiledCollection compiled =
                MongoSchemaCompiler.compileCreateCollection(
                        definition
                );

        String collection = definition.getName();

        /*
         * Fail with the same message as the mock and PostgreSQL
         * adapters before letting MongoDB decide. The create
         * itself still fails closed on a race (NamespaceExists).
         */
        if (collectionExists(collection)) {

            throw new PlugException(
                    "Collection already exists: " + collection
            );
        }

        try {

            admin.createCollection(
                    collection,
                    collectionOptions(compiled)
            );

        } catch (RuntimeException e) {

            if (isNamespaceExists(e)) {

                throw new PlugException(
                        "Collection already exists: " + collection
                );
            }

            throw new PlugException(
                    "CREATE TABLE failed: " + e.getMessage()
            );
        }

        try {

            for (IndexModel index : compiled.getIndexes()) {
                admin.createIndex(collection, index);
            }

        } catch (RuntimeException e) {

            /*
             * A collection without all of its declared unique
             * constraints must not be left behind: it would
             * silently accept data the definition forbids.
             */
            String cleanup = dropQuietly(collection);

            if (isDuplicateKey(e)) {

                throw new PlugException(
                        "Duplicate value violates a unique "
                                + "constraint on collection '"
                                + collection + "': "
                                + e.getMessage() + cleanup
                );
            }

            throw new PlugException(
                    "CREATE TABLE failed while creating unique "
                            + "indexes for '" + collection + "': "
                            + e.getMessage() + cleanup
            );
        }

        /*
         * Registration is the last step: resolve() must keep
         * reporting the sampled or validator schema until the
         * collection is fully usable.
         */
        resolver.declare(definition);

        return new QueryResult(
                0,
                new ArrayList<>()
        );
    }

    private static CreateCollectionOptions collectionOptions(
            MongoSchemaCompiler.CompiledCollection compiled
    ) {

        ValidationOptions validation = new ValidationOptions()
                .validator(compiled.getValidator())
                .validationLevel(ValidationLevel.STRICT)
                .validationAction(ValidationAction.ERROR);

        return new CreateCollectionOptions()
                .validationOptions(validation);
    }

    private boolean collectionExists(String collection) {

        try {

            return admin.collectionExists(collection);

        } catch (RuntimeException e) {

            throw new PlugException(
                    "Cannot inspect MongoDB collection '"
                            + collection + "': "
                            + e.getMessage()
            );
        }
    }

    private String dropQuietly(String collection) {

        try {

            admin.dropCollection(collection);
            return "";

        } catch (RuntimeException e) {

            return "; cleanup also failed: " + e.getMessage();
        }
    }

    private static boolean isNamespaceExists(RuntimeException e) {

        if (e instanceof MongoCommandException) {

            return ((MongoCommandException) e).getErrorCode()
                    == NAMESPACE_EXISTS;
        }

        return false;
    }

    private static boolean isDuplicateKey(RuntimeException e) {

        if (e instanceof MongoWriteException) {

            return ((MongoWriteException) e)
                    .getError()
                    .getCode() == DUPLICATE_KEY;
        }

        return false;
    }

    /*
     * Driver-backed admin: creation uses the configured
     * database, indexes are built on the collection handle, and
     * cleanup drops the just-created collection.
     */
    private static final class DriverAdmin implements Admin {

        private final MongoDatabase database;

        private DriverAdmin(MongoDatabase database) {
            this.database = database;
        }

        @Override
        public boolean collectionExists(String collection) {

            List<String> names = database
                    .listCollectionNames()
                    .into(new ArrayList<>());

            return names.contains(collection);
        }

        @Override
        public void createCollection(
                String collection,
                CreateCollectionOptions options
        ) {

            database.createCollection(collection, options);
        }

        @Override
        public void createIndex(
                String collection,
                IndexModel index
        ) {

            database.getCollection(collection)
                    .createIndexes(
                            java.util.Collections.singletonList(index)
                    );
        }

        @Override
        public void dropCollection(String collection) {

            database.getCollection(collection).drop();
        }
    }
}
