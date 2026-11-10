package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import com.mongodb.client.MongoDatabase;

import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Executes joined Plug reads on MongoDB.
 *
 * MongoJoinCompiler owns the pipeline shape and all validation;
 * this class owns driver mechanics and result materialization.
 * The compilation result names each requested output field and
 * its physical aggregation path, so rows are rebuilt as
 * LinkedHashMaps keyed by the user's original spelling — bare
 * names stay bare, qualified names keep their collection
 * prefix, and internal __join_N aliases never leak.
 *
 * Values decode through MongoTypeMapping using the resolved
 * schema type, preserving M0's exact-only rules (Long to
 * Integer inside the int range, exact Decimal128 to Double,
 * fail-closed otherwise). A missing physical path materializes
 * as null, matching Plug's always-present field model.
 *
 * Driver access sits behind Reader so unit tests can feed
 * result documents without a server; production uses the
 * aggregation cursor.
 */
final class MongoJoinExecutor {

    interface Reader {

        List<Document> aggregate(
                String collection,
                List<Bson> pipeline
        );
    }

    private final Reader reader;
    private final MongoJoinCompiler compiler;

    MongoJoinExecutor(
            MongoDatabase database,
            MongoSchemaResolver resolver
    ) {

        if (database == null) {

            throw new PlugException(
                    "MongoDB database is required."
            );
        }

        this.reader = new DriverReader(database);
        this.compiler = new MongoJoinCompiler(resolver);
    }

    /*
     * Package-private seam for focused tests.
     */
    MongoJoinExecutor(
            Reader reader,
            MongoSchemaResolver resolver
    ) {

        if (reader == null) {

            throw new PlugException(
                    "Join reader is required."
            );
        }

        this.reader = reader;
        this.compiler = new MongoJoinCompiler(resolver);
    }

    QueryResult execute(ExecutionPlan plan) {

        if (plan == null || plan.getQuery() == null) {

            throw new PlugException(
                    "Execution plan and query are required."
            );
        }

        if (!plan.getQuery().getJoins().isEmpty()
                && plan.getAggregate()
                != ExecutionPlan.Aggregate.NONE) {

            throw new PlugException(
                    "An aggregate cannot be combined "
                            + "with join()."
            );
        }

        MongoJoinCompiler.JoinedPlan compiled =
                compiler.compile(plan);

        List<Document> documents;

        try {

            documents = reader.aggregate(
                    compiled.getBaseCollection(),
                    compiled.getPipeline()
            );

        } catch (PlugException e) {

            throw e;

        } catch (RuntimeException e) {

            throw new PlugException(
                    "Cannot read MongoDB collection '"
                            + compiled.getBaseCollection()
                            + "': " + e.getMessage()
            );
        }

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (Document document : documents) {

            Map<String, Object> row =
                    new LinkedHashMap<>();

            for (MongoJoinCompiler.ProjectionField field :
                    compiled.getProjection()) {

                Object value = readPath(
                        document,
                        field.getPath()
                );

                row.put(
                        field.getRequested(),
                        MongoTypeMapping.toPlugValue(
                                field.getRequested(),
                                value,
                                field.getType()
                        )
                );
            }

            rows.add(row);
        }

        return new QueryResult(
                rows.size(),
                rows
        );
    }

    /*
     * Physical path lookup: base fields are top-level, joined
     * fields sit under their internal alias. Missing
     * intermediate documents read as a missing value.
     */
    private static Object readPath(
            Document document,
            String path
    ) {

        Object current = document;

        for (String segment : path.split("\\.")) {

            if (!(current instanceof Document)) {
                return null;
            }

            Document currentDocument =
                    (Document) current;

            if (!currentDocument.containsKey(segment)) {
                return null;
            }

            current = currentDocument.get(segment);
        }

        return current;
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
