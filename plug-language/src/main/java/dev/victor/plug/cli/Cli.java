package dev.victor.plug.cli;

import dev.victor.plug.ast.Program;
import dev.victor.plug.lexer.Lexer;
import dev.victor.plug.lexer.Token;
import dev.victor.plug.parser.Parser;
import dev.victor.plug.runtime.Runtime;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/*
 * The command-line front end for Plug:
 *
 *   plug run <file.plug>
 *   plug check <file.plug>
 *   plug --version | --help
 *
 * Argument dispatch, file handling, and exit codes only — nothing
 * script-shaped lives here. Every statement is handled by the
 * existing Lexer, Parser, and Runtime. Entry points (CliMain for
 * the executable jar, Main for classpath runs) both call run(...)
 * and apply its returned code with System.exit.
 *
 * Exit codes:
 *   0  success
 *   1  file, parse, or execution failure
 *   2  usage error (unknown command, malformed arguments)
 */
public final class Cli {

    public int run(String[] arguments) {

        if (arguments.length == 0) {

            printUsage();

            System.err.println(
                    "Error: No command given."
            );

            return 2;
        }

        String command = arguments[0];

        if (command.isBlank()) {

            System.err.println(
                    "Error: No command given."
            );

            return 2;
        }

        switch (command) {

            case "--version":
            case "-v":
                return versionCommand(arguments);

            case "--help":
            case "-h":
            case "help":
                return helpCommand(arguments);

            case "run":
            case "check":
                return scriptCommand(
                        command,
                        arguments
                );

            case "repl":
                System.err.println(
                        "Error: The interactive REPL is not yet "
                                + "supported."
                );
                return 2;

            default:
                return unknownCommand(command, arguments);
        }
    }

    /*
     * run and check share one argument shape: exactly one file
     * path. Quoted paths containing spaces arrive as a single
     * argument; anything else (missing, extra arguments) is a
     * usage error, per the conventional CLI contract.
     */
    private int scriptCommand(
            String command,
            String[] arguments
    ) {

        if (arguments.length == 1) {

            System.err.println(
                    "Error: " + command
                            + " requires exactly one script file."
            );

            System.err.println(
                    "Run 'plug --help' for usage."
            );

            return 2;
        }

        if (arguments.length > 2) {

            System.err.println(
                    "Error: " + command + " accepts exactly one "
                            + "script file; got "
                            + (arguments.length - 1) + "."
            );

            return 2;
        }

        String target = arguments[1];

        if (target.isBlank()) {

            System.err.println(
                    "Error: " + command + " requires a non-empty "
                            + "script file name."
            );

            return 2;
        }

        return command.equals("run")
                ? execute(target)
                : check(target);
    }

    /*
     * The informational commands take no arguments; anything
     * after them is a usage mistake, rejected like every other
     * malformed invocation instead of silently ignored.
     */
    private int versionCommand(String[] arguments) {

        if (arguments.length > 1) {

            System.err.println(
                    "Error: --version accepts no arguments; got "
                            + (arguments.length - 1) + "."
            );

            return 2;
        }

        return version();
    }

    private int helpCommand(String[] arguments) {

        if (arguments.length > 1) {

            System.err.println(
                    "Error: --help accepts no arguments; got "
                            + (arguments.length - 1) + "."
            );

            return 2;
        }

        printUsage();

        return 0;
    }

    /*
     * Legacy compatibility: a bare .plug path takes the direct
     * plug <file> invocation of the original Main — and follows
     * the same validation rules: exactly one file, nothing else.
     */
    private int unknownCommand(
            String command,
            String[] arguments
    ) {

        if (command.toLowerCase().endsWith(".plug")) {

            if (arguments.length > 1) {

                System.err.println(
                        "Error: Unexpected extra arguments: a bare "
                                + "script must be exactly one file."
                );

                return 2;
            }

            return execute(command);
        }

        System.err.println(
                "Error: Unknown command: " + command
        );

        System.err.println(
                "Run 'plug --help' for the available commands."
        );

        return 2;
    }

    private int execute(String target) {

        String source;

        try {

            source = readSource(target);

        } catch (IOException | InvalidPathException e) {

            System.err.println(
                    readFailureMessage(target, e)
            );

            return 1;
        }

        try {

            Program program = parse(source);

            new Runtime().execute(program);

        } catch (Exception e) {

            System.err.println(failureMessage(e));

            return 1;
        }

        return 0;
    }

    /*
     * Static validation only: tokenize and parse, then stop.
     * No connection opens and no adapter executes, so schema
     * and driver behavior stay out of scope by design.
     */
    private int check(String target) {

        String source;

        try {

            source = readSource(target);

        } catch (IOException | InvalidPathException e) {

            System.err.println(
                    readFailureMessage(target, e)
            );

            return 1;
        }

        try {

            parse(source);

        } catch (Exception e) {

            System.err.println(failureMessage(e));

            return 1;
        }

        System.out.println("ok: " + target);

        return 0;
    }

    private static String readSource(String target)
            throws IOException {

        Path path = Path.of(target);

        return Files.readString(path);
    }

    private static String readFailureMessage(
            String target,
            Exception e
    ) {

        if (e instanceof InvalidPathException) {

            /*
             * Path.of rejects names the filesystem cannot even
             * represent (illegal characters, NUL bytes). The
             * exception's own message is raw encoder detail —
             * echoing the invoked path is the useful part.
             */
            return "Error: Invalid path: " + target;
        }

        if (e instanceof NoSuchFileException) {

            return "Error: No such file: " + target;
        }

        if (e instanceof AccessDeniedException) {

            return "Error: Permission denied: " + target;
        }

        String detail = e.getMessage();

        return "Error: Cannot read file: " + target
                + (detail == null || detail.isBlank()
                        ? ""
                        : " (" + detail + ")");
    }

    /*
     * One diagnostic line for ordinary users: the message the
     * language produced (with its line number), or — when an
     * exception carries a null or blank message — the exception
     * type as a fallback. Stack traces stay out of the CLI's
     * visible output.
     */
    private static String failureMessage(Exception e) {

        String message = e.getMessage();

        if (message == null || message.isBlank()) {

            message = "unexpected failure: "
                    + e.getClass().getName();
        }

        return "Error: " + message;
    }

    private static Program parse(String source) {

        Lexer lexer = new Lexer(source);

        List<Token> tokens = lexer.tokenize();

        return new Parser(tokens).parse();
    }

    private static int version() {
        System.out.println(
                "plug CLI " + Version.CLI
        );

        System.out.println(
                "Plug language " + Version.LANGUAGE
        );

        return 0;
    }

    private static void printUsage() {

        System.out.println(
                "Plug - a database-agnostic query language."
        );

        System.out.println();
        System.out.println("Usage:");

        System.out.println(
                "  plug run <file.plug>    "
                        + "Execute a Plug script"
        );

        System.out.println(
                "  plug check <file.plug>  "
                        + "Validate syntax only "
                        + "(lex + parse; nothing executes)"
        );

        System.out.println(
                "  plug --version          "
                        + "Show CLI and language versions"
        );

        System.out.println(
                "  plug --help             "
                        + "Show this help"
        );

        System.out.println();
        System.out.println(
                "A bare <file.plug> argument (exactly one file) runs "
                        + "that script directly."
        );

        System.out.println();
        System.out.println(
                "Exit codes: 0 success, 1 script or file failure, "
                        + "2 usage error."
        );

        System.out.println(
                "The interactive REPL is not yet supported."
        );
    }

}
