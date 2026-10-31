package dev.victor.plug.cli;

import dev.victor.plug.ast.Program;
import dev.victor.plug.lexer.Lexer;
import dev.victor.plug.lexer.Token;
import dev.victor.plug.parser.Parser;
import dev.victor.plug.runtime.Runtime;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
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
 *   2  usage error (unknown command, missing argument)
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

        switch (command) {

            case "--version":
            case "-v":
                return version();

            case "--help":
            case "-h":
            case "help":
                printUsage();
                return 0;

            case "run":
            case "check":
                return scriptCommand(
                        command,
                        rest(arguments)
                );

            case "repl":
                System.err.println(
                        "Error: The interactive REPL is not yet "
                                + "supported."
                );
                return 2;

            default:
                return unknownCommand(command);
        }
    }

    /*
     * run and check share one target-resolution path; only the
     * execution depth differs (execute everything vs. static
     * lex + parse).
     */
    private int scriptCommand(
            String command,
            String target
    ) {

        if (target.isEmpty()) {

            System.err.println(
                    "Error: " + command
                            + " requires a script file."
            );

            return 2;
        }

        return command.equals("run")
                ? execute(target)
                : check(target);
    }

    /*
     * Legacy compatibility: a bare .plug path takes the direct
     * plug <file> invocation of the original Main.
     */
    private int unknownCommand(String command) {

        if (command.toLowerCase().endsWith(".plug")) {

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

        } catch (IOException e) {

            System.err.println(
                    readFailureMessage(target, e)
            );

            return 1;
        }

        try {

            Program program = parse(source);

            new Runtime().execute(program);

        } catch (Exception e) {

            System.err.println(
                    "Error: " + e.getMessage()
            );

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

        } catch (IOException e) {

            System.err.println(
                    readFailureMessage(target, e)
            );

            return 1;
        }

        try {

            parse(source);

        } catch (Exception e) {

            System.err.println(
                    "Error: " + e.getMessage()
            );

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
            IOException e
    ) {

        if (e instanceof NoSuchFileException) {

            return "Error: No such file: " + target;
        }

        if (e instanceof AccessDeniedException) {

            return "Error: Permission denied: " + target;
        }

        return "Error: Cannot read file: " + target
                + " (" + e.getMessage() + ")";
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
                "Plug — a database-agnostic query language."
        );

        System.out.println();
        System.out.println("Usage:");

        System.out.println(
                "  plug run <file.plug>    "
                        + "Execute a Plug script"
        );

        System.out.println(
                "  plug check <file.plug>  "
                        + "Validate a script without executing it"
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
                "A single bare <file.plug> argument runs that "
                        + "script directly."
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

    /*
     * Joins the arguments after the command back into one name so
     * an unquoted path with spaces ("plug run my script.plug")
     * still resolves; a quoted path arrives as one argument and
     * joins to itself.
     */
    private static String rest(String[] arguments) {

        StringBuilder path = new StringBuilder();

        for (int index = 1; index < arguments.length; index++) {

            if (index > 1) {
                path.append(' ');
            }

            path.append(arguments[index]);
        }

        return path.toString();
    }
}
