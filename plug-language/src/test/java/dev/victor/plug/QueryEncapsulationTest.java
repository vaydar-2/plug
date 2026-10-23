package dev.victor.plug;

import dev.victor.plug.ast.Value;
import dev.victor.plug.runtime.Condition;
import dev.victor.plug.runtime.ExecutionPlan;
import dev.victor.plug.runtime.Join;
import dev.victor.plug.runtime.Query;
import dev.victor.plug.runtime.Sort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Unit tests for the runtime query model's encapsulation:
 * callers must not be able to move an already built Query
 * or ExecutionPlan through the lists they handed in or the
 * accessors they read back.
 */
public class QueryEncapsulationTest {

    @Test
    public void constructorCopiesTheFieldsList() {

        List<String> handedIn = new ArrayList<>();
        handedIn.add("name");

        Query query = new Query(handedIn);

        handedIn.add("surname");

        assertEquals(
                1,
                query.getFields().size()
        );
    }

    @Test
    public void constructorAcceptsNullFieldsAsAnEmptyProjection() {

        Query query = new Query(null);

        assertTrue(query.getFields().isEmpty());
    }

    @Test
    public void getListAccessorsAreUnmodifiable() {

        Query query = new Query(java.util.Arrays.asList("name"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> query.getFields().add("age")
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> query.getConditions().add(
                        new Condition(
                                "name",
                                "EQ",
                                new Value(
                                        Value.Type.STRING,
                                        "Victor"
                                ),
                                Condition.LogicalOperator.AND
                        )
                )
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> query.getJoins().add(
                        new Join(
                                "users.id",
                                "orders.user_id",
                                "orders"
                        )
                )
        );
    }

    @Test
    public void internalBuildersStillWorkThroughTheAccessors() {

        Query query = new Query(java.util.Arrays.asList("name"));

        query.where(
                "country",
                "EQ",
                new Value(Value.Type.STRING, "Ghana")
        );

        query.or(
                "age",
                "LT",
                new Value(Value.Type.NUMBER, 21)
        );

        query.join(
                new Join(
                        "orders.user_id",
                        "users.id",
                        "orders"
                )
        );

        query.sort("balance", "DESC");
        query.limit(5);

        assertEquals(1, query.getFields().size());
        assertEquals(2, query.getConditions().size());
        assertEquals(1, query.getJoins().size());

        /*
         * Conditions keep their own logical operators in
         * call order.
         */
        assertEquals(
                Condition.LogicalOperator.OR,
                query.getConditions().get(1)
                        .getLogicalOperator()
        );

        assertEquals(
                "orders",
                query.getJoins().get(0).getCollection()
        );

        assertEquals(
                "balance",
                query.getSort().getField()
        );

        assertEquals(5, query.getLimit());
    }

    @Test
    public void executionPlanCopiesWriteData() {

        Map<String, Object> handedIn = new HashMap<>();
        handedIn.put("name", "Victor");

        ExecutionPlan plan = new ExecutionPlan(
                new Query(new ArrayList<>()),
                "users",
                ExecutionPlan.Operation.INSERT,
                handedIn
        );

        handedIn.put("name", "Tampered");

        assertEquals(
                "Victor",
                plan.getData().get("name")
        );
    }

    @Test
    public void executionPlanDataIsUnmodifiable() {

        ExecutionPlan plan = new ExecutionPlan(
                new Query(new ArrayList<>()),
                "users",
                ExecutionPlan.Operation.INSERT,
                new HashMap<>()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.getData().put("role", "admin")
        );
    }

    @Test
    public void executionPlanTreatsNullDataAsEmpty() {

        ExecutionPlan plan = new ExecutionPlan(
                new Query(new ArrayList<>()),
                "users",
                ExecutionPlan.Operation.READ,
                null
        );

        assertTrue(plan.getData().isEmpty());
    }

    @Test
    public void readPlanCarriesNoAggregateIntent() {

        ExecutionPlan plan = new ExecutionPlan(
                new Query(new ArrayList<>()),
                "users"
        );

        assertEquals(
                ExecutionPlan.Operation.READ,
                plan.getOperation()
        );

        assertEquals(
                ExecutionPlan.Aggregate.NONE,
                plan.getAggregate()
        );

        assertNull(plan.getAggregateField());
    }

    @Test
    public void aggregatePlanCarriesAggregateIntent() {

        ExecutionPlan plan = new ExecutionPlan(
                new Query(new ArrayList<>()),
                "users",
                ExecutionPlan.Aggregate.SUM,
                "balance"
        );

        assertEquals(
                ExecutionPlan.Operation.READ,
                plan.getOperation()
        );

        assertEquals(
                ExecutionPlan.Aggregate.SUM,
                plan.getAggregate()
        );

        assertEquals(
                "balance",
                plan.getAggregateField()
        );
    }

    @Test
    public void sortAndLimitRemainImmutableValues() {

        Sort sort = new Sort("balance", "DESC");

        assertEquals("balance DESC", sort.toString());
    }
}
