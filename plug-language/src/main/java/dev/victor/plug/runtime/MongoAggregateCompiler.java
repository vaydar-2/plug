package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/*
 * Compiles Plug's aggregate reads into a MongoDB aggregation
 * pipeline for a single collection.
 *
 * The runtime rejects aggregates combined with join() and with
 * sort()/limit(), and this compiler enforces the same contract
 * even when an adapter is driven directly by a plan. A valid
 * aggregate therefore has exactly one source collection:
 *
 *     $match  (the combined WHERE, compiled by M2)
 *     $group  (_id: null, one value)
 *
 * Keeping $match before $group is what makes the WHERE scope the
 * aggregate inputs rather than its single output row.
 *
 * SUM needs one extra group field. MongoDB's $sum ignores null
 * and missing values but returns 0 when every input is ignored,
 * while Plug (like SQL) returns null for a non-empty match whose
 * aggregated field has no values. valueCount counts matching
 * numeric inputs so the executor can tell those cases apart.
 */
final class MongoAggregateCompiler {

    static final String VALUE_FIELD = "aggregateValue";
    static final String VALUE_COUNT_FIELD = "valueCount";

    private static final List<String> NUMERIC_BSON_TYPES =
            Arrays.asList("int", "long", "double", "decimal");

    static final class CompiledAggregate {

        private final String collection;
        private final ExecutionPlan.Aggregate aggregate;
        private final String field;
        private final Class<?> fieldType;
        private final List<Bson> pipeline;

        private CompiledAggregate(
                String collection,
                ExecutionPlan.Aggregate aggregate,
                String field,
                Class<?> fieldType,
                List<Bson> pipeline
        ) {
            this.collection = collection;
            this.aggregate = aggregate;
            this.field = field;
            this.fieldType = fieldType;
            this.pipeline = pipeline;
        }

        String getCollection() {
            return collection;
        }

        ExecutionPlan.Aggregate getAggregate() {
            return aggregate;
        }

        String getField() {
            return field;
        }

        Class<?> getFieldType() {
            return fieldType;
        }

        List<Bson> getPipeline() {
            return pipeline;
        }
    }

    private final MongoSchemaResolver resolver;

    MongoAggregateCompiler(MongoSchemaResolver resolver) {

        if (resolver == null) {
            throw new PlugException("Schema resolver is required.");
        }

        this.resolver = resolver;
    }

    CompiledAggregate compile(ExecutionPlan plan) {

        if (plan == null
                || plan.getOperation() == null
                || plan.getQuery() == null) {

            throw new PlugException(
                    "Execution plan is required."
            );
        }

        ExecutionPlan.Aggregate aggregate = plan.getAggregate();

        if (aggregate == null
                || aggregate == ExecutionPlan.Aggregate.NONE) {

            throw new PlugException(
                    "Aggregate operation is required."
            );
        }

        Query query = plan.getQuery();

        if (!query.getJoins().isEmpty()) {

            throw new PlugException(
                    "An aggregate cannot be combined with join()."
            );
        }

        if (query.getSort() != null
                || query.getLimit() != null) {

            throw new PlugException(
                    "sort()/limit() cannot be combined with an aggregate."
            );
        }

        Map<String, Class<?>> schema =
                resolver.resolve(plan.getCollection()).getFields();

        QueryValidation.validateAggregate(
                aggregate,
                plan.getAggregateField(),
                schema
        );
        QueryValidation.validateWhere(query, schema);

        Bson filter = MongoQueryCompiler.compileFilter(
                query,
                schema
        );

        List<Bson> pipeline = new ArrayList<>();

        /*
         * Always include the match stage, even for an unfiltered
         * aggregate: it documents and fixes WHERE-before-fold
         * ordering in every generated pipeline.
         */
        pipeline.add(new Document("$match", filter));
        pipeline.add(new Document("$group", groupStage(
                aggregate,
                plan.getAggregateField()
        )));

        Class<?> fieldType = aggregate == ExecutionPlan.Aggregate.COUNT
                ? null
                : schema.get(plan.getAggregateField());

        return new CompiledAggregate(
                plan.getCollection(),
                aggregate,
                plan.getAggregateField(),
                fieldType,
                pipeline
        );
    }

    private static Document groupStage(
            ExecutionPlan.Aggregate aggregate,
            String field
    ) {

        Document group = new Document("_id", null);

        if (aggregate == ExecutionPlan.Aggregate.COUNT) {
            group.append(VALUE_FIELD, new Document("$sum", 1));
            return group;
        }

        String path = "$" + field;

        switch (aggregate) {

            case SUM:
                group.append(
                        VALUE_FIELD,
                        new Document("$sum", path)
                );
                group.append(
                        VALUE_COUNT_FIELD,
                        new Document(
                                "$sum",
                                new Document(
                                        "$cond",
                                        Arrays.asList(
                                                new Document(
                                                        "$in",
                                                        Arrays.asList(
                                                                new Document(
                                                                        "$type",
                                                                        path
                                                                ),
                                                                NUMERIC_BSON_TYPES
                                                        )
                                                ),
                                                1,
                                                0
                                        )
                                )
                        )
                );
                return group;

            case AVG:
                group.append(
                        VALUE_FIELD,
                        new Document("$avg", path)
                );
                return group;

            case MIN:
                group.append(
                        VALUE_FIELD,
                        new Document("$min", path)
                );
                return group;

            case MAX:
                group.append(
                        VALUE_FIELD,
                        new Document("$max", path)
                );
                return group;

            default:
                throw new PlugException(
                        "Unsupported aggregate: " + aggregate
                );
        }
    }
}
