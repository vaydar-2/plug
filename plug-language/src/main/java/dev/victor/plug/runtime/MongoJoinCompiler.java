package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Compiles a joined Plug read into a MongoDB aggregation
 * pipeline.
 *
 * Plug's join model is an inner join, so each joined collection
 * becomes a $lookup followed by an $unwind: an empty lookup
 * result disappears exactly like a non-matching SQL row.
 * $lookup's own null/missing behaviour is not Plug's, so both
 * key sides are guarded inside the lookup pipeline before the
 * equality test.
 *
 * Joined collections are nested under collision-resistant
 * internal aliases (__join_0, __join_1, ...) instead of being
 * flattened under dotted keys. Dotted keys would make MongoDB
 * read them as nested paths, and flattening would risk
 * overwriting user fields. Every logical field is mapped to
 * the physical aggregation path instead:
 *
 *   users.id    -> id
 *   orders.total -> __join_0.total
 *
 * All $lookup/$unwind stages run before the single combined
 * $match, so AND/OR/NOT see complete joined rows and no
 * predicate is pushed into a lookup. Projection, sort and limit
 * follow, with any sort key retained by the projection so
 * Mongo's own ordering stays possible; qualified output names
 * are restored during client-side materialization because a
 * MongoDB document cannot hold a literal dotted field name.
 *
 * Pure translation: no client or server access. Validation is
 * shared with the mock and PostgreSQL adapters.
 */
final class MongoJoinCompiler {

    /*
     * One requested output field: the user's spelling plus the
     * physical path and resolved Plug type used to decode it.
     */
    static final class ProjectionField {

        private final String requested;
        private final String path;
        private final Class<?> type;

        private ProjectionField(
                String requested,
                String path,
                Class<?> type
        ) {
            this.requested = requested;
            this.path = path;
            this.type = type;
        }

        String getRequested() {
            return requested;
        }

        String getPath() {
            return path;
        }

        Class<?> getType() {
            return type;
        }
    }

    /*
     * A complete joined-read plan: the base collection to
     * aggregate, the ordered stages, and how to turn each
     * result document back into Plug's field names.
     */
    static final class JoinedPlan {

        private final String baseCollection;
        private final List<Bson> pipeline;
        private final List<ProjectionField> projection;

        private JoinedPlan(
                String baseCollection,
                List<Bson> pipeline,
                List<ProjectionField> projection
        ) {
            this.baseCollection = baseCollection;
            this.pipeline = Collections.unmodifiableList(
                    new ArrayList<>(pipeline)
            );
            this.projection = Collections.unmodifiableList(
                    new ArrayList<>(projection)
            );
        }

        String getBaseCollection() {
            return baseCollection;
        }

        List<Bson> getPipeline() {
            return pipeline;
        }

        List<ProjectionField> getProjection() {
            return projection;
        }
    }

    private final MongoSchemaResolver resolver;

    MongoJoinCompiler(MongoSchemaResolver resolver) {

        if (resolver == null) {
            throw new PlugException(
                    "Schema resolver is required."
            );
        }

        this.resolver = resolver;
    }

    JoinedPlan compile(ExecutionPlan plan) {

        if (plan == null || plan.getQuery() == null) {

            throw new PlugException(
                    "Execution plan and query are required."
            );
        }

        String baseCollection = plan.getCollection();

        if (baseCollection == null
                || baseCollection.isBlank()) {

            throw new PlugException(
                    "Collection name is required."
            );
        }

        Query query = plan.getQuery();

        /*
         * Schemas in join order: base first, then every joined
         * collection. Later joins and conditions may reference
         * any earlier collection.
         */
        LinkedHashMap<String, Map<String, Class<?>>> schemas =
                new LinkedHashMap<>();

        schemas.put(
                baseCollection,
                resolver.resolve(baseCollection).getFields()
        );

        for (Join join : query.getJoins()) {

            if (schemas.containsKey(join.getCollection())) {

                throw new PlugException(
                        "Duplicate collection in join: "
                                + join.getCollection()
                );
            }

            schemas.put(
                    join.getCollection(),
                    resolver.resolve(join.getCollection())
                            .getFields()
            );
        }

        for (Join join : query.getJoins()) {
            QueryValidation.validateJoin(join, schemas);
        }

        for (Condition condition : query.getConditions()) {

            QueryValidation.validateResolvedCondition(
                    condition,
                    schemas,
                    baseCollection
            );
        }

        if (query.getExpression() != null) {

            QueryValidation.validateResolvedExpression(
                    query.getExpression(),
                    schemas,
                    baseCollection
            );
        }

        Sort sort = query.getSort();

        String sortPath = null;

        if (sort != null) {

            String resolved = QueryValidation.resolveField(
                    sort.getField(),
                    schemas,
                    baseCollection
            );

            Class<?> sortType = schemas
                    .get(QueryValidation.collectionOf(resolved))
                    .get(QueryValidation.bareName(resolved));

            if (!Comparable.class.isAssignableFrom(sortType)) {

                throw new PlugException(
                        "Field '" + sort.getField()
                                + "' cannot be sorted."
                );
            }
        }

        List<String> aliases = aliases(schemas, query.getJoins());

        Map<String, String> prefixes =
                new LinkedHashMap<>();

        prefixes.put(baseCollection, "");

        for (int i = 0; i < query.getJoins().size(); i++) {

            prefixes.put(
                    query.getJoins().get(i).getCollection(),
                    aliases.get(i) + "."
            );
        }

        List<ProjectionField> projection =
                projection(
                        query.getFields(),
                        schemas,
                        baseCollection,
                        prefixes
                );

        if (sort != null) {
            sortPath = path(
                    QueryValidation.resolveField(
                            sort.getField(),
                            schemas,
                            baseCollection
                    ),
                    prefixes
            );
        }

        FieldPaths paths = new FieldPaths(
                schemas,
                prefixes,
                baseCollection
        );

        Document filter = MongoQueryCompiler.compileFilter(
                query,
                paths
        );

        List<Bson> pipeline = new ArrayList<>();

        for (int i = 0; i < query.getJoins().size(); i++) {

            Join join = query.getJoins().get(i);

            pipeline.add(
                    lookup(
                            join,
                            aliases.get(i),
                            prefixes
                    )
            );

            pipeline.add(
                    new Document(
                            "$unwind",
                            "$" + aliases.get(i)
                    )
            );
        }

        /*
         * One combined predicate after every join. Pushing a
         * condition into a lookup would make AND/OR/NOT see
         * partially joined rows and can change the result.
         */
        pipeline.add(new Document("$match", filter));

        Integer limit = query.getLimit();

        if (Integer.valueOf(0).equals(limit)) {

            /*
             * Aggregation $limit must be positive. An always-
             * false match keeps limit(0)'s no-rows contract.
             */
            pipeline.add(
                    new Document(
                            "$match",
                            new Document(
                                    "_id",
                                    new Document(
                                            "$in",
                                            Collections.emptyList()
                                    )
                            )
                    )
            );
        }

        Document project = new Document();

        Set<String> projectedPaths = new LinkedHashSet<>();

        for (ProjectionField field : projection) {
            projectedPaths.add(field.getPath());
        }

        if (sortPath != null) {
            projectedPaths.add(sortPath);
        }

        for (String path : projectedPaths) {
            project.put(path, 1);
        }

        project.put("_id", 0);

        pipeline.add(new Document("$project", project));

        if (sort != null) {

            pipeline.add(
                    new Document(
                            "$sort",
                            new Document(
                                    sortPath,
                                    "DESC".equals(sort.getDirection())
                                            ? -1
                                            : 1
                            )
                    )
            );
        }

        if (limit != null && limit > 0) {

            pipeline.add(
                    new Document("$limit", limit)
            );
        }

        return new JoinedPlan(
                baseCollection,
                pipeline,
                projection
        );
    }

    /*
     * $lookup pipeline form. Guards run against the local key
     * and the foreign key before equality, because a bare
     * lookup would match null and missing keys in ways Plug
     * never does. $unwind then turns the array into inner-join
     * rows.
     */
    private static Document lookup(
            Join join,
            String alias,
            Map<String, String> prefixes
    ) {

        boolean joinedIsLeft = QueryValidation
                .collectionOf(join.getLeftField())
                .equals(join.getCollection());

        String joinedField = joinedIsLeft
                ? join.getLeftField()
                : join.getRightField();

        String existingField = joinedIsLeft
                ? join.getRightField()
                : join.getLeftField();

        String foreignPath = QueryValidation.bareName(
                joinedField
        );

        String localPath = path(existingField, prefixes);

        Document localGuard = new Document(
                "$and",
                Arrays.asList(
                        new Document(
                                "$ne",
                                Arrays.asList("$$localKey", null)
                        ),
                        new Document(
                                "$ne",
                                Arrays.asList(
                                        new Document(
                                                "$type",
                                                "$$localKey"
                                        ),
                                        "missing"
                                )
                        )
                )
        );

        Document foreignGuard = new Document(
                "$and",
                Arrays.asList(
                        new Document(
                                "$ne",
                                Arrays.asList(
                                        "$" + foreignPath,
                                        null
                                )
                        ),
                        new Document(
                                "$ne",
                                Arrays.asList(
                                        new Document(
                                                "$type",
                                                "$" + foreignPath
                                        ),
                                        "missing"
                                )
                        )
                )
        );

        Document expression = new Document(
                "$and",
                Arrays.asList(
                        localGuard,
                        foreignGuard,
                        new Document(
                                "$eq",
                                Arrays.asList(
                                        "$$localKey",
                                        "$" + foreignPath
                                )
                        )
                )
        );

        return new Document(
                "$lookup",
                new Document("from", join.getCollection())
                        .append(
                                "let",
                                new Document(
                                        "localKey",
                                        "$" + localPath
                                )
                        )
                        .append(
                                "pipeline",
                                Collections.singletonList(
                                        new Document(
                                                "$match",
                                                new Document(
                                                        "$expr",
                                                        expression
                                                )
                                        )
                                )
                        )
                        .append("as", alias)
        );
    }

    /*
     * Internal aliases never reuse a user field name. Reserved
     * names are collected from every participating schema so a
     * base or joined field such as "__join_0" cannot be
     * overwritten by a lookup.
     */
    private static List<String> aliases(
            Map<String, Map<String, Class<?>>> schemas,
            List<Join> joins
    ) {

        Set<String> reserved = new LinkedHashSet<>();

        for (Map<String, Class<?>> schema : schemas.values()) {
            reserved.addAll(schema.keySet());
        }

        List<String> aliases = new ArrayList<>();

        for (int i = 0; i < joins.size(); i++) {

            String candidate = "__join_" + i;

            int suffix = 1;

            while (reserved.contains(candidate)) {
                candidate = "__join_" + i + "_" + suffix++;
            }

            reserved.add(candidate);
            aliases.add(candidate);
        }

        return aliases;
    }

    /*
     * Joined projection follows the mock/PostgreSQL rule:
     * qualified names are exact, bare names prefer the base
     * collection and otherwise take the first joined collection
     * that defines them. Conditions are stricter and reject
     * ambiguity, but projection is first-write-wins.
     */
    private static List<ProjectionField> projection(
            List<String> fields,
            Map<String, Map<String, Class<?>>> schemas,
            String baseCollection,
            Map<String, String> prefixes
    ) {

        if (fields.isEmpty()) {

            throw new PlugException(
                    "get() expects at least one field."
            );
        }

        List<ProjectionField> projection = new ArrayList<>();

        for (String field : fields) {

            if (field.contains(".")) {

                String collection =
                        QueryValidation.collectionOf(field);

                String bare =
                        QueryValidation.bareName(field);

                Map<String, Class<?>> schema =
                        schemas.get(collection);

                if (schema == null
                        || !schema.containsKey(bare)) {

                    throw new PlugException(
                            "Unknown field: " + field
                    );
                }

                projection.add(
                        new ProjectionField(
                                field,
                                path(field, prefixes),
                                schema.get(bare)
                        )
                );

                continue;
            }

            Map<String, Class<?>> baseSchema =
                    schemas.get(baseCollection);

            if (baseSchema.containsKey(field)) {

                projection.add(
                        new ProjectionField(
                                field,
                                field,
                                baseSchema.get(field)
                        )
                );

                continue;
            }

            boolean resolved = false;

            for (Map.Entry<String, Map<String, Class<?>>> entry :
                    schemas.entrySet()) {

                if (entry.getKey().equals(baseCollection)) {
                    continue;
                }

                if (entry.getValue().containsKey(field)) {

                    projection.add(
                            new ProjectionField(
                                    field,
                                    prefixes.get(entry.getKey())
                                            + field,
                                    entry.getValue().get(field)
                            )
                    );

                    resolved = true;
                    break;
                }
            }

            if (!resolved) {

                throw new PlugException(
                        "Unknown field: " + field
                );
            }
        }

        return projection;
    }

    private static String path(
            String qualifiedField,
            Map<String, String> prefixes
    ) {

        String collection =
                QueryValidation.collectionOf(qualifiedField);

        String bare =
                QueryValidation.bareName(qualifiedField);

        String prefix = prefixes.get(collection);

        if (prefix == null) {

            throw new PlugException(
                    "Unknown collection in field: "
                            + qualifiedField
            );
        }

        return prefix + bare;
    }

    /*
     * Adapter used by the shared filter compiler: resolve the
     * logical name exactly like conditions do (strict, with
     * ambiguity errors), then map it to its aggregation path.
     */
    private static final class FieldPaths
            implements MongoQueryCompiler.FieldPathResolver {

        private final Map<String, Map<String, Class<?>>> schemas;
        private final Map<String, String> prefixes;
        private final String baseCollection;

        private FieldPaths(
                Map<String, Map<String, Class<?>>> schemas,
                Map<String, String> prefixes,
                String baseCollection
        ) {
            this.schemas = schemas;
            this.prefixes = prefixes;
            this.baseCollection = baseCollection;
        }

        @Override
        public String path(String field) {

            return MongoJoinCompiler.path(
                    QueryValidation.resolveField(
                            field,
                            schemas,
                            baseCollection
                    ),
                    prefixes
            );
        }
    }
}
