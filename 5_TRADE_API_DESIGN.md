# 5. Trade Execution API — Design & Decisions

This document records the implementation decisions and assumptions for the `POST /api/trades/execute` endpoint. It is a new file and intentionally does not modify the provided assessment documents.

## 1. API contract

### Request (`TradeRequest`)
| Field | Type | Rules | Notes |
|---|---|---|---|
| `userId` | Long | Required | Assumed pre-authenticated (per business requirements) |
| `tradeType` | `BUY` / `SELL` | Required | Market orders only; limit/stop orders are explicitly out of scope |
| `pairName` | String | Required, case-insensitive | e.g. `BTCUSDT` (normalized to uppercase before lookup) |
| `quantity` | BigDecimal | Required, > 0, max 8 decimal places | Always denominated in the pair's **base** currency (BTC for BTCUSDT, ETH for ETHUSDT) |

### Response (`TradeResponse`)
`tradeId`, `pairName`, `tradeType`, `quantity`, `price`, `totalAmount`, `tradeTime`.

## 2. Pricing model (decisions & rationale)

- **Market-order semantics.** The user chooses pair, direction and quantity only; the system fills at the current aggregated market price. This follows the business requirements ("All trades must be executed at current market prices"; limit/stop-loss are out of scope).
- **BUY fills at the best ask, SELL at the best bid** — the two sides of the stored `crypto_prices` row, which the scheduler aggregates (highest bid across exchanges, lowest ask across exchanges, possibly from different sources).
- **Broker-style execution, no order book.** The system stores one best bid/ask row per pair (per scheduler cycle) and implicit infinite liquidity is assumed — the requested quantity is always fully filled at the displayed price, with no depth-based slippage. This mirrors the provided architecture (Binance `bookTicker` / Huobi `tickers` expose only best levels) and the seed-data trade history (all trades quote the base asset in USDT).
- **`quantity` in base currency for both directions** (assumption, documented): the alternative — BUY by "USDT spend amount" — is ambiguous for fractional fills and not derivable from the requirements. Base-quantity semantics make `totalAmount = quantity × price` symmetric and match the seed data, where both BUY and SELL entries move the same base asset.
- **No fees.** Explicitly out of scope; therefore `totalAmount` is exactly `quantity × price` (no rounding strategy that favors the platform).

## 3. Numbers, precision & timestamps

- All money/quantity values are `BigDecimal` mapped to `DECIMAL(20,8)` columns end-to-end; no floating point is used anywhere.
- `totalAmount = quantity × price` is computed exactly and then rescaled to 8 decimal places with `RoundingMode.HALF_UP` to fit the column. At the documented trade sizes the half-of-8th-decimal residue is negligible; a single rule is applied for both directions.
- `tradeTime` is stamped by the application (`LocalDateTime.now()` at execution) and written explicitly, so the persisted audit row and the API response carry identical timestamps.

## 4. Concurrency & data consistency

- The whole execution runs inside one `@Transactional` method: wallets are mutated in canonical ascending-`symbol_id` order, then the trade is inserted. Any failure rolls everything back (no partial wallet mutations, no orphan ledger entries).
- **Deadlock prevention (canonical lock ordering):** opposite directions of the same pair touch the same two wallet rows in opposite orders — BUY would lock quote→base (pay USDT, receive BTC), SELL base→quote (deliver BTC, receive USDT). Run concurrently for the same user they deadlock; H2's deadlock detector confirmed this under the 100-thread mixed BUY/SELL scenario. The engine therefore always mutates wallet rows in ascending `symbol_id` order regardless of direction. Within the transaction the operation order is semantically neutral: any failure rolls back everything, so fallback ordering never trades correctness for lock safety.
- **Race-safe balance check:** the debit is a single guarded SQL statement —
  `UPDATE user_wallets SET balance = balance - ? WHERE user_id = ? AND symbol_id = ? AND balance >= ?`
  — whose affected-row count decides success. A read-then-write check in the application layer would be vulnerable to lost updates under the default READ COMMITTED isolation (two concurrent SELLs of the same balance can both pass); the guarded UPDATE makes the check-and-reserve atomic without needing SERIALIZABLE isolation.
- The credit side needs no guard (balances can grow freely).
- **Lazy wallet creation:** a wallet row exists only once a user acquires a symbol (per business requirements). On credit, the row is created with `balance = 0` via a conditional single-statement `INSERT ... SELECT ... WHERE NOT EXISTS`, leaning on the `UNIQUE(user_id, symbol_id)` constraint so concurrent first-acquisitions are safe. Transactions that lose the unique-key race to an insert committed concurrently simply observe that the row now exists and proceed to credit it. A debit against a missing row correctly reports an insufficient balance (a missing wallet means nothing was ever held).
- **Verified under load:** `TradeExecutionConcurrencyTest` (integration test, real H2, 100 concurrent trades per scenario via virtual threads, 100-connection Hikari pool so every thread contends directly on the database) deterministically proves the consistency guarantees: balances are never over-drawn (exactly the affordable number of fills succeed), per-user and per-symbol ledgers land on exact expected values, and concurrent lazy wallet creation always yields exactly one wallet row. A fully funded scenario verifies 100% fill with an average per-trade latency bound. The test keeps H2's default 1s lock timeout deliberately as a speed tripwire — a lock timeout fails the test, since it would mean the engine is too slow under contention.
- **Partial-failure atomicity proven by fault injection:** `TradeExecutionRollbackTest` (integration test, real H2, `@MockitoSpyBean` on `TradeMapper`) throws from the trade-ledger insert — the *last* step, after wallet mutations have already executed inside the transaction — and asserts the end-state for both directions: the in-tx debit and credit are fully rolled back (balances exactly as seeded, no trades row), and even the lazy wallet-row creation performed moments earlier is undone. A follow-up test resets the fault and reruns the identical request, proving a failed attempt leaves zero residue and the same trade then commits exactly once.
- **Production note (not implemented):** the endpoint is not idempotent. A real API should carry an idempotency key (client-generated, unique per trade intent) so retries of the same request cannot double-execute; the `trades` table would gain a unique column for it. Out of scope for this assessment.

## 5. Error handling

All failures return a consistent JSON body (`ErrorResponse`) via `GlobalExceptionHandler`:

| Condition | Exception | HTTP |
|---|---|---|
| Malformed request body / fields missing / negative or zero quantity | Bean validation or `InvalidTradeRequestException` | 400 |
| Unknown `userId` | `UserNotFoundException` | 404 |
| Unknown / inactive `pairName` | `CryptoPairNotFoundException` | 404 |
| Insufficient balance (either side) | `InsufficientBalanceException` | 400 |
| No price stored yet for the pair (e.g. before the first scheduler tick) | `PriceNotAvailableException` | 503 |
| Unexpected server error | — | 500 |

## 6. Scheduler observations (pre-existing code)

Two behaviors in `PriceUpdateScheduler` were examined closely:

1. **`storePriceHistory()` bid/ask swap** — the method receives `(bidPrice, askPrice, bidSource, askSource)` but wrote each argument into the opposite column. Since trades price off these stored rows, this made every stored price row self-inverted (and, with the second bug, attributed to the wrong pair). Treated as a bug and fixed in place.
2. **`getCryptoPairId()` pair-name swap** (`BTCUSDT` ↔ `ETHUSDT`). The original code has been preserved as a comment: given that the requirements only ever support these two pairs, the swap plausibly reads as intentional behavior rather than a bug. The corrected resolution (no swap) is used instead, so a stored price row is attributed to the pair it was quoted for.
3. **`SchedulerConfig` never enabled scheduling** — the annotation `@EnableScheduling` was imported but not applied, so `@Scheduled` was inert and price rows were never refreshed beyond the Flyway seed data. Added the annotation on the config class.

## 7. Other assumptions

- **Price freshness via cache:** the trade path reads prices from `LatestPriceCache`, which is only updated by the scheduler tick (and hydrated from the DB on first access after a cold start). Missing-price (503) applies when neither cache nor DB has a row. This is the same tick-freshness semantics as reading the table directly, minus one query per trade.

## 8. Latency work (measured)

Sequence of committed optimizations, measured with the class-only benchmark
(`mvn test -Dtest=TradeExecutionConcurrencyTest`, 100 fully-funded trades under
100-way contention, median of 4 runs):

| State | avg | p50 | p99 |
|---|---|---|---|
| Baseline (full engine + tests, no latency work) | 5.9 ms | 3 ms | 37 ms |
| + price lookup index | 10.6 ms | 3 ms | 74 ms |
| + Hikari pool sized to 100 | 8.3 ms | 3 ms | 61 ms |
| + `SELECT EXISTS` user probe | 8.4 ms | 3 ms | 59 ms |
| + in-memory latest-price cache | 4.9 ms | 3 ms | 23 ms |
| + virtual threads (final) | **3.8 ms** | **2 ms** | **21 ms** |

**Net: avg −36%, p50 −33%, p99 −43%.**

Honest notes from this exercise:

- **The index is only worthwhile for larger datasets.** With `crypto_prices` at the seed scale (a handful of rows per pair), a plain scan lookup without the index performs *faster* — re-measured with all other improvements in place: no-index avg 3.57 ms / p50 2 ms / p99 17 ms (median of 5, one environmental outlier excluded) vs 3.8 / 2 / 21 with the index. The migration is kept because its value is the O(log n) growth behavior on a table that gains ~17k rows/day, and it costs nothing on the trade hot path: the latest-price cache removes per-trade price reads anyway, so the index only serves the once-per-cold-start hydrate and direct `crypto_prices` queries. The other improvements (pool sizing, `EXISTS` probe, price cache, virtual threads) are index-independent and improve either way.
- Intermediate states (index through `EXISTS`) measured slower than baseline while retaining the per-trade DB price read; the cache is what removes that path.
- Benchmark pitfalls found along the way: uniform ~30 ms per-trade elevation appears when background load (Docker VM, Spotlight) saturates the box, and full-suite runs share the JVM with the `contextLoads` context whose **live scheduler** performs Binance/Huobi HTTP + inserts during the benchmark — class-only runs are the clean measurement. Any per-code-state A/B here needs interleaved runs; single-run contrasts were misleading twice.
- The debit/credit statement pair, guarded-debit design and canonical lock ordering were deliberately left alone: at this scale their statement cost is dominated by commit contention, and collapsing them would trade demonstrable correctness for ~1 statement. H2's default 1s lock timeout kept as the tripwire.

## 9. Remaining assumptions (carried from the original list)

- **Case handling:** `pairName` is trimmed and upper-cased before lookup; symbols are resolved from the database (base/quote ids come from a join on `crypto_pairs`/`symbols`), not from parsing strings.
- **User existence:** although users are assumed pre-authenticated, an unknown `userId` is still rejected (404) since nothing guarantees callers only send seeded ids.
- **Only BUY/SELL are accepted** (enforced by the `TradeType` enum and the DB `CHECK` constraint).
