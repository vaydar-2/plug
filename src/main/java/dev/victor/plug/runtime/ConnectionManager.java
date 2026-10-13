package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.Argument;
import dev.victor.plug.ast.Value;

import java.util.List;

public class ConnectionManager {

    private DatabaseContext context;

    public void connect(List<Argument> arguments) {

        String driver = "mock";
        String host = null;
        int port = 5432;
        String database = null;
        String username = null;
        String password = null;

        for (Argument argument : arguments) {

            if (argument.getName() == null) {
                throw new PlugException(
                        "connect() requires named arguments."
                );
            }

            String name = argument.getName();
            Value value = argument.getValue();

            switch (name) {

                case "driver":
                    driver = requireString(value, "driver");
                    break;

                case "host":
                    host = requireString(value, "host");
                    break;

                case "port":
                    port = requireNumber(value, "port");
                    break;

                case "database":
                    database = requireString(value, "database");
                    break;

                case "username":
                    username = requireString(value, "username");
                    break;

                case "password":
                    password = requireString(value, "password");
                    break;

                default:
                    throw new PlugException(
                            "Unknown connection parameter: "
                                    + name
                    );
            }
        }

        if (host == null) {
            throw new PlugException(
                    "connect() is missing 'host'."
            );
        }

        if (database == null) {
            throw new PlugException(
                    "connect() is missing 'database'."
            );
        }

        if (username == null) {
            throw new PlugException(
                    "connect() is missing 'username'."
            );
        }

        if (password == null) {
            throw new PlugException(
                    "connect() is missing 'password'."
            );
        }

        ConnectionConfig config = new ConnectionConfig(
                driver,
                host,
                port,
                database,
                username,
                password
        );

        DatabaseAdapter adapter =
                DatabaseAdapterRegistry.create(driver);

        adapter.connect(config);

        context = new DatabaseContext(adapter);

        System.out.println(
                "Connection successful."
        );
    }

    public DatabaseContext getContext() {
        return context;
    }

    /*
     * Closes the active connection through its adapter and
     * clears the context. Safe to call when nothing is open,
     * which lets Runtime auto-close every program run.
     */
    public void close() {

        if (context == null) {
            return;
        }

        context.getAdapter().close();

        context = null;
    }

    private String requireString(
            Value value,
            String parameter
    ) {

        if (value.getType() != Value.Type.STRING) {
            throw new PlugException(
                    parameter + " must be a string."
            );
        }

        return (String) value.getValue();
    }

    private int requireNumber(
            Value value,
            String parameter
    ) {

        if (value.getType() != Value.Type.NUMBER) {
            throw new PlugException(
                    parameter + " must be a number."
            );
        }

        return (Integer) value.getValue();
    }
}