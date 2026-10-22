package dev.victor.plug.runtime;

import java.util.List;
import java.util.Map;

/*
 * Structured result of a query execution. Adapters return
 * this; display is the caller's concern, so real database
 * adapters don't need to print anything.
 */public class QueryResult {

    private final int count;
    private final List<Map<String, Object>> rows;

    /*
     * For aggregate reads this is the computed value
     * (COUNT/SUM/AVG/MIN/MAX); all other results have it null.
     */
    private final Object aggregateValue;

    public QueryResult(
            int count,
            List<Map<String, Object>> rows
    ) {

        this(count, rows, null);
    }

    public QueryResult(
            int count,
            List<Map<String, Object>> rows,
            Object aggregateValue
    ) {

        this.count = count;
        this.rows = rows;
        this.aggregateValue = aggregateValue;
    }

    public int getCount() {
        return count;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public Object getAggregateValue() {
        return aggregateValue;
    }
}
