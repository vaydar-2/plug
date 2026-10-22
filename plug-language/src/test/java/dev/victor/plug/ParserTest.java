package dev.victor.plug;

import dev.victor.plug.ast.Call;
import dev.victor.plug.ast.ComparisonExpression;
import dev.victor.plug.ast.LogicalExpression;
import dev.victor.plug.ast.NotExpression;
import dev.victor.plug.ast.Program;
import dev.victor.plug.ast.Statement;
import dev.victor.plug.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Tests for the parser: statement shapes, the two
 * where-representations, and parse-error reporting.
 */
public class ParserTest {

    private List<Statement> parseStatements(String source) {

        Program program = new Parser(
                new dev.victor.plug.lexer.Lexer(source).tokenize()
        ).parse();

        return program.getStatements();
    }

    @Test
    public void parseChainedCallsAsOneStatement() {

        List<Statement> statements = parseStatements(
                "plug.get(name, country).where((country, EQ, \"Ghana\"));"
        );

        assertEquals(1, statements.size());

        List<Call> calls = statements.get(0).getCalls();

        assertEquals(2, calls.size());

        assertEquals(
                "get",
                calls.get(0).getMethod()
        );

        assertEquals(
                "where",
                calls.get(1).getMethod()
        );
    }

    /*
     * The flat where form is parsed as a comparison expression,
     * meaning the simple single-condition case also takes the
     * expression path by design.
     */
    @Test
    public void parseFlatWhereIntoComparisonExpression() {

        List<Statement> statements = parseStatements(
                "plug.get(name).where((country, EQ, \"Ghana\"));"
        );

        Call whereCall =
                statements.get(0).getCalls().get(1);

        ComparisonExpression comparison =
                assertInstanceOf(
                        ComparisonExpression.class,
                        whereCall.getExpression()
                );

        assertEquals("country", comparison.getField());
        assertEquals("EQ", comparison.getOperator());
    }

    @Test
    public void parseNotWrapsComparison() {

        List<Statement> statements = parseStatements(
                "plug.get(name).where(NOT ((country, EQ, \"Ghana\")));"
        );

        Call whereCall =
                statements.get(0).getCalls().get(1);

        NotExpression not =
                assertInstanceOf(
                        NotExpression.class,
                        whereCall.getExpression()
                );

        ComparisonExpression comparison =
                assertInstanceOf(
                        ComparisonExpression.class,
                        not.getExpression()
                );

        assertEquals("country", comparison.getField());
    }

    @Test
    public void parseLogicalAndOrTree() {

        List<Statement> grouped = parseStatements(
                "plug.get(name)"
                        + ".where(((age, GT, 18)) AND ((country, EQ, \"Ghana\")));"
        );

        Call whereCall =
                grouped.get(0).getCalls().get(1);

        LogicalExpression logical =
                assertInstanceOf(
                        LogicalExpression.class,
                        whereCall.getExpression()
                );

        assertEquals(
                LogicalExpression.Operator.AND,
                logical.getOperator()
        );

        assertTrue(logical.getLeft() instanceof ComparisonExpression);
        assertTrue(logical.getRight() instanceof ComparisonExpression);
    }

    @Test
    public void parseDecimalNumberAsDouble() {

        List<Statement> statements = parseStatements(
                "plug.get(name).where((balance, GT, 25000.5));"
        );

        Call whereCall =
                statements.get(0).getCalls().get(1);

        ComparisonExpression comparison =
                assertInstanceOf(
                        ComparisonExpression.class,
                        whereCall.getExpression()
                );

        Object value = comparison.getValue().getValue();

        assertEquals(25000.5, value);
    }

    @Test
    public void parseNegativeNumber() {

        List<Statement> statements = parseStatements(
                "plug.get(name).where((age, LT, -1));"
        );

        Call whereCall =
                statements.get(0).getCalls().get(1);

        ComparisonExpression comparison =
                assertInstanceOf(
                        ComparisonExpression.class,
                        whereCall.getExpression()
                );

        assertEquals(-1, comparison.getValue().getValue());
    }

    /*
     * Case-insensitive logical keywords are handled by the
     * parser (contextually), not the lexer, so chained
     * .and()/.or() calls stay unambiguous.
     */
    @Test
    public void parseLowercaseLogicalOperators() {

        List<Statement> statements = parseStatements(
                "plug.get(name)"
                        + ".where(not ((age, GT, 18))"
                        + " and ((role, EQ, \"student\")));"
        );

        Call whereCall =
                statements.get(0).getCalls().get(1);

        LogicalExpression logical =
                assertInstanceOf(
                        LogicalExpression.class,
                        whereCall.getExpression()
                );

        assertEquals(
                LogicalExpression.Operator.AND,
                logical.getOperator()
        );

        assertInstanceOf(
                NotExpression.class,
                logical.getLeft()
        );

        assertInstanceOf(
                ComparisonExpression.class,
                logical.getRight()
        );
    }

    @Test
    public void parseErrorReportsLine() {

        String source =
                "plug.use(\"users\");\n"
                        + "plug.get(name,\n"
                        + "    .where((country, EQ, \"Ghana\");\n";

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> parseStatements(source)
                );

        assertEquals(
                "line 3: Expected a value.",
                exception.getMessage()
        );
    }
}
