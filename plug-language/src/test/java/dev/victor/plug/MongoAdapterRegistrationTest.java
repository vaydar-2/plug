package dev.victor.plug;

import dev.victor.plug.runtime.ConnectionConfig;
import dev.victor.plug.runtime.DatabaseAdapterRegistry;
import dev.victor.plug.runtime.ExecutionPlan;
import dev.victor.plug.runtime.MongoDatabaseAdapter;
import dev.victor.plug.runtime.Query;
import dev.victor.plug.runtime.SchemaPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoDB driver registration and lifecycle edges. No server
 * is required: registration is static, and the connection
 * test points at a closed port to exercise the error path.
 */
public class MongoAdapterRegistrationTest {

    @Test
    public void mongoDriverIsRegistered() {

        assertTrue(
                DatabaseAdapterRegistry.exists("mongo")
        );

        assertInstanceOf(
                MongoDatabaseAdapter.class,
                DatabaseAdapterRegistry.create("mongo")
        );
    }

    @Test
    public void mongodbAliasIsRegistered() {

        assertTrue(
                DatabaseAdapterRegistry.exists("mongodb")
        );

        assertInstanceOf(
                MongoDatabaseAdapter.class,
                DatabaseAdapterRegistry.create("mongodb")
        );
    }

    @Test
    public void unavailableServerReportsConnectionFailure() {

        MongoDatabaseAdapter adapter =
                new MongoDatabaseAdapter();

        ConnectionConfig config = new ConnectionConfig(
                "mongo",
                "127.0.0.1",
                1,
                "plug_test",
                "",
                ""
        );

        PlugException exception = assertThrows(
                PlugException.class,
                () -> adapter.connect(config)
        );

        assertTrue(
                exception.getMessage().startsWith(
                        "Cannot connect to MongoDB database "
                                + "'plug_test' at 127.0.0.1:1"
                ),
                exception.getMessage()
        );
    }

    @Test
    public void createDatabaseIsRejectedAsUnsupported() {

        MongoDatabaseAdapter adapter =
                new MongoDatabaseAdapter();

        PlugException exception = assertThrows(
                PlugException.class,
                () -> adapter.executeSchema(
                        SchemaPlan.createDatabase("plug_test")
                )
        );

        assertEquals(
                "CREATE DATABASE is not supported by the "
                        + "MongoDB adapter. Connect to an "
                        + "existing database.",
                exception.getMessage()
        );
    }

    @Test
    public void queryOperationsAreRejectedUntilImplemented() {

        MongoDatabaseAdapter adapter =
                new MongoDatabaseAdapter();

        ExecutionPlan plan = new ExecutionPlan(
                new Query(List.of("id")),
                "users"
        );

        PlugException exception = assertThrows(
                PlugException.class,
                () -> adapter.execute(plan)
        );

        assertTrue(
                exception.getMessage().contains(
                        "does not support query operations yet"
                ),
                exception.getMessage()
        );
    }

    @Test
    public void closeWithoutConnectIsSafeAndIdempotent() {

        MongoDatabaseAdapter adapter =
                new MongoDatabaseAdapter();

        adapter.close();
        adapter.close();
    }
}
