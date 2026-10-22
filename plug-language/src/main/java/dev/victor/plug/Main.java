package dev.victor.plug;

import dev.victor.plug.ast.Program;
import dev.victor.plug.lexer.Lexer;
import dev.victor.plug.lexer.Token;
import dev.victor.plug.parser.Parser;
import dev.victor.plug.runtime.Runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static void main(String[] args) {

        if (args.length == 0) {
            System.out.println("Usage: plug <file.plug>");
            return;
        }

        try {

            String source = Files.readString(Path.of(args[0]));

            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.tokenize();

            Parser parser = new Parser(tokens);
            Program program = parser.parse();

            Runtime runtime = new Runtime();
            runtime.execute(program);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}