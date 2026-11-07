package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import com.mongodb.client.MongoDatabase;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Resolves the effective Plug schema of a MongoDB collection.
 *
 * MongoDB collections are schemaless, so Plug needs a
 * deterministic answer to "which fields does this collection
 * have, and what are their types?" before any query can be
 * validated. Resolution is layered, strongest first:
 *
 *   1. A TableDefinition declared in this session. It is
 *      authoritative and is never merged with anything else.
 *   2. The collection's $jsonSchema validator. When present it
 *      is treated as the complete declared schema; sampling is
 *      not used to extend it.
 *   3. A bounded sample of SAMPLE_SIZE documents. Best-effort
 *      only: fields that cannot be typed exactly are omitted,
 *      never guessed.
 *
 * Every layer keeps unknown fields unknown, so the shared
 * validation contract ("Unknown field: x") still applies
 * downstream. Raw driver exceptions are translated and never
 * escape this class.
 *
 * The resolver is session state: one instance per adapter
 * connection. It is not thread-safe, and clear() drops both
 * declared and cached schemas.
 */
public class MongoSchemaResolver {

    /*
     * Fixed for this milestone: schema inference reads at most
     * this many documents. A full collection scan is never
     * performed.
     */
    public static final int SAMPLE_SIZE = 100;

    /*
     * Where a ResolvedSchema came from. SAMPLE is the only
     * non-authoritative origin; it exists only because no
     * declaration was available.
     */
    public enum Origin {
        SESSION,
        VALIDATOR,
        SAMPLE
    }

    /*
     * The effective Plug schema of one collection: the field ->
     * Java type map used by validation and query compilation,
     * plus the resolution metadata callers may need.
     */
    public static final class ResolvedSchema {

        private final String collection;
        private final Origin origin;
        private final Map<String, Class<?>> fields;
        private final Set<String> requiredFields;
        private final TableDefinition definition;

        private ResolvedSchema(
                String collection,
                Origin origin,
                Map<String, Class<?>> fields,
                Set<String> requiredFields,
                TableDefinition definition
        ) {
            this.collection = collection;
            this.origin = origin;
            this.fields = Collections.unmodifiableMap(
                    new LinkedHashMap<>(fields)
            );
            this.requiredFields = Collections.unmodifiableSet(
                    new LinkedHashSet<>(requiredFields)
            );
            this.definition = definition;
        }

        public String getCollection() {
            return collection;
        }

        public Origin getOrigin() {
            return origin;
        }

        public Map<String, Class<?>> getFields() {
            return fields;
        }

        /*
         * Fields the schema declares as non-nullable (a Plug
         * REQUIRED/PRIMARY_KEY constraint, or a $jsonSchema
         * "required" entry for a field we could type). Sampling
         * never produces required fields.
         */
        public Set<String> getRequiredFields() {
            return requiredFields;
        }

        /*
         * Session-declared definition, including constraints and
         * defaults; null for validator- and sample-derived
         * schemas, which carry no Plug DDL.
         */
        public TableDefinition getDefinition() {
            return definition;
        }

        public boolean isAuthoritative() {
            return origin != Origin.SAMPLE;
        }

        @Override
        public String toString() {

            return "ResolvedSchema{" + collection
                    + ", origin=" + origin
                    + ", fields=" + fields + '}';
        }
    }

    /*
     * The only MongoDB surface the resolver needs. Production
     * uses the driver-backed implementation; tests supply a
     * focused double, so ordinary unit tests never need a live
     * server.
     */
    interface CollectionSource {

        /*
         * Full listCollections metadata for the named collection;
         * empty when the collection does not exist.
         */
        List<Document> collectionMetadata(String collection);

        /*
         * At most limit documents, without their _id field.
         */
        List<Document> sample(String collection, int limit);
    }

    private final CollectionSource source;

    /*
     * Session-declared schemas. Authoritative and never merged.
     */
    private final Map<String, TableDefinition> declared =
            new LinkedHashMap<>();

    /*
     * Validator- and sample-derived schemas, so one collection
     * is inspected once per session.
     */
    private final Map<String, ResolvedSchema> cache =
            new LinkedHashMap<>();

    public MongoSchemaResolver(MongoDatabase database) {

        if (database == null) {
            throw new PlugException(
                    "MongoDB database is required."
            );
        }

        this.source = new DriverSource(database);
    }

    /*
     * Package-private seam for focused tests.
     */
    MongoSchemaResolver(CollectionSource source) {

        if (source == null) {
            throw new PlugException(
                    "Collection source is required."
            );
        }

        this.source = source;
    }

    /*
     * Registers a session-declared schema. Declaration is
     * validated by the same shared rules as every other schema
     * operation, and any inferred schema for the collection is
     * dropped so stale samples can never win over a new
     * declaration.
     */
    public void declare(TableDefinition definition) {

        if (definition == null) {
            throw new PlugException(
                    "Table definition is required."
            );
        }

        SchemaValidation.validate(definition);

        declared.put(definition.getName(), definition);
        cache.remove(definition.getName());
    }

    /*
     * Returns the collection's effective schema, resolving it on
     * first use. Declared schemas are rebuilt per call (cheap,
     * and always reflects the current session definition);
     * validator and sampled schemas are cached.
     */
    public ResolvedSchema resolve(String collection) {

        requireCollectionName(collection);

        TableDefinition definition = declared.get(collection);

        if (definition != null) {
            return fromDeclaration(collection, definition);
        }

        ResolvedSchema cached = cache.get(collection);

        if (cached != null) {
            return cached;
        }

        ResolvedSchema resolved = load(collection);

        cache.put(collection, resolved);

        return resolved;
    }

    /*
     * Drops all session state. Adapters call this on close so no
     * cache can leak into a later connection.
     */
    public void clear() {

        declared.clear();
        cache.clear();
    }

    private ResolvedSchema load(String collection) {

        List<Document> metadata =
                metadataFor(collection);

        Document entry = null;

        for (Document candidate : metadata) {

            if (collection.equals(
                    candidate.getString("name")
            )) {

                entry = candidate;
                break;
            }
        }

        if (entry == null) {

            throw new PlugException(
                    "Unknown collection: " + collection
            );
        }

        Document jsonSchema =
                jsonSchemaOf(entry, collection);

        if (jsonSchema != null) {
            return fromValidator(collection, jsonSchema);
        }

        return fromSample(collection);
    }

    /*
     * Extracts the $jsonSchema declaration from collection
     * metadata. A validator that is not a document, or a
     * $jsonSchema that is not a document, is malformed: it must
     * not silently turn into an empty or sampled schema. A
     * validator without $jsonSchema (a query-expression
     * validator) declares no field types, so sampling is the
     * only available best effort.
     */
    private static Document jsonSchemaOf(
            Document metadata,
            String collection
    ) {

        Object options = metadata.get("options");

        if (options == null) {
            return null;
        }

        if (!(options instanceof Document)) {

            throw malformed(
                    collection,
                    "collection options must be a document"
            );
        }

        Object validator = ((Document) options).get("validator");

        if (validator == null) {
            return null;
        }

        if (!(validator instanceof Document)) {

            throw malformed(
                    collection,
                    "validator must be a document"
            );
        }

        Object jsonSchema = ((Document) validator).get("$jsonSchema");

        if (jsonSchema == null) {
            return null;
        }

        if (!(jsonSchema instanceof Document)) {

            throw malformed(
                    collection,
                    "$jsonSchema must be a document"
            );
        }

        return (Document) jsonSchema;
    }

    private static ResolvedSchema fromValidator(
            String collection,
            Document jsonSchema
    ) {

        requireObjectSchema(jsonSchema, collection);

        Map<String, Class<?>> fields =
                new LinkedHashMap<>();

        Object properties = jsonSchema.get("properties");

        if (properties != null) {

            if (!(properties instanceof Document)) {

                throw malformed(
                        collection,
                        "properties must be a document"
                );
            }

            for (Map.Entry<String, Object> property :
                    ((Document) properties).entrySet()) {

                String name = property.getKey();

                /*
                 * _id is MongoDB storage detail. Plug reads
                 * always exclude it, so exposing it as a field
                 * would allow queries no execution path can
                 * honour.
                 */
                if ("_id".equals(name)) {
                    continue;
                }

                Class<?> type = validatorType(
                        name,
                        property.getValue(),
                        collection
                );

                if (type != null) {
                    fields.put(name, type);
                }
            }
        }

        return new ResolvedSchema(
                collection,
                Origin.VALIDATOR,
                fields,
                requiredFields(jsonSchema, fields, collection),
                null
        );
    }

    private static void requireObjectSchema(
            Document jsonSchema,
            String collection
    ) {

        Object bsonType = jsonSchema.get("bsonType");

        if (bsonType == null) {
            return;
        }

        List<String> types =
                bsonTypeList(bsonType, collection);

        if (!types.contains("object")) {

            throw malformed(
                    collection,
                    "top-level bsonType must be \"object\""
            );
        }
    }

    /*
     * One $jsonSchema property. Missing or unsupported bsonType
     * leaves the field unknown instead of guessing; a bsonType
     * of the wrong shape is malformed.
     */
    private static Class<?> validatorType(
            String field,
            Object spec,
            String collection
    ) {

        if (!(spec instanceof Document)) {

            throw malformed(
                    collection,
                    "property '" + field + "' must be a document"
            );
        }

        Object bsonType = ((Document) spec).get("bsonType");

        if (bsonType == null) {
            return null;
        }

        List<String> types =
                bsonTypeList(bsonType, collection);

        /*
         * A nullable declaration (for example ["int", "null"])
         * still has one Plug type; null is a value state, not a
         * type. A declaration of only null stays unknown.
         */
        types.removeIf("null"::equals);

        if (types.isEmpty()) {
            return null;
        }

        return MongoTypeMapping.declaredType(types);
    }

    private static List<String> bsonTypeList(
            Object bsonType,
            String collection
    ) {

        if (bsonType instanceof String) {

            List<String> types = new ArrayList<>();
            types.add((String) bsonType);

            return types;
        }

        if (bsonType instanceof List) {

            List<String> types = new ArrayList<>();

            for (Object member : (List<?>) bsonType) {

                if (!(member instanceof String)) {

                    throw malformed(
                            collection,
                            "bsonType entries must be strings"
                    );
                }

                types.add((String) member);
            }

            return types;
        }

        throw malformed(
                collection,
                "bsonType must be a string or an array of strings"
        );
    }

    /*
     * Required entries are honoured only for fields the
     * validator actually types; an untyped required field stays
     * unknown rather than being assigned a guessed type.
     */
    private static Set<String> requiredFields(
            Document jsonSchema,
            Map<String, Class<?>> fields,
            String collection
    ) {

        Set<String> required = new LinkedHashSet<>();

        Object value = jsonSchema.get("required");

        if (value == null) {
            return required;
        }

        if (!(value instanceof List)) {

            throw malformed(
                    collection,
                    "required must be an array of field names"
            );
        }

        for (Object member : (List<?>) value) {

            if (!(member instanceof String)) {

                throw malformed(
                        collection,
                        "required entries must be strings"
                );
            }

            if (fields.containsKey(member)) {
                required.add((String) member);
            }
        }

        return required;
    }

    /*
     * Bounded inference. Documents are read in natural order up
     * to SAMPLE_SIZE; each field keeps every value seen, and
     * MongoTypeMapping decides whether those values share one
     * exact Plug type. Nulls are ignored by the mapping, mixed
     * types and unsupported BSON simply leave the field out.
     */
    private ResolvedSchema fromSample(String collection) {

        List<Document> documents =
                sampleOf(collection);

        Map<String, List<Object>> observed =
                new LinkedHashMap<>();

        int accepted = 0;

        for (Document document : documents) {

            if (document == null) {
                continue;
            }

            if (accepted++ >= SAMPLE_SIZE) {
                break;
            }

            for (String field : document.keySet()) {

                if ("_id".equals(field)) {
                    continue;
                }

                observed.computeIfAbsent(
                        field,
                        name -> new ArrayList<>()
                ).add(document.get(field));
            }
        }

        Map<String, Class<?>> fields =
                new LinkedHashMap<>();

        for (Map.Entry<String, List<Object>> field :
                observed.entrySet()) {

            Class<?> type = MongoTypeMapping.inferType(
                    field.getValue()
            );

            if (type != null) {
                fields.put(field.getKey(), type);
            }
        }

        return new ResolvedSchema(
                collection,
                Origin.SAMPLE,
                fields,
                Collections.emptySet(),
                null
        );
    }

    /*
     * Session schema -> Plug map. Constraints and defaults stay
     * on the TableDefinition itself; nothing is sampled or
     * merged in alongside them.
     */
    private static ResolvedSchema fromDeclaration(
            String collection,
            TableDefinition definition
    ) {

        Map<String, Class<?>> fields =
                new LinkedHashMap<>();

        Set<String> required =
                new LinkedHashSet<>();

        for (TableDefinition.FieldDefinition field :
                definition.getFields()) {

            fields.put(
                    field.getName(),
                    plugType(field.getType())
            );

            if (field.hasConstraint(
                    TableDefinition.FieldConstraint.REQUIRED
            ) || field.hasConstraint(
                    TableDefinition.FieldConstraint.PRIMARY_KEY
            )) {

                required.add(field.getName());
            }
        }

        return new ResolvedSchema(
                collection,
                Origin.SESSION,
                fields,
                required,
                definition
        );
    }

    private static Class<?> plugType(
            TableDefinition.FieldType type
    ) {

        switch (type) {

            case INT:
                return Integer.class;

            case DECIMAL:
                return Double.class;

            case STRING:
                return String.class;

            case BOOL:
                return Boolean.class;

            default:
                throw new PlugException(
                        "Unsupported field type: " + type
                );
        }
    }

    private List<Document> metadataFor(String collection) {

        try {

            List<Document> metadata =
                    source.collectionMetadata(collection);

            return metadata == null
                    ? Collections.emptyList()
                    : metadata;

        } catch (PlugException e) {

            throw e;

        } catch (RuntimeException e) {

            throw new PlugException(
                    "Cannot inspect MongoDB collection '"
                            + collection + "': "
                            + e.getMessage()
            );
        }
    }

    private List<Document> sampleOf(String collection) {

        try {

            List<Document> documents =
                    source.sample(collection, SAMPLE_SIZE);

            return documents == null
                    ? Collections.emptyList()
                    : documents;

        } catch (PlugException e) {

            throw e;

        } catch (RuntimeException e) {

            throw new PlugException(
                    "Cannot read MongoDB collection '"
                            + collection + "': "
                            + e.getMessage()
            );
        }
    }

    private static void requireCollectionName(String collection) {

        if (collection == null || collection.isBlank()) {

            throw new PlugException(
                    "Collection name is required."
            );
        }
    }

    private static PlugException malformed(
            String collection,
            String detail
    ) {

        return new PlugException(
                "Malformed $jsonSchema validator for collection '"
                        + collection + "': " + detail + "."
        );
    }

    /*
     * Driver-backed source. listCollections() returns full
     * metadata (not nameOnly), which is where the validator
     * lives; sampling projects _id out server-side and caps the
     * cursor at the fixed sample size.
     */
    private static final class DriverSource
            implements CollectionSource {

        private final MongoDatabase database;

        private DriverSource(MongoDatabase database) {
            this.database = database;
        }

        @Override
        public List<Document> collectionMetadata(
                String collection
        ) {

            return database.listCollections()
                    .filter(new Document("name", collection))
                    .into(new ArrayList<>());
        }

        @Override
        public List<Document> sample(
                String collection,
                int limit
        ) {

            return database.getCollection(collection)
                    .find()
                    .projection(new Document("_id", 0))
                    .limit(limit)
                    .into(new ArrayList<>());
        }
    }
}
