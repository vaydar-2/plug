# Plug

A database-agnostic query language. Write one script against any database:
the language stays the same, only the adapter driver changes.

```plug
plug.connect(host: "localhost", port: 5432, database: "mydb", username: "victor", password: "secret");
plug.use("users");

plug.get(name, country)
    .where(
        NOT ((country, EQ, "Ghana"))
    );
```

Plug is pure Java (JDK 27+) with zero runtime dependencies. A console
launcher reads a `.plug` file, and a pluggable adapter layer executes the
compiled query plan. The bundled `mock` adapter runs against in-memory
sample data; new backends register themselves by name — the planned order
is PostgreSQL, MongoDB, then PlugDB (a custom database using Plug as its
native query language).

## Running

Compile (requires JDK 27+; any build runner works, e.g. Maven `mvn compile`):

```bash
javac -d target/classes $(find src/main/java -name "*.java")
java -cp target/classes dev.victor.plug.Main examples/test.plug
```

Output:

```
Mock database connected:
Host: localhost
Port: 5432
Database: mydb
Username: victor
Connection successful.
Using collection: users
Executing plan: ExecutionPlan{...}
Records found: 9
{country=Nigeria, name=Michael}
...
```

Errors go to stderr and the process exits with code 1. Parse and lexer
errors include line numbers, e.g. `Error: line 3: Expected a value.`

## Language reference

### Statements

Every statement is a chain of calls ending in `;`. A chain starts with `plug.`
or a query call, and each further call joins with `.`.

### plug.connect(...) — open a connection

Named arguments only:

| Argument    | Required   | Notes                                    |
|-------------|------------|------------------------------------------|
| `driver:`   | optional   | adapter name, defaults to `"mock"`        |
| `host:`     | required   | string                                    |
| `port:`     | optional   | number, defaults to `5432`                |
| `database:` | required   | string                                    |
| `username:` | required   | string                                    |
| `password:` | required   | string                                    |

```plug
plug.connect(driver: "mock", host: "localhost", port: 5432, database: "mydb", username: "victor", password: "secret");
```

An unknown driver fails with the list of registered drivers, e.g.
`Unknown driver: postgres. Available drivers: mock`.

### plug.use(...) — select a collection

```plug
plug.use("users");
```

### plug.close() — close the connection

```plug
plug.close();
```

After closing, further queries fail until you connect again. Programs also
close their connection automatically when they end (even on error).

### plug.get(...) — start a query (projection)

At least one field name:

```plug
plug.get(name, country)
```

### .where(...) — filter

Two representations exist by design:

1. **Expression form** — conditions built from grouping, logical keywords,
   and comparison tuples. Use this for anything with `AND`, `OR`, or `NOT`:

   ```plug
   plug.get(name)
       .where((age, GT, 18) AND ((country, EQ, "Ghana") OR (role, EQ, "student")));

   plug.get(name)
       .where(NOT ((country, EQ, "Ghana")));
   ```

   Comparison tuples are `(field, OPERATOR, value)`. Keywords work in any
   case (`AND`, `and`).

2. **Flat chained form** — unparenthesized arguments, one call per hop.
   Chains evaluate strictly left-to-right:

   ```plug
   plug.get(name, age)
       .where((country, EQ, "Ghana"))
       .and(active, EQ, true)
       .or(role, EQ, "developer");
   ```

   (A single-hop `.where((field, OP, value))` also accepts the expression
   form; both paths are checked against the collection schema.)

When an expression-form `where` is combined with chained `.and()`/`.or()`
calls, the expression result seeds the chain and each chained condition
folds in with its own operator, left-to-right:
`.where(x).or(y).and(z)` means `(x OR y) AND z`.

### .sort(field, DIRECTION)

```plug
.sort(age, DESC)
```

Direction is `ASC` or `DESC`. The field must be sortable in the schema.

### .limit(n)

```plug
.limit(5)
```

Whole numbers only; a decimal literal is rejected.

### Operators

| Operator  | Meaning                              |
|-----------|--------------------------------------|
| `EQ`      | equality (matches `null` too)        |
| `NE`      | inequality                           |
| `GT` `GTE` `LT` `LTE` | numeric comparison       |
| `IN`      | membership in a list                 |
| `CONTAINS`| substring match on two strings       |

```plug
.where((country, IN, ["Kenya", "Nigeria"]))
.where((email, EQ, null))
.where((name, CONTAINS, "li"));
```

### Values

- Strings in `"double quotes"` with escapes: `\n`, `\t`, `\"`, `\\`.
- Numbers: integers (`20`, `-7`) and decimals (`3.14`).
- Booleans: `true`, `false`. Null: `null`.
- Lists: `[1, 2, 3]`.
- Bare identifiers name fields.

### Comments

```plug
# a comment to end of line
// also a comment to end of line
```

## Errors

All failures surface as `PlugException`:

- Lex and parse errors carry line numbers (`line 3: Expected a value.`).
- Unknown fields, operators, or collections are runtime errors.
- Condition values are validated against the collection schema on both
  where-paths — a string compared to an integer field fails type checking.

## Architecture

    .plug file → Lexer (+ line numbers) → Parser → AST
               → Runtime → Query → QueryPlanner → ExecutionPlan
               → ExecutionEngine → DatabaseAdapter → QueryResult

- `DatabaseAdapter` is the backend contract: `connect`, `execute(plan) →
  QueryResult`, `close`. Adapters return structured results and never print.
- `DatabaseAdapterRegistry` maps driver names to adapter factories.
- The mock adapter implements in-memory filtering, sorting, limiting, and
  schema/type validation; it is the reference implementation for semantics.

## Roadmap

1. Complete the prototype language: insert/update/delete, aggregates
   (count/sum/avg/min/max), joins, and more operators (BETWEEN, LIKE,
   IS NULL).
2. Real adapters, in order: PostgreSQL, MongoDB.
3. PlugDB — a custom database using Plug as its query language.
4. Adapters for more database categories.
