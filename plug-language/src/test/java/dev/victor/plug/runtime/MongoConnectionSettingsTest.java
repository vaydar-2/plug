package dev.victor.plug.runtime;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/*
 * Settings construction is tested directly: building client
 * settings never opens a socket, so authenticated and
 * unauthenticated configurations can be verified without a
 * live server. The adapter's settings factory is
 * package-private for exactly this reason.
 */
public class MongoConnectionSettingsTest {

    private static ConnectionConfig config(
            String username,
            String password
    ) {

        return new ConnectionConfig(
                "mongo",
                "db.example",
                27017,
                "plugdb",
                username,
                password
        );
    }

    @Test
    public void unauthenticatedConfigSkipsCredential() {

        MongoClientSettings settings =
                MongoDatabaseAdapter.settingsFor(
                        config("", "")
                );

        assertNull(settings.getCredential());
    }

    @Test
    public void blankUsernameSkipsCredential() {

        MongoClientSettings settings =
                MongoDatabaseAdapter.settingsFor(
                        config("   ", "secret")
                );

        assertNull(settings.getCredential());
    }

    @Test
    public void usernameBuildsCredentialForTheSelectedDatabase() {

        MongoClientSettings settings =
                MongoDatabaseAdapter.settingsFor(
                        config("victor", "secret")
                );

        MongoCredential credential = settings.getCredential();

        assertNotNull(credential);

        assertEquals("victor", credential.getUserName());

        assertEquals("plugdb", credential.getSource());

        assertArrayEquals(
                "secret".toCharArray(),
                credential.getPassword()
        );
    }

    @Test
    public void settingsUseConfiguredAddressAndTimeouts() {

        MongoClientSettings settings =
                MongoDatabaseAdapter.settingsFor(
                        config("", "")
                );

        assertEquals(
                new ServerAddress("db.example", 27017),
                settings.getClusterSettings()
                        .getHosts()
                        .get(0)
        );

        assertEquals(
                1,
                settings.getClusterSettings()
                        .getHosts()
                        .size()
        );

        assertEquals(
                3000,
                settings.getClusterSettings()
                        .getServerSelectionTimeout(
                                TimeUnit.MILLISECONDS
                        )
        );

        assertEquals(
                3000,
                settings.getSocketSettings()
                        .getConnectTimeout(
                                TimeUnit.MILLISECONDS
                        )
        );
    }

    @Test
    public void adapterStartsDisconnectedAndCloseKeepsItSo() {

        MongoDatabaseAdapter adapter =
                new MongoDatabaseAdapter();

        assertFalse(adapter.isConnected());

        adapter.close();

        assertFalse(adapter.isConnected());
    }
}
