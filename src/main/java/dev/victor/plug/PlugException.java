package dev.victor.plug;

/*
 * The single exception type used across the lexer, parser, and
 * runtime. Carries an optional line number so parse and runtime
 * errors can point at source locations.
 */
public class PlugException extends RuntimeException {

    private final int line;

    public PlugException(String message) {
        this(message, -1);
    }

    public PlugException(String message, int line) {

        super(line > 0
                ? "line " + line + ": " + message
                : message);

        this.line = line;
    }

    public int getLine() {
        return line;
    }
}
