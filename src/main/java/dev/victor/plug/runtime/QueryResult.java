package dev.victor.plug.runtime;

import java.util.List;
import java.util.Map;

/*
 * Structured result of a query execution. Adapters return
 * this; display is the caller's concern, so real database
 * adapters don't need to print anything.
 */
public class QueryResult {

    private final int count;
    private final List<Map<String, Object>> rows;

    public QueryResult(
            int count,
            List<Map<String, Object>> rows
    ) {

        this.count = count;
        this.rows = rows;
    }

    public int getCount() {
        return count;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }
}
