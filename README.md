# Plug

A database-agnostic query language. Write one script against any database —
the language stays the same, only the adapter driver changes.

```plug
plug.connect(host: "localhost", port: 5432, database: "mydb", username: "victor", password: "secret");
plug.use("users");

plug.get(name, country)
    .where(
        NOT ((country, EQ, "Ghana"))
    );
```

Plug is pure Java (JDK 27+) with zero runtime dependencies. The language is
built here in `plug-language/`; this repository grows into a suite of modules
around it (CLI, GUI, real database adapters, a custom engine, docs, website).

## Repository layout

The full system is organized as independent modules:

| Module                      | Status      | Purpose                                                                 |
|-----------------------------|-------------|-------------------------------------------------------------------------|
| `plug-language/`            | **active**  | Lexer, parser, AST, query engine, runtime, and the shared adapter contract |
| PostgreSQL adapter (inside `runtime/`)   | **active**  | PostgreSQL implementation of the database adapter contract              |
| `plug-cli/`                 | planned     | Terminal application for running `.plug` files, connecting, managing commands |
| `plug-studio/`              | planned     | Future GUI: script editor, syntax highlighting, results, connection management, debugging |
| `plug-adapter-mongodb/`     | planned     | MongoDB implementation of the same contract                              |
| `plugdb/`                   | planned     | Custom database engine that uses Plug as its native query language        |
| `plug-docs/` (or `docs/`)   | planned     | Language reference, tutorials, installation instructions, adapter guides |
| `plug-website/`             | planned     | Public website, downloads, documentation links, project announcements    |

Only `plug-language/` exists today. Every planned module sits either in front
of or on top of the same contract: `DatabaseAdapter` (`connect`, `execute(plan)
→ QueryResult`, `close`), owned by `plug-language` so every backend depends on
that single interface.

## Current progress

Work happens in `plug-language/` — a console launcher
(`dev.victor.plug.Main`) reads a `.plug` file and executes queries against the
bundled in-memory `mock` adapter. Status of the language itself:

| Milestone                    | State         |
|------------------------------|---------------|
| Lexer with line-numbered errors | ✅ done     |
| Parser → AST (statements, chains, condition expressions) | ✅ done |
| Runtime: `connect`, `use`, `close`, `get` | ✅ done |
| Filtering: expression form (`AND`/`OR`/`NOT` tuples) and flat chained form | ✅ done |
| Operators: `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `CONTAINS`, `BETWEEN`, `LIKE`, `IS NULL` / `IS NOT NULL` | ✅ done |
| `.sort(field, ASC/DESC)` and `.limit(n)` | ✅ done |
| Writes: `insert` (auto-increment ids, escaping, nulls), gated `update` / `delete` | ✅ done |
| Aggregates: `count()`, `sum(f)`, `avg(f)`, `min(f)`, `max(f)` | ✅ done |
| Schema/type validation against the collection | ✅ done |
| **Joins**: `.join(collection, on: (left, EQ, right))`, inner-join semantics, dotted `collection.field` names, base-first bare resolution | ✅ done |
| **PostgreSQL adapter** (the first real backend) | ✅ done |
| **PostgreSQL adapter** selections: `driver: "postgres"` / `"postgresql"` alias, schema introspection from `information_schema`, parameterized SQL, gated integration tests | ✅ done |
| MongoDB adapter, PlugDB, other backends | ⬜ next |

Test suite: **132 tests, all green** (JUnit 5, run through the console
standalone launcher; the 3 PostgreSQL integration tests skip silently
unless `PLUG_PG_HOST` points at a reachable server).

### Running the language

```bash
cd plug-language
javac -d target/classes $(find src/main/java -name "*.java")
java -cp target/classes dev.victor.plug.Main examples/test.plug
```

`examples/test.plug` is the original smoke script and
`examples/all-features.plug` exercises every implemented feature end to end
— both are regression gates after any language change.

Errors go to stderr with the process exiting 1. Parse and lexer errors carry
line numbers, e.g. `Error: line 3: Expected a value.`

The complete language reference (statements, operators, values, error
semantics, architecture) lives in [`plug-language/README.md`](plug-language/README.md).

## Architecture

    .plug file → Lexer (+ line numbers) → Parser → AST
               → Runtime → Query → QueryPlanner → ExecutionPlan
               → ExecutionEngine → DatabaseAdapter → QueryResult

- `ExecutionPlan` is a single unit of work: a `READ`, `INSERT`, `UPDATE`, or
  `DELETE` operation over a collection, with query conditions and (for
  writes) field/value data.
- `DatabaseAdapter` is the backend contract. Adapters return structured
  results and never print. New backends register by name in
  `DatabaseAdapterRegistry`.
- The `mock` adapter is the reference implementation for semantics:
  in-memory filtering, sorting, limiting, joins (planned), and schema/type
  validation.

The adapter contract is what makes the module split safe: `plug-cli` only
needs to run scripts through the runtime; the PostgreSQL and MongoDB
adapters only need to translate an `ExecutionPlan` into backend calls;
PlugDB hosts the runtime itself as its query engine.

## Design principles

- **A declarative DSL**, not an SQL lookalike: conditions are `(field, OP, value)`
  tuples, all keywords are chainable method calls, and flat chains evaluate
  left-to-right.
- **Safety by default**: `update`/`delete` refuse to run without a `where()`
  scope; unknown anything (driver, fields, operators, collections) is an
  error, never a guess; condition values check against collection
  schema/type.
- **One language, many backends**: no backend-specific syntax leaks into
  `.plug` scripts; adapters do the translating.
- **Regression-first workflow**: every language change updates the tests,
  the full language reference, and `examples/all-features.plug` — all three
  must stay green.

## Roadmap

1. Joins in the language and the mock adapter — **done**: chained
   `.join(collection, on: (left.qualified, EQ, right.qualified))`,
   inner-join semantics, dotted field qualification, rejections on
   writes and with aggregates.
2. PostgreSQL adapter — **done**: `driver: "postgres"` (alias
   `"postgresql"`), schemas introspected from `information_schema`, all
   queries compile to parameterized SQL by `PostgresQueryCompiler`, with
   shared validation in `QueryValidation` so both adapters enforce the
   identical contract.
3. MongoDB adapter, then PlugDB — a custom engine whose native language
   *is* Plug.
4. `plug-cli` extracts the console launcher out of `plug-language`.
5. `plug-studio` GUI and the public site/website once the language reaches wider
   usefulness.
