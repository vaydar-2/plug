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

- An omitted `id` is auto-assigned above the current maximum; an
  explicit `id:` must not already exist (`Duplicate id: 1` otherwise).
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

## Architecture

    .plug file → Lexer (+ line numbers) → Parser → AST
               → Runtime → Query → QueryPlanner → ExecutionPlan
               → ExecutionEngine → DatabaseAdapter → QueryResult

- `ExecutionPlan` is a single unit of work: a `READ`, `INSERT`,
  `UPDATE`, or `DELETE` operation over a collection, with the query
  conditions and (for writes) the field/value data.

- `DatabaseAdapter` is the backend contract: `connect`, `execute(plan) →
  QueryResult`, `close`. Adapters return structured results and never print.
- `DatabaseAdapterRegistry` maps driver names to adapter factories.
- The mock adapter implements in-memory filtering, sorting, limiting,
  joins (nested-loop inner joins over its sample collections), and
  schema/type validation; it is the reference implementation for semantics.

## Roadmap

1. Complete the prototype language. Aggregates
   (count/sum/avg/min/max), insert/update/delete, the operator set
   (EQ, NE, GT, GTE, LT, LTE, IN, CONTAINS, BETWEEN, LIKE, IS NULL/
   IS NOT NULL), and joins (`.join(collection, on: ...)`) are done.
2. Real adapters, in order: PostgreSQL, MongoDB.
3. PlugDB — a custom database using Plug as its query language.
4. Adapters for more database categories.
