package dev.victor.plug.runtime;

public class Join {

    private final String leftField;
    private final String rightField;
    private final String collection;

    /*
     * Both fields are collection-qualified ("users.id",
     * "orders.user_id") — the parser guarantees the dotted
     * spelling, and the adapter validates the collections
     * and fields against its schemas.
     */
    public Join(
            String leftField,
            String rightField,
            String collection
    ) {
        this.leftField = leftField;
        this.rightField = rightField;
        this.collection = collection;
    }

    public String getLeftField() {
        return leftField;
    }

    public String getRightField() {
        return rightField;
    }

    public String getCollection() {
        return collection;
    }

    @Override
    public String toString() {

        return "Join{" +
                "left='" + leftField + '\'' +
                ", right='" + rightField + '\'' +
                ", collection=" + collection +
                '}';
    }
}
