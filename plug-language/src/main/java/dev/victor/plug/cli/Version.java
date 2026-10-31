package dev.victor.plug.cli;

/*
 * Version markers reported by plug --version. The CLI version is
 * this front end's own; the language version tracks the milestone
 * the lexer/parser/runtime implement (write DDL reached 1.0.0).
 */
public final class Version {

    public static final String CLI = "1.0.0";

    public static final String LANGUAGE = "1.0.0";

    private Version() {
    }
}
