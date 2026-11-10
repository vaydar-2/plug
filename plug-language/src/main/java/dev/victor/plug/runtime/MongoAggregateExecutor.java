package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import com.mongodb.client.MongoDatabase;

import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/*
 * Executes Plug's COUNT, SUM, AVG, MIN and MAX reads against
 * MongoDB.
 *
 * The compiler owns validation and pipeline shape; this class
 * owns the two things a pipeline cannot express directly:
 *
 *   - an empty pipeline result means COUNT 0 and null for every
 *     other aggregate;
 *   - every returned BSON number is decoded through M0's exact
 *     MongoTypeMapping rules, so overflow or an incompatible
 *     BSON type fails explicitly instead of being truncated.
 *
 * SUM also reads the compiler's valueCount. MongoDB returns 0
 * when all matched values are null/missing, but Plug returns
 * null in that case; valueCount distinguishes it from a real
 * sum of zero. A missing valueCount (as in a focused test
 * double) means the sum came from at least one value.
 */
final class MongoAggregateExecutor {

    interface Reader {

        List<Document> aggregate(
                String collection,
                List<Bson> pipeline
        );
    }

    private final Reader reader;
    private final MongoAggregateCompiler compiler;

    MongoAggregateExecutor(
            MongoDatabase database,
            MongoSchemaResolver resolver
    ) {

        if (database == null) {
            throw new PlugException(
                    "MongoDB database is required."
            );
        }

        this.reader = new DriverReader(database);
        this.compiler = new MongoAggregateCompiler(resolver);
    }

    /*
     * Package-private seam for focused tests.
     */
    MongoAggregateExecutor(
            Reader reader,
            MongoSchemaResolver resolver
    ) {

        if (reader == null) {
            throw new PlugException(
                    "Aggregate reader is required."
            );
        }

        this.reader = reader;
        this.compiler = new MongoAggregateCompiler(resolver);
    }

    QueryResult execute(ExecutionPlan plan) {

        MongoAggregateCompiler.CompiledAggregate compiled =
                compiler.compile(plan);

        List<Document> documents;

        try {

            documents = reader.aggregate(
                    compiled.getCollection(),
                    compiled.getPipeline()
            );

        } catch (PlugException e) {

            throw e;

        } catch (RuntimeException e) {

            throw new PlugException(
                    "Cannot aggregate MongoDB collection '"
                            + compiled.getCollection()
                            + "': " + e.getMessage()
            );
        }

        return materialize(compiled, documents);
    }

    private static QueryResult materialize(
            MongoAggregateCompiler.CompiledAggregate compiled,
            List<Document> documents
    ) {

        if (documents == null || documents.isEmpty()) {

            if (compiled.getAggregate()
                    == ExecutionPlan.Aggregate.COUNT) {

                return new QueryResult(
                        0,
                        new ArrayList<>(),
                        0
                );
            }

            return new QueryResult(
                    0,
                    new ArrayList<>(),
                    null
            );
        }

        if (documents.size() > 1) {

            throw new PlugException(
                    "Aggregate returned more than one result."
            );
        }

        Document document = documents.get(0);

        if (compiled.getAggregate()
                == ExecutionPlan.Aggregate.COUNT) {

            Object value = document.get(
                    MongoAggregateCompiler.VALUE_FIELD
            );

            Object converted = MongoTypeMapping.toPlugValue(
                    "COUNT",
                    value,
                    Integer.class
            );

            if (converted == null) {
                throw new PlugException(
                        "COUNT result is missing."
                );
            }

            int count = (Integer) converted;

            return new QueryResult(
                    count,
                    new ArrayList<>(),
                    count
            );
        }

        if (compiled.getAggregate()
                == ExecutionPlan.Aggregate.SUM
                && readValueCount(document) == 0) {

            return new QueryResult(
                    0,
                    new ArrayList<>(),
                    null
            );
        }

        Object raw = document.get(
                MongoAggregateCompiler.VALUE_FIELD
        );

        Object value = MongoTypeMapping.toPlugValue(
                compiled.getField(),
                raw,
                resultType(compiled)
        );

        return new QueryResult(
                0,
                new ArrayList<>(),
                value
        );
    }

    /*
     * AVG is always a Double in Plug, even for an Integer
     * field; every other aggregate keeps the field's declared
     * Plug type.
     */
    private static Class<?> resultType(
            MongoAggregateCompiler.CompiledAggregate compiled
    ) {

        if (compiled.getAggregate()
                == ExecutionPlan.Aggregate.AVG) {

            return Double.class;
        }

        return compiled.getFieldType();
    }

    /*
     * -1 means the group document carries no count (focused
     * test doubles), so the aggregate value is taken at face
     * value. A real SUM pipeline always writes the count.
     */
    private static long readValueCount(Document document) {

        String field = MongoAggregateCompiler.VALUE_COUNT_FIELD;

        if (!document.containsKey(field)) {
            return -1;
        }

        Object value = document.get(field);

        if (value instanceof Integer) {
            return (Integer) value;
        }

        if (value instanceof Long) {
            return (Long) value;
        }

        throw new PlugException(
                "Aggregate value count " + value
                        + " cannot be represented as Long."
        );
    }

    private static final class DriverReader
            implements Reader {

        private final MongoDatabase database;

        private DriverReader(MongoDatabase database) {
            this.database = database;
        }

        @Override
        public List<Document> aggregate(
                String collection,
                List<Bson> pipeline
        ) {

            return database.getCollection(collection)
                    .aggregate(pipeline)
                    .into(new ArrayList<>());
        }
    }
}
