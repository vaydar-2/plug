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

Plug is pure Java (compiled for Java 17+ runtimes). The packaged executable bundles
its database drivers (PostgreSQL JDBC and the MongoDB Java sync driver) plus a
Logback SLF4J backend, so scripts need no external classpath. A console
launcher reads a `.plug` file, and a pluggable adapter layer executes the
compiled query plan. The bundled `mock` adapter runs against in-memory
sample data; backends register themselves by name — the PostgreSQL adapter
is already included (`driver: "postgres"`), and MongoDB is registered as
`mongo`/`mongodb` with its connection lifecycle, schema resolver,
`CREATE TABLE`, INSERT/UPDATE/DELETE, and all read shapes (ordinary,
joined, and aggregates) in place. PlugDB (a custom database using Plug
as its native query language) is next in line.

## Running

Compile (requires a JDK 17+; any build runner works, e.g. Maven `mvn compile` —
the build targets `--release 17`, so the packaged jar runs on any
Java 17+ runtime):

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

## Command-line interface

The `plug` CLI wraps the same pipeline behind four commands:

```bash
# legacy entry point (unchanged):
java -cp target/classes dev.victor.plug.Main examples/test.plug

# with the CLI:
java -cp target/classes dev.victor.plug.cli.CliMain run examples/test.plug
```

After packaging (below) the commands are simply
`java -jar plug.jar run <file.plug>` — or `plug ...` once installed
through jpackage.

| Command                 | Behavior |
|-------------------------|----------|
| `plug run <file.plug>`   | Executes a script (same output as before); exactly one file argument |
| `plug check <file.plug>` | Syntax check — lexing + parsing only, nothing executes; prints `ok: <file>` on success |
| `plug --version`         | CLI and language versions (build-derived) |
| `plug --help`            | Usage information |

The CLI does argument dispatch, file reading, and exit codes only —
script handling is delegated to the existing `Lexer`, `Parser`, and
`Runtime`; no language behavior is re-implemented or shadowed. It is
kept separate from the language parser (`dev.victor.plug.cli` vs.
`lexer`/`parser`).

Notes:

- Paths may be relative or absolute and may contain spaces — quote
  paths the way you would with any Unix-style tool and they reach the
  CLI intact (`plug run "my script.plug"`). `run` and `check` accept
  exactly one file; missing or unexpected extra arguments are rejected
  with a usage error.
- Exit codes: `0` success, `1` file-not-found / parse / runtime
  failure, `2` usage error (unknown command, missing or extra
  arguments). Results go to stdout, errors to stderr.
- `check` is a **syntax** check — lexing and parsing only (including
  DDL definitions). It does not touch a database and does not validate
  database schemas, driver availability, or runtime behavior: those
  only exist (and fail) at execution time. It never opens a
  connection, so a fully valid syntax verdict says nothing about
  whether a database or driver actually works.
- The first line of any failure is the exception's own message (with
  its line number when the language provides one); exceptions without
  a message fall back to the exception type. Stack traces are not
  part of the ordinary CLI output.
- The interactive REPL is explicitly deferred; `plug repl` says so and
  exits with a usage error.
- `dev.victor.plug.Main` remains the entry point for manual classpath
  runs and now delegates to the CLI, so the original bare
  `plug <file.plug>` invocation keeps working.

### Packaging and distribution

`mvn package` produces the runnable executable `target/plug.jar` with
the PostgreSQL JDBC driver, the MongoDB Java sync driver, and the
Logback SLF4J provider inside. The jar replaces the plain artifact (the
main class is `dev.victor.plug.cli.CliMain`), and the services transformer
keeps both the JDBC driver's `META-INF/services/java.sql.Driver`
registration and the SLF4J provider registration intact, so
`DriverManager` and `LoggerFactory` discover them at runtime. The MongoDB
driver is bundled for the registered `mongo`/`mongodb` adapters:

```bash
mvn package
java -jar target/plug.jar run examples/test.plug
java -jar target/plug.jar --help
java -jar target/plug.jar --version
java -jar target/plug.jar check examples/create-table.plug
```

`src/main/resources/logback.xml` sets the CLI logging defaults: warnings
and errors go to stderr, while MongoDB driver INFO/DEBUG output (cluster
topology, connection pool, command progress) is suppressed. Script output
on stdout is unaffected, and Logback is the only SLF4J provider bundled.

### Windows distribution

Plug also has a self-contained Windows app image and MSI installer. Both
include a private Java runtime, so users do not need Java installed to run
the packaged application.

The current artifacts are:

| Artifact | Purpose |
|----------|---------|
| `dist/plug/plug.exe` | Self-contained app image: `plug.exe`, `app/`, and `runtime/` |
| `dist-installer/Plug-1.0.msi` | Installer that places Plug in `C:\Program Files\Plug` |

#### Prerequisites

- JDK 21, which provides `jpackage`.
- WiX Toolset 3.14.1, with `candle.exe` and `light.exe` on `PATH`.
  This JDK 21 packaging workflow uses WiX 3; WiX 7 is not used.
- A freshly built shaded jar.

#### Build the app image and MSI

From `plug-language`, stage the jar and build the app image:

```powershell
mvn clean package
New-Item -ItemType Directory -Force .\dist-input | Out-Null
Copy-Item .\target\plug.jar .\dist-input\plug.jar -Force

jpackage `
  --type app-image `
  --name plug `
  --input .\dist-input `
  --main-jar plug.jar `
  --main-class dev.victor.plug.cli.CliMain `
  --dest .\dist `
  --icon (Resolve-Path .\assets\plug.ico).Path `
  --win-console
```

Build the MSI with the same staged jar. The install name is capitalized:

```powershell
jpackage `
  --type msi `
  --name Plug `
  --input .\dist-input `
  --main-jar plug.jar `
  --main-class dev.victor.plug.cli.CliMain `
  --dest .\dist-installer `
  --icon (Resolve-Path .\assets\plug.ico).Path `
  --resource-dir .\jpackage-resources `
  --win-console `
  --verbose
```

The app image runs without installation:

```powershell
.\dist\plug\plug.exe --version
```

After installing the MSI, `plug --version` works from any directory. The
current local installer is `Plug-1.0.msi`; the CLI continues to report the
Maven build version (`1.0-SNAPSHOT`).

#### PATH registration

`jpackage-resources/main.wxs` customizes the WiX source generated by
`jpackage`. It adds a `PlugPathEnvironment` component to `DefaultFeature`
that:

- Appends `[INSTALLDIR]` to the machine-wide `PATH`.
- Uses `HKLM\Software\Plug\PathInstalled` as the component key path.
- Uses `Permanent="no"`, allowing Windows Installer to remove the entry on
  uninstall.

`Environment` is the standard WiX element; this template does not use
`util:Environment`. The component GUID is:

```text
{4a3ee4e0-b922-48c1-b3c8-4f2d5e272956}
```

**Preserve this GUID across installer versions.** It identifies the PATH
component during upgrades and uninstall; generating a new GUID casually can
leave stale or duplicated PATH entries.

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
`Unknown driver: sqlite. Available drivers: mock, postgres, postgresql, mongo, mongodb`.

### plug.use(...) — select a collection

```plug
plug.use("users");
```

### plug.create.table(...) — define a table

DDL rides the same connection context as CRUD (a connection is required;
no collection is). The block defines columns with types and constraints;
unknown types, unknown constraints, duplicate fields, and duplicate
constraints are parse errors, and identifiers must be lowercase, digit,
underscore names:

```plug
plug.create.table("products") {
    id: INT primary_key,
    name: STRING required,
    category: STRING unique,
    price: DECIMAL default 9.99,
    active: BOOL default true
};

Table created: products
```

- Types: `INT` (integer), `DECIMAL` (double precision), `STRING` (text),
  `BOOL` (boolean) — case-insensitive.
- Constraints: `primary_key`, `required` (NOT NULL), `unique`, and
  `default` with a literal of the column's own type (integer for `INT`,
  any number for `DECIMAL`, a string for `STRING`, `true`/`false` for
  `BOOL`). Lists, identifiers, and `null` defaults are rejected.
- Supported constraints are applied, never dropped: on the mock, omitted
  insert fields fall back to the defaults; on PostgreSQL, `DEFAULT`
  clauses do it server-side. On MongoDB, defaults are applied by INSERT
  only while that session's declared schema is available — they are not
  stored in the collection validator and therefore do not survive into a
  later session. An unsupported constraint name is a parse error —
  definitions never carry unknown names into an adapter.
- DDL cannot be chained with `.where()`/`.and()`/`.or()` or aggregates.

### plug.create.database(...) — create a database

```plug
plug.create.database("shop");
```

The mock validates the name and succeeds without adding state (its data
lives in one in-memory world). PostgreSQL opens a short-lived
administrative connection to the server's `postgres` database, runs
`CREATE DATABASE`, and reports duplicate names or insufficient
privileges clearly. The MongoDB adapter does **not** support this
operation: it connects to an existing database and rejects
`CREATE DATABASE` with `CREATE DATABASE is not supported by the MongoDB
adapter. Connect to an existing database.`

More schema operations (alter table, indexes, drop operations) are
planned and stay out of scope for now.

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

Whole numbers only; a decimal literal is rejected. `.sort` and `.limit`
are read features and only valid after `plug.get(...)`.

### .join(...) — combine collections

A read feature that chains after `plug.get(...)`, combining the selected
collection with one more collection. The join key is a comparison tuple
whose two sides are collection-qualified fields, matched with `EQ`:

```plug
plug.get(name, orders.total)
    .join(orders, on: (users.id, EQ, orders.user_id))
    .where((country, EQ, "Ghana"))
    .sort(orders.total, DESC)
    .limit(3);
```

Rules:

- The key tuple must use `EQ` and both sides must be qualified —
  `(id, EQ, orders.user_id)` is rejected, as is any other operator.
- Exactly one side of the key references the newly joined collection;
  the other references the base collection or an earlier join — either
  orientation works:

  ```plug
  .join(orders, on: (orders.user_id, EQ, users.id))
  ```

- Multiple joins chain left-to-right; a later join may key against any
  earlier collection. Joining the same collection twice, or the base
  collection with itself, is rejected.
- One side of the key must reference the joined collection and the other
  must not — a key like `(users.id, EQ, users.name)` is rejected.
- Inner-join semantics: an unmatched row on either side is dropped, and
  matches multiply (a user with three orders appears three times).
  `null` key values never match, SQL-style.
- `join()` is a read feature: invalid on `update`/`delete` chains and
  not combinable with aggregates (`count()` etc.).
- Key fields must exist in their collections and share one type
  (`Type mismatch in join key` otherwise).

### Dotted field names

Inside any parenthesized context, a field may be collection-qualified:
`orders.total`, `users.age`. This works in projections, condition
tuples, `.sort()`, aggregate fields, and join keys:

```plug
plug.get(name, orders.total)
    .join(orders, on: (users.id, EQ, orders.user_id))
    .where((orders.total, BETWEEN, [100, 500]));

plug.get(name).where((users.age, GT, 30));

plug.get(name).sort(users.balance, ASC);
```

Bare names still work and resolve in order: the base collection first,
then the joins in chain order. A bare name found in more than one joined
collection (but not the base) is ambiguous:

    Ambiguous field: product (inventory.product, orders.product)

Qualify such fields to disambiguate. Unqualified base names never
become ambiguous — bare `id` always means the base collection's `id`.
Unknown fields still fail type checking as before.

### Write operations

The write statements use the same grammar and run through the same
adapter `execute(plan)` contract as reads. Payloads are named
field/value pairs — string, number, boolean, or `null` values only.

#### plug.insert(...) — add one record

```plug
plug.insert(name: "Kwame", age: 22, country: "Ghana", role: "student", balance: 500, active: true, email: "kwame@gmail.com");

Records inserted: 1
```

- On the mock and PostgreSQL, an omitted `id` is auto-assigned (above
  the current maximum on the mock; by a `serial`/identity column on
  PostgreSQL). MongoDB requires an explicit, non-null primary key and
  does not generate integer ids. An explicit `id:` must not already
  exist (`Duplicate id: 1` otherwise).
- Unknown fields and type mismatches are rejected exactly like they
  are in `where()`.
- `insert()` cannot be chained with `.where()`/.and()/.or().

#### plug.update(...) — modify matching records

The payload names the fields to change; a `.where(...)` clause in the
same chain scopes the effect:

```plug
plug.update(balance: 1).where((id, LTE, 2));

Records updated: 2
```

`.where()`/`.and()`/`.or()` all work the same as in reads.

#### plug.delete() — remove matching records

Takes no arguments; a `.where(...)` clause in the same chain scopes
the effect:

```plug
plug.delete().where((country, EQ, "Kenya"));

Records deleted: 4
```

`update()` and `delete()` **require** at least one `where()` condition,
so a typo can never rewrite or wipe a whole collection. Count lines
reflect how many records the operation affected, and later statements
in the same program see the change.

### Aggregates

Terminal chained calls that fold the filtered records into one value
instead of returning rows. The query's conditions scope them:

```plug
plug.get(name).count();

COUNT = 20

plug.get().where((country, EQ, "Ghana")).count();

COUNT = 11

plug.get().sum(balance);

SUM(balance) = 590700

plug.get().avg(balance);

AVG(balance) = 29535.0

plug.get().min(age);

MIN(age) = 17

plug.get().max(balance);

MAX(balance) = 91000
```

Rules:

- `count()` takes no arguments. `sum(field)`, `avg(field)`, `min(field)`,
  and `max(field)` take one bare field name.
- `get()` may be argument-free in an aggregate statement — projecting
  fields has no effect on aggregates.
- An aggregate must be the **last** call in its chain, and cannot be
  combined with `.sort()`/`.limit()` or a write statement.
- `sum`/`avg` require a numeric field; `min`/`max` work on any comparable
  field (strings compare lexicographically).
- Null values are skipped. An empty match set is `COUNT = 0` but
  `null` for every other aggregate (SQL semantics).
- Unknown fields, non-numeric `sum`/`avg`, and non-comparable fields are
  rejected with the same schema validation as reads.

### Operators

| Operator              | Meaning                              |
|-----------------------|--------------------------------------|
| `EQ`                  | equality (matches `null` too)        |
| `NE`                  | inequality                           |
| `GT` `GTE` `LT` `LTE` | numeric comparison                   |
| `IN`                  | membership in a list                 |
| `CONTAINS`            | substring match on two strings       |
| `BETWEEN`             | inclusive range `[low, high]`; works on numbers and comparable strings |
| `LIKE`                | pattern match: `%` = any run of characters, `_` = exactly one character; the whole string must match and matching is case-sensitive |
| `IS NULL`             | true when the field holds `null` (takes no value)                     |
| `IS NOT NULL`         | inverse of `IS NULL`                                                  | 

```plug
.where((country, IN, ["Kenya", "Nigeria"]))
.where((email, EQ, null))
.where((name, CONTAINS, "li"))
.where((age, BETWEEN, [18, 25]))
.where((name, LIKE, "%@outlook.com"))
.where((email, IS NULL))
.where((email, IS NOT NULL));
```

`IS NULL` has two spellings — the keyword form `(email, IS NULL)`, the
negated keyword form `(email, IS NOT NULL)`, and the tuple form
`(email, IS, null)` — which also works in the flat chained form:
`.and(email, IS, null)`. Keyword forms normalize case-insensitively
(`is null`, `is not null`). Null fields never match `LIKE` (they are
simply skipped, SQL-style).

### Values

- Strings in `"double quotes"` with escapes: `\n`, `\t`, `\"`, `\\`.
- Numbers: integers (`20`, `-7`) and decimals (`3.14`).
- Booleans: `true`, `false`. Null: `null`.
- Lists: `[1, 2, 3]`.
- Bare identifiers name fields; inside any parenthesized context a
  field may be collection-qualified (`orders.total`).
- A collection name in `join()` is a bare identifier; the `on:`
  argument takes a `(left, EQ, right)` key tuple.

### Comments

```plug
# a comment to end of line
// also a comment to end of line
```

All failures surface as `PlugException`:

- Lex and parse errors carry line numbers (`line 3: Expected a value.`).
- Unknown fields, operators, or collections are runtime errors.
- Condition values and insert/update payloads are validated against the
  collection schema — a string compared to (or written into) an integer
  field fails type checking.

## PostgreSQL adapter

The first real backend. From the script's point of view nothing changes —
only `connect` gains a driver argument:

```plug
plug.connect(driver: "postgres", host: "localhost", port: 5432,
             database: "mydb", username: "victor", password: "secret");
plug.use("users");

plug.get(name, balance)
    .where((country, EQ, "Ghana"))
    .sort(balance, DESC)
    .limit(3);
```

`driver: "postgresql"` is accepted as a spelled-out alias.

Every statement — reads, joins, aggregates, writes — becomes a single
parameterized SQL statement executed through JDBC (the PostgreSQL driver
is the module's one transport dependency). The same script that runs on
the mock runs unchanged against PostgreSQL, and vice versa.

### Division of responsibility

The adapter does not re-implement the query language. Everything
query-shaped — field existence, type checking, join structure, and the
left-to-right condition folding — lives in the shared query model
(`QueryValidation` and `PostgresQueryCompiler`), next to the same logic
the mock uses. `PostgresDatabaseAdapter` itself owns only the JDBC
mechanics: opening the connection, introspecting schemas, binding
parameters, and mapping result rows back into Plug's Java types. The
core runtime (`Runtime`, `Query`, `ExecutionPlan`) never mentions
PostgreSQL — the adapter plugs in through the same three methods the
mock implements.

### Compilation to SQL

- A read compiles to one `SELECT` — projection, `JOIN ... ON`, `WHERE`,
  `ORDER BY`, and `LIMIT` in a single statement; joins become explicit
  `INNER JOIN`s keyed exactly as the `.join(on:)` tuple says.
- Aggregates map to `COUNT(*)`, `SUM(col)`, `AVG(col)`, `MIN(col)`,
  `MAX(col)`; a SQL `NULL` aggregate over an empty match set comes back
  as Plug's `null`.
- `insert` compiles to `INSERT INTO t (cols) VALUES (?)`: an omitted
  `id` is left out of the column list entirely, so a `serial`/
  identity-backed column fills it server-side; an explicit `id:` is
  duplicate-checked first and produces `Duplicate id: N` (also on a
  unique-violation race). `update` and `delete` keep the same
  require-`where()` rule the mock enforces, so unscoped writes stay
  impossible on a real server.
- Operator mapping: `EQ`/`NE` → `=`/`<>`, `GT`/`GTE`/`LT`/`LTE` →
  `>`/`>=`/`<`/`<=`, `IN` → `= ANY(?)`, `CONTAINS` → a `LIKE
  '%value%'` with `%`/`_`/`\` escaped, `BETWEEN` → `BETWEEN ? AND ?`,
  `LIKE` → `LIKE`, and `IS NULL`/`IS NOT NULL` map directly. `AND`,
  `OR`, and `NOT` compile with explicit parentheses so the folded left-
  to-right structure cannot be re-grouped by SQL's own precedence.
- All condition and payload values are bound as `?` parameters — script
  values are never interpolated into SQL text. Only identifiers reach
  the SQL string, and each is validated: a collection name must match
  `[a-z_][a-z0-9_]*` (PostgreSQL's unquoted lowercase convention), and
  column names are quoted only after being resolved against the
  introspected schema, so script input never becomes SQL syntax.

### Schema and types

On `plug.use(collection)` the adapter reads the table's columns from
`information_schema` and caches them; an unknown collection, unknown
field, or type mismatch fails exactly as it does on the mock. The tables
must exist beforehand — Plug queries, it does not migrate. Column types
map back into Plug's value types:

| PostgreSQL type                                | Plug type  |
|------------------------------------------------|------------|
| `smallint`, `integer`                          | number (integer) |
| `bigint`                                       | number (integer) |
| `real`, `double precision`, `numeric`, `decimal` | number (decimal) |
| `boolean`                                      | boolean    |
| `varchar`, `text`, `character`                 | string     |

Any other column type is rejected at `use` time. `sum` on an integer
column yields an integer, on a decimal column a decimal, mirroring the
mock's aggregate typing.

### Integration tests

Compiler behavior is covered by ordinary unit tests (`PostgresQueryCompilerTest`
asserts the generated SQL for operators, folding, joins, aggregates, and
writes — no server needed). Three end-to-end tests in
`PostgresAdapterIntegrationTest` run against a real server and skip
silently unless `PLUG_PG_HOST` is set (`PLUG_PG_PORT`, `PLUG_PG_DB`,
`PLUG_PG_USER`, `PLUG_PG_PASSWORD` override the defaults — the database
and its `users`, `orders`, and `inventory` tables with the mock's schemas
must exist):

```bash
PLUG_PG_HOST=localhost PLUG_PG_DB=plugdb \
PLUG_PG_USER=victor PLUG_PG_PASSWORD=secret mvn test
```

`examples/postgres.plug` is the adapter's smoke script.

## MongoDB adapter

`examples/mongo.plug` is the adapter's smoke script: it connects with
`driver: "mongo"`, creates two collections with generated validators and
unique partial indexes, selects them, runs INSERTs, two UPDATEs, and a
DELETE, prints an ordinary filtered read and an inner join, and runs
COUNT/SUM/AVG/MIN/MAX. Drop the created collections between runs
(`mongosh plugdb --eval '
db.mongo_people.drop(); db.mongo_orders.drop()'`).

The adapter is registered under `mongo` and `mongodb`. M0 delivered the
type contract, M1 added the connection lifecycle, M2 added the pure read
compiler, M3 added schema resolution, M4 added `CREATE TABLE`
(collection creation with a `$jsonSchema` validator and unique
indexes), M5 added INSERT/UPDATE/DELETE, M6 added joined reads
(`MongoJoinCompiler`/`MongoJoinExecutor`), M7 added aggregates
(`MongoAggregateCompiler`/`MongoAggregateExecutor`), and M8 added
ordinary reads (`MongoReadExecutor`).

```plug
plug.connect(driver: "mongo", host: "localhost", port: 27017,
             database: "plugdb", username: "", password: "");
plug.close();
```

- Port defaults to `5432` for every driver, so MongoDB scripts pass
  `port: 27017` explicitly.
- A blank username means an unauthenticated server: no credential is
  attached. A non-blank username builds a credential for the selected
  database, which is also its auth source (`ConnectionConfig` has no
  separate `authSource`).
- Client settings are built explicitly from the configured host and
  port, with 3-second server-selection, connect, and read timeouts,
  followed by a `ping` probe. Failures read
  `Cannot connect to MongoDB database '<db>' at <host>:<port>: ...`,
  matching the PostgreSQL adapter's message shape.
- The driver logs through the bundled SLF4J/Logback stack. The default
  configuration shows warnings and errors only, on stderr.
- `MongoQueryCompiler` translates a validated read into a MongoDB filter,
  projection, sort, and limit without touching a client. `EQ null` /
  `IS NULL` match only explicitly present nulls (never missing fields),
  `NE` excludes both null and missing, `IN` drops null items (empty or
  all-null matches nothing), and `BETWEEN` with a null bound matches
  nothing. `LIKE` is a whole-string, case-sensitive SQL-wildcard regex;
  `CONTAINS` is an escaped literal substring regex. Nested `AND`/`OR`/
  `NOT` maps to `$and`/`$or`/`$nor`, flat chains fold left-to-right, and
  `limit(0)` is encoded as a match-nothing filter because Mongo's own
  `limit(0)` means unbounded. Writes reuse `compileFilter`, a
  projection-free entry point with the same filter rules, and joined
  reads pass it a logical-to-physical path resolver.
- `MongoSchemaResolver` determines a collection's effective Plug schema
  in three tiers. A session-declared `TableDefinition` wins outright
  (constraints and defaults preserved, never merged with anything);
  otherwise a `$jsonSchema` validator is treated as the complete
  declared schema (`_id` is storage detail and not exposed); otherwise
  the schema is inferred from a fixed sample of 100 documents. Sampling
  drops `_id`, unsupported BSON types, only-null fields, mixed-type
  fields, and numerics that `MongoTypeMapping` cannot represent
  exactly — unknown fields stay unknown. Declared and inferred schemas
  are cached per session; declaring a schema discards any earlier
  inference for that collection, and `close()` clears all of it.
  Malformed validators raise `Malformed $jsonSchema validator ...`,
  missing collections raise `Unknown collection: <name>`, and driver
  failures are translated to `Cannot inspect`/`Cannot read MongoDB
  collection ...`. The resolver backs DDL, writes, and every read shape.
- `CREATE TABLE` now runs on MongoDB. `MongoSchemaCompiler` turns the
  validated `TableDefinition` into a strict, error-action `$jsonSchema`
  validator (`INT`→`int`, `DECIMAL`→`double`, `STRING`→`string`,
  `BOOL`→`bool`, `additionalProperties: false`, `_id` admitted as
  `objectId`) and unique indexes for `PRIMARY_KEY`/`UNIQUE`. Optional
  fields also admit `null`, matching Plug's nullable-by-default
  contract; `REQUIRED` and `PRIMARY_KEY` fields do not. Unique indexes
  are partial on `$type`, so null and missing values never collide
  (numeric fields cover `int`/`long` and `double`/`decimal`).
  `DEFAULT` is session metadata only: MongoDB validators cannot apply
  defaults, so none is written, and the declared definition (with its
  defaults) is registered in `MongoSchemaResolver` for INSERT. Defaults
  therefore apply only while the declaring session is open; an INSERT
  into the same collection from a later session resolves the validator
  (which cannot carry defaults), so an omitted optional field becomes
  null. Declaring a field named `_id` is rejected. Creation never
  overwrites an existing collection, a failed index build drops the
  new collection best-effort, and the schema is registered only after
  every step succeeds. `CREATE DATABASE` is deliberately unsupported by
  this adapter; connect to an existing database instead.
- INSERT, UPDATE, and DELETE now run through `MongoWriteExecutor`.
  Every write resolves the effective M3 schema and reuses
  `QueryValidation`, so unknown fields and type mismatches fail before
  the driver. INSERT applies declared defaults (only for fields the
  caller omitted), requires an explicit non-null primary key, enforces
  required fields after defaults, and stores every other omitted schema
  field as an explicit BSON null so Plug's null never means "missing".
  MongoDB does **not** auto-generate integer ids, so `insert` without a
  primary key fails with `Missing primary key: <field>` instead of
  assigning one. Explicit values and nulls are never replaced by
  defaults. UPDATE sets only the supplied fields (it never applies
  insert defaults) and returns the number of matched documents; DELETE
  returns the deleted count. Both compile their WHERE through
  `MongoQueryCompiler.compileFilter` and defensively reject a
  conditionless plan, even though `Runtime` already rejects one. Driver
  duplicate-key errors are translated to a duplicate-unique-constraint
  error, and other failures become `INSERT/UPDATE/DELETE failed: ...`;
  a missing connection reports `No database connection.` INSERT is
  single-document, matching the execution plan shape.
- Joined reads now run through `MongoJoinCompiler` and
  `MongoJoinExecutor`. Plug's existing joins are inner joins: each
  joined collection becomes a pipeline-form `$lookup` inside a
  `let`/`$expr`, followed by `$unwind`, so a non-match disappears like a
  SQL inner join. Both key sides are guarded against null and missing
  before equality, because `$lookup` would otherwise match them; this
  matches mock/PostgreSQL semantics. All joins run before one combined
  `$match` produced by the M2 compiler, so AND/OR/NOT and left-to-right
  folding see complete rows and no predicate is pushed into a lookup.
  `$project`, `$sort`, and `$limit` follow; the sort key is retained by
  the projection, and `limit(0)` uses an always-false match because
  aggregation `$limit` must be positive. Joined collections nest under
  collision-resistant internal aliases (`__join_0`, `__join_1`, ...),
  never overwriting a user field and never appearing in results.
  Logical qualified names map back to physical paths (`users.id` →
  `id`, `orders.total` → `__join_0.total`); rows are materialized with
  the caller's exact spelling and order, base-first bare resolution,
  first-write-wins duplicate bare names, and `MongoTypeMapping`'s exact
  decoding. Unknown fields, ambiguous bare conditions, join type
  mismatches, duplicate joined collections, and aggregate-plus-join
  requests fail with the established Plug errors. Multiple sequential
  joins and joined sort/limit are supported.
- Aggregates now run through `MongoAggregateCompiler` and
  `MongoAggregateExecutor`. A valid aggregate has one collection (the
  runtime and the compiler both reject aggregate-plus-join and
  aggregate-plus-sort/limit), and its pipeline is one combined WHERE
  `$match` from the M2 compiler followed by `$group` with `_id: null`.
  COUNT uses `$sum: 1`; SUM/AVG/MIN/MAX use their Mongo accumulators on
  the single field, so WHERE always scopes the folded inputs. Empty
  matches return `0` for COUNT and `null` for every other aggregate.
  SUM additionally records a count of non-null numeric inputs: MongoDB
  returns `0` when all matched values are null/missing, while Plug's
  SQL-style result is `null`, and the count distinguishes that from a
  real sum of zero. Integer-field sums stay `Integer` and fail on int
  overflow; AVG is always `Double`; MIN/MAX keep the field's Plug type.
  Every returned value goes through `MongoTypeMapping`'s exact
  conversion, so an unrepresentable or incompatible BSON value raises
  an explicit error rather than being rounded. Aggregate results are
  `QueryResult.aggregateValue` with no rows; non-COUNT results carry a
  zero `count`, matching PostgreSQL.
- Ordinary reads now run through `MongoReadExecutor`. It resolves the
  effective M3 schema, applies `QueryValidation.validateWhere`, and uses
  `MongoQueryCompiler.compileRead` for the filter, projection, sort, and
  limit. A real `find()` executes those pieces; `limit(0)` returns no
  rows before the driver is touched. Rows are rebuilt as ordered
  `LinkedHashMap`s using the caller's field names and decoded through
  `MongoTypeMapping`, so explicit nulls and missing projected fields both
  appear as null and numeric overflow/inexactness still fails closed.
  Driver failures become `Cannot read MongoDB collection '<name>': ...`.
- `close()` closes the client and is safe to call twice.
- `CREATE DATABASE` fails with `CREATE DATABASE is not supported by the
  MongoDB adapter. Connect to an existing database.` Other schema
  operations report that they are not supported yet; none returns empty
  rows or placeholder success.

Mapping rules:

- `Int32` → `Integer`; `Int64` → `Integer` while every value fits the
  int range, `Double` only while every value is exactly representable
  (`|v| <= 2^53`), otherwise the field is omitted.
- `Decimal128` → `Double` only when the decimal round trips exactly;
  inexact values are omitted. `string` → `String`, `bool` → `Boolean`.
- Unsupported BSON types (nested documents, arrays, `ObjectId`, dates,
  binary, regex, ...) and mixed families are omitted.
- Decoding fails closed: once a schema type is established, an
  out-of-range or inexact stored value raises `PlugException` naming
  the field, value, and stored class — never a rounded value.
- Null stays null for every type; nullability is enforced by the schema,
  not by the conversion.

`MongoTypeMappingTest` covers these rules with 22 server-free unit
tests (integer boundaries, 2^53, Decimal128 exactness, unsupported and
mixed samples). The M1 suites (`MongoAdapterRegistrationTest`,
`MongoConnectionSettingsTest`, 11 tests) cover both aliases, settings
and credential construction, timeouts, the unreachable-server error,
unsupported operations, and close idempotence — all without a live
server. `MongoQueryCompilerTest` (28 tests) evaluates compiled filters
against documents with MongoDB's matching rules, covering every
operator, explicit-null versus missing fields, null-containing `IN`,
null `BETWEEN` bounds, LIKE/CONTAINS escaping and Unicode, nested logic,
left-to-right flat folds, projection, sorting, and limits.
`MongoSchemaResolverTest` (48 tests) covers resolution precedence,
validator types/required fields/`_id`/malformed input, the 100-document
sample bound, mixed and missing fields, BSON type and numeric
representability, per-collection caching, declaration invalidation,
clearing on close, isolated resolver instances, and translated errors —
all server-free through a collection-source double. `MongoSchemaCompilerTest`
(17 tests) covers every validator type, nullability, required fields,
`additionalProperties`, `_id`, defaults being metadata-only, and the
unique partial-index definitions. `MongoSchemaWriterTest` (19 tests)
covers collection creation with the validator attached, duplicate
collection rejection, translated creation/index/duplicate-key errors,
cleanup after a failed index build, registration only on success,
preserved defaults, independent collections, validator round-tripping
through M3, and adapter wiring. `MongoWriteExecutorTest` (29 tests) uses
a recording store and real schema resolution to cover INSERT
defaults/explicit nulls/required/primary-key/duplicate/type/translated
errors, UPDATE field selection, null preservation, duplicates, missing
WHERE, filter semantics and counts, DELETE counts, logical and null
filters, missing WHERE, translated errors, and adapter dispatch/close —
all server-free. `MongoJoinCompilerTest` (20 tests) covers pipeline
stage order, both-side null/missing guards, one combined post-join
`$match`, two and three sequential joins, alias collisions, projection
resolution and ambiguity rules, sort/limit placement, `limit(0)`, and
validation errors. `MongoJoinExecutorTest` (10 tests) covers requested
name/order materialization, first-write-wins bare projection, exact type
decoding, missing values, translated driver failures, aggregate-plus-join
rejection, adapter dispatch, and close cleanup. `MongoJoinIntegrationTest`
runs only with `PLUG_MONGO_HOST` set and verifies against a live server
that null/missing keys on both sides do not match, inner joins drop
non-matches, and a combined WHERE filters after joining.
`MongoReadExecutorTest` (19 tests) covers basic reads, WHERE compilation,
explicit-null and missing-field materialization, projection order,
ascending/descending sort, positive limits, `limit(0)` without a driver
call, empty collections, unknown projection/WHERE/sort fields, invalid
plans, exact numeric conversion, ordered rows, translated driver
failures, and adapter dispatch/close. `MongoReadIntegrationTest` runs
only with `PLUG_MONGO_HOST` set and verifies actual `find()` filtering,
projection, server-side sort/limit, explicit-null versus missing fields,
`limit(0)`, and empty results.

`MongoAggregateCompilerTest` (12 tests) covers `$match`-then-`$group`
stage order, accumulator shape, the SUM value count, shared logical
WHERE folding, projection-independent COUNT, aggregate validation, and
the aggregate-plus-join/sort/limit contract. `MongoAggregateExecutorTest`
(20 tests) covers COUNT, empty/all-null results, integer and Double
conversion, int overflow, inexact Decimal128 rejection, MIN/MAX typing,
translated driver failures, malformed multi-document results, adapter
dispatch, and close cleanup. `MongoAggregateIntegrationTest` runs only
with `PLUG_MONGO_HOST` set and verifies live that WHERE precedes the
fold, null and missing inputs are skipped, an all-null SUM is null, and
COUNT over no matches is zero. The full architecture, milestones, and
documented limitations live in `../MONGODB_ADAPTER_PLAN.html`.

## Architecture

    .plug file → Lexer (+ line numbers) → Parser → AST
               → Runtime → Query → QueryPlanner → ExecutionPlan
               → ExecutionEngine → DatabaseAdapter → QueryResult

- `ExecutionPlan` is a single unit of work: a `READ`, `INSERT`,
  `UPDATE`, or `DELETE` operation over a collection, with the query
  conditions and (for writes) the field/value data.

- `DatabaseAdapter` is the backend contract: `connect`, `execute(plan) →
  QueryResult`, `close`. Adapters return structured results and never print.
- `DatabaseAdapterRegistry` maps driver names to adapter factories
  (`mock`, `postgres`, `postgresql`, `mongo`, `mongodb` today).
- `MongoTypeMapping` is the MongoDB conversion layer (M0): pure
  functions, exact-only conversions, no client or server dependency.
- The mock adapter implements in-memory filtering, sorting, limiting,
  joins (nested-loop inner joins over its sample collections), and
  schema/type validation; it is the reference implementation for semantics.
- The PostgreSQL adapter compiles plans to parameterized SQL through
  `PostgresQueryCompiler`, shares all schema/type/join validation with
  the mock via `QueryValidation`, and owns only the JDBC mechanics.
- The MongoDB adapter executes DDL, writes, and all read shapes;
  `MongoQueryCompiler` compiles validated ordinary reads to
  filter/projection/sort/limit documents (and write WHERE clauses to
  projection-free filters) using the same shared validation and
  left-to-right contract, `MongoSchemaResolver` supplies the collection
  schema from a session declaration, the `$jsonSchema` validator, or a
  bounded sample, `MongoSchemaWriter` executes `CREATE TABLE` through
  `MongoSchemaCompiler` (validator and unique indexes),
  `MongoWriteExecutor` executes INSERT/UPDATE/DELETE against that schema,
  `MongoReadExecutor` runs ordinary `find()` reads and materializes
  ordered rows, `MongoJoinCompiler`/`MongoJoinExecutor` translate inner
  joins into `$lookup`/`$unwind` stages and materialize the requested
  fields, and `MongoAggregateCompiler`/`MongoAggregateExecutor` fold a
  filtered collection with one `$group` and exact result decoding.
- DDL is typed end to end: `TableDefinition`/`FieldDefinition` in the
  AST, semantic checks in `SchemaValidation`, a `SchemaPlan` through
  `QueryPlanner.planSchema` and `ExecutionEngine.executeSchema`, and a
  separate adapter contract method (`executeSchema`) — never squeezed
  into the CRUD plan shape. SQL comes from `PostgresSchemaCompiler`.

## Roadmap

1. Complete the prototype language. Aggregates
   (count/sum/avg/min/max), insert/update/delete, the operator set
   (EQ, NE, GT, GTE, LT, LTE, IN, CONTAINS, BETWEEN, LIKE, IS NULL/
   IS NOT NULL), and joins (`.join(collection, on: ...)`) are done.
2. Real adapters: PostgreSQL is done (parameterized SQL over JDBC,
   integration tests gated on `PLUG_PG_HOST`). MongoDB M0 (driver plus
   exact BSON↔Plug type mapping), M1 (registered `mongo`/`mongodb`
   connection lifecycle), M2 (`MongoQueryCompiler` filters, projection,
   sorting, limits), M3 (`MongoSchemaResolver` precedence, validator
   parsing, bounded sampling, caching), M4 (`CREATE TABLE` with a
   `$jsonSchema` validator and unique partial indexes), M5
   (INSERT/UPDATE/DELETE through `MongoWriteExecutor`), M6 (joined
   reads through `MongoJoinCompiler`/`MongoJoinExecutor`, with
   `$lookup`/`$unwind`, null/missing key guards, and a combined post-join
   filter), M7 (COUNT/SUM/AVG/MIN/MAX through
   `MongoAggregateCompiler`/`MongoAggregateExecutor`, with one WHERE
   `$match`, `$group`, SQL empty/null semantics, and exact numeric
   results), and M8 (ordinary reads through `MongoReadExecutor`) are
   done. MongoDB explicitly requires primary keys on INSERT, rejects
   `CREATE DATABASE`, and keeps defaults only in session-declared
   schemas; these limitations are intentional and documented above.
3. Foundational DDL is done: `plug.create.table(...)` compiles and
   runs on both adapters. `plug.create.database(...)` runs on the mock
   and PostgreSQL; the MongoDB adapter rejects it and connects to an
   existing database instead. Alter/index/drop are the next schema
   features.
4. PlugDB — a custom database using Plug as its query language.
5. Adapters for more database categories.
