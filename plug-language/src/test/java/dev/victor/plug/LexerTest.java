package dev.victor.plug;

import dev.victor.plug.lexer.Lexer;
import dev.victor.plug.lexer.Token;
import dev.victor.plug.lexer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
 * Golden tests for the lexer: token streams, line-number
 * tracking, and lexer-level error reporting.
 */
public class LexerTest {

    private List<Token> tokenize(String source) {
        return new Lexer(source).tokenize();
    }

    @Test
    public void tokenizeTokensSimpleStatement() {

        List<Token> tokens =
                tokenize("plug.use(\"users\");");

        assertEquals(
                TokenType.IDENTIFIER,
                tokens.get(0).getType()
        );
        assertEquals(
                "plug",
                tokens.get(0).getValue()
        );

        assertEquals(
                TokenType.DOT,
                tokens.get(1).getType()
        );

        assertEquals(
                TokenType.IDENTIFIER,
                tokens.get(2).getType()
        );
        assertEquals(
                "use",
                tokens.get(2).getValue()
        );

        assertEquals(
                TokenType.LPAREN,
                tokens.get(3).getType()
        );

        assertEquals(
                TokenType.STRING,
                tokens.get(4).getType()
        );
        assertEquals(
                "users",
                tokens.get(4).getValue()
        );

        assertEquals(
                TokenType.RPAREN,
                tokens.get(5).getType()
        );

        assertEquals(
                TokenType.SEMICOLON,
                tokens.get(6).getType()
        );

        assertEquals(
                TokenType.EOF,
                tokens.get(7).getType()
        );
    }

    @Test
    public void tokenizeKeywordsAsTheirOwnType() {

        List<Token> tokens =
                tokenize("NOT country AND city OR street");

        assertEquals(
                TokenType.NOT,
                tokens.get(0).getType()
        );

        assertEquals(
                TokenType.AND,
                tokens.get(2).getType()
        );

        assertEquals(
                TokenType.OR,
                tokens.get(4).getType()
        );
    }

    @Test
    public void tokenizeListAndNamedArguments() {

        List<Token> tokens =
                tokenize("host: [1, 2]");

        assertEquals(
                TokenType.IDENTIFIER,
                tokens.get(0).getType()
        );

        assertEquals(
                TokenType.COLON,
                tokens.get(1).getType()
        );

        assertEquals(
                TokenType.LBRACKET,
                tokens.get(2).getType()
        );

        assertEquals(
                TokenType.NUMBER,
                tokens.get(3).getType()
        );

        assertEquals(
                TokenType.NUMBER,
                tokens.get(5).getType()
        );

        assertEquals(
                TokenType.RBRACKET,
                tokens.get(6).getType()
        );
    }

    @Test
    public void tokensCarryLineNumber() {

        List<Token> tokens =
                tokenize("plug.use(\"users\");\nplug.get(name);");

        Token useToken = tokens.get(2);

        assertEquals(1, useToken.getLine());

        Token getMethodToken = tokens.get(10);

        assertEquals(2, getMethodToken.getLine());
    }

    @Test
    public void unexpectedCharacterReportsLine() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> tokenize("plug.use(\"users\");\n@")
                );

        assertEquals(
                "line 2: Unexpected character: @",
                exception.getMessage()
        );
    }

    @Test
    public void lineCommentsAreIgnored() {

        List<Token> tokens =
                tokenize(
                        "plug.use(\"users\"); # trailing comment\n"
                                + "// full line comment\n"
                                + "plug.get(name);"
                );

        long statementCount =
                tokens.stream()
                        .filter(token ->
                                token.getValue().equals("plug"))
                        .count();

        assertEquals(2, statementCount);

        assertEquals(
                TokenType.EOF,
                tokens.get(tokens.size() - 1).getType()
        );
    }

    @Test
    public void loneSlashIsAnError() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> tokenize("plug.get(name) / x;")
                );

        assertEquals(
                "line 1: Unexpected character: /",
                exception.getMessage()
        );
    }

    @Test
    public void stringEscapeSequences() {

        List<Token> tokens =
                tokenize("plug.use(\"a\\\"q\\\\t\\n\");");

        assertEquals(
                "a\"q\\t\n",
                tokens.get(4).getValue()
        );
    }

    @Test
    public void invalidEscapeReportsLine() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> tokenize("x;\nplug.use(\"bad\\p\");")
                );

        assertEquals(
                "line 2: Invalid escape sequence: \"p\"",
                exception.getMessage()
        );
    }

    @Test
    public void negativeAndDecimalNumbers() {

        List<Token> tokens =
                tokenize("-7 x 3.14 y -2.5");

        assertEquals(
                "-7",
                tokens.get(0).getValue()
        );

        assertEquals(
                "3.14",
                tokens.get(2).getValue()
        );

        assertEquals(
                "-2.5",
                tokens.get(4).getValue()
        );
    }

    /*
     * Lowercase and/or/not must stay identifiers at token
     * level so chained .and()/.or() calls keep lexing; the
     * parser specializes them contextually in expressions.
     */
    @Test
    public void lowercaseKeywordsRemainIdentifiersAtLexerLevel() {

        List<Token> tokens =
                tokenize("not x and y or z");

        String[] expectedValues =
                {"not", "and", "or"};

        for (int i = 0; i < 3; i++) {

            int tokenIndex = i * 2;

            assertEquals(
                    TokenType.IDENTIFIER,
                    tokens.get(tokenIndex).getType()
            );

            assertEquals(
                    expectedValues[i],
                    tokens.get(tokenIndex).getValue()
            );
        }
    }

    @Test
    public void decimalPointAloneIsDotToken() {

        List<Token> tokens =
                tokenize("3.");

        assertEquals(
                TokenType.NUMBER,
                tokens.get(0).getType()
        );
        assertEquals(
                "3",
                tokens.get(0).getValue()
        );

        assertEquals(
                TokenType.DOT,
                tokens.get(1).getType()
        );
    }

    @Test
    public void numberFollowedByLetterIsInvalid() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> tokenize("5x")
                );

        assertEquals(
                "line 1: Invalid number: 5x",
                exception.getMessage()
        );
    }


    public void unterminatedStringReportsStartLine() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> tokenize("plug.use(\"users")
                );

        assertEquals(
                "line 1: Unterminated string.",
                exception.getMessage()
        );
    }
}
