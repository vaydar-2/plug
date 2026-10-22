package dev.victor.plug.lexer;

import dev.victor.plug.PlugException;

import java.util.ArrayList;
import java.util.List;

public class Lexer {

    private final String source;
    private final List<Token> tokens = new ArrayList<>();

    private int current = 0;
    private int start = 0;
    private int line = 1;
    private int startLine = 1;

    public Lexer(String source) {
        this.source = source;
    }

    public List<Token> tokenize() {

        while (!isAtEnd()) {
            start = current;
            startLine = line;
            scanToken();
        }

        tokens.add(new Token(TokenType.EOF, "", line));

        return tokens;
    }

    private void scanToken() {

        char c = advance();

        switch (c) {

            case '.':
                addToken(TokenType.DOT);
                break;

            case ',':
                addToken(TokenType.COMMA);
                break;

            case ':':
                addToken(TokenType.COLON);
                break;

            case '(':
                addToken(TokenType.LPAREN);
                break;

            case ')':
                addToken(TokenType.RPAREN);
                break;

            case '[':
                addToken(TokenType.LBRACKET);
                break;

            case ']':
                addToken(TokenType.RBRACKET);
                break;

            case ';':
                addToken(TokenType.SEMICOLON);
                break;

            case '"':
                string();
                break;

            case '#':
                lineComment();
                break;

            case '/':

                if (peek() == '/') {

                    advance();
                    lineComment();

                    break;
                }

                throw new PlugException(
                        "Unexpected character: /",
                        line
                );

            case '-':

                /*
                 * '-' only introduces a negative number;
                 * there is no subtraction in the language.
                 */
                if (Character.isDigit(peek())) {
                    number();

                    break;
                }

                throw new PlugException(
                        "Unexpected character: -",
                        line
                );

            case ' ':
            case '\r':
            case '\t':
            case '\n':
                break;

            default:
                if (isIdentifierStart(c)) {
                    identifier();
                } else if (Character.isDigit(c)) {
                    number();
                } else {
                    throw new PlugException(
                            "Unexpected character: " + c,
                            line
                    );
                }
        }
    }

    private void identifier() {

        while (!isAtEnd() && isIdentifierPart(peek())) {
            advance();
        }

        String text = source.substring(start, current);

        /*
         * Keywords stay uppercase-only so that .and()/.or()
         * chained calls still lex as identifiers.
         * The parser accepts lowercase and/or/not contextually
         * in expression positions.
         */
        switch (text) {

            case "AND":
                addToken(TokenType.AND, text);
                break;

            case "OR":
                addToken(TokenType.OR, text);
                break;

            case "NOT":
                addToken(TokenType.NOT, text);
                break;

            default:
                addToken(TokenType.IDENTIFIER, text);
                break;
        }
    }

    private void string() {

        StringBuilder value = new StringBuilder();

        while (!isAtEnd() && peek() != '"') {

            char c = advance();

            if (c == '\\') {

                if (isAtEnd()) {
                    break;
                }

                c = advance();

                switch (c) {

                    case 'n':
                        value.append('\n');
                        break;

                    case 't':
                        value.append('\t');
                        break;

                    case '"':
                        value.append('"');
                        break;

                    case '\\':
                        value.append('\\');
                        break;

                    default:
                        throw new PlugException(
                                "Invalid escape sequence: \"" + c + "\"",
                                startLine
                        );
                }

            } else {
                value.append(c);
            }
        }

        if (isAtEnd()) {
            throw new PlugException(
                    "Unterminated string.",
                    startLine
            );
        }

        advance(); // Closing quote

        addToken(
                TokenType.STRING,
                value.toString()
        );
    }

    private void number() {

        while (!isAtEnd() && Character.isDigit(peek())) {
            advance();
        }

        /*
         * A '.' immediately followed by a digit is a decimal
         * fraction; otherwise it is the DOT method-chain token.
         */
        if (!isAtEnd()
                && peek() == '.'
                && current + 1 < source.length()
                && Character.isDigit(source.charAt(current + 1))) {

            advance(); // Fraction point

            while (!isAtEnd()
                    && Character.isDigit(peek())) {
                advance();
            }
        }

        if (!isAtEnd()
                && isIdentifierStart(peek())) {

            throw new PlugException(
                    "Invalid number: "
                            + source.substring(
                            start,
                            current + 1
                    ),
                    startLine
            );
        }

        addToken(
                TokenType.NUMBER,
                source.substring(start, current)
        );
    }

    private void lineComment() {

        while (!isAtEnd() && peek() != '\n') {
            advance();
        }
    }

    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private char advance() {

        char c = source.charAt(current++);

        if (c == '\n') {
            line++;
        }

        return c;
    }

    private char peek() {

        if (isAtEnd()) {
            return '\0';
        }

        return source.charAt(current);
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private void addToken(TokenType type) {
        addToken(type, String.valueOf(source.charAt(current - 1)));
    }

    private void addToken(TokenType type, String value) {
        tokens.add(new Token(type, value, startLine));
    }
}