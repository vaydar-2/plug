package dev.victor.plug.ast;

import java.util.List;

public class Statement {

    private final List<Call> calls;

    public Statement(List<Call> calls) {
        this.calls = calls;
    }

    public List<Call> getCalls() {
        return calls;
    }

    @Override
    public String toString() {
        return "Statement{" +
                "calls=" + calls +
                '}';
    }
}