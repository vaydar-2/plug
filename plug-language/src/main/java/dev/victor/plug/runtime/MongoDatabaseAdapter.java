package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/*
 * MongoDB backend foundation.
 *
 * M1 covers the connection lifecycle: explicit client
 * settings, the connectivity probe, close, and registry
 * entries. M3 adds session schema resolution, M4 executes
 * CREATE TABLE through MongoSchemaWriter (validator and unique
 * indexes), and M5 executes INSERT/UPDATE/DELETE through
 * MongoWriteExecutor. Reads, joins and aggregates arrive in
 * later milestones; none of them silently return empty
 * results or placeholder success.
 *
 * As in the PostgreSQL adapter, this class owns driver
 * mechanics only. Query-shaped rules stay in the shared
 * validation and (future) compiler layers.
 */
public class MongoDatabaseAdapter implements DatabaseAdapter {

    /*
     * Bounds a failed connection: without a timeout the driver
     * waits far longer before reporting an unreachable server.
     */
    private static final int CONNECTION_TIMEOUT_MILLIS = 3_000;

    private MongoClient client;

    /*
     * Kept for the read/write milestones, which select this
     * database instead of re-deriving it from the config.
     */
    private MongoDatabase database;

    /*
     * Session schema state, created once the connection is
     * usable and dropped again by close(). Package-private so
     * lifecycle tests can install a resolver without a server.
     */
    MongoSchemaResolver schemaResolver;

    /*
     * CREATE TABLE executor, created with the connection and
     * dropped by close(). Package-private for schema tests.
     */
    MongoSchemaWriter schemaWriter;

    /*
     * CRUD executor, created with the connection and dropped
     * by close(). Package-private for write tests.
     */
    MongoWriteExecutor writeExecutor;

    @Override
    public void connect(ConnectionConfig config) {

        if (config == null) {
            throw new PlugException(
                    "Connection configuration is required."
            );
        }

        MongoClient created = null;

        try {

            created = MongoClients.create(settingsFor(config));

            MongoDatabase selected =
                    created.getDatabase(config.getDatabase());

            /*
             * Fail fast on unusable connections rather than at
             * the first statement, like the PostgreSQL probe.
             */
            selected.runCommand(new Document("ping", 1));

            if (schemaResolver != null) {
                schemaResolver.clear();
            }

            this.client = created;
            this.database = selected;
            this.schemaResolver =
                    new MongoSchemaResolver(selected);
            this.schemaWriter = new MongoSchemaWriter(
                    selected,
                    this.schemaResolver
            );
            this.writeExecutor = new MongoWriteExecutor(
                    selected,
                    this.schemaResolver
            );

        } catch (RuntimeException e) {

            if (created != null) {
                created.close();
            }

            throw new PlugException(
                    "Cannot connect to MongoDB database '"
                            + config.getDatabase()
                            + "' at "
                            + config.getHost()
                            + ":"
                            + config.getPort()
                            + ": "
                            + e.getMessage()
            );
        }
    }

    /*
     * Explicit settings, built from the shared config only.
     *
     * Credentials are attached only for a non-blank username:
     * an empty username means an unauthenticated server, not
     * an empty credential. ConnectionConfig has no authSource
     * field, so the selected database is the credential source.
     *
     * Package-private so tests can assert the built settings
     * without a live server.
     */
    static MongoClientSettings settingsFor(ConnectionConfig config) {

        MongoClientSettings.Builder builder =
                MongoClientSettings.builder()
                        .applyToClusterSettings(cluster ->
                                cluster.hosts(
                                                Collections.singletonList(
                                                        new ServerAddress(
                                                                config.getHost(),
                                                                config.getPort()
                                                        )
                                                )
                                        )
                                        .serverSelectionTimeout(
                                                CONNECTION_TIMEOUT_MILLIS,
                                                TimeUnit.MILLISECONDS
                                        ))
                        .applyToSocketSettings(socket ->
                                socket.connectTimeout(
                                                CONNECTION_TIMEOUT_MILLIS,
                                                TimeUnit.MILLISECONDS
                                        )
                                        .readTimeout(
                                                CONNECTION_TIMEOUT_MILLIS,
                                                TimeUnit.MILLISECONDS
                                        ));

        String username = config.getUsername();

        if (username != null && !username.isBlank()) {

            char[] password = config.getPassword() == null
                    ? new char[0]
                    : config.getPassword().toCharArray();

            builder.credential(
                    MongoCredential.createCredential(
                            username,
                            config.getDatabase(),
                            password
                    )
            );
        }

        return builder.build();
    }

    @Override
    public QueryResult execute(ExecutionPlan plan) {

        if (plan == null || plan.getOperation() == null) {
            throw new PlugException(
                    "Execution plan is required."
            );
        }

        switch (plan.getOperation()) {

            case INSERT:
                requireWrites();
                return writeExecutor.insert(plan);

            case UPDATE:
                requireWrites();
                return writeExecutor.update(plan);

            case DELETE:
                requireWrites();
                return writeExecutor.delete(plan);

            case READ:
            default:
                throw new PlugException(
                        "MongoDB adapter does not support query "
                                + "operations yet: "
                                + plan.getOperation()
                );
        }
    }

    private void requireWrites() {

        if (writeExecutor == null) {

            throw new PlugException(
                    "No database connection."
            );
        }
    }

    /*
     * CREATE_DATABASE is a deliberate limitation of this
     * adapter: it connects to an existing database only. The
     * error is decided before any connection check so the
     * unsupported operation is unambiguous.
     */
    @Override
    public QueryResult executeSchema(SchemaPlan plan) {

        if (plan == null || plan.getOperation() == null) {
            throw new PlugException(
                    "Schema operation is required."
            );
        }

        switch (plan.getOperation()) {

            case CREATE_TABLE:

                if (schemaWriter == null) {

                    throw new PlugException(
                            "No database connection."
                    );
                }

                return schemaWriter.createTable(
                        plan.getTable()
                );

            case CREATE_DATABASE:
                throw new PlugException(
                        "CREATE DATABASE is not supported by "
                                + "the MongoDB adapter. Connect to "
                                + "an existing database."
                );

            default:
                throw new PlugException(
                        "MongoDB adapter does not support schema "
                                + "operation yet: " + plan.getOperation()
                );
        }
    }

    @Override
    public void close() {

        schemaWriter = null;
        writeExecutor = null;

        if (schemaResolver != null) {
            schemaResolver.clear();
            schemaResolver = null;
        }

        database = null;

        if (client == null) {
            return;
        }

        try {
            client.close();
        } catch (RuntimeException e) {
            /* Closing a dead client is not fatal. */
        } finally {
            client = null;
        }
    }

    /*
     * Lifecycle state used by the connection tests: no client
     * exists before connect() succeeds and none survives
     * close().
     */
    boolean isConnected() {
        return client != null;
    }
}
