package dev.victor.plug.ast;

import java.util.List;

public class Value {

    public enum Type {
        STRING,
        NUMBER,
        BOOLEAN,
        NULL,
        IDENTIFIER,
        LIST
    }

    private final Type type;
    private final Object value;

    public Value(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    public Type getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public String toString() {

        if (type == Type.LIST) {
            return value.toString();
        }

        return type + "(" + value + ")";
    }
}