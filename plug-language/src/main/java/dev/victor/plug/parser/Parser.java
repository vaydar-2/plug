package dev.victor.plug.parser;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.Argument;
import dev.victor.plug.ast.Call;
import dev.victor.plug.ast.Program;
import dev.victor.plug.ast.Statement;
import dev.victor.plug.ast.Value;
import dev.victor.plug.lexer.Token;
import dev.victor.plug.lexer.TokenType;
import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.ConditionExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;

import java.util.ArrayList;
import java.util.List;

public class Parser {

    private final List<Token> tokens;
    private int current = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public Program parse() {

        List<Statement> statements = new ArrayList<>();

        while (!isAtEnd()) {
            statements.add(statement());
        }

        return new Program(statements);
    }

    private Statement statement() {

        List<Call> calls = new ArrayList<>();

        calls.add(call(true));

        while (match(TokenType.DOT)) {
            calls.add(call(false));
        }

        consume(
                TokenType.SEMICOLON,
                "Expected ';' after statement."
        );

        return new Statement(calls);
    }

    private Call call(boolean firstCall) {

        String object;

        if (firstCall) {

            object = consumeIdentifier(
                    "Expected object name."
            );

        } else {

            object = "";
        }

        String method;

        if (firstCall) {

            consume(
                    TokenType.DOT,
                    "Expected '.' after object name."
            );
        }

        method = consumeIdentifier(
                "Expected method name."
        );

        consume(
                TokenType.LPAREN,
                "Expected '(' after method name."
        );

        /*
         * where() has two possible forms:
         *
         * where(country, EQ, "Ghana")
         *
         * where(
         *     (country, EQ, "Ghana")
         *     OR
         *     (country, EQ, "Nigeria")
         * )
         */
        if (method.equals("where")) {

            ConditionExpression expression =
                    conditionExpression();

            consume(
                    TokenType.RPAREN,
                    "Expected ')' after condition expression."
            );

            return new Call(
                    object,
                    method,
                    expression
            );
        }

        /*
         * join(collection, on: (left, EQ, right)) has its own
         * argument shape: a bare collection name plus one
         * named on: argument whose value is a key tuple.
         */
        if (method.equals("join")) {

            List<Argument> joinArgs = joinArguments();

            consume(
                    TokenType.RPAREN,
                    "Expected ')' after arguments."
            );

            return new Call(
                    object,
                    method,
                    joinArgs
            );
        }

        List<Argument> arguments = arguments();

        consume(
                TokenType.RPAREN,
                "Expected ')' after arguments."
        );

        return new Call(
                object,
                method,
                arguments
        );
    }

    /*
     * join() is parsed as two arguments: a positional bare
     * collection name, and an optional named on: argument
     * holding a comparison tuple, e.g. (users.id, EQ,
     * orders.user_id). Structure errors like a missing on:
     * are reported by the runtime against the real grammar,
     * so the parser only rejects malformed syntax here.
     */
    private List<Argument> joinArguments() {

        List<Argument> arguments = new ArrayList<>();

        String collection =
                consumeIdentifier(
                        "Expected a collection name in join()."
                );

        arguments.add(
                new Argument(
                        null,
                        new Value(
                                Value.Type.IDENTIFIER,
                                collection
                        )
                )
        );

        boolean comma = match(TokenType.COMMA);

        if (!comma) {

            throw new PlugException(
                    "join() requires an on: condition, e.g. "
                            + "join(orders, on: (users.id, EQ, "
                            + "orders.user_id)).",
                    peek().getLine()
            );
        }

        if (check(TokenType.IDENTIFIER)
                && peek().getValue().equals("on")
                && peekNext().getType() == TokenType.COLON) {

            advance();

            advance();

            ConditionExpression condition =
                    comparisonExpression();

            arguments.add(
                    new Argument(
                            "on",
                            condition
                    )
            );
        } else {

            throw new PlugException(
                    "Expected 'on:' after the collection in join().",
                    peek().getLine()
            );
        }

        return arguments;
    }

    private List<Argument> arguments() {

        List<Argument> arguments = new ArrayList<>();

        if (check(TokenType.RPAREN)) {
            return arguments;
        }

        do {

            if (check(TokenType.IDENTIFIER)
                    && peekNext().getType() == TokenType.COLON) {

                String name = advance().getValue();

                consume(
                        TokenType.COLON,
                        "Expected ':' after argument name."
                );

                Value value = parseValue();

                arguments.add(
                        new Argument(name, value)
                );

            } else {

                Value value = parseValue();

                arguments.add(
                        new Argument(null, value)
                );
            }

        } while (match(TokenType.COMMA));

        return arguments;
    }

    /*
     * Field names may be collection-qualified inside any
     * parenthesized context: values like orders.total and
     * users.id. The DOT token there can only mean a dotted
     * field — statement-level chain dots always appear
     * after a closing parenthesis, never inside a call's
     * arguments.
     */
    private String qualifyFieldName(String first) {

        while (check(TokenType.DOT)
                && peekNext().getType() == TokenType.IDENTIFIER) {

            advance();

            first = first
                    + "."
                    + advance().getValue();
        }

        return first;
    }

    private Value parseValue() {

        if (check(TokenType.LBRACKET)) {
            return parseList();
        }

        Token token = advance();

        if (token.getType() == TokenType.STRING) {

            return new Value(
                    Value.Type.STRING,
                    token.getValue()
            );
        }

        if (token.getType() == TokenType.NUMBER) {

            String raw = token.getValue();

            /*
             * Decimal numbers become Double; anything else
             * stays an Integer.
             */
            if (raw.contains(".")) {

                return new Value(
                        Value.Type.NUMBER,
                        Double.parseDouble(raw)
                );
            }

            return new Value(
                    Value.Type.NUMBER,
                    Integer.parseInt(raw)
            );
        }

        if (token.getType() == TokenType.IDENTIFIER) {

            if (token.getValue().equals("true")) {

                return new Value(
                        Value.Type.BOOLEAN,
                        true
                );
            }

            if (token.getValue().equals("false")) {

                return new Value(
                        Value.Type.BOOLEAN,
                        false
                );
            }

            if (token.getValue().equals("null")) {

                return new Value(
                        Value.Type.NULL,
                        null
                );
            }

            return new Value(
                    Value.Type.IDENTIFIER,
                    qualifyFieldName(token.getValue())
            );
        }

        throw new PlugException(
                "Expected a value.",
                peek().getLine()
        );
    }

    private Value parseList() {

        consume(
                TokenType.LBRACKET,
                "Expected '['."
        );

        List<Value> values = new ArrayList<>();

        if (check(TokenType.RBRACKET)) {

            advance();

            return new Value(
                    Value.Type.LIST,
                    values
            );
        }

        do {

            values.add(
                    parseValue()
            );

        } while (match(TokenType.COMMA));

        consume(
                TokenType.RBRACKET,
                "Expected ']' after list."
        );

        return new Value(
                Value.Type.LIST,
                values
        );
    }

    private ConditionExpression conditionExpression() {

        return logicalExpression();
    }

    private ConditionExpression logicalExpression() {

        ConditionExpression expression =
                primaryExpression();

        while (peekLogicalKeyword()) {

            Token operator = advance();

            ConditionExpression right =
                    primaryExpression();

            LogicalExpression.Operator logicalOperator;

            if (operator.getType() == TokenType.AND
                    || (operator.getType()
                    == TokenType.IDENTIFIER
                    && operator.getValue()
                    .equalsIgnoreCase("and"))) {

                logicalOperator =
                        LogicalExpression.Operator.AND;

            } else {

                logicalOperator =
                        LogicalExpression.Operator.OR;
            }

            expression =
                    new LogicalExpression(
                            expression,
                            right,
                            logicalOperator
                    );
        }

        return expression;
    }

    /*
     * Lowercase and/or work as logical operators inside
     * expressions, while still lexing as identifiers so
     * chained .and()/.or() calls keep working.
     */
    private boolean peekLogicalKeyword() {

        if (isAtEnd()) {
            return false;
        }

        Token token = peek();

        if (token.getType() == TokenType.AND
                || token.getType() == TokenType.OR) {
            return true;
        }

        return token.getType() == TokenType.IDENTIFIER
                && (token.getValue().equalsIgnoreCase("and")
                || token.getValue().equalsIgnoreCase("or"));
    }

    /*
     * Lowercase not works as a prefix operator inside
     * expressions, the same contextual rule as and/or.
     */
    private boolean peekNotKeyword() {

        if (isAtEnd()) {
            return false;
        }

        Token token = peek();

        if (token.getType() == TokenType.NOT) {
            return true;
        }

        return token.getType() == TokenType.IDENTIFIER
                && token.getValue().equalsIgnoreCase("not");
    }

    private ConditionExpression primaryExpression() {

        if (peekNotKeyword()) {

            advance();

            ConditionExpression expression =
                    primaryExpression();

            return new NotExpression(expression);
        }

        if (!check(TokenType.LPAREN)) {

            throw new PlugException(
                    "Expected '(' before condition.",
                    peek().getLine()
            );
        }

        /*
         * Look at the token after '('.
         *
         * If we have:
         *
         *     (field, ...
         *
         * then this is a comparison.
         *
         * Otherwise it is a grouped expression.
         */

        Token next = peekNext();

        if (next.getType() == TokenType.IDENTIFIER) {

            /*
             * A dotted field (users.age, ...) advances the
             * scan past the qualification before checking
             * whether a ',' ends the field position.
             */
            int index = current + 2;

            if (index < tokens.size()
                    && tokens.get(index).getType()
                    == TokenType.DOT
                    && index + 1 < tokens.size()
                    && tokens.get(index + 1).getType()
                    == TokenType.IDENTIFIER) {

                index += 2;
            }

            if (index >= tokens.size()
                    || tokens.get(index).getType()
                    == TokenType.COMMA) {

                return comparisonExpression();
            }
        }

        consume(
                TokenType.LPAREN,
                "Expected '('."
        );

        ConditionExpression expression =
                logicalExpression();

        consume(
                TokenType.RPAREN,
                "Expected ')' after expression."
        );

        return expression;
    }

    private String consumeFieldName(String message) {

        String field =
                consume(
                        TokenType.IDENTIFIER,
                        message
                ).getValue();

        return qualifyFieldName(field);
    }

    private ConditionExpression comparisonExpression() {

        consume(
                TokenType.LPAREN,
                "Expected '(' before condition."
        );

        String field =
                consumeFieldName("Expected field name.");

        consume(
                TokenType.COMMA,
                "Expected ',' after field."
        );

        String operator =
                consumeIdentifier(
                        "Expected operator."
                );

        /*
         * IS takes three spellings:
         *
         * (email, IS NULL)        IS followed by the NULL keyword
         * (email, IS NOT NULL)    the negated two-keyword form
         * (email, IS, null)       the tuple form with a null value
         *
         * All normalize to the same comparison. Any other value
         * after IS is rejected here, at parse time.
         */
        if (operator.equalsIgnoreCase("IS")) {

            if (check(TokenType.IDENTIFIER)
                    && peek().getValue().equalsIgnoreCase("NULL")) {

                advance();

                consume(
                        TokenType.RPAREN,
                        "Expected ')' after condition."
                );

                return new ComparisonExpression(
                        field,
                        "IS NULL",
                        new Value(Value.Type.NULL, null)
                );
            }

            /*
             * NOT lexes as a keyword token when written
             * uppercase, and as an identifier when written
             * lowercase — accept both forms here.
             */
            boolean isNotKeyword =
                    check(TokenType.NOT)
                            || (check(TokenType.IDENTIFIER)
                            && peek().getValue().equalsIgnoreCase("NOT"));

            if (isNotKeyword) {

                advance();

                if (!check(TokenType.IDENTIFIER)
                        || !peek().getValue().equalsIgnoreCase("NULL")) {

                    throw new PlugException(
                            "Expected NULL after IS NOT.",
                            peek().getLine()
                    );
                }

                advance();

                consume(
                        TokenType.RPAREN,
                        "Expected ')' after condition."
                );

                return new ComparisonExpression(
                        field,
                        "IS NOT NULL",
                        new Value(Value.Type.NULL, null)
                );
            }

            if (check(TokenType.COMMA)) {

                advance();

                Value value = parseValue();

                if (value.getType() != Value.Type.NULL) {

                    throw new PlugException(
                            "IS requires a null value.",
                            peek().getLine()
                    );
                }

                consume(
                        TokenType.RPAREN,
                        "Expected ')' after condition."
                );

                return new ComparisonExpression(
                        field,
                        "IS NULL",
                        value
                );
            }

            throw new PlugException(
                    "Expected NULL or a null value after IS.",
                    peek().getLine()
            );
        }

        consume(
                TokenType.COMMA,
                "Expected ',' after operator."
        );

        Value value =
                parseValue();

        consume(
                TokenType.RPAREN,
                "Expected ')' after condition."
        );

        return new ComparisonExpression(
                field,
                operator,
                value
        );
    }

    private String consumeIdentifier(String message) {

        return consume(
                TokenType.IDENTIFIER,
                message
        ).getValue();
    }

    private Token consume(
            TokenType type,
            String message
    ) {

        if (check(type)) {
            return advance();
        }

        throw new PlugException(
                message,
                peek().getLine()
        );
    }

    private boolean match(TokenType type) {

        if (check(type)) {
            advance();
            return true;
        }

        return false;
    }

    private boolean check(TokenType type) {

        if (isAtEnd()) {
            return type == TokenType.EOF;
        }

        return peek().getType() == type;
    }

    private Token advance() {

        if (!isAtEnd()) {
            current++;
        }

        return previous();
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token peekNext() {

        if (current + 1 >= tokens.size()) {
            return new Token(
                    TokenType.EOF,
                    ""
            );
        }

        return tokens.get(current + 1);
    }

    private Token peekNextNext() {

        if (current + 2 >= tokens.size()) {

            return new Token(
                    TokenType.EOF,
                    ""
            );
        }

        return tokens.get(current + 2);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private boolean isAtEnd() {
        return peek().getType() == TokenType.EOF;
    }
}