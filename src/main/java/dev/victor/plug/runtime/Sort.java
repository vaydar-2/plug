package dev.victor.plug.runtime;

public class Sort {

    private final String field;
    private final String direction;

    public Sort(
            String field,
            String direction
    ) {
        this.field = field;
        this.direction = direction;
    }

    public String getField() {
        return field;
    }

    public String getDirection() {
        return direction;
    }

    @Override
    public String toString() {

        return field + " " + direction;
    }
}