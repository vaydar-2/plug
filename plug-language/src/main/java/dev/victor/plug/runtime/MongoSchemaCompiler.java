package dev.victor.plug.runtime;

import dev.victor.plug.PlugException;

import dev.victor.plug.ast.TableDefinition;

import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/*
 * Compiles a validated TableDefinition into the MongoDB pieces
 * of a CREATE TABLE: a $jsonSchema validator, and the unique
 * indexes that enforce Plug's PRIMARY_KEY and UNIQUE
 * constraints.
 *
 * Pure and static, like PostgresSchemaCompiler: no client, no
 * server, no state. The compiler validates again so it is safe
 * to call directly, and it never emits a schema MongoDB cannot
 * represent.
 *
 * Mapping decisions:
 * - INT is "int", DECIMAL is "double" (Plug decimals are Java
 *   doubles), STRING is "string", BOOL is "bool".
 * - Optional fields admit null as well, matching Plug's
 *   nullable-by-default contract; REQUIRED and PRIMARY_KEY
 *   fields do not, matching the mock/PostgreSQL NOT NULL rule.
 * - PRIMARY_KEY becomes required + a unique partial index;
 *   UNIQUE becomes a unique partial index. The partial filter
 *   is a $type match, so null and missing values are not
 *   indexed and therefore never collide.
 * - DEFAULT is Plug session metadata only. MongoDB validators
 *   cannot apply defaults, so the compiler emits no "default"
 *   keyword; the declared TableDefinition travels unchanged
 *   into MongoSchemaResolver for the insert milestone.
 * - _id is MongoDB storage detail: it is admitted by the
 *   validator as an ObjectId, and declaring it as a Plug field
 *   is rejected instead of silently colliding with storage.
 */
public final class MongoSchemaCompiler {

    private static final String ID = "_id";

    private MongoSchemaCompiler() {
    }

    /*
     * Everything the adapter needs to create one collection.
     */
    public static final class CompiledCollection {

        private final Document validator;
        private final List<IndexModel> indexes;

        private CompiledCollection(
                Document validator,
                List<IndexModel> indexes
        ) {
            this.validator = validator;
            this.indexes = Collections.unmodifiableList(
                    new ArrayList<>(indexes)
            );
        }

        /*
         * The complete validator document, already wrapped as
         * {"$jsonSchema": {...}} so it can be passed straight to
         * CreateCollectionOptions.validationOptions().
         */
        public Document getValidator() {
            return validator;
        }

        public List<IndexModel> getIndexes() {
            return indexes;
        }
    }

    public static CompiledCollection compileCreateCollection(
            TableDefinition definition
    ) {

        if (definition == null) {

            throw new PlugException(
                    "Table definition is required."
            );
        }

        SchemaValidation.validate(definition);

        Document properties = new Document();

        /*
         * MongoDB adds _id before validating, so it must be an
         * admitted property whenever additionalProperties is
         * false.
         */
        properties.put(
                ID,
                new Document("bsonType", "objectId")
        );

        List<String> required = new ArrayList<>();

        Set<String> uniqueFields = new LinkedHashSet<>();

        for (TableDefinition.FieldDefinition field :
                definition.getFields()) {

            String name = field.getName();

            if (ID.equals(name)) {

                throw new PlugException(
                        "Field '_id' is reserved by MongoDB "
                                + "and cannot be declared in "
                                + "a Plug table."
                );
            }

            boolean notNull = field.hasConstraint(
                    TableDefinition.FieldConstraint.REQUIRED
            ) || field.hasConstraint(
                    TableDefinition.FieldConstraint.PRIMARY_KEY
            );

            if (notNull) {

                required.add(name);
            }

            /*
             * A field carrying both PRIMARY_KEY and UNIQUE
             * still gets exactly one index.
             */
            if (field.hasConstraint(
                    TableDefinition.FieldConstraint.PRIMARY_KEY
            ) || field.hasConstraint(
                    TableDefinition.FieldConstraint.UNIQUE
            )) {

                uniqueFields.add(name);
            }

            properties.put(
                    name,
                    propertySchema(field.getType(), notNull)
            );
        }

        Document jsonSchema = new Document(
                "bsonType",
                "object"
        )
                .append("required", required)
                .append("additionalProperties", false)
                .append("properties", properties);

        List<IndexModel> indexes = new ArrayList<>();

        for (TableDefinition.FieldDefinition field :
                definition.getFields()) {

            if (!uniqueFields.contains(field.getName())) {
                continue;
            }

            indexes.add(
                    uniqueIndex(
                            field.getName(),
                            field.getType()
                    )
            );
        }

        return new CompiledCollection(
                new Document("$jsonSchema", jsonSchema),
                indexes
        );
    }

    /*
     * The $jsonSchema property for one Plug field. A nullable
     * field accepts its BSON type or null; a required field
     * accepts only the BSON type.
     */
    private static Document propertySchema(
            TableDefinition.FieldType type,
            boolean notNull
    ) {

        String bsonType = bsonType(type);

        if (notNull) {
            return new Document("bsonType", bsonType);
        }

        return new Document(
                "bsonType",
                Arrays.asList(bsonType, "null")
        );
    }

    private static String bsonType(
            TableDefinition.FieldType type
    ) {

        switch (type) {

            case INT:
                return "int";

            case DECIMAL:
                return "double";

            case STRING:
                return "string";

            case BOOL:
                return "bool";

            default:
                throw new PlugException(
                        "Unsupported field type: " + type
                );
        }
    }

    /*
     * The BSON types a Plug field can legitimately hold. M0
     * accepts Int64 values inside the int range and Decimal128
     * values that round trip exactly, so the partial index must
     * cover those storage forms as well as the canonical one
     * written by Plug.
     */
    private static Object storageTypes(
            TableDefinition.FieldType type
    ) {

        switch (type) {

            case INT:
                return Arrays.asList("int", "long");

            case DECIMAL:
                return Arrays.asList("double", "decimal");

            case STRING:
                return "string";

            case BOOL:
                return "bool";

            default:
                throw new PlugException(
                        "Unsupported field type: " + type
                );
        }
    }

    /*
     * A unique partial index. The $type filter matches only
     * documents holding a real value of the field's type, so
     * missing fields and explicit nulls are outside the index
     * and may repeat freely.
     */
    private static IndexModel uniqueIndex(
            String field,
            TableDefinition.FieldType type
    ) {

        Document partialFilter = new Document(
                field,
                new Document(
                        "$type",
                        storageTypes(type)
                )
        );

        return new IndexModel(
                Indexes.ascending(field),
                new IndexOptions()
                        .name(field + "_unique")
                        .unique(true)
                        .partialFilterExpression(partialFilter)
        );
    }
}
