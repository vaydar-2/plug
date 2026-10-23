package dev.victor.plug;

import dev.victor.plug.PlugException;
import dev.victor.plug.runtime.ConnectionConfig;
import dev.victor.plug.runtime.DatabaseAdapterRegistry;
import dev.victor.plug.runtime.PostgresDatabaseAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Driver registration: the postgres adapter must be
 * reachable through the same registry the mock uses, with
 * both spellings accepted. No server is needed.
 */
public class PostgresAdapterRegistrationTest {

    @Test
    public void postgresDriverIsRegistered() {

        assertTrue(
                DatabaseAdapterRegistry.exists("postgres")
        );

        assertInstanceOf(
                PostgresDatabaseAdapter.class,
                DatabaseAdapterRegistry.create("postgres")
        );
    }

    @Test
    public void postgresqlAliasIsRegistered() {

        assertTrue(
                DatabaseAdapterRegistry.exists("postgresql")
        );

        assertInstanceOf(
                PostgresDatabaseAdapter.class,
                DatabaseAdapterRegistry.create("postgresql")
        );
    }

    @Test
    public void unavailableServerReportsConnectionFailure() {

        PostgresDatabaseAdapter adapter =
                new PostgresDatabaseAdapter();

        ConnectionConfig config = new ConnectionConfig(
                "postgres",
                "127.0.0.1",
                1,
                "plug_test",
                "nobody",
                "nothing"
        );

        PlugException exception = assertThrows(
                PlugException.class,
                () -> adapter.connect(config)
        );

        assertTrue(
                exception.getMessage().startsWith(
                        "Cannot connect to PostgreSQL database"
                )
        );
    }
}
