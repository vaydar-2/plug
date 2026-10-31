package dev.victor.plug.ast;

import java.util.List;

/*
 * One column of a create.table() definition. DDL is typed
 * end to end: the parser produces FieldDefinitions, the
 * runtime validates them against the language's type and
 * constraint system, and adapters compile them — no raw
 * column text ever reaches the database layer.
 */
public class TableDefinition {

    /*
     * Plug's primitive column types. INT is the language's
     * integer type, DECIMAL its double-precision number,
     * STRING its text, and BOOL its boolean.
     */
    public enum FieldType {
        INT,
        DECIMAL,
        STRING,
        BOOL
    }

    /*
     * Every constraint the language recognizes. Unknown
     * constraint names are rejected at parse time, so an
     * adapter never has to wonder whether a definition
     * carries semantics it chose to ignore.
     */
    public enum FieldConstraint {
        PRIMARY_KEY,
        REQUIRED,
        UNIQUE,
        DEFAULT
    }

    public static class FieldDefinition {

        private final String name;
        private final FieldType type;
        private final List<FieldConstraint> constraints;
        private final Value defaultValue;

        public FieldDefinition(
                String name,
                FieldType type,
                List<FieldConstraint> constraints,
                Value defaultValue
        ) {
            this.name = name;
            this.type = type;
            this.constraints = constraints;
            this.defaultValue = defaultValue;
        }

        public String getName() {
            return name;
        }

        public FieldType getType() {
            return type;
        }

        public List<FieldConstraint> getConstraints() {
            return constraints;
        }

        public boolean hasConstraint(
                FieldConstraint constraint
        ) {
            return constraints.contains(constraint);
        }

        /*
         * Present only when the field carries the DEFAULT
         * constraint; validated to a literal of the field's
         * own type before any adapter sees it.
         */
        public Value getDefaultValue() {
            return defaultValue;
        }

        @Override
        public String toString() {

            return name + ": " + type
                    + constraints + (defaultValue == null
                    ? ""
                    : " default " + defaultValue.getValue());
        }
    }

    private final String name;
    private final List<FieldDefinition> fields;

    public TableDefinition(
            String name,
            List<FieldDefinition> fields
    ) {
        this.name = name;
        this.fields = fields;
    }

    public String getName() {
        return name;
    }

    public List<FieldDefinition> getFields() {
        return fields;
    }

    @Override
    public String toString() {

        return "TableDefinition{" + name
                + ", fields=" + fields + '}';
    }
}
