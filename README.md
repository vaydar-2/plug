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

Plug is pure Java (compiled for Java 17+ runtimes). The packaged executable bundles
its database drivers — the PostgreSQL JDBC driver and the MongoDB Java sync
driver — plus a Logback SLF4J backend, so scripts need no external classpath. The language is
built here in `plug-language/`; this repository grows into a suite of modules
around it (CLI, GUI, real database adapters, a custom engine, docs, website).

## Repository layout

The full system is organized as independent modules:

| Module                      | Status      | Purpose                                                                 |
|-----------------------------|-------------|-------------------------------------------------------------------------|
| `plug-language/`            | **active**  | Lexer, parser, AST, query engine, runtime, and the shared adapter contract |
| PostgreSQL adapter (inside `runtime/`)   | **active**  | PostgreSQL implementation of the database adapter contract              |
| MongoDB adapter (inside `runtime/`)      | **active**  | Registered `mongo`/`mongodb`: connection lifecycle, schema resolution, `CREATE TABLE`, all reads (plain, joined, aggregates), and INSERT/UPDATE/DELETE; deliberate MongoDB limits are documented below |
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
| Writes: `insert` (auto-increment ids on mock/PostgreSQL, explicit keys on MongoDB; escaping, nulls), gated `update` / `delete` | ✅ done |
| Aggregates: `count()`, `sum(f)`, `avg(f)`, `min(f)`, `max(f)` | ✅ done |
| Schema/type validation against the collection | ✅ done |
| **Joins**: `.join(collection, on: (left, EQ, right))`, inner-join semantics, dotted `collection.field` names, base-first bare resolution | ✅ done |
| **PostgreSQL adapter** (the first real backend) | ✅ done |
| **PostgreSQL adapter** selections: `driver: "postgres"` / `"postgresql"` alias, schema introspection from `information_schema`, parameterized SQL, gated integration tests | ✅ done |
| **DDL**: `plug.create.table(...) { ... }` on both adapters; `plug.create.database(...)` on the mock and PostgreSQL (the MongoDB adapter rejects it) | ✅ done |
| **MongoDB adapter foundation** (M0): sync driver dependency, `MongoTypeMapping` (exact Int64/Decimal128 rules, fail-closed decoding), 22 unit tests | ✅ done |
| **MongoDB adapter connection layer** (M1): `mongo`/`mongodb` registered, explicit `MongoClientSettings`, optional credentials, 3 s timeouts, ping probe, PostgreSQL-style connect errors, `CREATE DATABASE` rejected, query operations rejected until implemented, 11 unit tests | ✅ done |
| **MongoDB query compiler** (M2): explicit-null versus missing filters, all supported operators, LIKE/CONTAINS escaping, nested logic and left-to-right folding, projection/sort/limit, 28 unit tests | ✅ done |
| **MongoDB schema resolution** (M3): session-declared schema over `$jsonSchema` validator over a bounded 100-document sample, exact type mapping, per-collection session caching cleared on close, nullable validator unions, 48 unit tests | ✅ done |
| **MongoDB `CREATE TABLE`** (M4): collection creation with a strict `$jsonSchema` validator, required/nullable fields, unique partial indexes, failure rollback, session schema registration, 36 unit tests | ✅ done |
| **MongoDB writes** (M5): INSERT with declared defaults, explicit nulls, explicit primary keys and duplicate detection; UPDATE/DELETE with compiled WHERE filters, affected/deleted counts, and translated errors, 29 unit tests | ✅ done |
| **MongoDB joins** (M6): inner joins compiled to `$lookup`/`$unwind`, null/missing key guards on both sides, one combined post-join WHERE, alias collision avoidance, projection/sort/limit, 30 unit tests plus a gated live test | ✅ done |
| **MongoDB aggregates** (M7): COUNT/SUM/AVG/MIN/MAX compiled to one WHERE `$match` plus `$group`, SQL empty/null semantics, exact BSON result conversion with explicit overflow failures, 32 unit tests plus a gated live test | ✅ done |
| **MongoDB plain reads** (M8): `MongoReadExecutor` resolves the schema, reuses shared WHERE validation and the M2 compiler, runs `find()` with filter/projection/sort/limit, short-circuits `limit(0)`, and materializes ordered rows with exact BSON conversion; 19 unit tests plus a gated live test | ✅ done |
| **Windows distribution**: self-contained `jpackage` app image and MSI installer with a bundled runtime, custom icon, machine-wide PATH registration, and uninstall cleanup configured | ✅ done |
| PlugDB; other backends | ⬜ next |

Test suite: **453 tests, 0 failures** (JUnit 5). The 5 PostgreSQL
integration tests skip silently unless `PLUG_PG_HOST` points at a reachable
server. The MongoDB read, join, and aggregate integration tests run when
`PLUG_MONGO_HOST` is set (this run executed them against `localhost`; only
the 5 PostgreSQL tests skipped, so 448 executed). Without a MongoDB host,
those three integration tests also skip, leaving 445 executed.

### Running the language

```bash
cd plug-language
javac -d target/classes $(find src/main/java -name "*.java")
java -cp target/classes dev.victor.plug.Main examples/test.plug
```

`examples/test.plug` is the original smoke script and
`examples/all-features.plug` exercises every implemented feature end to end
— both are regression gates after any language change.
`examples/mongo.plug` is the MongoDB smoke script: it connects to
`localhost:27017`, creates two collections with validators and unique
indexes, exercises INSERT/UPDATE/DELETE, prints an ordinary filtered read,
prints an inner join, and runs all five aggregates.

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
  in-memory filtering, sorting, limiting, nested-loop joins, and schema/type
  validation.

The adapter contract is what makes the module split safe: `plug-cli` only
needs to run scripts through the runtime; the PostgreSQL and MongoDB
adapters only need to translate an `ExecutionPlan` into backend calls;
PlugDB hosts the runtime itself as its query engine.

`MongoTypeMapping` is the MongoDB adapter's pure BSON↔Plug conversion layer
(M0): exact `Int64`/`Decimal128` rules, unsupported values omitted, and
fail-closed decoding. `MongoDatabaseAdapter` provides the registered
`mongo`/`mongodb` connection lifecycle, `MongoQueryCompiler` (M2)
compiles validated reads into null-aware filters plus projection, sort,
and limit documents, `MongoSchemaResolver` (M3) resolves collection
schemas from a session declaration, the `$jsonSchema` validator, or a
bounded 100-document sample, `MongoSchemaWriter` (M4) creates collections
with generated validators and unique partial indexes, and
`MongoWriteExecutor` (M5) runs INSERT/UPDATE/DELETE. M6 adds
`MongoJoinCompiler`/`MongoJoinExecutor`: inner joins become
`$lookup`/`$unwind` with null/missing key guards, followed by one
combined WHERE, projection, sort, and limit. M7 adds
`MongoAggregateCompiler`/`MongoAggregateExecutor`: COUNT/SUM/AVG/MIN/MAX
become one WHERE `$match` followed by `$group`, with SQL-compatible
empty and null results and exact BSON decoding. M8 adds
`MongoReadExecutor`: ordinary reads resolve the schema, reuse shared
WHERE validation, run `find()` with the compiled filter, projection,
sort, and limit, and materialize ordered rows with exact BSON decoding.

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

## Command-line interface

The console launcher graduated into a CLI inside `plug-language`
(`dev.victor.plug.cli`): `plug run <file.plug>` executes a script,
`plug check <file.plug>` validates it statically (lex + parse, no
database interaction), plus `--version`/`--help` — with documented
exit codes (0 success, 1 script/file failure, 2 usage error) and
stdout/stderr separation. The legacy bare `plug <file.plug>`
invocation via `dev.victor.plug.Main` still works unchanged. `mvn
package` produces the runnable executable `target/plug.jar` with the
PostgreSQL JDBC driver, MongoDB Java sync driver, and Logback SLF4J
provider bundled (the JDBC and SLF4J service entries are preserved);
logging defaults to warnings/errors on stderr, so driver INFO/DEBUG
output does not clutter script output.

Windows distribution is available as a self-contained app image
(`dist/plug/plug.exe`) and an MSI (`dist-installer/Plug-1.0.msi`),
both built with JDK 21 `jpackage` and a bundled runtime. The MSI
installs under `C:\Program Files\Plug` and adds that directory to
the machine-wide PATH through a customized WiX component; its
template preserves a stable component GUID for upgrades and uninstall.
Prerequisites, commands, outputs, and PATH behavior are documented in
`plug-language/README.md` (the interactive REPL is explicitly deferred).

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
3. MongoDB adapter — **done through M8**: M0 added the sync driver and
   exact BSON↔Plug type mapping (`MongoTypeMapping`); M1 added the
   registered `mongo`/`mongodb` connection lifecycle; M2 added the pure
   `MongoQueryCompiler` (operators, explicit-null/missing semantics,
   projection, sort, limit, left-to-right folding); M3 added
   `MongoSchemaResolver` (session declaration, `$jsonSchema` validator,
   bounded sampling, caching); M4 added `CREATE TABLE` (validator and
   unique partial indexes, failure rollback, schema registration); M5
   added INSERT/UPDATE/DELETE (`MongoWriteExecutor`) with declared
   defaults, explicit nulls, compiled WHERE filters, counts, and
   translated errors; M6 added joined reads
   (`MongoJoinCompiler`/`MongoJoinExecutor`) with `$lookup`/`$unwind`,
   both-side null/missing key guards, a combined post-join filter, and
   projection/sort/limit; M7 added COUNT/SUM/AVG/MIN/MAX
   (`MongoAggregateCompiler`/`MongoAggregateExecutor`) with one WHERE
   `$match`, `$group`, SQL empty/null semantics, and exact numeric
   results; and M8 added ordinary reads through `MongoReadExecutor`,
   with shared validation, `find()` filter/projection/sort/limit, and
   exact ordered materialization. MongoDB's explicit-primary-key
   requirement, rejected `CREATE DATABASE`, and session-scoped defaults
   are documented adapter limitations. Then PlugDB — a custom engine
   whose native language *is* Plug.
4. `plug-cli` extracts the console launcher out of `plug-language` —
   the CLI commands themselves are done and live in
   `dev.victor.plug.cli`; the module split is still open.
5. `plug-studio` GUI and the public site/website once the language reaches wider
   usefulness.
