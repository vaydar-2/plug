package dev.victor.plug.ast;

public class Argument {

    private final String name;
    private final Value value;

    public Argument(String name, Value value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public Value getValue() {
        return value;
    }

    @Override
    public String toString() {

        if (name == null) {
            return value.toString();
        }

        return name + ": " + value;
    }
}