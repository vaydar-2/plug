package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/*
 * Maps driver names to adapter factories. connect(driver: ...)
 * selects an adapter from here; each new backend (PostgreSQL,
 * MongoDB, PlugDB, ...) registers itself by name.
 */
public class DatabaseAdapterRegistry {

    /*
     * Insertion order is kept so "Available drivers: ..."
     * lists drivers deterministically.
     */
    private static final Map<String, Supplier<DatabaseAdapter>> ADAPTERS =
            new LinkedHashMap<>();

    static {
        ADAPTERS.put("mock", MockDatabaseAdapter::new);

        /*
         * PostgreSQL backend; "postgresql" is accepted as
         * the spelled-out alias of "postgres".
         */
        ADAPTERS.put("postgres", PostgresDatabaseAdapter::new);
        ADAPTERS.put("postgresql", PostgresDatabaseAdapter::new);
    }

    private DatabaseAdapterRegistry() {
    }

    public static void register(
            String name,
            Supplier<DatabaseAdapter> factory
    ) {

        if (name == null
                || name.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Driver name must not be empty."
            );
        }

        if (factory == null) {
            throw new IllegalArgumentException(
                    "Adapter factory must not be null."
            );
        }

        ADAPTERS.put(name.trim(), factory);
    }

    public static boolean exists(String name) {
        return ADAPTERS.containsKey(name);
    }

    public static DatabaseAdapter create(String name) {

        validateDriverName(name);

        return ADAPTERS.get(name.trim()).get();
    }

    public static String availableDrivers() {

        return String.join(", ", ADAPTERS.keySet());
    }

    private static void validateDriverName(String name) {

        if (name == null
                || name.trim().isEmpty()) {

            throw new PlugException(
                    "Driver name must not be empty."
            );
        }

        if (!ADAPTERS.containsKey(name.trim())) {

            throw new PlugException(
                    "Unknown driver: " + name.trim()
                            + ". Available drivers: "
                            + availableDrivers()
            );
        }
    }
}
