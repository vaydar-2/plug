package dev.victor.plug;

import dev.victor.plug.cli.Cli;
import dev.victor.plug.cli.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Command-line tests drive Cli.run directly (CliMain only wraps
 * it with System.exit), so exit codes are assertable without
 * spawning processes. Output capture matches RuntimeTest's
 * approach: swap System.out/System.err around the call.
 */
public class CliTest {

    /*
     * A minimal script that exercises the mock connection,
     * collection selection, and a read — same shape as the
     * RuntimeTest connectivity scripts.
     */
    private static final String CONNECT_AND_READ =
            "plug.connect(driver: \"mock\", host: \"localhost\", "
                    + "port: 5432, database: \"mydb\", "
                    + "username: \"victor\", password: \"secret\");\n"
                    + "plug.use(\"users\");\n"
                    + "plug.get(name)"
                    + ".where((country, EQ, \"Ghana\"));\n";

    @TempDir
    Path tempDir;

    private record Result(int exit, String out, String err) {
    }

    private Result run(String[] arguments) {

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        ByteArrayOutputStream outBuffer =
                new ByteArrayOutputStream();

        ByteArrayOutputStream errBuffer =
                new ByteArrayOutputStream();

        System.setOut(new PrintStream(
                outBuffer,
                true,
                StandardCharsets.UTF_8
        ));

        System.setErr(new PrintStream(
                errBuffer,
                true,
                StandardCharsets.UTF_8
        ));

        try {

            int exit = new Cli().run(arguments);

            return new Result(
                    exit,
                    outBuffer.toString(StandardCharsets.UTF_8),
                    errBuffer.toString(StandardCharsets.UTF_8)
            );

        } finally {

            System.out.flush();
            System.err.flush();

            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private String writeScript(
            String name,
            String source
    ) throws IOException {

        Path file = tempDir.resolve(name);

        Files.writeString(file, source);

        return file.toString();
    }

    // -------------------------------------------------------- version and help

    @Test
    public void versionCommandReportsBothVersions() {

        Result result = run(new String[]{"--version"});

        assertEquals(0, result.exit());

        assertTrue(result.out().contains(
                "plug CLI " + Version.CLI
        ));

        assertTrue(result.out().contains(
                "Plug language " + Version.LANGUAGE
        ));

        /*
         * CLI, language, and build report one build-derived
         * version, never independently assigned constants.
         */
        assertEquals(Version.CLI, Version.LANGUAGE);

        assertTrue(result.err().isEmpty());
    }

    @Test
    public void shortVersionFlagAlsoWorks() {

        Result result = run(new String[]{"-v"});

        assertEquals(0, result.exit());

        assertTrue(result.out().contains(
                "plug CLI " + Version.CLI
        ));
    }

    @Test
    public void helpCommandShowsAllCommands() {

        Result result = run(new String[]{"--help"});

        assertEquals(0, result.exit());

        assertTrue(result.out().contains("plug run"));
        assertTrue(result.out().contains("plug check"));
        assertTrue(result.out().contains("--version"));
        assertTrue(result.out().contains("--help"));

        assertTrue(result.err().isEmpty());
    }

    @Test
    public void helpAliasAlsoWorks() {

        Result result = run(new String[]{"-h"});

        assertEquals(0, result.exit());

        assertTrue(result.out().contains("plug run"));
    }

    // ------------------------------------------------------------------ usage

    @Test
    public void noArgumentsIsAUsageError() {

        Result result = run(new String[0]);

        assertEquals(2, result.exit());

        assertTrue(result.err().contains("No command given."));
    }

    @Test
    public void unknownCommandIsAUsageError() {

        Result result = run(new String[]{"launch"});

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "Unknown command: launch"
        ));

        assertTrue(result.err().contains("plug --help"));
    }

    @Test
    public void replIsExplicitlyDeferred() {

        Result result = run(new String[]{"repl"});

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "REPL is not yet supported"
        ));
    }

    // ------------------------------------------------------------------- run

    @Test
    public void runExecutesScriptAndPrintsResults() throws IOException {

        String script = writeScript(
                "hello.plug",
                CONNECT_AND_READ
        );

        Result result = run(new String[]{"run", script});

        assertEquals(0, result.exit());

        assertTrue(result.out().contains("Records found:"));
    }

    @Test
    public void runAcceptsPathsWithSpaces() throws IOException {

        String script = writeScript(
                "my script file.plug",
                CONNECT_AND_READ
        );

        Result result = run(new String[]{"run", script});

        assertEquals(0, result.exit());

        assertTrue(result.out().contains("Records found:"));
    }

    @Test
    public void runReportsMissingFile() {

        String missing = tempDir
                .resolve("missing.plug")
                .toString();

        Result result = run(new String[]{"run", missing});

        assertEquals(1, result.exit());

        assertTrue(result.err().contains(
                "No such file: " + missing
        ));
    }

    @Test
    public void runReportsParseErrorsWithLineNumbers()
            throws IOException {

        String script = writeScript(
                "broken.plug",
                "plug.get(name"
        );

        Result result = run(new String[]{"run", script});

        assertEquals(1, result.exit());

        assertTrue(result.err().contains("line 1"));
    }

    @Test
    public void runReportsRuntimeFailures() throws IOException {

        /*
         * Runtime failures surface from schema validation during
         * execution; an unknown field in a condition is the
         * guaranteed shape (a bare unknown projection is not
         * rejected by the mock without any condition).
         */
        String script = writeScript(
                "runtime-failure.plug",
                CONNECT_AND_READ
                        + "plug.get(name)"
                        + ".where((nonexistent_field, EQ, 1));\n"
        );

        Result result = run(new String[]{"run", script});

        assertEquals(1, result.exit());

        assertTrue(result.err().contains("nonexistent_field"));
    }

    @Test
    public void runRequiresFileArgument() {

        Result result = run(new String[]{"run"});

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "requires exactly one script file"
        ));
    }

    @Test
    public void checkRequiresFileArgument() {

        Result result = run(new String[]{"check"});

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "requires exactly one script file"
        ));
    }

    @Test
    public void runRejectsExtraArguments() throws IOException {

        String first = writeScript(
                "first.plug",
                CONNECT_AND_READ
        );

        String second = writeScript(
                "second.plug",
                CONNECT_AND_READ
        );

        Result result = run(new String[]{"run", first, second});

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "accepts exactly one script file; got 2"
        ));

        /*
         * An extra-argument mistake must not execute anything:
         * no script output is allowed on stdout.
         */
        assertTrue(result.out().isEmpty());
    }

    @Test
    public void checkRejectsExtraArguments() throws IOException {

        String first = writeScript(
                "first.plug",
                CONNECT_AND_READ
        );

        Result result = run(new String[]{
                "check",
                first,
                "--verbose"
        });

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "accepts exactly one script file; got 2"
        ));
    }

    @Test
    public void legacyBareScriptRejectsExtraArguments()
            throws IOException {

        String script = writeScript(
                "legacy.plug",
                CONNECT_AND_READ
        );

        Result result = run(new String[]{script, script});

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "exactly one file"
        ));
    }

    @Test
    public void runReportsInaccessibleFile() throws IOException {

        /*
         * A directory is the portable stand-in for an
         * unreadable target: reading it fails on every
         * platform with an IOException, whatever its message.
         */
        Path directory = tempDir.resolve("a-directory");

        Files.createDirectory(directory);

        Result result = run(new String[]{"run", directory.toString()});

        assertEquals(1, result.exit());

        assertFalse(result.err().isEmpty());

        assertTrue(result.err().contains(directory.toString()));
    }

    @Test
    public void versionRejectsExtraArguments() {

        Result result = run(
                new String[]{"--version", "extra"}
        );

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "--version accepts no arguments"
        ));

        assertTrue(result.out().isEmpty());
    }

    @Test
    public void helpRejectsExtraArguments() {

        Result result = run(
                new String[]{"--help", "extra"}
        );

        assertEquals(2, result.exit());

        assertTrue(result.err().contains(
                "--help accepts no arguments"
        ));

        assertTrue(result.out().isEmpty());
    }

    @Test
    public void blankCommandIsAUsageError() {

        Result result = run(new String[]{""});

        assertEquals(2, result.exit());

        assertTrue(result.err().contains("No command given."));
    }

    @Test
    public void blankFileTargetIsAUsageError() throws IOException {

        /*
         * An empty quoted name must be a clear usage error, not
         * a confusing "No such file: " with an invisible path.
         */
        Result emptyRun = run(new String[]{"run", ""});

        assertEquals(2, emptyRun.exit());

        assertTrue(emptyRun.err().contains("non-empty"));

        Result blankCheck = run(new String[]{"check", "   "});

        assertEquals(2, blankCheck.exit());

        assertTrue(blankCheck.err().contains("non-empty"));
    }

    @Test
    public void runReportsInvalidPathCleanly() throws IOException {

        /*
         * "bad|pipe.plug" is not representable on Windows-family
         * filesystems (Path.of throws InvalidPathException), but
         * is a legal file name elsewhere — the test only asserts
         * where the name really is invalid.
         */
        String target = "bad|pipe.plug";

        try {

            Path.of(target);

        } catch (InvalidPathException expected) {

            Result result = run(new String[]{"run", target});

            assertEquals(1, result.exit());

            assertTrue(result.err().contains(
                    "Invalid path: " + target
            ));

            /*
             * The encoder internals must not leak into the
             * user-visible diagnostic.
             */
            assertFalse(
                    result.err().contains("Exception")
            );

            return;
        }

        /*
         * Filesystems that accept the name: nothing invalid to
         * exercise here.
         */
    }

    @Test
    public void checkReportsInvalidPathCleanly() throws IOException {

        String target = "also|invalid.plug";

        try {

            Path.of(target);

        } catch (InvalidPathException expected) {

            Result result = run(new String[]{"check", target});

            assertEquals(1, result.exit());

            assertTrue(result.err().contains(
                    "Invalid path: " + target
            ));

            /*
             * check must fail on the path before any parsing.
             */
            assertFalse(result.out().startsWith("ok:"));

            return;
        }
    }

    @Test
    public void barePlugPathStillRunsTheScript() throws IOException {

        String script = writeScript(
                "legacy.plug",
                CONNECT_AND_READ
        );

        Result result = run(new String[]{script});

        assertEquals(0, result.exit());

        assertTrue(result.out().contains("Records found:"));
    }

    // ------------------------------------------------------------------ check

    @Test
    public void checkValidatesWithoutExecuting() throws IOException {

        /*
         * This script would print "Table created" and "Records
         * inserted" if it were executed; check must parse it and
         * stop before any adapter work.
         */
        String script = writeScript(
                "check-ok.plug",
                "plug.create.table(\"gadgets\") {\n"
                        + "    id: INT primary_key,\n"
                        + "    name: STRING required\n"
                        + "};\n"
                        + "plug.use(\"gadgets\");\n"
                        + "plug.insert(name: \"Widget\");\n"
        );

        Result result = run(new String[]{"check", script});

        assertEquals(0, result.exit());

        assertTrue(result.out().startsWith("ok:"));

        assertFalse(result.out().contains("Table created"));
        assertFalse(result.out().contains("Records inserted"));
    }

    @Test
    public void checkRejectsMalformedScriptsWithDiagnostics()
            throws IOException {

        String script = writeScript(
                "check-bad.plug",
                "plug.use(\"users\""
        );

        Result result = run(new String[]{"check", script});

        assertEquals(1, result.exit());

        assertTrue(result.err().contains("line 1"));
    }

    @Test
    public void checkReportsMissingFile() {

        String missing = tempDir
                .resolve("gone.plug")
                .toString();

        Result result = run(new String[]{"check", missing});

        assertEquals(1, result.exit());

        assertTrue(result.err().contains(
                "No such file: " + missing
        ));
    }
}
