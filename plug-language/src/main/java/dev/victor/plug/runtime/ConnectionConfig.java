package dev.victor.plug.runtime;

public class ConnectionConfig {

    private final String driver;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    public ConnectionConfig(
            String driver,
            String host,
            int port,
            String database,
            String username,
            String password
    ) {
        this.driver = driver;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    public String getDriver() {
        return driver;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}