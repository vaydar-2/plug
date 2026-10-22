package dev.victor.plug.runtime;

public class DatabaseContext {

    private final DatabaseAdapter adapter;

    private String collection;

    public DatabaseContext(DatabaseAdapter adapter) {
        this.adapter = adapter;
    }

    public DatabaseAdapter getAdapter() {
        return adapter;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }
}