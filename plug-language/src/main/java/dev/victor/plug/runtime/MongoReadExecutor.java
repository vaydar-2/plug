package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Executes ordinary Plug reads against MongoDB.
 *
 * MongoQueryCompiler owns filter/projection/sort/limit translation
 * and MongoSchemaResolver owns schema discovery. This class owns the
 * remaining driver mechanics: validating the WHERE clause against the
 * resolved schema, running one find(), and rebuilding rows in the
 * caller's requested field order.
 *
 * MongoDB's limit(0) means "no limit", while Plug's limit(0) means no
 * rows. The compiler makes the filter match nothing; this executor
 * also returns before touching the driver so that contract does not
 * depend on driver handling.
 *
 * Values decode through MongoTypeMapping using the resolved schema
 * type, preserving M0's exact-only rules. Explicit nulls and missing
 * projected fields both materialize as null, matching Plug's
 * always-present field model.
 *
 * Driver access sits behind Reader so unit tests can inspect the
 * compiled query and feed documents without a server.
 */
final class MongoReadExecutor {

    interface Reader {

        List<Document> find(
                String collection,
                MongoQueryCompiler.CompiledQuery query
        );
    }

    private final Reader reader;
    private final MongoSchemaResolver resolver;

    MongoReadExecutor(
            MongoDatabase database,
            MongoSchemaResolver resolver
    ) {

        if (database == null) {

            throw new PlugException(
                    "MongoDB database is required."
            );
        }

        this.reader = new DriverReader(database);
        this.resolver = requireResolver(resolver);
    }

    /*
     * Package-private seam for focused tests.
     */
    MongoReadExecutor(
            Reader reader,
            MongoSchemaResolver resolver
    ) {

        if (reader == null) {

            throw new PlugException(
                    "Read reader is required."
            );
        }

        this.reader = reader;
        this.resolver = requireResolver(resolver);
    }

    QueryResult execute(ExecutionPlan plan) {

        if (plan == null || plan.getQuery() == null) {

            throw new PlugException(
                    "Execution plan and query are required."
            );
        }

        Query query = plan.getQuery();

        if (!query.getJoins().isEmpty()) {

            throw new PlugException(
                    "Read plans cannot contain joins."
            );
        }

        if (plan.getAggregate() != null
                && plan.getAggregate()
                != ExecutionPlan.Aggregate.NONE) {

            throw new PlugException(
                    "Read plans cannot contain aggregates."
            );
        }

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve(plan.getCollection());

        Map<String, Class<?>> schema = resolved.getFields();

        QueryValidation.validateWhere(query, schema);

        MongoQueryCompiler.CompiledQuery compiled =
                MongoQueryCompiler.compileRead(query, schema);

        if (Integer.valueOf(0).equals(compiled.getLimit())) {

            return new QueryResult(
                    0,
                    new ArrayList<>()
            );
        }

        List<Document> documents;

        try {

            documents = reader.find(
                    plan.getCollection(),
                    compiled
            );

        } catch (PlugException e) {

            throw e;

        } catch (RuntimeException e) {

            throw new PlugException(
                    "Cannot read MongoDB collection '"
                            + plan.getCollection()
                            + "': " + e.getMessage()
            );
        }

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (Document document : documents) {

            Map<String, Object> row =
                    new LinkedHashMap<>();

            for (String field : query.getFields()) {

                row.put(
                        field,
                        MongoTypeMapping.toPlugValue(
                                field,
                                document.get(field),
                                schema.get(field)
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

    private static MongoSchemaResolver requireResolver(
            MongoSchemaResolver resolver
    ) {

        if (resolver == null) {

            throw new PlugException(
                    "Schema resolver is required."
            );
        }

        return resolver;
    }

    private static final class DriverReader
            implements Reader {

        private final MongoDatabase database;

        private DriverReader(MongoDatabase database) {
            this.database = database;
        }

        @Override
        public List<Document> find(
                String collection,
                MongoQueryCompiler.CompiledQuery query
        ) {

            FindIterable<Document> iterable =
                    database.getCollection(collection)
                            .find(query.getFilter())
                            .projection(query.getProjection());

            if (query.getSort() != null) {
                iterable = iterable.sort(query.getSort());
            }

            /*
             * limit(0) is already handled before this layer. This
             * guard keeps a defensive zero from becoming Mongo's
             * unbounded limit.
             */
            if (query.getLimit() != null
                    && query.getLimit() > 0) {

                iterable = iterable.limit(query.getLimit());
            }

            return iterable.into(new ArrayList<>());
        }
    }
}
