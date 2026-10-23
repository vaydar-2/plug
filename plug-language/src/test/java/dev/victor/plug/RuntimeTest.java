package dev.victor.plug;

import dev.victor.plug.ast.Program;
import dev.victor.plug.lexer.Lexer;
import dev.victor.plug.parser.Parser;
import dev.victor.plug.runtime.MockData;
import dev.victor.plug.runtime.Runtime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * End-to-end runtime tests: full lex -> parse -> execute runs
 * against the mock adapter, with output captured instead of
 * depending on console inspection.
 */
public class RuntimeTest {

    /*
     * Write tests mutate the shared mock data, so every
     * test starts from a pristine copy of the seeded users
     * list. Updates replace entries with copies instead of
     * mutating the seeded maps, which makes this restore
     * sound across test order.
     */
    private static final List<Map<String, Object>> ORIGINAL_USERS =
            new ArrayList<>(MockData.DATA.get("users"));

    @BeforeEach
    public void restoreMockData() {

        MockData.DATA.put(
                "users",
                new ArrayList<>(ORIGINAL_USERS)
        );
    }

    private String runCaptured(String source) {

        PrintStream originalOut = System.out;

        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream();

        System.setOut(new PrintStream(
                buffer,
                true,
                StandardCharsets.UTF_8
        ));

        try {

            Program program = new Parser(
                    new Lexer(source).tokenize()
            ).parse();

            new Runtime().execute(program);

        } finally {

            System.out.flush();
            System.setOut(originalOut);
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static final String connectAndSelect =
            "plug.connect(host: \"localhost\", port: 5432, "
                    + "database: \"mydb\", username: \"victor\", "
                    + "password: \"secret\");\n"
                    + "plug.use(\"users\");\n";

    @Test
    public void flatWhereFiltersByCountry() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name, country)"
                        + ".where((country, EQ, \"Ghana\"));"
        );

        assertTrue(output.contains("Records found: 11"));

        assertTrue(output.contains("{country=Ghana, name=Victor}"));

        assertTrue(
                !output.contains("Nigeria")
                        && !output.contains("Kenya")
        );
    }

    @Test
    public void notExpressionFiltersByCountry() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name, country)"
                        + ".where(NOT ((country, EQ, \"Ghana\")));"
        );

        assertTrue(output.contains("Records found: 9"));

        /*
         * Filtered rows must not be Ghana, but the echoed
         * execution plan prints the condition's literal, so
         * match on the record field instead.
         */
        assertTrue(
                !output.contains("country=Ghana")
        );
    }

    @Test
    public void groupedAndOrEvaluation() {

        // Both sides true for Victor: age 20 AND country Ghana.
        String both = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".where(((age, GT, 18)) AND ((country, EQ, \"Ghana\")));"
        );

        assertTrue(both.contains("Records found: 10"));

        // OR side: students (role EQ) makes the record match
        // even when the country check fails.
        String either = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".where(((age, GT, 18)) OR ((role, EQ, \"student\")));"
        );

        assertTrue(either.contains("Records found: 20"));
    }

    /*
     * The chained flat forms take unparenthesized arguments:
     * .and(field, OP, value). The leading where() populates
     * the expression tree, the flat calls append to the
     * conditions list — both must be honored (left-to-right).
     */
    @Test
    public void chainedAndOrCallsCombineBothPaths() {

        String andOutput = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".where((country, EQ, \"Ghana\"))"
                        + ".and(active, EQ, true);"
        );

        assertTrue(andOutput.contains("Records found: 6"));

        String orOutput = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".where((country, EQ, \"Kenya\"))"
                        + ".or(role, EQ, \"developer\");"
        );

        assertTrue(orOutput.contains("Records found: 8"));

        /*
         * Regression: flat conditions were once silently
         * dropped whenever an expression tree was present.
         * active=false records must be filtered out here.
         */
        assertTrue(
                !andOutput.contains("active=false")
        );
    }

    /*
     * A fully flat chain where/and/or folds strictly
         * left-to-right: (role developer AND age>21) OR
         * country Nigeria. Developers over 21 are Michael,
         * Emma, Chris, Rachel and Andrew (5 rows); the OR
         * branch adds Nigeria's Samuel, Olivia and Martha
         * (non-developers), giving 8. An all-AND folding
         * would give 5 instead.
         */
    @Test
    public void flatChainEvaluatesLeftToRight() {

        String leftToRight = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((role, EQ, \"developer\"))"
                        + ".and(age, GT, 21).or(country, EQ, \"Nigeria\");"
        );

        assertTrue(leftToRight.contains("Records found: 8"));

        /*
         * (Ghana OR age<21) AND role manager: only James
         * (31, Ghana, manager) and Thomas (32, Ghana,
         * manager) survive; naive OR-before-AND wiring
         * would pull non-manager students onto the score.
         */
        String ordered = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((country, EQ, \"Ghana\"))"
                        + ".or(age, LT, 21).and(role, EQ, \"manager\");"
        );

        assertTrue(ordered.contains("Records found: 2"));
        assertTrue(ordered.contains("{name=James}"));
        assertTrue(ordered.contains("{name=Thomas}"));
    }

    @Test
    public void nullComparison() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".where((email, EQ, null));"
        );

        assertTrue(output.contains("Records found: 1"));

        assertTrue(output.contains("Alex"));
    }

    @Test
    public void inOperatorWithList() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".where((country, IN, [\"Kenya\", \"Nigeria\"]));"
        );

        assertTrue(output.contains("Records found: 9"));
    }

    @Test
    public void negativeNumberFilterMatchesNothing() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".where((age, LT, -1));"
        );

        assertTrue(output.contains("Records found: 0"));
    }

    @Test
    public void decimalFilterTypeChecksAgainstSchema() {

        /*
         * balance is Integer in the users schema, so a
         * decimal literal must be rejected by validation.
         */
        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".where((balance, GT, 25000.5));"
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("Type mismatch for field 'balance'")
        );
        assertTrue(
                exception.getMessage().contains("Double")
        );
    }

    @Test
    public void decimalLimitIsRejected() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".limit(3.5);"
                        )
                );

        assertEquals(
                "limit() expects a whole number.",
                exception.getMessage()
        );
    }

    @Test
    public void sortAndLimit() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name, age)"
                        + ".where((country, EQ, \"Ghana\"))"
                        + ".sort(age, DESC)"
                        + ".limit(2);"
        );

        assertTrue(output.contains("Records found: 2"));
        assertTrue(output.contains("age=31}")); // James, oldest Ghana
    }

    @Test
    public void unknownCollectionReportsError() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect.replace(
                                        "users",
                                        "products"
                                )
                                        + "plug.get(name);"
                        )
                );

        assertEquals(
                "Collection not found: products",
                exception.getMessage()
        );
    }

    @Test
    public void useWithoutConnectionReportsError() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                "plug.use(\"users\");"
                        )
                );

        assertEquals(
                "No database connection. Use plug.connect() first.",
                exception.getMessage()
        );
    }

    /*
     * "postgres" is a real driver since the PostgreSQL
     * adapter milestone; mongodb is the next planned one
     * and still unregistered.
     */
    @Test
    public void unknownDriverReportsAvailability() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                "plug.connect(driver: \"mongodb\", "
                                        + "host: \"localhost\", port: 27017, "
                                        + "database: \"mydb\", username: \"victor\", "
                                        + "password: \"secret\");"
                        )
                );

        assertEquals(
                "Unknown driver: mongodb. "
                        + "Available drivers: mock, postgres, postgresql",
                exception.getMessage()
        );
    }

    @Test
    public void explicitPlugCloseOutputsLifecycle() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).limit(1);"
                        + "plug.close();"
        );

        assertTrue(output.contains("Records found: 1"));

        assertTrue(
                output.contains("Mock database connection closed.")
        );

        /*
         * plug.use after plug.close() must fail: the
         * connection is gone.
         */
        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.close();"
                                        + "plug.use(\"users\");"
                        )
                );

        assertEquals(
                "No database connection. Use plug.connect() first.",
                exception.getMessage()
        );
    }

    @Test
    public void connectionAutoClosesAfterProgramRun() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).limit(1);"
        );

        assertTrue(
                output.contains("Mock database connection closed.")
        );
    }

    @Test
    public void insertAddsRecordVisibleToLaterQueries() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.insert(name: \"Kwame\", age: 22, "
                        + "email: \"kwame@gmail.com\", balance: 500, "
                        + "active: true, country: \"Ghana\", "
                        + "role: \"student\");"
                        + "plug.get(name).where((name, EQ, \"Kwame\"));"
        );

        assertTrue(output.contains("Records inserted: 1"));

        assertTrue(output.contains("Records found: 1"));

        assertTrue(output.contains("Kwame"));
    }

    @Test
    public void insertAutoAssignsIdAboveMaximum() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.insert(name: \"Kwame\", age: 22, "
                        + "email: \"kwame@gmail.com\", balance: 500, "
                        + "active: true, country: \"Ghana\", "
                        + "role: \"student\");"
                        + "plug.get(id).where((name, EQ, \"Kwame\"));"
        );

        assertTrue(output.contains("id=21"));
    }

    @Test
    public void insertHonorsExplicitIdAndRejectsDuplicates() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.insert(id: 99, name: \"Zed\");"
                        + "plug.get(name).where((id, EQ, 99));"
        );

        assertTrue(output.contains("Records inserted: 1"));

        assertTrue(output.contains("Zed"));

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.insert(id: 1, name: \"Zed\");"
                        )
                );

        assertEquals(
                "Duplicate id: 1",
                exception.getMessage()
        );
    }

    @Test
    public void insertRejectsUnknownField() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.insert(nickname: \"Zed\");"
                        )
                );

        assertEquals(
                "Unknown field: nickname",
                exception.getMessage()
        );
    }

    @Test
    public void insertRejectsTypeMismatch() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.insert(balance: 2000.5);"
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("Type mismatch for field 'balance'")
        );
    }

    @Test
    public void insertRejectsUnnamedArguments() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.insert(\"Victor\");"
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("named arguments")
        );
    }

    @Test
    public void insertDoesNotAcceptWhere() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.insert(name: \"Kwame\")"
                                        + ".where((id, EQ, 1));"
                        )
                );

        assertEquals(
                "insert() does not accept where().",
                exception.getMessage()
        );
    }

    @Test
    public void updateChangesOnlyMatchingRecords() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.update(balance: 1).where((id, LTE, 2));"
                        + "plug.get(id, balance).where((id, LTE, 2));"
        );

        assertTrue(output.contains("Records updated: 2"));

        assertTrue(output.contains("id=1"));

        assertTrue(output.contains("id=2"));

        /*
         * Only the two matched records carry the new
         * balance afterwards.
         */
        assertTrue(output.contains("balance=1"));
    }

    @Test
    public void updateWithoutWhereIsRejected() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.update(balance: 1);"
                        )
                );

        assertEquals(
                "update() requires a where() condition.",
                exception.getMessage()
        );
    }

    @Test
    public void deleteRemovesMatchingRecords() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.delete().where((country, EQ, \"Kenya\"));"
                        + "plug.get(name).where((country, EQ, \"Kenya\"));"
        );

        assertTrue(output.contains("Records deleted: 4"));

        assertTrue(output.contains("Records found: 0"));
    }

    @Test
    public void deleteWithoutWhereIsRejected() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.delete();"
                        )
                );

        assertEquals(
                "delete() requires a where() condition.",
                exception.getMessage()
        );
    }

    @Test
    public void writeRequiresConnection() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                "plug.insert(name: \"Victor\");"
                        )
                );

        assertEquals(
                "No database connection. Use plug.connect() first.",
                exception.getMessage()
        );
    }

    @Test
    public void countAllRecords() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).count();"
        );

        assertTrue(output.contains("COUNT = 20"));
    }

    @Test
    public void countWithWhereConditions() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get().where((country, EQ, \"Ghana\")).count();"
        );

        assertTrue(output.contains("COUNT = 11"));

        /*
         * An aggregate never prints rows.
         */
        assertTrue(!output.contains("{name="));
    }

    @Test
    public void sumBalances() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get().sum(balance);"
        );

        assertTrue(output.contains("SUM(balance) = 590700"));
    }

    @Test
    public void sumScopedByWhere() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get().where((id, LTE, 2)).sum(balance);"
        );

        assertTrue(output.contains("SUM(balance) = 43500"));
    }

    @Test
    public void avgBalances() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get().avg(balance);"
        );

        assertTrue(output.contains("AVG(balance) = " + 590700.0 / 20));
    }

    @Test
    public void minMaxAgeAndBalance() {

        String ageOutput = runCaptured(
                connectAndSelect
                        + "plug.get().min(age);"
        );

        assertTrue(ageOutput.contains("MIN(age) = 17"));

        String balanceOutput = runCaptured(
                connectAndSelect
                        + "plug.get().max(balance);"
        );

        assertTrue(balanceOutput.contains("MAX(balance) = 91000"));

        /*
         * MIN/MAX also work on comparable strings
         * (lexicographic order).
         */
        String nameOutput = runCaptured(
                connectAndSelect
                        + "plug.get().min(name);"
        );

        assertTrue(nameOutput.contains("MIN(name) = Alex"));
    }

    @Test
    public void aggregatesOnEmptyMatches() {

        String countOutput = runCaptured(
                connectAndSelect
                        + "plug.get().where((age, LT, -1)).count();"
        );

        assertTrue(countOutput.contains("COUNT = 0"));

        /*
         * SUM over nothing is null, matching SQL semantics.
         */
        String sumOutput = runCaptured(
                connectAndSelect
                        + "plug.get().where((age, LT, -1)).sum(balance);"
        );

        assertTrue(sumOutput.contains("SUM(balance) = null"));

        /* AVG/MIN/MAX over nothing are null as well. */
        String avgOutput = runCaptured(
                connectAndSelect
                        + "plug.get().where((age, LT, -1)).avg(balance);"
        );

        assertTrue(avgOutput.contains("AVG(balance) = null"));

        String minOutput = runCaptured(
                connectAndSelect
                        + "plug.get().where((age, LT, -1)).min(balance);"
        );

        assertTrue(minOutput.contains("MIN(balance) = null"));

        String maxOutput = runCaptured(
                connectAndSelect
                        + "plug.get().where((age, LT, -1)).max(balance);"
        );

        assertTrue(maxOutput.contains("MAX(balance) = null"));
    }

    /*
     * Aggregate null-handling: nulls in the aggregated field
     * are skipped, so AVG divides by the count of non-null
     * values, not by the row count.
     */
    @Test
    public void avgTreatsNullValuesAsAbsent() {

        runCaptured(
                connectAndSelect
                        + "plug.update(balance: null).where((id, EQ, 1));"
        );

        String output = runCaptured(
                connectAndSelect
                        + "plug.get().avg(balance);"
        );

        /* 590700 - Victor's 25000 = 565700 over the 19 non-null rows. */
        assertTrue(output.contains("AVG(balance) = " + 565700.0 / 19));
    }

    @Test
    public void minSkipsNullValues() {

        /*
         * Alex's email is the only null one; SQL-style MIN
         * ignores it and keeps the lexicographic minimum
         * over the remaining players.
         */
        String output = runCaptured(
                connectAndSelect
                        + "plug.get().min(email);"
        );

        assertTrue(output.contains("MIN(email) = andrew@gmail.com"));
    }

    @Test
    public void sumOnNonNumericFieldIsRejected() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get().sum(name);"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "SUM requires a numeric field"
                )
        );
    }

    @Test
    public void aggregateOnUnknownFieldIsRejected() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get().min(nickname);"
                        )
                );

        assertEquals(
                "Unknown field: nickname",
                exception.getMessage()
        );
    }

    @Test
    public void countTakesNoArguments() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get().count(name);"
                        )
                );

        assertEquals(
                "count() takes no arguments.",
                exception.getMessage()
        );
    }

    @Test
    public void sumExpectsAFieldArgument() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get().sum();"
                        )
                );

        assertEquals(
                "sum() expects one field name, e.g. sum(balance).",
                exception.getMessage()
        );
    }

    @Test
    public void aggregateMustBeTheLastCall() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).count().limit(3);"
                        )
                );

        assertEquals(
                "count() must be the last call in the chain.",
                exception.getMessage()
        );
    }

    @Test
    public void sortCannotPrecedeAggregate() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).sort(age, DESC).count();"
                        )
                );

        assertEquals(
                "sort()/limit() cannot be combined "
                        + "with an aggregate.",
                exception.getMessage()
        );
    }

    @Test
    public void aggregateCannotFollowWrite() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.insert(name: \"Victor\").min(age);"
                        )
                );

        assertEquals(
                "An aggregate cannot follow insert().",
                exception.getMessage()
        );
    }

    @Test
    public void aggregateWithoutGetIsRejected() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.count();"
                        )
                );

        assertEquals(
                "count() must follow get().",
                exception.getMessage()
        );
    }

    @Test
    public void betweenMatchesInclusiveBounds() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((age, BETWEEN, [18, 20]));"
        );

        /*
         * Victor (20), Daniel (19), Brian (18); Alex at 17
         * is below the low bound and is excluded.
         */
        assertTrue(output.contains("Records found: 3"));

        assertTrue(output.contains("Victor"));

        assertTrue(output.contains("Brian"));

        assertTrue(!output.contains("Alex"));
    }

    @Test
    public void betweenAlsoWorksOnStrings() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((name, BETWEEN, [\"Brian\", \"Chris\"]));"
        );

        assertTrue(output.contains("Records found: 2"));

        assertTrue(output.contains("Brian"));

        assertTrue(output.contains("Chris"));
    }

    @Test
    public void betweenRequiresAListOfTwoValues() {

        PlugException notAList =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).where((age, BETWEEN, 18));"
                        )
                );

        assertEquals(
                "BETWEEN requires a list of two values.",
                notAList.getMessage()
        );

        PlugException wrongArity =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).where((age, BETWEEN, [18, 20, 22]));"
                        )
                );

        assertEquals(
                "BETWEEN requires exactly two values.",
                wrongArity.getMessage()
        );
    }

    @Test
    public void betweenBoundsAreTypeChecked() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).where((age, BETWEEN, [\"a\", \"b\"]));"
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("Type mismatch for field 'age'")
        );
    }

    @Test
    public void likePercentMatchesSubstring() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((name, LIKE, \"%li%\"));"
        );

        assertTrue(output.contains("Records found: 1"));

        assertTrue(output.contains("Olivia"));
    }

    @Test
    public void likeUnderscoreMatchesOneCharacter() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((name, LIKE, \"_ictor\"));"
        );

        assertTrue(output.contains("Records found: 1"));

        assertTrue(output.contains("Victor"));
    }

    @Test
    public void likeIsAnchoredToTheFullString() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((name, LIKE, \"li\"));"
        );

        assertTrue(output.contains("Records found: 0"));
    }

    @Test
    public void likeNullFieldsNeverMatch() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((email, LIKE, \"%@outlook.com\"));"
        );

        /*
         * Three outlook addresses (Sarah, Linda, Jessica,
         * Rachel); Alex has a null email and is skipped,
         * not an error.
         */
        assertTrue(output.contains("Records found: 4"));
    }

    @Test
    public void likeRequiresStringValues() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).where((age, LIKE, \"%2%\"));"
                        )
                );

        /*
         * Schema type checking fires before the LIKE check:
         * a string pattern against an integer field is a
         * type mismatch, which is the more informative error.
         */
        assertEquals(
                "Type mismatch for field 'age'. "
                        + "Expected Integer but got String",
                exception.getMessage()
        );
    }

    @Test
    public void isNullMatchesOnlyNullFields() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((email, IS NULL));"
        );

        assertTrue(output.contains("Records found: 1"));

        assertTrue(output.contains("Alex"));
    }

    @Test
    public void isNotNullMatchesAllPresentFields() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((email, IS NOT NULL));"
        );

        assertTrue(output.contains("Records found: 19"));
    }

    @Test
    public void isNullWorksWithoutTheValueSlot() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((email, IS, null));"
        );

        assertTrue(output.contains("Records found: 1"));

        assertTrue(output.contains("Alex"));
    }

    @Test
    public void isNullOperatesInsideExpressions() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where((email, IS NULL) OR (age, GT, 30));"
        );

        /*
         * Alex (null email) plus James (31) and Thomas (32)
         * from the age branch.
         */
        assertTrue(output.contains("Records found: 3"));

        assertTrue(output.contains("Alex"));

        assertTrue(output.contains("James"));

        assertTrue(output.contains("Thomas"));
    }

    @Test
    public void isNullCanBeNegatedAndLowercased() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).where(NOT (email, is null));"
        );

        assertTrue(output.contains("Records found: 19"));
    }

    @Test
    public void flatIsFormRequiresNullValue() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).and(name, IS, \"Victor\");"
                        )
                );

        assertEquals(
                "IS requires a null value.",
                exception.getMessage()
        );
    }

    @Test
    public void flatIsFormMapsToIsNull() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name).and(email, IS, null);"
        );

        assertTrue(output.contains("Records found: 1"));
    }

    @Test
    public void parserRejectsBareIs() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).where((email, IS));"
                        )
                );

        /*
         * Parse errors carry line numbers.
         */
        assertTrue(
                exception.getMessage().contains(
                        "Expected NULL or a null value after IS."
                )
        );
    }

    @Test
    public void parserRejectsIncompleteIsNot() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name).where((email, IS NOT, null));"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "Expected NULL after IS NOT."
                )
        );
    }

    /*
     * ------------------------------------------------------------------ joins
     *
     * The mock data ships 12 orders keyed to user ids plus
     * one order whose user_id is null, and an inventory
     * collection whose 'product' field collides with
     * orders' — cases these tests pin down.
     */
    private static final String ghanaJoin =
            connectAndSelect
                    + "plug.get(name, orders.product, orders.total)"
                    + ".join(orders, on: (users.id, EQ, orders.user_id))"
                    + ".where((country, EQ, \"Ghana\"))"
                    + ".sort(orders.total, DESC)"
                    + ".limit(3);\n";

    @Test
    public void joinCombinesAndFiltersCollections() {

        String output = runCaptured(ghanaJoin);

        /*
         * Ghana users have 6 matching joined rows; the count
         * reports rows after sort/limit, so 3.
         */
        assertTrue(output.contains("Records found: 3"));

        assertTrue(output.contains(
                "{name=Jessica, orders.total=400, orders.product=Card}"
        ));

        assertTrue(output.contains(
                "{name=James, orders.total=300, orders.product=Monitor}"
        ));

        assertTrue(output.contains(
                "{name=Thomas, orders.total=120, orders.product=Headset}"
        ));
    }

    @Test
    public void joinAcceptsEitherKeyOrientation() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name, orders.product)"
                        + ".join(orders, on: (orders.user_id, EQ, users.id))"
                        + ".where((orders.total, BETWEEN, [100, 500]));\n"
        );

        assertTrue(output.contains("Records found: 6"));

        assertTrue(output.contains(
                "{name=Sarah, orders.product=Desk}"
        ));

        assertTrue(output.contains(
                "{name=Linda, orders.product=Speaker}"
        ));
    }

    @Test
    public void joinDropsNullKeysAndRowlessRecords() {

        /*
         * 13 orders exist but one has a null user_id, and
         * nine users have no orders: an inner join over
         * users matches exactly 12 rows.
         */
        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name, orders.product)"
                        + ".join(orders, on: (users.id, EQ, orders.user_id));\n"
        );

        assertTrue(output.contains("Records found: 12"));

        /* The Gift card order has a null join key. */
        assertTrue(!output.contains("Gift card"));
    }

    @Test
    public void joinBareIdResolvesToBaseCollection() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name, orders.product)"
                        + ".join(orders, on: (users.id, EQ, orders.user_id))"
                        + ".where((id, EQ, 1));\n"
        );

        /* Bare 'id' means users.id, which is Victor. */
        assertTrue(output.contains("Records found: 1"));

        assertTrue(output.contains(
                "{name=Victor, orders.product=Keyboard}"
        ));
    }

    @Test
    public void joinBareFieldResolvesToJoinedCollection() {

        /*
         * 'total' exists only in orders, so it resolves to
         * orders.total without needing qualification.
         */
        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name, total)"
                        + ".join(orders, on: (users.id, EQ, orders.user_id))"
                        + ".where((total, GT, 200));\n"
        );

        assertTrue(output.contains("Records found: 5"));

        assertTrue(output.contains("{total=1200, name=Michael}"));
    }

    @Test
    public void joinExpressionWhereUsesQualifiedFields() {

        String output = runCaptured(
                connectAndSelect
                        + "plug.get(name)"
                        + ".join(orders, on: (users.id, EQ, orders.user_id))"
                        + ".where((orders.total, GT, 200) OR (country, EQ, \"Kenya\"));\n"
        );

        assertTrue(output.contains("Records found: 6"));

        assertTrue(!output.contains("Victor"));
    }

    @Test
    public void joinRejectsAmbiguousBareField() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(orders, on: (users.id, EQ, orders.user_id))"
                                        + ".join(inventory, on: (orders.id, EQ, inventory.order_id))"
                                        + ".where((product, EQ, \"Laptop\"));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "Ambiguous field: product"
                )
        );
    }

    @Test
    public void joinRejectsNonEqualityKey() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(orders, on: (users.id, GT, orders.user_id));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "join() keys must match with EQ"
                )
        );
    }

    @Test
    public void joinRejectsUnqualifiedKeys() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(orders, on: (id, EQ, orders.user_id));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "collection-qualified"
                )
        );
    }

    @Test
    public void joinRejectsSelfReferencingKey() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(orders, on: (users.id, EQ, users.name));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "cannot key a collection to itself"
                )
        );
    }

    @Test
    public void joinRejectsUnknownCollection() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(categories, on: (users.id, EQ, categories.id));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "Collection not found: categories"
                )
        );
    }

    @Test
    public void joinRejectsDuplicateCollection() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(orders, on: (users.id, EQ, orders.user_id))"
                                        + ".join(orders, on: (users.id, EQ, orders.user_id));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "Duplicate collection in join: orders"
                )
        );
    }

    @Test
    public void joinRejectsMismatchedKeyTypes() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(orders, on: (users.name, EQ, orders.user_id));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "Type mismatch in join key"
                )
        );
    }

    @Test
    public void joinRejectsOnWrites() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.update(balance: 1)"
                                        + ".join(orders, on: (users.id, EQ, orders.user_id))"
                                        + ".where((id, EQ, 1));\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "join() is only valid on reads."
                )
        );
    }

    @Test
    public void joinRejectsWithAggregates() {

        PlugException exception =
                assertThrows(
                        PlugException.class,
                        () -> runCaptured(
                                connectAndSelect
                                        + "plug.get(name)"
                                        + ".join(orders, on: (users.id, EQ, orders.user_id))"
                                        + ".count();\n"
                        )
                );

        assertTrue(
                exception.getMessage().contains(
                        "An aggregate cannot be combined with join()."
                )
        );
    }
}
