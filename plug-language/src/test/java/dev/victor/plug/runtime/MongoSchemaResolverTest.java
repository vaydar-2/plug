package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;
import dev.victor.plug.ast.Value;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * MongoSchemaResolver unit tests.
 *
 * Resolution runs against a focused collection-source double,
 * so precedence, validator parsing, bounded sampling and
 * caching are all covered without a MongoDB server. The only
 * untested seam is the thin driver-backed source.
 */
public class MongoSchemaResolverTest {

    /* ---------------- test double ---------------- */

    private static final class FakeSource
            implements MongoSchemaResolver.CollectionSource {

        private final Map<String, List<Document>> metadata =
                new LinkedHashMap<>();

        private final Map<String, List<Document>> documents =
                new LinkedHashMap<>();

        private int metadataCalls;
        private int sampleCalls;
        private int lastLimit = -1;

        private RuntimeException metadataFailure;
        private RuntimeException sampleFailure;

        private void putMetadata(
                String collection,
                Document... entries
        ) {
            metadata.put(
                    collection,
                    new ArrayList<>(Arrays.asList(entries))
            );
        }

        private void putDocuments(
                String collection,
                Document... samples
        ) {
            documents.put(
                    collection,
                    new ArrayList<>(Arrays.asList(samples))
            );
        }

        @Override
        public List<Document> collectionMetadata(
                String collection
        ) {

            metadataCalls++;

            if (metadataFailure != null) {
                throw metadataFailure;
            }

            return metadata.getOrDefault(
                    collection,
                    Collections.emptyList()
            );
        }

        @Override
        public List<Document> sample(
                String collection,
                int limit
        ) {

            sampleCalls++;
            lastLimit = limit;

            if (sampleFailure != null) {
                throw sampleFailure;
            }

            return documents.getOrDefault(
                    collection,
                    Collections.emptyList()
            );
        }
    }

    /* ---------------- builders ---------------- */

    private static Document metadata(
            String collection,
            Document validator
    ) {

        Document options = new Document();

        if (validator != null) {
            options.put("validator", validator);
        }

        return new Document("name", collection)
                .append("type", "collection")
                .append("options", options);
    }

    private static Document metadataWithValidator(
            String collection,
            Document jsonSchema
    ) {

        return metadata(
                collection,
                new Document("$jsonSchema", jsonSchema)
        );
    }

    private static Document schema(Document properties) {

        return new Document("bsonType", "object")
                .append("properties", properties);
    }

    private static Document typed(String bsonType) {

        return new Document("bsonType", bsonType);
    }

    private static Document typed(List<String> bsonTypes) {

        return new Document("bsonType", bsonTypes);
    }

    private static TableDefinition.FieldDefinition field(
            String name,
            TableDefinition.FieldType type,
            TableDefinition.FieldConstraint... constraints
    ) {

        return new TableDefinition.FieldDefinition(
                name,
                type,
                Arrays.asList(constraints),
                null
        );
    }

    private static TableDefinition definition(
            String name,
            TableDefinition.FieldDefinition... fields
    ) {

        return new TableDefinition(
                name,
                Arrays.asList(fields)
        );
    }

    private static MongoSchemaResolver resolver(
            FakeSource source
    ) {
        return new MongoSchemaResolver(source);
    }

    private static void assertFields(
            MongoSchemaResolver.ResolvedSchema resolved,
            Object... pairs
    ) {

        Map<String, Class<?>> expected =
                new LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {

            expected.put(
                    (String) pairs[i],
                    (Class<?>) pairs[i + 1]
            );
        }

        assertEquals(expected, resolved.getFields());
    }

    /* ---------------- precedence ---------------- */

    @Test
    void sessionDeclaredSchemaOverridesValidatorAndSampling() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(new Document("name", typed("int")))
                )
        );

        source.putDocuments(
                "people",
                new Document("name", 7)
        );

        MongoSchemaResolver resolver = resolver(source);

        resolver.declare(
                definition(
                        "people",
                        field(
                                "name",
                                TableDefinition.FieldType.STRING,
                                TableDefinition.FieldConstraint.REQUIRED
                        )
                )
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                resolved.getOrigin()
        );

        assertFields(resolved, "name", String.class);

        assertEquals(
                Collections.singleton("name"),
                resolved.getRequiredFields()
        );

        assertNotNull(resolved.getDefinition());

        assertEquals(0, source.metadataCalls);
        assertEquals(0, source.sampleCalls);
    }

    @Test
    void validatorOverridesSampling() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(new Document("name", typed("string")))
                )
        );

        source.putDocuments(
                "people",
                new Document("name", "Alex")
                        .append("age", 17)
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.VALIDATOR,
                resolved.getOrigin()
        );

        assertFields(resolved, "name", String.class);

        assertEquals(0, source.sampleCalls);
    }

    @Test
    void samplingRunsOnlyWithoutHigherPrioritySchema() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("name", "Alex")
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.SAMPLE,
                resolved.getOrigin()
        );

        assertEquals(1, source.metadataCalls);
        assertEquals(1, source.sampleCalls);
    }

    @Test
    void validatorDefinedSchemaDoesNotAbsorbSampledFields() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(new Document("name", typed("string")))
                )
        );

        source.putDocuments(
                "people",
                new Document("name", "Alex")
                        .append("age", 17)
                        .append("active", true)
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertFields(resolved, "name", String.class);
    }

    @Test
    void declaredSchemaDoesNotAbsorbOtherFields() {

        FakeSource source = new FakeSource();

        source.putDocuments(
                "people",
                new Document("name", "Alex")
                        .append("age", 17)
        );

        MongoSchemaResolver resolver = resolver(source);

        resolver.declare(
                definition(
                        "people",
                        field("name", TableDefinition.FieldType.STRING)
                )
        );

        assertFields(
                resolver.resolve("people"),
                "name",
                String.class
        );
    }

    /* ---------------- validator resolution ---------------- */

    @Test
    void validatorMapsSupportedBsonTypes() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document("id", typed("int"))
                                        .append("visits", typed("long"))
                                        .append("score", typed("double"))
                                        .append("balance", typed("decimal"))
                                        .append("name", typed("string"))
                                        .append("active", typed("bool"))
                        )
                )
        );

        assertFields(
                resolver(source).resolve("people"),
                "id", Integer.class,
                "visits", Integer.class,
                "score", Double.class,
                "balance", Double.class,
                "name", String.class,
                "active", Boolean.class
        );
    }

    @Test
    void numericBsonTypeUnionsWidenWithoutCoercion() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document(
                                        "score",
                                        typed(Arrays.asList(
                                                "int",
                                                "double"
                                        ))
                                ).append(
                                        "count",
                                        typed(Arrays.asList(
                                                "int",
                                                "long"
                                        ))
                                )
                        )
                )
        );

        assertFields(
                resolver(source).resolve("people"),
                "score", Double.class,
                "count", Integer.class
        );
    }

    @Test
    void nullableDeclarationsResolveToTheirNonNullType() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document(
                                        "age",
                                        typed(Arrays.asList(
                                                "int",
                                                "null"
                                        ))
                                ).append(
                                        "name",
                                        typed(Arrays.asList(
                                                "string",
                                                "null"
                                        ))
                                )
                        )
                )
        );

        assertFields(
                resolver(source).resolve("people"),
                "age", Integer.class,
                "name", String.class
        );
    }

    @Test
    void nullOnlyDeclarationStaysUnknown() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document(
                                        "nothing",
                                        typed(Collections.singletonList("null"))
                                ).append(
                                        "name",
                                        typed("string")
                                )
                        )
                )
        );

        assertFields(
                resolver(source).resolve("people"),
                "name", String.class
        );
    }

    @Test
    void unsupportedValidatorTypesAreOmitted() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document("address", typed("object"))
                                        .append("tags", typed("array"))
                                        .append("created", typed("date"))
                                        .append("key", typed("objectId"))
                                        .append("nothing", typed("null"))
                                        .append(
                                                "mixed",
                                                typed(Arrays.asList(
                                                        "string",
                                                        "int"
                                                ))
                                        )
                                        .append(
                                                "name",
                                                typed("string")
                                        )
                        )
                )
        );

        assertFields(
                resolver(source).resolve("people"),
                "name",
                String.class
        );
    }

    @Test
    void requiredFieldsAreRecoveredForTypedFieldsOnly() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document("name", typed("string"))
                                        .append("age", typed("int"))
                        ).append(
                                "required",
                                Arrays.asList(
                                        "name",
                                        "age",
                                        "missing"
                                )
                        )
                )
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(
                new java.util.LinkedHashSet<>(
                        Arrays.asList("name", "age")
                ),
                resolved.getRequiredFields()
        );

        assertFalse(resolved.getFields().containsKey("missing"));
    }

    @Test
    void idIsNotExposedAsAPlugField() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document("_id", typed("objectId"))
                                        .append("name", typed("string"))
                        ).append("required", Collections.singletonList("_id"))
                )
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertFields(resolved, "name", String.class);
        assertTrue(resolved.getRequiredFields().isEmpty());
    }

    @Test
    void validatorWithoutPropertiesIsAnEmptyAuthoritativeSchema() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        new Document("bsonType", "object")
                )
        );

        source.putDocuments(
                "people",
                new Document("name", "Alex")
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.VALIDATOR,
                resolved.getOrigin()
        );

        assertTrue(resolved.getFields().isEmpty());
        assertEquals(0, source.sampleCalls);
    }

    @Test
    void propertyWithoutBsonTypeStaysUnknown() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document("name", new Document())
                                        .append("age", typed("int"))
                        )
                )
        );

        assertFields(
                resolver(source).resolve("people"),
                "age",
                Integer.class
        );
    }

    @Test
    void validatorWithoutJsonSchemaFallsBackToSampling() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadata(
                        "people",
                        new Document(
                                "name",
                                new Document("$exists", true)
                        )
                )
        );

        source.putDocuments(
                "people",
                new Document("name", "Alex")
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.SAMPLE,
                resolved.getOrigin()
        );

        assertFields(resolved, "name", String.class);
    }

    @Test
    void malformedValidatorIsRejected() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadata("people", new Document("$jsonSchema", "nope"))
        );

        PlugException error = assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );

        assertTrue(error.getMessage().startsWith(
                "Malformed $jsonSchema validator for collection 'people'"
        ));

        assertEquals(0, source.sampleCalls);
    }

    @Test
    void nonDocumentValidatorIsRejected() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadata("people", new Document("$jsonSchema", 1))
        );

        assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );
    }

    @Test
    void malformedPropertiesAreRejected() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        new Document(
                                "properties",
                                "not-a-document"
                        )
                )
        );

        assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );
    }

    @Test
    void nonDocumentPropertySpecIsRejected() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        new Document(
                                "properties",
                                new Document("name", "string")
                        )
                )
        );

        assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );
    }

    @Test
    void malformedRequiredIsRejected() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        new Document(
                                "properties",
                                new Document("name", typed("string"))
                        ).append("required", "name")
                )
        );

        assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );
    }

    @Test
    void nonStringRequiredEntryIsRejected() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        new Document(
                                "properties",
                                new Document("name", typed("string"))
                        ).append("required", Collections.singletonList(1))
                )
        );

        assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );
    }

    @Test
    void topLevelBsonTypeMustBeObject() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        new Document("bsonType", "string")
                )
        );

        assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );
    }

    @Test
    void malformedBsonTypeIsRejected() {

        FakeSource source = new FakeSource();

        source.putMetadata(
                "people",
                metadataWithValidator(
                        "people",
                        schema(
                                new Document(
                                        "name",
                                        new Document("bsonType", 7)
                                )
                        )
                )
        );

        assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );
    }

    /* ---------------- sampling ---------------- */

    @Test
    void samplingUsesTheFixedBoundedLimit() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));
        source.putDocuments("people", new Document("name", "Alex"));

        resolver(source).resolve("people");

        assertEquals(100, MongoSchemaResolver.SAMPLE_SIZE);
        assertEquals(100, source.lastLimit);
    }

    @Test
    void samplingReadsAtMostOneHundredDocuments() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < 150; i++) {
            documents.add(
                    new Document(
                            "field" + i,
                            "value"
                    )
            );
        }

        source.putDocuments(
                "people",
                documents.toArray(new Document[0])
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(100, resolved.getFields().size());
        assertTrue(resolved.getFields().containsKey("field0"));
        assertTrue(resolved.getFields().containsKey("field99"));
        assertFalse(resolved.getFields().containsKey("field100"));
    }

    @Test
    void emptyCollectionYieldsEmptySchema() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.SAMPLE,
                resolved.getOrigin()
        );

        assertTrue(resolved.getFields().isEmpty());
        assertFalse(resolved.isAuthoritative());
    }

    @Test
    void consistentSampleTypesAreInferred() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("name", "Alex")
                        .append("age", 17)
                        .append("active", true)
                        .append("score", 1.5),
                new Document("name", "Sam")
                        .append("age", 21)
                        .append("active", false)
                        .append("score", 2.5)
        );

        assertFields(
                resolver(source).resolve("people"),
                "name", String.class,
                "age", Integer.class,
                "active", Boolean.class,
                "score", Double.class
        );
    }

    @Test
    void mixedSampleTypesAreOmitted() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("name", "Alex")
                        .append("age", 17)
                        .append("note", "text"),
                new Document("name", 5)
                        .append("age", 21)
                        .append("note", true)
        );

        assertFields(
                resolver(source).resolve("people"),
                "age", Integer.class
        );
    }

    @Test
    void fieldsMissingFromSomeDocumentsAreStillInferred() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("name", "Alex"),
                new Document("name", "Sam")
                        .append("age", 21)
        );

        assertFields(
                resolver(source).resolve("people"),
                "name", String.class,
                "age", Integer.class
        );
    }

    @Test
    void unsupportedBsonSampleTypesAreOmitted() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("address", new Document("city", "Accra"))
                        .append("key", new ObjectId())
                        .append("created", new Date())
                        .append("tags", Arrays.asList("a", "b"))
                        .append("name", "Alex")
        );

        assertFields(
                resolver(source).resolve("people"),
                "name", String.class
        );
    }

    @Test
    void sampleNumericTypesRespectMongoTypeMapping() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("small", 5L)
                        .append("wide", 4_000_000_000L)
                        .append("tooWide", 9_007_199_254_740_993L)
                        .append("exactDecimal", Decimal128.parse("1.5"))
                        .append("inexactDecimal", Decimal128.parse("0.1"))
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver(source).resolve("people");

        assertEquals(Integer.class, resolved.getFields().get("small"));
        assertEquals(Double.class, resolved.getFields().get("wide"));
        assertEquals(Double.class, resolved.getFields().get("exactDecimal"));
        assertFalse(resolved.getFields().containsKey("tooWide"));
        assertFalse(resolved.getFields().containsKey("inexactDecimal"));
    }

    @Test
    void fieldsWithOnlyNullValuesAreOmitted() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("name", null),
                new Document("name", null)
        );

        assertTrue(
                resolver(source).resolve("people")
                        .getFields()
                        .isEmpty()
        );
    }

    @Test
    void idIsExcludedFromSampling() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.putDocuments(
                "people",
                new Document("_id", new ObjectId())
                        .append("name", "Alex")
        );

        assertFields(
                resolver(source).resolve("people"),
                "name", String.class
        );
    }

    /* ---------------- caching and lifecycle ---------------- */

    @Test
    void repeatedResolutionDoesNotResample() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));
        source.putDocuments("people", new Document("name", "Alex"));

        MongoSchemaResolver resolver = resolver(source);

        resolver.resolve("people");
        resolver.resolve("people");
        resolver.resolve("people");

        assertEquals(1, source.metadataCalls);
        assertEquals(1, source.sampleCalls);
    }

    @Test
    void collectionsHaveIndependentCachedSchemas() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));
        source.putMetadata("orders", metadata("orders", null));

        source.putDocuments(
                "people",
                new Document("name", "Alex")
        );

        source.putDocuments(
                "orders",
                new Document("total", 12.5)
        );

        MongoSchemaResolver resolver = resolver(source);

        assertFields(
                resolver.resolve("people"),
                "name", String.class
        );

        assertFields(
                resolver.resolve("orders"),
                "total", Double.class
        );

        assertFields(
                resolver.resolve("people"),
                "name", String.class
        );
    }

    @Test
    void declarationReplacesAnInferredSchema() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));
        source.putDocuments("people", new Document("name", "Alex"));

        MongoSchemaResolver resolver = resolver(source);

        assertEquals(
                MongoSchemaResolver.Origin.SAMPLE,
                resolver.resolve("people").getOrigin()
        );

        resolver.declare(
                definition(
                        "people",
                        field("age", TableDefinition.FieldType.INT)
                )
        );

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve("people");

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                resolved.getOrigin()
        );

        assertFields(resolved, "age", Integer.class);
        assertEquals(1, source.sampleCalls);
    }

    @Test
    void clearDropsDeclaredAndCachedSchemas() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));
        source.putDocuments("people", new Document("name", "Alex"));

        MongoSchemaResolver resolver = resolver(source);

        resolver.resolve("people");

        resolver.declare(
                definition(
                        "strict",
                        field("code", TableDefinition.FieldType.STRING)
                )
        );

        resolver.clear();

        assertThrows(
                PlugException.class,
                () -> resolver.resolve("strict")
        );

        assertEquals(
                MongoSchemaResolver.Origin.SAMPLE,
                resolver.resolve("people").getOrigin()
        );

        assertEquals(2, source.sampleCalls);
    }

    @Test
    void separateResolversDoNotShareState() {

        FakeSource firstSource = new FakeSource();
        firstSource.putMetadata("people", metadata("people", null));

        FakeSource secondSource = new FakeSource();

        MongoSchemaResolver first = resolver(firstSource);
        MongoSchemaResolver second = resolver(secondSource);

        first.declare(
                definition(
                        "people",
                        field("name", TableDefinition.FieldType.STRING)
                )
        );

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                first.resolve("people").getOrigin()
        );

        assertEquals("Unknown collection: people",
                assertThrows(
                        PlugException.class,
                        () -> second.resolve("people")
                ).getMessage());
    }

    @Test
    void closingAdapterClearsSessionState() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        MongoDatabaseAdapter adapter = new MongoDatabaseAdapter();

        MongoSchemaResolver resolver = resolver(source);

        resolver.declare(
                definition(
                        "people",
                        field("name", TableDefinition.FieldType.STRING)
                )
        );

        adapter.schemaResolver = resolver;

        adapter.close();

        assertNull(adapter.schemaResolver);
        assertEquals(
                MongoSchemaResolver.Origin.SAMPLE,
                resolver.resolve("people").getOrigin()
        );
    }

    @Test
    void separateAdapterInstancesDoNotShareSchemaState() {

        MongoDatabaseAdapter first = new MongoDatabaseAdapter();
        MongoDatabaseAdapter second = new MongoDatabaseAdapter();

        first.schemaResolver = resolver(new FakeSource());

        first.schemaResolver.declare(
                definition(
                        "people",
                        field("name", TableDefinition.FieldType.STRING)
                )
        );

        assertNull(second.schemaResolver);

        assertEquals(
                MongoSchemaResolver.Origin.SESSION,
                first.schemaResolver.resolve("people").getOrigin()
        );
    }

    /* ---------------- errors ---------------- */

    @Test
    void unknownCollectionIsReported() {

        FakeSource source = new FakeSource();

        PlugException error = assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );

        assertEquals("Unknown collection: people", error.getMessage());
        assertEquals(0, source.sampleCalls);
    }

    @Test
    void blankCollectionNameIsRejected() {

        FakeSource source = new FakeSource();

        MongoSchemaResolver resolver = resolver(source);

        assertThrows(
                PlugException.class,
                () -> resolver.resolve(null)
        );

        assertThrows(
                PlugException.class,
                () -> resolver.resolve("  ")
        );
    }

    @Test
    void connectivityErrorsAreTranslated() {

        FakeSource source = new FakeSource();

        source.metadataFailure =
                new RuntimeException("connection refused");

        PlugException error = assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );

        assertEquals(
                "Cannot inspect MongoDB collection 'people': "
                        + "connection refused",
                error.getMessage()
        );
    }

    @Test
    void samplingErrorsAreTranslated() {

        FakeSource source = new FakeSource();

        source.putMetadata("people", metadata("people", null));

        source.sampleFailure =
                new RuntimeException("cursor timed out");

        PlugException error = assertThrows(
                PlugException.class,
                () -> resolver(source).resolve("people")
        );

        assertEquals(
                "Cannot read MongoDB collection 'people': "
                        + "cursor timed out",
                error.getMessage()
        );
    }

    @Test
    void nullDatabaseIsRejected() {

        assertThrows(
                PlugException.class,
                () -> new MongoSchemaResolver(
                        (com.mongodb.client.MongoDatabase) null
                )
        );
    }

    @Test
    void nullDeclarationIsRejected() {

        assertThrows(
                PlugException.class,
                () -> resolver(new FakeSource()).declare(null)
        );
    }

    @Test
    void invalidDeclarationIsRejected() {

        MongoSchemaResolver resolver = resolver(new FakeSource());

        assertThrows(
                PlugException.class,
                () -> resolver.declare(
                        definition(
                                "People",
                                field(
                                        "name",
                                        TableDefinition.FieldType.STRING
                                )
                        )
                )
        );
    }

    @Test
    void declaredDefaultsAndConstraintsAreKeptOnTheDefinition() {

        TableDefinition.FieldDefinition id =
                new TableDefinition.FieldDefinition(
                        "id",
                        TableDefinition.FieldType.INT,
                        Arrays.asList(
                                TableDefinition.FieldConstraint.PRIMARY_KEY
                        ),
                        null
                );

        TableDefinition.FieldDefinition name =
                new TableDefinition.FieldDefinition(
                        "name",
                        TableDefinition.FieldType.STRING,
                        Arrays.asList(
                                TableDefinition.FieldConstraint.REQUIRED,
                                TableDefinition.FieldConstraint.DEFAULT
                        ),
                        new Value(Value.Type.STRING, "unknown")
                );

        MongoSchemaResolver resolver = resolver(new FakeSource());

        resolver.declare(definition("people", id, name));

        MongoSchemaResolver.ResolvedSchema resolved =
                resolver.resolve("people");

        assertNotNull(resolved.getDefinition());
        assertEquals(
                "unknown",
                resolved.getDefinition()
                        .getFields()
                        .get(1)
                        .getDefaultValue()
                        .getValue()
        );

        assertEquals(
                new java.util.LinkedHashSet<>(
                        Arrays.asList("id", "name")
                ),
                resolved.getRequiredFields()
        );
    }
}
